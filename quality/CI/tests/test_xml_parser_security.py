"""Reject XML entity expansion in scanner reports."""

import tempfile
import unittest
from pathlib import Path

from quality.CI.gate.parsers.checkstyle import _load_checkstyle_root
from quality.CI.gate.parsers.pmd import _load_pmd_root
from quality.CI.gate.parsers.spotbugs import _load_spotbugs_root


class XmlParserSecurityTests(unittest.TestCase):
    """Scanner XML parsing must fail closed on DTD entity expansion."""

    def test_rejects_entity_expansion(self):
        """Reject internal entities without accepting the XML report."""
        payload = (
            '<!DOCTYPE checkstyle [<!ENTITY expanded "unsafe">]>'
            '<checkstyle><file name="&expanded;"/></checkstyle>'
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "scanner.xml"
            path.write_text(payload, encoding="utf-8")
            for loader in (
                _load_checkstyle_root,
                _load_pmd_root,
                _load_spotbugs_root,
            ):
                with self.subTest(loader=loader.__name__):
                    result = {"metadata": {}, "runtime_error": False}
                    root = loader(path, result)
                    self.assertIsNone(root)
                    self.assertTrue(result["runtime_error"])
