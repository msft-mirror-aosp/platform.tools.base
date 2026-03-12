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

import com.android.tools.lint.client.api.TYPE_STRING
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
import org.jetbrains.uast.UExpression

/**
 * Reports calls to `PackageManager.checkPermission` when the second argument looks like a permission (because it evaluates to a String that
 * starts with "android.permission.").
 *
 * Why: because the second argument is supposed to be a package name, not a permission.
 */
class PackageManagerDetector : Detector(), SourceCodeScanner {

  override fun getApplicableMethodNames() = listOf("checkPermission")

  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
    if (!context.evaluator.methodMatches(method, "android.content.pm.PackageManager", allowInherit = true, TYPE_STRING, TYPE_STRING)) {
      return
    }

    val arguments = node.valueArguments
    if (arguments.size < 2) return

    val secondArg = arguments[1]

    fun UExpression.evaluatesToPermissionString(): Boolean {
      val value = this.evaluate() as? String ?: return false
      return value.startsWith("android.permission.")
    }

    if (secondArg.evaluatesToPermissionString()) {
      context.report(
        issue = ISSUE,
        scope = secondArg,
        location = context.getLocation(secondArg),
        message = "Argument is supposed to be a package name, not a permission",
      )
    }
  }

  companion object {
    @JvmField
    val ISSUE =
      Issue.create(
        id = "PackageManagerCheckPermission",
        briefDescription = "Using a permission instead of a package name",
        explanation = "The second argument to `PackageManager.checkPermission` is supposed to be a package name, not a permission.",
        category = Category.CORRECTNESS,
        priority = 5,
        severity = Severity.ERROR,
        implementation = Implementation(PackageManagerDetector::class.java, Scope.JAVA_FILE_SCOPE),
      )
  }
}
