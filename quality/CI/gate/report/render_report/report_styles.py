
"""Report-specific CSS layered over the shared base styles."""

from quality.shared.reporting.report_styles import BASE_CSS


CSS = BASE_CSS + """
.tool-section.hidden {
    display: none;
}

.tool-header {
    padding: 14px 20px;
    background: #fafbfc;
    border-bottom: 1px solid #eee;
}

.tool-title {
    display: flex;
    align-items: center;
    gap: 12px;
    margin-bottom: 6px;
}

.tool-name {
    font-weight: bold;
    font-size: 1.05em;
    font-family: "SFMono-Regular", Consolas, monospace;
}

.tool-category {
    color: #7f8c8d;
    font-size: 0.85em;
}

.tool-meta {
    display: flex;
    align-items: center;
    margin-bottom: 8px;
    font-size: 0.9em;
}

.sev-counts {
    margin-top: 6px;
}

.tool-findings {
    padding: 16px 20px;
}

.tool-findings table {
    margin-bottom: 0;
}

.finding-row.hidden {
    display: none;
}

.no-results {
    padding: 12px;
    color: #7f8c8d;
    font-style: italic;
    display: none;
}

.section-header {
    background: #2c3e50;
    color: #fff;
    padding: 10px 20px;
    border-radius: 6px;
    margin: 24px 0 12px;
    font-weight: bold;
    font-size: 1.05em;
}

.section-header.advisory {
    background: #7f8c8d;
}

footer {
    margin-top: 32px;
    padding-top: 12px;
    border-top: 1px solid #dde;
    color: #7f8c8d;
    font-size: 0.85em;
}

a {
    color: #2980b9;
    text-decoration: none;
}

a:hover {
    text-decoration: underline;
}

a.tool-link {
    color: #2980b9;
    text-decoration: none;
    font-family: "SFMono-Regular", Consolas, monospace;
}

a.tool-link:hover {
    text-decoration: underline;
}

/* Filter bar */
.filter-bar {
    background: #fff;
    border-radius: 6px;
    padding: 14px 20px;
    margin-bottom: 16px;
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
    display: flex;
    align-items: center;
    gap: 16px;
    flex-wrap: wrap;
}

.filter-bar label {
    font-size: 0.85em;
    color: #7f8c8d;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    margin-right: 6px;
}

.filter-bar select {
    padding: 6px 10px;
    border: 1px solid #dde;
    border-radius: 4px;
    font-size: 0.9em;
    background: #f5f6fa;
    color: #2c3e50;
    cursor: pointer;
}

.filter-bar select:focus {
    outline: none;
    border-color: #2980b9;
}

.search-wrap {
    display: flex;
    align-items: center;
    gap: 6px;
    flex: 1;
    min-width: 200px;
}

.search-wrap input {
    flex: 1;
    padding: 6px 10px;
    border: 1px solid #dde;
    border-radius: 4px;
    font-size: 0.9em;
    background: #f5f6fa;
    color: #2c3e50;
}

.search-wrap input:focus {
    outline: none;
    border-color: #2980b9;
}

.btn-clear {
    padding: 6px 12px;
    background: #ecf0f1;
    border: 1px solid #dde;
    border-radius: 4px;
    font-size: 0.85em;
    cursor: pointer;
    color: #2c3e50;
}

.btn-clear:hover {
    background: #dde;
}

.btn-reset {
    padding: 6px 14px;
    background: #2c3e50;
    border: none;
    border-radius: 4px;
    font-size: 0.85em;
    cursor: pointer;
    color: #fff;
    margin-left: auto;
}

.btn-reset:hover {
    background: #34495e;
}

/* Toggle switch */
.toggle-wrap {
    display: flex;
    align-items: center;
    justify-content: center;
}

.toggle {
    position: relative;
    display: inline-block;
    width: 36px;
    height: 20px;
}

.toggle input {
    opacity: 0;
    width: 0;
    height: 0;
}

.slider {
    position: absolute;
    cursor: pointer;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    background: #ccc;
    border-radius: 20px;
    transition: 0.2s;
}

.slider:before {
    position: absolute;
    content: "";
    height: 14px;
    width: 14px;
    left: 3px;
    bottom: 3px;
    background: #fff;
    border-radius: 50%;
    transition: 0.2s;
}

input:checked + .slider {
    background: #27ae60;
}

input:checked + .slider:before {
    transform: translateX(16px);
}
"""
