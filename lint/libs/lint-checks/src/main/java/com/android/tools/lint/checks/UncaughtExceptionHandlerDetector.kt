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
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import java.util.EnumSet
import org.jetbrains.uast.UCallExpression

/**
 * Reports calls to `setDefaultUncaughtExceptionHandler` unless we see a call to
 * `getDefaultUncaughtExceptionHandler` (to get the existing handler) in the same module.
 *
 * A prototype version of this check also required seeing
 * `Thread.UncaughtExceptionHandler.uncaughtException` (to call the existing handler) to not report
 * a warning, and used partial analysis to allow the elements to appear in any module. However, it
 * seems unlikely for the `{get,set}DefaultUncaughtExceptionHandler` calls to occur in different
 * modules, and by avoiding partial results, we can report a warning in more cases, such as when the
 * user is not using checkDependencies or when the user is running Lint on just a library module
 * (without an app module).
 */
class UncaughtExceptionHandlerDetector : Detector(), SourceCodeScanner {

  private var seenGetCall = false
  private val incidents = mutableListOf<Incident>()

  override fun beforeCheckEachProject(context: Context) {
    // Probably redundant given afterCheckEachProject, but just to be safe:
    seenGetCall = false
    incidents.clear()
  }

  override fun afterCheckEachProject(context: Context) {
    for (incident in incidents) {
      incident.report()
    }
    seenGetCall = false
    incidents.clear()
  }

  override fun getApplicableMethodNames() =
    listOf("setDefaultUncaughtExceptionHandler", "getDefaultUncaughtExceptionHandler")

  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
    if (seenGetCall) return

    when (method.name) {
      "setDefaultUncaughtExceptionHandler" -> {
        if (
          context.evaluator.methodMatches(
            method,
            THREAD_CLASS,
            false,
            "java.lang.Thread.UncaughtExceptionHandler",
          )
        ) {
          incidents.add(
            Incident(context)
              .issue(ISSUE)
              .at(node)
              .message(
                "Must call `getDefaultUncaughtExceptionHandler()` to get the existing handler, " +
                  "and call `existingHandler.uncaughtException(thread, throwable)` from your new handler"
              )
          )
        }
      }
      "getDefaultUncaughtExceptionHandler" -> {
        seenGetCall = true
        incidents.clear()
      }
    }
  }

  companion object {
    private val IMPLEMENTATION =
      Implementation(UncaughtExceptionHandlerDetector::class.java, EnumSet.of(Scope.ALL_JAVA_FILES))

    @JvmField
    val ISSUE =
      Issue.create(
        id = "DefaultUncaughtExceptionDelegation",
        briefDescription = "Missing default uncaught exception handler delegation",
        explanation =
          """
          A default uncaught exception handler should usually call the existing (previously set) \
          default uncaught exception handler. \
          This is especially true on Android, which uses a default uncaught exception handler to handle crashes. \
          This lint check reports calls to `setDefaultUncaughtExceptionHandler` \
          unless we can also see a call to `getDefaultUncaughtExceptionHandler` (to get the existing handler) \
          in the same module. \
          Make sure you also call `existingHandler.uncaughtException(thread, throwable)` from your new handler.
          """,
        category = Category.CORRECTNESS,
        priority = 5,
        severity = Severity.WARNING,
        implementation = IMPLEMENTATION,
        androidSpecific = true,
      )

    private const val THREAD_CLASS = "java.lang.Thread"
  }
}
