#!/usr/bin/env python3
"""
mixcoara_claves.py - Módulo de claves para la bóveda compartida.

Modelo:
  frase de contraseña --scrypt--> KEK_frase  --descifra--> llave maestra (64 B)
  clave de recuperación --HKDF--> KEK_recup  --descifra--> llave maestra (64 B)
  llave maestra --HKDF--> clave SIV (64 B) para nombres
  llave maestra --HKDF--> clave GCM (32 B) para contenido

Archivo en la nube: masterkey.vaultdav (JSON, no contiene secretos en claro).
"""

import json
import base64
import hashlib
import hmac
import unicodedata
import uuid
from dataclasses import dataclass
from typing import Dict, Tuple

from Cryptodome.Cipher import AES
from Cryptodome.Hash import SHA256
from Cryptodome.Protocol.KDF import scrypt, HKDF
from Cryptodome.Random import get_random_bytes

VERSION = 1
SCRYPT_N = 2 ** 15   # ~32 MiB de RAM con r=8
SCRYPT_R = 8
SCRYPT_P = 1
NOMBRE_ARCHIVO_LLAVE = "masterkey.vaultdav"


class ErrorBoveda(Exception):
    pass


@dataclass
class Llaves:
    siv: bytes        # 64 bytes (AES-256-SIV)
    gcm: bytes        # 32 bytes (AES-256-GCM)
    root_uuid: str
    vault_id: str


# ---------------------------------------------------------------------------
# Utilidades
# ---------------------------------------------------------------------------
def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def _unb64(txt: str) -> bytes:
    return base64.b64decode(txt.encode("ascii"))


def _cabecera_bytes(arch: Dict) -> bytes:
    """Datos inmutables que quedan autenticados como AAD. Sal y parámetros KDF
    quedan protegidos de forma implícita: si se alteran, la KEK cambia y falla."""
    cab = {k: arch[k] for k in ("version", "vault_id", "root_uuid")}
    return json.dumps(cab, sort_keys=True, separators=(",", ":")).encode("utf-8")


def _envolver(kek: bytes, secreto: bytes, aad: bytes) -> str:
    nonce = get_random_bytes(12)
    c = AES.new(kek, AES.MODE_GCM, nonce=nonce)
    c.update(aad)
    ct, tag = c.encrypt_and_digest(secreto)
    return _b64(nonce + ct + tag)


def _desenvolver(kek: bytes, blob_b64: str, aad: bytes) -> bytes:
    blob = _unb64(blob_b64)
    nonce, ct, tag = blob[:12], blob[12:-16], blob[-16:]
    c = AES.new(kek, AES.MODE_GCM, nonce=nonce)
    c.update(aad)
    try:
        return c.decrypt_and_verify(ct, tag)
    except ValueError:
        raise ErrorBoveda("Frase o clave de recuperación incorrecta, "
                          "o el archivo de llave fue alterado.")


def _kek_frase(frase: str, salt: bytes, kdf: Dict) -> bytes:
    pw = unicodedata.normalize("NFKC", frase).encode("utf-8")
    return scrypt(pw, salt, 32, N=kdf["N"], r=kdf["r"], p=kdf["p"])


def _kek_recuperacion(clave32: bytes, salt: bytes) -> bytes:
    return HKDF(clave32, 32, salt, SHA256, context=b"vaultdav recovery kek v1")


def _subclaves(maestra: bytes) -> Tuple[bytes, bytes]:
    siv = HKDF(maestra, 64, b"", SHA256, context=b"vaultdav siv v1")
    gcm = HKDF(maestra, 32, b"", SHA256, context=b"vaultdav gcm v1")
    return siv, gcm


# ---------------------------------------------------------------------------
# Clave de recuperación (32 bytes + 3 de control, en Base32 con guiones)
# ---------------------------------------------------------------------------
def generar_clave_recuperacion() -> Tuple[bytes, str]:
    clave = get_random_bytes(32)
    control = hashlib.sha256(b"vaultdav-rk" + clave).digest()[:3]
    b32 = base64.b32encode(clave + control).decode("ascii")   # 56 caracteres
    texto = "-".join(b32[i:i + 4] for i in range(0, len(b32), 4))
    return clave, texto


def leer_clave_recuperacion(texto: str) -> bytes:
    limpio = "".join(ch for ch in texto.upper() if ch.isalnum())
    try:
        crudo = base64.b32decode(limpio)
    except Exception:
        raise ErrorBoveda("La clave de recuperación tiene caracteres inválidos.")
    if len(crudo) != 35:
        raise ErrorBoveda("La clave de recuperación tiene longitud incorrecta.")
    clave, control = crudo[:32], crudo[32:]
    esperado = hashlib.sha256(b"vaultdav-rk" + clave).digest()[:3]
    if not hmac.compare_digest(control, esperado):
        raise ErrorBoveda("La clave de recuperación tiene un error de captura.")
    return clave


# ---------------------------------------------------------------------------
# API principal
# ---------------------------------------------------------------------------
def crear_boveda(frase: str) -> Tuple[Dict, Llaves, str]:
    """Devuelve (contenido de masterkey.vaultdav, llaves, clave de recuperación)."""
    if len(frase) < 12:
        raise ErrorBoveda("La frase debe tener al menos 12 caracteres.")
    maestra = get_random_bytes(64)
    clave_rec, texto_rec = generar_clave_recuperacion()

    arch = {
        "version": VERSION,
        "vault_id": str(uuid.uuid4()),
        "root_uuid": str(uuid.uuid4()),
        "kdf": {"alg": "scrypt", "N": SCRYPT_N, "r": SCRYPT_R, "p": SCRYPT_P},
        "salt_frase": _b64(get_random_bytes(16)),
        "salt_recuperacion": _b64(get_random_bytes(16)),
    }
    aad = _cabecera_bytes(arch)
    kek_f = _kek_frase(frase, _unb64(arch["salt_frase"]), arch["kdf"])
    kek_r = _kek_recuperacion(clave_rec, _unb64(arch["salt_recuperacion"]))
    arch["llave_por_frase"] = _envolver(kek_f, maestra, aad)
    arch["llave_por_recuperacion"] = _envolver(kek_r, maestra, aad)

    siv, gcm = _subclaves(maestra)
    return arch, Llaves(siv, gcm, arch["root_uuid"], arch["vault_id"]), texto_rec


def _validar(arch: Dict):
    if arch.get("version") != VERSION:
        raise ErrorBoveda(f"Versión de bóveda no soportada: {arch.get('version')}")
    kdf = arch["kdf"]
    if kdf.get("alg") != "scrypt" or kdf["N"] > 2 ** 20 or kdf["N"] < 2 ** 14:
        raise ErrorBoveda("Parámetros KDF fuera de rango.")


def _llaves_desde_maestra(arch: Dict, maestra: bytes) -> Llaves:
    siv, gcm = _subclaves(maestra)
    return Llaves(siv, gcm, arch["root_uuid"], arch["vault_id"])


def abrir_con_frase(arch: Dict, frase: str) -> Llaves:
    _validar(arch)
    kek = _kek_frase(frase, _unb64(arch["salt_frase"]), arch["kdf"])
    maestra = _desenvolver(kek, arch["llave_por_frase"], _cabecera_bytes(arch))
    return _llaves_desde_maestra(arch, maestra)


def abrir_con_recuperacion(arch: Dict, texto_rec: str) -> Llaves:
    _validar(arch)
    clave = leer_clave_recuperacion(texto_rec)
    kek = _kek_recuperacion(clave, _unb64(arch["salt_recuperacion"]))
    maestra = _desenvolver(kek, arch["llave_por_recuperacion"], _cabecera_bytes(arch))
    return _llaves_desde_maestra(arch, maestra)


def cambiar_frase(arch: Dict, frase_actual: str, frase_nueva: str) -> Dict:
    """Reenvuelve la misma llave maestra: no hay que recifrar nada en la nube."""
    if len(frase_nueva) < 12:
        raise ErrorBoveda("La frase nueva debe tener al menos 12 caracteres.")
    _validar(arch)
    kek = _kek_frase(frase_actual, _unb64(arch["salt_frase"]), arch["kdf"])
    maestra = _desenvolver(kek, arch["llave_por_frase"], _cabecera_bytes(arch))
    return _reenvolver_frase(arch, maestra, frase_nueva)


def restablecer_frase_con_recuperacion(arch: Dict, texto_rec: str, frase_nueva: str) -> Dict:
    """Si olvidaste la frase: la clave de recuperación permite fijar una nueva."""
    if len(frase_nueva) < 12:
        raise ErrorBoveda("La frase nueva debe tener al menos 12 caracteres.")
    _validar(arch)
    clave = leer_clave_recuperacion(texto_rec)
    kek = _kek_recuperacion(clave, _unb64(arch["salt_recuperacion"]))
    maestra = _desenvolver(kek, arch["llave_por_recuperacion"], _cabecera_bytes(arch))
    return _reenvolver_frase(arch, maestra, frase_nueva)


def _reenvolver_frase(arch: Dict, maestra: bytes, frase_nueva: str) -> Dict:
    nuevo = dict(arch)
    nuevo["salt_frase"] = _b64(get_random_bytes(16))
    aad = _cabecera_bytes(nuevo)
    kek = _kek_frase(frase_nueva, _unb64(nuevo["salt_frase"]), nuevo["kdf"])
    nuevo["llave_por_frase"] = _envolver(kek, maestra, aad)
    return nuevo
