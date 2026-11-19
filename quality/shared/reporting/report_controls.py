"""Shared severity filter controls for Quality Gates HTML reports."""

SEVERITY_OPTIONS = """
            <option value="all">All</option>
            <option value="critical">Critical only</option>
            <option value="high">High and above</option>
            <option value="medium">Medium and above</option>
            <option value="low">Low and above</option>
"""

def render_legend(rows: str) -> str:
    """Wrap report-specific legend entries in consistent semantic markup."""
    return (
        '<div class="info-card">\n'
        '    <h3>Legend</h3>\n'
        '    <table class="info-table">\n'
        f'{rows}'
        '    </table>\n'
        '</div>'
    )


def summary_table_head(show_cell: str) -> str:
    """Build a shared summary table header with a caller-specific Show cell."""
    return (
        '        <tr>\n'
        f'            {show_cell}\n'
        '            <th>Tool</th>\n'
        '            <th>Category</th>\n'
        '            <th>Status</th>\n'
        '            <th>Role</th>\n'
        '            <th>Findings</th>\n'
        '        </tr>'
    )
