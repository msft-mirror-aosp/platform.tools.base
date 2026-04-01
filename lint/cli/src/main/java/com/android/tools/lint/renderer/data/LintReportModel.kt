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

package com.android.tools.lint.renderer.data

/** Root data class for the Lint report */
data class LintReport(
  /** The name of the report, e.g., "Lint Report for project-app". */
  val name: String,
  /** The timestamp when the report was generated. */
  val timeStamp: String,
  /** The list of issues found during the lint run. */
  val issues: List<LintIssue>,
  /** Total number of issues in the report. */
  val numberOfIssues: Int,
  /** The version of lint used to generate this report. */
  val lintVersion: String? = null,
  /** List of additional checks that were run. */
  val additionalChecks: List<LintCheck> = emptyList(),
  /** List of checks that were disabled. */
  val disabledChecks: List<LintCheck> = emptyList(),
  /** List of projects included in this report. */
  val projects: List<LintProject> = emptyList(),
)

/** Represents a single project in a multi-project report. */
data class LintProject(
  /** The name of the project. */
  val name: String,
  /** The relative path to the project root. */
  val relativePath: String,
  /** Number of errors in this project. */
  val errorCount: Int,
  /** Number of warnings in this project. */
  val warningCount: Int,
  /** Number of hints in this project. */
  val hintCount: Int = 0,
  /** Number of info-level issues in this project. */
  val infoCount: Int = 0,
)

/** Represents a single lint issue. */
data class LintIssue(
  /** The ID of the lint rule that triggered this issue. */
  val id: String,
  /** The severity of the issue (e.g., "Error", "Warning"). */
  val severityDescription: String,
  /** The message describing the issue. */
  val message: String,
  /** The category of the issue (e.g., "Correctness", "Performance"). */
  val category: String,
  /** The priority of the issue (1 to 10). */
  val priority: Int,
  /** A short summary of the issue. */
  val summary: String,
  /** A detailed explanation of the issue. */
  val explanation: String,
  /** The primary location of the issue. */
  val location: LintLocation?,
  /** Additional locations related to this issue. */
  val secondaryLocations: List<LintLocation> = emptyList(),
  /** The module where the issue was found. */
  val module: String? = null,
  /** URLs with more information about the issue. */
  val urls: List<String> = emptyList(),
  /** The first line of code snippet showing the error. */
  val errorLine1: String? = null,
  /** The second line of code snippet (usually the underline). */
  val errorLine2: String? = null,
  /** Variants where this issue was found. */
  val includedVariants: List<String> = emptyList(),
  /** Variants where this issue was NOT found. */
  val excludedVariants: List<String> = emptyList(),
  /** Additional source context for the issue. */
  val sourceContext: String? = null,
  /** Vendor who provided the check. */
  val vendor: String? = null,
  /** Package name where the issue occurred. */
  val packageName: String? = null,
  /** Class name where the issue occurred. */
  val className: String? = null,
  /** Whether the issue was automatically fixed. */
  val wasAutoFixed: Boolean = false,
)

/** Represents a lint check. */
data class LintCheck(
  /** The ID of the lint check. */
  val id: String,
  /** A summary of the check. */
  val summary: String,
  /** The category of the check. */
  val category: String? = null,
  /** The vendor who provided the check. */
  val vendor: String? = null,
  /** The reason for the check status (e.g., why it's disabled). */
  val reason: String? = null,
)

/** Location of a lint issue. */
data class LintLocation(
  /** The path to the file. */
  val file: String,
  /** The line number (1-based), if available. */
  val line: Int?,
  /** The column number (1-based), if available. */
  val column: Int?,
  /** An optional URL for the file or location. */
  val url: String? = null,
)
