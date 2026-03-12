/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.lint.checks

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.ResourceXmlDetector
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlContext
import org.w3c.dom.Attr

/**
 * Detector that checks for relative class names in specific layout attributes (e.g., `app:layout_behavior` and `app:layoutManager`).
 *
 * At runtime, the Android framework resolves relative class names using the `applicationId`. If the project's codebase `namespace` differs
 * from the `applicationId`, the framework will fail to instantiate the class, resulting in a ClassNotFoundException. This detector enforces
 * the use of fully qualified class names when these two build properties diverge, and warns against their use otherwise to prevent
 * refactoring bugs.
 */
class RelativeClassAttributeDetector : ResourceXmlDetector() {

  override fun getApplicableAttributes(): Collection<String> {
    return listOf("layout_behavior", "layoutManager")
  }

  override fun visitAttribute(context: XmlContext, attribute: Attr) {
    val value = attribute.value

    if (!value.startsWith(".")) {
      return
    }

    val project = context.project
    val namespace = project.`package` ?: return
    val isLibrary = project.isLibrary
    val applicationId = project.applicationId

    val willCrashCurrently = isLibrary || (applicationId != null && applicationId != namespace)

    val fullyQualifiedClass = namespace + value

    val fix =
      LintFix.create().replace().name("Replace with fully qualified class name").text(value).with(fullyQualifiedClass).autoFix().build()

    if (willCrashCurrently) {
      val incident =
        Incident(
          ISSUE,
          attribute,
          context.getLocation(attribute),
          "Relative class name (`$value`) will resolve using applicationId at runtime and crash. Use the fully qualified name instead: `$fullyQualifiedClass`",
          fix,
        )
      context.report(incident)
    } else {
      // Act as a refactoring safeguard for apps where ID and namespace currently match
      val incident =
        Incident(
            ISSUE,
            attribute,
            context.getLocation(attribute),
            "Relative class name (`$value`) is fragile. If the `applicationId` changes via build flavors or refactoring, this will crash at runtime. Use the fully qualified name instead: `$fullyQualifiedClass`",
            fix,
          )
          .overrideSeverity(Severity.WARNING)

      context.report(incident)
    }
  }

  companion object {
    @JvmField
    val ISSUE =
      Issue.create(
        id = "RelativeClassResolution",
        briefDescription = "Relative class names resolve to applicationId, not namespace",
        explanation =
          """
          When using relative class names (starting with a dot) in attributes like
          `app:layout_behavior` or `app:layoutManager`, the Android framework resolves
          them using the `applicationId` at runtime, not the codebase `namespace`.

          In library modules, or in app modules where `applicationId` and `namespace` differ,
          this will cause a `ClassNotFoundException` and crash the app. Even if they currently match,
          using a relative name is a refactoring risk. Use a fully qualified class name instead.
          """
            .trimIndent(),
        category = Category.CORRECTNESS,
        priority = 8,
        severity = Severity.FATAL,
        implementation = Implementation(RelativeClassAttributeDetector::class.java, Scope.RESOURCE_FILE_SCOPE),
      )
  }
}
