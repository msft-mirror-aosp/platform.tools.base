/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.lint.checks.studio

import com.android.tools.lint.detector.api.AnnotationInfo
import com.android.tools.lint.detector.api.AnnotationOrigin
import com.android.tools.lint.detector.api.AnnotationUsageInfo
import com.android.tools.lint.detector.api.AnnotationUsageType
import com.android.tools.lint.detector.api.Category.Companion.CORRECTNESS
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiLiteralValue
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPackage
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.USimpleNameReferenceExpression

/**
 * Searches for usages of scheduled-for-removal IntelliJ APIs, for the purpose of migrating to newer
 * APIs before the next platform merge.
 */
class IntellijApiUsageDetector : Detector(), SourceCodeScanner {

  override fun applicableAnnotations(): List<String> =
    listOf("java.lang.Deprecated", "org.jetbrains.annotations.ApiStatus.ScheduledForRemoval")

  // Deprecation annotations should generally not be inherited from a super class / super method.
  // Example: a new API might extend a deprecated interface for backwards compatibility.
  override fun inheritAnnotation(annotation: String): Boolean = false

  override fun isApplicableAnnotationUsage(type: AnnotationUsageType): Boolean {
    return when (type) {
      AnnotationUsageType.CLASS_REFERENCE,
      AnnotationUsageType.CLASS_REFERENCE_AS_DECLARATION_TYPE,
      AnnotationUsageType.EXTENDS,
      AnnotationUsageType.FIELD_REFERENCE,
      AnnotationUsageType.METHOD_CALL,
      AnnotationUsageType.METHOD_OVERRIDE,
      AnnotationUsageType.METHOD_REFERENCE -> true
      // We exclude the following usage types only because they do not seem worth the trouble:
      // CLASS_REFERENCE_AS_IMPLICIT_DECLARATION_TYPE, IMPLICIT_CONSTRUCTOR,
      // IMPLICIT_CONSTRUCTOR_CALL.
      else -> false
    }
  }

  override fun visitAnnotationUsage(
    context: JavaContext,
    element: UElement,
    annotationInfo: AnnotationInfo,
    usageInfo: AnnotationUsageInfo,
  ) {
    if (!isDeprecatedForRemoval(annotationInfo)) {
      return
    }
    if (isInIgnoredPackage(annotationInfo.annotation)) {
      return
    }
    if (annotationInfo.origin == AnnotationOrigin.PACKAGE) {
      return // TODO: package-level deprecations are currently too noisy due to Kotlin K1 usages.
    }
    val referenced = usageInfo.referenced
    val symbolName =
      when {
        element is USimpleNameReferenceExpression -> element.identifier
        referenced is PsiNamedElement -> referenced.name ?: return
        else -> return
      }
    val annotationDisplayName =
      when (annotationInfo.qualifiedName) {
        "java.lang.Deprecated" -> "`@Deprecated(forRemoval=true)`"
        else -> "`@${annotationInfo.qualifiedName.substringAfterLast('.')}`"
      }
    val origin = annotationInfo.origin
    val toBlame =
      when {
        origin == AnnotationOrigin.CLASS && referenced is PsiMethod && referenced.isConstructor -> {
          "`$symbolName`"
        }
        origin == AnnotationOrigin.METHOD &&
          referenced is PsiMethod &&
          referenced.isConstructor -> {
          "This constructor for `$symbolName`"
        }
        origin == AnnotationOrigin.FILE -> {
          "The file containing `$symbolName`"
        }
        origin == AnnotationOrigin.OUTER_CLASS ||
          origin == AnnotationOrigin.CLASS && referenced !is PsiClass -> {
          val containingClass = (annotationInfo.annotated as? PsiNamedElement)?.name
          if (containingClass != null) {
            "Containing class `$containingClass`"
          } else {
            "The class containing `$symbolName`"
          }
        }
        origin == AnnotationOrigin.PACKAGE && referenced !is PsiPackage -> {
          "The package containing `$symbolName`"
        }
        else -> {
          "`$symbolName`"
        }
      }
    context.report(
      SCHEDULED_FOR_REMOVAL,
      element,
      context.getNameLocation(element),
      "$toBlame is $annotationDisplayName",
    )
  }

  private fun isInIgnoredPackage(annotation: UAnnotation): Boolean {
    // Ignore our own packages, since the focus is on IntelliJ APIs that might change during platform updates.
    // Also ignore JDK APIs since these are removed very infrequently.
    val packageName = (annotation.javaPsi?.containingFile as? PsiClassOwner)?.packageName ?: return false
    return packageName.startsWith("com.android.") ||
        packageName.startsWith("com.google") ||
        packageName.startsWith("org.jetbrains.android.") ||
        packageName.startsWith("java.")
  }

  private fun isDeprecatedForRemoval(annotationInfo: AnnotationInfo): Boolean {
    return when (annotationInfo.qualifiedName) {
      "org.jetbrains.annotations.ApiStatus.ScheduledForRemoval" -> true
      "java.lang.Deprecated" -> isTrue(annotationInfo.annotation.findAttributeValue("forRemoval"))
      else -> false
    }
  }

  // Checks whether [expr] evaluates to 'true', handling the corner case where [expr] is a
  // compiled annotation argument and expr.evaluate() does not always work.
  private fun isTrue(expr: UExpression?): Boolean {
    if (expr == null) return false
    if (expr.evaluate() == true) return true
    val javaPsi = expr.javaPsi
    if (javaPsi is PsiLiteralValue && javaPsi.value == true) return true
    return false
  }

  companion object {
    private val IMPLEMENTATION =
      Implementation(IntellijApiUsageDetector::class.java, Scope.JAVA_FILE_SCOPE)

    @JvmField
    val SCHEDULED_FOR_REMOVAL =
      Issue.create(
        id = "ScheduledForRemoval",
        briefDescription = "Using APIs scheduled for removal",
        explanation =
          """
          APIs marked with `@Deprecated(forRemoval)` or `@ScheduledForRemoval` will likely be removed \
          in an upcoming IntelliJ Platform update. Usages should be removed now to reduce friction \
          during the next platform merge.
          """,
        category = CORRECTNESS,
        severity = Severity.WARNING,
        platforms = STUDIO_PLATFORMS,
        implementation = IMPLEMENTATION,
        enabledByDefault = false,
      )
  }
}
