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
package com.android.tools.lint.checks

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.GradleContext
import com.android.tools.lint.detector.api.GradleScanner
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity

class R8GradleConfigDetector : Detector(), GradleScanner {

  var minificationEnabledCookie: Any? = null
  var seenResourceShrinkingSet = false
  var isKts = false

  /** Property name matching, accounting for kts vs groovy property naming difference */
  private fun String.matchesBooleanPropertyName(context: GradleContext, expected: String): Boolean {
    require(!expected.startsWith("is"))
    require(!expected.first().isTitleCase())
    return if (context.ktsContext == null) {
      this == expected
    } else {
      // add is, and capitalize
      this == "is" + expected.replaceFirstChar { it.titlecase() }
    }
  }

  override fun checkDslPropertyAssignment(
    context: GradleContext,
    property: String,
    value: String,
    parent: String,
    parentParent: String?,
    propertyCookie: Any,
    valueCookie: Any,
    statementCookie: Any,
  ) {
    // Only apply to application plugin, since we really care more about `resource optimization at level of app
    if (context.project.isLibrary) return

    isKts = context.ktsContext != null

    if (property.matchesBooleanPropertyName(context, "shrinkResources")) {
      seenResourceShrinkingSet = true
      if (value == "false") {
        val shrinkPropertyName = if (isKts) "isShrinkResources" else "shrinkResources"
        val incident =
          Incident(
            ISSUE,
            valueCookie,
            context.getLocation(valueCookie),
            message = "Avoid setting $shrinkPropertyName = false",
            fix().replace().pattern("false").with("true").build(),
          )
        context.client.report(context, incident)
      }
    }

    if ((context.project.gradleModelVersion?.major ?: 10) < 10) {
      // Only perform the check for using default value if < AGP 10
      if (property.matchesBooleanPropertyName(context, "minifyEnabled")) {
        if (value == "true") {
          minificationEnabledCookie = statementCookie
        }
      }
    }
  }

  override fun afterCheckFile(context: Context) {
    if (minificationEnabledCookie != null && !seenResourceShrinkingSet) {
      val minifyEnabledLocation = context.getLocation(minificationEnabledCookie)
      val indentPrefix = " ".repeat(minifyEnabledLocation.start?.column ?: 0)

      val shrinkPropertyName = if (isKts) "isShrinkResources" else "shrinkResources"
      val incident =
        Incident(
          ISSUE,
          minificationEnabledCookie!!,
          context.getLocation(minificationEnabledCookie),
          message = "If enabling minification, also set $shrinkPropertyName = true",
          fix().replace().pattern("true").with("true\n$indentPrefix$shrinkPropertyName = true").build(),
        )
      context.client.report(context, incident)
    }
  }

  companion object {
    val ISSUE =
      Issue.create(
        id = "NotShrinkingResources",
        briefDescription = "Use resource shrinking to optimize your resources",
        explanation =
          "When minification / dex optimization is enabled, you should also enable resource shrinking, " +
            "since it enables considerable download and storage savings, and optimizes your app's resource table.",
        category = Category.PERFORMANCE,
        priority = 2,
        severity = Severity.ERROR,
        implementation = Implementation(R8GradleConfigDetector::class.java, Scope.GRADLE_SCOPE),
        moreInfo = "https://developer.android.com/topic/performance/app-optimization/enable-app-optimization",
        androidSpecific = true,
      )
  }
}
