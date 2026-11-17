#!/usr/bin/env python3
"""
mixcoara_menu.py - Menú principal con explorador de la bóveda (estilo Cryptomator).

Requiere en la misma carpeta: mixcoara_claves.py y mixcoara_nube.py (sin cambios).
Ejecuta este archivo en lugar de mixcoara_nube.py:   python mixcoara_menu.py
"""

import re
import sys
import time
import uuid
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import mixcoara_nube as mn
import mixcoara_registro as reg
from mixcoara_nube import (BarraConsola, ClienteWebDAV, ErrorBoveda, ErrorRed,
                           Llaves, Progreso, fmt_bytes)

SOBRECARGA_GCM = 28   # 12 de nonce + 16 de etiqueta

# Tiempo máximo (segundos) que se espera al servidor en CADA operación de red.
# Si pasa ese tiempo sin recibir nada, se reintenta en lugar de quedarse colgado.
TIMEOUT_LECTURA = 60
TIMEOUT_ESCRITURA = 300


# ===========================================================================
# Cliente WebDAV que no se queda colgado y reintenta
# ===========================================================================
class ClienteWebDAVRobusto(ClienteWebDAV):
    def _abrir(self, metodo, rel, data=None, headers=None, timeout=60):
        tope = TIMEOUT_ESCRITURA if metodo == "PUT" else TIMEOUT_LECTURA
        return super()._abrir(metodo, rel, data, headers, min(timeout, tope))

    @staticmethod
    def _con_reintentos(funcion, *args):
        ultimo = None
        for i in range(3):
            try:
                return funcion(*args)
            except (ErrorRed, OSError) as e:
                ultimo = e
                time.sleep(2 * (i + 1))
        raise ErrorRed(f"El servidor no respondió ({ultimo})")

    def listar_detalle(self, rel):
        return self._con_reintentos(super().listar_detalle, rel)

    def existe(self, rel):
        return self._con_reintentos(super().existe, rel)

    def mkcol(self, rel):
        return self._con_reintentos(super().mkcol, rel)

    def borrar(self, rel):
        return self._con_reintentos(super().borrar, rel)


# ===========================================================================
# Progreso: porcentaje y tiempo restante más realistas + aviso de "sin avance"
# ===========================================================================
class ProgresoMejorado(Progreso):
    UMBRAL_SIN_AVANCE = 25   # segundos sin movimiento antes de avisar en la barra

    def __init__(self, al_cambiar=None):
        self._ultimo_avance = time.monotonic()
        self._estado_guardado = None
        super().__init__(al_cambiar)
        threading.Thread(target=self._latido, daemon=True).start()

    def _marcar(self):
        self._ultimo_avance = time.monotonic()
        if self._estado_guardado is not None:
            self.estado, self._estado_guardado = self._estado_guardado, None

    def _latido(self):
        while True:
            time.sleep(1)
            if not self.fase:
                continue
            parado = time.monotonic() - self._ultimo_avance
            if parado >= self.UMBRAL_SIN_AVANCE:
                if self._estado_guardado is None:
                    self._estado_guardado = self.estado
                self.estado = f"esperando respuesta hace {int(parado)} s"
                self.refrescar(True)

    def iniciar(self, *args, **kwargs):
        self._estado_guardado = None
        self._ultimo_avance = time.monotonic()
        super().iniciar(*args, **kwargs)

    def poner(self, estado, actual=""):
        self._marcar()
        super().poner(estado, actual)

    def avanzar_bytes(self, n):
        if n > 0:
            self._marcar()
        super().avanzar_bytes(n)

    def archivo_listo(self):
        self._marcar()
        super().archivo_listo()

    def carpeta_lista(self):
        self._marcar()
        super().carpeta_lista()

    def fraccion(self) -> float:
        """El porcentaje real se calcula con respecto a los bytes totales transferidos."""
        if self.bytes_total > 0:
            return min(1.0, max(0.0, self.bytes_hechos / self.bytes_total))
        if self.archivos_total > 0:
            return min(1.0, max(0.0, self.archivos_hechos / self.archivos_total))
        return 0.0

    def eta(self) -> Optional[float]:
        fr = self.fraccion()
        if fr < 0.02 or self.transcurrido() < 3:
            return None
        return self.transcurrido() * (1 - fr) / fr


# ===========================================================================
# Explorador: lista UNA carpeta de la bóveda con los nombres ya descifrados
# ===========================================================================
@dataclass
class Entrada:
    nombre: str
    es_dir: bool
    uuid: str        # identificador del nodo en la nube
    tamano: int      # bytes del archivo ya descifrado (0 en carpetas)
    padre: str       # uuid de la carpeta que lo contiene


def listar_carpeta(dav: ClienteWebDAV, llaves: Llaves,
                   uuid_dir: str) -> Tuple[List[Entrada], List[Tuple[str, str]]]:
    """Devuelve (entradas ordenadas: carpetas primero, errores)."""
    nombres = [n for n, es_dir, _ in dav.listar_detalle(f"d/{uuid_dir}")
               if n != "dir.c9r" and not es_dir and n.endswith(".c9r")]

    def _una(nombre_cif: str):
        datos = dav.get_bytes(f"d/{uuid_dir}/{nombre_cif}")
        if datos is None:
            return None, (nombre_cif, "no se pudo bajar el .c9r")
        uuid_hijo = datos.decode("utf-8", errors="ignore").strip()
        try:
            uuid.UUID(uuid_hijo)
            nombre = mn.descifrar_nombre(nombre_cif, llaves.siv, uuid_dir)
        except Exception as e:
            return None, (nombre_cif, f"nombre inválido: {e}")
        if not mn._nombre_seguro(nombre):
            return None, (nombre_cif, "nombre con caracteres peligrosos")
        tam = None
        partes_count = 0
        for n, d, t in dav.listar_detalle(f"d/{uuid_hijo}"):
            if (n == f"{uuid_hijo}.bin" or n.endswith(".part")) and not d:
                tam = (tam or 0) + t
                partes_count += 1
        if tam is not None:
            sobrecarga = max(1, partes_count) * SOBRECARGA_GCM
            return Entrada(nombre, False, uuid_hijo, max(0, tam - sobrecarga), uuid_dir), None
        return Entrada(nombre, True, uuid_hijo, 0, uuid_dir), None

    with ThreadPoolExecutor(max_workers=6) as pool:
        resultados = list(pool.map(_una, nombres))
    entradas = [e for e, _ in resultados if e]
    errores = [err for _, err in resultados if err]
    entradas.sort(key=lambda e: (not e.es_dir, e.nombre.lower()))
    return entradas, errores


# ===========================================================================
# Descarga de una selección (archivos sueltos y/o carpetas completas)
# ===========================================================================
def _nombres_unicos(entradas: List[Entrada]) -> List[Entrada]:
    """Si dos elementos elegidos se llaman igual, agrega ' (2)', ' (3)'..."""
    usados: Dict[str, int] = {}
    salida = []
    for e in entradas:
        clave = e.nombre.lower()
        usados[clave] = usados.get(clave, 0) + 1
        if usados[clave] == 1:
            salida.append(e)
            continue
        p = Path(e.nombre)
        nuevo = f"{p.stem} ({usados[clave]}){p.suffix}" if not e.es_dir else f"{e.nombre} ({usados[clave]})"
        salida.append(replace(e, nombre=nuevo))
    return salida


def planificar_entradas(dav: ClienteWebDAV, llaves: Llaves, entradas: List[Entrada],
                        prog: Progreso):
    t0_plan = time.monotonic()
    prog.iniciar("Leyendo selección")
    dirs: List[Path] = []
    archivos: List[Tuple[str, Path, int]] = []
    errores: List[Tuple[str, str]] = []

    def _actualizar():
        prog.archivos_hechos, prog.carpetas_hechas = len(archivos), len(dirs)
        prog.refrescar()

    def _rec(carpeta: Entrada, rel: Path):
        hijos, errs = listar_carpeta(dav, llaves, carpeta.uuid)
        errores.extend(errs)
        for h in hijos:
            r = rel / h.nombre
            if h.es_dir:
                dirs.append(r)
                _actualizar()
                _rec(h, r)
            else:
                archivos.append((h.uuid, r, h.tamano + SOBRECARGA_GCM))
                _actualizar()

    for e in entradas:
        rel = Path(e.nombre)
        if e.es_dir:
            dirs.append(rel)
            _rec(e, rel)
        else:
            archivos.append((e.uuid, rel, e.tamano + SOBRECARGA_GCM))
        _actualizar()
    prog.sumar_preparacion(time.monotonic() - t0_plan)
    prog.refrescar(True)
    return dirs, archivos, errores


def descargar_entradas(dav: ClienteWebDAV, llaves: Llaves, entradas: List[Entrada],
                       destino: Path, prog: Optional[Progreso] = None,
                       tmp_dir: Optional[Path] = None) -> dict:
    prog = prog or Progreso()
    entradas = _nombres_unicos(entradas)
    dirs, archivos, errores = planificar_entradas(dav, llaves, entradas, prog)
    destino.mkdir(parents=True, exist_ok=True)
    total_bytes = sum(t for _, _, t in archivos)
    prog.iniciar("Descargando", len(archivos), len(dirs), total_bytes)
    res = {"dirs": 0, "archivos": 0, "errores": list(errores)}
    tmp_dir = tmp_dir or mn.directorio_temporal()
    reg.info("descarga_inicio", archivos=len(archivos), carpetas=len(dirs), bytes=total_bytes,
             destino=destino, errores_previos=len(errores))
    for rel_err, motivo_err in errores:
        reg.error("descarga_lectura_estructura", elemento=rel_err, motivo=motivo_err)

    for d in dirs:
        (destino / d).mkdir(parents=True, exist_ok=True)
        res["dirs"] += 1
        prog.carpeta_lista()

    for uuid_h, rel, _tam in archivos:
        prog.poner("descargando", rel.name)
        detalle_nodo = dav.listar_detalle(f"d/{uuid_h}")
        partes = sorted([n for n, d, _t in detalle_nodo if n.endswith(".part") and not d])

        if partes:
            destino_arch = destino / rel
            destino_arch.parent.mkdir(parents=True, exist_ok=True)
            try:
                with open(destino_arch, "wb") as f_out:
                    error_descarga = False
                    for parte in partes:
                        prog.poner("descargando parte", f"{rel.name} ({parte})")
                        tmp_parte = tmp_dir / f"desc_{uuid_h}_{parte}"
                        if not dav.get_a_archivo(f"d/{uuid_h}/{parte}", tmp_parte, prog):
                            causa = getattr(dav, "ultimo_error", "")
                            res["errores"].append((str(rel), f"falló la descarga de {parte}" + (f" ({causa})" if causa else "")))
                            error_descarga = True
                            break
                        t0_dec = time.monotonic()
                        try:
                            tam_parte = tmp_parte.stat().st_size
                            with open(tmp_parte, "rb") as fi:
                                nonce = fi.read(mn.GCM_NONCE_SIZE)
                                fi.seek(tam_parte - mn.GCM_TAG_SIZE)
                                tag = fi.read(mn.GCM_TAG_SIZE)
                                fi.seek(mn.GCM_NONCE_SIZE)
                                ct = fi.read(tam_parte - mn.GCM_NONCE_SIZE - mn.GCM_TAG_SIZE)
                            indice = int(parte.split(".")[0])
                            aad = f"{uuid_h}:{indice}".encode("utf-8")
                            c = mn.AES.new(llaves.gcm, mn.AES.MODE_GCM, nonce=nonce)
                            c.update(aad)
                            claro = c.decrypt_and_verify(ct, tag)
                            f_out.write(claro)
                            prog.sumar_preparacion(time.monotonic() - t0_dec)
                        except Exception as ex:
                            res["errores"].append((str(rel), f"falló el descifrado de {parte} ({ex})"))
                            error_descarga = True
                            break
                        finally:
                            tmp_parte.unlink(missing_ok=True)
                if error_descarga:
                    destino_arch.unlink(missing_ok=True)
                else:
                    res["archivos"] += 1
                    reg.info("descarga_archivo_ok", archivo=rel, id=uuid_h, destino=destino_arch)
            except Exception as ex:
                destino_arch.unlink(missing_ok=True)
                res["errores"].append((str(rel), f"error al restaurar multipart ({ex})"))
            finally:
                prog.archivo_listo()
        else:
            tmp = tmp_dir / f"desc_{uuid_h}.bin"
            try:
                if not dav.get_a_archivo(f"d/{uuid_h}/{uuid_h}.bin", tmp, prog):
                    causa = getattr(dav, "ultimo_error", "")
                    res["errores"].append((str(rel), "no se pudo descargar" + (f" ({causa})" if causa else "")))
                    reg.error("descarga_archivo_fallo", archivo=rel, id=uuid_h, causa=causa)
                else:
                    t0_dec = time.monotonic()
                    prog.poner("descifrando", rel.name)
                    if mn.descifrar_contenido(tmp, llaves.gcm, destino / rel, uuid_h):
                        res["archivos"] += 1
                        reg.info("descarga_archivo_ok", archivo=rel, id=uuid_h, destino=destino / rel)
                    else:
                        causa = mn.ULTIMO_ERROR_CRIPTO or "validación GCM"
                        res["errores"].append((str(rel), f"falló el descifrado ({causa})"))
                        reg.error("descarga_descifrado_fallo", archivo=rel, id=uuid_h, causa=causa)
                    prog.sumar_preparacion(time.monotonic() - t0_dec)
            finally:
                tmp.unlink(missing_ok=True)
                prog.archivo_listo()

    prog.poner("", "")
    prog.refrescar(True)
    res["bytes"] = prog.bytes_hechos
    res["segundos"] = prog.transcurrido()
    res["segundos_preparacion"] = round(prog.segundos_preparacion, 2)
    res["vel_media"] = prog.velocidad_media()
    reg.info("descarga_fin", archivos_ok=res["archivos"], carpetas=res["dirs"],
             errores=len(res["errores"]), bytes=res["bytes"],
             segundos_preparacion_descifrado=res["segundos_preparacion"],
             segundos_descarga_exclusiva=round(res["segundos"], 2))
    return res


def descargar_todo(dav: ClienteWebDAV, llaves: Llaves, destino: Path,
                   prog: Optional[Progreso] = None,
                   tmp_dir: Optional[Path] = None) -> dict:
    """Restaura toda la bóveda leyendo la estructura en paralelo."""
    prog = prog or Progreso()
    t0_read = time.monotonic()
    prog.iniciar("Leyendo bóveda")
    raiz, errores = listar_carpeta(dav, llaves, llaves.root_uuid)
    prog.sumar_preparacion(time.monotonic() - t0_read)
    res = descargar_entradas(dav, llaves, raiz, destino, prog, tmp_dir=tmp_dir)
    res["errores"] = list(errores) + res["errores"]
    return res


# ===========================================================================
# Explorador en consola
# ===========================================================================
def _indices(texto: str, maximo: int) -> List[int]:
    """Convierte '1,3,5-7' en [1,3,5,6,7] (solo valores válidos)."""
    salida: List[int] = []
    for trozo in re.split(r"[,\s]+", texto.strip()):
        if not trozo:
            continue
        m = re.fullmatch(r"(\d+)-(\d+)", trozo)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            salida.extend(range(min(a, b), max(a, b) + 1))
        elif trozo.isdigit():
            salida.append(int(trozo))
    return [i for i in salida if 1 <= i <= maximo]


def _corto(texto: str, n: int = 58) -> str:
    return texto if len(texto) <= n else texto[: n - 1] + "…"


def _ayuda_explorador():
    print("""
  Comandos del explorador:
    <número>      abre la carpeta, o marca/desmarca el archivo
    m 2,4,6-9     marca o desmarca varios elementos (archivos o carpetas)
    t             marca (o desmarca) todo lo de esta carpeta
    l             limpia todas las marcas
    v             muestra lo marcado
    d             descarga lo marcado
    ..            sube un nivel
    q             vuelve al menú
    ?             esta ayuda""")


def explorar(dav: ClienteWebDAV, llaves: Llaves, prog: Progreso, barra: BarraConsola):
    pila: List[Tuple[str, str]] = [(llaves.root_uuid, "")]
    cache: Dict[str, List[Entrada]] = {}
    seleccion: Dict[str, Tuple[Entrada, str]] = {}   # uuid -> (entrada, ruta mostrada)
    _ayuda_explorador()

    while True:
        uuid_dir = pila[-1][0]
        ruta = "/" + "/".join(n for _, n in pila[1:])
        if uuid_dir not in cache:
            print("\nLeyendo carpeta...")
            entradas, errores = listar_carpeta(dav, llaves, uuid_dir)
            cache[uuid_dir] = entradas
            for r, e in errores[:5]:
                print(f"   ⚠ {r}: {e}")
        entradas = cache[uuid_dir]

        print(f"\n📂 {ruta}   ({len(entradas)} elementos, {len(seleccion)} marcados en total)")
        if not entradas:
            print("   (vacía)")
        for i, e in enumerate(entradas, 1):
            marca = "[x]" if e.uuid in seleccion else "[ ]"
            if e.es_dir:
                print(f" {marca} {i:3d}  📁 {_corto(e.nombre)}/")
            else:
                print(f" {marca} {i:3d}  📄 {_corto(e.nombre):<58} {fmt_bytes(e.tamano):>10}")

        try:
            cmd = input("\nexplorador> ").strip()
        except (EOFError, KeyboardInterrupt):
            return
        bajo = cmd.lower()

        if bajo in ("q", "salir"):
            return
        if bajo == "?":
            _ayuda_explorador()
        elif bajo in ("..", "b"):
            if len(pila) > 1:
                pila.pop()
            else:
                print("Ya estás en la raíz.")
        elif bajo == "l":
            seleccion.clear()
            print("Marcas limpiadas.")
        elif bajo == "v":
            if not seleccion:
                print("No hay nada marcado.")
            for e, r in seleccion.values():
                print(f"  {'📁' if e.es_dir else '📄'} {r}")
        elif bajo == "t":
            todos = all(e.uuid in seleccion for e in entradas)
            for e in entradas:
                if todos:
                    seleccion.pop(e.uuid, None)
                else:
                    seleccion[e.uuid] = (e, f"{ruta}/{e.nombre}")
        elif bajo == "d":
            _descargar_marcados(dav, llaves, prog, barra, seleccion)
        elif bajo.startswith("m ") or re.fullmatch(r"[\d,\s-]+", bajo) and re.search(r"[,-]", bajo):
            texto = bajo[2:] if bajo.startswith("m ") else bajo
            for i in _indices(texto, len(entradas)):
                e = entradas[i - 1]
                if e.uuid in seleccion:
                    del seleccion[e.uuid]
                else:
                    seleccion[e.uuid] = (e, f"{ruta}/{e.nombre}")
        elif bajo.isdigit():
            idx = _indices(bajo, len(entradas))
            if not idx:
                print("Número fuera de rango.")
                continue
            e = entradas[idx[0] - 1]
            if e.es_dir:
                pila.append((e.uuid, e.nombre))
            elif e.uuid in seleccion:
                del seleccion[e.uuid]
            else:
                seleccion[e.uuid] = (e, f"{ruta}/{e.nombre}")
        elif bajo:
            print("Comando no reconocido. Escribe ? para ver la ayuda.")


def _descargar_marcados(dav, llaves, prog, barra, seleccion):
    if not seleccion:
        print("No hay nada marcado. Marca archivos o carpetas primero.")
        return
    elegidas = [e for e, _ in seleccion.values()]
    n_arch = sum(1 for e in elegidas if not e.es_dir)
    n_dir = len(elegidas) - n_arch
    peso = sum(e.tamano for e in elegidas if not e.es_dir)
    print(f"\nSe descargarán {n_arch} archivos sueltos ({fmt_bytes(peso)}) y {n_dir} carpetas completas.")
    dest = input("Carpeta destino [./descargas]: ").strip().strip('"') or "./descargas"
    destino = Path(dest).expanduser()
    existentes = [e.nombre for e in elegidas if (destino / e.nombre).exists()]
    if existentes:
        print(f"⚠ Ya existen en el destino: {', '.join(existentes[:5])}"
              f"{'...' if len(existentes) > 5 else ''}. Se reemplazarán los archivos con el mismo nombre.")
        if not mn._si_no("¿Continuar?", False):
            return
    try:
        res = descargar_entradas(dav, llaves, elegidas, destino, prog)
    finally:
        barra.terminar()
    print(f"\n📊 Carpetas: {res['dirs']} | Archivos: {res['archivos']} | Errores: {len(res['errores'])}")
    mn._resumen_final(res)
    for r, e in res["errores"][:10]:
        print(f"   ⚠ {r}: {e}")
    print(f"   Guardado en: {destino.resolve()}")
    if mn._si_no("¿Limpiar las marcas?", True):
        seleccion.clear()


# ===========================================================================
# Menú principal
# ===========================================================================
def menu():
    print("=" * 60)
    print(" mixcoara - Bóveda compartida cifrada sobre WebDAV")
    print("=" * 60)
    base = mn._crear_cliente()
    dav = ClienteWebDAVRobusto.__new__(ClienteWebDAVRobusto)
    dav.__dict__.update(base.__dict__)
    try:
        llaves = mn._desbloquear(dav)
    except (ErrorBoveda, ErrorRed) as e:
        print(f"❌ {e}")
        return
    print(f"✅ Bóveda abierta (id {llaves.vault_id[:8]}...)")
    barra = BarraConsola()
    prog = ProgresoMejorado(barra)

    while True:
        print("\n [1] Subir carpeta a la bóveda")
        print(" [2] Descargar TODO (restaurar la bóveda completa)")
        print(" [3] Explorar la bóveda y descargar solo lo que elija")
        print(" [4] Cambiar frase")
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
                resumen = mn.escanear_origen(carpeta, prog)
                barra.terminar()
                print(f"Se subirán {resumen['archivos']} archivos en {resumen['carpetas']} carpetas "
                      f"({fmt_bytes(resumen['bytes'])}). Omitidos (ocultos/enlaces): {resumen['omitidos']}.")
                if not mn._si_no("¿Continuar?", True):
                    continue
                verificar = mn._si_no("¿Verificar cada archivo tras subirlo?", True)
                res = mn.subir_carpeta(dav, llaves, carpeta, verificar, prog, resumen)
                barra.terminar()
                print(f"\n📊 Subidos: {res['ok']} | Carpetas: {res['carpetas']} | "
                      f"Verificados: {res['verificados']} | Fallidos: {len(res['fallidos'])}")
                mn._resumen_final(res)
                for r, e in res["fallidos"][:10]:
                    print(f"   ⚠ {r}: {e}")
            elif op == "2":
                dest = input("Carpeta destino [./restaurado]: ").strip().strip('"') or "./restaurado"
                destino = Path(dest).expanduser()
                if destino.exists() and any(destino.iterdir()):
                    print("⚠ La carpeta destino ya tiene contenido; los archivos con el mismo nombre se reemplazarán.")
                    if not mn._si_no("¿Continuar?", False):
                        continue
                res = descargar_todo(dav, llaves, destino, prog)
                barra.terminar()
                print(f"\n📊 Carpetas: {res['dirs']} | Archivos: {res['archivos']} | "
                      f"Errores: {len(res['errores'])}")
                mn._resumen_final(res)
                for r, e in res["errores"][:10]:
                    print(f"   ⚠ {r}: {e}")
            elif op == "3":
                explorar(dav, llaves, prog, barra)
            elif op == "4":
                import getpass
                actual = getpass.getpass("Frase actual: ")
                nueva = mn._pedir_frase_nueva()
                mn.cambiar_frase_nube(dav, actual, nueva)
                print("✅ Frase cambiada. La clave de recuperación sigue igual.")
            elif op == "0":
                print("👋 Hasta luego.")
                return
        except (ErrorBoveda, ErrorRed) as e:
            barra.terminar()
            print(f"❌ {e}")
        except KeyboardInterrupt:
            barra.terminar()
            print("\n⛔ Operación interrumpida.")


if __name__ == "__main__":
    menu()
