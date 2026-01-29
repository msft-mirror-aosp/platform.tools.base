/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.lint.checks

import com.android.ide.common.r8.ConsumerRuleGlobalGuardian
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope.Companion.GRADLE_AND_TOML_SCOPE
import com.android.tools.lint.detector.api.Scope.Companion.GRADLE_SCOPE
import com.android.tools.lint.detector.api.Scope.Companion.TOML_SCOPE
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.model.LintModelAndroidLibrary
import com.android.tools.lint.model.LintModelMavenName
import java.io.File

/**
 * Looks for problems with transitive libraries consumer rules.
 *
 * Currently, only looks for global options which should not be included, and will be ignored in AGP 9.0+
 */
class ProguardConsumerRulesDetector : DependencyDetector<ProguardConsumerRulesDetector.GlobalOptionIssue>() {
  /** Represents a specific problematic global option at a specified file */
  class GlobalOptionIssue(
      val coordinates: LintModelMavenName,
      val keepRules: File,
      val requiresArgumentInConsumerRules: Boolean,
      val globalOption: String,
  ) : DependencyIssue() {
    override fun toLintIncident(): Incident {
      val location = Location.create(keepRules)
      val libraryName = "${keepRules.parentFile?.name}/${keepRules.name}"
      val errorSuffix = if (requiresArgumentInConsumerRules) " without an argument" else ""
      val message =
          "The consumer keep rules at `$libraryName` (from `$coordinates`) contains" +
              " a global option which should not be specified in library consumer rules$errorSuffix:" +
              " -$globalOption"
      val incident = Incident(GLOBAL_OPTION_ISSUE, location, message)
      return incident
    }
  }

  override fun isDependencyKnownSafe(group: String, artifact: String, version: String): Boolean {

    if (group == "com.google.android.fhir" && (artifact == "engine" || artifact == "data-capture")) {
      return false // Don't assume these dependencies are safe, see b/456246831
    }

    return group.startsWith("androidx.") ||
        group.startsWith("com.google.") ||
        group.startsWith("com.android.") ||
        group == "org.chromium.net" ||
        group.startsWith("com.crashlytics.")
  }

  override val dependencyIssueCache: HashMap<LintModelMavenName, List<DependencyIssue>>
    get() = _dependencyIssueCache

  override fun getIncidentsFromAndroidLibrary(library: LintModelAndroidLibrary): List<DependencyIssue> {
    if (!library.proguardRules.exists()) {
      return emptyList()
    }

    val errors = mutableListOf<DependencyIssue>()
    ConsumerRuleGlobalGuardian.validateConsumerRulesHasNoBannedGlobals(
        library.proguardRules,
        isDynamicFeature = false,
    ) { issue ->
      errors.add(
          GlobalOptionIssue(
              coordinates = library.resolvedCoordinates,
              keepRules = library.proguardRules,
              requiresArgumentInConsumerRules = issue.requiresArgumentInConsumerRules,
              globalOption = issue.globalOption,
          )
      )
    }
    return errors
  }

  companion object {
    private val _dependencyIssueCache = HashMap<LintModelMavenName, List<DependencyIssue>>()

    @JvmField
    val GLOBAL_OPTION_ISSUE =
        Issue.create(
            id = "GlobalOptionInConsumerRules",
            briefDescription = "Library has global options in consumer rules",
            explanation =
                """
          Libraries often include consumer keep rules to instruct R8 how to \
          optimize the library, especially if the library uses reflection. \
          These keep rules typically indicate to R8 of which classes, methods \
          and fields shouldn't be fully optimized so e.g. reflection continues \
          to work.

          Global keep rules can be used by an application to disable significant \
          optimization features, debug R8 behavior, suppress warnings, etc. \
          However, these global options should not be included in library consumer \
          rules -- those distributed with a library.

          Starting in Android Gradle Plugin version 9.0, all such rules are not \
          supported in library builds, and ignored by application builds if \
          included in a library. Libraries should not rely on these global \
          options in consumer rules to function, and should remove them.

          If you see a flagged library, first try to update to a newer version \
          that doesn't have the embedded global rule. If an updated version without \
          these global options is not available, contact the library vendor to ask \
          about their plans to remove global options, and thus support better R8 \
          configuration.

          As an application developer using Android Gradle Plugin 9.0, verify that \
          Android Gradle Plugin removing these global options isn't causing issues \
          in your application. If they are, you can add them temporarily to a local \
          keep rule file.
          """,
            category = Category.CORRECTNESS,
            priority = 2,
            severity = Severity.WARNING,
            implementation =
                Implementation(
                    ProguardConsumerRulesDetector::class.java,
                    GRADLE_AND_TOML_SCOPE,
                    GRADLE_SCOPE,
                    TOML_SCOPE,
                ),
            androidSpecific = true,
            moreInfo = "https://developer.android.com/topic/performance/app-optimization/choose-libraries-wisely",
        )
  }
}
