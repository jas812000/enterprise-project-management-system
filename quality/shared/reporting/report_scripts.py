
"""Common JavaScript functions for report filtering and tool toggles."""

FILTER_HELPERS = """  function getMinSev() {
    return document.getElementById('sev-filter').value;
  }

  function getSearchTerm() {
    return document.getElementById('search-input').value.trim().toLowerCase();
  }
"""

TOGGLE_TOOL = """  function toggleTool(tool, visible) {
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
"""

# Shared filter implementation. CI hides empty sections; Local Scans keeps them visible.
APPLY_FILTERS = """  function applyFilters() {
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
        const matches = sevPasses(sev, minSev) && (term === '' || text.includes(term));
        row.classList.toggle('hidden', !matches);
        if (matches) visibleCount++;
      });

      const noRes = section.querySelector('.no-results');
      if (hideEmptySections) {
        // CI collapses sections when no findings pass the active filters.
        section.classList.toggle('hidden', rows.length > 0 && visibleCount === 0);
        if (noRes) noRes.style.display = rows.length > 0 && visibleCount === 0 ? 'block' : 'none';
      } else {
        // Local Scans keeps the section visible and displays a no-results message.
        section.classList.remove('hidden');
        if (noRes) noRes.style.display = rows.length > 0 && visibleCount === 0 ? 'block' : 'none';
      }
    });
  }
"""
