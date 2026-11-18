import json
import tempfile
import time
import unittest
from pathlib import Path

import mixcoara_bridge as bridge
import mixcoara_nube as nube


class BridgeProgressTests(unittest.TestCase):
    def test_progress_fraction_by_bytes(self):
        p = nube.Progreso()
        p.iniciar("bloques", archivos=1, carpetas=0, bytes_=100)
        p.avanzar_bytes(25)
        self.assertAlmostEqual(p.fraccion(), 0.25, places=2)

    def test_progress_serialization_contains_flags(self):
        p = bridge.ProgresoOperacion("op-1")
        data = p.serializar()
        self.assertEqual(data["op_id"], "op-1")
        self.assertFalse(data["cancelado"])
        self.assertFalse(data["terminado"])

    def test_background_cleanup_is_operation_scoped(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            foreign = base / "foreign.keep"
            foreign.write_text("do not delete", encoding="utf-8")

            original_tmp_dir = nube.directorio_temporal
            nube.directorio_temporal = lambda: base
            try:
                op_id = "scope-clean"

                def task(_prog, op_tmp_dir):
                    (op_tmp_dir / "tmp.bin").write_bytes(b"abc")
                    return {"ok": 1}

                bridge._ejecutar_en_fondo(op_id, task)
                for _ in range(80):
                    poll = json.loads(bridge.obtener_progreso(op_id))
                    if poll["data"]["terminado"]:
                        break
                    time.sleep(0.05)
                self.assertTrue(foreign.exists())
                self.assertFalse((base / f"op_{op_id}").exists())
            finally:
                nube.directorio_temporal = original_tmp_dir

    def test_cancellation_marks_operation(self):
        with tempfile.TemporaryDirectory() as td:
            base = Path(td)
            original_tmp_dir = nube.directorio_temporal
            nube.directorio_temporal = lambda: base
            try:
                op_id = "cancel-op"

                def task(prog, _op_tmp_dir):
                    prog.iniciar("trabajo", archivos=1, carpetas=0, bytes_=0)
                    for _ in range(50):
                        time.sleep(0.02)
                        prog.refrescar(True)
                    return {"ok": 1}

                bridge._ejecutar_en_fondo(op_id, task)
                bridge.cancelar_operacion(op_id)

                done = None
                for _ in range(120):
                    poll = json.loads(bridge.obtener_progreso(op_id))
                    data = poll["data"]
                    if data["terminado"]:
                        done = data
                        break
                    time.sleep(0.05)
                self.assertIsNotNone(done)
                self.assertTrue(done["cancelado"])
                self.assertIn("cancelada", done["error"].lower())
            finally:
                nube.directorio_temporal = original_tmp_dir


if __name__ == "__main__":
    unittest.main()
