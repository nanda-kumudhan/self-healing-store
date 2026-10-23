import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("chaos", ROOT / "tests/chaos/chaos.py")
chaos = importlib.util.module_from_spec(spec)
spec.loader.exec_module(chaos)


class CorruptionToolTest(unittest.TestCase):
    def test_flips_an_existing_byte_without_touching_other_offsets(self):
        with patch.object(sys, "argv", ["chaos", "corrupt", "node-2",
            "00000000-0000-0000-0000-000000000001", "--offset", "3"]), \
            patch.object(chaos, "compose", return_value=subprocess.CompletedProcess([], 0, b"\x01")) as execute, \
            patch.object(chaos, "record"):
            chaos.main()
        self.assertEqual(2, execute.call_count)
        write = execute.call_args_list[1]
        self.assertEqual(b"\x00", write.kwargs["input"])
        self.assertIn("seek=3", write.args)
        self.assertIn("conv=notrunc", write.args)

    def test_offset_beyond_end_does_not_extend_the_file(self):
        with patch.object(sys, "argv", ["chaos", "corrupt", "node-2",
            "00000000-0000-0000-0000-000000000001", "--offset", "100"]), \
            patch.object(chaos, "compose", return_value=subprocess.CompletedProcess([], 0, b"")) as execute, \
            patch.object(chaos, "record") as record:
            with self.assertRaises(SystemExit) as error:
                chaos.main()
        self.assertEqual(2, error.exception.code)
        self.assertEqual(1, execute.call_count)
        record.assert_not_called()


if __name__ == "__main__":
    unittest.main()
