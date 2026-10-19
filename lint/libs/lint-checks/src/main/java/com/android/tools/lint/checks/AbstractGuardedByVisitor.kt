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

import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.asJava.demangleInternalName
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.elements.isGetter
import org.jetbrains.kotlin.asJava.elements.isSetter
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULabeledExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UTryExpression
import org.jetbrains.uast.getContainingUMethod
import org.jetbrains.uast.getQualifiedChain
import org.jetbrains.uast.getQualifiedParentOrThis
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * This visitor keeps track of the currently held locks. When it encounters an expression whose declaration is annotated with a guard
 * annotation, it makes sure the correct lock is held.
 *
 * @param T The type that refers to held locks.
 * @param S The type that refers to guard annotations. This should have a way to determine if it's guard annotation's criteria is fulfilled
 *   by an instance of {@code T}
 * @property context The context for the visitor.
 * @property lockSet The locks that are already held when this visitor starts traversing the tree.
 * @property issue The issue of the detector running this visitor.
 */
internal abstract class AbstractGuardedByVisitor<T, S, V : AbstractGuardedByVisitor<T, S, V>>(
  protected val context: JavaContext,
  protected val lockSet: MutableSet<T>,
  protected val issue: Issue,
  protected val locksAreReentrant: Boolean,
) : AbstractUastVisitor() {

  protected abstract fun createChild(context: JavaContext, lockSet: Set<T>, issue: Issue): V

  /** Keeps track of locks that need a try block after being locked and must be released in a finally block. */
  protected val unprotectedLockSet = mutableSetOf<T>()

  // Default accessors don't have bodies, so we need to handle them at the method level
  override fun visitMethod(node: UMethod): Boolean {
    val nodePsi = node.javaPsi
    if (node.uastBody == null && nodePsi is KtLightMethod) {
      val field = nodePsi.getAccessedField() ?: return false
      if (field.getGuardedByAnnos().isNotEmpty()) {
        reportUnheldLocks(node.uastAnchor ?: node, field, nodePsi)
      }
    }
    return false
  }

  override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
    val containingMethod by lazy(LazyThreadSafetyMode.NONE) { node.getContainingUMethod() }

    node.getQualifiedChain().forEach { chainElement ->
      val qualifiedElement = chainElement.getQualifiedParentOrThis()
      if (qualifiedElement !is UReferenceExpression) return@forEach

      when (val resolvedNode = qualifiedElement.tryResolveHarder()) {
        is KtLightMethod -> {
          val inAccessorMethod = (containingMethod?.javaPsi as? KtLightMethod)?.let { it.isGetter || it.isSetter } == true

          // In an accessor, `field` resolves to the same accessor. We want to look at the
          // backing field instead in this case.
          if (inAccessorMethod && resolvedNode.name == containingMethod?.name && chainElement.asSourceString() == "field") {
            reportUnheldLocks(qualifiedElement, resolvedNode.getAccessedField() ?: return false)
          } else {
            reportUnheldLocks(qualifiedElement, resolvedNode)
          }
        }
        is PsiField,
        is PsiMethod -> {
          reportUnheldLocks(qualifiedElement, resolvedNode as PsiModifierListOwner)
        }
        is PsiParameter -> {
          val backingField = qualifiedElement.getBackingFieldIfConstructorProperty()
          reportUnheldLocks(qualifiedElement, backingField ?: resolvedNode)
        }
        is PsiVariable -> reportUnheldLocks(qualifiedElement, resolvedNode)
      }
    }
    return false
  }

  override fun visitLambdaExpression(node: ULambdaExpression): Boolean {
    // We must assume no locks are held when entering a lambda we don't recognize
    node.body.accept(createChild(context, setOf(), issue))
    return true // don't enter children, we called accept on the child block
  }

  override fun visitCallableReferenceExpression(node: UCallableReferenceExpression): Boolean {
    val visitorForEscaped = createChild(context, setOf(), issue)
    node.qualifierExpression?.accept(visitorForEscaped)
    return true
  }

  private fun checkCallableReference(node: UCallableReferenceExpression) {
    node.qualifierExpression?.accept(this)
    val resolved = node.resolve() as? PsiModifierListOwner
    if (resolved != null) {
      reportUnheldLocks(node, resolved)
    }
  }

  protected fun handleImmediateLambdaInvocation(node: UCallExpression): Boolean {
    node.receiver?.accept(this)
    for (arg in node.valueArguments) {
      when (val unwrapped = (arg as? ULabeledExpression)?.expression ?: arg) {
        is ULambdaExpression -> unwrapped.body.accept(this)
        is UCallableReferenceExpression -> checkCallableReference(unwrapped)
        else -> unwrapped.accept(this)
      }
    }
    return true
  }

  /**
   * This method determines which lambda arguments of the provided inline function can and cannot maintain the current held lock context. It
   * then proceeds to visit them with the appropriate set of held locks.
   *
   * More specifically, it visits any lambda arguments without a {@code noinline} or {@code crossinline} modifier with the current set of
   * held locks. Otherwise the lambda arguments are treated like any other lambda and an empty lock set is used.
   *
   * @return Boolean that follows the convention of UastVisitors' visit.* methods: false to visit children, true to skip children nodes.
   */
  protected fun KaSession.handleInlineMethodCall(
    functionLikeSymbol: KaFunctionSymbol,
    args: List<UExpression>,
  ): Boolean {
    args.forEachIndexed { i, arg ->
      if (arg !is ULambdaExpression) return@forEachIndexed
      // if we can't get descriptor, check children
      val param = functionLikeSymbol.valueParameters.getOrNull(i) ?: return false
      if (param.isCrossinline || param.isNoinline) {
        visitLambdaExpression(arg)
      } else {
        arg.body.accept(this@AbstractGuardedByVisitor)
      }
    }
    return true
  }

  /**
   * Visits all of the try expression's subexpressions and checks to make sure any locks used to protect code in the {@code try} block are
   * released in the {@code finally} block, or acquired via try-with-resources.
   *
   * @param node The try expression to be checked.
   * @param unlockMatcher Matcher used to determine which function calls unlock a lock.
   * @param reExitErrorMessage Message provided if a non-reentrant lock is unlocked twice. Not used if [locksAreReentrant] is true.
   */
  protected fun visitTryExpression(
    node: UTryExpression,
    unlockMatcher: MethodMatcher,
    reExitErrorMessage: String = "",
  ): Boolean {
    // Visit resource variables (and their initializers) to check for unguarded accesses inside them
    for (resource in node.resourceVariables) {
      resource.accept(this)
    }

    // Extract any locks acquired for the try block via try-with-resources
    val resourceLocks = extractResourceLocks(node)

    val finallyUnlockedLocks = mutableSetOf<T>()
    // Look for any locks released in the finally block
    node.finallyClause?.accept(
      object : AbstractUastVisitor() {
        override fun visitCallExpression(node: UCallExpression): Boolean {
          if (unlockMatcher.matchesExpr(node, context)) {
            val lockRef = createLockRefFromUCallExpr(node) ?: return false
            if (locksAreReentrant) {
              finallyUnlockedLocks.add(lockRef)
              return false
            }
            if (!lockSet.contains(lockRef) || !finallyUnlockedLocks.add(lockRef)) {
              report(node, reExitErrorMessage)
            }
          }
          return false
        }
      }
    )

    node.finallyClause?.accept(this)
    // Traversing the finally clause will remove the locks from the set, so we need to re-add them
    lockSet.addAll(finallyUnlockedLocks)
    unprotectedLockSet.removeAll(finallyUnlockedLocks)
    // Catch clauses execute after try-with-resources are closed, but while finally-unlocked locks
    // are held
    node.catchClauses.forEach { it.accept(this) }

    // Try block executes while both resource locks and finally-unlocked locks are held
    lockSet.addAll(resourceLocks)
    unprotectedLockSet.removeAll(resourceLocks)
    node.tryClause.accept(this)

    // Remove acquired locks from lockSet
    lockSet.removeAll(resourceLocks)
    lockSet.removeAll(finallyUnlockedLocks)

    return true // already checked children
  }

  protected open fun extractResourceLocks(node: UTryExpression): Set<T> = emptySet()

  protected fun handleWithLockBlock(node: UCallExpression) {
    val lockRef = createLockRefFromUCallExpr(node)
    val lambda = node.findArgOfType<ULambdaExpression>()
    if (lambda == null) {
      report(node, "No 'withLock' block could be found") // Shouldn't happen
    } else {
      val newLockSet = lockSet.toMutableSet()
      if (lockRef != null) newLockSet.add(lockRef)
      lambda.body.accept(createChild(context, newLockSet, issue))
    }
  }

  protected abstract fun createLockRefFromUCallExpr(expr: UCallExpression): T?

  /**
   * Checks if the given field's GuardedBy annotation(s) is satisfied by the held locks, and if not it reports it.
   *
   * @param node The UAST node where the member access being checked occurred.
   * @param member The member whose access is being checked.
   * @param accessLocation The method where the checked access occurred. Only used if that method is implicit (i.e., a default getter or
   *   setter) in order to provide a clearer report message.
   */
  protected fun reportUnheldLocks(
    node: UElement,
    member: PsiModifierListOwner,
    accessLocation: PsiMethod? = null,
  ) {
    val isExtensionMember = member is KtLightMethod && member.kotlinOrigin?.isExtensionDeclaration() == true
    val parentClass = PsiTreeUtil.getParentOfType(member, PsiClass::class.java) ?: return
    member
      .getGuardedByAnnos()
      .mapNotNull { it.value<String>("value") }
      .forEach { guardedByString ->
        // Unresolved GuardedBy expressions are handled in GuardedByChecker, so we can return here.
        val guardedByRef = createGuardedByRef(node, guardedByString, parentClass, member, isExtensionMember) ?: return@forEach
        reportUnsafeLockRelease(node, guardedByRef)
        if (lockSet.none { guardedByRef.fulfilledBy(it) }) {
          report(node, formatUnheldLockReport(guardedByRef, member, accessLocation))
        }
      }
  }

  private fun formatUnheldLockReport(
    guardedByRef: S,
    member: PsiModifierListOwner,
    accessLocation: PsiMethod?,
  ): String {
    val implicitLocationText =
      if (accessLocation != null) {
        val kotlinName = demangleInternalName(accessLocation.name) ?: accessLocation.name
        "in implicit accessor `${kotlinName}` "
      } else {
        ""
      }

    val namedMember = member.namedUnwrappedElement
    val memberNameText =
      if (namedMember != null) {
        "to `${namedMember.name}` "
      } else {
        ""
      }

    val guardText =
      if (guardedByRef.className != null) {
        "${guardedByRef.className}."
      } else {
        ""
      } + guardedByRef.selector

    return "Access ${memberNameText}${implicitLocationText}should be guarded by `$guardText`"
  }

  /** Reports if the `guardedByRef`s lock is not protected by a try/finally block (if necessary). */
  protected open fun reportUnsafeLockRelease(node: UElement, guardedByRef: S) {}

  /** Get the list of guard annotations on this PsiModifierListOwner */
  abstract fun PsiModifierListOwner.getGuardedByAnnos(): List<PsiAnnotation>

  /**
   * Creates a reference to a member's guard annotation.
   *
   * @param expr The expression where the guarded member occurs. Used to specify what instance this member is on.
   * @param guardedByString The string value held by the guard annotation.
   * @param parentClass The class the guarded member is defined in.
   * @param isExtensionMember Whether or not the guarded member is an extension member.
   */
  abstract fun createGuardedByRef(
    expr: UElement,
    guardedByString: String,
    parentClass: PsiClass,
    annotatedMember: PsiModifierListOwner,
    isExtensionMember: Boolean,
  ): S?

  /** Check if the passed lock reference fulfills this guard reference */
  abstract fun S.fulfilledBy(lockRef: T): Boolean

  /** The name of the class the referenced mutex is defined on. For use in report message. */
  abstract val S.className: String?

  /** The name of the referenced lock itself. For example, this could be a field name, "this", etc. */
  abstract val S.selector: String

  protected inline fun <reified M : UExpression> UCallExpression.findArgOfType(): M? {
    return valueArguments.find { it is M } as? M
  }

  protected fun report(node: UElement, message: String) {
    context.report(issue, node, context.getLocation(node), message)
  }

  companion object {
    private val OPTIONAL_IMMEDIATE_METHODS =
      setOf(
        "ifPresent",
        "ifPresentOrElse",
        "filter",
        "map",
        "flatMap",
        "or",
        "orElseGet",
        "orElseThrow",
      )
    private val ITERABLES_IMMEDIATE_METHODS = setOf("tryFind", "any", "all", "indexOf")
    private val MAP_IMMEDIATE_METHODS = setOf("computeIfAbsent", "computeIfPresent", "merge")

    internal fun isImmediateLambdaInvoker(method: PsiMethod?): Boolean {
      if (method == null) return false
      val containingClass = method.containingClass ?: return false
      val className = containingClass.qualifiedName ?: ""
      val name = method.name
      return when {
        className == "java.util.Optional" -> name in OPTIONAL_IMMEDIATE_METHODS
        className == "com.google.common.collect.Iterables" -> name in ITERABLES_IMMEDIATE_METHODS
        name == "replaceAll" -> isSubtypeOf(containingClass, "java.util.List") || isSubtypeOf(containingClass, "java.util.Map")
        name == "forEach" -> isSubtypeOf(containingClass, "java.lang.Iterable") || isSubtypeOf(containingClass, "java.util.Map")
        name == "forEachRemaining" -> isSubtypeOf(containingClass, "java.util.Iterator")
        name in MAP_IMMEDIATE_METHODS -> isSubtypeOf(containingClass, "java.util.Map")
        else -> false
      }
    }

    private fun isSubtypeOf(clazz: PsiClass, baseName: String): Boolean =
      clazz.qualifiedName == baseName || InheritanceUtil.isInheritor(clazz, baseName)
  }
}
