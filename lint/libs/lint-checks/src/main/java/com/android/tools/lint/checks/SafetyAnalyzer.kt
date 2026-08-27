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

import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.isJava
import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.Multimap
import com.intellij.lang.jvm.JvmModifier
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiCatchSection
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiField
import com.intellij.psi.PsiForeachStatement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiLiteralValue
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypeParameterListOwner
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.impl.source.PsiClassReferenceType
import com.intellij.psi.util.InheritanceUtil.isInheritor
import com.intellij.psi.util.PsiTypesUtil
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSamConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.typeParameters
import org.jetbrains.kotlin.analysis.api.types.KaClassErrorType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDestructuringDeclarationEntry
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.getParentOfTypes
import org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnonymousClass
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UExpressionList
import org.jetbrains.uast.UField
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UResolvable
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UastFacade
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getContainingUMethod
import org.jetbrains.uast.getOutermostQualified
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.resolveToUElement
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastVisitor
import org.jetbrains.uast.withContainingElements

/**
 * Encapsulates common safety analysis logic.
 *
 * @property typeAnnos The annotations marking types that are safe.
 * @property typeAnnosWithContainParams The [typeAnnos] that have parameters specifying type parameters that must be safe for the whole
 *   class to be safe (e.g. `@Immutable(containerOf = ...)`).
 * @property strictTypeParamAnnos The annotations marking type parameters that must be filled with safe types in order to be instantiated.
 * @property containerTypeParamAnnos The annotations marking type parameters that must be filled with safe types in order for a class to be
 *   considered safe.
 * @property variableSuppressionAnnos The annotations that mark a variable as able to be ignored during safety analysis (e.g. `@GuardedBy`
 *   means we allow an unsafe variable to be used across boundaries).
 * @property knownSafeTypeMap A map of known safe types to the type parameters that must be filled with safe types.
 * @property unsafeAdj An adjective describing a violater of this check. Used for making reports.
 * @property safeAdj An adjective describing a type that satisfies this check. Used for making reports.
 * @property treatLambdasAsSafe Whether to allow lambdas to cross safety boundaries.
 * @property issue The issue under which to report a violation.
 */
internal class SafetyAnalyzer(
  private val typeAnnos: List<String>,
  private val typeAnnosWithContainParams: List<String>,
  private val strictTypeParamAnnos: List<String>,
  private val containerTypeParamAnnos: List<String>,
  private val variableSuppressionAnnos: List<String>,
  private val knownSafeTypeMap: Map<String, List<String>>,
  private val unsafeAdj: String,
  private val safeAdj: String,
  private val context: JavaContext,
  private val treatLambdasAsSafe: Boolean = true,
  private val issue: Issue,
) {

  private val psiClassReferenceTypeResolver = mutableMapOf<PsiClassReferenceType, PsiType>()

  /**
   * Gets a visitor that will check for any references to unsafe references that are declared outside the [declaration] scope.
   *
   * @param declaration the scope which unsafe references should not cross.
   * @param reportSubjectGenerator a function that provides a subject that explains why a particular declaration scope must be confirmed to
   *   be safe.
   */
  fun getLeakedReferenceVisitor(declaration: UElement, reportSubjectGenerator: (String) -> String): AbstractUastVisitor =
    LeakedReferenceVisitor(declaration, reportSubjectGenerator)

  /**
   * This visitor visits the references in a scope and makes sure they aren't unsafe references to elements from a wider scope. Example
   * scopes include classes, method definitions, and lambda expressions.
   */
  private inner class LeakedReferenceVisitor(val declaration: UElement, val reportSubjectGenerator: (String) -> String) :
    AbstractUastVisitor() {
    override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
      if (node.resolve() is PsiClass) return false

      visitUResolvable(node)
      return false
    }

    override fun visitCallExpression(node: UCallExpression): Boolean {
      if (node.resolve()?.isConstructor == true || node.isFunInterfaceConversion()) return false
      visitUResolvable(node)
      return false
    }

    override fun visitThisExpression(node: UThisExpression): Boolean {
      visitUResolvable(node)
      return false
    }

    private fun <T> visitUResolvable(node: T) where T : UResolvable, T : UExpression {
      val outermostReceiver = getFirstReceiverOfFullExpr(node)
      if (outermostReceiver != node) return

      if (node.withContainingElements.none { it is UMethod || it is ULambdaExpression }) return

      val psiResolvedNode = node.resolve()
      val uResolvedNode = (psiResolvedNode as? UElement) ?: node.resolveToUElement()

      if (uResolvedNode is ULocalVariable && uResolvedNode.isElvisExprTempVar()) return

      val nodeDeclaration =
        uResolvedNode?.getDeclaration() ?: (psiResolvedNode as? PsiParameter)?.declarationScope.toUElement() ?: uResolvedNode

      if (nodeDeclaration?.withContainingElements?.contains(declaration) == true) return

      if (uResolvedNode is UAnnotated && variableSuppressionAnnos.any { uResolvedNode.findAnnotation(it) != null }) {
        return
      }

      if (psiResolvedNode is PsiModifierListOwner && canIgnoreElement(psiResolvedNode)) return

      if (!reportOnThisReceiverSafety(node, uResolvedNode)) return

      if (uResolvedNode == null && nodeDeclaration == null && node !is UThisExpression) return

      if (node is UCallExpression) return

      if (uResolvedNode is UVariable && !uResolvedNode.isMultipleScopeReassignmentSafe()) {
        report(node, "${reportSubjectGenerator("'${node.asSourceString()}'")} is not final")
        return
      }

      val exprType = node.getExpressionType() ?: return
      if (nodeDeclaration != null) {
        populatePsiClassReferenceTypeResolver(nodeDeclaration, exprType)
      }

      reportOnUnsafeTypes(reportSubjectGenerator("'${node.asSourceString()}'"), exprType, node)
    }

    private fun UExpression.isImplicit(): Boolean {
      return this is UThisExpression && this::class.simpleName?.contains("Implicit", ignoreCase = true) == true
    }

    private fun getFirstReceiverOfFullExpr(expr: UExpression): UExpression {
      var outermostQualified: UExpression? = null
      var receiver: UExpression? = expr.getOutermostQualified() ?: expr
      while (receiver != null && !receiver.isImplicit()) {
        outermostQualified = receiver
        receiver =
          when (outermostQualified) {
            is UQualifiedReferenceExpression -> outermostQualified.receiver
            is UCallExpression -> outermostQualified.receiver
            else -> null
          }
      }
      return outermostQualified ?: expr
    }

    private fun reportOnThisReceiverSafety(node: UExpression, resolvedNode: UElement?): Boolean {
      val receiverScope = getReceiverScopeType(node, resolvedNode) ?: return true

      var thisScopeInDeclarationBlock = true
      val thisScope =
        if (node is UThisExpression && node.label != null) {
          val resolvedThisWithLabel = node.resolve().toUElement()
          thisScopeInDeclarationBlock = resolvedThisWithLabel?.withContainingElements?.contains(declaration) != false
          resolvedThisWithLabel
        } else {
          (node as? UElement)?.withContainingElements?.firstOrNull { containingElement ->
            if (containingElement is UAnnotated && containingElement.isValidThisScope(receiverScope)) {
              return@firstOrNull true
            }
            if (containingElement == declaration) thisScopeInDeclarationBlock = false
            return@firstOrNull false
          }
        }
      @Suppress("UElementAsPsi")
      if (thisScopeInDeclarationBlock || (thisScope is UClass && isMarkedSafe(thisScope))) {
        return false
      } else if (thisScope != null && (node is UCallExpression || resolvedNode is UMethod) && resolvedNode?.isDefaultProperty() != true) {
        val readableNodeName = if (node is UThisExpression) "'${node.asSourceString()}'" else "Implicit 'this'"
        reportOnUnsafeTypes(reportSubjectGenerator(readableNodeName), receiverScope, node)
        return false
      }

      return true
    }

    @Suppress("UElementAsPsi")
    private fun getReceiverScopeType(outermostReceiver: UExpression, resolvedReceiver: UElement?): PsiType? {
      return when {
        outermostReceiver is UCallExpression -> {
          resolvedReceiver?.takeIf { it.isExtensionFunction() }?.getContainingUClass()?.javaPsi?.type ?: outermostReceiver.receiverType
        }
        outermostReceiver is UThisExpression -> {
          outermostReceiver.getExpressionType()?.psiClass?.getPsiType(context)
        }
        resolvedReceiver is PsiParameter -> {
          if (resolvedReceiver.isConstructorParameterProperty()) {
            resolvedReceiver.getContainingUClass()?.javaPsi?.getPsiType(context)
          } else {
            null
          }
        }
        resolvedReceiver is PsiField -> resolvedReceiver.containingClass?.getPsiType(context)
        resolvedReceiver is PsiMethod -> resolvedReceiver.containingClass?.getPsiType(context)
        else -> null
      }
    }
  }

  private fun populatePsiClassReferenceTypeResolver(declaration: UElement, nodeExprType: PsiType) {
    val typeParams =
      generateSequence(declaration.getContainingUMethodInclusive()) { it.getContainingUMethod() }
        .flatMap { @Suppress("UElementAsPsi") it.typeParameters.asSequence() }
        .map { it.type }
        .toMutableSet()

    val containedTypeQueue = ArrayDeque<PsiType>()
    containedTypeQueue.add(nodeExprType)
    generateSequence {
        val currType = containedTypeQueue.removeFirstOrNull()
        if (currType is PsiClassType) containedTypeQueue.addAll(currType.parameters)
        if (currType is PsiTypeParameter) containedTypeQueue.addAll(currType.extendsListTypes)
        if (currType is PsiWildcardType) containedTypeQueue.add(currType.extendsBound)
        currType
      }
      .takeWhile { typeParams.isNotEmpty() }
      .forEach { paramType ->
        val methodType = typeParams.firstOrNull { methodType -> methodType.canonicalText == paramType.canonicalText }
        if (methodType != null && paramType is PsiClassReferenceType) {
          psiClassReferenceTypeResolver[paramType] = methodType
          typeParams.remove(methodType)
        }
      }
  }

  private fun UElement.getContainingUMethodInclusive(): UMethod? {
    return if (this is UMethod) this else getContainingUMethod()
  }

  private fun UElement.getDeclaration(): UElement? {
    val ktPsi = sourcePsi
    return if (ktPsi != null) {
      ktPsi
        .getParentOfTypes(
          true,
          KtCatchClause::class.java,
          KtLightMethod::class.java,
          KtFunction::class.java,
          KtLambdaExpression::class.java,
          KtForExpression::class.java,
        )
        .toUElement()
    } else {
      javaPsi
        ?.getParentOfTypes(
          true,
          PsiLambdaExpression::class.java,
          PsiCatchSection::class.java,
          PsiMethod::class.java,
          PsiForeachStatement::class.java,
        )
        .toUElement()
    }
  }

  fun reportOnUnsafeTypes(
    subject: String,
    type: PsiType,
    nodeForReport: UElement,
    typeStack: List<PsiSubstitutor> = emptyList(),
    includeStrictTypeParamAnnos: Boolean = true,
  ) {
    val resolvedCheckedType = type.resolveFromTypeStack(typeStack)
    if (resolvedCheckedType is PsiArrayType) {
      report(nodeForReport, "$subject is an array which is $unsafeAdj")
    }
    val checkedClass = resolvedCheckedType.psiClass ?: return

    if (checkedClass is PsiTypeParameter) {
      if (!checkedClass.isContainedType() && !resolvedCheckedType.isSafelyBounded(typeStack)) {
        report(nodeForReport, "$subject is of generic " + "type ${type.presentableText} which is not guaranteed to be $safeAdj")
      }
      return
    }

    if (!isMarkedSafe(resolvedCheckedType)) {
      val typeNamePhrase =
        if (isInheritor(checkedClass, "kotlin.Function")) {
          "a function type"
        } else {
          "of type ${checkedClass.name}"
        }
      report(nodeForReport, "$subject is $typeNamePhrase which is $unsafeAdj")
      return
    }

    val mutableContainedTypes = getUnsafeContainedTypes(type, typeStack, includeStrictParamAnnos = includeStrictTypeParamAnnos)
    if (mutableContainedTypes.isEmpty()) return

    report(
      nodeForReport,
      "$subject is a container for the $unsafeAdj type(s) " + mutableContainedTypes.joinToString(transform = PsiType::getPresentableText),
    )
  }

  private fun PsiType.isSafelyBounded(typeStack: List<PsiSubstitutor>, visitedBounds: Set<PsiTypeParameter> = emptySet()): Boolean {
    val resolvedType = resolveFromTypeStack(typeStack)

    val resolvedClass = resolvedType.psiClass ?: return false
    if (resolvedClass is PsiTypeParameter) {
      if (resolvedClass in visitedBounds) return false
      if (resolvedClass.isContainedType()) return true
      val boundsList = resolvedClass.bounds.mapNotNull { it as? PsiType }
      if (boundsList.isEmpty()) return false
      return boundsList.any { getUnsafeContainedTypes(it, typeStack, visitedBounds + resolvedClass).isEmpty() }
    }

    if (!isMarkedSafe(resolvedType)) return false

    return getUnsafeContainedTypes(resolvedType, typeStack).isEmpty()
  }

  fun getUnsafeContainedTypes(
    type: PsiType,
    typeStack: List<PsiSubstitutor> = emptyList(),
    visitedBounds: Set<PsiTypeParameter> = emptySet(),
    includeStrictParamAnnos: Boolean = true,
  ): List<PsiType> {
    val resolvedClassReferenceType = psiClassReferenceTypeResolver[type]
    return when {
      type is PsiPrimitiveType -> emptyList()
      type is PsiArrayType -> listOf(type)
      type.psiClass?.qualifiedName == "java.lang.Object" -> listOf(type)
      type is PsiClassReferenceType && resolvedClassReferenceType != null -> {
        getUnsafeContainedTypes(resolvedClassReferenceType, typeStack, visitedBounds, includeStrictParamAnnos)
      }
      type is PsiWildcardType -> {
        if (type.extendsBound.psiClass?.qualifiedName == "java.lang.Object") return listOf(type)
        getUnsafeContainedTypes(type.extendsBound, typeStack, visitedBounds, includeStrictParamAnnos)
      }
      type.psiClass is PsiTypeParameter -> {
        getPsiTypeParameterUnsafeContainedTypes(type, typeStack, visitedBounds)
      }
      !isMarkedSafe(type) -> listOf(type)
      else -> {
        getContainedTypes(type, includeStrictParamAnnos)
          .map { resolvedType -> getUnsafeContainedTypes(resolvedType, typeStack, visitedBounds) }
          .flatten()
      }
    }
  }

  private fun getContainedTypes(type: PsiType, includeStrictParamAnnos: Boolean = true): List<PsiType> {
    val clazz = type.psiClass ?: return emptyList()
    return when {
      clazz.hasAnyDirectOrInheritedAnnos(typeAnnos) -> {
        clazz.typeParameters.filter { it.isContainedType(includeStrictParamAnnos) }.mapNotNull { type.substitutor?.substitute(it) }
      }
      knownSafeTypeMap.containsKey(clazz.qualifiedName) -> {
        val containerOf = clazz.qualifiedName?.let { knownSafeTypeMap[it] }!!
        val typeParams = clazz.typeParameters
        typeParams.filter { containerOf.contains(it.name) }.mapNotNull { type.substitutor?.substitute(it) }
      }
      else -> emptyList()
    }
  }

  private fun getPsiTypeParameterUnsafeContainedTypes(
    type: PsiType,
    typeStack: List<PsiSubstitutor>,
    visitedBounds: Set<PsiTypeParameter>,
  ): List<PsiType> {
    val resolvedType = type.resolveFromTypeStack(typeStack)
    val resolvedClass = resolvedType.psiClass
    return when {
      resolvedClass !is PsiTypeParameter -> {
        getUnsafeContainedTypes(resolvedType, typeStack, visitedBounds)
      }
      resolvedClass.isContainedType() || resolvedType.isSafelyBounded(typeStack, visitedBounds) -> {
        emptyList()
      }
      else -> listOf(resolvedType)
    }
  }

  fun isMarkedSafe(type: PsiType): Boolean {
    if (type is PsiPrimitiveType) return true
    return isMarkedSafe(type.psiClass ?: return false)
  }

  fun isMarkedSafe(clazz: PsiClass): Boolean {
    return clazz.isAnnotationType ||
      isInheritor(clazz, "java.lang.annotation.Annotation") ||
      clazz.isProto2ImmutableMessageClass() ||
      clazz.hasAnyDirectOrInheritedAnnos(typeAnnos) ||
      clazz.isProtoEnum() ||
      isSafeAnonymousClass(clazz) ||
      knownSafeTypeMap.containsKey(clazz.qualifiedName) ||
      clazz.isEnum ||
      (treatLambdasAsSafe && isInheritor(clazz, "kotlin.Function"))
  }

  private fun isSafeAnonymousClass(clazz: PsiClass): Boolean {
    if (clazz.name != null) return false
    return clazz.interfaces.any { isMarkedSafe(it) }
  }

  @Suppress("ExternalAnnotations") // external suppressions not supported
  fun canIgnoreElement(element: PsiModifierListOwner): Boolean {
    return element !is PsiClass &&
      (element.hasModifier(JvmModifier.STATIC) && (element as? UField)?.getContainingUClass() !is UAnonymousClass) ||
      element.annotations.any { elementAnno ->
        variableSuppressionAnnos.any { suppressAnno ->
          elementAnno.qualifiedName == suppressAnno || elementAnno.qualifiedName?.endsWith(".$suppressAnno") == true
        }
      } ||
      element.hasSuppressionAnnotation(issue.id)
  }

  @Suppress("ExternalAnnotations") // external suppressions not supported
  private fun PsiModifierListOwner.hasSuppressionAnnotation(checkId: String): Boolean {
    for (anno in annotations) {
      val name = anno.qualifiedName?.substringAfterLast('.') ?: continue
      if (name == "SuppressWarnings" || name == "Suppress") {
        val values = anno.getStringArrayAttribute("value") + anno.getStringArrayAttribute("names")
        if (checkId in values) return true
      }
    }
    return false
  }

  private fun PsiTypeParameter.isContainedType(includeStrictParamAnnos: Boolean = true): Boolean {
    return if (includeStrictParamAnnos) {
      this.isContainedType(strictTypeParamAnnos + containerTypeParamAnnos, typeAnnosWithContainParams)
    } else {
      this.isContainedType(containerTypeParamAnnos, typeAnnosWithContainParams)
    }
  }

  private fun PsiType.resolveFromTypeStack(typeStack: List<PsiSubstitutor>): PsiType {
    if (psiClass !is PsiTypeParameter) return this
    return typeStack.foldRight(this) { containingSubstitutor, type ->
      val typeClass = type.psiClass
      if (typeClass !is PsiTypeParameter) return@resolveFromTypeStack type
      containingSubstitutor.substitute(typeClass) ?: type
    }
  }

  private fun report(scope: UElement, message: String) {
    context.report(Incident(issue, scope, context.getLocation(scope), message))
  }

  companion object {
    private const val MESSAGE_TYPE: String = "com.google.protobuf.MessageLite"
    private const val MUTABLE_MESSAGE_TYPE: String = "com.google.protobuf.MutableMessageLite"
    private const val PROTOCOL_MESSAGE_TYPE: String = "com.google.io.protocol.ProtocolMessage"
    private const val PROTO_ENUM_TYPE: String = "com.google.protobuf.Internal.EnumLite"

    internal fun PsiClass.isProto2ImmutableMessageClass(): Boolean {
      return isProto2MessageClass() && !isProto2MutableMessageClass()
    }

    internal fun PsiClass.isProtoEnum(): Boolean {
      return isInheritor(this, PROTO_ENUM_TYPE)
    }

    private fun PsiClass.isProto2MessageClass(): Boolean {
      return isInheritor(this, MESSAGE_TYPE) && !isInheritor(this, PROTOCOL_MESSAGE_TYPE)
    }

    private fun PsiClass.isProto2MutableMessageClass(): Boolean {
      return isInheritor(this, MUTABLE_MESSAGE_TYPE) && !isInheritor(this, PROTOCOL_MESSAGE_TYPE)
    }

    private fun UElement.isDefaultProperty(): Boolean {
      return when {
        this is UReferenceExpression && sourcePsi is KtProperty -> {
          val resolved = resolve()
          resolved is KtLightMethod && UastFacade.getMethodBody(resolved) == null
        }
        this is UMethod && sourcePsi is KtProperty -> {
          @Suppress("UElementAsPsi")
          UastFacade.getMethodBody(this) == null
        }
        this.isConstructorParameterProperty() -> true
        else -> false
      }
    }

    private fun PsiTypeParameter.isContainedType(typeParamAnnos: List<String>, typeAnnosWithContainArgs: List<String>): Boolean {
      val declarationElement = owner ?: return false

      return hasAnyOfAnnos(typeParamAnnos) ||
        declarationElement.getTypeParamsWithAnnos(typeParamAnnos)?.containsKey(this) == true ||
        typeAnnosWithContainArgs.any { anno ->
          declarationElement.getDirectOrInheritedAnno(anno)?.getStringArrayAttribute("containerOf")?.contains(name) == true
        }
    }

    private fun ULocalVariable.isElvisExprTempVar(): Boolean {
      val parentExpressionPsi = getParentOfType(UExpressionList::class.java)?.sourcePsi ?: return false
      return (parentExpressionPsi as? KtBinaryExpression)?.operationToken == KtTokens.ELVIS
    }
  }
}

internal fun UVariable.isMultipleScopeReassignmentSafe(): Boolean {
  return when (this) {
    is PsiField -> {
      hasModifier(JvmModifier.FINAL) || hasAnnotation(AbstractSafetyDetector.LAZY_INIT_ANNO)
    }
    is PsiLocalVariable -> {
      if (isJava(lang)) return true
      if (hasModifier(JvmModifier.FINAL)) return true
      val uThis = (this as? UElement) ?: toUElement() ?: return false
      uThis.sourcePsi is KtDestructuringDeclarationEntry || (uThis.sourcePsi as? KtProperty)?.isVar == false
    }
    is UParameter -> (this.sourcePsi as? KtParameter)?.isMutable != true
    else -> true
  }
}

internal val PsiType.psiClass: PsiClass?
  get() = PsiTypesUtil.getPsiClass(this)

internal val PsiClass.type: PsiType
  get() = PsiTypesUtil.getClassType(this)

@Suppress("ExternalAnnotations") // TODO: query external annotations
internal fun PsiModifierListOwner.hasAnnotation(qualifiedName: String): Boolean {
  return annotations.any {
    it.hasQualifiedName(qualifiedName) || it.qualifiedName == qualifiedName || it.qualifiedName?.endsWith(".$qualifiedName") == true
  }
}

@Suppress("ExternalAnnotations") // TODO: query external annotations
internal fun PsiModifierListOwner.getDirectOrInheritedAnno(qualifiedName: String): PsiAnnotation? {
  if (this !is PsiClass) {
    return annotations.firstOrNull {
      it.hasQualifiedName(qualifiedName) || it.qualifiedName == qualifiedName || it.qualifiedName?.endsWith(".$qualifiedName") == true
    }
  }
  var currentClass: PsiClass? = this
  do {
    val anno =
      currentClass?.annotations?.firstOrNull {
        it.hasQualifiedName(qualifiedName) || it.qualifiedName == qualifiedName || it.qualifiedName?.endsWith(".$qualifiedName") == true
      }
    if (anno != null) return anno
    currentClass = currentClass?.superClass
  } while (currentClass != null)
  return null
}

internal fun PsiModifierListOwner.hasDirectOrInheritedAnno(qualifiedName: String): Boolean {
  return getDirectOrInheritedAnno(qualifiedName) != null
}

internal fun PsiModifierListOwner.hasAnyOfAnnos(annos: List<String>): Boolean {
  return annos.any { hasAnnotation(it) }
}

internal fun PsiClass.hasAnyDirectOrInheritedAnnos(annos: List<String>): Boolean {
  return annos.any { hasDirectOrInheritedAnno(it) }
}

internal val PsiType.substitutor: PsiSubstitutor?
  get() = (this as? PsiClassType)?.resolveGenerics()?.substitutor

internal fun PsiClass.getPsiType(context: JavaContext): PsiType? {
  return context.evaluator.getClassType(this)
}

internal fun PsiAnnotation.getStringArrayAttribute(name: String): List<String> {
  val value = findAttributeValue(name) ?: return emptyList()
  if (value is PsiArrayInitializerMemberValue) {
    return value.initializers.mapNotNull {
      (it as? PsiLiteralValue)?.value as? String ?: (it.toUElement() as? UExpression)?.evaluate() as? String
    }
  }
  if (value is PsiLiteralValue) {
    val str = value.value as? String
    return if (str != null) listOf(str) else emptyList()
  }
  val evaluated = (toUElement() as? org.jetbrains.uast.UAnnotation)?.findAttributeValue(name)?.evaluate()
  if (evaluated is Array<*>) {
    return evaluated.filterIsInstance<String>()
  }
  if (evaluated is Collection<*>) {
    return evaluated.filterIsInstance<String>()
  }
  if (evaluated is String) {
    return listOf(evaluated)
  }
  return emptyList()
}

internal fun UElement.isConstructorParameterProperty(): Boolean {
  return (sourcePsi as? KtParameter)?.hasValOrVar() == true
}

internal fun UElement.isExtensionFunction(): Boolean {
  return (sourcePsi as? KtFunction)?.isExtensionDeclaration() == true
}

internal fun ULambdaExpression.isExtensionLambda(): Boolean {
  val ktSource = sourcePsi as? KtLambdaExpression ?: return false
  return analyze(ktSource) {
    val lambdaType = ktSource.expressionType
    lambdaType is KaFunctionType && lambdaType.hasReceiver
  }
}

internal fun UCallExpression.isFunInterfaceConversion(): Boolean {
  val ktElement = (sourcePsi as? KtElement) ?: return false
  return analyze(ktElement) {
    val functionLikeSymbol = getFunctionLikeSymbol(ktElement)
    functionLikeSymbol is KaSamConstructorSymbol
  }
}

internal fun UCallExpression.callsExtensionFunction(): Boolean {
  val ktElement = this.sourcePsi as? KtElement ?: return false
  return analyze(ktElement) { isExtensionFunctionCall(ktElement) }
}

internal fun UAnnotated.isValidThisScope(receiverType: PsiType): Boolean {
  fun ULambdaExpression.isValidLambdaScope(receiverType: PsiType): Boolean {
    val subs = (getExpressionType() as? PsiClassType)?.resolveGenerics()?.substitutor
    val actualReceiverType = getExpressionType()?.psiClass?.typeParameters?.firstOrNull()?.let { subs?.substitute(it) }
    return isExtensionLambda() &&
      if (actualReceiverType is PsiWildcardType) {
        actualReceiverType.isAssignableFrom(receiverType)
      } else {
        receiverType == actualReceiverType
      }
  }

  return when {
    this is ULambdaExpression -> isValidLambdaScope(receiverType)
    this is UMethod && isExtensionFunction() -> {
      @Suppress("UElementAsPsi")
      this.parameterList.getParameter(0)?.type == receiverType
    }
    this is UClass -> javaPsi == receiverType.psiClass
    else -> false
  }
}

@OptIn(KaExperimentalApi::class)
internal fun PsiTypeParameterListOwner.getTypeParamsWithAnnos(annos: List<String>): Multimap<PsiTypeParameter, String>? {
  val psiTypeParameters = this.typeParameters.toList()
  val ktDeclaration = this.toUElement()?.sourcePsi as? KtDeclaration
  return when {
    this is KtLightClass -> {
      val uClass = this.toUElement() as? UClass ?: return null
      val psi = uClass.sourcePsi as? KtDeclaration ?: return null
      analyze(psi) {
        val classSymbol = psi.symbol as? KaClassLikeSymbol ?: return null
        buildMapWithAnnotatedTypeParams(psiTypeParameters, classSymbol.typeParameters, annos)
      }
    }
    ktDeclaration != null -> {
      analyze(ktDeclaration) {
        val functionSymbol = ktDeclaration.symbol as? KaFunctionSymbol ?: return null
        buildMapWithAnnotatedTypeParams(psiTypeParameters, functionSymbol.typeParameters, annos)
      }
    }
    else -> {
      val builder = ImmutableMultimap.builder<PsiTypeParameter, String>()
      psiTypeParameters.forEach { psiTypeParam ->
        val presentAnnos = annos.filter { psiTypeParam.hasAnnotation(it) }
        if (presentAnnos.isNotEmpty()) {
          builder.putAll(psiTypeParam, presentAnnos)
        }
      }
      builder.build()
    }
  }
}

@OptIn(KaExperimentalApi::class)
internal fun UCallExpression.getTypeParamsWithAnnos(annos: List<String>): Multimap<PsiTypeParameter, String>? {
  val kt = this.sourcePsi as? KtCallExpression ?: return null
  val callee = resolve() ?: return null
  return analyze(kt) {
    val ktTypeParameters = (getFunctionLikeSymbol(kt) as? KaFunctionSymbol)?.typeParameters.orEmpty()
    buildMapWithAnnotatedTypeParams(callee.typeParameters.toList(), ktTypeParameters, annos)
  }
}

@OptIn(KaExperimentalApi::class)
private fun KaSession.buildMapWithAnnotatedTypeParams(
  psiTypeParams: List<PsiTypeParameter>,
  typeParamSymbols: List<KaTypeParameterSymbol>,
  annos: List<String>,
): Multimap<PsiTypeParameter, String> {
  val builder = ImmutableMultimap.builder<PsiTypeParameter, String>()
  typeParamSymbols.zip(psiTypeParams).forEach { (typeParam, psiTypeParam) ->
    val presentAnnos = annos.filter { hasAnnotation(typeParam, it) }
    if (presentAnnos.isNotEmpty()) {
      builder.putAll(psiTypeParam, presentAnnos)
    }
  }
  return builder.build()
}

@OptIn(KaExperimentalApi::class)
private fun KaSession.hasAnnotation(ktAnnotated: KaAnnotated, annoName: String): Boolean {
  return ktAnnotated.annotations.any { anno ->
    val classId = anno.classId ?: return@any false
    val fqName = classId.asSingleFqName().asString()
    fqName == annoName || fqName.endsWith(".$annoName") || classId.shortClassName.asString() == annoName
  }
}

@OptIn(KaExperimentalApi::class)
internal fun ULambdaExpression.getFunctionalInterfaceType(): PsiType? {
  if (isJava(lang)) {
    return functionalInterfaceType
  }
  val ktExpression = sourcePsi as? KtExpression ?: return functionalInterfaceType
  return analyze(ktExpression) {
    val samType =
      ktExpression.expectedType?.takeIf { it !is KaClassErrorType && it.isFunctionalInterface }?.lowerBoundIfFlexible()
        ?: return@analyze functionalInterfaceType
    val psiTypeParent =
      this@getFunctionalInterfaceType.getParentOfType(UDeclaration::class.java, strict = false)?.javaPsi as? PsiModifierListOwner
        ?: ktExpression
    try {
      (samType.asPsiType(psiTypeParent, allowErrorTypes = true) as? PsiClassType) ?: functionalInterfaceType
    } catch (e: IllegalArgumentException) {
      functionalInterfaceType
    }
  }
}
