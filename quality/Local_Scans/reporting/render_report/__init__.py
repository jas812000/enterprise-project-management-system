"""
render_report — Local Quality Gate HTML report package.

Public API
----------
build_html(context) -> str
    Build the complete HTML report from a context dict.
"""

from .report_builder import build_html

__all__ = ["build_html"]
