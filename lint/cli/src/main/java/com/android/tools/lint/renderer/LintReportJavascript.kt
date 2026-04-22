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
        searchQuery: '',
        expandedIssues: new Set(),
        expandedChecks: new Set(),
        collapsedNodes: new Set(),
        sort: { by: 'severity', order: 'desc' },
        filters: { severities: [], categories: [], modules: [] },
        isSeverityAdded: false,
        isCategoryAdded: false,
        isModuleAdded: false
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
            searchRevealBtn: document.getElementById('search-reveal-btn'),
            searchWrapper: document.getElementById('search-wrapper'),
            searchInput: document.getElementById('search-input'),
            searchClearBtn: document.getElementById('search-clear-btn'),

            addFilterBtn: document.getElementById('add-filter-btn'),
            addFilterDropdown: document.getElementById('add-filter-dropdown'),
            addFilterList: document.getElementById('add-filter-list'),
            sevChipContainer: document.getElementById('sev-chip-container'),
            severityFilterBtn: document.getElementById('sev-filter-btn'),
            severityFilterText: document.getElementById('sev-filter-text'),
            severityFilterDropdown: document.getElementById('sev-dropdown'),
            severityFilterList: document.getElementById('sev-filter-list'),
            removeSeverityFilter: document.getElementById('remove-severity-filter'),

            catChipContainer: document.getElementById('cat-chip-container'),
            categoryFilterBtn: document.getElementById('cat-filter-btn'),
            categoryFilterText: document.getElementById('cat-filter-text'),
            categoryFilterDropdown: document.getElementById('cat-dropdown'),
            categoryFilterList: document.getElementById('cat-filter-list'),
            removeCategoryFilter: document.getElementById('remove-category-filter'),

            modChipContainer: document.getElementById('mod-chip-container'),
            moduleFilterBtn: document.getElementById('mod-filter-btn'),
            moduleFilterText: document.getElementById('mod-filter-text'),
            moduleFilterDropdown: document.getElementById('mod-dropdown'),
            moduleFilterList: document.getElementById('mod-filter-list'),
            removeModuleFilter: document.getElementById('remove-module-filter'),

            mainTable: document.getElementById('main-table'),
            tableHeaders: document.getElementById('table-headers'),
            lintData: document.getElementById('lint-data'),
            additionalChecksData: document.getElementById('additional-checks-data'),
            disabledChecksData: document.getElementById('disabled-checks-data'),
            issuesSection: document.getElementById('issues-section'),
            groupByBtn: document.getElementById('group-by-btn'),
            groupByText: document.getElementById('group-by-text'),
            groupByDropdown: document.getElementById('group-by-dropdown'),
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

        const allCategories = [...new Set(issues.map(i => i.category))].sort();
        this.buildActionDropdown(this.elements.categoryFilterList, allCategories.map(c => ({name: c, value: c})), this.state.filters.categories, () => {
            this.updateFilterButtons();
            this.render();
        });

        const allModules = [...new Set(issues.map(i => i.module))].sort();
        this.buildActionDropdown(this.elements.moduleFilterList, allModules.map(m => ({name: m || 'Unknown', value: m})), this.state.filters.modules, () => {
            this.updateFilterButtons();
            this.render();
        });
    },

    attachCheckRowToggleListener(element) {
        if (!element) return;
        element.addEventListener('click', (e) => {
            const row = e.target.closest('.issue-row');
            if (row) {
                const id = row.dataset.checkId;
                if (this.state.expandedChecks.has(id)) this.state.expandedChecks.delete(id);
                else this.state.expandedChecks.add(id);
                this.render();
            }
        });
    },

    bindEvents() {
        this.setupDensitySegments(this.elements, this.state, [
            this.elements.mainTable
        ]);

        this.attachCheckRowToggleListener(this.elements.additionalChecksData);
        this.attachCheckRowToggleListener(this.elements.disabledChecksData);

        const dropdownConfigs = [
            { btn: this.elements.addFilterBtn, dropdown: this.elements.addFilterDropdown },
            { btn: this.elements.severityFilterBtn, dropdown: this.elements.severityFilterDropdown },
            { btn: this.elements.categoryFilterBtn, dropdown: this.elements.categoryFilterDropdown },
            { btn: this.elements.moduleFilterBtn, dropdown: this.elements.moduleFilterDropdown },
            { btn: this.elements.groupByBtn, dropdown: this.elements.groupByDropdown }
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
                } else if (type === 'category') {
                    this.state.isCategoryAdded = true;
                } else if (type === 'module') {
                    this.state.isModuleAdded = true;
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

        if (this.elements.removeCategoryFilter) {
            this.elements.removeCategoryFilter.addEventListener('click', (e) => {
                e.stopPropagation();
                this.state.isCategoryAdded = false;
                this.state.filters.categories = [];
                this.populateFilters();
                this.updateFilterButtons();
                this.render();
            });
        }

        if (this.elements.removeModuleFilter) {
            this.elements.removeModuleFilter.addEventListener('click', (e) => {
                e.stopPropagation();
                this.state.isModuleAdded = false;
                this.state.filters.modules = [];
                this.populateFilters();
                this.updateFilterButtons();
                this.render();
            });
        }

        if (this.elements.searchRevealBtn) {
            this.elements.searchRevealBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.elements.searchRevealBtn.classList.add('hidden');
                this.elements.searchWrapper.classList.add('expanded');
                this.elements.searchInput.focus();
            });
        }

        if (this.elements.searchInput) {
            const debouncedRender = this.debounce(() => {
                this.render();
            }, 150);

            this.elements.searchInput.addEventListener('input', () => {
                const term = this.elements.searchInput.value.trim().toLowerCase();
                this.state.searchQuery = term;
                if (term.length > 0) {
                    this.elements.searchClearBtn.classList.remove('hidden');
                } else {
                    this.elements.searchClearBtn.classList.add('hidden');
                }
                debouncedRender();
            });
        }

        if (this.elements.searchClearBtn) {
            this.elements.searchClearBtn.addEventListener('click', () => {
                this.elements.searchInput.value = '';
                this.state.searchQuery = '';
                this.elements.searchClearBtn.classList.add('hidden');
                this.render();
                this.elements.searchInput.focus();
            });
        }

        if (this.elements.groupByDropdown) {
            this.elements.groupByDropdown.addEventListener('click', (e) => {
                const target = e.target.closest('.dropdown-item');
                if (!target) return;
                this.state.currentView = target.dataset.value;
                this.elements.groupByDropdown.classList.add('hidden');
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
        let issues = this.getFilteredIssues(this.lintReport.issues || []);
        if (this.state.searchQuery) {
            issues = issues.filter(i => this.matchesSearch(i, this.state.searchQuery));
        }
        this.renderStats();
        this.renderContent(issues);
        this.renderAdditionalChecks();
        this.renderDisabledChecks();
    },

    updateFilterButtons() {
        const issues = this.lintReport.issues || [];
        const allSeverities = [...new Set(issues.map(i => i.severityDescription))];
        const allCategories = [...new Set(issues.map(i => i.category))];
        const allModules = [...new Set(issues.map(i => i.module))];

        if (this.elements.sevChipContainer) {
            this.elements.sevChipContainer.classList.toggle('hidden', !this.state.isSeverityAdded);
        }

        if (this.elements.catChipContainer) {
            this.elements.catChipContainer.classList.toggle('hidden', !this.state.isCategoryAdded);
        }

        if (this.elements.modChipContainer) {
            this.elements.modChipContainer.classList.toggle('hidden', !this.state.isModuleAdded);
        }

        if (this.elements.severityFilterText) {
            this.elements.severityFilterText.textContent = this.getFilterLabel('severity', this.state.filters.severities, allSeverities.length);
        }

        if (this.elements.categoryFilterText) {
            this.elements.categoryFilterText.textContent = this.getFilterLabel('category', this.state.filters.categories, allCategories.length);
        }

        if (this.elements.moduleFilterText) {
            this.elements.moduleFilterText.textContent = this.getFilterLabel('module', this.state.filters.modules, allModules.length);
        }

        if (this.elements.addFilterList) {
            const items = this.elements.addFilterList.querySelectorAll('.dropdown-item');
            items.forEach(item => {
                const type = item.dataset.filterType;
                if (type === 'severity') item.classList.toggle('hidden', this.state.isSeverityAdded);
                if (type === 'category') item.classList.toggle('hidden', this.state.isCategoryAdded);
                if (type === 'module') item.classList.toggle('hidden', this.state.isModuleAdded);
            });
        }

        if (this.elements.addFilterBtn && this.elements.addFilterBtn.parentElement) {
            this.elements.addFilterBtn.parentElement.classList.toggle('hidden', this.state.isSeverityAdded && this.state.isCategoryAdded && this.state.isModuleAdded);
        }
    },

    getFilteredIssues(issues) {
        return issues.filter(issue => {
            if (this.state.filters.severities.length > 0) {
                if (!this.state.filters.severities.includes(issue.severityDescription)) return false;
            }
            if (this.state.filters.categories.length > 0) {
                if (!this.state.filters.categories.includes(issue.category)) return false;
            }
            if (this.state.filters.modules.length > 0) {
                if (!this.state.filters.modules.includes(issue.module)) return false;
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
        if (this.elements.groupByText) {
            const text = this.state.currentView;
            this.elements.groupByText.textContent = text.charAt(0).toUpperCase() + text.slice(1);
        }
    },

    renderFlatIssues(issues) {
        if (!this.elements.tableHeaders) return;
        this.elements.tableHeaders.innerHTML = `<tr>
            <th class="cursor-pointer sticky-name" data-sort="id">ID</th>
            <th class="cursor-pointer" data-sort="severity">Severity</th>
            <th class="cursor-pointer" data-sort="category">Category</th>
            <th class="cursor-pointer" data-sort="module">Module</th>
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
                <td>${'$'}{this.escapeHTML(issue.module)}</td>
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
                const imagesHtml = (issue.images && issue.images.length > 0)
                    ? `<div class="mt-4 flex gap-4 overflow-x-auto pb-2">${'$'}{issue.images.map(url => `<div class="flex-shrink-0"><a href="${'$'}{this.escapeHTML(url)}" target="_blank"><img src="${'$'}{this.escapeHTML(url)}" class="h-32 object-contain border border-gray-300 rounded-md p-1 bg-gray-50 hover:border-blue-500 transition-all shadow-sm"></a></div>`).join('')}</div>`
                    : '';

                rowsHtml.push(`<tr class="explanation-row" data-parent-id="${'$'}{parentId}"><td colspan="7"><div class="explanation-content">
                    <strong>Summary:</strong> ${'$'}{issue.summary}<br><br>
                    <strong>Explanation:</strong><br>${'$'}{issue.explanation.replace(/\n/g, '<br>')}
                    ${'$'}{urlsHtml}
                    ${'$'}{autoFixedMsg}
                    ${'$'}{imagesHtml}
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
            this.renderCheckRows(checks, this.elements.additionalChecksData);
        }
    },

    renderDisabledChecks() {
        const checks = this.lintReport.disabledChecks || [];
        const container = document.getElementById('MissingIssues');
        if (container) {
            container.classList.toggle('hidden', checks.length === 0);
            this.renderCheckRows(checks, this.elements.disabledChecksData);
        }
    },

    renderCheckRows(checks, container) {
        if (!container) return;
        let rowsHtml = [];
        checks.forEach(c => {
            const isExpanded = this.state.expandedChecks.has(c.id);
            rowsHtml.push(`<tr class="issue-row" data-check-id="${'$'}{this.escapeHTML(c.id)}">
                <td class="font-mono text-xs">${'$'}{this.escapeHTML(c.id)}</td>
                <td>${'$'}{this.escapeHTML(c.summary)}</td>
            </tr>`);

            if (isExpanded) {
                let detailsHtml = '<div class="explanation-content text-sm">';
                if (c.explanation) {
                    detailsHtml += `<strong>Explanation:</strong><br>${'$'}{c.explanation.replace(/\n/g, '<br>')}<br><br>`;
                }
                if (c.vendor) {
                    if (c.vendor.name) detailsHtml += `<strong>Vendor:</strong> ${'$'}{this.escapeHTML(c.vendor.name)}<br>`;
                    if (c.vendor.identifier) detailsHtml += `<strong>Identifier:</strong> ${'$'}{this.escapeHTML(c.vendor.identifier)}<br>`;
                    if (c.vendor.contact) {
                        const contact = c.vendor.contact;
                        if (contact.startsWith('http')) {
                            detailsHtml += `<strong>Contact:</strong> <a href="${'$'}{this.escapeHTML(contact)}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(contact)}</a><br>`;
                        } else {
                            detailsHtml += `<strong>Contact:</strong> ${'$'}{this.escapeHTML(contact)}<br>`;
                        }
                    }
                    if (c.vendor.feedbackUrl) detailsHtml += `<strong>Feedback:</strong> <a href="${'$'}{this.escapeHTML(c.vendor.feedbackUrl)}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(c.vendor.feedbackUrl)}</a><br>`;
                }
                if (c.reason) detailsHtml += `<strong>Reason:</strong> ${'$'}{this.escapeHTML(c.reason)}<br>`;
                detailsHtml += '</div>';

                rowsHtml.push(`<tr class="explanation-row"><td colspan="2">${'$'}{detailsHtml}</td></tr>`);
            }
        });
        container.innerHTML = rowsHtml.join('');
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

    matchesSearch(issue, query) {
        if (!issue || !query) return false;
        const searchableFields = ['id', 'category', 'module', 'summary', 'explanation', 'message', 'priority', 'severityDescription'];
        for (const field of searchableFields) {
            const val = issue[field];
            if (val != null && val.toString().toLowerCase().includes(query)) {
                return true;
            }
        }
        if (issue.location && issue.location.file && issue.location.file.toLowerCase().includes(query)) {
            return true;
        }
        return false;
    },

    debounce(func, wait) {
        let timeout;
        return function(...args) {
            const context = this;
            clearTimeout(timeout);
            timeout = setTimeout(() => func.apply(context, args), wait);
        };
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
