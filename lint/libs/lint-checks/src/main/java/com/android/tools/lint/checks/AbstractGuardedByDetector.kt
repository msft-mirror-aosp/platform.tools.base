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

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.getUMethod
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getContainingUMethod
import org.jetbrains.uast.getParentOfType

/**
 * An abstract superclass for detectors that validate guarding annotations (e.g. {@code @GuardedBy}).
 *
 * @param guardedBySimpleName a name for the relevant guard annotation that will appear in the reports produced.
 * @param lockName the noun for locks that appears in the reports produced.
 * @param allowedLockTypes a list of qualified names of types that this check will allow to guard properties. A null list means there are no
 *   restrictions.
 * @param unsupportedLockTypes a list of unsupported lock types to report on when encountered.
 * @param unsupportedLockIssue the issue to use for unsupported lock findings.
 */
abstract class AbstractGuardedByDetector
internal constructor(
  protected val guardedBySimpleName: String,
  private val lockName: String,
  private val allowedLockTypes: List<String> = emptyList(),
  private val unsupportedLockTypes: List<UnsupportedLock> = emptyList(),
) : Detector(), SourceCodeScanner {

  abstract val issue: Issue

  override fun getApplicableUastTypes(): List<Class<out UElement>> =
    listOf(
      USimpleNameReferenceExpression::class.java,
      UCallExpression::class.java,
      UCallableReferenceExpression::class.java,
      UField::class.java,
      UMethod::class.java,
      UParameter::class.java,
    )

  protected fun JavaContext.report(
    scope: UElement,
    message: String,
    quickfixData: LintFix? = null,
  ) {
    report(issue, scope, getLocation(scope), message, quickfixData)
  }

  protected fun JavaContext.report(
    scope: UElement,
    message: String,
    fix: LintFix.Builder.() -> LintFix,
  ) {
    report(scope, message, fix(fix()))
  }

  /**
   * This method visits every decedent of the provided UMethod. To keep it performant, it should only be called after a guarded member has
   * already been found in the method.
   */
  protected abstract fun analyzeMethod(node: UMethod, context: JavaContext)

  /** Finds all of the guard annotations on a PsiModifierListOwner. */
  protected abstract fun PsiModifierListOwner.findGuardedByAnnotations(): List<PsiAnnotation>

  /** Returns whether the PsiModifierListOwner has any guard annotations. */
  protected abstract fun PsiModifierListOwner.hasGuardedByAnnotation(): Boolean

  private fun reportInvalidLockTypes(
    guardRef: GuardReference,
    varName: String,
    reportNode: UDeclaration,
    context: JavaContext,
  ) {
    if (allowedLockTypes.any { guardRef.lockType?.psiClass?.inheritsFrom(context, it) != true }) {
      context.report(
        reportNode,
        "The lock `$varName` should be of type ${allowedLockTypes.joinToString()}, but is not",
      )
    }
    for ((type, message) in unsupportedLockTypes) {
      // TODO: consider reporting as warnings, or support the currently unsupported lock types
      if (guardRef.lockType?.psiClass?.inheritsFrom(context, type) == true) {
        context.report(
          issue,
          context.getLocation(reportNode as UElement),
          message ?: "Checking `$guardedBySimpleName` locks of type $type is not currently supported",
        )
      }
    }
  }

  internal abstract fun createGuardRefFromString(
    string: String,
    clazz: PsiClass,
    annotatedMember: PsiModifierListOwner,
    context: JavaContext,
  ): GuardReference?

  override fun createUastHandler(context: JavaContext): UElementHandler? {
    return object : UElementHandler() {
      private val analyzedMethods: MutableSet<UMethod> = mutableSetOf()

      /**
       * Looks for references to members that are guarded by a lock. For performance, only once one is found do we visit the enclosing
       * method to check the lock is held.
       */
      override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression) {
        val hasGuardedByAnno =
          when (val resolvedNode = node.tryResolveHarder()) {
            is KtLightMethod -> {
              resolvedNode.hasGuardedByAnnotation() || resolvedNode.getAccessedField()?.hasGuardedByAnnotation() ?: return
            }
            is PsiField -> resolvedNode.hasGuardedByAnnotation()
            is PsiMethod -> resolvedNode.hasGuardedByAnnotation()
            // If a property is declared in a constructor's params, it will resolve to a
            // PsiParameter
            is PsiParameter -> node.getBackingFieldIfConstructorProperty()?.hasGuardedByAnnotation() ?: return
            else -> return
          }
        if (hasGuardedByAnno) {
          analyzeMethodIfUnvisited(node.getContainingUMethod() ?: return)
        }
      }

      /**
       * Looks for calls to methods that are guarded by a lock. For performance, only once one is found do we visit the enclosing method to
       * check the lock is held.
       */
      override fun visitCallExpression(node: UCallExpression) {
        val method = node.resolve() ?: return
        if (method.hasGuardedByAnnotation()) {
          analyzeMethodIfUnvisited(node.getContainingUMethod() ?: return)
        }
      }

      /** Looks for references to methods that are guarded by a lock and reports them as unsafe. */
      override fun visitCallableReferenceExpression(node: UCallableReferenceExpression) {
        val parentCall = node.getParentOfType<UCallExpression>(strict = true)
        if (AbstractGuardedByVisitor.isImmediateLambdaInvoker(parentCall?.resolve())) {
          return
        }
        val method = node.resolve() as? PsiMethod ?: return
        if (method.hasGuardedByAnnotation()) {
          context.report(
            node,
            "This member should be guarded by a $lockName; saving a reference to it is unsafe",
          )
        }
      }

      override fun visitField(node: UField) {
        validateGuardAnnotations(node)

        @Suppress("UElementAsPsi") if (!node.hasGuardedByAnnotation()) return
        val setter = node.getKtPropertySetter()?.getUMethod()
        if (setter != null && setter.uastBody == null) {
          analyzeMethodIfUnvisited(setter)
        }
        val getter = node.getKtPropertyGetter()?.getUMethod()
        if (getter != null && getter.uastBody == null) {
          analyzeMethodIfUnvisited(getter)
        }
      }

      override fun visitMethod(node: UMethod) = validateGuardAnnotations(node)

      @Suppress("UElementAsPsi")
      override fun visitParameter(node: UParameter) {
        // Accesses of properties defined in a classes' constructor's parameters in UAST don't give
        // any indication as to whether they are reads or writes, and simply resolve to the
        // `PsiParameter`s. Because of this, annotations on the getter or setter are not respected,
        // and we warn about them instead.
        if ((node.sourcePsi as? KtParameter)?.hasValOrVar() == true) {
          val containingClass = node.getContainingUClass()
          val getter = containingClass?.getGetterForParameterProperty(node)
          val setter = containingClass?.getSetterForParameterProperty(node)
          if ((getter?.hasGuardedByAnnotation() == true) || (setter?.hasGuardedByAnnotation() == true)) {
            context.report(
              node,
              "@$guardedBySimpleName is not allowed to be specifically on getters or " +
                "setters of constructor defined properties, but is on at least one of " +
                "${node.name}'s accessors",
            )
          }
        }
      }

      @Suppress("UElementAsPsi")
      private fun validateGuardAnnotations(node: UDeclaration) {
        val containingClass = node.getContainingUClass() ?: return

        val guardedByValues =
          node
            .findGuardedByAnnotations()
            .mapNotNull { it.value<String>("value") }
            .map { Pair(createGuardRefFromString(it, containingClass, node, context), it) }

        // getContainingClass skips companion objects
        val companionObjects = (containingClass.sourcePsi as? KtClass)?.getCompanionObjects()
        val nodeDirectlyInCompanionObject = companionObjects?.any { it.getDeclarations().contains(node.sourcePsi) } == true

        if (nodeDirectlyInCompanionObject) {
          for ((_, annoValue) in guardedByValues) {
            if (annoValue.endsWith(".Companion.this") || annoValue == "Companion.this" || annoValue == "this") {
              // Avoid @GuardedBy("this") and @GuardedBy("Foo.class") in Foo's companion object
              // meaning essentially the same thing, but not being equivalent.
              context.report(
                node,
                "$guardedBySimpleName annotations are not allowed to reference companion " +
                  "objects. Instead of '$annoValue', use '${containingClass.name}.class' to " +
                  "guard on the class reference.",
              ) {
                replace()
                  .text(annoValue)
                  .range(context.getLocation(node.findGuardedByAnnotations().single()))
                  .with("${containingClass.name}.class")
                  .build()
              }
            } else if (annoValue.endsWith(".this")) {
              context.report(
                node,
                "$guardedBySimpleName annotations inside companion objects are not allowed to " +
                  "reference enclosing `this` class instance, as it would allow multiple " +
                  "instances of the class to be used as locks for the same member. Instead of " +
                  "'$annoValue', use '${containingClass.name}.class' to guard on the class " +
                  "reference.",
              ) {
                replace()
                  .text("$annoValue")
                  .range(context.getLocation(node.findGuardedByAnnotations().single()))
                  .with("${containingClass.name}.class")
                  .build()
              }
            }
          }
        }

        guardedByValues.forEach {
          val lockRef = it.first
          if (lockRef != null) {
            reportInvalidLockTypes(lockRef, it.second, node, context)
          } else {
            context.report(node, "Could not resolve $lockName identifier `${it.second}`")
          }
        }

        guardedByValues
          .filter { it.first?.lockIsFinal() == false }
          .forEach {
            context.report(node, "The $lockName `${it.second}` should be final, but is not")
          }
      }

      private fun analyzeMethodIfUnvisited(node: UMethod) {
        if (node in analyzedMethods) return
        analyzedMethods.add(node)
        analyzeMethod(node, context)
      }
    }
  }

  /** A class for holding the information needed to report on unsupported lock types. */
  internal data class UnsupportedLock(val qualifiedType: String, val reportMessage: String? = null)

  /** An interface for references to guard annotations that is used to check their validity. */
  internal interface GuardReference {
    fun lockIsFinal(): Boolean

    val lockType: PsiType?
  }
}
