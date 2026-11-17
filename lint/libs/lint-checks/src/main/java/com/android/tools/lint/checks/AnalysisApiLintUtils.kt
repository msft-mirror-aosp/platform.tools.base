/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.parentsOfType
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.resolution.KaAnnotationCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCompoundAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.singleCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.singleConstructorCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.session.KaSessionProvider
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.analysis.KotlinExtensionConstants
import org.jetbrains.uast.kotlin.BaseKotlinUastResolveProviderService
import org.jetbrains.uast.toUElementOfType

/**
 * Executes the given [action] in an [analysis session][KaSession] context, even if starting from Java source.
 *
 * The project will be analyzed from the perspective of [element]'s module, which can be a Java or Kotlin element. This is similar to
 * [org.jetbrains.kotlin.analysis.api.analyze], but also allows the use of a Java element. And unlike the version of analyze that takes a
 * KaModule, this function does not use `crossinline` on [action].
 */
@OptIn(KaImplementationDetail::class, KaPlatformInterface::class)
internal inline fun <R> analyzeFromPsi(element: PsiElement, action: KaSession.() -> R): R {
  val useSiteModule = KotlinProjectStructureProvider.getInstance(element.project).getModule(element, useSiteModule = null)
  val sessionProvider = KaSessionProvider.getInstance(useSiteModule.project)
  return sessionProvider.analyze(useSiteModule, action)
}

/**
 * Resolve [KtElement] to function call, constructor call, or annotation call and return function-like symbol if any.
 *
 * Please use UAST/PSI `resolve` first, which provides a general output (of [PsiMethod]).
 *
 * The returned function-like symbol from Analysis API should be used *within* [KtAnalysisSession].
 */
internal fun KaSession.getFunctionLikeSymbol(ktElement: KtElement): KaFunctionSymbol? {
  val callInfo = ktElement.resolveToCall() ?: return null
  return callInfo.singleFunctionCallOrNull()?.symbol
    ?: callInfo.singleConstructorCallOrNull()?.symbol
    ?: callInfo.singleCallOrNull<KaAnnotationCall>()?.symbol
}

/**
 * Returns true if [ktElement] is a call of an extension function.
 *
 * Unlike [org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration], which only works if you have the Kotlin source PSI of a function
 * declaration (it will not work if you have a call that resolves to a compiled function), this analysis API-based function can identify
 * calls to extensions functions, regardless of whether the function declaration is in source or binary.
 */
internal fun KaSession.isExtensionFunctionCall(ktElement: KtElement): Boolean = getFunctionLikeSymbol(ktElement)?.isExtension == true

/** Returns the `<this>` UParameter of [this] ULambdaExpression, if it exists. */
@Suppress("UnstableApiUsage")
internal fun ULambdaExpression.getThisParameter(resolveProviderService: BaseKotlinUastResolveProviderService): UParameter? {
  val ktLambdaExpression = this.sourcePsi as? KtLambdaExpression ?: return null
  // Note: includeExplicitParameters seems to currently do the opposite of what you might expect; it
  // must be true to be able to find the implicit <this> parameter.
  val parameters =
    resolveProviderService.getImplicitParameters(ktLambdaExpression, this, includeExplicitParameters = true) as List<UParameter>
  return parameters.firstOrNull { (it.javaPsi as? PsiParameter)?.name == KotlinExtensionConstants.LAMBDA_THIS_PARAMETER_NAME }
}

/**
 * Returns the implicit receiver of the call-like expression [ktExpression] if the implicit receiver resolves to a `<this>` UParameter of a
 * lambda expression, otherwise null.
 */
internal fun KaSession.getImplicitReceiverIfFromLambdaExpr(
  ktExpression: KtExpression,
  resolveProviderService: BaseKotlinUastResolveProviderService,
): UParameter? =
  getImplicitReceiverValue(ktExpression)
    ?.getImplicitReceiverPsi()
    ?.toUElementOfType<ULambdaExpression>()
    ?.getThisParameter(resolveProviderService)

/** Returns the PSI for [this], which will be the owning lambda expression or the surrounding class. */
internal fun KaImplicitReceiverValue.getImplicitReceiverPsi(): PsiElement? {
  return when (val receiverParameterSymbol = this.symbol) {
    // the owning lambda expression
    is KaReceiverParameterSymbol -> receiverParameterSymbol.owningCallableSymbol.psi
    // the class that we are in, calling a method
    is KaClassSymbol -> receiverParameterSymbol.psi
    else -> null
  }
}

/** Returns the implicit receiver value of the call-like expression [ktExpression] (can include property accesses, for example). */
internal fun KaSession.getImplicitReceiverValue(ktExpression: KtExpression): KaImplicitReceiverValue? {
  val (extensionReceiver, dispatchReceiver) =
    when (val call = ktExpression.resolveToCall()?.singleCallOrNull<KaCall>()) {
      // Note: Calls that are a `KaCompoundAccessCall` (especially, `KaCompoundArrayAccessCall`)
      // are
      // quite complex, as such a call essentially contains multiple calls. For example, in:
      //
      // m["a"] += "b"
      //
      // we can have MutableMap.get, String?.plus, MutableMap.set. We get the implicit receiver of
      // the second call, which can only exist in this example if the extension function also
      // requires a dispatch receiver (an instance of a class in which the extension function is
      // declared).
      is KaCompoundAccessCall -> call.compoundOperation.operationCall.let { it.extensionReceiver to it.dispatchReceiver }
      is KaCallableMemberCall<*, *> -> call.partiallyAppliedSymbol.let { it.extensionReceiver to it.dispatchReceiver }
      else -> null
    } ?: return null

  return extensionReceiver as? KaImplicitReceiverValue ?: dispatchReceiver as? KaImplicitReceiverValue
}

/**
 * Returns a sequence containing [this] plus other PsiFields that are the same as [this] (if there are any), or emptySequence() if [this] is
 * null.
 *
 * A constant in a companion object in an interface results in multiple PsiFields. For example:
 * ```kt
 * // Also works for annotation class, which is essentially an interface.
 * interface MyInterface {
 *   companion object {
 *     const val CONST_1 = 1
 *   }
 * }
 * ```
 *
 * At the JVM level, the constant ends up duplicated: as a field within the companion class, and as a field within MyInterface, both
 * initialized to 1.
 *
 * It is possible to refer to both fields in Java. Even in a Kotlin-only codebase, annotations can be recovered from compiled modules via
 * annotations.zip (not just from the Android SDK); these are parsed as Java, and typically will resolve to the field in the interface,
 * while most other references will resolve to the field within the companion class.
 *
 * Thus, we must consider both fields as allowed values.
 *
 * [useSiteElement] is the element from whose module the Analysis API is queried (to confirm that the inner class is really a companion
 * object); if it is null, no duplicates are looked up.
 */
internal fun PsiElement?.asSeqWithDuplicatedConstants(useSiteElement: PsiElement?): Sequence<PsiElement> {

  // TODO(b/535598134): this is a copy of org.jetbrains.kotlin.idea.base.psi.classIdIfNonLocal which can be removed
  //  once both Lint IDE and Lint CLI are running on IntelliJ 2026.2+.
  fun PsiClass.classIdIfNonLocal(): ClassId? {
    if (this is KtLightClass) {
      return this.kotlinOrigin?.getClassId()
    }
    val packageName = (containingFile as? PsiClassOwner)?.packageName ?: return null
    val packageFqName = FqName(packageName)

    val classesNames = parentsOfType<PsiClass>().map { it.name }.toList().asReversed()
    if (classesNames.any { it == null }) return null
    return ClassId(packageFqName, FqName(classesNames.joinToString(separator = ".")), false)
  }

  fun PsiClass.isCompanion(useSiteElement: PsiElement): Boolean {
    return analyzeFromPsi(useSiteElement) {
      val classId = classIdIfNonLocal() ?: return false
      val namedClass = findClass(classId) as? KaNamedClassSymbol ?: return false
      namedClass.classKind == KaClassKind.COMPANION_OBJECT
    }
  }

  fun PsiClass.mightBeCompanion(): Boolean {
    if (!this.hasModifierProperty(PsiModifier.STATIC)) return false
    if (!this.hasModifierProperty(PsiModifier.FINAL)) return false
    if (this.isInterface) return false
    if (this.isAnnotationType) return false
    if (this.isEnum) return false
    return true
  }

  fun PsiField.getSimilarConstFieldFrom(psiClass: PsiClass): PsiField? {
    val similarField = psiClass.findFieldByName(this.name, false) ?: return null
    if (similarField.type != this.type) return null
    if (!similarField.hasModifierProperty(PsiModifier.STATIC)) return null
    if (!similarField.hasModifierProperty(PsiModifier.FINAL)) return null
    return similarField
  }

  fun PsiField.getOuterDuplicate(useSiteElement: PsiElement): PsiField? {
    val innerClass = this.containingClass ?: return null
    val outerClass = innerClass.containingClass ?: return null
    if (!outerClass.isInterface) return null
    if (!innerClass.mightBeCompanion()) return null
    val field = this.getSimilarConstFieldFrom(outerClass) ?: return null
    // We assume this is somewhat expensive, so we do it last.
    if (!innerClass.isCompanion(useSiteElement)) return null
    return field
  }

  fun PsiField.getInnerDuplicate(useSiteElement: PsiElement): PsiField? {
    val outerClass = this.containingClass ?: return null
    if (!outerClass.isInterface) return null
    for (innerClass in outerClass.innerClasses) {
      if (!innerClass.mightBeCompanion()) continue
      val innerField = this.getSimilarConstFieldFrom(innerClass) ?: continue
      // We assume this is somewhat expensive, so we do it last.
      if (!innerClass.isCompanion(useSiteElement)) continue
      return innerField
    }
    return null
  }

  fun PsiField.mightBeDuplicatedInBytecode(): Boolean {
    // If we are dealing with Kotlin source then we don't need to worry about this
    // because the light classes use the Kotlin origin when checking equality.
    if (this !is PsiCompiledElement) return false
    // Constants will have these modifiers.
    if (!this.hasModifierProperty(PsiModifier.STATIC)) return false
    if (!this.hasModifierProperty(PsiModifier.FINAL)) return false
    val containingClass = this.containingClass ?: return false
    // Compiled Kotlin will have the Metadata annotation.
    return containingClass.hasAnnotation("kotlin.Metadata")
  }

  if (this == null) return emptySequence()
  val field = this as? PsiField ?: return sequenceOf(this)
  if (useSiteElement == null || !field.mightBeDuplicatedInBytecode()) return sequenceOf(this)
  // We don't know if this is the field in the companion object or the containing interface,
  // so we try both.
  return sequenceOf(this, field.getOuterDuplicate(useSiteElement), field.getInnerDuplicate(useSiteElement)).filterNotNull()
}
