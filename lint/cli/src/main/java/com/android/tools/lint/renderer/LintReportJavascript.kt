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
        expandedIssues: new Set(),
        collapsedNodes: new Set(),
        sort: { by: 'severity', order: 'desc' }
    },
    elements: {},
    lintReport: null,
    counts: { total: 0, errors: 0, warnings: 0, info: 0, hints: 0 },

    init(lintReport) {
        this.lintReport = lintReport;
        this.calculateCounts();
        this.cacheDOMElements();
        this.populateHeaderInfo();
        this.bindEvents();
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

    bindEvents() {

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
        const issues = this.lintReport.issues || [];
        this.renderStats();
        this.renderContent(issues);
        this.renderAdditionalChecks();
        this.renderDisabledChecks();
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
            rowsHtml.push(`<tr class="issue-row" data-issue-id="${'$'}{this.escapeHTML(issue.id)}" data-index="${'$'}{index}" data-parent-id="${'$'}{(parentId)}">
                <td class="sticky-name ${'$'}{plClass}">${'$'}{this.escapeHTML(issue.id)}</td>
                <td class="${'$'}{severityClass}">${'$'}{this.escapeHTML(issue.severityDescription)}</td>
                <td>${'$'}{this.escapeHTML(issue.category)}</td>
                <td>${'$'}{issue.priority}</td>
                <td>${'$'}{this.escapeHTML(issue.message)}</td>
                <td title="${'$'}{issue.location ? (issue.location.file) : ''}">${'$'}{locationHtml}</td>
            </tr>`);
            if (isExpanded) {
                const codeSnippet = issue.sourceContext ? `<pre class="errorlines">${'$'}{issue.sourceContext}</pre>` : (issue.errorLine1 ? `<pre class="errorlines">${'$'}{this.escapeHTML(issue.errorLine1)}\n${'$'}{this.escapeHTML(issue.errorLine2 || '')}</pre>` : '');
                const autoFixedMsg = issue.wasAutoFixed ? '<div class="mt-4 text-green-600 font-medium">This issue was automatically fixed.</div>' : '';
                const urlsHtml = (issue.urls && issue.urls.length > 0)
                    ? `<br><strong>More info:</strong><ul class="more-info-list">${'$'}{issue.urls.map(url => `<li><a href="${'$'}{this.escapeHTML(url)}" class="text-blue-600 hover:underline">${'$'}{this.escapeHTML(url)}</a></li>`).join('')}</ul>`
                    : '';

                rowsHtml.push(`<tr class="explanation-row" data-parent-id="${'$'}{(parentId)}"><td colspan="6"><div class="explanation-content">
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
};

document.addEventListener('DOMContentLoaded', () => {
    if (typeof lintReport !== 'undefined') {
        LintReportApp.init(lintReport);
    } else {
        console.error("lintReport data not found. Ensure report-data.js is loaded.");
    }
});
"""
