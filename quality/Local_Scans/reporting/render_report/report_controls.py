"""
Filter bar and legend block HTML constants for the EPMS Local Quality Gate report.
"""

FILTER_BAR = """
<div class="report-actions">
    <button type="button" id="btn-print" class="btn-print">
        Print / Save as PDF
    </button>
</div>

<div class="filter-bar">
    <div>
        <label for="sev-filter">Severity</label>
        <select id="sev-filter">
            <option value="all">All</option>
            <option value="critical">Critical only</option>
            <option value="high">High and above</option>
            <option value="medium">Medium and above</option>
            <option value="low">Low and above</option>
            <option value="info">Info and above</option>
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

LEGEND_BLOCK = """
<div class="info-card">
    <h3>Legend</h3>
    <table class="info-table">
        <tr><td>PASSED</td>
            <td>Scanner completed successfully with no violations</td></tr>
        <tr><td>FAILED</td>
            <td>Scanner detected one or more violations</td></tr>
        <tr><td>ERROR</td>
            <td>Scanner could not complete successfully</td></tr>
        <tr><td>SKIPPED</td>
            <td>Required scanner was not executed; overall report is incomplete</td></tr>
        <tr><td>APPROVED</td>
            <td>All three required scanners passed</td></tr>
        <tr><td>INCOMPLETE</td>
            <td>One or more required scanners were skipped</td></tr>
    </table>
</div>"""
