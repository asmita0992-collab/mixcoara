#!/usr/bin/env python3

"""
1.3.0
mixcoara_nube.py - Bóveda compartida cifrada sobre WebDAV (sin curl), con progreso.

Requiere: pycryptodomex  y  mixcoara_claves.py en la misma carpeta.

Estructura en la nube:
Boveda/
├── masterkey.vaultdav
└── d/
    ├── <uuid_raiz>/        dir.c9r + <nombre cifrado SIV>.c9r (contiene el uuid del hijo)
    └── <uuid_archivo>/     dir.c9r + <uuid_archivo>.bin (AES-256-GCM, bloques de 64 KB)
"""

import io
import os
import sys
import json
import time
import uuid
import base64
import shutil
import getpass
import threading
import requests
from concurrent.futures import ThreadPoolExecutor
from requests.auth import HTTPBasicAuth
from requests.utils import quote, unquote, urlparse
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Callable, List, Optional, Tuple

from Cryptodome.Cipher import AES
from Cryptodome.Random import get_random_bytes

import mixcoara_claves as mc
import mixcoara_config as cfgm
import mixcoara_registro as reg
from mixcoara_claves import ErrorBoveda, Llaves

VAULT_DIR = "Mixcodir"

# Número de archivos que se cifran y suben en paralelo.
# 3-4 es un buen punto de partida; auméntalo si el servidor lo soporta.
WORKERS_SUBIDA = 3


# ===========================================================================
# Certificados TLS: ruta estable (certifi crea un temporal que Android puede borrar)
# ===========================================================================

def asegurar_certificados(forzar: bool = False) -> bool:
    """Garantiza que REQUESTS_CA_BUNDLE apunte a un archivo que existe.

    Devuelve False (y lo registra) si no se pudo preparar. Es barata: si el archivo
    ya existe, solo comprueba eso. Se llama antes de cada transferencia y de cada petición.
    """
    try:
        if not cfgm.es_android():
            return True
        actual = os.environ.get("REQUESTS_CA_BUNDLE", "")
        if not forzar and actual and os.path.isfile(actual) and os.path.getsize(actual) > 1000:
            return True

        origen = None
        try:
            import certifi
            p = Path(certifi.where())
            if p.is_file():
                origen = p
        except Exception as ex:
            reg.aviso("certificados_certifi_no_disponible", causa=f"{type(ex).__name__}: {ex}")

        candidatos = [cfgm.dir_datos_anterior()]
        try:
            candidatos.append(cfgm.dir_privado())
        except OSError:
            pass
        errores = []
        for d in candidatos:
            destino = d / "cacert.pem"
            try:
                d.mkdir(parents=True, exist_ok=True)
                vigente = destino.is_file() and destino.stat().st_size > 1000
                if origen is not None and (not vigente or origen.stat().st_mtime > destino.stat().st_mtime):
                    shutil.copy2(origen, destino)
                    vigente = destino.is_file() and destino.stat().st_size > 1000
                if vigente:
                    os.environ["REQUESTS_CA_BUNDLE"] = str(destino)
                    os.environ["SSL_CERT_FILE"] = str(destino)
                    reg.info("certificados_listos", ruta=destino)
                    return True
                errores.append(f"{destino}: sin copia válida y sin certifi de origen")
            except Exception as ex:
                errores.append(f"{destino}: {type(ex).__name__}: {ex}")
        reg.error("certificados_fallo", detalle=" | ".join(errores) or "sin candidatos")
        return False
    except Exception as ex:
        reg.error("certificados_fallo_inesperado", exc=ex)
        return False


asegurar_certificados()
CHUNK_SIZE = 64 * 1024
TAM_PARTE_BYTES = 25 * 1024 * 1024  # 25 MB para multipart en RAM
GCM_NONCE_SIZE = 12
GCM_TAG_SIZE = 16
MAX_NOMBRE_CIFRADO = 250
REINTENTOS = 3

# ---------------------------------------------------------------------------
# Error criptográfico: thread-local para que subidas paralelas no se pisen.
# ---------------------------------------------------------------------------
_tl = threading.local()


def _get_error_cripto() -> str:
    return getattr(_tl, "cripto", "")


def _set_error_cripto(val: str):
    global ULTIMO_ERROR_CRIPTO
    _tl.cripto = val
    ULTIMO_ERROR_CRIPTO = val


ULTIMO_ERROR_CRIPTO = ""


def directorio_mixcoara() -> Path:
    """Carpeta visible y estable para salidas y temporales controlados."""
    configurada = os.environ.get("MIXCOARA_DOWNLOAD_DIR")
    raiz_android = Path(configurada) if configurada else Path("/storage/emulated/0/Download")
    base = raiz_android if configurada or raiz_android.parent.is_dir() else Path.home() / "Downloads"
    destino = base / "Mixcoara"
    destino.mkdir(parents=True, exist_ok=True)
    return destino


def directorio_temporal() -> Path:
    destino = directorio_mixcoara() / ".temporales"
    destino.mkdir(parents=True, exist_ok=True)
    for residuo in destino.iterdir():
        try:
            if residuo.is_file() and time.time() - residuo.stat().st_mtime > 24 * 3600:
                residuo.unlink()
        except OSError:
            pass
    return destino


def limpiar_temporales() -> Tuple[int, int]:
    """Borra TODO lo que haya en .temporales. Solo debe llamarse cuando no hay una tarea en curso
    (al abrir Mixcoara y al terminar o fallar una tarea). Devuelve (archivos, bytes)."""
    cuantos, tam = 0, 0
    try:
        base = directorio_mixcoara() / ".temporales"
        if not base.is_dir():
            return 0, 0
        for residuo in base.iterdir():
            try:
                if residuo.is_file() or residuo.is_symlink():
                    tam += residuo.stat().st_size if residuo.is_file() else 0
                    residuo.unlink()
                    cuantos += 1
                elif residuo.is_dir():
                    shutil.rmtree(residuo, ignore_errors=True)
            except OSError as ex:
                reg.aviso("temporal_no_borrado", archivo=residuo, causa=f"{type(ex).__name__}: {ex}")
        if cuantos:
            reg.info("temporales_limpiados", archivos=cuantos, bytes=tam)
    except Exception as ex:
        reg.error("temporales_limpieza_fallo", exc=ex)
    return cuantos, tam


class ErrorRed(Exception):
    pass


# ===========================================================================
# Progreso (la misma clase servirá para la barra de consola y para Flet)
# ===========================================================================
def fmt_bytes(n: float) -> str:
    n = float(n)
    for unidad in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024 or unidad == "TB":
            return f"{n:.0f} {unidad}" if unidad == "B" else f"{n:.1f} {unidad}"
        n /= 1024
    return f"{n:.1f} TB"


def fmt_tiempo(seg: float) -> str:
    seg = int(max(0, seg))
    h, r = divmod(seg, 3600)
    m, s = divmod(r, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


class Progreso:
    """Lleva los contadores de una fase y separa el tiempo de preparación/cifrado del tiempo de subida."""

    def __init__(self, al_cambiar: Optional[Callable[["Progreso"], None]] = None):
        self.al_cambiar = al_cambiar
        self._ult = 0.0
        self._tiempo_lock = threading.Lock()
        self.segundos_preparacion = 0.0
        self.t_inicio_subida: Optional[float] = None
        self.segundos_validacion = 0.0
        self.iniciar("")

    def iniciar(self, fase: str, archivos: int = 0, carpetas: int = 0, bytes_: int = 0):
        self.fase = fase
        self.archivos_total, self.archivos_hechos = archivos, 0
        self.carpetas_total, self.carpetas_hechas = carpetas, 0
        self.bytes_total, self.bytes_hechos = bytes_, 0
        self.estado, self.actual = "", ""
        self.t0 = time.monotonic()
        self.t_inicio_subida = None
        self._muestras = [(self.t0, 0)]
        self.refrescar(True)

    def sumar_preparacion(self, seg: float):
        if seg > 0:
            with self._tiempo_lock:
                self.segundos_preparacion += seg
        self.refrescar()

    def marcar_inicio_subida(self):
        with self._tiempo_lock:
            if self.t_inicio_subida is None:
                self.t_inicio_subida = time.monotonic()

    def transcurrido_subida(self) -> float:
        with self._tiempo_lock:
            if self.t_inicio_subida is None:
                return 0.0
            return max(0.0, time.monotonic() - self.t_inicio_subida - self.segundos_validacion)

    def poner(self, estado: str, actual: str = ""):
        self.estado, self.actual = estado, actual or self.actual
        self.refrescar()

    def avanzar_bytes(self, n: int):
        if n > 0:
            self.marcar_inicio_subida()
        self.bytes_hechos = max(0, self.bytes_hechos + n)
        ahora = time.monotonic()
        if ahora - self._muestras[-1][0] >= 0.3:
            self._muestras.append((ahora, self.bytes_hechos))
            if len(self._muestras) > 40:
                self._muestras.pop(0)
        self.refrescar()

    def archivo_listo(self):
        self.archivos_hechos += 1
        self.refrescar()

    def carpeta_lista(self):
        self.carpetas_hechas += 1
        self.refrescar()

    def refrescar(self, forzar: bool = False):
        ahora = time.monotonic()
        if self.al_cambiar and (forzar or ahora - self._ult >= 0.1):
            self._ult = ahora
            self.al_cambiar(self)

    def transcurrido(self) -> float:
        t_sub = self.transcurrido_subida()
        if t_sub > 0:
            return t_sub
        return max(0.0, time.monotonic() - self.t0)

    def velocidad(self) -> float:
        """Bytes por segundo, promediados en los últimos ~6 s."""
        ahora = time.monotonic()
        base = self._muestras[0]
        for m in self._muestras:
            if ahora - m[0] <= 6:
                base = m
                break
        dt = ahora - base[0]
        return (self.bytes_hechos - base[1]) / dt if dt > 0.5 else 0.0

    def velocidad_media(self) -> float:
        t = self.transcurrido()
        return self.bytes_hechos / t if t > 0 else 0.0

    def eta(self) -> Optional[float]:
        v = self.velocidad()
        if v <= 0 or self.bytes_total <= 0:
            return None
        return max(0.0, (self.bytes_total - self.bytes_hechos) / v)

    def fraccion(self) -> float:
        if self.bytes_total > 0:
            f = self.bytes_hechos / self.bytes_total
        elif self.archivos_total > 0:
            f = self.archivos_hechos / self.archivos_total
        else:
            f = 0.0
        return min(1.0, max(0.0, f))


class BarraConsola:
    """Dibuja una sola línea que se actualiza en su lugar."""

    def __init__(self):
        self._fase = None
        self._largo = 0

    def __call__(self, p: Progreso):
        if p.fase != self._fase:
            self.terminar()
            self._fase = p.fase
            if not p.fase:
                return
        fr = p.fraccion()
        n = 18
        lleno = int(n * fr)
        partes = [f"{p.fase} {'█' * lleno}{'░' * (n - lleno)} {int(fr * 100):3d}%"]
        if p.archivos_total:
            partes.append(f"{p.archivos_hechos}/{p.archivos_total} arch")
        elif p.archivos_hechos:
            partes.append(f"{p.archivos_hechos} arch")
        if p.carpetas_total:
            partes.append(f"{p.carpetas_hechas}/{p.carpetas_total} carp")
        elif p.carpetas_hechas:
            partes.append(f"{p.carpetas_hechas} carp")
        if p.bytes_total:
            partes.append(f"{fmt_bytes(p.bytes_hechos)}/{fmt_bytes(p.bytes_total)}")
            partes.append(f"{fmt_bytes(p.velocidad())}/s")
        partes.append(f"t {fmt_tiempo(p.transcurrido())}")
        eta = p.eta()
        if eta is not None:
            partes.append(f"faltan {fmt_tiempo(eta)}")
        if p.estado:
            partes.append(f"{p.estado} {p.actual}".strip())
        ancho = shutil.get_terminal_size((110, 20)).columns - 1
        linea = " | ".join(partes)[:ancho]
        sys.stdout.write("\r" + linea.ljust(self._largo))
        sys.stdout.flush()
        self._largo = len(linea)

    def terminar(self):
        if self._largo:
            sys.stdout.write("\n")
            sys.stdout.flush()
        self._largo = 0
        self._fase = None


class _LectorProgreso:
    """Envuelve un archivo para contar los bytes que se van enviando."""

    def __init__(self, f, prog: Optional[Progreso]):
        self.f, self.prog, self.enviado = f, prog, 0

    def read(self, n=-1):
        d = self.f.read(n)
        if d:
            self.enviado += len(d)
            if self.prog:
                self.prog.avanzar_bytes(len(d))
        return d


# ===========================================================================
# .env (solo URL, usuario y contraseña de WebDAV)
# ===========================================================================
def cargar_env(ruta_env: Path):
    if not ruta_env.is_file():
        return
    for linea in ruta_env.read_text(encoding="utf-8-sig").splitlines():
        linea = linea.strip()
        if linea.startswith("export "):
            linea = linea[7:].strip()
        if not linea or linea.startswith("#") or "=" not in linea:
            continue
        k, v = (x.strip() for x in linea.split("=", 1))
        if len(v) >= 2 and v[0] == v[-1] and v[0] in ("'", '"'):
            v = v[1:-1]
        else:
            v = v.split(" #", 1)[0].rstrip()
        os.environ[k] = v


# ===========================================================================
# Cliente WebDAV con requests (streaming en Windows y Android)
# ===========================================================================
class ClienteWebDAV:
    def __init__(self, url: str, usuario: str, password: str, carpeta: str = VAULT_DIR):
        self.base = url.rstrip("/")
        self.carpeta = carpeta
        self._usuario, self._password = usuario, password
        self._session = requests.Session()
        self._session.auth = HTTPBasicAuth(usuario, password)
        self.ultimo_error = ""

    def url(self, rel: str = "") -> str:
        partes = [self.carpeta] + [p for p in rel.replace("\\", "/").split("/") if p]
        return self.base + "/" + "/".join(quote(p, safe="") for p in partes)

    def _abrir(self, metodo, rel, data=None, headers=None, timeout=60):
        if not asegurar_certificados():
            raise ErrorRed("No se encontró el paquete de certificados TLS y no se pudo recrear. "
                           "Revisa el permiso «Acceso a todos los archivos» (detalles en el registro).")
        try:
            return self._session.request(
                metodo, self.url(rel), data=data, headers=headers or {},
                timeout=timeout, stream=True, allow_redirects=True)
        except requests.RequestException as e:
            self.ultimo_error = f"{type(e).__name__}: {e}"
            raise ErrorRed(f"No se pudo conectar con el servidor WebDAV: {type(e).__name__}: {e}") from e

    def _detalle_http(self, resp) -> str:
        razon = getattr(resp, "reason", "") or ""
        return f"HTTP {resp.status_code} {razon}".strip()

    @staticmethod
    def _codigo(resp) -> int:
        return resp.status_code

    def mkcol(self, rel: str) -> bool:
        r = self._abrir("MKCOL", rel, data=b"", timeout=30)
        try:
            ok = self._codigo(r) in (200, 201, 204, 405)
            if not ok:
                self.ultimo_error = f"MKCOL {rel}: {self._detalle_http(r)}"
                reg.error("mkcol_fallo", rel=rel, http=self._detalle_http(r))
            return ok
        finally:
            r.close()

    def asegurar(self, rel: str) -> bool:
        """Crea la jerarquía (una llamada MKCOL por nivel), incluida Boveda/."""
        partes = [p for p in rel.replace("\\", "/").split("/") if p]
        if not self.mkcol(""):
            return False
        for i in range(1, len(partes) + 1):
            if not self.mkcol("/".join(partes[:i])):
                return False
        return True

    def put_archivo(self, rel: str, ruta_local: Path, prog: Optional[Progreso] = None) -> bool:
        tam = os.path.getsize(ruta_local)
        t0 = time.monotonic()
        if prog:
            prog.marcar_inicio_subida()
        for intento in range(REINTENTOS):
            lector = None
            causa = ""
            try:
                with open(ruta_local, "rb") as f:
                    lector = _LectorProgreso(f, prog)
                    r = self._abrir("PUT", rel, data=lector, timeout=3600, headers={
                        "Content-Length": str(tam),
                        "Content-Type": "application/octet-stream"})
                    try:
                        cod = self._codigo(r)
                        detalle = self._detalle_http(r)
                    finally:
                        r.close()
                if cod in (200, 201, 204):
                    reg.info("put_archivo_ok", rel=rel, bytes=tam, intento=intento + 1,
                             segundos_subida_red=round(time.monotonic() - t0, 2))
                    return True
                causa = detalle
                self.ultimo_error = f"PUT: {detalle}"
                if cod < 500:
                    reg.error("put_archivo_rechazado", rel=rel, bytes=tam, http=detalle,
                              intento=intento + 1)
                    return False
            except ErrorRed as ex:
                causa = str(ex)
                self.ultimo_error = causa
            reg.aviso("put_archivo_reintento", rel=rel, bytes=tam, intento=intento + 1,
                      de=REINTENTOS, enviado=getattr(lector, "enviado", 0), causa=causa)
            if prog and lector:
                prog.avanzar_bytes(-lector.enviado)
            time.sleep(2 * (intento + 1))
        reg.error("put_archivo_agotado", rel=rel, bytes=tam, reintentos=REINTENTOS,
                  ultima_causa=self.ultimo_error)
        return False

    def put_stream(self, rel: str, stream, tam: int, prog: Optional[Progreso] = None) -> Tuple[bool, float]:
        """Sube un archivo desde la RAM de forma progresiva y devuelve (éxito, segundos_red)."""
        if prog:
            prog.marcar_inicio_subida()
        for intento in range(REINTENTOS):
            lector = None
            causa = ""
            t_intento = time.monotonic()
            try:
                stream.seek(0)
                lector = _LectorProgreso(stream, prog)
                r = self._abrir("PUT", rel, data=lector, timeout=3600, headers={
                    "Content-Length": str(tam),
                    "Content-Type": "application/octet-stream"})
                try:
                    cod = self._codigo(r)
                    detalle = self._detalle_http(r)
                finally:
                    r.close()
                if cod in (200, 201, 204):
                    return True, time.monotonic() - t_intento
                causa = detalle
                self.ultimo_error = f"PUT: {detalle}"
                if cod < 500:
                    reg.error("put_stream_rechazado", rel=rel, bytes=tam, http=detalle,
                              intento=intento + 1)
                    return False, time.monotonic() - t_intento
            except ErrorRed as ex:
                causa = str(ex)
                self.ultimo_error = causa
            reg.aviso("put_stream_reintento", rel=rel, bytes=tam, intento=intento + 1,
                      de=REINTENTOS, enviado=getattr(lector, "enviado", 0), causa=causa)
            if prog and lector:
                prog.avanzar_bytes(-lector.enviado)
            time.sleep(2 * (intento + 1))
        reg.error("put_stream_agotado", rel=rel, bytes=tam, reintentos=REINTENTOS,
                  ultima_causa=self.ultimo_error)
        return False, 0.0

    def put_bytes(self, rel: str, datos: bytes) -> bool:
        for intento in range(REINTENTOS):
            causa = ""
            try:
                r = self._abrir("PUT", rel, data=datos, timeout=120, headers={
                    "Content-Type": "application/octet-stream"})
                try:
                    cod = self._codigo(r)
                    detalle = self._detalle_http(r)
                finally:
                    r.close()
                if cod in (200, 201, 204):
                    return True
                causa = detalle
                self.ultimo_error = f"PUT {rel}: {detalle}"
                if cod < 500:
                    reg.error("put_bytes_rechazado", rel=rel, bytes=len(datos), http=detalle)
                    return False
            except ErrorRed as ex:
                causa = str(ex)
                self.ultimo_error = causa
            reg.aviso("put_bytes_reintento", rel=rel, intento=intento + 1, de=REINTENTOS, causa=causa)
            time.sleep(2 * (intento + 1))
        reg.error("put_bytes_agotado", rel=rel, bytes=len(datos), ultima_causa=self.ultimo_error)
        return False

    def get_a_archivo(self, rel: str, destino: Path, prog: Optional[Progreso] = None) -> bool:
        destino.parent.mkdir(parents=True, exist_ok=True)
        t0 = time.monotonic()
        if prog:
            prog.marcar_inicio_subida()
        for intento in range(REINTENTOS):
            recibido = 0
            causa = ""
            try:
                r = self._abrir("GET", rel, timeout=3600)
                try:
                    if self._codigo(r) != 200:
                        detalle = self._detalle_http(r)
                        self.ultimo_error = f"GET {rel}: {detalle}"
                        if self._codigo(r) < 500:
                            reg.error("get_archivo_rechazado", rel=rel, http=detalle)
                            return False
                        raise ErrorRed(f"error del servidor ({detalle})")
                    with open(destino, "wb") as f:
                        while True:
                            bloque = r.raw.read(CHUNK_SIZE)
                            if not bloque:
                                break
                            f.write(bloque)
                            recibido += len(bloque)
                            if prog:
                                prog.avanzar_bytes(len(bloque))
                    reg.info("get_archivo_ok", rel=rel, bytes=recibido, intento=intento + 1,
                             segundos_descarga_red=round(time.monotonic() - t0, 2))
                    return True
                finally:
                    r.close()
            except (ErrorRed, OSError) as ex:
                causa = f"{type(ex).__name__}: {ex}"
                self.ultimo_error = causa
                reg.aviso("get_archivo_reintento", rel=rel, intento=intento + 1, de=REINTENTOS,
                          recibido=recibido, causa=causa)
                if prog and recibido:
                    prog.avanzar_bytes(-recibido)
                time.sleep(2 * (intento + 1))
        reg.error("get_archivo_agotado", rel=rel, destino=destino, reintentos=REINTENTOS,
                  ultima_causa=self.ultimo_error)
        return False

    def get_bytes(self, rel: str) -> Optional[bytes]:
        for intento in range(REINTENTOS):
            try:
                r = self._abrir("GET", rel, timeout=120)
                try:
                    cod = self._codigo(r)
                    if cod == 200:
                        return r.content
                    if cod != 404:
                        self.ultimo_error = f"GET {rel}: {self._detalle_http(r)}"
                        reg.aviso("get_bytes_codigo", rel=rel, http=self._detalle_http(r))
                    if cod < 500:
                        return None
                finally:
                    r.close()
            except (ErrorRed, OSError) as ex:
                self.ultimo_error = f"{type(ex).__name__}: {ex}"
                reg.aviso("get_bytes_reintento", rel=rel, intento=intento + 1, causa=self.ultimo_error)
            time.sleep(2 * (intento + 1))
        return None

    def existe(self, rel: str) -> bool:
        r = self._abrir("HEAD", rel, timeout=30)
        try:
            return self._codigo(r) == 200
        finally:
            r.close()

    def listar_detalle(self, rel: str) -> List[Tuple[str, bool, int]]:
        """Devuelve [(nombre, es_dir, tamaño)] del contenido de una carpeta."""
        r = self._abrir("PROPFIND", rel, data=b"", headers={"Depth": "1"}, timeout=60)
        try:
            if self._codigo(r) not in (200, 207):
                return []
            cuerpo = r.content
        finally:
            r.close()
        try:
            raiz = ET.fromstring(cuerpo)
        except ET.ParseError:
            return []
        propia = unquote(urlparse(self.url(rel)).path).rstrip("/")
        salida = []
        for resp in raiz.findall(".//{DAV:}response"):
            href = resp.find("{DAV:}href")
            if href is None or not href.text:
                continue
            ruta = unquote(urlparse(href.text).path).rstrip("/")
            if ruta == propia:
                continue
            nombre = os.path.basename(ruta)
            if not nombre:
                continue
            es_dir = resp.find(".//{DAV:}resourcetype/{DAV:}collection") is not None
            tam_el = resp.find(".//{DAV:}getcontentlength")
            try:
                tam = int(tam_el.text) if tam_el is not None and tam_el.text else 0
            except ValueError:
                tam = 0
            salida.append((nombre, es_dir, tam))
        return salida

    def borrar(self, rel: str) -> bool:
        r = self._abrir("DELETE", rel, timeout=120)
        try:
            return self._codigo(r) in (200, 204, 404)
        finally:
            r.close()


# ===========================================================================
# Nombres (AES-SIV) y contenido (AES-256-GCM)
# ===========================================================================
def _b64url_sin_padding(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _b64url_con_padding(texto: str) -> bytes:
    return base64.urlsafe_b64decode(texto + "=" * (-len(texto) % 4))


def cifrar_nombre(nombre: str, clave_siv: bytes, uuid_padre: str) -> str:
    c = AES.new(clave_siv, AES.MODE_SIV)
    c.update(uuid_padre.encode("utf-8"))
    ct, tag = c.encrypt_and_digest(nombre.encode("utf-8"))
    res = _b64url_sin_padding(ct + tag) + ".c9r"
    if len(res) > MAX_NOMBRE_CIFRADO:
        raise ErrorBoveda(f"Nombre demasiado largo para cifrar: {nombre[:40]}...")
    return res


def descifrar_nombre(nombre_cifrado: str, clave_siv: bytes, uuid_padre: str) -> str:
    if nombre_cifrado.endswith(".c9r"):
        nombre_cifrado = nombre_cifrado[:-4]
    datos = _b64url_con_padding(nombre_cifrado)
    ct, tag = datos[:-16], datos[-16:]
    c = AES.new(clave_siv, AES.MODE_SIV)
    c.update(uuid_padre.encode("utf-8"))
    return c.decrypt_and_verify(ct, tag).decode("utf-8")


def cifrar_contenido(origen: Path, clave_gcm: bytes, destino: Path, id_archivo: str, prog: Optional[Progreso] = None) -> bool:
    """Formato: [nonce 12][ciphertext][tag 16]. El uuid del archivo va como AAD."""
    _set_error_cripto("")
    nonce = get_random_bytes(GCM_NONCE_SIZE)
    c = AES.new(clave_gcm, AES.MODE_GCM, nonce=nonce)
    c.update(id_archivo.encode("utf-8"))
    escritos = 0
    t0 = time.monotonic()
    try:
        with open(origen, "rb") as fi, open(destino, "wb") as fo:
            fo.write(nonce)
            while True:
                bloque = fi.read(CHUNK_SIZE)
                if not bloque:
                    break
                fo.write(c.encrypt(bloque))
                escritos += len(bloque)
                if prog:
                    prog.poner("cifrando", origen.name)
            fo.write(c.digest())
        dt = time.monotonic() - t0
        if prog:
            prog.sumar_preparacion(dt)
        reg.info("cifrado_ok", origen=origen, bytes=escritos, segundos_cifrado=round(dt, 2))
        return True
    except (OSError, ValueError) as ex:
        _set_error_cripto(f"{type(ex).__name__}: {ex}")
        reg.error("cifrado_fallo", exc=ex, origen=origen, destino=destino, bytes_procesados=escritos)
        destino.unlink(missing_ok=True)
        return False
    except BaseException:
        # Cancelación u otra señal: se limpia el temporal y se deja pasar la excepción.
        destino.unlink(missing_ok=True)
        raise


def descifrar_contenido(blob: Path, clave_gcm: bytes, destino: Path, id_archivo: str) -> bool:
    """Descifra a un temporal y solo lo mueve al destino si la etiqueta es válida."""
    _set_error_cripto("")
    tmp = destino.with_name(destino.name + ".parcial")
    t0 = time.monotonic()
    try:
        tam = blob.stat().st_size
        if tam < GCM_NONCE_SIZE + GCM_TAG_SIZE:
            _set_error_cripto(f"el archivo cifrado es demasiado corto ({tam} bytes)")
            reg.error("descifrado_corto", blob=blob, bytes=tam)
            return False
        with open(blob, "rb") as fi:
            nonce = fi.read(GCM_NONCE_SIZE)
            fi.seek(tam - GCM_TAG_SIZE)
            tag = fi.read(GCM_TAG_SIZE)
            fi.seek(GCM_NONCE_SIZE)
            c = AES.new(clave_gcm, AES.MODE_GCM, nonce=nonce)
            c.update(id_archivo.encode("utf-8"))
            restante = tam - GCM_NONCE_SIZE - GCM_TAG_SIZE
            destino.parent.mkdir(parents=True, exist_ok=True)
            with open(tmp, "wb") as fo:
                while restante > 0:
                    bloque = fi.read(min(CHUNK_SIZE, restante))
                    if not bloque:
                        break
                    fo.write(c.decrypt(bloque))
                    restante -= len(bloque)
            c.verify(tag)
        os.replace(tmp, destino)
        reg.info("descifrado_ok", destino=destino, bytes=tam, segundos_descifrado=round(time.monotonic() - t0, 2))
        return True
    except ValueError as ex:
        _set_error_cripto("la etiqueta GCM no coincide (archivo alterado, incompleto o llave distinta)")
        reg.error("descifrado_gcm_invalido", exc=ex, blob=blob, destino=destino, bytes=blob.stat().st_size
                  if blob.exists() else -1)
        tmp.unlink(missing_ok=True)
        return False
    except Exception as ex:
        _set_error_cripto(f"{type(ex).__name__}: {ex}")
        reg.error("descifrado_fallo", exc=ex, blob=blob, destino=destino)
        tmp.unlink(missing_ok=True)
        return False


# ===========================================================================
# Bóveda: crear, abrir, cambiar frase
# ===========================================================================
def boveda_existe(dav: ClienteWebDAV) -> bool:
    return dav.existe(mc.NOMBRE_ARCHIVO_LLAVE)


def crear_boveda_nube(dav: ClienteWebDAV, frase: str) -> Tuple[Llaves, str]:
    if boveda_existe(dav):
        raise ErrorBoveda("Ya existe una bóveda en la nube. No se sobrescribe.")
    arch, llaves, rec = mc.crear_boveda(frase)
    if not dav.asegurar(f"d/{llaves.root_uuid}"):
        raise ErrorBoveda("No se pudo crear la estructura de carpetas en la nube.")
    if not dav.put_bytes(f"d/{llaves.root_uuid}/dir.c9r", (llaves.root_uuid + "\n").encode()):
        raise ErrorBoveda("No se pudo subir dir.c9r de la raíz.")
    contenido = json.dumps(arch, indent=2).encode("utf-8")
    if not dav.put_bytes(mc.NOMBRE_ARCHIVO_LLAVE, contenido):
        raise ErrorBoveda(f"No se pudo subir {mc.NOMBRE_ARCHIVO_LLAVE}.")
    if dav.get_bytes(mc.NOMBRE_ARCHIVO_LLAVE) != contenido:
        raise ErrorBoveda(f"{mc.NOMBRE_ARCHIVO_LLAVE} no coincide tras subirlo.")
    return llaves, rec


def _leer_arch(dav: ClienteWebDAV) -> dict:
    datos = dav.get_bytes(mc.NOMBRE_ARCHIVO_LLAVE)
    if datos is None:
        raise ErrorBoveda(f"No hay bóveda en la nube (falta {mc.NOMBRE_ARCHIVO_LLAVE}).")
    try:
        return json.loads(datos.decode("utf-8"))
    except Exception:
        raise ErrorBoveda(f"{mc.NOMBRE_ARCHIVO_LLAVE} está dañado.")


def abrir_boveda_nube(dav: ClienteWebDAV, frase: Optional[str] = None,
                      recuperacion: Optional[str] = None) -> Llaves:
    arch = _leer_arch(dav)
    if recuperacion:
        return mc.abrir_con_recuperacion(arch, recuperacion)
    return mc.abrir_con_frase(arch, frase or "")


def cambiar_frase_nube(dav: ClienteWebDAV, frase_actual: Optional[str],
                       frase_nueva: str, recuperacion: Optional[str] = None):
    arch = _leer_arch(dav)
    if recuperacion:
        nuevo = mc.restablecer_frase_con_recuperacion(arch, recuperacion, frase_nueva)
    else:
        nuevo = mc.cambiar_frase(arch, frase_actual or "", frase_nueva)
    dav.put_bytes(mc.NOMBRE_ARCHIVO_LLAVE + ".bak", json.dumps(arch, indent=2).encode())
    if not dav.put_bytes(mc.NOMBRE_ARCHIVO_LLAVE, json.dumps(nuevo, indent=2).encode()):
        raise ErrorBoveda(f"No se pudo subir el nuevo {mc.NOMBRE_ARCHIVO_LLAVE}.")


# ===========================================================================
# Subir carpeta (archivos en paralelo: cifrado + subida simultáneos multipart)
# ===========================================================================
def escanear_origen(carpeta: Path, prog: Optional[Progreso] = None) -> dict:
    """Cuenta carpetas, archivos y bytes que se van a subir (omite ocultos y enlaces)."""
    t0_scan = time.monotonic()
    r = {"carpetas": 1, "archivos": 0, "bytes": 0, "omitidos": 0, "errores": []}
    if prog:
        prog.iniciar("Escaneando")

    def _rec(d: Path):
        try:
            entradas = list(d.iterdir())
        except OSError as e:
            r["errores"].append((str(d), f"no se puede leer: {e}"))
            return
        for e in entradas:
            if e.name.startswith(".") or e.is_symlink():
                r["omitidos"] += 1
                continue
            try:
                if e.is_dir():
                    r["carpetas"] += 1
                    _rec(e)
                elif e.is_file():
                    r["archivos"] += 1
                    r["bytes"] += e.stat().st_size
                if prog:
                    prog.archivos_hechos, prog.carpetas_hechas = r["archivos"], r["carpetas"]
                    prog.refrescar()
            except OSError as ex:
                r["errores"].append((str(e), str(ex)))

    _rec(carpeta)
    dt_scan = time.monotonic() - t0_scan
    if prog:
        prog.sumar_preparacion(dt_scan)
        prog.refrescar(True)
    return r


def _tam_seguro(ruta: Path) -> int:
    try:
        return ruta.stat().st_size
    except OSError:
        return -1


def _detalle(dav) -> str:
    e = getattr(dav, "ultimo_error", "")
    return f" ({e})" if e else ""


def subir_carpeta(dav: ClienteWebDAV, llaves: Llaves, carpeta: Path,
                  verificar: bool = False, prog: Optional[Progreso] = None,
                  resumen: Optional[dict] = None,
                  uuid_dir: Optional[str] = None,
                  tmp_dir: Optional[Path] = None) -> dict:
    prog = prog or Progreso()
    t0_prep_init = time.monotonic()
    if not carpeta.is_dir():
        raise ErrorBoveda(f"No es una carpeta válida: {carpeta}")
    destino_padre = uuid_dir or llaves.root_uuid
    nombre_top = cifrar_nombre(carpeta.name, llaves.siv, destino_padre)
    if dav.existe(f"d/{destino_padre}/{nombre_top}"):
        raise ErrorBoveda(f"Ya existe '{carpeta.name}' en la carpeta de destino. "
                          "Renómbrala o bórrala antes de volver a subirla.")
    prog.sumar_preparacion(time.monotonic() - t0_prep_init)

    if resumen is None:
        resumen = escanear_origen(carpeta, prog)
    if resumen["errores"]:
        r0 = resumen["errores"][0]
        raise ErrorBoveda(f"No se puede leer parte de la carpeta ({r0[0]}: {r0[1]}).")
    if resumen["archivos"] == 0:
        raise ErrorBoveda("La carpeta seleccionada está vacía.")

    total_bytes = resumen["bytes"] + resumen["archivos"] * (GCM_NONCE_SIZE + GCM_TAG_SIZE)
    prog.iniciar("Subiendo", resumen["archivos"], resumen["carpetas"], total_bytes)
    res = {
        "ok": 0,
        "carpetas": 0,
        "fallidos": [],
        "verificados": 0,
        "segundos_validacion": 0.0,
        "segundos_preparacion": 0.0,
        "segundos_subida_red": 0.0,
    }
    tmp_dir = tmp_dir or directorio_temporal()

    # Lock para proteger las mutaciones de 'res' desde hilos paralelos.
    _lock = threading.Lock()

    def _nueva_carpeta(uuid_padre: str, nombre: str) -> Optional[str]:
        t0_c = time.monotonic()
        try:
            cif = cifrar_nombre(nombre, llaves.siv, uuid_padre)
        except ErrorBoveda as ex:
            with _lock:
                res["fallidos"].append((nombre, str(ex)))
            return None
        hijo = str(uuid.uuid4())
        marca = (hijo + "\n").encode()
        if (dav.mkcol(f"d/{hijo}") and dav.put_bytes(f"d/{hijo}/dir.c9r", marca)
                and dav.put_bytes(f"d/{uuid_padre}/{cif}", marca)):
            dt_c = time.monotonic() - t0_c
            prog.sumar_preparacion(dt_c)
            with _lock:
                res["carpetas"] += 1
            prog.carpeta_lista()
            return hijo
        dav.borrar(f"d/{hijo}")
        with _lock:
            res["fallidos"].append((nombre, "no se pudo crear la carpeta en la nube" + _detalle(dav)))
        reg.error("subida_carpeta_nube_fallo", carpeta=nombre, ultima_causa=getattr(dav, "ultimo_error", ""))
        return None

    def _subir_archivo(ruta: Path, uuid_dir: str):
        id_arch = str(uuid.uuid4())
        motivo = None
        logrado = False
        t_prep_arch = 0.0
        t_red_arch = 0.0
        try:
            t0_meta = time.monotonic()
            try:
                nombre_cif = cifrar_nombre(ruta.name, llaves.siv, uuid_dir)
            except ErrorBoveda as ex:
                motivo = str(ex)
                return

            tam_archivo = _tam_seguro(ruta)
            prog.poner("preparando y cifrando", ruta.name)

            if not dav.mkcol(f"d/{id_arch}") or not dav.put_bytes(f"d/{id_arch}/dir.c9r", (id_arch + "\n").encode()):
                motivo = "falló la creación de carpeta de archivo" + _detalle(dav)
                dav.borrar(f"d/{id_arch}")
                return
            dt_meta = time.monotonic() - t0_meta
            t_prep_arch += dt_meta
            prog.sumar_preparacion(dt_meta)

            hubo_error = []
            _lock_partes = threading.Lock()

            # Descontamos el tamaño fijo original que sumó este archivo,
            # lo agregaremos paso a paso para que la barra de progreso fluya al subir las partes.
            if prog:
                prog.avanzar_bytes(-(tam_archivo + GCM_NONCE_SIZE + GCM_TAG_SIZE))

            def procesar_parte(indice: int, datos: bytes):
                nonlocal t_prep_arch, t_red_arch
                if hubo_error:
                    return
                try:
                    t0_cif = time.monotonic()
                    prog.poner("cifrando parte", ruta.name)
                    aad = f"{id_arch}:{indice}".encode("utf-8")
                    nonce = get_random_bytes(GCM_NONCE_SIZE)
                    c = AES.new(llaves.gcm, AES.MODE_GCM, nonce=nonce)
                    c.update(aad)
                    ct, tag = c.encrypt_and_digest(datos)
                    blob = nonce + ct + tag
                    dt_cif = time.monotonic() - t0_cif
                    with _lock_partes:
                        t_prep_arch += dt_cif
                    prog.sumar_preparacion(dt_cif)

                    prog.poner("subiendo a la nube", ruta.name)
                    rel_part = f"d/{id_arch}/{indice:04d}.part"
                    stream = io.BytesIO(blob)
                    ok_part, dt_red = dav.put_stream(rel_part, stream, len(blob), prog)
                    with _lock_partes:
                        t_red_arch += dt_red
                    if not ok_part:
                        with _lock_partes:
                            hubo_error.append(f"falló la subida de la parte {indice}")
                except Exception as ex:
                    with _lock_partes:
                        hubo_error.append(f"error cifrando parte {indice}: {ex}")

            with open(ruta, "rb") as f:
                with ThreadPoolExecutor(max_workers=3) as exec_partes:
                    futures_partes = []
                    indice = 0
                    while True:
                        if hubo_error:
                            break
                        t0_read = time.monotonic()
                        bloque = f.read(TAM_PARTE_BYTES)
                        dt_read = time.monotonic() - t0_read
                        with _lock_partes:
                            t_prep_arch += dt_read
                        prog.sumar_preparacion(dt_read)
                        if not bloque and indice > 0:
                            break
                        futures_partes.append(exec_partes.submit(procesar_parte, indice, bloque))
                        indice += 1
                        if not bloque:  # Para archivos vacíos de 0 bytes
                            break
                    for fut in futures_partes:
                        fut.result()

            if hubo_error:
                motivo = hubo_error[0]
                dav.borrar(f"d/{id_arch}")
                return

            if verificar:
                t_validacion = time.monotonic()
                prog.poner("validando partes", ruta.name)
                error_verif = False
                for idx in range(indice):
                    rel_part = f"d/{id_arch}/{idx:04d}.part"
                    bajado = tmp_dir / f"v_{id_arch}_{idx}.part"
                    if not dav.get_a_archivo(rel_part, bajado, None):
                        motivo = f"no se pudo descargar para verificar la parte {idx}"
                        error_verif = True
                        break
                    try:
                        tam_parte = bajado.stat().st_size
                        with open(bajado, "rb") as fi:
                            nonce = fi.read(GCM_NONCE_SIZE)
                            fi.seek(tam_parte - GCM_TAG_SIZE)
                            tag = fi.read(GCM_TAG_SIZE)
                            fi.seek(GCM_NONCE_SIZE)
                            ct = fi.read(tam_parte - GCM_NONCE_SIZE - GCM_TAG_SIZE)
                        aad = f"{id_arch}:{idx}".encode("utf-8")
                        c = AES.new(llaves.gcm, AES.MODE_GCM, nonce=nonce)
                        c.update(aad)
                        c.verify(tag)
                    except Exception:
                        motivo = f"la verificación GCM falló en parte {idx}"
                        error_verif = True
                        break
                    finally:
                        bajado.unlink(missing_ok=True)

                if error_verif:
                    dav.borrar(f"d/{id_arch}")
                    return
                else:
                    dt_val = time.monotonic() - t_validacion
                    with _lock:
                        res["verificados"] += 1
                        res["segundos_validacion"] += dt_val
                    with prog._tiempo_lock:
                        prog.segundos_validacion += dt_val

            t0_reg = time.monotonic()
            ok_reg = dav.put_bytes(f"d/{uuid_dir}/{nombre_cif}", (id_arch + "\n").encode())
            dt_reg = time.monotonic() - t0_reg
            t_red_arch += dt_reg
            if not ok_reg:
                motivo = "no se pudo registrar el nombre" + _detalle(dav)
                dav.borrar(f"d/{id_arch}")
                return

            with _lock:
                res["ok"] += 1
                res["segundos_subida_red"] += t_red_arch
            logrado = True

        finally:
            if motivo:
                with _lock:
                    res["fallidos"].append((str(ruta), motivo))
                reg.error("subida_archivo_fallo", archivo=ruta, motivo=motivo, id=id_arch,
                          tam=_tam_seguro(ruta), verificar=verificar,
                          segundos_preparacion_cifrado=round(t_prep_arch, 2),
                          segundos_subida_exclusiva=round(t_red_arch, 2))
            elif logrado:
                reg.info("subida_archivo_ok", archivo=ruta, bytes=_tam_seguro(ruta), id=id_arch,
                         segundos_preparacion_cifrado=round(t_prep_arch, 2),
                         segundos_subida_exclusiva=round(t_red_arch, 2))
                # Reporte incremental (nuevo)
                try:
                    import mixcoara_reporte as _rep
                    _rep.anotar_en_reporte(ruta, id_arch, "file", _tam_seguro(ruta))
                except Exception:
                    pass
            else:
                reg.aviso("subida_archivo_interrumpida", archivo=ruta, id=id_arch,
                          segundos_preparacion_cifrado=round(t_prep_arch, 2),
                          segundos_subida_exclusiva=round(t_red_arch, 2))
            prog.archivo_listo()

    def _procesar(ruta_dir: Path, uuid_dir: str, executor: ThreadPoolExecutor, futures: list):
        """Crea carpetas en orden (secuencial) y envía archivos al pool."""
        for e in sorted(ruta_dir.iterdir(), key=lambda p: p.name.lower()):
            if e.name.startswith(".") or e.is_symlink():
                continue
            if e.is_dir():
                hijo = _nueva_carpeta(uuid_dir, e.name)
                if hijo:
                    _procesar(e, hijo, executor, futures)
            elif e.is_file():
                futures.append(executor.submit(_subir_archivo, e, uuid_dir))

    top = _nueva_carpeta(destino_padre, carpeta.name)
    if top:
        futures = []
        with ThreadPoolExecutor(max_workers=WORKERS_SUBIDA) as executor:
            _procesar(carpeta, top, executor, futures)
        # El bloque 'with' espera a que todos los futuros terminen antes de continuar.

    prog.poner("", "")
    prog.refrescar(True)
    res["bytes"] = prog.bytes_hechos
    t_subida_exclusiva = prog.transcurrido_subida()
    if t_subida_exclusiva <= 0:
        t_subida_exclusiva = res["segundos_subida_red"]
    res["segundos"] = max(0.001, t_subida_exclusiva)
    res["segundos_preparacion"] = round(prog.segundos_preparacion, 2)
    res["vel_media"] = res["bytes"] / res["segundos"]
    reg.info(
        "subida_carpeta_fin",
        carpeta=carpeta.name,
        archivos_ok=res["ok"],
        carpetas_ok=res["carpetas"],
        fallidos=len(res["fallidos"]),
        bytes=res["bytes"],
        segundos_preparacion_cifrado=res["segundos_preparacion"],
        segundos_subida_exclusiva=round(res["segundos"], 2),
        velocidad_media_Bps=round(res["vel_media"], 1),
    )
    return res


def subir_archivo(dav: ClienteWebDAV, llaves: Llaves, ruta: Path,
                  verificar: bool = False, prog: Optional[Progreso] = None,
                  uuid_dir: Optional[str] = None,
                  tmp_dir: Optional[Path] = None) -> dict:
    """Sube UN archivo suelto a la raíz de la bóveda usando multipart en RAM."""
    prog = prog or Progreso()
    t0_prep = time.monotonic()
    if not ruta.is_file():
        raise ErrorBoveda(f"No es un archivo válido: {ruta}")

    destino_dir = uuid_dir or llaves.root_uuid
    nombre_cif = cifrar_nombre(ruta.name, llaves.siv, destino_dir)
    if dav.existe(f"d/{destino_dir}/{nombre_cif}"):
        raise ErrorBoveda(f"Ya existe '{ruta.name}' en la carpeta de destino.")

    tam_archivo = _tam_seguro(ruta)
    partes_estimadas = max(1, (tam_archivo + TAM_PARTE_BYTES - 1) // TAM_PARTE_BYTES)
    dt_init = time.monotonic() - t0_prep
    prog.iniciar("Subiendo", 1, 0, tam_archivo + partes_estimadas * (GCM_NONCE_SIZE + GCM_TAG_SIZE))
    prog.sumar_preparacion(dt_init)

    res = {
        "ok": 0,
        "verificados": 0,
        "fallidos": [],
        "segundos_validacion": 0.0,
        "segundos_preparacion": 0.0,
        "segundos_subida_red": 0.0,
    }
    id_arch = str(uuid.uuid4())
    tmp_dir = tmp_dir or directorio_temporal()
    motivo = None
    t_prep_arch = dt_init
    t_red_arch = 0.0
    reg.info("subida_archivo_inicio", archivo=ruta, bytes=tam_archivo, id=id_arch, verificar=verificar)

    try:
        prog.poner("preparando y cifrando", ruta.name)
        t0_mk = time.monotonic()
        if not dav.mkcol(f"d/{id_arch}") or not dav.put_bytes(f"d/{id_arch}/dir.c9r", (id_arch + "\n").encode()):
            motivo = "falló la creación de carpeta de archivo" + _detalle(dav)
            dav.borrar(f"d/{id_arch}")
            return res
        dt_mk = time.monotonic() - t0_mk
        t_prep_arch += dt_mk
        prog.sumar_preparacion(dt_mk)

        hubo_error = []
        _lock_partes = threading.Lock()

        def procesar_parte(indice: int, datos: bytes):
            nonlocal t_prep_arch, t_red_arch
            if hubo_error:
                return
            try:
                t0_cif = time.monotonic()
                prog.poner("cifrando parte", ruta.name)
                aad = f"{id_arch}:{indice}".encode("utf-8")
                nonce = get_random_bytes(GCM_NONCE_SIZE)
                c = AES.new(llaves.gcm, AES.MODE_GCM, nonce=nonce)
                c.update(aad)
                ct, tag = c.encrypt_and_digest(datos)
                blob = nonce + ct + tag
                dt_cif = time.monotonic() - t0_cif
                with _lock_partes:
                    t_prep_arch += dt_cif
                prog.sumar_preparacion(dt_cif)

                prog.poner("subiendo a la nube", ruta.name)
                rel_part = f"d/{id_arch}/{indice:04d}.part"
                stream = io.BytesIO(blob)
                ok_part, dt_red = dav.put_stream(rel_part, stream, len(blob), prog)
                with _lock_partes:
                    t_red_arch += dt_red
                if not ok_part:
                    with _lock_partes:
                        hubo_error.append(f"falló la subida de la parte {indice}")
            except Exception as ex:
                with _lock_partes:
                    hubo_error.append(f"error cifrando parte {indice}: {ex}")

        with open(ruta, "rb") as f:
            with ThreadPoolExecutor(max_workers=3) as exec_partes:
                futures_partes = []
                indice = 0
                while True:
                    if hubo_error:
                        break
                    t0_read = time.monotonic()
                    bloque = f.read(TAM_PARTE_BYTES)
                    dt_read = time.monotonic() - t0_read
                    with _lock_partes:
                        t_prep_arch += dt_read
                    prog.sumar_preparacion(dt_read)
                    if not bloque and indice > 0:
                        break
                    futures_partes.append(exec_partes.submit(procesar_parte, indice, bloque))
                    indice += 1
                    if not bloque:
                        break
                for fut in futures_partes:
                    fut.result()

        if hubo_error:
            motivo = hubo_error[0]
            dav.borrar(f"d/{id_arch}")
        else:
            if verificar:
                t_validacion = time.monotonic()
                prog.poner("validando partes", ruta.name)
                error_verif = False
                for idx in range(indice):
                    rel_part = f"d/{id_arch}/{idx:04d}.part"
                    bajado = tmp_dir / f"v_{id_arch}_{idx}.part"
                    if not dav.get_a_archivo(rel_part, bajado, None):
                        motivo = f"no se pudo descargar para verificar la parte {idx}"
                        error_verif = True
                        break
                    try:
                        tam_parte = bajado.stat().st_size
                        with open(bajado, "rb") as fi:
                            nonce = fi.read(GCM_NONCE_SIZE)
                            fi.seek(tam_parte - GCM_TAG_SIZE)
                            tag = fi.read(GCM_TAG_SIZE)
                            fi.seek(GCM_NONCE_SIZE)
                            ct = fi.read(tam_parte - GCM_NONCE_SIZE - GCM_TAG_SIZE)
                        aad = f"{id_arch}:{idx}".encode("utf-8")
                        c = AES.new(llaves.gcm, AES.MODE_GCM, nonce=nonce)
                        c.update(aad)
                        c.verify(tag)
                    except Exception:
                        motivo = f"la verificación GCM falló en parte {idx}"
                        error_verif = True
                        break
                    finally:
                        bajado.unlink(missing_ok=True)

                if error_verif:
                    dav.borrar(f"d/{id_arch}")
                else:
                    dt_val = time.monotonic() - t_validacion
                    res["verificados"] += 1
                    res["segundos_validacion"] += dt_val
                    with prog._tiempo_lock:
                        prog.segundos_validacion += dt_val

            if not motivo:
                t0_reg = time.monotonic()
                ok_reg = dav.put_bytes(f"d/{destino_dir}/{nombre_cif}", (id_arch + "\n").encode())
                dt_reg = time.monotonic() - t0_reg
                t_red_arch += dt_reg
                if not ok_reg:
                    motivo = "no se pudo registrar el nombre" + _detalle(dav)
                    dav.borrar(f"d/{id_arch}")
                else:
                    res["ok"] += 1
    finally:
        res["segundos_preparacion"] = round(t_prep_arch, 2)
        res["segundos_subida_red"] = round(t_red_arch, 2)
        if motivo:
            res["fallidos"].append((str(ruta), motivo))
            reg.error("subida_archivo_fallo", archivo=ruta, motivo=motivo, id=id_arch,
                      tam=tam_archivo, verificar=verificar,
                      segundos_preparacion_cifrado=round(t_prep_arch, 2),
                      segundos_subida_exclusiva=round(t_red_arch, 2))
        elif res["ok"]:
            reg.info("subida_archivo_ok", archivo=ruta, bytes=tam_archivo, id=id_arch,
                     verificado=bool(res["verificados"]),
                     segundos_preparacion_cifrado=round(t_prep_arch, 2),
                     segundos_subida_exclusiva=round(t_red_arch, 2))
            # Reporte incremental (nuevo)
            try:
                import mixcoara_reporte as _rep
                _rep.anotar_en_reporte(ruta, id_arch, "file", tam_archivo)
            except Exception:
                pass
        else:
            reg.aviso("subida_archivo_interrumpida", archivo=ruta, id=id_arch,
                      segundos_preparacion_cifrado=round(t_prep_arch, 2),
                      segundos_subida_exclusiva=round(t_red_arch, 2))
        prog.archivo_listo()
        prog.poner("", "")
        prog.refrescar(True)

    res["bytes"] = prog.bytes_hechos
    t_subida_exclusiva = prog.transcurrido_subida()
    if t_subida_exclusiva <= 0:
        t_subida_exclusiva = t_red_arch
    res["segundos"] = max(0.001, t_subida_exclusiva)
    res["vel_media"] = res["bytes"] / res["segundos"]
    return res


# ===========================================================================
# Cifrado local portátil
# ===========================================================================
LOCAL_MAGIC = b"Mixcoara-LOCAL-1\n"


def cifrar_archivo_local(origen: Path, clave_gcm: bytes, destino: Path) -> Path:
    """Cifra un archivo local; el identificador autenticado queda en la cabecera."""
    if not origen.is_file():
        raise ErrorBoveda(f"No es un archivo válido: {origen}")
    destino.parent.mkdir(parents=True, exist_ok=True)
    identificador = str(uuid.uuid4())
    temporal = destino.with_name(destino.name + ".parcial")
    nonce = get_random_bytes(GCM_NONCE_SIZE)
    cifrador = AES.new(clave_gcm, AES.MODE_GCM, nonce=nonce)
    cifrador.update(identificador.encode("ascii"))
    t0 = time.monotonic()
    try:
        with origen.open("rb") as entrada, temporal.open("wb") as salida:
            salida.write(LOCAL_MAGIC)
            salida.write(identificador.encode("ascii") + b"\n")
            salida.write(nonce)
            while bloque := entrada.read(CHUNK_SIZE):
                salida.write(cifrador.encrypt(bloque))
            salida.write(cifrador.digest())
        os.replace(temporal, destino)
        reg.info("cifrado_local_ok", origen=origen, destino=destino,
                 bytes=destino.stat().st_size, segundos_cifrado=round(time.monotonic() - t0, 2))
        return destino
    except Exception as ex:
        temporal.unlink(missing_ok=True)
        reg.error("cifrado_local_fallo", exc=ex, origen=origen, destino=destino)
        raise ErrorBoveda(f"No se pudo cifrar el archivo local: {type(ex).__name__}: {ex}") from ex


def descifrar_archivo_local(origen: Path, clave_gcm: bytes, destino: Path) -> Path:
    """Descifra un archivo .Mixcoara y no conserva salida si falla GCM."""
    temporal = destino.with_name(destino.name + ".parcial")
    t0 = time.monotonic()
    try:
        with origen.open("rb") as entrada:
            if entrada.readline() != LOCAL_MAGIC:
                raise ErrorBoveda("El archivo no tiene el formato local de Mixcoara.")
            identificador = entrada.readline().strip().decode("ascii")
            uuid.UUID(identificador)
            nonce = entrada.read(GCM_NONCE_SIZE)
            inicio = entrada.tell()
            entrada.seek(0, os.SEEK_END)
            fin = entrada.tell()
            if fin - inicio < GCM_TAG_SIZE:
                raise ErrorBoveda("El archivo cifrado está incompleto.")
            entrada.seek(fin - GCM_TAG_SIZE)
            tag = entrada.read(GCM_TAG_SIZE)
            entrada.seek(inicio)
            restante = fin - inicio - GCM_TAG_SIZE
            cifrador = AES.new(clave_gcm, AES.MODE_GCM, nonce=nonce)
            cifrador.update(identificador.encode("ascii"))
            destino.parent.mkdir(parents=True, exist_ok=True)
            with temporal.open("wb") as salida:
                while restante:
                    bloque = entrada.read(min(CHUNK_SIZE, restante))
                    if not bloque:
                        raise ErrorBoveda("El archivo cifrado está incompleto.")
                    salida.write(cifrador.decrypt(bloque))
                    restante -= len(bloque)
            cifrador.verify(tag)
        os.replace(temporal, destino)
        reg.info("descifrado_local_ok", origen=origen, destino=destino,
                 bytes=destino.stat().st_size, segundos_descifrado=round(time.monotonic() - t0, 2))
        return destino
    except ErrorBoveda as ex:
        temporal.unlink(missing_ok=True)
        reg.error("descifrado_local_fallo", exc=ex, origen=origen, destino=destino)
        raise
    except Exception as ex:
        temporal.unlink(missing_ok=True)
        reg.error("descifrado_local_fallo", exc=ex, origen=origen, destino=destino)
        raise ErrorBoveda(f"No se pudo descifrar o la validación GCM falló: {type(ex).__name__}: {ex}") from ex


# ===========================================================================
# Restaurar (primero lee la estructura para conocer totales y soporta multipart)
# ===========================================================================
def _nombre_seguro(n: str) -> bool:
    return n not in ("", ".", "..") and "/" not in n and "\\" not in n and "\0" not in n


def planificar_restauracion(dav: ClienteWebDAV, llaves: Llaves, prog: Progreso):
    t0_plan = time.monotonic()
    prog.iniciar("Leyendo estructura")
    dirs: List[Path] = []
    archivos: List[Tuple[str, Path, int]] = []
    errores: List[Tuple[str, str]] = []

    def _rec(uuid_dir: str, rel: Path, entradas):
        for nombre_cif, es_dir, _t in entradas:
            if nombre_cif == "dir.c9r" or es_dir or not nombre_cif.endswith(".c9r"):
                continue
            datos = dav.get_bytes(f"d/{uuid_dir}/{nombre_cif}")
            if datos is None:
                errores.append((nombre_cif, "no se pudo bajar el .c9r"))
                continue
            uuid_hijo = datos.decode("utf-8", errors="ignore").strip()
            try:
                uuid.UUID(uuid_hijo)
                nombre = descifrar_nombre(nombre_cif, llaves.siv, uuid_dir)
            except Exception as e:
                errores.append((nombre_cif, f"nombre inválido: {e}"))
                continue
            if not _nombre_seguro(nombre):
                errores.append((nombre_cif, "nombre con caracteres peligrosos"))
                continue
            hijo_entradas = dav.listar_detalle(f"d/{uuid_hijo}")

            # Sumar tamaños del legado y de multipart
            tam = None
            for n, d, t in hijo_entradas:
                if (n == f"{uuid_hijo}.bin" or n.endswith(".part")) and not d:
                    tam = (tam or 0) + t

            rel_hijo = rel / nombre
            if tam is not None:
                archivos.append((uuid_hijo, rel_hijo, tam))
            else:
                dirs.append(rel_hijo)
                _rec(uuid_hijo, rel_hijo, hijo_entradas)
            prog.archivos_hechos, prog.carpetas_hechas = len(archivos), len(dirs)
            prog.refrescar()

    _rec(llaves.root_uuid, Path("."), dav.listar_detalle(f"d/{llaves.root_uuid}"))
    prog.sumar_preparacion(time.monotonic() - t0_plan)
    prog.refrescar(True)
    return dirs, archivos, errores


def restaurar(dav: ClienteWebDAV, llaves: Llaves, destino: Path,
              prog: Optional[Progreso] = None) -> dict:
    prog = prog or Progreso()
    dirs, archivos, errores = planificar_restauracion(dav, llaves, prog)
    destino.mkdir(parents=True, exist_ok=True)
    total_bytes = sum(t for _, _, t in archivos)
    prog.iniciar("Restaurando", len(archivos), len(dirs), total_bytes)
    res = {"dirs": 0, "archivos": 0, "errores": list(errores)}
    tmp_dir = directorio_temporal()

    for d in dirs:
        (destino / d).mkdir(parents=True, exist_ok=True)
        res["dirs"] += 1
        prog.carpeta_lista()

    for uuid_h, rel, _tam in archivos:
        prog.poner("descargando", rel.name)
        entradas = dav.listar_detalle(f"d/{uuid_h}")
        partes = sorted([n for n, d, t in entradas if n.endswith(".part") and not d])

        if partes:
            try:
                destino_arch = destino / rel
                destino_arch.parent.mkdir(parents=True, exist_ok=True)
                with open(destino_arch, "wb") as f_out:
                    error_descarga = False
                    for parte in partes:
                        prog.poner("descargando parte", f"{rel.name} ({parte})")
                        tmp_parte = tmp_dir / f"desc_{uuid_h}_{parte}"
                        if not dav.get_a_archivo(f"d/{uuid_h}/{parte}", tmp_parte, prog):
                            res["errores"].append((str(rel), f"falló la descarga de {parte}"))
                            error_descarga = True
                            break

                        t0_dec = time.monotonic()
                        try:
                            tam_parte = tmp_parte.stat().st_size
                            with open(tmp_parte, "rb") as fi:
                                nonce = fi.read(GCM_NONCE_SIZE)
                                fi.seek(tam_parte - GCM_TAG_SIZE)
                                tag = fi.read(GCM_TAG_SIZE)
                                fi.seek(GCM_NONCE_SIZE)
                                ct = fi.read(tam_parte - GCM_NONCE_SIZE - GCM_TAG_SIZE)

                            indice = int(parte.split(".")[0])
                            aad = f"{uuid_h}:{indice}".encode("utf-8")
                            c = AES.new(llaves.gcm, AES.MODE_GCM, nonce=nonce)
                            c.update(aad)
                            claro = c.decrypt_and_verify(ct, tag)
                            f_out.write(claro)
                            prog.sumar_preparacion(time.monotonic() - t0_dec)
                        except Exception as e:
                            res["errores"].append((str(rel), f"falló descifrado de la parte {parte}: {e}"))
                            error_descarga = True
                            break
                        finally:
                            tmp_parte.unlink(missing_ok=True)

                if error_descarga:
                    (destino / rel).unlink(missing_ok=True)
                else:
                    res["archivos"] += 1
            except Exception as e:
                res["errores"].append((str(rel), f"error general de restauración multipart: {e}"))
                (destino / rel).unlink(missing_ok=True)

        else:
            tmp = tmp_dir / f"desc_{uuid_h}.bin"
            if not dav.get_a_archivo(f"d/{uuid_h}/{uuid_h}.bin", tmp, prog):
                res["errores"].append((str(rel), "no se pudo descargar (archivo legado)"))
            else:
                t0_dec = time.monotonic()
                prog.poner("descifrando legado", rel.name)
                if descifrar_contenido(tmp, llaves.gcm, destino / rel, uuid_h):
                    res["archivos"] += 1
                else:
                    res["errores"].append((str(rel), "falló el descifrado (GCM legado)"))
                prog.sumar_preparacion(time.monotonic() - t0_dec)
            tmp.unlink(missing_ok=True)

        prog.archivo_listo()

    prog.poner("", "")
    prog.refrescar(True)
    res["bytes"] = prog.bytes_hechos
    res["segundos"] = prog.transcurrido()
    res["segundos_preparacion"] = round(prog.segundos_preparacion, 2)
    res["vel_media"] = prog.velocidad_media()
    return res


# ===========================================================================
# Menú de consola (para probar en la PC antes de pasar a Flet)
# ===========================================================================
def _si_no(pregunta: str, defecto: bool = False) -> bool:
    sufijo = "[S/n]" if defecto else "[s/N]"
    try:
        r = input(f"{pregunta} {sufijo}: ").strip().lower()
    except (EOFError, KeyboardInterrupt):
        return False
    return defecto if not r else r in ("s", "si", "sí", "y", "yes")


def _pedir_frase_nueva() -> str:
    while True:
        f1 = getpass.getpass("Frase nueva (mínimo 12 caracteres): ")
        f2 = getpass.getpass("Repite la frase: ")
        if f1 != f2:
            print("❌ No coinciden.")
        elif len(f1) < 12:
            print("❌ Muy corta.")
        else:
            return f1


def _crear_cliente() -> ClienteWebDAV:
    cargar_env(Path(__file__).resolve().parent / ".env")
    url = os.environ.get("WEBDAV_URL", "").strip()
    usr = os.environ.get("WEBDAV_USER", "").strip()
    pwd = os.environ.get("WEBDAV_PASSWORD", "")
    carpeta = os.environ.get("VAULT_DIR", VAULT_DIR).strip() or VAULT_DIR
    if not (url and usr and pwd):
        print("❌ Faltan WEBDAV_URL, WEBDAV_USER o WEBDAV_PASSWORD en el archivo .env")
        sys.exit(1)
    return ClienteWebDAV(url, usr, pwd, carpeta)


def _desbloquear(dav: ClienteWebDAV) -> Llaves:
    if not boveda_existe(dav):
        print("ℹ Aún no hay bóveda en la nube.")
        if not _si_no("¿Crear una bóveda nueva?", True):
            sys.exit(0)
        frase = _pedir_frase_nueva()
        print("Creando bóveda...")
        llaves, rec = crear_boveda_nube(dav, frase)
        print("\n" + "=" * 62)
        print(" CLAVE DE RECUPERACIÓN (guárdala AHORA en lugar seguro):")
        print(f"\n   {rec}\n")
        print(" Sin la frase y sin esta clave, los datos no se pueden recuperar.")
        print("=" * 62)
        input("Presiona Enter cuando la hayas guardado...")
        return llaves
    for _ in range(3):
        frase = getpass.getpass("Frase de la bóveda (o escribe R para usar la clave de recuperación): ")
        try:
            if frase.strip().upper() == "R":
                rec = input("Clave de recuperación: ")
                return abrir_boveda_nube(dav, recuperacion=rec)
            return abrir_boveda_nube(dav, frase=frase)
        except ErrorBoveda as e:
            print(f"❌ {e}")
    sys.exit(1)


def _resumen_final(res: dict):
    if "segundos_preparacion" in res:
        print(f"   Prep/cifrado : {fmt_tiempo(res['segundos_preparacion'])}")
    print(f"   Tiempo subida: {fmt_tiempo(res['segundos'])}")
    print(f"   Transferido  : {fmt_bytes(res['bytes'])}")
    mbps = res["vel_media"] * 8 / 1_000_000
    print(f"   Velocidad    : {fmt_bytes(res['vel_media'])}/s promedio ({mbps:.1f} Mbps)")


def menu():
    print("=" * 60)
    print(" Mixcoara - Bóveda compartida cifrada sobre WebDAV")
    print("=" * 60)
    dav = _crear_cliente()
    try:
        llaves = _desbloquear(dav)
    except (ErrorBoveda, ErrorRed) as e:
        print(f"❌ {e}")
        return
    print(f"✅ Bóveda abierta (id {llaves.vault_id[:8]}...)")
    barra = BarraConsola()
    prog = Progreso(barra)

    while True:
        print("\n [1] Subir carpeta a la bóveda")
        print(" [2] Restaurar toda la bóveda en una carpeta")
        print(" [3] Cambiar frase")
        print(" [0] Salir")
        op = input("Opción [0]: ").strip() or "0"
        try:
            if op == "1":
                ruta = input("Ruta de la carpeta a subir: ").strip().strip('"')
                if not ruta:
                    continue
                carpeta = Path(ruta).expanduser()
                if not carpeta.is_dir():
                    print(f"❌ No es una carpeta válida: {carpeta}")
                    continue
                resumen = escanear_origen(carpeta, prog)
                barra.terminar()
                print(f"Se subirán {resumen['archivos']} archivos en {resumen['carpetas']} carpetas "
                      f"({fmt_bytes(resumen['bytes'])}). Omitidos (ocultos/enlaces): {resumen['omitidos']}.")
                if not _si_no("¿Continuar?", True):
                    continue
                verificar = _si_no("¿Verificar cada archivo tras subirlo?", True)
                res = subir_carpeta(dav, llaves, carpeta, verificar, prog, resumen)
                barra.terminar()
                print(f"\n📊 Subidos: {res['ok']} | Carpetas: {res['carpetas']} | "
                      f"Verificados: {res['verificados']} | Fallidos: {len(res['fallidos'])}")
                _resumen_final(res)
                for r, e in res["fallidos"][:10]:
                    print(f"   ⚠ {r}: {e}")
            elif op == "2":
                dest = input("Carpeta destino [./restaurado]: ").strip().strip('"') or "./restaurado"
                destino = Path(dest).expanduser()
                if destino.exists() and any(destino.iterdir()):
                    print("⚠ La carpeta destino ya tiene contenido; los archivos con el mismo nombre se reemplazarán.")
                    if not _si_no("¿Continuar?", False):
                        continue
                res = restaurar(dav, llaves, destino, prog)
                barra.terminar()
                print(f"\n📊 Carpetas: {res['dirs']} | Archivos: {res['archivos']} | "
                      f"Errores: {len(res['errores'])}")
                _resumen_final(res)
                for r, e in res["errores"][:10]:
                    print(f"   ⚠ {r}: {e}")
            elif op == "3":
                actual = getpass.getpass("Frase actual: ")
                nueva = _pedir_frase_nueva()
                cambiar_frase_nube(dav, actual, nueva)
                print("✅ Frase cambiada. La clave de recuperación sigue igual.")
            elif op == "0":
                print("👋 Hasta luego.")
                return
        except (ErrorBoveda, ErrorRed) as e:
            barra.terminar()
            print(f"❌ {e}")
        except KeyboardInterrupt:
            barra.terminar()
            print("\n⛔ Operación interrumpida. Lo ya subido queda en la nube; puedes reintentar.")


if __name__ == "__main__":
    menu()
