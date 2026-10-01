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

import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.isKotlin
import com.intellij.lang.jvm.JvmModifier
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTypesUtil
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.asJava.elements.KtLightParameter
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UResolvable
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.toUElement

/** Class that holds the information needed to compare locks to GuardedBy annotations. */
internal sealed class GuardedByExpression {
  protected abstract val qualifyingExpression: QualifierExpression?
  abstract val lockType: PsiType?
  abstract val selector: String

  /** Checks whether the qualifier represents Java implicit or explicit lexical `this` scopes. */
  protected fun isJavaLexicalThis(expr: QualifierExpression?): Boolean {
    val uExpr = expr?.uExpr
    if (expr == null || uExpr is UThisExpression) {
      val lang = uExpr?.lang
      val isKt = lang != null && isKotlin(lang)
      return !isKt
    }
    return false
  }

  /**
   * The class that contains the target of this expression. E.g. the class that 'this' refers to, or the class the target field is a member
   * of.
   */
  abstract val clazz: PsiClass?

  open val className: String?
    get() = clazz?.name

  /**
   * Returns whether this expression is satisfied by the lock defined by another expression.
   *
   * @param other An expression defining a lock to be compared to this expression.
   */
  abstract fun fulfilledBy(other: GuardedByExpression): Boolean

  /** Checks that the lock being referenced by this expression is final. */
  abstract fun lockIsFinal(): Boolean

  internal data class ThisExpression
  private constructor(
    override val clazz: PsiClass,
    override val qualifyingExpression: QualifierExpression?,
  ) : GuardedByExpression() {
    constructor(
      clazz: PsiClass,
      qualifyingUExpression: UExpression?,
    ) : this(clazz, qualifyingUExpression?.let(::QualifierExpression))

    override val lockType = PsiTypesUtil.getClassType(clazz)
    override val selector = "this"

    override fun fulfilledBy(other: GuardedByExpression): Boolean {
      return when (other) {
        is ThisExpression -> {
          if (
            InheritanceUtil.isInheritorOrSelf(other.clazz, clazz, true) &&
              qualifyingExpression?.string == other.qualifyingExpression?.string
          ) {
            return true
          }
          if (isJavaLexicalThis(qualifyingExpression) && isJavaLexicalThis(other.qualifyingExpression)) {
            return InheritanceUtil.isInheritorOrSelf(other.clazz, clazz, true) ||
              InheritanceUtil.isInheritorOrSelf(clazz, other.clazz, true)
          }
          false
        }
        // This handles the case where the other expression is recognized as a field, but that field
        // is actually the 'this' for this expression
        is FieldExpression -> {
          val otherQualifier = other.qualifyingExpression?.string?.let { "$it." }.orEmpty() + other.fieldRef.name
          other.fieldRef.type.psiClass?.let { InheritanceUtil.isInheritorOrSelf(it, clazz, true) } == true &&
            qualifyingExpression?.string == otherQualifier
        }
        else -> false
      }
    }

    override fun lockIsFinal() = qualifyingExpression?.uExpr?.isFinalLock() ?: true
  }

  internal data class ClassExpression internal constructor(override val clazz: PsiClass) : GuardedByExpression() {
    override val qualifyingExpression: QualifierExpression? = null
    override val lockType: PsiType = PsiTypesUtil.getClassType(clazz)
    override val selector = "class"

    override fun fulfilledBy(other: GuardedByExpression): Boolean {
      return other is ClassExpression &&
        (InheritanceUtil.isInheritorOrSelf(other.clazz, clazz, true) || InheritanceUtil.isInheritorOrSelf(clazz, other.clazz, true))
    }

    override fun lockIsFinal() = true
  }

  internal data class FieldExpression
  private constructor(
    val fieldRef: PsiField,
    override val qualifyingExpression: QualifierExpression?,
  ) : GuardedByExpression() {
    constructor(
      fieldRef: PsiField,
      qualifyingUExpression: UExpression?,
    ) : this(fieldRef, qualifyingUExpression?.let(::QualifierExpression))

    override val clazz = fieldRef.containingClass
    override val lockType = fieldRef.type
    override val selector = fieldRef.name

    override fun fulfilledBy(other: GuardedByExpression): Boolean {
      if (other !is FieldExpression || other.fieldRef != fieldRef) return false
      if (fieldRef.hasModifier(JvmModifier.STATIC)) return true
      if (other.qualifyingExpression?.string == qualifyingExpression?.string) return true
      return isJavaLexicalThis(qualifyingExpression) && isJavaLexicalThis(other.qualifyingExpression)
    }

    override fun lockIsFinal(): Boolean {
      return fieldRef.hasModifier(JvmModifier.FINAL) && (qualifyingExpression?.uExpr?.isFinalLock() ?: true)
    }
  }

  internal data class ItselfExpression
  private constructor(
    val member: PsiModifierListOwner,
    override val qualifyingExpression: QualifierExpression?,
  ) : GuardedByExpression() {
    constructor(
      member: PsiModifierListOwner,
      qualifyingUExpression: UExpression?,
    ) : this(member, qualifyingUExpression?.let(::QualifierExpression))

    override val clazz = (member as? PsiMember)?.containingClass
    override val lockType =
      when (member) {
        is PsiField -> member.type
        is PsiMethod -> member.returnType ?: clazz?.let { PsiTypesUtil.getClassType(it) }
        else -> null
      }
    override val selector =
      when (member) {
        is PsiMethod -> "${member.name}()"
        is PsiNamedElement -> member.name ?: "itself"
        else -> "itself"
      }

    override fun fulfilledBy(other: GuardedByExpression): Boolean {
      return when (other) {
        is FieldExpression -> member is PsiField && other.fieldRef == member
        is MethodExpression -> member is PsiMethod && other.methodRef == member
        is ItselfExpression -> member == other.member
        else -> false
      }
    }

    override fun lockIsFinal() = true
  }

  internal data class MethodExpression
  private constructor(
    val methodRef: PsiMethod,
    override val qualifyingExpression: QualifierExpression?,
  ) : GuardedByExpression() {
    constructor(
      methodRef: PsiMethod,
      qualExpr: UExpression?,
    ) : this(methodRef, qualExpr?.let(::QualifierExpression))

    override val clazz: PsiClass? = methodRef.containingClass

    // PsiMethod.returnType can return null if it is a constructor; if so, we use class type
    override val lockType = methodRef.returnType ?: clazz?.let { PsiTypesUtil.getClassType(it) }
    override val selector: String = "${methodRef.name}()"
    override val className: String?
      get() {
        return if (clazz?.isCompanionObject() == true) {
          clazz.containingClass?.name
        } else {
          clazz?.name
        }
      }

    override fun fulfilledBy(other: GuardedByExpression): Boolean {
      return other is MethodExpression &&
        other.methodRef == methodRef &&
        (clazz?.isCompanionObject() == true || other.qualifyingExpression?.string == qualifyingExpression?.string)
    }

    override fun lockIsFinal(): Boolean = qualifyingExpression?.uExpr?.isFinalLock() ?: true
  }

  /**
   * Wrapper class for the [GuardedByExpression]'s qualifying expressions. [UExpression]'s equals and hashcode implementations are too
   * strict, so the wrapper class implements one that is more by value for our purposes.
   */
  protected data class QualifierExpression(val uExpr: UExpression) {
    val string by lazy { uExpr.asSourceString() }

    override fun equals(other: Any?): Boolean {
      return other is QualifierExpression && other.string == string
    }

    override fun hashCode() = string.hashCode()
  }

  companion object {
    internal fun createGuardedByExprFromString(
      string: String,
      clazz: PsiClass,
      annotatedMember: PsiModifierListOwner,
      context: JavaContext,
      receiver: UExpression? = null,
    ): GuardedByExpression? {
      val trimmedReceiver = receiver?.let { trimTrivialThisExpr(it) }
      val tokens = string.split(".")
      return when {
        tokens.singleOrNull() == "itself" -> {
          annotatedMember.createSelfGuardExpression(trimmedReceiver)
        }
        tokens.last() == "this" -> { // Class instance reference
          val targetClass: PsiClass? = if (tokens.size > 1) clazz.findClassInOuterClasses(tokens.first()) else clazz
          targetClass?.let { ThisExpression(it, trimmedReceiver) }
        }
        tokens.last() == "class" -> { // Class object reference
          if (tokens.size != 2) return null
          // Error prone does not resolve class symbols outside of the package the @GuardedBy
          // annotation is located, so we follow its example.
          val matchedClass = context.resolveClassSymbolInPackage(tokens.first()) ?: clazz.findClassInOuterClasses(tokens.first())
          matchedClass?.let { ClassExpression(it) }
        }
        tokens.size > 1 && "this" !in tokens -> { // Member reference (could be static or instance)
          val matchedClass = context.resolveClassSymbolInPackage(tokens.first()) ?: clazz.findClassInOuterClasses(tokens.first())
          matchedClass?.let {
            createQualifiedMemberExpression(tokens.last(), trimmedReceiver, it, false)
          }
        }
        tokens.size > 2 -> { // Qualified instance member reference
          val targetClass = clazz.findClassInOuterClasses(tokens.first())
          targetClass?.let {
            createQualifiedMemberExpression(tokens.last(), trimmedReceiver, it, true)
          }
        }
        tokens.last().endsWith("()") -> { // Instance method reference
          val foundMethods = findMethodsInOuterClasses(clazz, tokens.last().removeSuffix("()"))
          // Error prone doesn't resolve methods when there are multiple with the same name.
          // We do the same.
          foundMethods.singleOrNull()?.let { MethodExpression(it, trimmedReceiver) }
        }
        else -> { // Instance field reference
          findFieldInOuterClasses(clazz, tokens.last())?.let {
            FieldExpression(it, trimmedReceiver)
          }
        }
      }
    }

    private fun PsiModifierListOwner.createSelfGuardExpression(receiver: UExpression?): GuardedByExpression {
      return ItselfExpression(this, receiver)
    }

    private fun createQualifiedMemberExpression(
      memberIdentifier: String,
      receiver: UExpression?,
      targetClass: PsiClass,
      mustNotBeStatic: Boolean,
    ): GuardedByExpression? {
      return if (memberIdentifier.endsWith("()")) {
        createQualifiedMethodExpression(memberIdentifier, receiver, targetClass, mustNotBeStatic)
      } else {
        createQualifiedFieldExpression(memberIdentifier, receiver, targetClass, mustNotBeStatic)
      }
    }

    private fun createQualifiedMethodExpression(
      memberIdentifier: String,
      receiver: UExpression?,
      targetClass: PsiClass,
      mustNotBeStatic: Boolean,
    ): MethodExpression? {
      val methodName = memberIdentifier.removeSuffix("()")
      val matchingMethods = targetClass.findMethodsByName(methodName, true).toMutableList()
      if (!mustNotBeStatic) {
        matchingMethods.addAll(
          (targetClass as? KtLightClass)
            ?.kotlinOrigin
            ?.companionObjects
            ?.flatMap { it.toLightClass()?.findMethodsByName(methodName, true).orEmpty().toList() }
            .orEmpty()
        )
      } else {
        matchingMethods.removeAll { it.hasModifier(JvmModifier.STATIC) }
      }
      // Error prone doesn't resolve methods when there are multiple with the same name.
      // We do the same.
      return matchingMethods.singleOrNull()?.let { MethodExpression(it, receiver) }
    }

    private fun createQualifiedFieldExpression(
      memberIdentifier: String,
      receiver: UExpression?,
      targetClass: PsiClass,
      mustNotBeStatic: Boolean,
    ): FieldExpression? {
      return targetClass.findFieldByName(memberIdentifier, true)?.let {
        if (mustNotBeStatic && it.hasModifier(JvmModifier.STATIC)) {
          null
        } else {
          FieldExpression(it, receiver)
        }
      }
    }

    protected tailrec fun UExpression.isFinalLock(): Boolean {
      fun UExpression.isFinalHelper(): Boolean {
        if (this is UThisExpression) return true
        if (this !is UResolvable) return false
        val resolved = resolve()
        if (resolved !is PsiVariable) return true
        val ktParam = (resolved as? KtLightParameter)?.kotlinOrigin ?: (resolved.toUElement()?.sourcePsi as? KtParameter)
        if (ktParam != null) {
          return !ktParam.hasValOrVar() || !ktParam.isMutable
        }
        val ktProperty =
          (resolved.toUElement()?.sourcePsi as? KtProperty) ?: ((resolved as? KtLightElement<*, *>)?.kotlinOrigin as? KtProperty)
        if (ktProperty != null) {
          return !ktProperty.isVar
        }
        return resolved.hasModifier(JvmModifier.FINAL) ||
          (resolved is PsiLocalVariable && resolved.isEffectivelyFinal()) ||
          (resolved is PsiParameter && resolved.isEffectivelyFinal())
      }
      return if (this is UQualifiedReferenceExpression) {
        if (!selector.isFinalHelper()) false else receiver.isFinalLock()
      } else {
        isFinalHelper()
      }
    }
  }
}

/**
 * Class that holds the GuardedByExpression for a GuardedBy guard. Used to compare held locks with lock expressions in GuardedBy
 * annotations.
 */
internal class GuardedByReference(private val expr: GuardedByExpression) : AbstractGuardedByDetector.GuardReference {
  val clazz: PsiClass?
    get() = expr.clazz

  override val lockType = expr.lockType
  val selector = expr.selector
  val className = expr.className

  fun fulfilledBy(heldLock: LockReference): Boolean = expr.fulfilledBy(heldLock.expr)

  override fun lockIsFinal(): Boolean = expr.lockIsFinal()

  companion object {
    /**
     * Creates a GuardedByReference from a GuardedBy annotation argument string and the enclosing PsiClass.
     *
     * @param string GuardedBy annotation argument that identifies the lock.
     * @param clazz The class that contains the guarded member.
     * @param receiver An optional receiver if the lock reference is qualified.
     */
    internal fun createGuardedByRefFromString(
      string: String,
      clazz: PsiClass,
      annotatedMember: PsiModifierListOwner,
      context: JavaContext,
      receiver: UExpression? = null,
    ): GuardedByReference? {
      return GuardedByExpression.createGuardedByExprFromString(
          string,
          clazz,
          annotatedMember,
          context,
          receiver,
        )
        ?.let { GuardedByReference(it) }
    }
  }
}

/** Data class that holds the information of a lock. Used to compare held locks with lock expressions in GuardedBy annotations. */
internal data class LockReference(val expr: GuardedByExpression) {
  constructor(
    clazz: PsiClass,
    receiver: UExpression? = null,
    isStatic: Boolean = false,
  ) : this(createClassGuardedByExpr(clazz, receiver, isStatic))

  @Suppress("ExternalAnnotations") // TODO: use JavaEvaluator.getAllAnnotations, which isn't in scope here
  constructor(
    field: PsiField,
    receiver: UExpression? = null,
  ) : this(
    if (
      field.annotations.any {
        (it.simpleName == "GuardedBy" || it.simpleName == "GuardedByMutex") && it.value<String>("value") == "itself"
      }
    ) {
      GuardedByExpression.ItselfExpression(field, receiver?.let { trimTrivialThisExpr(it) })
    } else {
      GuardedByExpression.FieldExpression(field, receiver?.let { trimTrivialThisExpr(it) })
    }
  )

  constructor(
    method: PsiMethod,
    receiver: UExpression? = null,
  ) : this(GuardedByExpression.MethodExpression(method, receiver?.let { trimTrivialThisExpr(it) }))

  companion object {
    /**
     * Creates a LockReference from a GuardedBy annotation argument string and the annotated UElement node.
     *
     * @param guardString Text that identifies the lock.
     * @param node The node of the guarded member
     */
    fun createLockReferenceFromString(
      guardString: String,
      node: UDeclaration,
      context: JavaContext,
    ): LockReference? {
      val clazz = node.getContainingUClass()?.javaPsi ?: return null
      return GuardedByExpression.createGuardedByExprFromString(guardString, clazz, node, context)?.let { LockReference(it) }
    }

    private fun createClassGuardedByExpr(
      clazz: PsiClass,
      receiver: UExpression?,
      isStatic: Boolean,
    ): GuardedByExpression {
      return if (isStatic) {
        GuardedByExpression.ClassExpression(clazz)
      } else {
        GuardedByExpression.ThisExpression(clazz, receiver?.let { trimTrivialThisExpr(it) })
      }
    }
  }
}
