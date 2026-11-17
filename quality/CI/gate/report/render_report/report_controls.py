
"""Report-specific controls and legend with shared severity choices."""

from quality.shared.reporting.report_controls import SEVERITY_OPTIONS, render_legend


FILTER_BAR = f"""
<div class="filter-bar">
    <div>
        <label for="sev-filter">Severity</label>
        <select id="sev-filter">
{SEVERITY_OPTIONS}        </select>
    </div>
    <div class="search-wrap">
        <label for="search-input">Search</label>
        <input type="text" id="search-input"
               placeholder="file path, rule, or message..." />
        <button class="btn-clear" id="btn-clear">Clear</button>
    </div>
    <button class="btn-reset" id="btn-reset">Reset</button>
</div>
"""


LEGEND_BLOCK = render_legend("""
        <tr>
            <td>SUCCESS</td>
            <td>Tool ran and found no violations</td>
        </tr>
        <tr>
            <td>FAILURE</td>
            <td>Tool found one or more violations</td>
        </tr>
        <tr>
            <td>DISABLED</td>
            <td>Tool is not yet configured</td>
        </tr>
        <tr>
            <td>Enforced</td>
            <td>Violations from this tool will block the merge</td>
        </tr>
        <tr>
            <td>Advisory</td>
            <td>Violations are reported but will not block the merge</td>
        </tr>
""")
