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
import com.android.tools.lint.detector.api.AnnotationInfo
import com.android.tools.lint.detector.api.AnnotationUsageInfo
import com.android.tools.lint.detector.api.AnnotationUsageType
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.isKotlin
import com.android.tools.lint.detector.api.nameFromSource
import com.intellij.lang.jvm.JvmModifier
import com.intellij.psi.CommonClassNames.JAVA_LANG_STRING
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.InheritanceUtil.isInheritorOrSelf
import com.intellij.psi.util.PsiTypesUtil.classNameEquals
import com.intellij.psi.util.PsiUtil
import kotlin.reflect.KClass
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.name.JvmStandardClassIds
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.isPrivate
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UMultiResolvable
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UPostfixExpression
import org.jetbrains.uast.UPrefixExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UUnaryExpression
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.UastPostfixOperator
import org.jetbrains.uast.UastPrefixOperator
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.tryResolve
import org.jetbrains.uast.util.isAssignment

class CompileTimeConstantDetector : Detector(), SourceCodeScanner {

  // Matches annotations by their simple name to remain generic. The primary target is
  // `com.google.errorprone.annotations.CompileTimeConstant`, but this also supports
  // any other annotation with the same simple name to avoid strict package dependencies.
  override fun applicableAnnotations(): List<String> = listOf(COMPILE_TIME_CONSTANT_SHORT_NAME)

  override fun inheritAnnotation(annotation: String): Boolean = false

  override fun isApplicableAnnotationUsage(type: AnnotationUsageType): Boolean =
    // Assignment includes field/property initializers and method parameter default values.
    // On the other hand it misses field assignments, which we handle in visitBinary instead.
    type == AnnotationUsageType.METHOD_CALL_PARAMETER || type == AnnotationUsageType.ASSIGNMENT_RHS

  override fun visitAnnotationUsage(
    context: JavaContext,
    element: UElement,
    annotationInfo: AnnotationInfo,
    usageInfo: AnnotationUsageInfo,
  ) {
    if ((element as? UExpression)?.isConstant(context) != true) {
      context.report(
        ISSUE,
        element,
        context.getLocation(element),
        "Non-compile-time constant expression passed to parameter with `@CompileTimeConstant` annotation",
      )
    }
  }

  override fun getApplicableUastTypes(): List<Class<out UElement>> =
    listOf(
      UBinaryExpression::class.java,
      UCallableReferenceExpression::class.java,
      UClass::class.java,
      UField::class.java,
      UMethod::class.java,
      UParameter::class.java,
      UPostfixExpression::class.java,
      UPrefixExpression::class.java,
      UUnaryExpression::class.java,
    )

  override fun createUastHandler(context: JavaContext): UElementHandler {
    return object : UElementHandler() {
      override fun visitBinaryExpression(node: UBinaryExpression) {
        val isAssignment = node.isAssignment()
        val resolvedDeclarations =
          if (isAssignment) {
            // Multi resolution support for compound assignment:
            // E.g., += is now resolved to getter and setter.
            (node as? UMultiResolvable)?.multiResolve()?.mapNotNull { it.element as? PsiMethod } ?: emptyList()
          } else {
            listOfNotNull(node.resolveOperator())
          }
        for (callee in resolvedDeclarations) {
          // Check arguments to infix and overloaded binary operator method calls
          callee.parameterList.let {
            it.parameters.forEachIndexed { i, param ->
              if (param.isAnnotatedCtc()) {
                // Note that this works whether `callee` is the infix method or a setter
                // (which only has one param)
                val arg = if (i == it.parametersCount - 1) node.rightOperand else node.leftOperand
                // Prevent compound assignments (+= etc) outright in a similar way below.
                if (resolvedDeclarations.size > 1 || !arg.isConstant(context)) {
                  context.report(
                    ISSUE,
                    arg,
                    context.getLocation(arg),
                    """Non-compile-time constant expression passed to binary method parameter with
                      |`@CompileTimeConstant` annotation: ${node.operatorIdentifier?.name}"""
                      .trimMargin(),
                  )
                  return
                }
              }
            }
          }
        }

        // If operator is built-in it won't have annotations, but the assignee may
        if (!isAssignment) return

        // If lhs requires CTC, prevent compound assignments (+= etc.) outright, and make sure
        // CTC is used for regular assignments. Note we do something similar for ++/-- elsewhere.
        when (val assignee = node.leftOperand.tryResolveHarder()) {
          is PsiVariable ->
            if (assignee.isAnnotatedCtc() && (node.operator != UastBinaryOperator.ASSIGN || !node.rightOperand.isConstant(context))) {
              context.report(
                ISSUE,
                node,
                context.getLocation(node),
                "Non-compile-time constant expression assigned to variable annotated with `@CompileTimeConstant`",
              )
            }
          is PsiMethod -> // this is implicitly calling a Java setter using property syntax
            // For simple assignment (=), checkAnnotation() already reports on the parameter argument.
            // Only report here for compound assignments (+= etc.).
            if (assignee.parameterList.parameters.any { it.isAnnotatedCtc() } && node.operator != UastBinaryOperator.ASSIGN) {
              context.report(
                ISSUE,
                node,
                context.getLocation(node),
                """Non-compile-time constant expression implicitly passed to method
                  |parameter with `@CompileTimeConstant` annotation: `${assignee.name}`"""
                  .trimMargin(),
              )
            }
        }
      }

      override fun visitCallableReferenceExpression(node: UCallableReferenceExpression) {
        // Method references / callables: @CompileTimeConstant not allowed in Kotlin lambda expressions, so ignoring them for now
        if (
          isKotlin(node.lang) &&
            (node.resolve() as? PsiMethod)?.parameterList?.parameters?.any {
              it.isAnnotatedCtc()
            } == true
        ) {
          context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "Method with `@CompileTimeConstant` parameter cannot be used as a Kotlin function type",
          )
        }
      }

      /**
       * Looks for inherited methods that may have conflicting annotations in interfaces.
       *
       * [visitMethod] checks this for overridden methods; here we do the same for inherited ones for completeness.
       */
      override fun visitClass(node: UClass) {
        if (node.isInterface) return
        val javaPsi = node.javaPsi
        for (method in javaPsi.allMethods) {
          if (!isInheritorOrSelf(javaPsi.superClass, method.containingClass, true)) {
            continue // not inherited from a superclass
          }
          val paramIndices =
            method.parameterList.parameters.mapIndexedNotNull { i, param ->
              if (param.isAnnotatedCtc()) i else null
            }
          if (paramIndices.isEmpty()) continue
          javaPsi.interfaces
            .flatMap { it.findMethodsBySignature(method, true).toList() }
            .forEach {
              val params = it.parameterList.parameters
              if (paramIndices.any { i -> !params[i].isAnnotatedCtc() }) {
                context.report(
                  ISSUE,
                  node,
                  context.getLocation(node as UElement),
                  """Inherited method `${method.name}` with `@CompileTimeConstant` parameter can be called
                  |unsafely through implemented interface `${it.containingClass?.qualifiedName}`.
                  |Explicitly override the method or annotate the conflicting interface."""
                    .trimMargin(),
                )
              }
            }
        }
      }

      // Checked here for consistency with compile-time constant enforcement.
      override fun visitField(node: UField) {
        if (!node.isAnnotatedCtc()) return
        val isAnnotatedDirectly = node.isFieldTargetedCtc()

        if (!context.evaluator.isFinal(node)) {
          // Note that `isAnnotatedDirectly` has opposite meanings in fields and constructors:
          // In Fields, @Ctc is considered "direct" on the Field, and @setparam:Ctc is not "direct".
          // But in constructors @Ctc is on the Parameter "behind" the Field so is not "direct", and
          // @param:Ctc is considered "direct" instead.
          if (
            !isKotlin(node.lang) ||
              (node.sourcePsi is KtProperty && isAnnotatedDirectly) ||
              (node.sourcePsi is KtParameter && !isAnnotatedDirectly)
          ) {
            val fieldName = node.nameFromSource ?: (node.javaPsi as? PsiField)?.name ?: ""
            val keyword = if (isKotlin(node.lang)) "`val`" else "`final`"
            context.report(
              ISSUE,
              node,
              context.getLocation(node as UElement),
              "`@CompileTimeConstant` found on non-final field. Fields annotated with `@CompileTimeConstant` must be declared $keyword.",
            )
          }
        }

        when (val sourcePsi = node.sourcePsi) {
          is KtProperty -> {
            // These are properties with backing field declared in class body (Kotlin forbids @Ctc
            // annotation on properties without backing field).
            // Non-private properties are accessed through getters and we don't currently track that
            // (since they're method calls, and UAST (correctly) doesn't expose the @Ctc
            // annotation).
            // Ruling out getter calls is also consistent with how @Ctc is handled in Java.
            if (!sourcePsi.isPrivate() && isAnnotatedDirectly && node.sourceAnnotations.none { it.qualifiedName == JVM_FIELD_ANNOTATION }) {
              context.report(
                ISSUE,
                node,
                context.getLocation(node),
                "Property must be declared `private` or use `@JvmField` to allow `@CompileTimeConstant`",
              )
            }
          }
          // Constructor parameter properties appear as KtParameters
          is KtParameter -> {
            if (sourcePsi.hasValOrVar()) {
              if (isAnnotatedDirectly) {
                // @field:Ctc is unnecessary and potentially problematic if the constructor
                // parameter isn't also annotated, so error to enforce that parameter itself is
                // annotated instead
                context.report(
                  ISSUE,
                  node,
                  context.getLocation(node),
                  """
                  |Annotating backing field of constructor property with `@field:CompileTimeConstant`
                  |is unsafe and unnecessary. Annotate as `@CompileTimeConstant` only.
                  """
                    .trimMargin(),
                )
              }
              if (!sourcePsi.isPrivate() && node.sourceAnnotations.none { it.qualifiedName == JVM_FIELD_ANNOTATION }) {
                context.report(
                  ISSUE,
                  node,
                  context.getLocation(node),
                  "Property must be declared `private` or use `@JvmField` to allow `@CompileTimeConstant`",
                )
              }
            }
          }
        }
      }

      override fun visitMethod(node: UMethod) {
        if (node.sourcePsi is KtProperty || node.sourcePsi is KtPropertyAccessor) {
          // UAST doesn't resolve assignments to Kotlin property setters, so we can't check call
          // sites. Instead, raise error for annotated setters. Using the annotation on a property
          // setter is pretty weird anyway, especially since we forbid annotating the property
          // itself if it has a setter (i.e., is not a val property).
          if (node.uastParameters.any { it.hasCtcAnnotation() }) {
            context.report(
              ISSUE,
              node,
              context.getLocation(node),
              """
              |`@CompileTimeConstant` parameters are not supported in Kotlin property setters.
              |Use a `fun` with a parameter annotated `@CompileTimeConstant`.
              """
                .trimMargin(),
            )
          }
        }

        // Check that super-methods have @CompileTimeConstant annos where this method does
        val overridden: Array<PsiMethod> = (node.javaPsi as? PsiMethod)?.findSuperMethods() ?: emptyArray()
        if (overridden.isEmpty()) return
        val paramIndices =
          node.uastParameters.mapIndexedNotNull { i, param ->
            if (param.hasCtcAnnotation()) i else null
          }
        if (paramIndices.isEmpty()) return
        overridden.forEach {
          val params = it.parameterList.parameters
          if (paramIndices.any { i -> !params[i].isAnnotatedCtc() }) {
            context.report(
              ISSUE,
              node,
              context.getLocation(node),
              "Method with `@CompileTimeConstant` parameter cannot override method without it",
            )
            // Don't need to recurse if we assume we'll visit super methods separately
          }
        }
      }

      override fun visitParameter(node: UParameter) {
        when (val sourcePsi = node.sourcePsi) {
          // Extension function receivers appear as KtTypeReferences. At call sites, UAST doesn't
          // expose any @receiver: annotations. Until that can be fixed so we can enforce the
          // annotation at call sites, rule out declarations like that instead.
          is KtTypeReference -> {
            node.findCtcAnnotation()?.let { anno ->
              context.report(
                ISSUE,
                anno,
                context.getLocation(anno),
                """
                |Annotating extension function receivers with `@receiver:CompileTimeConstant` is not
                |supported. Only regular function parameters can be annotated with
                |`@CompileTimeConstant`.
                """
                  .trimMargin(),
              )
            }
          }
        }
      }

      override fun visitPostfixExpression(node: UPostfixExpression) = visitUnaryExpression(node)

      override fun visitPrefixExpression(node: UPrefixExpression) = visitUnaryExpression(node)

      override fun visitUnaryExpression(node: UUnaryExpression) {
        val isIncOrDec = node.isIncOrDec()
        val resolvedDeclarations =
          if (isIncOrDec) {
            (node as? UMultiResolvable)?.multiResolve()?.mapNotNull { it.element as? PsiMethod } ?: emptyList()
          } else {
            listOfNotNull(node.resolveOperator())
          }
        // get; operator (e.g., ++); then set. Therefore, it's no longer constant.
        // If any of resolved callee has an annotated parameter, report.
        val violated = resolvedDeclarations.any { callee ->
          callee.parameterList.parameters.any { it.isAnnotatedCtc() }
        }
        if (violated) {
          context.report(
            ISSUE,
            node.operand,
            context.getLocation(node.operand),
            """Non-compile-time constant expression passed to unary operator parameter with
              |`@CompileTimeConstant` annotation: ${node.operatorIdentifier?.name}"""
              .trimMargin(),
          )
          return
        }

        // Built-in operators (or operator couldn't be resolved)
        if (!isIncOrDec) return // we're done here: other built-ins don't modify operand

        // Flag ++/-- on CTCs, in particular, inferred Java properties, since that makes them no
        // longer CTCs, similar to what we do for assignments and compound assignments.
        val assignee = node.operand.tryResolveHarder()
        if (assignee is PsiVariable && assignee.isAnnotatedCtc()) {
          context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "Cannot apply increment or decrement operators to variable annotated with `@CompileTimeConstant`",
          )
        }
      }
    }
  }

  companion object {
    private const val COMPILE_TIME_CONSTANT_SHORT_NAME = "CompileTimeConstant"

    private val CONSTANT_CLASSES: Set<KClass<*>> =
      setOf(
        String::class,
        Boolean::class,
        Int::class,
        Long::class,
        Float::class,
        Double::class,
        Short::class,
        Byte::class,
        Char::class,
      )

    private val CONSTANT_OF_CLASSES =
      setOf(
        "com.google.common.collect.ImmutableList",
        "com.google.common.collect.ImmutableSet",
        "java.util.List",
        "java.util.Set",
      )

    private fun isConstantMethodReturn(method: PsiMethod?): Boolean {
      if (method == null) return false
      val name = method.name
      val className = method.containingClass?.qualifiedName ?: return false
      return when (name) {
        "of" -> className in CONSTANT_OF_CLASSES
        "listOf",
        "setOf",
        "emptyList",
        "emptySet" -> className.startsWith("kotlin.collections.")
        else -> false
      }
    }

    /**
     * Returns true if the receiver is a (boxed) primitive or [String].
     *
     * In particular returns `false` for enum constants, which UAST typically treats as constant, but `@CompileTimeConstant` isn't meant to
     * cover them.
     */
    private fun Any.isConsideredCtc(): Boolean = this::class in CONSTANT_CLASSES

    fun UExpression.isConstant(context: JavaContext): Boolean =
      when (this) {
        is UParenthesizedExpression -> expression.isConstant(context)
        // TODO(b/384706320): Add support for switch + when expressions.
        is UIfExpression -> (thenExpression?.isConstant(context) == true) && (elseExpression?.isConstant(context) == true)
        // note: includes null literal as desired
        // note: string literal handled below in else branch
        is ULiteralExpression -> true
        is UCallExpression -> {
          isConstantMethodReturn(resolve()) && this.valueArguments.all { it.isConstant(context) }
        }
        is UQualifiedReferenceExpression -> this.selector.isConstant(context)
        is UReferenceExpression -> {
          when (val resolved = resolve()) {
            is PsiParameter -> resolved.isEffectivelyFinal() && resolved.isAnnotatedCtc()
            // Inner functions' parameters resolve as KtParameters, not PsiParameters
            // Don't check isEffectivelyFinal because param reassignment only works in Java
            is KtParameter -> resolved.isAnnotatedCtc()
            is PsiField ->
              context.evaluator.isFinal(resolved) &&
                (resolved.computeConstantValue()?.isConsideredCtc() == true ||
                  evaluate()?.isConsideredCtc() == true ||
                  resolved.isAnnotatedCtc() // annotated fields
                )
            // For Kotlin locals, computeConstantValue() doesn't work, but <expr>.evaluate() does.
            // evaluate() seems to return null for non-final locals, but also check that
            // just-in-case.
            is PsiLocalVariable -> resolved.isDeclaredFinal(context) && evaluate()?.isConsideredCtc() == true
            else -> false
          }
        }
        else -> {
          evaluate()?.isConsideredCtc()
            ?: when (this) {
              is UPolyadicExpression -> {
                operator == UastBinaryOperator.PLUS &&
                  classNameEquals(getExpressionType(), JAVA_LANG_STRING) &&
                  operands.all { it.isConstant(context) } // string literal or templates
              }
              else -> false
            }
        }
      }

    /** Whether the receiver variable declaration is annotated `@CompileTimeConstant`. */
    @Suppress("ExternalAnnotations")
    fun PsiVariable.isAnnotatedCtc(): Boolean {
      val uElement = toUElement() as? UAnnotated
      if (uElement?.hasCtcAnnotation() == true) return true
      return annotations.any {
        it.qualifiedName?.endsWith(".$COMPILE_TIME_CONSTANT_SHORT_NAME") == true || it.qualifiedName == COMPILE_TIME_CONSTANT_SHORT_NAME
      }
    }

    fun KtParameter.isAnnotatedCtc(): Boolean {
      val uParam = toUElement() as? UParameter
      if (uParam != null) {
        return uParam.hasCtcAnnotation()
      }
      return analyze(this) {
        val annos = this@isAnnotatedCtc.symbol.annotations
        annos.classIds.any { it.asFqNameString().endsWith(COMPILE_TIME_CONSTANT_SHORT_NAME) }
      }
    }

    private fun UAnnotated.findCtcAnnotation(): UAnnotation? {
      return uAnnotations.find {
        it.qualifiedName?.endsWith(".$COMPILE_TIME_CONSTANT_SHORT_NAME") == true || it.qualifiedName == COMPILE_TIME_CONSTANT_SHORT_NAME
      }
    }

    private fun UAnnotated.hasCtcAnnotation(): Boolean = findCtcAnnotation() != null

    private fun UField.isAnnotatedCtc(): Boolean = hasCtcAnnotation() || (javaPsi as? PsiVariable)?.isAnnotatedCtc() == true

    private fun UField.isFieldTargetedCtc(): Boolean {
      val ktParam = sourcePsi as? KtParameter ?: return findCtcAnnotation() != null
      return ktParam.annotationEntries.any { entry ->
        entry.useSiteTarget?.text == "field" && entry.shortName?.asString() == COMPILE_TIME_CONSTANT_SHORT_NAME
      }
    }

    private fun PsiLocalVariable.isDeclaredFinal(context: JavaContext): Boolean {
      // isFinal returns false for Kotlin "val" locals, so check for them separately
      return context.evaluator.isFinal(this) || (toUElement()?.sourcePsi as? KtProperty)?.isVar == false
    }

    private fun UExpression.tryResolveHarder(): PsiElement? =
      tryResolve() ?: (this as? UQualifiedReferenceExpression)?.selector?.tryResolve()

    private fun UUnaryExpression.isIncOrDec(): Boolean =
      when (operator) {
        UastPostfixOperator.INC,
        UastPostfixOperator.DEC,
        UastPrefixOperator.INC,
        UastPrefixOperator.DEC -> true
        else -> false
      }

    private val IMPLEMENTATION = Implementation(CompileTimeConstantDetector::class.java, Scope.JAVA_FILE_SCOPE)

    @JvmField
    @Suppress("LintImplUnexpectedDomain")
    val ISSUE =
      Issue.create(
        id = COMPILE_TIME_CONSTANT_SHORT_NAME,
        briefDescription = "Compile-time constant required",
        explanation =
          """
          Arguments passed to `@CompileTimeConstant` parameters must be literals, references \
          to static constants, or references to other `@CompileTimeConstant` parameters.
          """,
        category = Category.SECURITY,
        priority = 4,
        severity = Severity.ERROR,
        moreInfo = "https://errorprone.info/bugpattern/CompileTimeConstant",
        implementation = IMPLEMENTATION,
      )
  }
}

internal val JVM_FIELD_ANNOTATION = JvmStandardClassIds.Annotations.JvmField.asSingleFqName().asString()

internal fun PsiParameter.isEffectivelyFinal(): Boolean {
  if (hasModifier(JvmModifier.FINAL)) return true

  var effectivelyFinal = true
  declarationScope.accept(
    object : JavaElementVisitor() {
      override fun visitElement(element: PsiElement) {
        if (!effectivelyFinal) return // End traversal ASAP
        element.acceptChildren(this)
      }

      override fun visitReferenceExpression(expr: PsiReferenceExpression) {
        if (expr.resolve() == this@isEffectivelyFinal && PsiUtil.isAccessedForWriting(expr)) {
          effectivelyFinal = false
        }
        this.visitElement(expr)
      }
    }
  )
  return effectivelyFinal
}
