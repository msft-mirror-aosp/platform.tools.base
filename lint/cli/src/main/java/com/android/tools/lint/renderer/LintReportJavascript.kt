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
        currentView: 'modules', // 'modules', 'packages', 'issues'
        density: 'comfy',
        searchQuery: '',
        expandedIssues: new Set(),
        expandedChecks: new Set(),
        collapsedNodes: new Set(),
        sort: { by: 'name', order: 'asc' },
        filters: { severities: [], categories: [], modules: [], packages: [], classes: [] },
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
            breadcrumbs: document.getElementById('breadcrumbs'),
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

        const allModules = [...new Set(issues.map(i => i.module || 'Unknown'))].sort();
        this.buildActionDropdown(this.elements.moduleFilterList, allModules.map(m => ({name: m, value: m})), this.state.filters.modules, () => {
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
                this.populateFilters();
                this.updateFilterButtons();
                this.render();
            });
        }

        if (this.elements.removeSeverityFilter) {
            this.elements.removeSeverityFilter.addEventListener('click', (e) => {
                e.stopPropagation();
                this.state.isSeverityAdded = false;
                this.state.filters.severities.length = 0;
                this.populateFilters();
                this.updateFilterButtons();
                this.render();
            });
        }

        if (this.elements.removeCategoryFilter) {
            this.elements.removeCategoryFilter.addEventListener('click', (e) => {
                e.stopPropagation();
                this.state.isCategoryAdded = false;
                this.state.filters.categories.length = 0;
                this.populateFilters();
                this.updateFilterButtons();
                this.render();
            });
        }

        if (this.elements.removeModuleFilter) {
            this.elements.removeModuleFilter.addEventListener('click', (e) => {
                e.stopPropagation();
                this.state.isModuleAdded = false;
                this.state.filters.modules.length = 0;
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
        this.renderBreadcrumbs();
        this.renderContent(issues);
        this.renderAdditionalChecks();
        this.renderDisabledChecks();
    },

    renderBreadcrumbs() {
        if (!this.elements.breadcrumbs) return;

        const levels = [];
        levels.push({ name: 'All Issues', action: () => {
            this.state.currentView = 'issues';
            this.state.filters.modules.length = 0;
            this.state.filters.packages.length = 0;
            this.state.filters.classes.length = 0;
            this.state.isModuleAdded = false;
            this.state.searchQuery = '';
            if (this.elements.searchInput) this.elements.searchInput.value = '';
            this.populateFilters();
        }});

        if (this.state.filters.modules.length === 1) {
            const modName = this.state.filters.modules[0] || "Unknown";
            levels.push({ name: modName, action: () => {
                this.state.currentView = 'packages';
                this.state.filters.packages.length = 0;
                this.state.filters.classes.length = 0;
                this.state.searchQuery = '';
                if (this.elements.searchInput) this.elements.searchInput.value = '';
            }});
        } else if (this.state.filters.modules.length > 1) {
            levels.push({ name: `${'$'}{this.state.filters.modules.length} Modules`, action: () => {
                this.state.currentView = 'issues';
                this.state.filters.packages.length = 0;
                this.state.filters.classes.length = 0;
            }});
        }

        if (this.state.filters.packages.length === 1) {
            const pkgName = this.state.filters.packages[0];
            levels.push({ name: pkgName, action: () => {
                this.state.currentView = 'classes';
                this.state.filters.classes.length = 0;
                this.state.searchQuery = '';
                if (this.elements.searchInput) this.elements.searchInput.value = '';
            }});
        } else if (this.state.filters.packages.length > 1) {
            levels.push({ name: `${'$'}{this.state.filters.packages.length} Packages`, action: () => {
                this.state.currentView = 'issues';
                this.state.filters.classes.length = 0;
            }});
        }

        if (this.state.filters.classes.length === 1) {
            const clsName = this.state.filters.classes[0];
            levels.push({ name: clsName, action: () => {
                this.state.currentView = 'issues';
                this.state.searchQuery = '';
                if (this.elements.searchInput) this.elements.searchInput.value = '';
            }});
        } else if (this.state.filters.classes.length > 1) {
            levels.push({ name: `${'$'}{this.state.filters.classes.length} Classes`, action: () => {
                this.state.currentView = 'issues';
            }});
        }

        if (this.state.currentView !== 'issues') {
            const viewLabels = { 'modules': 'Modules', 'packages': 'Packages', 'classes': 'Classes' };
            const currentLevelName = viewLabels[this.state.currentView];
            if (currentLevelName) {
                 levels.push({ name: currentLevelName, isLast: true });
            }
        }

        if (this.state.searchQuery) {
            levels.push({ name: `Search: ${'$'}{this.state.searchQuery}`, isLast: true });
        }

        // Render levels
        let html = '';
        levels.forEach((level, index) => {
            const isLast = index === levels.length - 1 || level.isLast;
            if (isLast) {
                html += `<span class="font-medium text-gray-900">${'$'}{this.escapeHTML(level.name)}</span>`;
            } else {
                html += `<span class="cursor-pointer hover:text-blue-600 breadcrumb-item" data-index="${'$'}{index}">${'$'}{this.escapeHTML(level.name)}</span>`;
                html += ` <span class="text-gray-400">/</span> `;
            }
        });

        this.elements.breadcrumbs.innerHTML = html;

        this.elements.breadcrumbs.querySelectorAll('.breadcrumb-item').forEach(item => {
            item.addEventListener('click', () => {
                const idx = parseInt(item.dataset.index);
                levels[idx].action();
                this.updateFilterButtons();
                this.render();
            });
        });
    },

    updateFilterButtons() {
        const issues = this.lintReport.issues || [];
        const allSeverities = [...new Set(issues.map(i => i.severityDescription))];
        const allCategories = [...new Set(issues.map(i => i.category))];
        const allModules = [...new Set(issues.map(i => i.module || 'Unknown'))];

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
                const mod = issue.module || "Unknown";
                if (!this.state.filters.modules.includes(mod)) return false;
            }
            if (this.state.filters.packages.length > 0) {
                const pkg = issue.packageName || "default";
                if (!this.state.filters.packages.includes(pkg)) return false;
            }
            if (this.state.filters.classes.length > 0) {
                const cls = issue.className || "Unknown";
                if (!this.state.filters.classes.includes(cls)) return false;
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
        if (this.state.currentView === 'issues') {
            this.renderFlatIssues(issues);
        } else {
            this.renderGroupedView(issues, this.state.currentView);
        }

        if (this.elements.groupByText) {
            const text = this.state.currentView;
            this.elements.groupByText.textContent = text.charAt(0).toUpperCase() + text.slice(1);
        }
    },

    renderFlatIssues(issues) {
        if (!this.elements.tableHeaders) return;
        this.elements.tableHeaders.innerHTML = `<tr>
            <th class="cursor-pointer" data-sort="id">ID</th>
            <th class="cursor-pointer" data-sort="severity">Severity</th>
            <th class="cursor-pointer" data-sort="category">Category</th>
            <th class="cursor-pointer" data-sort="module">Module</th>
            <th class="cursor-pointer" data-sort="priority">Priority</th>
            <th>Message</th>
            <th>Location</th>
        </tr>`;
        this.renderIssueRows(issues, this.elements.lintData);
    },

    getGroupedData(issues, groupByKey) {
        const groups = {};
        issues.forEach(issue => {
            let key = "Unknown";
            if (groupByKey === 'modules') key = issue.module || "Unknown";
            else if (groupByKey === 'packages') key = issue.packageName || "default";
            else if (groupByKey === 'classes') key = issue.className || "Unknown";

            if (!groups[key]) {
                groups[key] = { name: key, total: 0, errors: 0, warnings: 0, info: 0, hints: 0, issues: [] };
            }
            groups[key].total++;
            const sev = issue.severityDescription;
            if (sev === 'Fatal' || sev === 'Error') groups[key].errors++;
            else if (sev === 'Warning') groups[key].warnings++;
            else if (sev === 'Information' || sev === 'Informational' || sev === 'Info') groups[key].info++;
            else if (sev === 'Hint') groups[key].hints++;
            groups[key].issues.push(issue);
        });
        return Object.values(groups);
    },

    renderGroupedView(issues, groupByKey) {
        const grouped = this.getGroupedData(issues, groupByKey);
        const sorted = this.getSortedGroupedData(grouped);
        const typeLabels = { 'modules': 'Module', 'packages': 'Package', 'classes': 'Class' };
        const capitalizedType = typeLabels[groupByKey] || 'Item';

        this.elements.tableHeaders.innerHTML = `<tr>
            <th class="cursor-pointer" data-sort="name">Element</th>
            <th class="cursor-pointer" data-sort="total">Total</th>
            <th class="cursor-pointer" data-sort="errors">Errors</th>
            <th class="cursor-pointer" data-sort="warnings">Warnings</th>
            <th class="cursor-pointer" data-sort="info">Info</th>
            <th class="cursor-pointer" data-sort="hints">Hints</th>
        </tr>`;

        let rowsHtml = sorted.map(group => `
            <tr class="issue-row" data-group-type="${'$'}{groupByKey}" data-group-name="${'$'}{this.escapeHTML(group.name)}">
                <td class="font-medium">${'$'}{this.escapeHTML(group.name)}</td>
                <td>${'$'}{group.total}</td>
                <td class="${'$'}{group.errors > 0 ? 'text-red-600 font-bold' : 'text-gray-400'}">${'$'}{group.errors}</td>
                <td class="${'$'}{group.warnings > 0 ? 'text-yellow-600 font-bold' : 'text-gray-400'}">${'$'}{group.warnings}</td>
                <td class="${'$'}{group.info > 0 ? 'text-blue-600 font-bold' : 'text-gray-400'}">${'$'}{group.info}</td>
                <td class="${'$'}{group.hints > 0 ? 'text-green-600 font-bold' : 'text-gray-400'}">${'$'}{group.hints}</td>
            </tr>
        `).join('');

        this.elements.lintData.innerHTML = rowsHtml;

        // Add listeners for drill-down
        const rows = this.elements.lintData.querySelectorAll('.issue-row');
        rows.forEach(row => {
            row.addEventListener('click', (e) => {
                const type = row.dataset.groupType;
                const name = row.dataset.groupName;

                if (type === 'modules') {
                    this.state.filters.modules.length = 0;
                    this.state.filters.modules.push(name);
                    this.state.isModuleAdded = true;
                    this.state.currentView = 'packages';
                    this.populateFilters();
                } else if (type === 'packages') {
                    this.state.filters.packages.length = 0;
                    this.state.filters.packages.push(name);
                    this.state.currentView = 'classes';
                } else if (type === 'classes') {
                    this.state.filters.classes.length = 0;
                    this.state.filters.classes.push(name);
                    this.state.currentView = 'issues';
                }

                this.updateFilterButtons();
                this.render();
            });
        });
    },

    getSortedGroupedData(groups) {
        return [...groups].sort((a, b) => {
            const by = this.state.sort.by;
            let vA = a[by], vB = b[by];
            if (vA === undefined) vA = a.name; // Fallback to name if sorting by something non-existent for groups
            if (vB === undefined) vB = b.name;

            if (typeof vA === 'string') {
                return this.state.sort.order === 'asc' ? vA.localeCompare(vB) : vB.localeCompare(vA);
            }
            if (vA < vB) return this.state.sort.order === 'asc' ? -1 : 1;
            if (vA > vB) return this.state.sort.order === 'asc' ? 1 : -1;
            return 0;
        });
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
                <td class="${'$'}{plClass}">${'$'}{this.escapeHTML(issue.id)}</td>
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
                const quickfixMsg = (!issue.vendor && issue.hasAutoFix) ? '<div class="mt-4 text-gray-500 text-sm">Note: This issue has an associated quickfix operation in Android Studio and IntelliJ IDEA.</div>' : '';
                const urlsHtml = (issue.urls && issue.urls.length > 0)
                    ? `<div class="mt-4"><strong>More info:</strong><ul class="more-info-list">${'$'}{issue.urls.map(url => `<li><a href="${'$'}{this.escapeHTML(url)}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(url)}</a></li>`).join('')}</ul></div>`
                    : '';
                const secondaryHtml = (issue.secondaryLocations && issue.secondaryLocations.length > 0)
                    ? `<div class="mt-4"><strong>Additional locations:</strong><ul class="more-info-list">${'$'}{issue.secondaryLocations.map(loc => {
                        const locStr = `${'$'}{loc.file}${'$'}{loc.line ? ':' + loc.line : ''}${'$'}{loc.message ? ': ' + loc.message : ''}`;
                        return `<li>${'$'}{loc.url ? `<a href="${'$'}{loc.url}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(locStr)}</a>` : this.escapeHTML(locStr)}</li>`;
                    }).join('')}</ul></div>`
                    : '';
                const imagesHtml = (issue.images && issue.images.length > 0)
                    ? `<div class="mt-4 flex gap-4 overflow-x-auto pb-2">${'$'}{issue.images.map(url => `<div class="flex-shrink-0"><a href="${'$'}{this.escapeHTML(url)}" target="_blank"><img src="${'$'}{this.escapeHTML(url)}" class="h-32 object-contain border border-gray-300 rounded-md p-1 bg-gray-50 hover:border-blue-500 transition-all shadow-sm"></a></div>`).join('')}</div>`
                    : '';
                let vendorHtml = '';
                if (issue.vendor) {
                    vendorHtml = `<div class="vendor mt-4 text-sm text-gray-500">`;
                    if (issue.vendor.name) vendorHtml += `<strong>Vendor:</strong> ${'$'}{this.escapeHTML(issue.vendor.name)}<br>`;
                    if (issue.vendor.identifier) vendorHtml += `<strong>Identifier:</strong> ${'$'}{this.escapeHTML(issue.vendor.identifier)}<br>`;
                    if (issue.vendor.contact) {
                        const contact = issue.vendor.contact;
                        if (contact.startsWith('http://') || contact.startsWith('https://')) {
                             vendorHtml += `<strong>Contact:</strong> <a href="${'$'}{this.escapeHTML(contact)}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(contact)}</a><br>`;
                        } else {
                             vendorHtml += `<strong>Contact:</strong> ${'$'}{this.escapeHTML(contact)}<br>`;
                        }
                    }
                    if (issue.vendor.feedbackUrl) vendorHtml += `<strong>Feedback:</strong> <a href="${'$'}{this.escapeHTML(issue.vendor.feedbackUrl)}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(issue.vendor.feedbackUrl)}</a><br>`;
                    vendorHtml += `</div>`;
                }
                const suppressHtml = issue.suppressMessage ? `<div class="mt-4 text-sm text-gray-500">${'$'}{issue.suppressMessage}</div>` : '';
                rowsHtml.push(`<tr class="explanation-row" data-parent-id="${'$'}{parentId}"><td colspan="7"><div class="explanation-content">
                    <div class="mb-4"><strong>Summary:</strong> ${'$'}{issue.summary}</div>
                    <div class="mb-4">
                        <strong>Explanation:</strong>
                        <div class="mt-1">${'$'}{this.renderExplanation(issue.explanation)}</div>
                    </div>
                    ${'$'}{issue.options && issue.options.length > 0 ? `
                        <div class="mt-4">This check can be configured via the following options:</div>
                        <div class="options mt-2 ml-4">
                            ${'$'}{issue.options.map(opt => `
                                <div class="mb-2">${'$'}{opt.description}</div>
                                ${'$'}{opt.explanation ? `
                                    <div class="mb-2">To configure this option, use a <code>lint.xml</code> file in the project or source folder using an <code>&lt;option&gt;</code> block like the following:</div>
                                    <pre class="errorlines mb-4">${'$'}{opt.explanation}</pre>
                                ` : ''}
                            `).join('')}
                        </div>
                    ` : ''}
                    ${'$'}{urlsHtml}
                    ${'$'}{secondaryHtml}
                    ${'$'}{autoFixedMsg}
                    ${'$'}{quickfixMsg}
                    ${'$'}{imagesHtml}
                    ${'$'}{codeSnippet}
                    ${'$'}{suppressHtml}
                    ${'$'}{vendorHtml}
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
            let by = this.state.sort.by;
            if (by === 'name') by = 'id'; // Issues use 'id', Groups use 'name' (displayed as Element)
            let vA = a[by], vB = b[by];
            if (by === 'severity') { vA = order[a.severityDescription] || 0; vB = order[b.severityDescription] || 0; }
            if (vA === undefined) vA = '';
            if (vB === undefined) vB = '';
            if (typeof vA === 'string') {
                return this.state.sort.order === 'asc' ? vA.localeCompare(vB) : vB.localeCompare(vA);
            }
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
                if (c.reason) detailsHtml += `<strong>Disabled by:</strong> ${'$'}{this.escapeHTML(c.reason)}<br>`;
                if (c.explanation) {
                    detailsHtml += `<div class="mb-4">${'$'}{this.renderExplanation(c.explanation)}</div>`;
                }
                if (c.options && c.options.length > 0) {
                    detailsHtml += `<div class="mt-4">This check can be configured via the following options:</div><div class="options mt-2 ml-4">`;
                    c.options.forEach(opt => {
                        detailsHtml += `<div class="mb-2">${'$'}{opt.description}</div>`;
                        if (opt.explanation) {
                            detailsHtml += `<div class="mb-2">To configure this option, use a <code>lint.xml</code> file in the project or source folder using an <code>&lt;option&gt;</code> block like the following:</div>`;
                            detailsHtml += `<pre class="errorlines mb-4">${'$'}{opt.explanation}</pre>`;
                        }
                    });
                    detailsHtml += `</div>`;
                }
                if (c.urls && c.urls.length > 0) {
                    detailsHtml += `<div class="mt-4"><strong>More info:</strong><ul class="more-info-list">${'$'}{c.urls.map(url => `<li><a href="${'$'}{this.escapeHTML(url)}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(url)}</a></li>`).join('')}</ul></div>`;
                }
                if (c.vendor) {
                    if (c.vendor.name) detailsHtml += `<strong>Vendor:</strong> ${'$'}{this.escapeHTML(c.vendor.name)}<br>`;
                    if (c.vendor.identifier) detailsHtml += `<strong>Identifier:</strong> ${'$'}{this.escapeHTML(c.vendor.identifier)}<br>`;
                    if (c.vendor.contact) {
                        const contact = c.vendor.contact;
                        if (contact.startsWith('http://') || contact.startsWith('https://')) {
                            detailsHtml += `<strong>Contact:</strong> <a href="${'$'}{this.escapeHTML(contact)}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(contact)}</a><br>`;
                        } else {
                            detailsHtml += `<strong>Contact:</strong> ${'$'}{this.escapeHTML(contact)}<br>`;
                        }
                    }
                    if (c.vendor.feedbackUrl) detailsHtml += `<strong>Feedback:</strong> <a href="${'$'}{this.escapeHTML(c.vendor.feedbackUrl)}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(c.vendor.feedbackUrl)}</a><br>`;
                } else if (c.hasAutoFix) {
                    detailsHtml += `<div class="mt-4 text-gray-500 text-sm">Note: This issue has an associated quickfix operation in Android Studio and IntelliJ IDEA.</div>`;
                }
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

    renderExplanation(text) {
        if (!text) return '';
        return text.trim()
            .split(/(?:\s*<br\s*\/?>\s*){2,}|\n\n+/)
            .map(p => `<div>${'$'}{p.trim().replace(/\n/g, '<br>')}</div>`)
            .join('<div class="mt-2"></div>');
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

                checkbox.addEventListener("change", (e) => {
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
