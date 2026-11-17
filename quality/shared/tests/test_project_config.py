"""Shared project configuration validation tests."""

import json
import tempfile
import unittest
from pathlib import Path

from quality.project_config import load


class ProjectConfigTests(unittest.TestCase):
    """Validate repository layout and configuration security boundaries."""

    def test_other_layout(self):
        """Allow a project with a different source and Maven directory."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "config.json"
            config.write_text(json.dumps({
                "java_source": "service/src/main/java",
                "maven_project": "service",
                "project_name": "Another App",
            }), encoding="utf-8")
            data = load(root, config)
            self.assertEqual(
                Path(data["java_source"]),
                (root / "service/src/main/java").resolve(),
            )
            self.assertEqual(data["project_name"], "Another App")

    def test_escape_rejected(self):
        """Reject paths that leave the configured repository."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "config.json"
            config.write_text('{"java_source":"../outside"}', encoding="utf-8")
            with self.assertRaises(ValueError):
                load(root, config)

    def test_unknown_key_rejected(self):
        """Reject unknown keys rather than silently ignoring mistakes."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "config.json"
            config.write_text('{"unsupported":true}', encoding="utf-8")
            with self.assertRaises(ValueError):
                load(root, config)

    def test_jdbc_parts_are_exact_and_credential_free(self):
        """Accept local JDBC components but reject unsafe exceptions."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = root / "config.json"
            rule = {
                "file": "service/application.properties",
                "jdbc_parts": {
                    "scheme": "postgresql", "host": "localhost",
                    "port": 5432, "database": "example",
                },
            }
            config.write_text(json.dumps({"safe_jdbc_urls": [rule]}))
            data = load(root, config)
            self.assertEqual(len(data["safe_jdbc_urls"]), 1)
            self.assertEqual(data["safe_jdbc_urls"][0]["file"], rule["file"])
            for key, bad in (("host", "remote.example"),
                             ("database", "example?password=secret"),
                             ("port", 0)):
                with self.subTest(key=key):
                    invalid = json.loads(json.dumps(rule))
                    invalid["jdbc_parts"][key] = bad
                    config.write_text(json.dumps({"safe_jdbc_urls": [invalid]}))
                    with self.assertRaises(ValueError):
                        load(root, config)
