#!/usr/bin/env python3
"""
mixcoara_reporte.py - Reporte legible de la bóveda (árbol + tabla de UUIDs).

Dos modos de uso:
  * volcar_reporte_completo(dav, llaves, destino, formato="arbol"):
    Recorre toda la bóveda, descifra nombres y escribe un .txt con:
      - Cabecera (fecha, raíz UUID, totales).
      - Árbol de directorios (como el reporte original de webdavg.py).
      - Tabla UUID → ruta lógica (para recuperación manual).
    Formatos disponibles: "arbol" (por defecto), "tabla", "solo_rutas".
    Funciona también para lo que ya estaba subido antes de esta versión.

  * anotar_en_reporte(ruta_local, id_fisico, tipo, tam):
    Añade una línea al reporte incremental mientras se sube.
    Lo llaman subir_carpeta y subir_archivo desde mixcoara_nube.
    No falla nunca: si no se puede escribir, registra y sigue.

El archivo se guarda en Descargas/Mixcoara/reportes/ con nombre
boveda_YYYYmmdd_HHMMSS.txt, para que se pueda abrir con cualquier visor.
"""

from __future__ import annotations

import time
import uuid as _uuid
from datetime import datetime
from pathlib import Path
from typing import List, Optional, Tuple

import mixcoara_config as cfgm
import mixcoara_nube as mn
import mixcoara_registro as reg
from mixcoara_nube import ClienteWebDAV, Llaves


# ===========================================================================
# Utilidades
# ===========================================================================
def _fmt_ts() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _carpeta_reportes() -> Path:
    """Descargas/Mixcoara/reportes/ — visible desde el gestor de archivos."""
    destino = cfgm.dir_descargas_mixcoara() / "reportes"
    destino.mkdir(parents=True, exist_ok=True)
    return destino


def _nombre_reporte() -> Path:
    sufijo = datetime.now().strftime("%Y%m%d_%H%M%S")
    return _carpeta_reportes() / f"boveda_{sufijo}.txt"


def _tam_logico_archivo(dav: ClienteWebDAV, uuid_arch: str) -> int:
    """Suma los bytes descifrados de todas las partes .part (y del .bin legado)."""
    total = 0
    for nombre, es_dir, tam in dav.listar_detalle(f"d/{uuid_arch}"):
        if es_dir:
            continue
        if nombre.endswith(".part"):
            total += max(0, tam - mn.GCM_NONCE_SIZE - mn.GCM_TAG_SIZE)
        elif nombre == f"{uuid_arch}.bin":
            total += max(0, tam - mn.GCM_NONCE_SIZE - mn.GCM_TAG_SIZE)
    return total


# ===========================================================================
# Recorrido recursivo de la bóveda
# ===========================================================================
def _recorrer(dav: ClienteWebDAV, llaves: Llaves, uuid_dir: str,
              prefijo: str, salida: list, prof_max: int = 128) -> None:
    """
    Llena `salida` con tuplas (uuid, tipo, tam, ruta_logica) recorriendo la
    bóveda igual que lo hace la app al navegar: descifra cada nombre .c9r y
    sigue el UUID del hijo.
    """
    if prof_max <= 0:
        return
    for nombre_cif, es_dir, _t in dav.listar_detalle(f"d/{uuid_dir}"):
        if nombre_cif == "dir.c9r" or es_dir or not nombre_cif.endswith(".c9r"):
            continue
        datos = dav.get_bytes(f"d/{uuid_dir}/{nombre_cif}")
        if datos is None:
            continue
        uuid_hijo = datos.decode("utf-8", errors="ignore").strip()
        try:
            _uuid.UUID(uuid_hijo)
            nombre = mn.descifrar_nombre(nombre_cif, llaves.siv, uuid_dir)
        except Exception:
            continue
        if not mn._nombre_seguro(nombre):
            continue
        ruta = (prefijo.rstrip("/") + "/" + nombre).lstrip("/")
        hijos = dav.listar_detalle(f"d/{uuid_hijo}")
        es_archivo = any(n == f"{uuid_hijo}.bin" and not d for n, d, _ in hijos) or \
                     any(n.endswith(".part") and not d for n, d, _ in hijos)
        if es_archivo:
            salida.append((uuid_hijo, "file", _tam_logico_archivo(dav, uuid_hijo), ruta))
        else:
            salida.append((uuid_hijo, "dir", 0, ruta))
            _recorrer(dav, llaves, uuid_hijo, ruta, salida, prof_max - 1)


# ===========================================================================
# Árbol textual (estilo webdavg.py)
# ===========================================================================
def _generar_arbol(filas: List[Tuple[str, str, int, str]]) -> str:
    """
    Construye el árbol visual con el mismo formato que el reporte original:

        la nada
         |_ audio completo
          |_ 1.m4a
          |_ 2.m4a
         |_ 1.txt
         |_ 2.txt
         |_ video.mp4

    A partir de la lista plana de rutas lógicas.
    """
    # Nodo raíz implícito (sin nombre): sus hijos son las primeras componentes.
    raiz: dict = {}
    for _uid, _tipo, _tam, ruta in filas:
        partes = [p for p in ruta.split("/") if p]
        if not partes:
            continue
        nodo = raiz
        for p in partes:
            nodo = nodo.setdefault(p, {})

    lineas: List[str] = []

    def _emitir(nodo: dict, nivel: int):
        # Carpetas primero, luego archivos; todo por nombre.
        claves = sorted(nodo.keys(), key=lambda k: (not nodo[k], k.lower()))
        for k in claves:
            prefijo = " " * nivel + "|_ "
            lineas.append(f"{prefijo}{k}")
            if nodo[k]:
                _emitir(nodo[k], nivel + 1)

    _emitir(raiz, 1)
    return "\n".join(lineas) if lineas else "(bóveda vacía)"


# ===========================================================================
# Escritura del archivo
# ===========================================================================
def _escribir(destino: Path, filas: List[Tuple[str, str, int, str]],
              cabecera_extra: str = "", formato: str = "arbol") -> Path:
    destino.parent.mkdir(parents=True, exist_ok=True)
    total_archivos = sum(1 for _u, t, _s, _r in filas if t == "file")
    total_carpetas = sum(1 for _u, t, _s, _r in filas if t == "dir")

    with open(destino, "w", encoding="utf-8") as f:
        f.write("=== Reporte de bóveda Mixcoara ===\n")
        f.write(f"Fecha y hora      : {_fmt_ts()}\n")
        f.write("Destino en la nube: WebDAV · carpeta configurada en la app\n")
        if cabecera_extra:
            for linea in cabecera_extra.rstrip("\n").splitlines():
                f.write(linea + "\n")
        f.write(f"Total             : {len(filas)} entradas "
                f"({total_archivos} archivos, {total_carpetas} carpetas)\n\n")

        if formato in ("arbol", "solo_rutas"):
            f.write("Estructura de la bóveda:\n")
            f.write(_generar_arbol(filas) + "\n\n")

        if formato in ("arbol", "tabla"):
            f.write("UUID del contenido (para recuperación manual):\n")
            f.write("UUID\tTIPO\tTAMAÑO\tRUTA_LÓGICA\n")
            for uid, tipo, tam, ruta in filas:
                f.write(f"{uid}\t{tipo}\t{tam}\t{ruta}\n")
            f.write("\n")

        if formato == "solo_rutas":
            f.write("Rutas lógicas (una por línea):\n")
            for _uid, _tipo, _tam, ruta in filas:
                f.write(f"{ruta}\n")

    return destino


# ===========================================================================
# API pública
# ===========================================================================
def volcar_reporte_completo(dav: ClienteWebDAV, llaves: Llaves,
                            destino: Optional[Path] = None,
                            formato: str = "arbol") -> Path:
    """
    Genera el .txt completo recorriendo la bóveda.

    formato:
      * "arbol"      → árbol visual + tabla UUID (por defecto).
      * "tabla"      → solo tabla UUID\tTIPO\tTAMAÑO\tRUTA.
      * "solo_rutas" → solo una ruta por línea (estilo script original).

    Devuelve la ruta del archivo escrito.
    """
    filas: list = []
    _recorrer(dav, llaves, llaves.root_uuid, "", filas)
    filas.sort(key=lambda x: (x[1] != "dir", x[3].lower()))

    if destino is None:
        destino = _nombre_reporte()

    cab = f"Raíz UUID         : {llaves.root_uuid}"
    return _escribir(destino, filas, cab, formato)


def anotar_en_reporte(ruta_local: Path, id_fisico: str, tipo: str = "file",
                      tam: int = -1) -> None:
    """
    Añade una línea al reporte incremental (llamado desde el finally de
    subir_carpeta / subir_archivo, cuando la operación tuvo éxito).

    El reporte incremental vive en Descargas/Mixcoara/reportes/ y se llama
    boveda_ultimo.txt para que se vaya reutilizando. Si no existe, se crea
    con la cabecera.

    No falla nunca: si no se puede escribir, se registra y se sigue.
    """
    try:
        reporte = _carpeta_reportes() / "boveda_ultimo.txt"
        if not reporte.exists():
            reporte.write_text(
                "=== Reporte incremental de subidas Mixcoara ===\n"
                f"Iniciado: {_fmt_ts()}\n\n"
                "UUID\tTIPO\tTAMAÑO\tRUTA_LÓGICA\n",
                encoding="utf-8",
            )
        tam_real = tam if tam >= 0 else (
            ruta_local.stat().st_size if ruta_local.exists() else -1
        )
        with open(reporte, "a", encoding="utf-8") as f:
            f.write(f"{id_fisico}\t{tipo}\t{tam_real}\t{ruta_local}\n")
    except Exception as ex:
        try:
            reg.aviso("reporte_anotar_fallo", ruta=str(ruta_local),
                      causa=f"{type(ex).__name__}: {ex}")
        except Exception:
            pass


# ===========================================================================
# Prueba manual desde consola
# ===========================================================================
if __name__ == "__main__":
    print("Este módulo se usa desde mixcoara_bridge.exportar_reporte_boveda().")
    print("Uso esperado:")
    print("  from mixcoara_nube import cargar_env, ClienteWebDAV, abrir_boveda_nube")
    print("  from mixcoara_reporte import volcar_reporte_completo")
    print("  ... abrir bóveda ...")
    print("  volcar_reporte_completo(dav, llaves)")