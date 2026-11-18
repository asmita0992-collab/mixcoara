"""Bóveda local independiente MIXCOARA-DIRECTORY-1. No usa WebDAV."""
from __future__ import annotations
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import threading
import time
import uuid

try:
    from Cryptodome.Cipher import AES
    from Cryptodome.Protocol.KDF import scrypt
    CRYPTO_BACKEND = "pycryptodomex"
except ModuleNotFoundError:
    # Backend opcional de escritorio para pruebas. Android ya incluye pycryptodomex.
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    CRYPTO_BACKEND = "cryptography"
    def scrypt(password, salt, key_len, N, r, p):
        return hashlib.scrypt(password, salt=salt, n=N, r=r, p=p, dklen=key_len, maxmem=128*1024*1024)
    class _GCM:
        def __init__(self, key, nonce): self.key=key; self.nonce=nonce; self.aad=b""; self.ctx=None
        def update(self, aad): self.aad=aad
        def _context(self, encrypt):
            if self.ctx is None:
                base=Cipher(algorithms.AES(self.key),modes.GCM(self.nonce))
                self.ctx=base.encryptor() if encrypt else base.decryptor()
                self.ctx.authenticate_additional_data(self.aad)
            return self.ctx
        def encrypt(self, data): return self._context(True).update(data)
        def decrypt(self, data): return self._context(False).update(data)
        def digest(self): self._context(True).finalize(); return self.ctx.tag
        def verify(self, tag):
            try: self._context(False).finalize_with_tag(tag)
            except Exception as exc: raise ValueError("La autenticación GCM no coincide.") from exc
        def encrypt_and_digest(self, data): return self.encrypt(data), self.digest()
        def decrypt_and_verify(self, data, tag):
            plain=self.decrypt(data); self.verify(tag); return plain
    class AES:
        MODE_GCM=1
        @staticmethod
        def new(key, mode, nonce): return _GCM(key,nonce)


FORMAT = "MIXCOARA-DIRECTORY-1"
# El archivo previo se lee SOLO para migrar bóvedas ya existentes.
LEGACY_HEADER = "mixcoara.local.json"
META_DIR = ".mixcoara"
HEADER = "metadata.enc"
META_MAGIC = b"MIXCOARA-META-2\x00"
INDEX = "index.gcm"
CHUNK = 256 * 1024
_sessions = {}
_ops = {}
_guard = threading.RLock()

class VaultError(Exception): pass
class Cancelled(VaultError): pass

def _id(): return uuid.uuid4().hex

def _b64(b): return base64.b64encode(b).decode("ascii")

def _un64(s): return base64.b64decode(s, validate=True)

def _aad(vid, purpose): return (FORMAT + ":" + vid + ":" + purpose).encode()

def _safe(name):
    if not isinstance(name, str) or not name or name in (".", "..") or any(c in name for c in ("/", "\\", "\0")):
        raise VaultError("Nombre de archivo o carpeta no válido.")
    if len(name.encode("utf-8")) > 240:
        raise VaultError("Nombre demasiado largo para exportarlo de forma segura.")
    return name

def _uid(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{32}", value):
        raise VaultError("Identificador de bóveda no válido.")
    return value

def _seal(key, plain, aad):
    nonce = os.urandom(12)
    c = AES.new(key, AES.MODE_GCM, nonce=nonce)
    c.update(aad)
    data, tag = c.encrypt_and_digest(plain)
    return nonce + data + tag

def _open(key, blob, aad):
    if len(blob) < 28: raise VaultError("Archivo cifrado incompleto.")
    c = AES.new(key, AES.MODE_GCM, nonce=blob[:12])
    c.update(aad)
    return c.decrypt_and_verify(blob[12:-16], blob[-16:])

def _atomic(path, data):
    tmp = path.with_name(path.name + "." + _id() + ".part")
    try:
        with open(tmp, "xb") as f:
            f.write(data); f.flush(); os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)

def _json(data): return json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode()

def _derive(password, salt):
    return scrypt(password.encode("utf-8"), salt, 32, N=2**15, r=8, p=1)

def _wrapper(password, key, vid):
    salt = os.urandom(16)
    return {"salt": _b64(salt), "wrapped": _b64(_seal(_derive(password, salt), key, _aad(vid, "master")))}

def _unwrap(password, wrap, vid):
    salt = _un64(wrap["salt"])
    if len(salt) != 16: raise VaultError("Encabezado no válido.")
    key = _open(_derive(password, salt), _un64(wrap["wrapped"]), _aad(vid, "master"))
    if len(key) != 32: raise VaultError("Llave maestra no válida.")
    return key

def _meta_path(path):
    return path / META_DIR / HEADER


def _header(path):
    """Lee v2 cifrado o el encabezado histórico, sin migrar hasta autenticar."""
    meta_dir = path / META_DIR
    meta = _meta_path(path)
    old = path / LEGACY_HEADER
    if (path.is_symlink() or meta_dir.is_symlink() or meta.is_symlink() or
            old.is_symlink() or (path / INDEX).is_symlink() or (path / "d").is_symlink()):
        raise VaultError("No se admiten enlaces en la estructura de la bóveda.")
    if meta.exists():
        binary = meta.read_bytes()
        if not binary.startswith(META_MAGIC):
            raise VaultError("Metadatos cifrados no válidos.")
        try:
            envelope = json.loads(binary[len(META_MAGIC):])
            if envelope["format"] != FORMAT or envelope["kdf"] != "scrypt-32768-8-1":
                raise ValueError("Formato incompatible")
            _uid(envelope["id"])
            if not all(k in envelope for k in ("password", "recovery", "private")):
                raise ValueError("Faltan componentes cifrados")
        except (KeyError, ValueError, TypeError) as exc:
            raise VaultError("Metadatos cifrados alterados.") from exc
        return envelope
    if not old.is_file():
        raise VaultError("No se encontraron metadatos de esta bóveda.")
    h = json.loads(old.read_text(encoding="utf-8"))
    if h.get("format") != FORMAT or h.get("kdf") != "scrypt-32768-8-1":
        raise VaultError("No es una bóveda local compatible de Mixcoara.")
    _uid(h["id"])
    return h


def _write_header(path, h, key):
    """Formato 2: sobres cifrados para las dos credenciales y payload privado AES-GCM.

    Los salt y sobres siguen siendo necesarios para obtener la llave sin guardarla.
    Los metadatos internos se cifran con la llave maestra y se autentican.
    """
    folder = path / META_DIR
    if folder.is_symlink() or _meta_path(path).is_symlink():
        raise VaultError("Ruta de metadatos insegura.")
    folder.mkdir(mode=0o700, exist_ok=True)
    envelope = {
        "format": FORMAT, "kdf": "scrypt-32768-8-1", "id": h["id"],
        "password": h["password"], "recovery": h["recovery"],
        "private": _b64(_seal(key, _json({"format": FORMAT, "id": h["id"]}),
                              _aad(h["id"], "metadata"))),
    }
    _atomic(_meta_path(path), META_MAGIC + _json(envelope))
    # Solo después de escribir de forma atómica la nueva copia.
    (path / LEGACY_HEADER).unlink(missing_ok=True)


def _verify_private(h, key):
    if "private" in h:
        try:
            data = json.loads(_open(key, _un64(h["private"]), _aad(h["id"], "metadata")))
            if data != {"format": FORMAT, "id": h["id"]}:
                raise ValueError("Encabezado no coincide")
        except (ValueError, KeyError, TypeError) as exc:
            raise VaultError("Metadatos cifrados alterados.") from exc


def _index(path, key, vid):
    nodes = json.loads(_open(key, (path / INDEX).read_bytes(), _aad(vid, "index")))
    if not isinstance(nodes, list): raise VaultError("Índice no válido.")
    ids = set()
    for n in nodes:
        _uid(n["id"]); _safe(n["name"])
        if n["id"] in ids: raise VaultError("Índice duplicado.")
        ids.add(n["id"])
        if type(n["dir"]) is not bool or type(n["size"]) is not int or n["size"] < 0:
            raise VaultError("Entrada no válida.")
    mapping = {n["id"]: n for n in nodes}
    siblings = set()
    for n in nodes:
        chain = {n["id"]}; parent = n["parent"]
        while parent:
            if parent in chain or parent not in mapping or not mapping[parent]["dir"]:
                raise VaultError("Jerarquía no válida.")
            chain.add(parent); parent = mapping[parent]["parent"]
        k = (n["parent"], n["name"])
        if k in siblings: raise VaultError("Nombres duplicados en el índice.")
        siblings.add(k)
    return nodes

def _save(s, nodes):
    _atomic(s["path"] / INDEX, _seal(s["key"], _json(nodes), _aad(s["vid"], "index")))

def _register(path, key, h):
    if any(s["path"] == path for s in _sessions.values()):
        raise VaultError("Esta bóveda ya está abierta. Bloquéala antes de abrir otra sesión.")
    nodes = _index(path, key, h["id"])
    sid = _id()
    _sessions[sid] = {"path": path, "key": key, "vid": h["id"], "nodes": nodes, "busy": False}
    return {"session": sid, "vault_id": h["id"], "path": str(path)}

def create(path, password):
    if len(password) < 12: raise VaultError("Usa una contraseña de al menos 12 caracteres.")
    p = Path(path).expanduser()
    _safe(p.name)
    p.mkdir(parents=False, exist_ok=False)
    try:
        (p / "d").mkdir()
        vid = _id(); key = os.urandom(32)
        recovery = base64.b32encode(os.urandom(32)).decode().rstrip("=")
        h = {"format": FORMAT, "id": vid, "kdf": "scrypt-32768-8-1",
             "password": _wrapper(password, key, vid), "recovery": _wrapper(recovery, key, vid)}
        _atomic(p / INDEX, _seal(key, b"[]", _aad(vid, "index")))
        _write_header(p, h, key)
        data = _register(p.resolve(), key, h); data["recovery"] = recovery
        return data
    except BaseException:
        shutil.rmtree(p, ignore_errors=True)
        raise

def open_vault(path, password):
    p = Path(path).expanduser()
    h = _header(p)
    try: key = _unwrap(password, h["password"], h["id"])
    except (ValueError, KeyError): raise VaultError("Contraseña incorrecta o encabezado alterado.")
    _verify_private(h, key)
    _index(p, key, h["id"])
    if "private" not in h:
        # Migra tras validar contraseña e índice, antes de crear la sesión.
        _write_header(p, h, key)
    return _register(p.resolve(), key, h)

def recover(path, recovery, new_password):
    if len(new_password) < 12: raise VaultError("La contraseña nueva debe tener al menos 12 caracteres.")
    p = Path(path).expanduser(); h = _header(p)
    if any(s["path"] == p.resolve() for s in _sessions.values()):
        raise VaultError("Bloquea esta bóveda antes de usar la recuperación.")
    normalized = re.sub(r"[\s-]", "", recovery).upper()
    try: key = _unwrap(normalized, h["recovery"], h["id"])
    except (ValueError, KeyError): raise VaultError("Clave de recuperación incorrecta.")
    _verify_private(h, key)
    _index(p, key, h["id"])
    h["password"] = _wrapper(new_password, key, h["id"])
    _write_header(p, h, key)
    return _register(p.resolve(), key, h)

def _session(sid):
    s = _sessions.get(sid)
    if not s: raise VaultError("Bóveda bloqueada. Ábrela nuevamente.")
    return s

def list_entries(sid, parent=""):
    s = _session(sid)
    return {"entries": sorted([n for n in s["nodes"] if n["parent"] == parent], key=lambda n: (not n["dir"], n["name"].casefold()))}

def close(sid):
    s = _session(sid)
    if s["busy"]: raise VaultError("Espera a que termine la operación antes de bloquear.")
    s["key"] = b""; s["nodes"].clear(); _sessions.pop(sid, None)
    return {}

def _check(op):
    if op["cancel"]: raise Cancelled("Operación cancelada; los originales no se modificaron.")

def _phase(op, phase, total=0):
    op.update(phase=phase, done=0, total=total, start=time.monotonic(), current="")

def _crypt_file(s, src, dst, ident, op, decrypt=False):
    tmp = dst.with_name(dst.name + "." + _id() + ".part")
    try:
        with open(src, "rb") as fi, open(tmp, "xb") as fo:
            if decrypt:
                total = os.fstat(fi.fileno()).st_size
                if total < 28: raise VaultError("Contenido cifrado incompleto.")
                nonce = fi.read(12); fi.seek(-16, 2); tag = fi.read(16); fi.seek(12)
                remaining = total - 28
            else:
                nonce = os.urandom(12); fo.write(nonce); remaining = None
            c = AES.new(s["key"], AES.MODE_GCM, nonce=nonce)
            c.update(_aad(s["vid"], "file:" + ident))
            while remaining is None or remaining > 0:
                _check(op)
                chunk = fi.read(CHUNK if remaining is None else min(CHUNK, remaining))
                if not chunk:
                    if remaining: raise VaultError("Archivo incompleto.")
                    break
                fo.write(c.decrypt(chunk) if decrypt else c.encrypt(chunk))
                if remaining is not None: remaining -= len(chunk)
                op["done"] += len(chunk)
            _check(op)
            if decrypt: c.verify(tag)
            else: fo.write(c.digest())
            fo.flush(); os.fsync(fo.fileno())
        # Nunca reemplaza un destino existente.
        if dst.exists(): raise VaultError("El destino ya existe; no se sobrescribe.")
        os.replace(tmp, dst)
    finally:
        tmp.unlink(missing_ok=True)

def _import(s, source, parent, op):
    p = Path(source)
    if p.is_symlink(): raise VaultError("No se importan enlaces simbólicos.")
    source = p.resolve(strict=True)
    vault = s["path"]
    if source == vault or source in vault.parents or vault in source.parents:
        raise VaultError("El origen no puede contener la bóveda ni estar dentro de ella.")
    nodes = copy.deepcopy(s["nodes"])
    if parent and not any(n["id"] == parent and n["dir"] for n in nodes):
        raise VaultError("Carpeta de destino no válida.")
    _phase(op, "Escaneando carpeta")
    planned=[]
    def scan(path, parent_id):
        _check(op)
        if path.is_symlink(): raise VaultError("Se detectó un enlace; no se importó la carpeta.")
        name = _safe(path.name); ident = _id()
        if any(n["parent"] == parent_id and n["name"] == name for n in nodes):
            raise VaultError("Ya existe un elemento con ese nombre. No se sobrescribe.")
        if not path.is_dir() and not path.is_file(): raise VaultError("Tipo de archivo no admitido.")
        n={"id":ident,"parent":parent_id,"name":name,"dir":path.is_dir(),"size":0 if path.is_dir() else path.stat().st_size}
        nodes.append(n)
        if n["dir"]:
            for child in sorted(path.iterdir()): scan(child, ident)
        else: planned.append((path,n))
    scan(source,parent)
    total=sum(n["size"] for _,n in planned)
    if shutil.disk_usage(vault).free < total + len(planned)*28 + 2*1024*1024:
        raise VaultError("Espacio insuficiente para importar la carpeta.")
    _phase(op,"Cifrando e importando",total)
    created=[]
    try:
        for path,n in planned:
            _check(op); op["current"]=n["name"]
            blob=vault/"d"/(n["id"]+".gcm")
            _crypt_file(s,path,blob,n["id"],op)
            created.append(blob)
            if path.stat().st_size != n["size"]: raise VaultError("El origen cambió durante la importación.")
            op["files"]+=1
        _check(op); op["phase"]="Guardando índice cifrado"
        _save(s,nodes); s["nodes"]=nodes
        return {"message":"Importación terminada. Los originales se conservaron.","files":len(planned)}
    except BaseException:
        for blob in created: blob.unlink(missing_ok=True)
        raise

def _export(s, ident, output, op):
    nodes={n["id"]:n for n in s["nodes"]}
    root=nodes.get(ident)
    if not root: raise VaultError("Elemento no válido.")
    out=Path(output).expanduser().resolve(strict=True)
    if not out.is_dir() or out == s["path"] or s["path"] in out.parents:
        raise VaultError("El destino debe ser una carpeta fuera de la bóveda.")
    chosen=[]
    def collect(n, rel):
        chosen.append((n, rel/_safe(n["name"])))
        if n["dir"]:
            for child in s["nodes"]:
                if child["parent"] == n["id"]: collect(child, rel/n["name"])
    collect(root,Path())
    total=sum(n["size"] for n,_ in chosen if not n["dir"])
    if shutil.disk_usage(out).free < total+1024*1024: raise VaultError("Espacio insuficiente para exportar.")
    target=out/_safe(root["name"])
    if target.exists(): raise VaultError("El destino ya existe. Elige otra carpeta; no se sobrescribe.")
    work=out/(".mixcoara-export-"+_id()); work.mkdir()
    _phase(op,"Descifrando y verificando",total)
    try:
        for n,rel in chosen:
            _check(op); dest=work/rel
            if n["dir"]: dest.mkdir(parents=True,exist_ok=True)
            else:
                dest.parent.mkdir(parents=True,exist_ok=True);op["current"]=n["name"]
                blob=s["path"]/"d"/(n["id"]+".gcm")
                if blob.is_symlink(): raise VaultError("Enlace no permitido en la bóveda.")
                _crypt_file(s,blob,dest,n["id"],op,True)
                if dest.stat().st_size != n["size"]: raise VaultError("Tamaño descifrado no válido.")
                op["files"]+=1
        _check(op)
        if target.exists(): raise VaultError("El destino ya existe; no se sobrescribe.")
        os.replace(work/root["name"],target)
        return {"message":"Copia descifrada exportada: "+str(target)}
    finally:
        if work.exists(): shutil.rmtree(work)

def start(sid, kind, source="", parent="", entry="", output=""):
    s=_session(sid)
    if kind not in ("import","export"): raise VaultError("Operación no válida.")
    if any(x["path"]==s["path"] and x["busy"] for x in _sessions.values()):
        raise VaultError("Ya hay una operación sobre esta bóveda.")
    # No se permite una segunda sesión que pueda sobrescribir un índice obsoleto.
    if sum(x["path"]==s["path"] for x in _sessions.values())>1:
        raise VaultError("Bloquea las otras sesiones de esta bóveda antes de modificarla.")
    s["busy"]=True; oid=_id()
    op={"phase":"Preparando", "done":0,"total":0,"files":0,"current":"","start":time.monotonic(),"finished":False,"cancel":False,"error":"","result":{}}
    _ops[oid]=op
    def run():
        try:
            op["result"]=_import(s,source,parent,op) if kind=="import" else _export(s,entry,output,op)
        except Exception as e: op["error"]=str(e) or type(e).__name__
        finally:
            op["phase"]="Finalizado";s["busy"]=False;op["finished"]=True
    threading.Thread(target=run,daemon=True).start()
    return {"operation":oid}

def poll(oid):
    op=_ops.get(oid)
    if not op: raise VaultError("Operación no encontrada.")
    return dict(op)

def cancel(oid):
    if oid in _ops: _ops[oid]["cancel"]=True
    return {}

def forget(oid):
    if oid in _ops and _ops[oid]["finished"]: del _ops[oid]
    return {}

def dispatch(action, payload="{}"):
    try:
        args=json.loads(payload)
        with _guard:
            funcs={"create":create,"open":open_vault,"recover":recover,"list":list_entries,"close":close,"start":start,"poll":poll,"cancel":cancel,"forget":forget}
            if action not in funcs: raise VaultError("Acción no válida.")
            data=funcs[action](**args)
        return json.dumps({"ok":True,"data":data},ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok":False,"message":str(e) or type(e).__name__},ensure_ascii=False)
