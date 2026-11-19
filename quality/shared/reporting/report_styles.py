"""Common CSS rules used by Local and CI Quality Gates reports."""

BASE_CSS = """
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background: #f5f6fa; color: #2c3e50; padding: 24px; font-size: 14px;
}
h1 { font-size: 1.6em; margin-bottom: 8px; }
h2 { font-size: 1.2em; margin: 24px 0 12px; padding-bottom: 6px;
     border-bottom: 2px solid #dde; }
h3 { font-size: 1em; margin-bottom: 8px; color: #555; }
.banner { padding: 12px 20px; border-radius: 6px; color: #fff;
          font-weight: bold; font-size: 1.05em; margin: 16px 0; }
.info-card { background: #fff; border-radius: 6px; padding: 16px 20px;
             margin-bottom: 16px; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }
.info-table td { padding: 4px 12px 4px 0; vertical-align: top; }
.info-table td:first-child { color: #7f8c8d; white-space: nowrap; }
table { width: 100%; border-collapse: collapse; background: #fff;
        border-radius: 6px; overflow: hidden;
        box-shadow: 0 1px 3px rgba(0,0,0,0.08); margin-bottom: 16px; }
th { background: #2c3e50; color: #fff; padding: 10px 14px; text-align: left;
     font-size: 0.85em; text-transform: uppercase; letter-spacing: 0.05em; }
td { padding: 8px 14px; border-bottom: 1px solid #eee; vertical-align: top; }
tr:last-child td { border-bottom: none; }
tr:hover td { background: #f8f9fa; }
code { background: #f0f2f5; padding: 2px 6px; border-radius: 3px;
       font-size: 0.9em; font-family: "SFMono-Regular", Consolas, monospace; }
.tool-section { background: #fff; border-radius: 6px; margin-bottom: 20px;
                box-shadow: 0 1px 3px rgba(0,0,0,0.08); overflow: hidden; }
"""


# Consistent report severity palette for CI and Local Scans.
SEVERITY_COLORS: dict[str, str] = {
    "critical": "#7c0000",
    "high": "#c0392b",
    "medium": "#e67e22",
    "low": "#f1c40f",
    "info": "#3498db",
}
