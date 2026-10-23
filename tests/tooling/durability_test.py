import importlib.util
import math
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("model", ROOT / "durability/model.py")
model = importlib.util.module_from_spec(spec)
spec.loader.exec_module(model)


class DurabilityTest(unittest.TestCase):
    def test_no_failures_or_instant_repair(self):
        self.assertEqual(0, model.annual_loss(3, 2, 0, 60))
        self.assertEqual(0, model.annual_loss(3, 2, 0.02, 0))

    def test_no_parity_matches_first_failure(self):
        self.assertAlmostEqual(-math.expm1(-3 * .02), model.annual_loss(3, 0, .02, 60), places=13)

    def test_no_repair_matches_binomial_tail(self):
        p = -math.expm1(-.02)
        expected = sum(math.comb(5, i) * p**i * (1-p)**(5-i) for i in range(3, 6))
        self.assertAlmostEqual(expected, model.annual_loss(3, 2, .02, None), delta=expected*1e-10)

    def test_longer_repairs_increase_loss_and_more_parity_reduces_it(self):
        values = [model.annual_loss(3, 2, .02, seconds) for seconds in [1, 60, 3600, 86400]]
        self.assertEqual(sorted(values), values)
        self.assertGreater(values[0], 0)
        self.assertLess(model.annual_loss(3, 3, .02, 3600), model.annual_loss(3, 2, .02, 3600))

    def test_invalid_inputs(self):
        for args in [(0, 2, .02, 1), (3, -1, .02, 1), (3, 2, float('nan'), 1), (3, 2, .02, -1)]:
            with self.assertRaises(ValueError):
                model.annual_loss(*args)
