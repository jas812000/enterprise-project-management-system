
"""Report-specific filtering script using shared JavaScript helpers."""

from quality.shared.reporting.report_scripts import APPLY_FILTERS, FILTER_HELPERS, TOGGLE_TOOL


FILTER_JS = (
    """
<script>
(function() {
  const SEV_ORDER = { critical: 5, high: 4, medium: 3, low: 2, info: 1 };
  const hiddenTools = new Set();
  const hideEmptySections = false;

"""
    + FILTER_HELPERS
    + """
  function sevPasses(rowSev, minSev) {
    if (minSev === 'all') return true;
    return (SEV_ORDER[rowSev] || 0) >= (SEV_ORDER[minSev] || 0);
  }


"""
    + APPLY_FILTERS
    + """
"""
    + TOGGLE_TOOL
    + """
  function resetFilters() {
    document.getElementById('sev-filter').value = 'all';
    document.getElementById('search-input').value = '';
    hiddenTools.clear();

    document.querySelectorAll('.tool-toggle').forEach(function(cb) {
      cb.checked = true;
    });

    applyFilters();
  }

  document.addEventListener('DOMContentLoaded', function() {
    document.getElementById('sev-filter').addEventListener('change', applyFilters);
    document.getElementById('search-input').addEventListener('input', applyFilters);

    document.getElementById('btn-clear').addEventListener('click', function() {
      document.getElementById('search-input').value = '';
      applyFilters();
    });

    document.getElementById('btn-reset').addEventListener('click', resetFilters);

    document.getElementById('btn-print').addEventListener('click', function() {
      window.print();
    });

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
