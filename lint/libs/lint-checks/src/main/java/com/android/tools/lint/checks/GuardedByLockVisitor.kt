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
import com.intellij.psi.PsiSynchronizedStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULabeledExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.UTryExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElementOfType

/**
 * This visitor keeps track of the currently held locks. When it encounters an expression whose declaration is annotated with GuardedBy, it
 * makes sure the correct lock is held.
 */
internal class GuardedByLockVisitor(
  context: JavaContext,
  lockSet: MutableSet<LockReference>,
  issue: Issue,
  private val guardedBySimpleName: String,
) :
  AbstractGuardedByVisitor<LockReference, GuardedByReference, GuardedByLockVisitor>(
    context,
    lockSet,
    issue,
    locksAreReentrant = true,
  ) {
  private val syncMethodMatcher = MethodMatcher.onClass("kotlin.StandardKt__SynchronizedKt").withName("synchronized")

  private val annotatedMethodMatcher = MethodMatcher { meth ->
    meth.annotations.any { it.simpleName == guardedBySimpleName }
  }

  private val withLockMatcher = MethodMatcher.onClass("kotlin.concurrent.LocksKt").withName("withLock")

  private val lockClassMatcher = ClassMatcher.isAssignableTo("java.util.concurrent.locks.Lock")
  private val semaphoreClassMatcher = ClassMatcher.isAssignableTo("java.util.concurrent.Semaphore")

  private val lockMatchers =
    listOf(
      MethodMatcher.onClass(lockClassMatcher).withName("lock"),
      MethodMatcher.onClass(lockClassMatcher).withName("tryLock"),
      MethodMatcher.onClass(lockClassMatcher).withName("lockInterruptibly"),
      MethodMatcher.onClass(semaphoreClassMatcher).withName("acquire"),
      MethodMatcher.onClass(semaphoreClassMatcher).withName("tryAcquire"),
      MethodMatcher.onClass(semaphoreClassMatcher).withName("acquireUninterruptibly"),
    )

  private val unlockMatcher = MethodMatcher { method ->
    val containingClass = method.containingClass ?: return@MethodMatcher false
    when (method.name) {
      "unlock" -> lockClassMatcher.matches(containingClass)
      "release" -> semaphoreClassMatcher.matches(containingClass)
      else -> false
    }
  }

  override fun visitCallExpression(node: UCallExpression): Boolean {
    val callee = node.resolve()
    if (callee != null && annotatedMethodMatcher.matches(callee)) {
      reportUnheldLocks(node, callee)
    }
    when {
      callee != null && syncMethodMatcher.matches(callee) -> {
        handleSyncBlock(node)
        return true // don't enter children, they are handled in handleSyncBlock
      }
      callee != null && lockMatchers.any { it.matches(callee) } -> {
        val lockRef = createLockRefFromUCallExpr(node) ?: return false
        lockSet.add(lockRef)
        unprotectedLockSet.add(lockRef)
      }
      callee != null && withLockMatcher.matches(callee) -> {
        handleWithLockBlock(node)
        return true // don't enter children, they are handled in handleWithLockBlock
      }
      isImmediateLambdaInvoker(callee) -> return handleImmediateLambdaInvocation(node)
      node.valueArguments.any { it is ULambdaExpression } -> {
        val ktCallElement = (node.sourcePsi as? KtCallElement) ?: return false
        return analyze(ktCallElement) {
          val functionLikeSymbol = getFunctionLikeSymbol(ktCallElement) ?: return@analyze false
          if (!isInlineOrInsideInline(functionLikeSymbol)) return@analyze false
          handleInlineMethodCall(functionLikeSymbol, node.valueArguments)
        }
      }
    }
    return false
  }

  override fun visitElement(node: UElement): Boolean {
    val sourcePsi = node.sourcePsi
    if (sourcePsi is PsiSynchronizedStatement) {
      val uLockExpr = sourcePsi.lockExpression.toUElementOfType<UExpression>()
      val uBody = sourcePsi.body.toUElementOfType<UExpression>()
      val lockRef: LockReference? =
        when (val expr = (uLockExpr as? ULabeledExpression)?.expression ?: uLockExpr) {
          is UThisExpression -> {
            val targetClass =
              expr.label?.let { label ->
                (expr.getParentOfType<UClass>()?.javaPsi ?: expr.getExpressionType()?.psiClass)?.findClassInOuterClasses(label)
              } ?: expr.getExpressionType()?.psiClass
            targetClass?.let { LockReference(it, expr) }
          }
          is UClassLiteralExpression -> expr.type?.psiClass?.let { LockReference(it, isStatic = true) }
          is UReferenceExpression -> {
            val ref = createLockRefFromSyncParam(expr)
            if (ref?.expr?.lockIsFinal() == false) {
              report(
                node,
                "Variables being locked on should be final, but ${expr.asSourceString()} is not",
              )
            }
            ref
          }
          is UCallExpression -> createLockRefFromSyncParam(expr)
          else -> null
        }
      val newLockSet = lockSet.toMutableSet()
      if (lockRef != null) newLockSet.add(lockRef)
      uBody?.accept(GuardedByLockVisitor(context, newLockSet, issue, guardedBySimpleName))
      return true
    }
    return super.visitElement(node)
  }

  override fun visitTryExpression(node: UTryExpression): Boolean {
    return visitTryExpression(node, unlockMatcher)
  }

  override fun extractResourceLocks(node: UTryExpression): Set<LockReference> = buildSet {
    fun addLockIfPresent(expr: UExpression) {
      val unwrapped = (expr as? ULabeledExpression)?.expression ?: expr
      if (!isLockType(unwrapped.getExpressionType())) return
      val lockRef = createLockRefFromResourceExpr(unwrapped) ?: return
      if (!lockRef.expr.lockIsFinal()) {
        report(
          node,
          "Variables being locked on should be final, but ${unwrapped.asSourceString()} is not",
        )
      }
      add(lockRef)
    }

    for (resource in node.resourceVariables) {
      val initExpr =
        when (resource) {
          is ULocalVariable -> resource.uastInitializer
          is UExpression -> resource
          else -> null
        } ?: continue

      when (val expr = (initExpr as? ULabeledExpression)?.expression ?: initExpr) {
        is UCallExpression -> {
          // Check constructor or method arguments for Lock expressions
          for (arg in expr.valueArguments) {
            addLockIfPresent(arg)
          }
          // Check receiver if method is called on a Lock (e.g. lock.open() or lock.lock())
          val receiver = expr.receiver ?: (expr.uastParent as? UQualifiedReferenceExpression)?.receiver
          if (receiver != null) {
            addLockIfPresent(receiver)
          }
        }
        is UReferenceExpression -> {
          addLockIfPresent(expr)
        }
      }
    }
  }

  private fun createLockRefFromResourceExpr(expr: UExpression): LockReference? {
    return when (val unwrapped = (expr as? ULabeledExpression)?.expression ?: expr) {
      is UThisExpression -> unwrapped.getExpressionType()?.psiClass?.let { LockReference(it, unwrapped) }
      is UClassLiteralExpression -> unwrapped.type?.psiClass?.let { LockReference(it, isStatic = true) }
      is UReferenceExpression,
      is UCallExpression -> createLockRefFromSyncParam(unwrapped)
      else -> null
    }
  }

  private fun isLockType(type: PsiType?): Boolean {
    if (type == null) return false
    return type.canonicalText in SUPPORTED_LOCK_TYPES || SUPPORTED_LOCK_TYPES.any { InheritanceUtil.isInheritor(type, it) }
  }

  private fun handleSyncBlock(node: UCallExpression) {
    val lockArg = node.getArgumentForParameter(0)
    val lock: LockReference? =
      when (val lockExpression = (lockArg as? ULabeledExpression)?.expression ?: lockArg) {
        is UThisExpression -> lockExpression.getExpressionType()?.psiClass?.let { LockReference(it) }
        is UReferenceExpression -> {
          val lockRef = createLockRefFromSyncParam(lockExpression)
          if (lockRef?.expr?.lockIsFinal() == false) {
            report(
              node,
              "Variables being locked on should be final, but ${lockExpression.asSourceString()} is not",
            )
          }
          lockRef
        }
        is UCallExpression -> createLockRefFromSyncParam(lockExpression)
        else -> null
      }
    val lambda = node.findArgOfType<ULambdaExpression>()
    if (lambda == null) report(node, "No sync code block could be found") // Shouldn't happen
    else {
      val newLockSet = lockSet.toMutableSet()
      if (lock != null) newLockSet.add(lock)
      lambda.body.accept(GuardedByLockVisitor(context, newLockSet, issue, guardedBySimpleName))
    }
  }

  override fun createLockRefFromUCallExpr(expr: UCallExpression): LockReference? {
    val receiverExpr = expr.receiver ?: (expr.uastParent as? UQualifiedReferenceExpression)?.receiver
    val lockRef = (receiverExpr as? UReferenceExpression)?.let { createLockRefFromSyncParam(it) }

    if (lockRef == null) {
      report(expr, "Could not resolve lock for the expression ${expr.asSourceString()}")
    }
    return lockRef
  }

  override fun createChild(
    context: JavaContext,
    lockSet: Set<LockReference>,
    issue: Issue,
  ): GuardedByLockVisitor = GuardedByLockVisitor(context, lockSet.toMutableSet(), issue, guardedBySimpleName)

  @Suppress("ExternalAnnotations")
  override fun PsiModifierListOwner.getGuardedByAnnos(): List<PsiAnnotation> {
    return annotations.filter { it.simpleName == guardedBySimpleName }
  }

  override fun createGuardedByRef(
    elem: UElement,
    guardedByString: String,
    parentClass: PsiClass,
    annotatedMember: PsiModifierListOwner,
    isExtensionMember: Boolean,
  ): GuardedByReference? {
    val qualifyingExpression =
      when {
        /*
         * Extension members treat 'this' guards as the defining class (not the extended class) to
         * maintain consistency with Java.
         * Because of this, these extension methods cannot be called outside of the 'this' context,
         * and so qualifying expressions do not matter.
         */
        isExtensionMember -> null
        // 'this' qualifying expressions should not be preserved when comparing the variable to
        // its guarding lock.
        elem is UQualifiedReferenceExpression -> elem.receiver
        elem is UCallExpression -> elem.receiver
        elem is UCallableReferenceExpression -> elem.qualifierExpression
        else -> null
      }
    val ref =
      GuardedByReference.createGuardedByRefFromString(
        guardedByString,
        parentClass,
        annotatedMember,
        context,
        qualifyingExpression,
      )

    // Warning is reported at the annotated member, so we just return null here if not supported
    val lockType = ref?.lockType ?: return null
    val isLockSupported =
      GuardedByDetector.UNSUPPORTED_LOCK_TYPES.none { (unsupportedType, _) ->
        InheritanceUtil.isInheritor(lockType, unsupportedType)
      }
    if (!isLockSupported) {
      return null
    }
    return ref
  }

  override fun GuardedByReference.fulfilledBy(lockRef: LockReference): Boolean {
    return fulfilledBy(lockRef)
  }

  override val GuardedByReference.className: String?
    get() = className

  override val GuardedByReference.selector: String
    get() = selector

  override fun reportUnsafeLockRelease(node: UElement, guardedByRef: GuardedByReference) {
    if (unprotectedLockSet.removeIf { guardedByRef.fulfilledBy(it) }) {
      report(
        node,
        "Locks must be properly released in the case of an exception being thrown. Try using withLock() or a try/finally expression",
      )
    }
  }

  companion object {
    private val SUPPORTED_LOCK_TYPES = setOf("java.util.concurrent.locks.Lock", "java.util.concurrent.Semaphore")

    fun createLockRefFromSyncParam(expr: UExpression): LockReference? {
      val receiver = if (expr is UQualifiedReferenceExpression) expr.receiver else null
      val referencedElement = expr.tryResolveHarder()
      return when {
        referencedElement is PsiField -> LockReference(referencedElement, receiver)
        referencedElement is PsiParameter -> {
          val backingField = (expr as? UReferenceExpression)?.getBackingFieldIfConstructorProperty()
          if (backingField != null) {
            LockReference(backingField, receiver)
          } else {
            referencedElement.type.psiClass?.let { LockReference(it, expr) }
          }
        }
        referencedElement is PsiVariable -> {
          referencedElement.type.psiClass?.let { LockReference(it, expr) }
        }
        referencedElement is KtLightMethod && referencedElement.isAccessor() -> {
          referencedElement.getAccessedField()?.let { LockReference(it, receiver) }
        }
        referencedElement is PsiMethod -> {
          if (
            referencedElement.name == "getJavaClass" &&
              // Require the java Class (no KClass) to be locked for @GuardedBy(Foo.class)
              // expressions
              referencedElement.returnType?.psiClass?.qualifiedName == "java.lang.Class"
          ) {
            (receiver as? UClassLiteralExpression)?.type?.psiClass?.let {
              LockReference(it, null, true)
            }
          } else {
            LockReference(referencedElement, receiver)
          }
        }
        else -> null
      }
    }
  }
}
