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

    --text-gray-500: #6b7280;
    --text-gray-600: #4b5563;
    --text-gray-700: #374151;
    --text-gray-800: #1f2937;
    --text-gray-900: #111827;

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

.caretline { background-color: var(--bg-caret); display: block; width: 100%; border-radius: 2px; }
.lineno { color: var(--text-lineno); background-color: var(--bg-lineno); padding: 0 8px; margin-right: 8px; border-right: 1px solid var(--border-lineno); user-select: none; }

.severity-fatal,
.severity-error { color: var(--text-red-600); font-weight: 600; }
.severity-warning { color: var(--text-yellow-600); font-weight: 600; }
.severity-info, .severity-information, .severity-informational { color: var(--text-blue-600); font-weight: 600; }
.severity-hint { color: var(--text-green-600); font-weight: 600; }

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
"""
