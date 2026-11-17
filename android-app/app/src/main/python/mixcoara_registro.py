#!/usr/bin/env python3
"""
mixcoara_registro.py - Registro de actividad de Mixcoara.

Guarda en un archivo con rotación (mixcoara.log, 4 copias de hasta 1 MB):
subidas, descargas, cifrado local, cambios de permisos y, sobre todo, errores
con todos los detalles disponibles (tipo de excepción, traceback, códigos HTTP,
rutas, tamaños, espacio libre, etc.).

Nunca se escriben contraseñas, frases ni claves: cualquier dato cuyo nombre
contenga "password", "frase", "clave" o "token" se reemplaza por ***.

Ninguna función de este módulo lanza excepciones: si no se puede escribir el
registro, la app sigue funcionando.
"""

import logging
import logging.handlers
import os
import platform
import shutil
import sys
import threading
import time
import traceback
from pathlib import Path
from typing import List, Optional

import mixcoara_config as cfgm

MAX_BYTES = 1_000_000
COPIAS = 4
_SECRETOS = ("password", "frase", "clave", "token", "secret")

_lock = threading.Lock()
_logger: Optional[logging.Logger] = None


def ruta_log() -> Path:
    return cfgm.dir_privado() / "logs" / "mixcoara.log"


def _obtener() -> logging.Logger:
    global _logger
    if _logger is not None:
        return _logger
    with _lock:
        if _logger is not None:
            return _logger
        lg = logging.getLogger("mixcoara")
        lg.setLevel(logging.DEBUG)
        lg.propagate = False
        try:
            ruta = ruta_log()
            ruta.parent.mkdir(parents=True, exist_ok=True)
            h = logging.handlers.RotatingFileHandler(
                ruta, maxBytes=MAX_BYTES, backupCount=COPIAS, encoding="utf-8")
            h.setFormatter(logging.Formatter(
                "%(asctime)s | %(levelname)-7s | %(message)s", "%Y-%m-%d %H:%M:%S"))
            lg.addHandler(h)
        except Exception:
            lg.addHandler(logging.NullHandler())
        _logger = lg
    return _logger


def _valor(v) -> str:
    s = str(v).replace("\r", " ").replace("\n", " ")
    return s if len(s) <= 600 else s[:600] + "…[recortado]"


def _linea(evento: str, datos: dict) -> str:
    partes = [evento]
    for k, v in datos.items():
        if any(s in k.lower() for s in _SECRETOS):
            v = "***"
        partes.append(f"{k}={_valor(v)}")
    return " | ".join(partes)


def _escribir(nivel: int, evento: str, datos: dict, exc: Optional[BaseException] = None) -> None:
    try:
        texto = _linea(evento, datos)
        if exc is not None:
            texto += "\n" + "".join(traceback.format_exception(type(exc), exc, exc.__traceback__)).rstrip()
        _obtener().log(nivel, texto)
    except Exception:
        pass


def info(evento: str, **datos) -> None:
    _escribir(logging.INFO, evento, datos)


def aviso(evento: str, **datos) -> None:
    _escribir(logging.WARNING, evento, datos)


def error(evento: str, exc: Optional[BaseException] = None, **datos) -> None:
    """Registra un error con el contexto más completo posible."""
    try:
        if exc is not None:
            datos.setdefault("excepcion", type(exc).__name__)
            datos.setdefault("mensaje", str(exc))
            errno = getattr(exc, "errno", None)
            if errno is not None:
                datos.setdefault("errno", errno)
        try:
            carpeta = cfgm.dir_descargas_mixcoara()
            libre = shutil.disk_usage(str(carpeta if carpeta.exists() else Path.home())).free
            datos.setdefault("espacio_libre_MB", round(libre / 1_048_576))
        except Exception:
            pass
        datos.setdefault("hilo", threading.current_thread().name)
    except Exception:
        pass
    _escribir(logging.ERROR, evento, datos, exc)


# ---------------------------------------------------------------------------
# Lectura, exportación y limpieza
# ---------------------------------------------------------------------------
def cabecera(version: str = "") -> str:
    lineas = [
        "=== Mixcoara: diagnóstico ===",
        f"Versión: {version}",
        f"Fecha: {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"Python: {sys.version.split()[0]} | {platform.platform()}",
        f"Android: {cfgm.es_android()}",
        f"Rutas: {cfgm.INFO_RUTA}",
        f"Estado de la configuración: {cfgm.ESTADO_LECTURA}",
        f"Configuración cifrada: {cfgm.hay_clave()}",
        f"REQUESTS_CA_BUNDLE: {os.environ.get('REQUESTS_CA_BUNDLE', '(no definido)')}",
    ]
    return "\n".join(lineas) + "\n"


def _archivos() -> List[Path]:
    """Del más viejo al más nuevo."""
    try:
        base = ruta_log()
        viejos = [base.with_name(f"{base.name}.{i}") for i in range(COPIAS, 0, -1)]
        return [p for p in viejos + [base] if p.is_file()]
    except Exception:
        return []


def leer(max_lineas: int = 400) -> str:
    try:
        lineas: List[str] = []
        for p in reversed(_archivos()):
            lineas = p.read_text(encoding="utf-8", errors="replace").splitlines() + lineas
            if len(lineas) >= max_lineas:
                break
        if not lineas:
            return "(el registro está vacío)"
        return "\n".join(lineas[-max_lineas:])
    except Exception as ex:
        return f"(no se pudo leer el registro: {ex})"


def exportar(version: str = "") -> Path:
    """Copia todo el registro, con cabecera de diagnóstico, a Descargas/Mixcoara/registros/."""
    destino_dir = cfgm.dir_descargas_mixcoara() / "registros"
    destino_dir.mkdir(parents=True, exist_ok=True)
    destino = destino_dir / f"mixcoara_registro_{time.strftime('%Y%m%d_%H%M%S')}.txt"
    with open(destino, "w", encoding="utf-8") as f:
        f.write(cabecera(version) + "\n")
        for p in _archivos():
            f.write(f"\n----- {p.name} -----\n")
            f.write(p.read_text(encoding="utf-8", errors="replace"))
    return destino


def limpiar() -> None:
    global _logger
    try:
        with _lock:
            if _logger is not None:
                for h in list(_logger.handlers):
                    h.close()
                    _logger.removeHandler(h)
                _logger = None
        for p in _archivos():
            p.unlink(missing_ok=True)
    except Exception:
        pass
