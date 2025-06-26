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

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

/**
 * Reports calls to InstantApps.showInstallPrompt.
 *
 * Context: Instant Apps support will be removed by Google Play in December 2025.
 *
 * `showInstallPrompt` is from `com.google.android.gms:play-services-instantapps`. This will be
 * marked as deprecated, but that relies on users upgrading the library version.
 *
 * There are already various other warnings (lint warning for the dependency, build warning, text in
 * Studio UIs), so this lint check just focuses on a specific API call. Note that not all instant
 * apps will actually call `showInstallPrompt`.
 */
class InstantAppDetector : Detector(), SourceCodeScanner {
  override fun getApplicableMethodNames() = listOf("showInstallPrompt")

  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
    if (
      !context.evaluator.isMemberInClass(method, "com.google.android.gms.instantapps.InstantApps")
    )
      return

    val annotation = context.evaluator.getAnnotation(method, "java.lang.Deprecated")
    if (annotation != null) {
      // Don't bother reporting an incident if the method is deprecated, as that will already show a
      // warning.
      return
    }

    context.report(
      ISSUE,
      node,
      context.getLocation(node),
      "Instant Apps support will be removed by Google Play in December 2025",
    )
  }

  companion object {
    private val IMPLEMENTATION =
      Implementation(InstantAppDetector::class.java, Scope.JAVA_FILE_SCOPE)

    @JvmField
    val ISSUE =
      Issue.create(
        id = "InstantAppCall",
        briefDescription = "Instant App call",
        explanation =
          """
          Instant Apps support will be removed by Google Play in December 2025. \
          Publishing and all Google Play Instant APIs will no longer work. \
          Tooling support will be removed in Android Studio Otter Feature Drop.
          """,
        category = Category.CORRECTNESS,
        priority = 5,
        severity = Severity.WARNING,
        implementation = IMPLEMENTATION,
        androidSpecific = true,
      )
  }
}
