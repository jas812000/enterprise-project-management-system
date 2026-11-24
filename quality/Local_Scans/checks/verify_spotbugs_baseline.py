#!/usr/bin/env python3
"""Reject drift from the approved, unfiltered SpotBugs finding baseline."""

import json
import sys
from collections import Counter
from pathlib import Path

from defusedxml import ElementTree
from defusedxml.common import DefusedXmlException

EXPECTED_SCHEMA_VERSION = 1
EXPECTED_SPOTBUGS_VERSION = "4.10.5"


def finding_key(finding):
    """Return the stable class, warning type, and instance hash identity."""
    return (
        finding["class"],
        finding["type"],
        finding["instanceHash"],
    )


def load_expected(baseline_path):
    """Load and validate the explicitly approved finding identities."""
    with baseline_path.open("r", encoding="utf-8") as baseline_file:
        baseline = json.load(baseline_file)

    if (
        baseline.get("schema_version") != EXPECTED_SCHEMA_VERSION
        or baseline.get("spotbugs_version") != EXPECTED_SPOTBUGS_VERSION
    ):
        raise ValueError("Unsupported baseline schema or SpotBugs version")

    findings = baseline["findings"]
    if not isinstance(findings, list):
        raise ValueError("Baseline findings must be a list")
    expected = Counter(finding_key(finding) for finding in findings)
    if any(not all(identity) for identity in expected):
        raise ValueError("Baseline contains an incomplete finding identity")
    if any(count != 1 for count in expected.values()):
        raise ValueError("Baseline contains duplicate finding identities")
    return expected


def load_observed(report_path):
    """Parse the unfiltered report with protections against malicious XML."""
    root = ElementTree.parse(str(report_path)).getroot()
    if root.tag != "BugCollection":
        raise ValueError("Unexpected SpotBugs XML root")

    errors = root.find("Errors")
    if (
        errors is None
        or errors.get("errors") != "0"
        or errors.get("missingClasses") != "0"
    ):
        raise ValueError("SpotBugs analysis errors or missing classes")

    observed = Counter()
    for bug in root.findall("BugInstance"):
        bug_class = bug.find(".//Class")
        if bug_class is None:
            raise ValueError("SpotBugs finding missing class")
        identity = (
            bug_class.get("classname"),
            bug.get("type"),
            bug.get("instanceHash"),
        )
        if not all(identity):
            raise ValueError("SpotBugs finding has incomplete identity")
        observed[identity] += 1
    return observed


def main(argv):
    """Compare the native report with the approved baseline; fail on drift."""
    if len(argv) != 3:
        print(
            "Usage: verify_spotbugs_baseline.py report.xml baseline.json",
            file=sys.stderr,
        )
        return 2

    try:
        observed = load_observed(Path(argv[1]))
        expected = load_expected(Path(argv[2]))
        unexpected = observed - expected
        missing = expected - observed
        if unexpected or missing:
            print(
                "FAIL SpotBugs baseline drift: "
                f"unexpected={sum(unexpected.values())}, "
                f"missing={sum(missing.values())}",
                file=sys.stderr,
            )
            for category, findings in (
                ("unexpected", unexpected),
                ("missing", missing),
            ):
                for (class_name, bug_type, _hash), count in sorted(findings.items()):
                    print(
                        f"{category}: {class_name} {bug_type} count={count}",
                        file=sys.stderr,
                    )
            return 1

        print(
            "PASS SpotBugs approved baseline: "
            f"{sum(observed.values())} unchanged findings"
        )
        return 0
    except (
        OSError, ValueError, KeyError, TypeError,
        ElementTree.ParseError, DefusedXmlException,
    ) as exc:
        print(f"FAIL SpotBugs baseline verification: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
