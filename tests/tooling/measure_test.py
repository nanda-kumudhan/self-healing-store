import csv
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MeasurementTest(unittest.TestCase):
    def test_records_failures_without_reporting_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "runs.csv"
            result = subprocess.run([sys.executable, str(ROOT / "scripts/measure.py"),
                "--label", "intentional-failure", "--bytes", "17", "--output", str(destination),
                "--", sys.executable, "-c", "raise SystemExit(7)"], capture_output=True, text=True)
            self.assertEqual(7, result.returncode, result.stderr)
            with destination.open(newline="") as source:
                rows = list(csv.DictReader(source))
            self.assertEqual(1, len(rows))
            self.assertEqual("7", rows[0]["exit_code"])
            self.assertEqual("17", rows[0]["data_bytes"])
            self.assertGreater(float(rows[0]["elapsed_seconds"]), 0)

    def test_rejects_invalid_size_before_running_a_command(self):
        with tempfile.TemporaryDirectory() as temporary:
            marker = Path(temporary) / "should-not-exist"
            result = subprocess.run([sys.executable, str(ROOT / "scripts/measure.py"),
                "--label", "invalid", "--bytes", "-1", "--", sys.executable, "-c",
                "from pathlib import Path; import sys; Path(sys.argv[1]).touch()", str(marker)],
                capture_output=True)
            self.assertEqual(2, result.returncode)
            self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
