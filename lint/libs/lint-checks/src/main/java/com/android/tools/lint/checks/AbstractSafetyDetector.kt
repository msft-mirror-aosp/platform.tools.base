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
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.PsiFormatUtil
import com.intellij.psi.util.PsiFormatUtilBase
import java.util.Locale
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.elements.isGetter
import org.jetbrains.kotlin.util.removeSuffixIfPresent
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.util.isConstructorCall

/**
 * Analyzes types for deep safety.
 *
 * @property typeAnnos The annotations marking types that are safe.
 * @property typeAnnosWithContainParams The [typeAnnos] that have parameters specifying type parameters that must be safe for the whole
 *   class to be safe (e.g. `@Immutable(containerOf = ...)`).
 * @property strictTypeParamAnnos The annotations marking type parameters that must be filled with safe types in order to be instantiated.
 * @property containerTypeParamAnnos The annotations marking type parameters that must be filled with safe types in order for a class to be
 *   considered safe.
 * @property strictTypeParamAnnosToCheck The strict annotations that this check should enforce. This check needs to know all of the
 *   [strictTypeParamAnnos], but will only report when these ones are violated to prevent duplicate reports.
 * @property variableSuppressionAnnoNames Annotations on variables that suppress safety checking.
 * @property knownSafeTypeMap A map of known safe types to the type parameters that must be filled with safe types.
 * @property unsafeAdj An adjective describing a violater of this check. Used for making reports.
 * @property safeAdj An adjective describing a type that satisfies this check. Used for making reports.
 * @property reasonToExpectSafety A verb phrase to explain why safety is expected. Used for making reports.
 */
abstract class AbstractSafetyDetector(
  protected val typeAnnos: List<String>,
  protected val typeAnnosWithContainParams: List<String>,
  protected val strictTypeParamAnnos: List<String>,
  protected val containerTypeParamAnnos: List<String>,
  private val strictTypeParamAnnosToCheck: List<String>,
  private val variableSuppressionAnnoNames: List<String>,
  protected val knownSafeTypeMap: Map<String, List<String>>,
  private val unsafeAdj: String,
  private val safeAdj: String,
  private val reasonToExpectSafety: String = "is annotated as $safeAdj",
) : Detector(), SourceCodeScanner {

  abstract val issue: Issue

  override fun getApplicableUastTypes(): List<Class<out UElement>> =
    listOf(UClass::class.java, UCallExpression::class.java, ULambdaExpression::class.java)

  /** Used to associate a type parameter with the provided type argument and any present annotations. */
  private data class TypeArgInfo(val typeParam: PsiTypeParameter, val typeArg: PsiType, val presentAnnos: Collection<String>)

  /**
   * This UastHandler can be thought of as having two very distinct parts:
   *
   * a. The first part ensures that a class annotated with [typeAnnos] looks correct -- e.g. that for an `@Immutable` class, that all its
   * fields are either immutable or suppressed.
   *
   * b. The second part ensures that parameterized classes and methods annotated with [strictTypeParamAnnos] are constructed with valid type
   * parameters. E.g. that an `@Immutable` class that is declared a `containerOf = "T"` is constructed with an immutable `<T>` (and
   * analogously for `@ThreadSafe` classes with params that are annotated with `@ThreadSafe.Element`).
   */
  override fun createUastHandler(context: JavaContext): UElementHandler? {
    return object : UElementHandler() {
      private val safetyAnalyzer =
        SafetyAnalyzer(
          typeAnnos,
          typeAnnosWithContainParams,
          strictTypeParamAnnos,
          containerTypeParamAnnos,
          variableSuppressionAnnoNames,
          knownSafeTypeMap,
          unsafeAdj,
          safeAdj,
          context,
          treatLambdasAsSafe = true,
          issue,
        )

      /** This method deals with case "a" as documented in [createUastHandler]. */
      override fun visitClass(node: UClass) {
        if (shouldAnalyzeClass(node, context)) {
          checkClass(node)
        }
      }

      /** This method deals with case "b" as documented in [createUastHandler]. */
      override fun visitCallExpression(node: UCallExpression) {
        val strictAnnotatedTypes: List<TypeArgInfo>? =
          // Check if class TypeParameter annotations are respected.
          // Constructor calls must be treated differently from normal method calls because type
          // parameters can be inferred from the 'return type' of a constructor call.
          if (node.isConstructorCall()) {
            if (node.returnType?.psiClass?.hasAnyOfAnnos(typeAnnos) != true) return

            val constructedClass = node.returnType?.psiClass ?: return
            constructedClass.getTypeParamsWithAnnos(strictTypeParamAnnosToCheck)?.asMap()?.mapNotNull { (typeParam, presentAnnos) ->
              node.returnType?.substitutor?.substitute(typeParam)?.let { typeArg -> TypeArgInfo(typeParam, typeArg, presentAnnos) }
            }
          } else { // Check if method TypeParameter annotations are respected
            val methodStrictTypeParams = node.getTypeParamsWithAnnos(strictTypeParamAnnosToCheck) ?: return
            if (methodStrictTypeParams.isEmpty) return
            node.getAllTypeArgs(strictTypeParamAnnosToCheck).map { (typeParam, typeArg) ->
              val presentAnnos =
                methodStrictTypeParams.asMap().filterKeys { key -> key.manager.areElementsEquivalent(key, typeParam) }.values.flatten()
              TypeArgInfo(typeParam, typeArg, presentAnnos)
            }
          }

        strictAnnotatedTypes?.forEach { (typeParam, typeArg, presentAnnos) ->
          val unsafeTypes = safetyAnalyzer.getUnsafeContainedTypes(typeArg)
          if (unsafeTypes.isNotEmpty()) {
            @Suppress("LintImplTextFormat")
            context.report(
              issue,
              node,
              context.getLocation(node as UElement),
              "Type parameter ${typeParam.name} is annotated with " +
                "${presentAnnos.joinToString { it.substringAfterLast('.') }} but " +
                "contains the $unsafeAdj type(s) " +
                unsafeTypes.joinToString { unsafeType -> unsafeType.presentableText },
            )
          }
        }
      }

      /**
       * For every type parameter in the called method that has one of [strictTypeParamAnnos], maps it to the associated type argument from
       * the call expression.
       *
       * This is necessary because [UCallExpression.typeArguments] only provides the explicit type arguments. If there are inferred type
       * arguments, we instead need to use this method to match the generic types to the concrete types they are bound to.
       */
      private fun UCallExpression.getAllTypeArgs(strictTypeParamAnnos: List<String>): Map<PsiTypeParameter, PsiType> {
        val psiMethod = resolve() ?: return emptyMap()
        val methodStrictTypeParams = getTypeParamsWithAnnos(strictTypeParamAnnos) ?: return emptyMap()
        if (typeArgumentCount == psiMethod.typeParameters.size) {
          return methodStrictTypeParams.keySet().associateWith { typeArguments[it.index] }
        }

        val argMapping = context.evaluator.computeArgumentMapping(this, psiMethod).toMutableMap()
        // computeArgumentMapping ignores receivers for extension functions, even though they are
        // treated as parameters in many cases
        val receiver = receiver
        val firstParam = psiMethod.parameterList.getParameter(0)
        if (this.callsExtensionFunction() && receiver != null && firstParam != null) {
          argMapping[receiver] = firstParam
        }
        val outMap = mutableMapOf<PsiTypeParameter, PsiType>()
        for ((arg, param) in argMapping) {
          findTypeParameter(arg, param, methodStrictTypeParams.keySet().toList(), outMap)
        }
        return outMap.toMap()
      }

      override fun visitLambdaExpression(node: ULambdaExpression) {
        fun PsiClass.isFunctionType(): Boolean {
          return qualifiedName?.startsWith("kotlin.jvm.functions.Function") == true
        }
        fun PsiClass.lambdaIsMarkedSafe(): Boolean {
          return isSafeAnonType() || hasAnyDirectOrInheritedAnnos(typeAnnos)
        }
        fun UClass.shouldAnalyzeLambdaClass(): Boolean {
          // Normally known safe types are simply trusted without being analyzed. In the case of
          // lambdas the implementation of the interface is no longer trusted as it can be anything.
          return shouldAnalyzeClass(this, context) || knownSafeTypeMap.containsKey(qualifiedName)
        }

        val lambdaType = node.getFunctionalInterfaceType()

        val lambdaInterface = lambdaType?.psiClass ?: return
        val lambdaUClass = (lambdaInterface as? UClass) ?: lambdaInterface.toUElement() as? UClass

        // Early return in the standard case of a Kotlin function type
        if (
          lambdaInterface.isFunctionType() ||
            !lambdaInterface.lambdaIsMarkedSafe() ||
            lambdaUClass?.shouldAnalyzeLambdaClass() != true ||
            // If a contained type is not safe the whole type does not have to be safe, so we don't
            // have to check it
            safetyAnalyzer.getUnsafeContainedTypes(lambdaType).isNotEmpty()
        ) {
          return
        }
        node.body.accept(
          safetyAnalyzer.getLeakedReferenceVisitor(node) { nodeIdentifier ->
            "$nodeIdentifier is " + "captured by a lambda expression that is passed as $safeAdj type(s) " + "${lambdaInterface.name} but"
          }
        )
      }

      private fun PsiClass.isSafeAnonType(): Boolean {
        return hasAnyDirectOrInheritedAnnos(typeAnnos) || knownSafeTypeMap.containsKey(qualifiedName)
      }

      /** Analyze the given UClass for safety. */
      @Suppress("UElementAsPsi")
      private fun checkClass(uClass: UClass) {
        uClass.fields.forEach { field ->
          if (field is PsiEnumConstant) {
            field.initializingClass?.fields?.forEach { checkFieldSafety(it, uClass) }
          } else {
            checkFieldSafety(field, uClass, emptyList())
          }
        }

        val isAnonymousClass = uClass.name == null

        if (isAnonymousClass && safetyAnalyzer.getUnsafeContainedTypes(uClass.superTypes.last()).isNotEmpty()) {
          return
        }

        val checkedTypes = mutableSetOf<PsiClassType>()
        uClass.superTypes.forEach { checkSuperTypesSafety(it, uClass, checkedTypes) }

        // Only check anonymous or inner classes for leaked unsafe variables.
        // TODO(b/205190069): Handle method references
        if (!isAnonymousClass && uClass.javaPsi.containingClass == null) return

        uClass.accept(
          safetyAnalyzer.getLeakedReferenceVisitor(uClass) { nodeIdentifier ->
            val safeSupersString =
              (uClass.supers + uClass)
                .filter { (it.hasAnyDirectOrInheritedAnnos(typeAnnos) || it.isEnum) && it.name != null }
                .joinToString { it.name!! }
            "The wider-scope variable $nodeIdentifier is captured by a class definition with $safeAdj type(s) $safeSupersString and"
          }
        )
      }

      /**
       * Checks an instance PsiField for safety, and if it is not safe reports it.
       *
       * @param field The field to be checked for safety.
       * @param checkedNode The node originally being checked for safety.
       * @param typeStack A list of checked types, starting with the type originally being checked, and ending with the type this field is a
       *   member of. Used for resolving generics.
       */
      protected fun checkFieldSafety(field: PsiField, checkedNode: UClass, typeStack: List<PsiSubstitutor> = emptyList()) {
        if (safetyAnalyzer.canIgnoreElement(field)) return
        val uVarField = (field as? UVariable) ?: (field.toUElement() as? UVariable)
        if (uVarField?.isMultipleScopeReassignmentSafe() == false) {
          val reportNode = getNodeForReport(field, checkedNode)
          context.report(
            issue,
            reportNode,
            context.getLocation(reportNode as UElement),
            "${checkedNode.reportSubject} $reasonToExpectSafety, " + "but field ${field.classQualifiedName} is not final",
          )
        }

        var fieldType = field.type

        // Delegated properties do not appear as fields.
        // Only related generated fields appear, with names in the format '<fieldName>$delegate'.
        if (field.name.endsWith("\$delegate")) {
          // If this is a delegated field, we want to make sure the delegate implementation is safe
          // Note this is not foolproof: for example, `lazy` can be called without synchronization
          // (via LazyThreadSafetyMode enum), and this check will still judge it safe
          reportUnsafeFieldTypes(fieldType, field, checkedNode, typeStack, isDelegateType = true)

          // We also want to make sure the return type of the property's getter is safe,
          // as this is the actual type of the property.
          fieldType = field.getDelegatedPropertyType(checkedNode) ?: field.type
        }

        if (fieldType is PsiPrimitiveType) return

        reportUnsafeFieldTypes(fieldType, field, checkedNode, typeStack, isDelegateType = false)
      }

      /** Finds any safety issues in the fieldType and reports them. */
      private fun reportUnsafeFieldTypes(
        fieldType: PsiType,
        field: PsiField,
        checkedNode: UClass,
        typeStack: List<PsiSubstitutor>,
        isDelegateType: Boolean,
      ) {
        val fieldName = field.classQualifiedName.removeSuffixIfPresent("\$delegate")
        val nodeForReport = getNodeForReport(field, checkedNode)

        // TypeParameters with wildcards
        safetyAnalyzer.reportOnUnsafeTypes(
          "${checkedNode.reportSubject} $reasonToExpectSafety, but " + "${if (isDelegateType) "the delegate for " else ""}field $fieldName",
          fieldType,
          nodeForReport,
          typeStack,
          includeStrictTypeParamAnnos = false,
        )
      }

      /**
       * Checks that the type is safe, and if it isn't reports it. Recursively checks the supertypes of the given type until a type marked
       * safe is found or no more types are left.
       *
       * @param type The type to check for safety.
       * @param checkedNode The node originally being checked for safety.
       */
      protected fun checkSuperTypesSafety(type: PsiClassType, checkedNode: UClass, alreadyCheckedTypes: MutableSet<PsiClassType>) {
        fun checkSuperTypesSafetyHelper(type: PsiClassType, checkedNode: UClass, typeStack: List<PsiSubstitutor>) {
          // Should be safe to check each type once, even if the `typeStack` is different, since
          // there can only be one substitution per type. Note that if a type has been checked then
          // its supertypes must also have been checked. This avoids expensive redundant checks
          // or even infinite recursion in case of (illegal) cyclicly declared types.
          if (!alreadyCheckedTypes.add(type)) return

          val increasedTypeStack = type.substitutor?.let { typeStack + it } ?: typeStack
          if (safetyAnalyzer.isMarkedSafe(type)) {
            val mutableTypeParams = safetyAnalyzer.getUnsafeContainedTypes(type, increasedTypeStack)
            if (mutableTypeParams.isNotEmpty()) {
              val reportNode = checkedNode.uastAnchor ?: checkedNode
              @Suppress("LintImplTextFormat")
              context.report(
                issue,
                reportNode,
                context.getLocation(reportNode as UElement),
                "Class $reasonToExpectSafety, but the super type ${type.presentableText} " +
                  "is a container for the $unsafeAdj type(s) " +
                  mutableTypeParams.joinToString(transform = PsiType::getPresentableText),
              )
            }
            return
          }

          val psiClass = type.psiClass ?: return
          psiClass.fields.forEach { checkFieldSafety(it, checkedNode, increasedTypeStack) }
          psiClass.superTypes.forEach { checkSuperTypesSafetyHelper(it, checkedNode, increasedTypeStack) }
        }

        checkSuperTypesSafetyHelper(type, checkedNode, emptyList())
      }

      /** A readable name for the subject of the report, even if the class is anonymous. */
      @Suppress("UElementAsPsi")
      private val UClass.reportSubject: String
        get() = name ?: "The anonymous class's supertype"

      /**
       * Gets the node to report on. If the field being checked is declared in the class being analyzed, return the field node. If it is
       * declared in a super type, return the class node being analyzed.
       */
      @Suppress("UElementAsPsi")
      private fun getNodeForReport(field: PsiField, parentNode: UClass): UElement {
        return if (parentNode.findFieldByName(field.name, /* checkBases= */ false) == null) {
          parentNode.uastAnchor
        } else {
          (field as? UElement) ?: field.toUElement() ?: parentNode.uastAnchor
        } ?: parentNode
      }

      /** Gets the name of the field qualified by its class, if available. */
      private val PsiField.classQualifiedName: String
        get() {
          return PsiFormatUtil.formatVariable(
            this,
            PsiFormatUtilBase.SHOW_CONTAINING_CLASS or PsiFormatUtilBase.SHOW_NAME,
            PsiSubstitutor.EMPTY,
          )
        }
    }
  }

  private fun PsiField.getDelegatedPropertyType(containingUClass: UClass): PsiType? {
    val delegatedFieldGetterName = "get${name.removeSuffix("\$delegate").replaceFirstChar { it.titlecase(Locale.ROOT)}}"
    @Suppress("UElementAsPsi")
    val delegatedFieldGetter =
      containingUClass.allMethods.singleOrNull { it.name == delegatedFieldGetterName && (it as? KtLightMethod)?.isGetter == true }
    return delegatedFieldGetter?.returnType
  }

  /** Returns whether the UClass needs to be tested for safety. */
  abstract fun shouldAnalyzeClass(node: UClass, context: JavaContext): Boolean

  companion object {
    internal const val LAZY_INIT_ANNO = "com.google.errorprone.annotations.concurrent.LazyInit"

    /**
     * Finds the type parameter supplied for any of [genericTypes] in the [arg] expression.
     *
     * This function mutates [currMap] to build a complete map of [PsiTypeParameter]s -> [PsiType]s recursively.
     */
    private fun findTypeParameter(
      arg: UExpression,
      param: PsiParameter,
      genericTypes: List<PsiTypeParameter>,
      currMap: MutableMap<PsiTypeParameter, PsiType>,
    ) {
      val argType = arg.getExpressionType() ?: return
      val paramType = param.type
      findTypeParameter(argType, paramType, genericTypes, currMap)
    }

    /**
     * Finds the type parameter supplied for any of [genericTypes] in the [argType].
     *
     * This function mutates [currMap] to build a complete map of [PsiTypeParameter]s -> [PsiType]s recursively.
     */
    private fun findTypeParameter(
      argType: PsiType,
      paramType: PsiType,
      genericTypes: List<PsiTypeParameter>,
      currMap: MutableMap<PsiTypeParameter, PsiType>,
    ) {
      val paramClass = paramType.psiClass
      if (paramClass is PsiTypeParameter) {
        if (paramClass in genericTypes) currMap[paramClass] = argType
        return
      }
      if (paramType is PsiClassType && argType is PsiClassType) {
        paramType.typeArguments().zip(argType.typeArguments()).forEach { (containedParam, containedArg) ->
          var containedArgType = containedArg as? PsiType
          if (containedArgType is PsiWildcardType) containedArgType = containedArgType.bound
          var containedParamType = containedParam as? PsiType
          if (containedParamType is PsiWildcardType) containedParamType = containedParamType.bound
          if (containedArgType != null && containedParamType != null) {
            findTypeParameter(containedArgType, containedParamType, genericTypes, currMap)
          }
          if (currMap.size == genericTypes.size) return@findTypeParameter
        }
      }
    }
  }
}
