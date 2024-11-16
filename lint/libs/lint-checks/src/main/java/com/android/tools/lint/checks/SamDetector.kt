/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.SdkConstants.CLASS_VIEW
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.findSelector
import com.android.tools.lint.detector.api.isJava
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.getContainingUMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.isNullLiteral
import org.jetbrains.uast.skipParenthesizedExprDown
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.tryResolve
import org.jetbrains.uast.util.isAssignment
import org.jetbrains.uast.util.isConstructorCall
import org.jetbrains.uast.visitor.AbstractUastVisitor

/** Looks for bugs around implicit SAM conversions. */
class SamDetector : Detector(), SourceCodeScanner {
  companion object Issues {
    /** Improperly handling implicit SAM instances. */
    @JvmField
    val ISSUE =
      Issue.create(
        id = "ImplicitSamInstance",
        briefDescription = "Implicit SAM Instances",
        explanation =
          """
                Kotlin's support for SAM (single accessor method) interfaces lets you pass \
                a lambda to the interface. This will create a new instance on the fly even \
                though there is no explicit constructor call. If you pass one of these \
                lambdas or method references into a method which (for example) stores or \
                compares the object identity, unexpected results may happen.
            """,
        category = Category.CORRECTNESS,
        priority = 6,
        severity = Severity.WARNING,
        androidSpecific = null,
        enabledByDefault = false,
        implementation = Implementation(SamDetector::class.java, Scope.JAVA_FILE_SCOPE),
      )

    private const val HANDLER_CLASS = "android.os.Handler"
    private const val DRAWABLE_CALLBACK_CLASS = "android.graphics.drawable.Drawable.Callback"
    private const val RUNNABLE_CLASS = "java.lang.Runnable"
  }

  override fun getApplicableUastTypes(): List<Class<out UElement>>? =
    listOf(ULambdaExpression::class.java, UCallableReferenceExpression::class.java)

  override fun getApplicableMethodNames(): List<String> {
    return listOf("removeCallbacks")
  }

  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
    val argument = node.valueArguments.firstOrNull()?.skipParenthesizedExprDown() ?: return
    if (argument is UCallableReferenceExpression) {
      // Already handled in the UElement handler below
      return
    }
    val argumentType = argument.getExpressionType() ?: return
    if (
      argumentType.canonicalText.startsWith("kotlin.jvm.functions.Function0") &&
        method.containingClass?.qualifiedName == HANDLER_CLASS
    ) {
      val location = context.getLocation(argument)

      val container = node.getParentOfType<UClass>()
      if (container?.sourcePsi != null) {
        val posts = findPosts(argument, container)
        var last = location
        for (post in posts) {
          val secondary = context.getLocation(post)
          last.withSecondary(
            secondary,
            "Different instance than the one for `removeCallbacks()` due to SAM conversion; wrap with a shared `Runnable`",
          )
          last = secondary
        }
      }

      val name = argument.sourcePsi?.text
      context.report(
        ISSUE,
        argument,
        location,
        "`$name` is an implicit SAM conversion, so the instance you are removing here will not match anything you posted. " +
          "To fix this, use for example `val runnable = Runnable { $name() }` and post and remove the `runnable` val instead.",
      )
    } else {
      val selector = argument.findSelector()
      if (selector is UCallExpression && selector.isConstructorCall()) {
        val location = context.getLocation(selector)
        context.report(
          ISSUE,
          selector,
          location,
          "This argument a new instance so `removeCallbacks` will not remove anything",
        )
      }
    }
  }

  private fun findPosts(postArgument: UExpression, container: UClass): List<UExpression> {
    val variable = postArgument.tryResolve() ?: return emptyList()
    val matches = mutableListOf<UExpression>()
    container.accept(
      object : AbstractUastVisitor() {
        override fun visitCallExpression(node: UCallExpression): Boolean {
          val resolved = node.resolve() ?: return super.visitCallExpression(node)
          if (
            resolved.name.startsWith("post") &&
              resolved.containingClass?.qualifiedName == HANDLER_CLASS
          ) {
            val posted = node.valueArguments.firstOrNull()?.skipParenthesizedExprDown()
            val postedVariable = posted?.tryResolve()
            @Suppress("LintImplPsiEquals")
            if (postedVariable == variable) {
              matches.add(posted)
            }
          }
          return super.visitCallExpression(node)
        }
      }
    )

    return matches
  }

  override fun createUastHandler(context: JavaContext): UElementHandler? {
    val psi = context.uastFile?.sourcePsi ?: return null
    if (isJava(psi.language)) {
      return null
    }
    return object : UElementHandler() {
      override fun visitLambdaExpression(node: ULambdaExpression) {
        val parent = node.uastParent ?: return
        if (parent is ULocalVariable) {
          val psiVar = parent.sourcePsi as? PsiLocalVariable ?: parent.psi ?: return
          checkCalls(context, node, psiVar)
        } else if (parent.isAssignment()) {
          val v = (parent as UBinaryExpression).leftOperand.tryResolve() ?: return
          val psiVar = v as? PsiLocalVariable ?: return
          checkCalls(context, node, psiVar)
        }
      }

      override fun visitCallableReferenceExpression(node: UCallableReferenceExpression) {
        val call = node.uastParent as? UCallExpression ?: return
        checkLambda(context, node, call, node)
      }
    }
  }

  private fun checkCalls(
    context: JavaContext,
    lambda: ULambdaExpression,
    variable: PsiLocalVariable,
  ) {
    val method = lambda.getContainingUMethod() ?: return
    method.accept(
      object : AbstractUastVisitor() {
        override fun visitCallExpression(node: UCallExpression): Boolean {
          for (argument in node.valueArguments) {
            if (argument is UReferenceExpression && argument.resolve() == variable) {
              checkLambda(context, lambda, node, argument)
            }
          }
          return super.visitCallExpression(node)
        }
      }
    )
  }

  private fun checkLambda(
    context: JavaContext,
    lambda: UExpression,
    call: UCallExpression,
    argument: UReferenceExpression,
  ) {
    val psiMethod = call.resolve() ?: return
    val evaluator = context.evaluator
    if (psiMethod is PsiCompiledElement) {
      // The various Runnable methods in Handler operate on Runnable instances
      // that are stored. Ditto for View and Drawable.Callback.
      val containingClass = psiMethod.containingClass
      if (
        evaluator.isMemberInClass(psiMethod, HANDLER_CLASS) ||
          evaluator.inheritsFrom(containingClass, CLASS_VIEW, false) ||
          evaluator.inheritsFrom(containingClass, "android.view.ViewTreeObserver", false) ||
          evaluator.inheritsFrom(containingClass, DRAWABLE_CALLBACK_CLASS, false)
      ) {
        // idea: only store if temporarily in a variable
        val map = evaluator.computeArgumentMapping(call, psiMethod)
        val psiParameter = map[lambda] ?: return
        val typeString = psiParameter.type.canonicalText
        if (typeString == RUNNABLE_CLASS) {
          reportError(context, lambda, typeString, argument)
        }
      }
      return
    }
    if (!isJava(psiMethod.language)) {
      return
    }

    val map = evaluator.computeArgumentMapping(call, psiMethod)
    val psiParameter = map[argument] ?: return
    val method = psiMethod.toUElement(UMethod::class.java) ?: return
    if (
      storesLambda(method, psiParameter) &&
        !context.driver.isSuppressed(context, ISSUE, method as UElement)
    ) {
      val typeString = psiParameter.type.canonicalText
      reportError(context, lambda, typeString, argument)
    }
  }

  private fun reportError(
    context: JavaContext,
    lambda: UExpression,
    type: String,
    argument: UReferenceExpression,
  ) {
    val location = context.getLocation(argument)
    val simpleType = type.substring(type.lastIndexOf('.') + 1)
    val range = context.getLocation(lambda)
    val fix =
      if (lambda is ULambdaExpression) {
        fix()
          .name("Explicitly create $simpleType instance")
          .replace()
          .beginning()
          .with("$simpleType ")
          .range(range)
          .build()
      } else {
        null
      }
    context.report(
      ISSUE,
      argument,
      location,
      "Implicit new `$simpleType` instance being passed to method which ends up " +
        "checking instance equality; this can lead to subtle bugs",
      fix,
    )
  }

  private fun storesLambda(method: UMethod, parameter: PsiParameter): Boolean {
    var storesLambda = false
    method.accept(
      object : AbstractUastVisitor() {
        override fun visitSimpleNameReferenceExpression(
          node: USimpleNameReferenceExpression
        ): Boolean {
          val resolved = node.resolve()
          if (resolved == parameter) {
            val parent = node.uastParent
            if (parent is UCallExpression) {
              // Decide if we're calling some method which is storing the new instance
              val methodName = parent.methodName
              if (
                methodName != null &&
                  (methodName.startsWith("add") ||
                    methodName.startsWith("put") ||
                    methodName.startsWith("set"))
              ) {
                storesLambda = true
              }
            } else if (parent is UBinaryExpression) {
              val kind = parent.operator
              if (
                (kind == UastBinaryOperator.IDENTITY_EQUALS ||
                  kind == UastBinaryOperator.IDENTITY_NOT_EQUALS) &&
                  !parent.rightOperand.isNullLiteral()
              ) {
                storesLambda = true
              } else if (kind == UastBinaryOperator.ASSIGN && parent.rightOperand == node) {
                val lhs = parent.leftOperand.tryResolve()
                if (lhs is PsiField) {
                  storesLambda = true
                }
              }
            }
            // One thing I can try is to let you ONLY invoke methods on these things,
            // to see what else I can surface
          }
          return super.visitSimpleNameReferenceExpression(node)
        }
      }
    )
    return storesLambda
  }
}
