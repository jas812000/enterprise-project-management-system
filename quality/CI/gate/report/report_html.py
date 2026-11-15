"""
HTML Report Builder

Thin shim — delegates to render_report package.

Functions
---------
build_html_report(evaluated_doc, env) -> str
    Build the full HTML quality gate report document.
"""

from quality.CI.gate.report.render_report.report_builder import build_html_report

__all__ = ["build_html_report"]
