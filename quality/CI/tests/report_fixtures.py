"""Reusable, synthetic CI report fixtures (never actual credentials)."""


def github_environment(**overrides) -> dict:
    """Create a valid GitHub Actions context for report tests."""
    env = {
        "run_number": "123",
        "run_id": "456789",
        "sha": "a" * 40,
        "server_url": "https://github.com",
        "repository": "jas812000/enterprise-project-management-system",
    }
    env.update(overrides)
    return env


def blocking_scan(tool: str, severity: str, findings: list[dict]) -> dict:
    """Build the normalized blocking result used in security tests."""
    return {
        "tool": tool,
        "blocking": True,
        "policy_violation": True,
        "reason": "",
        "normalized": {
            "executed": True,
            "runtime_error": False,
            "violation_count": len(findings),
            "severity_counts": {severity: len(findings)},
            "findings": findings,
        },
    }
