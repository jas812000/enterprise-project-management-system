"""
Open the local quality report in the default browser.

Browser-launch failures are nonfatal because the HTML report
remains available on disk.
"""

import os
import sys
import webbrowser
from pathlib import Path


WORK_DIR = os.environ["WORK_DIR"]
REPORT = Path(WORK_DIR) / "local-report.html"


def main():
    """Open the generated report when a browser is available."""

    if not REPORT.is_file():
        print(
            f"ERROR: HTML report not found: {REPORT}",
            file=sys.stderr,
        )
        return 2

    try:
        opened = webbrowser.open(REPORT.resolve().as_uri())
    # Browser-launch failures must not fail the quality report.
    except Exception as exc:  # pylint: disable=broad-except
        print(
            f"WARNING: Unable to open HTML report: {exc}",
            file=sys.stderr,
        )
        return 0

    if not opened:
        print(
            "WARNING: Browser did not confirm opening the report.",
            file=sys.stderr,
        )
        return 0

    print(
        f"[open-report] Opened in browser: "
        f"{REPORT.resolve().as_uri()}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
