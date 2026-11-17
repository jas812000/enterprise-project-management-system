"""Validate host-project configuration shared by local and CI quality gates."""

import argparse
import json
import re
import shlex
from pathlib import Path

ALLOWED = {
    "project_name", "java_version", "scan_root", "java_source",
    "maven_project", "python_source", "javascript_source", "safe_jdbc_urls",
}
DEFAULTS = {
    "project_name": "Quality Gate",
    "java_version": "25",
    "scan_root": ".",
    "java_source": "backend/src/main/java",
    "maven_project": "backend",
    "python_source": "",
    "javascript_source": "",
}
JDBC_PART_KEYS = {"scheme", "host", "port", "database"}


def resolve_path(repo: Path, value: str) -> str:
    """Resolve a repository-relative path without allowing traversal outside it."""
    if not isinstance(value, str) or not value or Path(value).is_absolute():
        raise ValueError("Paths must be non-empty repository-relative strings")
    resolved = (repo / value).resolve()
    if not resolved.is_relative_to(repo):
        raise ValueError("Path escapes repository root")
    return str(resolved)


def _safe_jdbc_rule(repo: Path, rule: dict) -> dict:
    """Validate an exact, credential-free local JDBC exception."""
    if not isinstance(rule, dict) or set(rule) != {"file", "jdbc_parts"}:
        raise ValueError("Each safe JDBC exception requires file and jdbc_parts")
    parts = rule["jdbc_parts"]
    if not isinstance(parts, dict) or set(parts) != JDBC_PART_KEYS:
        raise ValueError("Invalid JDBC parts")
    if parts["scheme"] != "postgresql" or parts["host"] != "localhost":
        raise ValueError("Only local PostgreSQL JDBC exceptions are allowed")
    port = parts["port"]
    database = parts["database"]
    if (
        not isinstance(port, int)
        or isinstance(port, bool)
        or not 1 <= port <= 65535
    ):
        raise ValueError("Invalid JDBC port")
    if not isinstance(database, str) or not re.fullmatch(r"[A-Za-z0-9_]+", database):
        raise ValueError("Invalid JDBC database")
    path = resolve_path(repo, rule["file"])
    return {
        "file": str(Path(path).relative_to(repo)),
        "url": f"jdbc:{parts['scheme']}://{parts['host']}:{port}/{database}",
    }


def load(repo: Path, config: Path) -> dict:
    """Load and validate the host project's paths and scanner exceptions."""
    repo = repo.resolve()
    supplied = json.loads(config.read_text(encoding="utf-8"))
    if not isinstance(supplied, dict) or set(supplied) - ALLOWED:
        raise ValueError("Unsupported project configuration keys")
    data = {**DEFAULTS, **supplied}
    for name in ("scan_root", "java_source", "maven_project"):
        data[name] = resolve_path(repo, data[name])
    for name in ("python_source", "javascript_source"):
        data[name] = resolve_path(repo, data[name]) if data[name] else ""
    name = data["project_name"]
    if not isinstance(name, str) or not name.strip() or len(name) > 100:
        raise ValueError("Invalid project_name")
    if str(data["java_version"]) not in ("8", "11", "17", "21", "25"):
        raise ValueError("Unsupported Java version")
    data["java_version"] = str(data["java_version"])
    rules = data.get("safe_jdbc_urls", [])
    if not isinstance(rules, list) or len(rules) > 20:
        raise ValueError("safe_jdbc_urls must be a list of at most 20 entries")
    data["safe_jdbc_urls"] = [_safe_jdbc_rule(repo, rule) for rule in rules]
    return data


def main():
    """Print shell-safe configuration assignments for the caller."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--shell", action="store_true", required=True)
    parser.add_argument("repo", type=Path)
    parser.add_argument("config", type=Path)
    args = parser.parse_args()
    data = load(args.repo, args.config)
    exports = {
        "QUALITY_JAVA_SOURCE": data["java_source"],
        "QUALITY_MAVEN_DIR": data["maven_project"],
        "QUALITY_SCAN_ROOT": data["scan_root"],
        "QUALITY_PYTHON_SOURCE": data["python_source"],
        "QUALITY_JS_SOURCE": data["javascript_source"],
        "QUALITY_PROJECT_NAME": data["project_name"],
        "QUALITY_JAVA_VERSION": data["java_version"],
        "QUALITY_CONFIG": str(args.config.resolve()),
    }
    for key, value in exports.items():
        print(f"{key}={shlex.quote(value)}")


if __name__ == "__main__":
    main()
