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
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.UastLintUtils
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UBinaryExpressionWithType
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UNamedExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.tryResolve

/**
 * Detector that flags integer confusion between java.lang.Thread and android.os.Process
 * thread priority APIs.
 */
class ThreadPriorityDetector : Detector(), SourceCodeScanner {

  companion object Issues {
    private val IMPLEMENTATION =
      Implementation(ThreadPriorityDetector::class.java, Scope.JAVA_FILE_SCOPE)

    @JvmField
    val ISSUE =
      Issue.create(
        id = "ThreadPriorityConfusion",
        briefDescription = "Thread Priority Integer Confusion",
        explanation =
          """
            Different thread priority APIs use different integer scales.

            * `java.lang.Thread.setPriority(int)` expects values between 1 (MIN_PRIORITY) and 10 (MAX_PRIORITY). Higher values represent higher priority.
            * `android.os.Process.setThreadPriority(int)` expects Linux priorities between -20 (highest) and 19 (lowest). Lower values represent higher priority.

            Confusing these scales can lead to bugs. For example, passing `Thread.MAX_PRIORITY` (10) to `Process.setThreadPriority` sets the priority to `THREAD_PRIORITY_BACKGROUND` (slow), which is the opposite of intended. Passing `Process.THREAD_PRIORITY_BACKGROUND` (10) to `Thread.setPriority` sets it to MAX priority. Passing negative values to `Thread.setPriority` causes a runtime exception.
          """.trimIndent(),
        category = Category.CORRECTNESS,
        priority = 6,
        severity = Severity.ERROR,
        androidSpecific = true,
        implementation = IMPLEMENTATION
      )
  }

  override fun getApplicableMethodNames(): List<String> =
    listOf("setPriority", "setThreadPriority")

  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
    val evaluator = context.evaluator

    if (method.name == "setPriority" && evaluator.isMemberInClass(method, "java.lang.Thread")) {
      if (node.valueArgumentCount == 1) {
        val arg = node.getArgumentForParameter(0)
        if (arg != null) {
          checkThreadSetPriority(context, arg)
        }
      }
    } else if (method.name == "setThreadPriority" && evaluator.isMemberInClass(method, "android.os.Process")) {
      val parameters = method.parameterList.parameters
      if (parameters.size == 1) {
        val arg = node.getArgumentForParameter(0)
        if (arg != null) {
          checkProcessSetThreadPriority(context, arg)
        }
      } else if (parameters.size == 2) {
        val arg = node.getArgumentForParameter(1)
        if (arg != null) {
          checkProcessSetThreadPriority(context, arg)
        }
      }
    }
  }

  override fun getApplicableConstructorTypes(): List<String> =
    listOf("android.os.HandlerThread")

  override fun visitConstructor(
    context: JavaContext,
    node: UCallExpression,
    constructor: PsiMethod
  ) {
    if (context.evaluator.isMemberInClass(constructor, "android.os.HandlerThread")) {
      if (node.valueArgumentCount == 2) {
        val arg = node.getArgumentForParameter(1)
        if (arg != null) {
          checkProcessSetThreadPriority(context, arg, "`HandlerThread` constructor")
        }
      }
    }
  }

  private fun skipContainers(expression: UExpression): UExpression {
    var current = expression
    while (true) {
      if (current is UNamedExpression) {
        current = current.expression
      } else if (current is UParenthesizedExpression) {
        current = current.expression
      } else if (current is UBinaryExpressionWithType) {
        current = current.operand
      } else {
        break
      }
    }
    return current
  }

  private fun resolveToSourceField(expression: UExpression): PsiField? {
    var current: UExpression? = expression
    var depth = 0
    while (current != null && depth < 20) {
      depth++
      val clean = skipContainers(current)
      val resolved = clean.tryResolve()
      if (resolved is PsiField) {
        return resolved
      }
      if (resolved is PsiLocalVariable) {
        current = UastLintUtils.findLastAssignment(resolved, clean)
      } else {
        break
      }
    }
    return null
  }

  private fun checkThreadSetPriority(context: JavaContext, argument: UExpression) {
    val field = resolveToSourceField(argument)
    if (field != null) {
      val qualifiedName = field.containingClass?.qualifiedName
      if (qualifiedName == "android.os.Process") {
        context.report(
          ISSUE,
          argument,
          context.getLocation(argument),
          "Passing `android.os.Process` priority constants to `Thread.setPriority()` is invalid"
        )
        return
      }
    }

    val cleanArg = skipContainers(argument)
    val value = ConstantEvaluator.evaluate(context, cleanArg)
    if (value is Int) {
      if (value !in 1..10) {
        context.report(
          ISSUE,
          argument,
          context.getLocation(argument),
          "Thread priority must be between 1 (`Thread.MIN_PRIORITY`) and 10 (`Thread.MAX_PRIORITY`); was $value"
        )
      }
    }
  }

  private fun checkProcessSetThreadPriority(
    context: JavaContext,
    argument: UExpression,
    targetDescription: String = "`Process.setThreadPriority()`"
  ) {
    val field = resolveToSourceField(argument)
    if (field != null) {
      val qualifiedName = field.containingClass?.qualifiedName
      if (qualifiedName == "java.lang.Thread") {
        context.report(
          ISSUE,
          argument,
          context.getLocation(argument),
          "Passing `java.lang.Thread` priority constants to $targetDescription is invalid"
        )
        return
      }
    }

    val cleanArg = skipContainers(argument)
    val value = ConstantEvaluator.evaluate(context, cleanArg)
    if (value is Int) {
      if (value !in -20..19) {
        context.report(
          ISSUE,
          argument,
          context.getLocation(argument),
          "Process thread priority must be between -20 (highest) and 19 (lowest); was $value"
        )
      }
    }
  }
}
