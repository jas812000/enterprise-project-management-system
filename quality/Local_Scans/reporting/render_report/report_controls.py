
"""Report-specific controls and legend with shared severity choices."""

from quality.shared.reporting.report_controls import SEVERITY_OPTIONS, render_legend


FILTER_BAR = f"""
<div class="report-actions">
    <button type="button" id="btn-print" class="btn-print">
        Print / Save as PDF
    </button>
</div>

<div class="filter-bar">
    <div>
        <label for="sev-filter">Severity</label>
        <select id="sev-filter">
{SEVERITY_OPTIONS}            <option value="info">Info and above</option>
        </select>
    </div>
    <div class="search-wrap">
        <label for="search-input">Search</label>
        <div>
            <input type="text" id="search-input"
                   placeholder="file path, rule, or message..." />
            <button class="btn-clear" id="btn-clear">Clear</button>
        </div>
    </div>
    <button class="btn-reset" id="btn-reset">Reset</button>
</div>
"""


LEGEND_BLOCK = render_legend("""
        <tr>
            <td>PASSED</td>
            <td>Scanner completed successfully with no violations</td>
        </tr>
        <tr>
            <td>FAILED</td>
            <td>Scanner detected one or more violations</td>
        </tr>
        <tr>
            <td>ERROR</td>
            <td>Scanner could not complete successfully</td>
        </tr>
        <tr>
            <td>SKIPPED</td>
            <td>Required scanner was not executed; overall report is incomplete</td>
        </tr>
        <tr>
            <td>APPROVED</td>
            <td>All three required scanners passed</td>
        </tr>
        <tr>
            <td>INCOMPLETE</td>
            <td>One or more required scanners were skipped</td>
        </tr>
""")
