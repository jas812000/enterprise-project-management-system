"""
JavaScript filtering logic for the EPMS Quality Gate HTML report.

Handles:
- Severity filter
- Per-tool visibility toggles
- Search bar with clear button
- Reset button
"""

FILTER_JS = """
<script>
(function() {

  // Severity ordering for "and above" filters
  const SEV_ORDER = { critical: 5, high: 4, medium: 3, low: 2, info: 1 };

  // Track manually hidden tools (toggle OFF)
  const hiddenTools = new Set();

  function getMinSev() {
    return document.getElementById('sev-filter').value;
  }

  function getSearchTerm() {
    return document.getElementById('search-input').value.trim().toLowerCase();
  }

  function sevPasses(rowSev, minSev) {
    if (minSev === 'all') return true;
    const rowVal = SEV_ORDER[rowSev] || 0;
    const minVal = SEV_ORDER[minSev] || 0;
    return rowVal >= minVal;
  }

  function applyFilters() {
    const minSev = getMinSev();
    const term = getSearchTerm();

    document.querySelectorAll('.tool-section').forEach(function(section) {
      const tool = section.dataset.tool;

      // If tool is manually hidden, keep it hidden regardless
      if (hiddenTools.has(tool)) {
        section.classList.add('hidden');
        return;
      }

      const rows = section.querySelectorAll('.finding-row');
      let visibleCount = 0;

      rows.forEach(function(row) {
        const sev = (row.dataset.severity || '').toLowerCase();
        const text = (row.dataset.text || '').toLowerCase();

        const sevOk = sevPasses(sev, minSev);
        const termOk = term === '' || text.includes(term);

        if (sevOk && termOk) {
          row.classList.remove('hidden');
          visibleCount++;
        } else {
          row.classList.add('hidden');
        }
      });

      // If no finding rows exist (disabled/error), always show section
      if (rows.length === 0) {
        section.classList.remove('hidden');
        const noRes = section.querySelector('.no-results');
        if (noRes) noRes.style.display = 'none';
        return;
      }

      // Collapse section if all rows filtered out
      if (visibleCount === 0) {
        section.classList.add('hidden');
      } else {
        section.classList.remove('hidden');
      }

      const noRes = section.querySelector('.no-results');
      if (noRes) noRes.style.display = visibleCount === 0 ? 'block' : 'none';
    });
  }

  function toggleTool(tool, visible) {
    const section = document.getElementById('tool-' + tool);
    if (visible) {
      hiddenTools.delete(tool);
      section.classList.remove('hidden');
      applyFilters();
    } else {
      hiddenTools.add(tool);
      section.classList.add('hidden');
    }
  }

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
