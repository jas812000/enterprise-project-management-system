"""
JavaScript filtering logic for the EPMS Local Quality Gate HTML report.
"""

FILTER_JS = """
<script>
(function() {
  const SEV_ORDER = { critical: 5, high: 4, medium: 3, low: 2, info: 1 };
  const hiddenTools = new Set();

  function getMinSev() {
    return document.getElementById('sev-filter').value;
  }

  function getSearchTerm() {
    return document.getElementById('search-input').value.trim().toLowerCase();
  }

  function sevPasses(rowSev, minSev) {
    if (minSev === 'all') return true;
    return (SEV_ORDER[rowSev] || 0) >= (SEV_ORDER[minSev] || 0);
  }

  function applyFilters() {
    const minSev = getMinSev();
    const term = getSearchTerm();

    document.querySelectorAll('.tool-section').forEach(function(section) {
      const tool = section.dataset.tool;

      if (hiddenTools.has(tool)) {
        section.classList.add('hidden');
        return;
      }

      const rows = section.querySelectorAll('.finding-row');
      let visibleCount = 0;

      rows.forEach(function(row) {
        const sev = (row.dataset.severity || '').toLowerCase();
        const text = (row.dataset.text || '').toLowerCase();
        const ok = sevPasses(sev, minSev) && (term === '' || text.includes(term));
        row.classList.toggle('hidden', !ok);
        if (ok) visibleCount++;
      });

      section.classList.remove('hidden');

      const noRes = section.querySelector('.no-results');
      if (noRes) {
        noRes.style.display =
        rows.length > 0 && visibleCount === 0 ? 'block' : 'none';
      }
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
