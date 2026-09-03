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

import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.getUMethod
import com.android.tools.lint.detector.api.isKotlin
import com.intellij.lang.jvm.JvmModifier
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralValue
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiLoopStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import java.util.Locale
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.demangleInternalName
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.elements.KtLightParameter
import org.jetbrains.kotlin.asJava.elements.isGetter
import org.jetbrains.kotlin.asJava.elements.isSetter
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.ULabeledExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.tryResolve

/** Returns whether this Java local variable or parameter is effectively final. */
internal fun PsiVariable.isEffectivelyFinal(): Boolean {
  if (hasModifier(JvmModifier.FINAL)) return true
  if (isKotlin(language)) return false
  val scope =
    when (this) {
      is PsiParameter -> declarationScope
      is PsiLocalVariable -> PsiTreeUtil.getParentOfType(this, PsiCodeBlock::class.java) ?: containingFile
      else -> return false
    }

  var writeCount = 0
  var hasLoopWrite = false
  val hasInitializer = (this as? PsiLocalVariable)?.initializer != null

  scope.accept(
    object : JavaElementVisitor() {
      override fun visitElement(element: PsiElement) {
        if (
          !hasLoopWrite &&
            (((hasInitializer || this@isEffectivelyFinal is PsiParameter) && writeCount == 0) || (!hasInitializer && writeCount <= 1))
        ) {
          element.acceptChildren(this)
        }
      }

      override fun visitReferenceExpression(expr: PsiReferenceExpression) {
        if (expr.resolve() == this@isEffectivelyFinal && PsiUtil.isAccessedForWriting(expr)) {
          writeCount++
          if (
            PsiTreeUtil.getParentOfType(
              expr,
              PsiLoopStatement::class.java,
              /* strict= */ true,
              PsiClass::class.java,
            ) != null
          ) {
            hasLoopWrite = true
          }
        }
        super.visitReferenceExpression(expr)
      }
    }
  )

  if (hasLoopWrite) return false
  return if (hasInitializer || this is PsiParameter) {
    writeCount == 0
  } else {
    writeCount == 1
  }
}

/** If the UReferenceExpression resolves to a constructor-defined property, returns the field backing the property and null otherwise. */
internal fun UReferenceExpression.getBackingFieldIfConstructorProperty(): PsiField? {
  val resolved = tryResolveHarder() as? PsiParameter ?: return null
  // Look for a Val or Var element to verify this is indeed a constructor-defined property
  if ((resolved as? KtLightParameter)?.kotlinOrigin?.hasValOrVar() != true) return null
  return PsiTreeUtil.getParentOfType(resolved, PsiClass::class.java)?.findFieldByName(resolved.name, false)
}

/**
 * Gets the getter for a property defined in the PsiClass's constructor by the passed UParameter. Returns null if the UParameter does not
 * define a property.
 */
internal fun PsiClass.getGetterForParameterProperty(param: UParameter): PsiMethod? {
  if (!(param.sourcePsi as KtParameter).hasValOrVar()) return null
  @Suppress("UElementAsPsi") val paramName = param.name
  val getterName = "get" + paramName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
  return findMethodsByName(getterName, /* checkBases= */ false).firstOrNull { it.parameterList.isEmpty }
}

/**
 * Gets the setter for a property defined in the PsiClass's constructor by the passed UParameter. Returns null if the UParameter does not
 * define a property.
 */
internal fun PsiClass.getSetterForParameterProperty(param: UParameter): PsiMethod? {
  if (!(param.sourcePsi as KtParameter).hasValOrVar()) return null
  @Suppress("UElementAsPsi") val paramName = param.name
  val setterName = "set" + paramName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
  return findMethodsByName(setterName, /* checkBases= */ false).firstOrNull {
    it.parameterList.parametersCount == 1 && it.parameterList.parameters.first().type == param.type
  }
}

/** Checks the passed PsiClass and all of its outer classes for a field matching the passed name, taking static boundaries into account. */
internal fun findFieldInOuterClasses(clazz: PsiClass, fieldName: String): PsiField? {
  var cur: PsiClass? = clazz
  var reachedStatic = false
  while (cur != null) {
    val field = cur.findFieldByName(fieldName, /* checkBases= */ true)
    if (field != null && (!reachedStatic || field.hasModifier(JvmModifier.STATIC))) {
      return field
    }
    if (cur is PsiAnonymousClass) {
      cur = PsiTreeUtil.getParentOfType(cur, PsiClass::class.java)
    } else {
      if (!cur.isInnerClass()) {
        reachedStatic = true
      }
      cur = cur.containingClass
    }
  }
  return null
}

internal fun findMethodsInOuterClasses(clazz: PsiClass, methodName: String): List<PsiMethod> {
  var cur: PsiClass? = clazz
  var reachedStatic = false
  while (cur != null) {
    val methods = cur.findMethodsByName(methodName, /* checkBases= */ true).toList()
    val matching = if (reachedStatic) methods.filter { it.hasModifier(JvmModifier.STATIC) } else methods
    if (matching.isNotEmpty()) return matching
    if (cur is PsiAnonymousClass) {
      cur = PsiTreeUtil.getParentOfType(cur, PsiClass::class.java)
    } else {
      if (!cur.isInnerClass()) {
        reachedStatic = true
      }
      cur = cur.containingClass
    }
  }
  return emptyList()
}

/** Checks whether the class has access to its enclosing class instance. */
internal fun PsiClass.isInnerClass(): Boolean {
  return isInner() || (this !is PsiAnonymousClass && !hasModifier(JvmModifier.STATIC) && containingClass != null)
}

/**
 * Returns a sequence of classes that are in scope of the passed [PsiClass], including that class. The sequence is sorted from narrowest
 * scope to widest.
 */
internal fun getClassesInScope(clazz: PsiClass): Sequence<PsiClass> {
  return generateSequence(clazz) {
    if (it is PsiAnonymousClass) {
      PsiTreeUtil.getParentOfType(it, PsiClass::class.java)
    } else if (it.isInnerClass()) {
      it.containingClass
    } else {
      null
    }
  }
}

/** Finds the [PsiClass] for an unqualified class name. If a matching class cannot be found in the [JavaContext]'s package, returns null. */
internal fun JavaContext.resolveClassSymbolInPackage(classSymbol: String): PsiClass? {
  return evaluator.findClass("${uastFile?.packageName}.$classSymbol")
}

/**
 * Finds the [PsiClass] for an unqualified class name in the outer classes of the given [PsiClass]. If a non-inner class is encountered
 * before a class with a matching name is found, returns null.
 */
internal fun PsiClass.findClassInOuterClasses(className: String): PsiClass? {
  return getClassesInScope(this).find { it.name == className }
}

/**
 * Removes any leading `this` expressions that do not add any meaning to the expression. This includes any `this` without a label, as well
 * as any `this` with a redundant label.
 */
internal fun trimTrivialThisExpr(expr: UExpression): UExpression? {
  var firstElement = expr
  var prev: UQualifiedReferenceExpression? = null
  while (firstElement is UQualifiedReferenceExpression) {
    prev = firstElement
    firstElement = firstElement.receiver
  }
  return when {
    firstElement is UThisExpression -> {
      if (firstElement.label != null) {
        val scopeName = firstElement.getExpressionType()?.let { expr.getCurrentLambdaScopeName(it) }
        if (scopeName == firstElement.label) prev?.selector else expr
      } else {
        prev?.selector
      }
    }
    else -> expr
  }
}

/** Gets the scope label for the narrowest scope that has a receiver of [thisType]. */
private fun UExpression.getCurrentLambdaScopeName(thisType: PsiType): String? {
  var scope: UAnnotated? = this
  do {
    scope =
      scope?.getParentOfType(
        strict = true,
        ULambdaExpression::class.java,
        UClass::class.java,
        UMethod::class.java,
      )
  } while (scope?.isValidThisScope(thisType) == false)

  return when (scope) {
    is ULambdaExpression -> scope.getLambdaScopeLabel()
    is UMethod -> scope.name
    is UClass -> scope.javaPsi.name
    else -> null
  }
}

internal fun UExpression.tryResolveHarder(): PsiElement? = tryResolve() ?: (this as? UQualifiedReferenceExpression)?.selector?.tryResolve()

internal fun PsiClass.isInner(): Boolean {
  val ktClassOrObject =
    when {
      this is KtLightClass -> kotlinOrigin
      isKotlin(language) && this is UClass -> (javaPsi as? KtLightClass)?.kotlinOrigin
      else -> null
    }
  return (ktClassOrObject as? KtClass)?.isInner() == true
}

internal fun ULambdaExpression.getLambdaScopeLabel(): String? {
  val parentCall = this.getParentOfType(UCallExpression::class.java, true)
  val parentFun =
    if (parentCall?.valueArguments?.contains(this) == true) {
      parentCall.resolve()?.getUMethod()
    } else {
      null
    }

  return when {
    uastParent is ULabeledExpression -> (uastParent as? ULabeledExpression)?.label
    parentFun != null -> parentFun.name
    sourcePsi is KtFunction -> (sourcePsi as? KtFunction)?.name
    else -> null
  }
}

internal fun PsiClass.inheritsFrom(context: JavaContext, superName: String, strict: Boolean = false): Boolean =
  context.evaluator.inheritsFrom(this, superName, strict = strict)

internal fun UField.getKtPropertyGetter(): PsiMethod? {
  val property = (this.sourcePsi as? KtProperty) ?: return null
  val propName = property.name ?: return null
  val getterName = "get" + propName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

  return getContainingUClass()
    ?.methods
    ?.map { it.javaPsi }
    ?.filter { it.name == getterName || it.name.startsWith("$getterName$") }
    ?.singleOrNull { method ->
      method.parameterList.parameters.isEmpty() && method.returnType == type && (method as? KtLightMethod)?.isGetter == true
    }
}

internal fun UField.getKtPropertySetter(): PsiMethod? {
  val property = (this.sourcePsi as? KtProperty) ?: return null
  val propName = property.name ?: return null
  val setterName = "set" + propName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

  return getContainingUClass()
    ?.methods
    ?.map { it.javaPsi }
    ?.filter { it.name == setterName || it.name.startsWith("$setterName$") }
    ?.singleOrNull { method ->
      method.parameterList.parameters.singleOrNull()?.type == type &&
        method.returnType == PsiType.VOID &&
        (method as? KtLightMethod)?.isSetter == true
    }
}

internal fun KtLightMethod.getAccessedField(): PsiField? {
  if (!isAccessor()) return null
  var topContainingClass: PsiClass = containingClass
  val uClass = topContainingClass.toUElement() as? UClass
  val ktObject = uClass?.sourcePsi as? KtObjectDeclaration
  if (ktObject?.isCompanion() == true) {
    topContainingClass = topContainingClass.containingClass ?: return null
  }
  val kotlinName = demangleInternalName(name) ?: name
  return topContainingClass.findFieldByName(
    kotlinName.drop(3).replaceFirstChar { it.lowercase(Locale.ROOT) },
    false,
  )
}

internal fun KtLightMethod.isAccessor(): Boolean = isGetter || isSetter

internal val PsiAnnotation.simpleName: String?
  get() = qualifiedName?.substringAfterLast('.') ?: (this as? UAnnotation)?.qualifiedName?.substringAfterLast('.')

internal inline fun <reified T : Any> PsiAnnotation.value(name: String = "value", context: JavaContext? = null): T? {
  val attrVal = findAttributeValue(name)
  val evaluated =
    (toUElement() as? UAnnotation)?.findAttributeValue(name)?.evaluate()
      ?: (attrVal.toUElement() as? UExpression)?.evaluate()
      ?: (attrVal as? PsiLiteralValue)?.value
      ?: (if (context != null && attrVal != null) ConstantEvaluator.evaluate(context, attrVal) else null)
  return (evaluated as? T) ?: (if (T::class == String::class && attrVal != null) attrVal.text.trim('"') as? T else null)
}

internal fun PsiClass.isCompanionObject(): Boolean =
  (this as? KtLightClass)?.kotlinOrigin is KtObjectDeclaration &&
    ((this as? KtLightClass)?.kotlinOrigin as KtObjectDeclaration).isCompanion()

internal fun interface MethodMatcher {
  fun matches(method: PsiMethod): Boolean

  fun matchesExpr(callExpr: UCallExpression, context: JavaContext? = null): Boolean = callExpr.resolve()?.let { matches(it) } == true

  companion object {
    fun onClass(className: String): MethodMatcherBuilder = MethodMatcherBuilder(ClassMatcher { it.qualifiedName == className })

    fun onClass(matcher: ClassMatcher): MethodMatcherBuilder = MethodMatcherBuilder(matcher)
  }
}

internal class MethodMatcherBuilder(private val classMatcher: ClassMatcher) {
  fun withName(name: String): MethodMatcher = MethodMatcher { method ->
    method.name == name && method.containingClass?.let { classMatcher.matches(it) } == true
  }
}

internal fun interface ClassMatcher {
  fun matches(clazz: PsiClass): Boolean

  companion object {
    fun isAssignableTo(className: String): ClassMatcher = ClassMatcher { clazz ->
      clazz.qualifiedName == className || InheritanceUtil.isInheritor(clazz, className)
    }
  }
}

internal fun KaSession.isInlineOrInsideInline(functionLikeSymbol: KaFunctionSymbol): Boolean {
  var symbol: KaSymbol? = functionLikeSymbol
  while (symbol != null) {
    if (symbol is KaNamedFunctionSymbol && symbol.isInline) {
      return true
    }
    symbol = symbol.containingSymbol
  }
  return false
}
