"""
Filter bar and legend block HTML for the EPMS Quality Gate report.

Constants
---------
FILTER_BAR  — HTML filter bar with severity dropdown, search, clear, and reset
LEGEND_BLOCK — HTML legend card explaining status and role values
"""

FILTER_BAR = """
<div class="filter-bar">
    <div>
        <label for="sev-filter">Severity</label>
        <select id="sev-filter">
            <option value="all">All</option>
            <option value="critical">Critical only</option>
            <option value="high">High and above</option>
            <option value="medium">Medium and above</option>
            <option value="low">Low and above</option>
        </select>
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

LEGEND_BLOCK = """
<div class="info-card">
    <h3>Legend</h3>
    <table class="info-table">
        <tr><td>SUCCESS</td>
            <td>Tool ran and found no violations</td></tr>
        <tr><td>FAILURE</td>
            <td>Tool found one or more violations</td></tr>
        <tr><td>DISABLED</td>
            <td>Tool is not yet configured</td></tr>
        <tr><td>Enforced</td>
            <td>Violations from this tool will block the merge</td></tr>
        <tr><td>Advisory</td>
            <td>Violations are reported but will not block the merge</td></tr>
    </table>
</div>"""
