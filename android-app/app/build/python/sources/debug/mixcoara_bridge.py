"""API estable y serializable entre Jetpack Compose y el núcleo de Mixcoara."""

from __future__ import annotations

import json
import os
import shutil
import tempfile
import threading
import time
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional

import mixcoara_claves as claves
import mixcoara_menu as menu
import mixcoara_nube as nube
import mixcoara_registro as registro
import mixcoara_seguridad as seguridad

_sesiones: Dict[str, Dict[str, Any]] = {}
_lock = threading.RLock()
_operaciones: Dict[str, "ProgresoOperacion"] = {}
_op_lock = threading.RLock()


class OperacionCancelada(Exception):
    """Señala que el usuario canceló la tarea en curso."""
    pass


class ProgresoOperacion(nube.Progreso):
    def __init__(self, op_id: str):
        self.cancelado = False
        self.terminado = False
        self.error: Optional[str] = None
        self.resultado: Any = None
        super().__init__()
        self.op_id = op_id

    def refrescar(self, forzar: bool = False):
        if self.cancelado:
            raise OperacionCancelada("Operación cancelada por el usuario.")
        super().refrescar(forzar)

    def serializar(self) -> Dict[str, Any]:
        return {
            "op_id": self.op_id,
            "fase": self.fase or "",
            "estado": self.estado or "",
            "actual": self.actual or "",
            "fraccion": float(self.fraccion()),
            "archivos_hechos": int(self.archivos_hechos),
            "archivos_total": int(self.archivos_total),
            "carpetas_hechas": int(self.carpetas_hechas),
            "carpetas_total": int(self.carpetas_total),
            "bytes_hechos": int(self.bytes_hechos),
            "bytes_total": int(self.bytes_total),
            "velocidad": float(self.velocidad()),
            "velocidad_media": float(self.velocidad_media()),
            "transcurrido": float(self.transcurrido()),
            "eta": float(self.eta()) if self.eta() is not None else None,
            "cancelado": bool(self.cancelado),
            "terminado": bool(self.terminado),
            "error": self.error,
            "resultado": self.resultado,
        }


def _respuesta(ok: bool, code: str, message: str = "", data: Any = None) -> str:
    return json.dumps(
        {"ok": ok, "code": code, "message": message, "data": data},
        ensure_ascii=False,
    )


def _configurar_rutas(private_dir: str, download_dir: str) -> None:
    os.environ["MIXCOARA_PRIVATE_DIR"] = private_dir
    os.environ["MIXCOARA_DOWNLOAD_DIR"] = download_dir
    Path(private_dir).mkdir(parents=True, exist_ok=True)
    (Path(download_dir) / "Mixcoara").mkdir(parents=True, exist_ok=True)


def _cliente(config_json: str) -> nube.ClienteWebDAV:
    config = json.loads(config_json)
    return menu.ClienteWebDAVRobusto(
        str(config["url"]).strip(),
        str(config["usuario"]).strip(),
        str(config["password"]),
        str(config.get("carpeta") or "Mixcoara").strip(),
    )


def _sesion(session_id: str) -> Dict[str, Any]:
    with _lock:
        sesion = _sesiones.get(session_id)
    if sesion is None:
        raise claves.ErrorBoveda("La sesión expiró. Desbloquea nuevamente la bóveda.")
    return sesion


def _validar_acceso(dav: nube.ClienteWebDAV) -> None:
    """Distingue una carpeta aún inexistente de autenticación rechazada."""
    respuesta = dav._abrir(
        "PROPFIND",
        "",
        data=b"",
        headers={"Depth": "0"},
        timeout=30,
    )
    try:
        codigo = respuesta.status_code
        if codigo in (401, 403):
            raise nube.ErrorRed(
                f"El servidor rechazó el usuario o la contraseña (HTTP {codigo})."
            )
        if codigo not in (200, 207, 404):
            raise nube.ErrorRed(
                f"El servidor WebDAV respondió HTTP {codigo} "
                f"{getattr(respuesta, 'reason', '') or ''}".strip()
            )
    finally:
        respuesta.close()


def self_test(private_dir: str, download_dir: str) -> str:
    """Prueba AES-GCM, AES-SIV, scrypt/HKDF y un archivo con formato local."""
    try:
        _configurar_rutas(private_dir, download_dir)
        arch, llaves, recuperacion = claves.crear_boveda(
            "frase de compatibilidad mixcoara"
        )
        abiertas = claves.abrir_con_frase(arch, "frase de compatibilidad mixcoara")
        if abiertas.gcm != llaves.gcm or abiertas.siv != llaves.siv:
            raise RuntimeError("Las subclaves derivadas no coinciden.")
        if claves.abrir_con_recuperacion(arch, recuperacion).gcm != llaves.gcm:
            raise RuntimeError("La recuperación no produce la misma llave.")

        nombre = "Prueba áéíóú.txt"
        cifrado = nube.cifrar_nombre(nombre, llaves.siv, llaves.root_uuid)
        if nube.descifrar_nombre(cifrado, llaves.siv, llaves.root_uuid) != nombre:
            raise RuntimeError("AES-SIV no recuperó el nombre.")

        with tempfile.TemporaryDirectory(dir=private_dir) as temporal:
            base = Path(temporal)
            origen = base / nombre
            cifrado_local = base / "prueba.mixcoara"
            restaurado = base / "restaurado.txt"
            contenido = (b"Mixcoara Android compatibility test.\n" * 4096)
            origen.write_bytes(contenido)
            nube.cifrar_archivo_local(origen, llaves.gcm, cifrado_local)
            nube.descifrar_archivo_local(cifrado_local, llaves.gcm, restaurado)
            if restaurado.read_bytes() != contenido:
                raise RuntimeError("El contenido restaurado no coincide.")

        return _respuesta(
            True,
            "crypto_self_test_ok",
            data={
                "aes_gcm": True,
                "aes_siv": True,
                "scrypt_hkdf": True,
                "local_format": True,
            },
        )
    except Exception as ex:
        return _respuesta(False, "crypto_self_test_failed", f"{type(ex).__name__}: {ex}")


def comprobar_boveda(
    config_json: str, private_dir: str, download_dir: str
) -> str:
    try:
        _configurar_rutas(private_dir, download_dir)
        dav = _cliente(config_json)
        _validar_acceso(dav)
        existe = nube.boveda_existe(dav)
        return _respuesta(
            True,
            "vault_found" if existe else "vault_missing",
            data={"exists": existe},
        )
    except Exception as ex:
        return _respuesta(False, "connection_failed", str(ex))


def abrir_boveda(
    config_json: str,
    frase: str,
    private_dir: str,
    download_dir: str,
) -> str:
    try:
        _configurar_rutas(private_dir, download_dir)
        dav = _cliente(config_json)
        _validar_acceso(dav)
        llaves = nube.abrir_boveda_nube(dav, frase=frase)
        session_id = str(uuid.uuid4())
        with _lock:
            _sesiones.clear()
            _sesiones[session_id] = {"dav": dav, "llaves": llaves}
        return _respuesta(True, "vault_opened", data={"session_id": session_id})
    except Exception as ex:
        return _respuesta(False, "unlock_failed", str(ex))


def crear_boveda(
    config_json: str,
    frase: str,
    private_dir: str,
    download_dir: str,
) -> str:
    try:
        _configurar_rutas(private_dir, download_dir)
        dav = _cliente(config_json)
        _validar_acceso(dav)
        llaves, recuperacion = nube.crear_boveda_nube(dav, frase)
        session_id = str(uuid.uuid4())
        with _lock:
            _sesiones.clear()
            _sesiones[session_id] = {"dav": dav, "llaves": llaves}
        return _respuesta(
            True,
            "vault_created",
            data={
                "session_id": session_id,
                "recovery_key": recuperacion,
            },
        )
    except Exception as ex:
        return _respuesta(False, "create_failed", str(ex))


def listar(session_id: str, directory_id: str = "") -> str:
    try:
        sesion = _sesion(session_id)
        llaves = sesion["llaves"]
        directory_id = directory_id or llaves.root_uuid
        entradas, errores = menu.listar_carpeta(
            sesion["dav"], llaves, directory_id
        )
        data = {
            "directory_id": directory_id,
            "entries": [
                {
                    "name": entrada.nombre,
                    "is_directory": entrada.es_dir,
                    "id": entrada.uuid,
                    "size": entrada.tamano,
                    "parent_id": entrada.padre,
                }
                for entrada in entradas
            ],
            "errors": [{"item": item, "message": error} for item, error in errores],
        }
        return _respuesta(True, "directory_listed", data=data)
    except Exception as ex:
        return _respuesta(False, "list_failed", str(ex))


def cerrar_sesion(session_id: str) -> str:
    with _lock:
        _sesiones.pop(session_id, None)
    return _respuesta(True, "session_closed")


def _ejecutar_en_fondo(op_id: str, tarea):
    prog = ProgresoOperacion(op_id)
    op_tmp_dir = nube.directorio_temporal() / f"op_{op_id}"
    op_tmp_dir.mkdir(parents=True, exist_ok=True)
    with _op_lock:
        _operaciones[op_id] = prog

    def ejecutor():
        try:
            res = tarea(prog, op_tmp_dir)
            prog.resultado = res
        except OperacionCancelada:
            prog.cancelado = True
            prog.error = "Operación cancelada."
        except Exception as ex:
            prog.error = f"{type(ex).__name__}: {ex}"
            registro.error("operacion_fondo_fallo", op_id=op_id, exc=ex)
        finally:
            cancelado_previo = prog.cancelado
            try:
                prog.cancelado = False
                prog.fase = "Limpieza de temporales"
                prog.estado = "limpiando"
                prog.actual = op_tmp_dir.name
                prog.refrescar(True)
                shutil.rmtree(op_tmp_dir, ignore_errors=True)
            finally:
                prog.cancelado = cancelado_previo
            prog.terminado = True
            cancelado_final = prog.cancelado
            prog.cancelado = False
            try:
                prog.refrescar(True)
            finally:
                prog.cancelado = cancelado_final

    hilo = threading.Thread(target=ejecutor, name=f"MixcoaraOp-{op_id[:8]}", daemon=True)
    hilo.start()
    return _respuesta(True, "operation_started", data={"op_id": op_id})


def obtener_progreso(op_id: str) -> str:
    with _op_lock:
        prog = _operaciones.get(op_id)
    if prog is None:
        return _respuesta(False, "not_found", "No existe la operación solicitada.")
    return _respuesta(True, "progress_update", data=prog.serializar())


def cancelar_operacion(op_id: str) -> str:
    with _op_lock:
        prog = _operaciones.get(op_id)
    if prog is not None:
        prog.cancelado = True
    return _respuesta(True, "cancellation_requested")


def subir_archivo(session_id: str, local_path: str, verify: bool, target_dir_id: str, op_id: str) -> str:
    sesion = _sesion(session_id)
    dav = sesion["dav"]
    llaves = sesion["llaves"]
    ruta = Path(local_path)
    uuid_dir = target_dir_id if target_dir_id else llaves.root_uuid

    def tarea(prog, op_tmp_dir: Path):
        return nube.subir_archivo(dav, llaves, ruta, verificar=verify, prog=prog, uuid_dir=uuid_dir, tmp_dir=op_tmp_dir)

    return _ejecutar_en_fondo(op_id, tarea)


def subir_carpeta(session_id: str, local_path: str, verify: bool, target_dir_id: str, op_id: str) -> str:
    sesion = _sesion(session_id)
    dav = sesion["dav"]
    llaves = sesion["llaves"]
    carpeta = Path(local_path)
    uuid_dir = target_dir_id if target_dir_id else llaves.root_uuid

    def tarea(prog, op_tmp_dir: Path):
        return nube.subir_carpeta(dav, llaves, carpeta, verificar=verify, prog=prog, uuid_dir=uuid_dir, tmp_dir=op_tmp_dir)

    return _ejecutar_en_fondo(op_id, tarea)


def descargar_seleccion(session_id: str, entries_json: str, output_dir: str, op_id: str) -> str:
    sesion = _sesion(session_id)
    dav = sesion["dav"]
    llaves = sesion["llaves"]
    datos = json.loads(entries_json)
    entradas = [
        menu.Entrada(
            nombre=d["name"],
            es_dir=bool(d["is_directory"]),
            uuid=d["id"],
            tamano=int(d.get("size") or 0),
            padre=d.get("parent_id") or llaves.root_uuid,
        )
        for d in datos
    ]
    destino = Path(output_dir) if output_dir else (nube.directorio_mixcoara() / "Descifrados")

    def tarea(prog, op_tmp_dir: Path):
        return menu.descargar_entradas(dav, llaves, entradas, destino, prog=prog, tmp_dir=op_tmp_dir)

    return _ejecutar_en_fondo(op_id, tarea)


def descargar_todo(session_id: str, output_dir: str, op_id: str) -> str:
    sesion = _sesion(session_id)
    dav = sesion["dav"]
    llaves = sesion["llaves"]
    destino = Path(output_dir) if output_dir else (nube.directorio_mixcoara() / "Descifrados")

    def tarea(prog, op_tmp_dir: Path):
        return menu.descargar_todo(dav, llaves, destino, prog=prog, tmp_dir=op_tmp_dir)

    return _ejecutar_en_fondo(op_id, tarea)


def cifrar_local(session_id: str, source_path: str, target_path: str, op_id: str) -> str:
    sesion = _sesion(session_id)
    llaves = sesion["llaves"]
    origen = Path(source_path)
    if not target_path:
        destino_dir = nube.directorio_mixcoara() / "BovedaLocal"
        destino = destino_dir / f"{origen.name}.mixcoara"
    else:
        destino = Path(target_path)

    def tarea(prog, _op_tmp_dir: Path):
        prog.iniciar("Cifrando archivo local", 1, 0, origen.stat().st_size)
        prog.poner("cifrando", origen.name)
        resultado = nube.cifrar_archivo_local(origen, llaves.gcm, destino)
        prog.archivo_listo()
        return {"destino": str(resultado), "bytes": destino.stat().st_size}

    return _ejecutar_en_fondo(op_id, tarea)


def descifrar_local(session_id: str, source_path: str, target_path: str, op_id: str) -> str:
    sesion = _sesion(session_id)
    llaves = sesion["llaves"]
    origen = Path(source_path)
    if not target_path:
        destino_dir = nube.directorio_mixcoara() / "Descifrados"
        nombre = origen.name
        if nombre.endswith(".mixcoara"):
            nombre = nombre[:-9]
        else:
            nombre = f"{nombre}.descifrado"
        destino = destino_dir / nombre
    else:
        destino = Path(target_path)

    def tarea(prog, _op_tmp_dir: Path):
        prog.iniciar("Descifrando archivo local", 1, 0, origen.stat().st_size)
        prog.poner("descifrando", origen.name)
        resultado = nube.descifrar_archivo_local(origen, llaves.gcm, destino)
        prog.archivo_listo()
        return {"destino": str(resultado), "bytes": destino.stat().st_size}

    return _ejecutar_en_fondo(op_id, tarea)


def cambiar_frase(session_id: str, frase_actual: str, frase_nueva: str) -> str:
    try:
        sesion = _sesion(session_id)
        dav = sesion["dav"]
        seguridad.cambiar_frase_segura(dav, frase_actual, frase_nueva)
        return _respuesta(True, "passphrase_changed", "Frase cambiada exitosamente.")
    except Exception as ex:
        return _respuesta(False, "change_passphrase_failed", f"{type(ex).__name__}: {ex}")


def regenerar_clave_recuperacion(session_id: str, frase_actual: str) -> str:
    try:
        sesion = _sesion(session_id)
        dav = sesion["dav"]
        nueva_clave = seguridad.regenerar_clave_recuperacion_nube(dav, frase_actual)
        return _respuesta(True, "recovery_key_regenerated", data={"recovery_key": nueva_clave})
    except Exception as ex:
        return _respuesta(False, "regenerate_key_failed", f"{type(ex).__name__}: {ex}")


def leer_registro(max_lineas: int = 250) -> str:
    try:
        texto = registro.leer(max_lineas)
        return _respuesta(True, "logs_read", data={"log_text": texto})
    except Exception as ex:
        return _respuesta(False, "read_logs_failed", str(ex))


def limpiar_registro() -> str:
    try:
        registro.limpiar()
        return _respuesta(True, "logs_cleared")
    except Exception as ex:
        return _respuesta(False, "clear_logs_failed", str(ex))


def exportar_registro() -> str:
    try:
        salida = registro.exportar("2.0.0")
        return _respuesta(True, "logs_exported", data={"export_path": str(salida)})
    except Exception as ex:
        return _respuesta(False, "export_logs_failed", str(ex))
