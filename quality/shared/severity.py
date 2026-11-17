"""Aggregate normalized scanner severity counts without applying policy."""


def aggregate_severity_counts(results: list[dict], levels: list[str]) -> dict[str, int]:
    """Sum known severity counts; ignore unrecognized severity labels."""
    counts = dict.fromkeys(levels, 0)
    for result in results:
        for level, count in result.get("severity_counts", {}).items():
            if level in counts:
                counts[level] += count
    return counts


TRUFFLEHOG_DETECTORS = frozenset({
    "Github", "Gitlab", "AWS", "Slack", "Stripe", "Postgres", "PrivateKey"
})
