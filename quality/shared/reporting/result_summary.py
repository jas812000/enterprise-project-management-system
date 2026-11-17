"""Read common normalized scanner summary fields without changing policy."""


def summary_fields(result: dict) -> tuple:
    """Return policy violation, blocking flag, reason, and finding count."""
    normalized = result.get("normalized", {})
    return (
        result.get("policy_violation", False),
        result.get("blocking", False),
        result.get("reason", ""),
        normalized.get("violation_count", 0),
    )
