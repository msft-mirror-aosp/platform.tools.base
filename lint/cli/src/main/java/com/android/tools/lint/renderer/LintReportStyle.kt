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

val STYLE_CSS =
  """
  /* --- Reset & Base Variables --- */
:root {
    --bg-body: #f8fafc;
    --bg-card: #ffffff;
    --bg-header: #ffffff;
    --bg-hover: #f3f4f6;
    --bg-selected: #eff6ff;
    --bg-subtle: #f9fafb;
    --bg-codeblock: #fafafa;
    --bg-green-light: #f0fdf4;
    --bg-red-light: #fef2f2;
    --bg-yellow-light: #fffbeb;
    --bg-tooltip: #2c2c2c;

    --text-main: #0f172a;
    --text-muted: #94a3b8;
    --text-subtle: #64748b;
    --text-dark: #374151;
    --text-darkest: #111827;
    --text-inverse: #ffffff;

    --text-gray-400: #9ca3af;
    --text-gray-500: #6b7280;
    --text-gray-600: #4b5563;
    --text-gray-700: #374151;
    --text-gray-800: #1f2937;
    --text-gray-900: #111827;

    --bg-blue-100: #dbeafe;
    --bg-blue-200: #bfdbfe;
    --border-blue-300: #93c5fd;
    --text-blue-600: #2563eb;
    --text-blue-700: #1d4ed8;
    --text-blue-800: #1e40af;

    --text-green-600: #16a34a;
    --text-red-600: #dc2626;
    --text-yellow-600: #ca8a04;

    --border-color: #e5e7eb;
    --border-light: #f1f5f9;
    --border-interactive: #d1d5db;
    --border-codeblock: #f0f0f0;
    --shadow-sm: 0 1px 2px 0 rgb(0 0 0 / 0.05);
    --shadow-md: 0 4px 6px -1px rgb(0 0 0 / 0.1);
    --shadow-inset: inset 0 1px 3px rgb(0 0 0 / 0.05);

    --font-mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
    --bg-caret: #fff9c4;
    --bg-lineno: #f5f5f5;
    --text-lineno: #999999;
    --border-lineno: #eeeeee;
}

* { box-sizing: border-box; margin: 0; padding: 0; }

/* Custom Scrollbar Styling */
* {
    scrollbar-width: thin;
    scrollbar-color: var(--border-color) transparent;
}

::-webkit-scrollbar {
    width: 8px;
    height: 8px;
}

::-webkit-scrollbar-track {
    background: transparent;
    margin: 0;
}

::-webkit-scrollbar-thumb {
    background-color: var(--border-color);
    border-radius: 4px;
    border: 2px solid transparent;
    background-clip: content-box;
}

::-webkit-scrollbar-thumb:hover {
    background-color: var(--border-interactive);
}

::-webkit-scrollbar-button {
    display: none;
    width: 0;
    height: 0;
}

::-webkit-scrollbar-corner {
    background: transparent;
}

/* --- Main Layout --- */
html, body {
    height: 100%;
    overflow: hidden;
}

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    -webkit-font-smoothing: antialiased;
    color: var(--text-main);
    background-color: var(--bg-body);
    line-height: 1.5;
    font-size: 0.875rem;
    display: flex;
    flex-direction: column;
}

a { text-decoration: none; color: inherit; }
button { background: none; border: none; cursor: pointer; font-family: inherit; }
input { font-family: inherit; }

.container-custom {
    flex: 1 1 auto;
    width: 100%;
    max-width: 1800px;
    margin: 0 auto;
    padding: 1rem;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    min-height: 0;
}
@media (min-width: 640px) { .container-custom { padding: 1.5rem; } }

#report-view > main {
    height: 100%;
    overflow-y: auto;
    padding-bottom: 2rem;
}

/* --- Header --- */
header {
    flex: 0 0 auto;
    z-index: 50;
    background: var(--bg-header);
    border-bottom: 1px solid var(--border-color);
    box-shadow: var(--shadow-sm);
    padding: 0;
}
.header-top-row {
    max-width: 1800px;
    margin: 0 auto;
    padding: 0.5rem 1.5rem;
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-bottom: 1px solid var(--border-light);
}
.header-left {
    display: flex;
    align-items: center;
}
.header-branding {
    display: flex;
    flex-direction: column;
    padding-right: 1rem;
    border-right: 1px solid var(--border-color);
}
.header-title { font-size: 1.25rem; font-weight: 700; color: var(--text-darkest); letter-spacing: -0.025em; }
.header-date { font-size: 0.8125rem; color: var(--text-subtle); margin-top: 0.125rem; }

.header-stats {
    display: flex;
    gap: 2rem;
    align-items: center;
}

.stat-item {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
}

.stat-label {
    font-size: 0.7rem;
    font-weight: 500;
    color: var(--text-muted);
    letter-spacing: 0.05em;
}

.stat-value {
    font-size: 0.95rem;
    font-weight: 700;
    color: var(--text-darkest);
    margin-top: 0.125rem;
}

.severity-fatal,
.severity-error { color: var(--text-red-600); font-weight: 600; }
.severity-warning { color: var(--text-yellow-600); font-weight: 600; }
.severity-info, .severity-information, .severity-informational { color: var(--text-blue-600); font-weight: 600; }
.severity-hint { color: var(--text-green-600); font-weight: 600; }

/** --- Table --- **/
.table-container {
    background: var(--bg-card);
    border: 1px solid var(--border-color);
    border-radius: 0.5rem;
    overflow-x: auto;
}
table { width: 100%; min-width: 800px; border-collapse: collapse; text-align: left; }
th {
    position: sticky;
    top: 0;
    z-index: 10;
    background: var(--bg-subtle);
    padding: 0.75rem 1rem;
    font-weight: 600;
    color: var(--text-gray-700);
    border-bottom: 1px solid var(--border-color);
    white-space: nowrap;
    border-right: 1px solid var(--border-color);
}
td {
    padding: 0.75rem 1rem;
    border-bottom: 1px solid var(--border-color);
    border-right: 1px solid var(--border-color);
}
th:last-child, td:last-child { border-right: none; }

.sticky-name {
    position: sticky; left: 0; z-index: 5;
    background-color: inherit;
    border-right: 1px solid var(--border-color);
}
thead th.sticky-name { z-index: 20; background: var(--bg-subtle); }

tr:hover { background-color: var(--bg-selected); }

.explanation-row { background-color: var(--bg-subtle); }
.explanation-content { padding: 1rem 2rem; white-space: normal; color: var(--text-gray-600); }

pre.errorlines {
    background-color: var(--bg-codeblock);
    font-family: var(--font-mono);
    border: 1px solid var(--border-codeblock);
    line-height: 1.2rem;
    font-size: 0.85rem;
    padding: 12px;
    margin-top: 10px;
    overflow: auto;
    white-space: pre;
    border-radius: 6px;
    box-shadow: var(--shadow-inset);
}

* --- List Checks Button --- */
.list-checks-btn {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    padding: 0.25rem 0.75rem;
    background-color: var(--bg-blue-100) !important;
    color: var(--text-blue-800) !important;
    border: 1px solid var(--border-blue-300) !important;
    border-radius: 9999px;
    font-size: 0.8125rem;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.2s;
    white-space: nowrap;
}
.list-checks-btn:hover { background-color: var(--bg-blue-200) !important; }

tr.issue-row:hover { cursor: pointer; }

* --- Utilities --- */
.flex { display: flex; }
.flex-1 { flex: 1 1 0%; }
.items-center { align-items: center; }
.gap-2 { gap: 0.5rem; }

.p-1 { padding: 0.25rem; }
.p-4 { padding: 1rem; }
.p-6 { padding: 1.5rem; }

.mt-8 { margin-top: 2rem; }
.mt-12 { margin-top: 3rem; }
.mb-4 { margin-bottom: 1rem; }
.mb-8 { margin-bottom: 2rem; }
.mb-12 { margin-bottom: 3rem; }
.ml-6 { margin-left: 1.5rem; }

.space-y-1 > * + * { margin-top: 0.25rem; }
.space-y-4 > * + * { margin-top: 1rem; }

.hidden { display: none !important; }

.text-xl { font-size: 1.25rem; line-height: 1.75rem; }
.text-sm { font-size: 0.875rem; }
.text-xs { font-size: 0.75rem; }
.text-center { text-align: center; }

.font-bold { font-weight: 700; }
.font-medium { font-weight: 500; }
.font-mono { font-family: var(--font-mono); }

.text-gray-400 { color: var(--text-gray-400); }
.text-gray-500 { color: var(--text-gray-500); }
.text-gray-600 { color: var(--text-gray-600); }
.text-gray-900 { color: var(--text-gray-900); }
.text-blue-600 { color: var(--text-blue-600); }
.text-red-600 { color: var(--text-red-600); }
.text-yellow-600 { color: var(--text-yellow-600); }
.text-green-600 { color: var(--text-green-600); }

.right-0 { right: 0; left: auto !important; }

.flex-start-gap-4 { display: flex; align-items: center; gap: 1rem; justify-content: flex-start; }
.flex-end-gap-3 { display: flex; align-items: center; gap: 0.75rem; justify-content: flex-end; }
.svg-full-size { width: 100%; height: 100%; }
.list-decimal { list-style-type: decimal; }

.caretline { background-color: var(--bg-caret); display: block; width: 100%; border-radius: 2px; }
.lineno { color: var(--text-lineno); background-color: var(--bg-lineno); padding: 0 8px; margin-right: 8px; border-right: 1px solid var(--border-lineno); user-select: none; }

.hover\:underline:hover { text-decoration: underline; }
.cursor-pointer { cursor: pointer; }

.pl-level-0 { padding-left: 1rem; }
.pl-level-1 { padding-left: 2rem; }
.pl-level-2 { padding-left: 3rem; }
.pl-level-3 { padding-left: 4rem; }
.pl-level-4 { padding-left: 5rem; }
.pl-level-5 { padding-left: 6rem; }
.pl-level-6 { padding-left: 7rem; }
.pl-level-7 { padding-left: 8rem; }
.pl-level-8 { padding-left: 9rem; }
"""
