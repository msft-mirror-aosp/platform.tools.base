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
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintMap
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.TypeEvaluator
import com.android.tools.lint.detector.api.UastLintUtils
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiSynchronizedStatement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.tryResolve

/**
 * Reports `synchronized` statements (Java) and `kotlin.synchronized` calls whose lock is an instance of a value-based class: primitive
 * wrappers, `java.util.Optional`, `java.time.*`, `@jdk.internal.ValueBased` classes, or Kotlin `@JvmInline` value classes. Such instances
 * have no stable identity, and ART throws an exception for apps targeting API 38+.
 */
class SynchronizeOnValueBasedClassDetector : Detector(), SourceCodeScanner {

  override fun getApplicableMethodNames(): List<String> = listOf("synchronized")

  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
    if (!context.evaluator.isMemberInClass(method) { it == "kotlin.StandardKt" || it == "kotlin.StandardKt__SynchronizedKt" }) return
    val lockArg = node.valueArguments.firstOrNull() ?: return
    val typeName = findValueBasedTypeName(context, lockArg) ?: return
    reportIncident(context, lockArg, typeName)
  }

  override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UBlockExpression::class.java)

  override fun createUastHandler(context: JavaContext): UElementHandler =
    object : UElementHandler() {
      override fun visitBlockExpression(node: UBlockExpression) {
        val syncStatement = node.sourcePsi as? PsiSynchronizedStatement ?: return
        val lockPsi = syncStatement.lockExpression ?: return
        val lockUast = lockPsi.toUElementOfType<UExpression>() ?: return
        val typeName = findValueBasedTypeName(context, lockUast) ?: return
        reportIncident(context, lockUast, typeName)
      }
    }

  private fun reportIncident(context: JavaContext, lockElement: UElement, typeName: String) {
    val message =
      "Synchronizing on an instance of value-based class `$typeName` is unsafe and can throw an exception when targeting API 38+"
    val incident = Incident(ISSUE, lockElement, context.getLocation(lockElement), message)
    context.report(incident, map())
  }

  override fun filterIncident(context: Context, incident: Incident, map: LintMap): Boolean {
    if (context.mainProject.targetSdk < API_LEVEL_ANDROID_D) {
      incident.overrideSeverity(Severity.WARNING)
    }
    return true
  }

  private fun findValueBasedTypeName(context: JavaContext, lockExpression: UExpression): String? =
    findKotlinInlineClassName(lockExpression)
      ?: findValueBasedPsiTypeName(context, lockExpression.getExpressionType())
      ?: (lockExpression.tryResolve() as? PsiVariable)
        ?.let { UastLintUtils.findLastAssignment(it, lockExpression) }
        ?.let(::findKotlinInlineClassName)
      ?: findValueBasedPsiTypeName(context, TypeEvaluator.evaluate(lockExpression))

  private fun findKotlinInlineClassName(element: UElement): String? {
    val ktExpression = element.sourcePsi as? KtExpression ?: return null
    analyze(ktExpression) {
      val symbol = ktExpression.expressionType?.expandedSymbol as? KaNamedClassSymbol ?: return null
      if (symbol.isInline) {
        return symbol.classId?.asFqNameString() ?: symbol.name.asString()
      }
    }
    return null
  }

  private fun findValueBasedPsiTypeName(context: JavaContext, type: PsiType?): String? {
    if (type is PsiPrimitiveType) return type.boxedTypeName?.takeIf { it in VALUE_BASED_CLASSES }
    val psiClass = context.evaluator.getTypeClass(type) ?: return null
    return if (isDirectlyValueBased(psiClass)) psiClass.qualifiedName ?: psiClass.name else null
  }

  // UAST unboxes Kotlin value class expressions, so the `@JvmInline` check below only fires for Java code holding a boxed instance;
  // Kotlin sources are handled by [findKotlinInlineClassName].
  private fun isDirectlyValueBased(psiClass: PsiClass): Boolean {
    val qualifiedName = psiClass.qualifiedName
    return (qualifiedName != null && qualifiedName in VALUE_BASED_CLASSES) ||
      psiClass.hasAnnotation(JDK_INTERNAL_VALUE_BASED) ||
      psiClass.hasAnnotation(JVM_INLINE_ANNOTATION)
  }

  companion object {
    private const val API_LEVEL_ANDROID_D = 38
    private const val JDK_INTERNAL_VALUE_BASED = "jdk.internal.ValueBased"
    private const val JVM_INLINE_ANNOTATION = "kotlin.jvm.JvmInline"

    private val VALUE_BASED_CLASSES =
      setOf(
        "java.lang.Boolean",
        "java.lang.Byte",
        "java.lang.Character",
        "java.lang.Double",
        "java.lang.Float",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Short",
        "java.lang.ProcessHandle",
        "java.lang.Runtime.Version",
        "java.time.Duration",
        "java.time.Instant",
        "java.time.LocalDate",
        "java.time.LocalDateTime",
        "java.time.LocalTime",
        "java.time.MonthDay",
        "java.time.OffsetDateTime",
        "java.time.OffsetTime",
        "java.time.Period",
        "java.time.Year",
        "java.time.YearMonth",
        "java.time.ZoneId",
        "java.time.ZoneOffset",
        "java.time.ZonedDateTime",
        "java.time.chrono.HijrahDate",
        "java.time.chrono.JapaneseDate",
        "java.time.chrono.MinguoDate",
        "java.time.chrono.ThaiBuddhistDate",
        "java.util.Optional",
        "java.util.OptionalDouble",
        "java.util.OptionalInt",
        "java.util.OptionalLong",
      )

    @JvmField
    val ISSUE: Issue =
      Issue.create(
          id = "SynchronizeOnValueBasedClass",
          briefDescription = "Synchronizing on value-based class",
          explanation =
            """
            Value-based classes (such as primitive wrapper classes like `java.lang.Boolean` or \
            `java.lang.Integer`, `java.util.Optional`, `java.time.*` classes, classes annotated \
            with `@jdk.internal.ValueBased`, or Kotlin `@JvmInline` value classes) do not have a \
            guaranteed unique object identity across boxing or factory invocations.

            Synchronizing on instances of value-based classes is unreliable and error-prone. \
            On Android D and later, ART throws an exception when an app targeting API 38 or higher \
            synchronizes on a value-based class instance.

            Instead of synchronizing on a value-based class instance, create and synchronize on a \
            dedicated private lock object (`private final Object lock = new Object();` in Java or \
            `private val lock = Any()` in Kotlin).
            """,
          category = Category.CORRECTNESS,
          priority = 6,
          severity = Severity.ERROR,
          implementation = Implementation(SynchronizeOnValueBasedClassDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
        .setAliases(
          listOf(
            "synchronization",
            "identity",
            "ValueClassIdentity",
            "SYNCHRONIZED_BLOCK_ON_JAVA_VALUE_BASED_CLASS",
            "SYNCHRONIZED_BLOCK_ON_VALUE_CLASS_OR_PRIMITIVE",
          )
        )
  }
}
