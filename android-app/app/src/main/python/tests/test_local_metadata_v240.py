"""Compatibilidad y protección de metadatos locales para Mixcoara 2.4.0.

Ejecutar: PYTHONPATH=app/src/main/python python -m pytest -q \
  app/src/main/python/tests/test_local_metadata_v240.py
"""
import json
from pathlib import Path
import time

import pytest
import mixcoara_local_vault as local

PASS = "testing-password-123"


def make_vault(tmp_path):
    path = tmp_path / "MiBoveda"
    data = local.create(str(path), PASS)
    local.close(data["session"])
    return path, data["recovery"]


def test_new_vault_uses_encrypted_hidden_metadata(tmp_path):
    path, recovery = make_vault(tmp_path)
    metadata = path / ".mixcoara" / "metadata.enc"
    assert metadata.is_file()
    assert not (path / "mixcoara.local.json").exists()
    raw = metadata.read_bytes()
    assert raw.startswith(local.META_MAGIC)
    assert PASS.encode() not in raw
    assert recovery.encode() not in raw
    assert not (path / "index.gcm").read_bytes().startswith(b"[")
    opened = local.open_vault(str(path), PASS)
    assert local.list_entries(opened["session"]) == {"entries": []}
    local.close(opened["session"])


def test_old_json_only_migrates_after_successful_unlock(tmp_path):
    path, _ = make_vault(tmp_path)
    envelope = local._header(path)
    previous = {k: v for k, v in envelope.items() if k != "private"}
    (path / "mixcoara.local.json").write_text(json.dumps(previous), encoding="utf-8")
    (path / ".mixcoara" / "metadata.enc").unlink()
    (path / ".mixcoara").rmdir()
    with pytest.raises(local.VaultError):
        local.open_vault(str(path), "incorrect-password")
    assert (path / "mixcoara.local.json").exists()
    assert not (path / ".mixcoara" / "metadata.enc").exists()
    reopened = local.open_vault(str(path), PASS)
    local.close(reopened["session"])
    assert (path / ".mixcoara" / "metadata.enc").exists()
    assert not (path / "mixcoara.local.json").exists()


def test_recovery_and_tamper_detection(tmp_path):
    path, recovery = make_vault(tmp_path)
    recovered = local.recover(str(path), recovery, "new-password-123")
    local.close(recovered["session"])
    with pytest.raises(local.VaultError):
        local.open_vault(str(path), PASS)
    reopened = local.open_vault(str(path), "new-password-123")
    local.close(reopened["session"])
    metadata = path / ".mixcoara" / "metadata.enc"
    raw = bytearray(metadata.read_bytes())
    # Se altera un byte del bloque autenticado privado sin invalidar el JSON exterior.
    outer = json.loads(raw[len(local.META_MAGIC):])
    encrypted = outer["private"]
    outer["private"] = ("A" if encrypted[0] != "A" else "B") + encrypted[1:]
    metadata.write_bytes(local.META_MAGIC + local._json(outer))
    with pytest.raises((local.VaultError, ValueError)):
        local.open_vault(str(path), "new-password-123")


def _wait(opid):
    for _ in range(200):
        status = local.poll(opid)
        if status["finished"]:
            local.forget(opid)
            assert not status["error"], status["error"]
            return status
        time.sleep(0.02)
    raise AssertionError("La operación no terminó")


def test_import_and_export_still_work(tmp_path):
    path, _ = make_vault(tmp_path)
    source = tmp_path / "archivo.txt"
    source.write_text("archivo de prueba", encoding="utf-8")
    destination = tmp_path / "exportado"
    destination.mkdir()
    data = local.open_vault(str(path), PASS)
    sid = data["session"]
    _wait(local.start(sid, "import", source=str(source))["operation"])
    entries = local.list_entries(sid)["entries"]
    assert len(entries) == 1 and entries[0]["name"] == "archivo.txt"
    _wait(local.start(sid, "export", entry=entries[0]["id"], output=str(destination))["operation"])
    assert (destination / "archivo.txt").read_text(encoding="utf-8") == "archivo de prueba"
    assert source.read_text(encoding="utf-8") == "archivo de prueba"
    local.close(sid)
