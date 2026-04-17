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

package com.android.tools.lint

import com.android.tools.lint.renderer.LINTSCRIPT_JS
import com.android.tools.lint.renderer.STYLE_CSS

internal fun getIndexHtml(reportData: String): String =
  """
<!--
 Copyright (C) 2026 The Android Open Source Project

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Lint Report</title>
<style>
$STYLE_CSS
</style>
</head>
<body>

<header>
<div class="header-top-row">
    <div class="header-left">
        <div class="header-branding">
            <h1 class="header-title" id="project-name">Lint Report</h1>
            <p class="header-date" id="report-date"></p>
        </div>
    </div>

    <div class="header-stats">
        <div class="stat-item">
            <span class="stat-label">Total Issues</span>
            <span class="stat-value" id="total-issues">0</span>
        </div>
        <div class="stat-item">
            <span class="stat-label">Errors</span>
            <span class="stat-value text-red-600" id="total-errors">0</span>
        </div>
        <div class="stat-item">
            <span class="stat-label">Warnings</span>
            <span class="stat-value text-yellow-600" id="total-warnings">0</span>
        </div>
        <div class="stat-item">
            <span class="stat-label">Info</span>
            <span class="stat-value text-blue-600" id="total-info">0</span>
        </div>
        <div class="stat-item">
            <span class="stat-label">Hints</span>
            <span class="stat-value text-green-600" id="total-hints">0</span>
        </div>
    </div>
</div>
<div class="header-toolbar">
    <div class="header-toolbar-inner">
        <div class="flex-1 flex-start-gap-4">
            <div class="filter-icon" title="Filter Options">
                <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M4 6h16M7 12h10M10 18h4" />
                </svg>
            </div>

            <div id="filter-chips-container" class="filter-chips-wrapper">
                <div id="sev-chip-container" class="hidden" style="position: relative;">
                    <div class="filter-chip">
                        <span id="sev-filter-btn" class="flex items-center cursor-pointer">
                            <span id="sev-filter-text">Severity: All</span>
                        </span>
                        <span class="chip-close" id="remove-severity-filter">
                            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                                <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                            </svg>
                        </span>
                    </div>
                    <div id="sev-dropdown" class="dropdown-menu hidden">
                        <div id="sev-filter-list" class="p-1 space-y-1"></div>
                    </div>
                </div>

                <div class="relative" id="add-filter-container">
                    <button id="add-filter-btn" class="add-filter-btn">
                        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4" />
                        </svg>
                        Add Filter
                    </button>
                    <div id="add-filter-dropdown" class="dropdown-menu hidden" style="min-width: 10rem">
                        <div id="add-filter-list">
                            <div class="dropdown-item" data-filter-type="severity">Severity</div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
        <div class="flex-1 flex-end-gap-3">
            <div class="search-container">
                <button id="search-reveal-btn" class="icon-btn" data-tooltip="Search Issues">
                    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                    </svg>
                </button>
                <div id="search-wrapper" class="search-input-wrapper">
                    <input type="text" id="search-input" placeholder="Search..." class="search-input">
                    <button id="search-clear-btn" class="search-clear-btn hidden">
                        <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                            <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
                        </svg>
                    </button>
                </div>
            </div>
            <div class="segmented-control" id="density-segments">
                <button data-value="comfy" class="segment-btn active" data-tooltip="Comfortable Density">
                    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" fill="none" viewBox="0 0 24 24"
                         stroke="currentColor" stroke-width="2">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M4 6h16M4 12h16M4 18h16" />
                    </svg>
                </button>
                <button data-value="compact" class="segment-btn" data-tooltip="Compact Density">
                    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" fill="none" viewBox="0 0 24 24"
                         stroke="currentColor" stroke-width="2">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M4 5h16M4 9.66h16M4 14.33h16M4 19h16" />
                    </svg>
                </button>
            </div>
        </div>
    </div>
</div>
</header>

<div id="report-view" class="container-custom">
<main>
    <section id="issues-section">
        <div class="table-container">
            <table id="main-table">
                <thead id="table-headers">
                    <tr>
                        <th class="cursor-pointer sticky-name" data-sort="id">ID</th>
                        <th class="cursor-pointer" data-sort="severity">Severity</th>
                        <th class="cursor-pointer" data-sort="category">Category</th>
                        <th class="cursor-pointer" data-sort="priority">Priority</th>
                        <th>Message</th>
                        <th>Location</th>
                    </tr>
                </thead>
                <tbody id="lint-data"></tbody>
            </table>
        </div>
    </section>

    <section id="ExtraIssues" class="mt-12 hidden">
        <h2 class="text-xl font-bold mb-4">Included Additional Checks</h2>
        <p class="mb-4 text-gray-600">
            This section lists all the extra checks run by lint, provided from libraries,
            build configuration and extra flags. This is included to help you verify
            whether a particular check is included in analysis when configuring builds.
            (Note that the list does not include the hundreds of built-in checks into lint,
            only additional ones.)
        </p>
        <button id="show-additional-checks-btn-v2" class="list-checks-btn mb-4">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M4 6h16M4 12h16M4 18h16" />
            </svg>
            <span id="show-additional-checks-text-v2">List Issues</span>
        </button>
        <div id="additional-checks-container" class="table-container hidden">
            <table>
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Summary</th>
                    </tr>
                </thead>
                <tbody id="additional-checks-data"></tbody>
            </table>
        </div>
    </section>

    <section id="MissingIssues" class="mt-12 hidden">
        <h2 class="text-xl font-bold mb-4">Disabled Checks</h2>
        <p class="mb-4 text-gray-600">
            One or more issues were not run by lint, either
            because the check is not enabled by default, or because
            it was disabled with a command line flag or via one or
            more <code>lint.xml</code> configuration files in the project directories.
        </p>
        <button id="show-disabled-checks-btn-v2" class="list-checks-btn mb-4">
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M4 6h16M4 12h16M4 18h16" />
            </svg>
            <span id="show-disabled-checks-text-v2">List Missing Issues</span>
        </button>
        <div id="disabled-checks-container" class="table-container hidden">
            <table>
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Summary</th>
                    </tr>
                </thead>
                <tbody id="disabled-checks-data"></tbody>
            </table>
        </div>
    </section>

    <section id="SuppressInfo" class="mt-12 mb-12">
        <h2 class="text-xl font-bold mb-4">Suppressing Warnings and Errors</h2>
        <div class="p-6 bg-white border border-gray-200 rounded-lg text-gray-700 space-y-4">
            <p>Lint errors can be suppressed in a variety of ways:</p>
            <ol class="list-decimal ml-6 space-y-2">
                <li>With a <code class="bg-gray-100 px-1 rounded">@SuppressLint</code> annotation in the Java code</li>
                <li>With a <code class="bg-gray-100 px-1 rounded">tools:ignore</code> attribute in the XML file</li>
                <li>With a <code class="bg-gray-100 px-1 rounded">//noinspection</code> comment in the source code</li>
                <li>With ignore flags specified in the <code class="bg-gray-100 px-1 rounded">build.gradle</code> file, as explained below</li>
                <li>With a <code class="bg-gray-100 px-1 rounded">lint.xml</code> configuration file in the project</li>
                <li>With a <code class="bg-gray-100 px-1 rounded">lint.xml</code> configuration file passed to lint via the <code class="bg-gray-100 px-1 rounded">--config</code> flag</li>
                <li>With the <code class="bg-gray-100 px-1 rounded">--ignore</code> flag passed to lint.</li>
            </ol>

            <p>To suppress a lint warning with an annotation, add a <code class="bg-gray-100 px-1 rounded">@SuppressLint("id")</code> annotation on the class, method or variable declaration closest to the warning instance you want to disable. The id can be one or more issue id's, such as <code class="bg-gray-100 px-1 rounded">"UnusedResources"</code> or <code class="bg-gray-100 px-1 rounded">{"UnusedResources", "UnusedIds"}</code>, or it can be <code class="bg-gray-100 px-1 rounded">"all"</code> to suppress all lint warnings in the given scope.</p>

            <p>To suppress a lint warning with a comment, add a <code class="bg-gray-100 px-1 rounded">//noinspection id</code> comment on the line before the statement with the error.</p>

            <p>To suppress a lint warning in an XML file, add a <code class="bg-gray-100 px-1 rounded">tools:ignore="id"</code> attribute on the element containing the error, or one of its surrounding elements. You also need to define the namespace for the tools prefix on the root element in your document, next to the <code class="bg-gray-100 px-1 rounded">xmlns:android</code> declaration:<br>
            <code class="bg-gray-100 px-1 rounded">xmlns:tools="http://schemas.android.com/tools"</code></p>

            <p>To suppress a lint warning in a <code class="bg-gray-100 px-1 rounded">build.gradle</code> file, add a section like this:</p>
            <pre class="errorlines">android {
    lintOptions {
        disable 'TypographyFractions','TypographyQuotes'
    }
}</pre>

            <p>Here we specify a comma separated list of issue id's after the disable command. You can also use <code class="bg-gray-100 px-1 rounded">warning</code> or <code class="bg-gray-100 px-1 rounded">error</code> instead of <code class="bg-gray-100 px-1 rounded">disable</code> to change the severity of issues.</p>

            <p>To suppress lint warnings with a configuration XML file, create a file named <code class="bg-gray-100 px-1 rounded">lint.xml</code> and place it at the root directory of the module in which it applies.</p>

            <p>The format of the <code class="bg-gray-100 px-1 rounded">lint.xml</code> file is something like the following:</p>
            <pre class="errorlines">&lt;?xml version="1.0" encoding="UTF-8"?&gt;
&lt;lint&gt;
&lt;!-- Ignore everything in the test source set --&gt;
&lt;issue id="all"&gt;
    &lt;ignore path="\*/test/\*" /&gt;
&lt;/issue&gt;

&lt;!-- Disable this given check in this project --&gt;
&lt;issue id="IconMissingDensityFolder" severity="ignore" /&gt;

&lt;!-- Ignore the ObsoleteLayoutParam issue in the given files --&gt;
&lt;issue id="ObsoleteLayoutParam"&gt;
    &lt;ignore path="res/layout/activation.xml" /&gt;
    &lt;ignore path="res/layout-xlarge/activation.xml" /&gt;
    &lt;ignore regexp="(foo|bar)\.java" /&gt;
&lt;/issue&gt;

&lt;!-- Ignore the UselessLeaf issue in the given file --&gt;
&lt;issue id="UselessLeaf"&gt;
    &lt;ignore path="res/layout/main.xml" /&gt;
&lt;/issue&gt;

&lt;!-- Change the severity of hardcoded strings to "error" --&gt;
&lt;issue id="HardcodedText" severity="error" /&gt;
&lt;/lint&gt;</pre>

            <p>To suppress lint checks from the command line, pass the <code class="bg-gray-100 px-1 rounded">--ignore</code> flag with a comma separated list of ids to be suppressed, such as:<br>
            <code class="bg-gray-100 px-1 rounded">$ lint --ignore UnusedResources,UselessLeaf /my/project/path</code></p>

            <p>For more information, see <a href="https://developer.android.com/studio/write/lint.html#config" class="text-blue-600 hover:underline">https://developer.android.com/studio/write/lint.html#config</a></p>
        </div>
    </section>
</main>
</div>

<footer class="mt-8 p-4 text-center text-gray-500 text-xs border-t border-gray-200">
Generated by <span id="lint-version"></span> at <span id="footer-date"></span>
</footer>

<script>
$reportData
$LINTSCRIPT_JS
</script>
</body>
</html>
"""
    .trimIndent()
