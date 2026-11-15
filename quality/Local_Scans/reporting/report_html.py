"""
Report HTML Builder — thin shim.

Delegates to the render_report package.
All callers continue to use build_html(context) unchanged.
"""

from .render_report import build_html

__all__ = ["build_html"]
