#!/usr/bin/env python3
"""
mixcoara_config.py - Rutas privadas y configuración cifrada de Mixcoara.

- La configuración (URL, usuario, contraseña de WebDAV, etc.) se guarda en
  ~/config_mixcoara/privado/ (almacenamiento privado de la app).
- Si hay una clave disponible (se guarda en el Keystore de Android), el archivo
  se cifra con AES-256-GCM: mixcoara_config.enc. Sin clave, queda como JSON
  (mixcoara_config.json) con permisos 600.
- Si encuentra la configuración vieja en Descargas/Mixcoara/.datos/, la migra
  a la ruta privada y borra el archivo viejo.
"""

import json
import os
from pathlib import Path
from typing import Dict, List, Optional

from Cryptodome.Cipher import AES
from Cryptodome.Random import get_random_bytes

NOMBRE_CIFRADO = "mixcoara_config.enc"
NOMBRE_PLANO = "mixcoara_config.json"
MAGIC = b"MXCFG1\n"

_clave: Optional[bytes] = None
_dir_cache: Optional[Path] = None

# Texto para el registro: qué ruta se eligió y qué se intentó antes.
INFO_RUTA = ""
# Último resultado de leer la configuración (para el registro).
ESTADO_LECTURA = ""


def es_android() -> bool:
    return bool(os.environ.get("MIXCOARA_PRIVATE_DIR")) or Path("/storage/emulated/0/Download").parent.is_dir()


def dir_descargas_mixcoara() -> Path:
    configurada = os.environ.get("MIXCOARA_DOWNLOAD_DIR")
    base = Path(configurada) if configurada else (
        Path("/storage/emulated/0/Download") if es_android() else Path.home() / "Downloads"
    )
    return base / "Mixcoara"


def dir_datos_anterior() -> Path:
    """Ruta usada por las versiones 1.5 y 1.6 (visible para otras apps)."""
    return dir_descargas_mixcoara() / ".datos"


def _candidatos() -> List[Path]:
    lista: List[Path] = []
    configurada = os.environ.get("MIXCOARA_PRIVATE_DIR")
    if configurada:
        lista.append(Path(configurada))
    try:
        lista.append(Path.home() / "config_mixcoara" / "privado")
    except Exception:
        pass
    lista.append(dir_datos_anterior())     # último recurso
    return lista


def dir_privado() -> Path:
    """Primera ruta que se pueda crear y escribir. Se recuerda para no repetir la prueba."""
    global _dir_cache, INFO_RUTA
    if _dir_cache is not None and _dir_cache.is_dir():
        return _dir_cache
    fallos = []
    for d in _candidatos():
        try:
            d.mkdir(parents=True, exist_ok=True)
            prueba = d / ".prueba_escritura"
            prueba.write_text("ok", encoding="utf-8")
            prueba.unlink()
            try:
                os.chmod(d, 0o700)
            except OSError:
                pass
            _dir_cache = d
            INFO_RUTA = f"config en {d}" + (
                f" (se descartaron: {'; '.join(fallos)})" if fallos else "")
            return d
        except Exception as ex:
            fallos.append(f"{d} -> {type(ex).__name__}: {ex}")
    INFO_RUTA = "SIN ruta de configuración escribible: " + "; ".join(fallos)
    raise OSError(INFO_RUTA)


def ruta_es_privada() -> bool:
    try:
        return dir_privado() != dir_datos_anterior()
    except OSError:
        return False


# ---------------------------------------------------------------------------
# Clave de cifrado de la configuración
# ---------------------------------------------------------------------------
def fijar_clave(clave: Optional[bytes]) -> None:
    global _clave
    _clave = clave if clave and len(clave) == 32 else None


def hay_clave() -> bool:
    return _clave is not None


def _cifrar(datos: bytes) -> bytes:
    nonce = get_random_bytes(12)
    c = AES.new(_clave, AES.MODE_GCM, nonce=nonce)
    c.update(MAGIC)
    ct, tag = c.encrypt_and_digest(datos)
    return MAGIC + nonce + ct + tag


def _descifrar(blob: bytes) -> bytes:
    if not blob.startswith(MAGIC) or len(blob) < len(MAGIC) + 12 + 16:
        raise ValueError("formato de configuración cifrada no reconocido")
    cuerpo = blob[len(MAGIC):]
    nonce, ct, tag = cuerpo[:12], cuerpo[12:-16], cuerpo[-16:]
    c = AES.new(_clave, AES.MODE_GCM, nonce=nonce)
    c.update(MAGIC)
    return c.decrypt_and_verify(ct, tag)


# ---------------------------------------------------------------------------
# Lectura y escritura
# ---------------------------------------------------------------------------
def valores_por_defecto() -> Dict[str, str]:
    return {"url": "", "usuario": "", "password": "", "carpeta": "Mixcoara",
            "destino": "", "origen": "", "bg_pedido": ""}


def _escribir_atomico(ruta: Path, datos: bytes) -> None:
    tmp = ruta.with_name(ruta.name + ".tmp")
    tmp.write_bytes(datos)
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        pass
    os.replace(tmp, ruta)


def _aplicar(cfg: Dict[str, str], datos: object) -> None:
    if isinstance(datos, dict):
        cfg.update({k: str(v) for k, v in datos.items() if k in cfg})


def migrar_config_anterior() -> bool:
    """Mueve la configuración vieja de Descargas/Mixcoara/.datos a la ruta privada."""
    try:
        destino_dir = dir_privado()
        viejo_dir = dir_datos_anterior()
        if destino_dir == viejo_dir:
            return False
        viejo = viejo_dir / NOMBRE_PLANO
        if not viejo.is_file():
            return False
        if (destino_dir / NOMBRE_CIFRADO).exists() or (destino_dir / NOMBRE_PLANO).exists():
            viejo.unlink(missing_ok=True)       # ya hay una nueva; la vieja sobra
            return True
        datos = json.loads(viejo.read_text(encoding="utf-8"))
        cfg = valores_por_defecto()
        _aplicar(cfg, datos)
        guardar_config(cfg)
        viejo.unlink(missing_ok=True)
        return True
    except Exception:
        return False


def leer_config() -> Dict[str, str]:
    global ESTADO_LECTURA
    cfg = valores_por_defecto()
    try:
        base = dir_privado()
    except OSError:
        ESTADO_LECTURA = "no hay ruta escribible"
        return cfg

    enc = base / NOMBRE_CIFRADO
    plano = base / NOMBRE_PLANO
    try:
        if enc.is_file():
            if _clave is None:
                ESTADO_LECTURA = "configuración cifrada pero sin clave disponible"
                return cfg
            try:
                _aplicar(cfg, json.loads(_descifrar(enc.read_bytes()).decode("utf-8")))
                ESTADO_LECTURA = "configuración cifrada leída"
            except Exception as ex:
                ilegible = enc.with_name(NOMBRE_CIFRADO + ".ilegible")
                try:
                    os.replace(enc, ilegible)
                except OSError:
                    pass
                ESTADO_LECTURA = (f"configuración cifrada ilegible ({type(ex).__name__}); "
                                  f"se movió a {ilegible.name}")
            return cfg
        if plano.is_file():
            _aplicar(cfg, json.loads(plano.read_text(encoding="utf-8")))
            ESTADO_LECTURA = "configuración en texto plano leída"
            if _clave is not None:
                guardar_config(cfg)             # se actualiza a cifrada
                ESTADO_LECTURA += " y convertida a cifrada"
            return cfg
        ESTADO_LECTURA = "no había configuración guardada"
    except (OSError, ValueError) as ex:
        ESTADO_LECTURA = f"error al leer la configuración: {type(ex).__name__}: {ex}"
    return cfg


def guardar_config(cfg: Dict[str, str]) -> None:
    base = dir_privado()
    datos = json.dumps(cfg, indent=2).encode("utf-8")
    if _clave is not None:
        _escribir_atomico(base / NOMBRE_CIFRADO, _cifrar(datos))
        (base / NOMBRE_PLANO).unlink(missing_ok=True)
    else:
        _escribir_atomico(base / NOMBRE_PLANO, datos)
