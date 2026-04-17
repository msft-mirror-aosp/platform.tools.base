/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.lint.renderer

val LINTSCRIPT_JS =
  """
/**
 * Main application object for the Lint Report.
 */
const LintReportApp = {
    state: {
        viewMode: 'flat', // 'flat' or 'tree'
        currentView: 'issues', // 'packages', 'issues'
        density: 'comfy',
        expandedIssues: new Set(),
        collapsedNodes: new Set(),
        sort: { by: 'severity', order: 'desc' },
        filters: { severities: [] },
        isSeverityAdded: false
    },
    elements: {},
    lintReport: null,
    counts: { total: 0, errors: 0, warnings: 0, info: 0, hints: 0 },

    init(lintReport) {
        this.lintReport = lintReport;
        this.calculateCounts();
        this.cacheDOMElements();
        this.populateHeaderInfo();
        this.populateFilters();
        this.bindEvents();
        this.updateFilterButtons();
        this.render();
    },

    calculateCounts() {
        const issues = this.lintReport.issues || [];
        this.counts = { total: issues.length, errors: 0, warnings: 0, info: 0, hints: 0 };
        issues.forEach(i => {
            const sev = i.severityDescription;
            if (sev === 'Fatal' || sev === 'Error') this.counts.errors++;
            else if (sev === 'Warning') this.counts.warnings++;
            else if (sev === 'Information' || sev === 'Informational' || sev === 'Info') this.counts.info++;
            else if (sev === 'Hint') this.counts.hints++;
        });
    },

    cacheDOMElements() {
        this.elements = {
            projectName: document.getElementById('project-name'),
            reportDate: document.getElementById('report-date'),
            totalIssues: document.getElementById('total-issues'),
            totalErrors: document.getElementById('total-errors'),
            totalWarnings: document.getElementById('total-warnings'),
            totalInfo: document.getElementById('total-info'),
            totalHints: document.getElementById('total-hints'),

            viewSegments: document.getElementById('view-segments'),
            densitySegments: document.getElementById('density-segments'),

            addFilterBtn: document.getElementById('add-filter-btn'),
            addFilterDropdown: document.getElementById('add-filter-dropdown'),
            addFilterList: document.getElementById('add-filter-list'),
            sevChipContainer: document.getElementById('sev-chip-container'),
            severityFilterBtn: document.getElementById('sev-filter-btn'),
            severityFilterText: document.getElementById('sev-filter-text'),
            severityFilterDropdown: document.getElementById('sev-dropdown'),
            severityFilterList: document.getElementById('sev-filter-list'),
            removeSeverityFilter: document.getElementById('remove-severity-filter'),

            mainTable: document.getElementById('main-table'),
            tableHeaders: document.getElementById('table-headers'),
            lintData: document.getElementById('lint-data'),
            issuesSection: document.getElementById('issues-section'),
        };
    },

    populateHeaderInfo() {
        if (this.elements.projectName) this.elements.projectName.textContent = this.lintReport.name;
        if (this.elements.reportDate) this.elements.reportDate.textContent = this.lintReport.timeStamp;
        const lv = document.getElementById('lint-version');
        if (lv) lv.textContent = this.lintReport.lintVersion || 'Lint';
        const fd = document.getElementById('footer-date');
        if (fd) fd.textContent = this.lintReport.timeStamp;
    },

    populateFilters() {
        const issues = this.lintReport.issues || [];
        const allSeverities = [...new Set(issues.map(i => i.severityDescription))].sort();
        this.buildActionDropdown(this.elements.severityFilterList, allSeverities.map(s => ({name: s, value: s})), this.state.filters.severities, () => {
            this.updateFilterButtons();
            this.render();
        });
    },

    bindEvents() {
        this.setupDensitySegments(this.elements, this.state, [
            this.elements.mainTable
        ]);

        const dropdownConfigs = [
            { btn: this.elements.addFilterBtn, dropdown: this.elements.addFilterDropdown },
            { btn: this.elements.severityFilterBtn, dropdown: this.elements.severityFilterDropdown }
        ];

        dropdownConfigs.forEach(({ btn, dropdown }) => {
            if (btn && dropdown) {
                btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    dropdownConfigs.forEach(other => {
                        if (other.dropdown && other.dropdown !== dropdown) {
                            other.dropdown.classList.add('hidden');
                        }
                    });
                    dropdown.classList.toggle('hidden');
                });
            }
        });

        document.addEventListener('click', (e) => {
            dropdownConfigs.forEach(({ btn, dropdown }) => {
                if (btn && dropdown && !btn.contains(e.target) && !dropdown.contains(e.target)) {
                    dropdown.classList.add('hidden');
                }
            });
        });

        if (this.elements.addFilterList) {
            this.elements.addFilterList.addEventListener('click', (e) => {
                const target = e.target.closest('.dropdown-item');
                if (!target) return;
                const type = target.dataset.filterType;
                if (type === 'severity') {
                    this.state.isSeverityAdded = true;
                }
                this.elements.addFilterDropdown.classList.add('hidden');
                this.updateFilterButtons();
                this.render();
            });
        }

        if (this.elements.removeSeverityFilter) {
            this.elements.removeSeverityFilter.addEventListener('click', (e) => {
                e.stopPropagation();
                this.state.isSeverityAdded = false;
                this.state.filters.severities = [];
                this.populateFilters();
                this.updateFilterButtons();
                this.render();
            });
        }

        if (this.elements.tableHeaders) {
            this.elements.tableHeaders.addEventListener('click', (e) => {
                const th = e.target.closest('[data-sort]');
                if (!th) return;
                const column = th.dataset.sort;
                if (this.state.sort.by === column) {
                    this.state.sort.order = this.state.sort.order === 'asc' ? 'desc' : 'asc';
                } else {
                    this.state.sort.by = column;
                    this.state.sort.order = 'asc';
                }
                this.render();
            });
        }

        if (this.elements.lintData) {
            this.elements.lintData.addEventListener('click', (e) => {
                const row = e.target.closest('.issue-row');
                if (row) {
                    const key = `${'$'}{row.dataset.issueId}-${'$'}{row.dataset.index}`;
                    if (this.state.expandedIssues.has(key)) this.state.expandedIssues.delete(key);
                    else this.state.expandedIssues.add(key);
                    this.render();
                }
            });
        }

        const sacb = document.getElementById('show-additional-checks-btn-v2');
        if (sacb) {
            sacb.addEventListener('click', () => {
                const container = document.getElementById('additional-checks-container');
                const textSpan = document.getElementById('show-additional-checks-text-v2');
                if (container && textSpan) {
                    container.classList.toggle('hidden');
                    textSpan.textContent = container.classList.contains('hidden') ? 'List Issues' : 'Hide Issues';
                }
            });
        }

        const sdcb = document.getElementById('show-disabled-checks-btn-v2');
        if (sdcb) {
            sdcb.addEventListener('click', () => {
                const container = document.getElementById('disabled-checks-container');
                const textSpan = document.getElementById('show-disabled-checks-text-v2');
                if (container && textSpan) {
                    container.classList.toggle('hidden');
                    textSpan.textContent = container.classList.contains('hidden') ? 'List Missing Issues' : 'Hide Issues';
                }
            });
        }
    },

    render() {
        const issues = this.getFilteredIssues(this.lintReport.issues || []);
        this.renderStats();
        this.renderContent(issues);
        this.renderAdditionalChecks();
        this.renderDisabledChecks();
    },

    updateFilterButtons() {
        const issues = this.lintReport.issues || [];
        const allSeverities = [...new Set(issues.map(i => i.severityDescription))];

        if (this.elements.sevChipContainer) {
            this.elements.sevChipContainer.classList.toggle('hidden', !this.state.isSeverityAdded);
        }

        if (this.elements.severityFilterText) {
            this.elements.severityFilterText.textContent = this.getFilterLabel('severity', this.state.filters.severities, allSeverities.length);
        }

        if (this.elements.addFilterBtn && this.elements.addFilterBtn.parentElement) {
            this.elements.addFilterBtn.parentElement.classList.toggle('hidden', this.state.isSeverityAdded);
        }
    },

    getFilteredIssues(issues) {
        return issues.filter(issue => {
            if (this.state.filters.severities.length > 0) {
                if (!this.state.filters.severities.includes(issue.severityDescription)) return false;
            }
            return true;
        });
    },

    renderStats() {
        if (this.elements.totalIssues) this.elements.totalIssues.textContent = this.counts.total;
        if (this.elements.totalErrors) this.elements.totalErrors.textContent = this.counts.errors;
        if (this.elements.totalWarnings) this.elements.totalWarnings.textContent = this.counts.warnings;
        if (this.elements.totalInfo) this.elements.totalInfo.textContent = this.counts.info;
        if (this.elements.totalHints) this.elements.totalHints.textContent = this.counts.hints;
    },

    renderContent(issues) {
        this.renderFlatIssues(issues);
    },

    renderFlatIssues(issues) {
        if (!this.elements.tableHeaders) return;
        this.elements.tableHeaders.innerHTML = `<tr>
            <th class="cursor-pointer sticky-name" data-sort="id">ID</th>
            <th class="cursor-pointer" data-sort="severity">Severity</th>
            <th class="cursor-pointer" data-sort="category">Category</th>
            <th class="cursor-pointer" data-sort="priority">Priority</th>
            <th>Message</th>
            <th>Location</th>
        </tr>`;
        this.renderIssueRows(issues, this.elements.lintData);
    },

    renderIssueRows(issues, container, level = 0, parentId = '') {
        const sorted = this.getSortedIssues(issues);
        let rowsHtml = [];
        sorted.forEach((issue, index) => {
            const key = `${'$'}{issue.id}-${'$'}{index}`;
            const isExpanded = this.state.expandedIssues.has(key);
            const severityClass = `severity-${'$'}{issue.severityDescription.toLowerCase()}`;
            const locationStr = issue.location ? `${'$'}{issue.location.file}${'$'}{issue.location.line ? ':' + issue.location.line : ''}` : 'Unknown';
            const locationHtml = (issue.location && issue.location.url)
                ? `<a href="${'$'}{issue.location.url}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(locationStr)}</a>`
                : (this.escapeHTML(locationStr));
            const plClass = `pl-level-${'$'}{Math.min(level, 8)}`;
            rowsHtml.push(`<tr class="issue-row" data-issue-id="${'$'}{this.escapeHTML(issue.id)}" data-index="${'$'}{index}" data-parent-id="${'$'}{parentId}">
                <td class="sticky-name ${'$'}{plClass}">${'$'}{this.escapeHTML(issue.id)}</td>
                <td class="${'$'}{severityClass}">${'$'}{this.escapeHTML(issue.severityDescription)}</td>
                <td>${'$'}{this.escapeHTML(issue.category)}</td>
                <td>${'$'}{issue.priority}</td>
                <td>${'$'}{this.escapeHTML(issue.message)}</td>
                <td title="${'$'}{issue.location ? issue.location.file : ''}">${'$'}{locationHtml}</td>
            </tr>`);
            if (isExpanded) {
                const codeSnippet = issue.sourceContext ? `<pre class="errorlines">${'$'}{issue.sourceContext}</pre>` : (issue.errorLine1 ? `<pre class="errorlines">${'$'}{this.escapeHTML(issue.errorLine1)}\n${'$'}{this.escapeHTML(issue.errorLine2 || '')}</pre>` : '');
                const autoFixedMsg = issue.wasAutoFixed ? '<div class="mt-4 text-green-600 font-medium">This issue was automatically fixed.</div>' : '';
                const urlsHtml = (issue.urls && issue.urls.length > 0)
                    ? `<br><strong>More info:</strong><ul class="more-info-list">${'$'}{issue.urls.map(url => `<li><a href="${'$'}{this.escapeHTML(url)}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(url)}</a></li>`).join('')}</ul>`
                    : '';

                rowsHtml.push(`<tr class="explanation-row" data-parent-id="${'$'}{parentId}"><td colspan="6"><div class="explanation-content">
                    <strong>Summary:</strong> ${'$'}{issue.summary}<br><br>
                    <strong>Explanation:</strong><br>${'$'}{issue.explanation.replace(/\n/g, '<br>')}
                    ${'$'}{urlsHtml}
                    ${'$'}{autoFixedMsg}
                    ${'$'}{codeSnippet}
                </div></td></tr>`);
            }
        });
        if (container) {
            container.innerHTML = rowsHtml.join('');
        }
        return rowsHtml.join('');
    },

    getSortedIssues(issues) {
        const order = { 'Fatal': 5, 'Error': 4, 'Warning': 3, 'Information': 2, 'Informational': 2, 'Hint': 1 };
        return [...issues].sort((a, b) => {
            const by = this.state.sort.by;
            let vA = a[by], vB = b[by];
            if (by === 'severity') { vA = order[a.severityDescription] || 0; vB = order[b.severityDescription] || 0; }
            if (vA < vB) return this.state.sort.order === 'asc' ? -1 : 1;
            if (vA > vB) return this.state.sort.order === 'asc' ? 1 : -1;
            return 0;
        });
    },

    renderAdditionalChecks() {
        const checks = this.lintReport.additionalChecks || [];
        const container = document.getElementById('ExtraIssues');
        if (container) {
            container.classList.toggle('hidden', checks.length === 0);
            const data = document.getElementById('additional-checks-data');
            if (data) data.innerHTML = checks.map(c => `<tr><td class="font-mono text-xs">${'$'}{this.escapeHTML(c.id)}</td><td>${'$'}{c.summary}${'$'}{c.vendor ? ` (${'$'}{this.escapeHTML(c.vendor)})` : ''}</td></tr>`).join('');
        }
    },

    renderDisabledChecks() {
        const checks = this.lintReport.disabledChecks || [];
        const container = document.getElementById('MissingIssues');
        if (container) {
            container.classList.toggle('hidden', checks.length === 0);
            const data = document.getElementById('disabled-checks-data');
            if (data) data.innerHTML = checks.map(c => `<tr><td class="font-mono text-xs">${'$'}{this.escapeHTML(c.id)}</td><td>${'$'}{c.summary}${'$'}{c.reason ? ` (${'$'}{this.escapeHTML(c.reason)})` : ''}</td></tr>`).join('');
        }
    },

    escapeHTML(str) {
        if (!str) return "";
        return str.toString()
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    },

    setupDensitySegments(elements, state, tableElements = []) {
        if (!elements.densitySegments) return;
        elements.densitySegments.addEventListener('click', (e) => {
            const btn = e.target.closest('.segment-btn');
            if (!btn) return;
            elements.densitySegments.querySelectorAll('.segment-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            state.density = btn.dataset.value;
            const isCompact = state.density === 'compact';
            tableElements.forEach(table => {
                if (table) table.classList.toggle('table-compact', isCompact);
            });
        });
    },

    buildActionDropdown(container, options, selectedStateArr, onSelectionChange) {
        if (!container) return;
        container.innerHTML = "";
        container.style.padding = "0";
        container.style.overflow = "hidden";

        // Search Zone
        const searchContainer = document.createElement("div");
        searchContainer.className = "dropdown-search-zone";
        const searchInput = document.createElement("input");
        searchInput.type = "text";
        searchInput.className = "popover-search";
        searchInput.placeholder = "Search...";
        searchContainer.appendChild(searchInput);
        container.appendChild(searchContainer);

        // Action Zone (Select All / Clear)
        const actionZone = document.createElement("div");
        actionZone.className = "dropdown-action-zone";
        const selectAllBtn = document.createElement("button");
        selectAllBtn.className = "dropdown-action-btn";
        selectAllBtn.textContent = "Select All";
        const clearBtn = document.createElement("button");
        clearBtn.className = "dropdown-action-btn";
        clearBtn.textContent = "Clear";
        actionZone.appendChild(selectAllBtn);
        actionZone.appendChild(clearBtn);
        container.appendChild(actionZone);

        const listZone = document.createElement("div");
        listZone.className = "dropdown-scroll-zone";
        container.appendChild(listZone);

        const renderList = (term = "") => {
            listZone.innerHTML = "";
            const filtered = options.filter(o => o.name.toLowerCase().includes(term.toLowerCase()));
            if (filtered.length === 0) {
                listZone.innerHTML = "<div class=\"p-4 text-xs text-gray-400 text-center\">No options available</div>";
                return;
            }
            filtered.forEach(opt => {
                const isChecked = selectedStateArr.includes(opt.value);
                const item = document.createElement("label");
                item.className = "popover-item";
                const checkbox = document.createElement("input");
                checkbox.type = "checkbox";
                checkbox.className = "popover-checkbox";
                checkbox.checked = isChecked;
                item.appendChild(checkbox);

                item.addEventListener("change", (e) => {
                    e.stopPropagation();
                    if (checkbox.checked) {
                        if (!selectedStateArr.includes(opt.value)) selectedStateArr.push(opt.value);
                    } else {
                        const idx = selectedStateArr.indexOf(opt.value);
                        if (idx > -1) selectedStateArr.splice(idx, 1);
                    }
                    onSelectionChange();
                });

                const label = document.createElement("span");
                label.textContent = opt.name;
                item.appendChild(label);
                listZone.appendChild(item);
            });
        };

        renderList();

        searchInput.addEventListener("input", (e) => {
            renderList(e.target.value);
        });

        selectAllBtn.addEventListener("click", (e) => {
            e.stopPropagation();
            const term = searchInput.value.toLowerCase();
            options.forEach(o => {
                if (o.name.toLowerCase().includes(term) && !selectedStateArr.includes(o.value)) {
                    selectedStateArr.push(o.value);
                }
            });
            renderList(searchInput.value);
            onSelectionChange();
        });

        clearBtn.addEventListener("click", (e) => {
            e.stopPropagation();
            const term = searchInput.value.toLowerCase();
            options.forEach(o => {
                if (o.name.toLowerCase().includes(term)) {
                    const idx = selectedStateArr.indexOf(o.value);
                    if (idx > -1) selectedStateArr.splice(idx, 1);
                }
            });
            renderList(searchInput.value);
            onSelectionChange();
        });
    },

    getFilterLabel(prefix, selectedArr, totalCount) {
        const capitalizedPrefix = prefix.charAt(0).toUpperCase() + prefix.slice(1);
        if (selectedArr.length === 0 || selectedArr.length === totalCount) return `${'$'}{capitalizedPrefix}: All`;
        if (selectedArr.length === 1) return `${'$'}{capitalizedPrefix}: ${'$'}{selectedArr[0]}`;
        return `${'$'}{capitalizedPrefix}: ${'$'}{selectedArr.length} Selected`;
    }
};

document.addEventListener('DOMContentLoaded', () => {
    if (typeof lintReport !== 'undefined') {
        LintReportApp.init(lintReport);
    } else {
        console.error("lintReport data not found. Ensure report-data.js is loaded.");
    }
});
"""
