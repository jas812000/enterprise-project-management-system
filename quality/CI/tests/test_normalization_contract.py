"""Contract tests for the EPMS CI quality-gate normalization layer."""

import unittest

from quality.CI.gate.normalize import TOOL_PARSERS
from quality.CI.gate.schemas import base_tool_result


class NormalizationContractTests(unittest.TestCase):
    """Regression tests for the corresponding EPMS quality component."""

    def test_all_twelve_scanners_are_registered(self):
        """Verify all twelve scanners are registered."""
        names = [name for name, _ in TOOL_PARSERS]

        self.assertEqual(len(names), 12)
        self.assertEqual(len(names), len(set(names)))

    def test_base_result_defaults_are_fail_safe(self):
        """Verify base result defaults are fail safe."""
        result = base_tool_result("checkstyle")

        self.assertFalse(result["artifact_present"])
        self.assertFalse(result["executed"])
        self.assertFalse(result["runtime_error"])
        self.assertEqual(result["violation_count"], 0)
        self.assertEqual(result["findings"], [])
        self.assertEqual(result["metadata"], {})

    def test_base_result_has_required_fields(self):
        """Verify base result has required fields."""
        result = base_tool_result("spotbugs")

        required_fields = {
            "tool",
            "artifact_present",
            "executed",
            "runtime_error",
            "findings",
            "violation_count",
            "severity_counts",
            "max_severity",
            "metadata",
        }

        self.assertTrue(required_fields.issubset(result))


if __name__ == "__main__":
    unittest.main()
