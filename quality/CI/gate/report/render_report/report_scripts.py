
"""Report-specific filtering script using shared JavaScript helpers."""

from quality.shared.reporting.report_scripts import APPLY_FILTERS, FILTER_HELPERS, TOGGLE_TOOL


FILTER_JS = (
    """
<script>
(function() {

  // Severity ordering for "and above" filters
  const SEV_ORDER = { critical: 5, high: 4, medium: 3, low: 2, info: 1 };

  // Track manually hidden tools (toggle OFF)
  const hiddenTools = new Set();
  const hideEmptySections = true;

"""
    + FILTER_HELPERS
    + """
  function sevPasses(rowSev, minSev) {
    if (minSev === 'all') return true;
    const rowVal = SEV_ORDER[rowSev] || 0;
    const minVal = SEV_ORDER[minSev] || 0;
    return rowVal >= minVal;
  }


"""
    + APPLY_FILTERS
    + """
"""
    + TOGGLE_TOOL
    + """
  function resetFilters() {
    // Reset severity
    document.getElementById('sev-filter').value = 'all';

    // Reset search
    document.getElementById('search-input').value = '';

    // Reset all toggles
    hiddenTools.clear();
    document.querySelectorAll('.tool-toggle').forEach(function(cb) {
      cb.checked = true;
    });

    // Re-apply (shows everything)
    applyFilters();
  }

  // Wire up events after DOM ready
  document.addEventListener('DOMContentLoaded', function() {
    document.getElementById('sev-filter')
      .addEventListener('change', applyFilters);

    document.getElementById('search-input')
      .addEventListener('input', applyFilters);

    document.getElementById('btn-clear').addEventListener('click', function() {
      document.getElementById('search-input').value = '';
      applyFilters();
    });

    document.getElementById('btn-reset').addEventListener('click', resetFilters);

    document.querySelectorAll('.tool-toggle').forEach(function(cb) {
      cb.addEventListener('change', function() {
        toggleTool(this.dataset.tool, this.checked);
      });
    });
  });

})();
</script>
"""
)
