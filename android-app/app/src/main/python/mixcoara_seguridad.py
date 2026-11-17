#!/usr/bin/env python3
"""
mixcoara_seguridad.py - Cambio de frase y nueva clave de recuperación, de forma segura.

Importante:
- Cada cambio se sube primero a un archivo temporal (masterkey.mixcoara.new), se
  comprueba que abre con las credenciales nuevas y solo entonces reemplaza al
  archivo definitivo.
- No se deja ningún respaldo (.bak) con la envoltura antigua: un respaldo así
  seguiría abriéndose con la frase o la clave de recuperación anteriores.
- Límite honesto: si tu servidor guarda versiones anteriores o papelera de
  masterkey.mixcoara, esas copias viejas seguirán abriéndose con la clave vieja.
  Cambiar la clave de recuperación NO cambia la llave maestra. Si una clave quedó
  expuesta, la medida completa es crear una bóveda nueva (llave maestra nueva).
"""

import json
from typing import Dict, Optional, Tuple

from Cryptodome.Random import get_random_bytes

import mixcoara_claves as mc
import mixcoara_nube as mn
from mixcoara_claves import ErrorBoveda


def regenerar_clave_recuperacion(arch: Dict, frase: str) -> Tuple[Dict, str]:
    """Devuelve (archivo de llave nuevo, texto de la nueva clave de recuperación)."""
    mc._validar(arch)
    kek = mc._kek_frase(frase, mc._unb64(arch["salt_frase"]), arch["kdf"])
    maestra = mc._desenvolver(kek, arch["llave_por_frase"], mc._cabecera_bytes(arch))
    clave, texto = mc.generar_clave_recuperacion()
    nuevo = dict(arch)
    nuevo["salt_recuperacion"] = mc._b64(get_random_bytes(16))
    kek_r = mc._kek_recuperacion(clave, mc._unb64(nuevo["salt_recuperacion"]))
    nuevo["llave_por_recuperacion"] = mc._envolver(kek_r, maestra, mc._cabecera_bytes(nuevo))
    return nuevo, texto


def _subir_verificado(dav, nuevo: Dict, comprobar) -> None:
    nombre = mc.NOMBRE_ARCHIVO_LLAVE
    temporal = nombre + ".new"
    datos = json.dumps(nuevo, indent=2).encode("utf-8")
    if not dav.put_bytes(temporal, datos):
        raise ErrorBoveda("No se pudo subir el archivo temporal de llaves. No se cambió nada.")
    if dav.get_bytes(temporal) != datos:
        raise ErrorBoveda("El archivo temporal no coincide tras subirlo. No se cambió nada.")
    comprobar(json.loads(datos.decode("utf-8")))
    if not dav.put_bytes(nombre, datos):
        raise ErrorBoveda(f"No se pudo reemplazar {nombre}. La copia buena quedó en {temporal}.")
    if dav.get_bytes(nombre) != datos:
        raise ErrorBoveda(f"{nombre} no coincide tras reemplazarlo. La copia buena está en {temporal}.")
    dav.borrar(temporal)
    dav.borrar(nombre + ".bak")


def regenerar_clave_recuperacion_nube(dav, frase: str) -> str:
    arch = mn._leer_arch(dav)
    anteriores = mc.abrir_con_frase(arch, frase)
    nuevo, texto = regenerar_clave_recuperacion(arch, frase)

    def comprobar(a: Dict):
        ll = mc.abrir_con_recuperacion(a, texto)
        if ll.siv != anteriores.siv or ll.gcm != anteriores.gcm:
            raise ErrorBoveda("La verificación de la clave de recuperación nueva falló.")
        mc.abrir_con_frase(a, frase)

    _subir_verificado(dav, nuevo, comprobar)
    return texto


def cambiar_frase_segura(dav, frase_actual: Optional[str], frase_nueva: str,
                         recuperacion: Optional[str] = None) -> None:
    arch = mn._leer_arch(dav)
    if recuperacion:
        anteriores = mc.abrir_con_recuperacion(arch, recuperacion)
        nuevo = mc.restablecer_frase_con_recuperacion(arch, recuperacion, frase_nueva)
    else:
        anteriores = mc.abrir_con_frase(arch, frase_actual or "")
        nuevo = mc.cambiar_frase(arch, frase_actual or "", frase_nueva)

    def comprobar(a: Dict):
        ll = mc.abrir_con_frase(a, frase_nueva)
        if ll.siv != anteriores.siv or ll.gcm != anteriores.gcm:
            raise ErrorBoveda("La verificación de la frase nueva falló.")

    _subir_verificado(dav, nuevo, comprobar)