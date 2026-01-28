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

package com.android.tools.lint.checks.optional

import com.android.SdkConstants.ATTR_VALUE
import com.android.sdklib.SdkVersionInfo.CUR_DEVELOPMENT
import com.android.support.AndroidxName
import com.android.tools.lint.checks.ApiLookup
import com.android.tools.lint.checks.BuiltinIssueRegistry
import com.android.tools.lint.checks.TypedefDetector
import com.android.tools.lint.client.api.JavaEvaluator
import com.android.tools.lint.client.api.LintBaseline.Companion.stringsEquivalent
import com.android.tools.lint.detector.api.AnnotationInfo
import com.android.tools.lint.detector.api.AnnotationOrigin
import com.android.tools.lint.detector.api.AnnotationUsageInfo
import com.android.tools.lint.detector.api.AnnotationUsageType
import com.android.tools.lint.detector.api.ApiConstraint
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.ExtensionSdk.Companion.ANDROID_SDK_ID
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.getInternalMethodName
import com.android.tools.lint.detector.api.getMethodName
import com.android.tools.lint.detector.api.isUnconditionalReturn
import com.android.utils.SdkUtils.constantNameToCamelCase
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralValue
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UUnaryExpression
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.UastFacade
import org.jetbrains.uast.UastPrefixOperator
import org.jetbrains.uast.evaluateString
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.skipParenthesizedExprDown
import org.jetbrains.uast.tryResolve

/**
 * Enforced flag checking in the Android platform; see go/android-flagged-apis.
 *
 * NOTE: This lint check is not part of the standard distribution via [BuiltinIssueRegistry]; it's part of [AospIssueRegistry] which can
 * conditionally be enabled.
 */
class FlaggedApiDetector : Detector(), SourceCodeScanner {
  companion object Issues {
    private val IMPLEMENTATION = Implementation(FlaggedApiDetector::class.java, Scope.JAVA_FILE_SCOPE)

    /** Accessing flagged api without check. */
    @JvmField
    val ISSUE =
      Issue.create(
        id = "FlaggedApi",
        explanation =
          """
          This lint check looks for accesses of APIs marked with `@FlaggedApi(X)` or \
          `@RequiresFlag(X)` without a guarding `if (Flags.X)` check. \
          See go/android-flagged-apis.
          """,
        briefDescription = "FlaggedApi access without check",
        category = Category.CORRECTNESS,
        priority = 6,
        severity = Severity.ERROR,
        androidSpecific = true,
        implementation = IMPLEMENTATION,
      )

    private val FLAGGED_API_ANNOTATION = AndroidxName("android.annotation.FlaggedApi", "androidx.annotation.FlaggedApi")
    private val REQUIRES_FLAG_ANNOTATION = AndroidxName("android.annotation.RequiresFlag", "androidx.annotation.RequiresFlag")

    private fun isFlagAnnotation(qualifiedName: String?): Boolean {
      return FLAGGED_API_ANNOTATION.isEquals(qualifiedName) || REQUIRES_FLAG_ANNOTATION.isEquals(qualifiedName)
    }

    /** Is the given [element] referencing an annotated element */
    fun isAlreadyAnnotated(evaluator: JavaEvaluator, element: UElement?): Boolean {
      val resolved = element?.tryResolve() ?: return false
      return isAlreadyAnnotated(evaluator, resolved)
    }

    /** Is the given [resolved] class/method/field annotated with a `@FlaggedApi` or `@RequiresFlag` annotation? */
    fun isAlreadyAnnotated(evaluator: JavaEvaluator, resolved: PsiElement?): Boolean {
      if (resolved !is PsiMember) return false
      // Check both the annotation on the member itself and its surrounding class.
      return listOfNotNull(resolved, resolved.containingClass).any { owner ->
        evaluator.getAnnotations(owner).any { isFlagAnnotation(it.qualifiedName) }
      }
    }
  }

  override fun applicableAnnotations(): List<String> {
    return listOf(
      FLAGGED_API_ANNOTATION.oldName(),
      FLAGGED_API_ANNOTATION.newName(),
      REQUIRES_FLAG_ANNOTATION.oldName(),
      REQUIRES_FLAG_ANNOTATION.newName(),
    )
  }

  override fun isApplicableAnnotationUsage(type: AnnotationUsageType): Boolean {
    return when (type) {
      AnnotationUsageType.METHOD_CALL,
      AnnotationUsageType.METHOD_REFERENCE,
      AnnotationUsageType.FIELD_REFERENCE,
      AnnotationUsageType.CLASS_REFERENCE,
      AnnotationUsageType.ANNOTATION_REFERENCE,
      AnnotationUsageType.EXTENDS,
      AnnotationUsageType.DEFINITION -> true
      else -> false
    }
  }

  override fun inheritAnnotation(annotation: String): Boolean {
    return false
  }

  override fun sameMessage(issue: Issue, new: String, old: String): Boolean {
    if (issue !== ISSUE) return super.sameMessage(issue, new, old)
    if (new == old) return true
    val normalizedNew = new.replace("FlaggedApi", "RequiresFlag")
    val normalizedOld = old.replace("FlaggedApi", "RequiresFlag")
    return stringsEquivalent(normalizedNew, normalizedOld)
  }

  override fun visitAnnotationUsage(
    context: JavaContext,
    element: UElement,
    annotationInfo: AnnotationInfo,
    usageInfo: AnnotationUsageInfo,
  ) {
    val qualifiedName = annotationInfo.qualifiedName ?: return
    val annotation = annotationInfo.annotation
    if (usageInfo.type == AnnotationUsageType.DEFINITION) {
      checkFlagApiDeclaration(annotation, context, usageInfo, qualifiedName)
      return
    }

    val compiled = usageInfo.referenced is PsiCompiledElement
    val evaluator = context.evaluator

    val flags =
      if (compiled) {
        val flagString = getFlaggedApiString(annotation)
        val flag =
          if (flagString != null) {
            getFlaggedApiFromString(evaluator, flagString)
          } else {
            null
          }
        if (flag == null && flagString != null) {
          if (isFinalized(context, element, annotationInfo.annotated)) {
            return
          }

          // Flags class missing from class path. We still want to flag
          // these as errors, and we don't need to check to see if you've
          // added explicit flags checks since clearly you haven't -- the
          // flags class aren't on the class path so a flag check wouldn't
          // compile.
          val flagClass = flagString.substringBeforeLast(".")
          val flagClassName = flagClass.substringAfterLast(".")
          val flagName = flagString.substringAfterLast(".")
          val flagMethodName = getFlagMethodName(flagName)
          reportError(context, element, flagClassName, flagName, flagMethodName)
          return
        }
        flag
      } else {
        getFlagFieldsFromSource(evaluator, annotation)
      }

    val (flag, flag2) = flags ?: return

    if (annotationInfo.origin == AnnotationOrigin.SELF) {
      if (FLAGGED_API_ANNOTATION.isEquals(qualifiedName) || REQUIRES_FLAG_ANNOTATION.isEquals(qualifiedName)) {
        return
      }
    } else if (isAlreadyAnnotated(evaluator, element, flag)) {
      return
    }

    val flagClass = flag.containingClass ?: return
    val flagName = flag.name
    val flagClass2 = flag2?.containingClass
    val flagMethodName = getFlagMethodName(flagName)

    if (isFlagChecked(element, flagClass, flagClass2, flagMethodName)) {
      return
    }

    // Make sure the API hasn't already been finalized; once it is, it will already be
    // checked by ApiDetector, and we don't want duplicate warnings.
    if (isFinalized(context, element, annotationInfo.annotated)) {
      return
    }

    reportError(context, element, flagClass.name ?: "", flagName, flagMethodName)
  }

  private fun isFinalized(context: JavaContext, reference: UElement, annotated: PsiElement?): Boolean {
    val apiDatabase = ApiLookup.getOrNull(context.client, context.project.buildTarget) ?: return false

    val element = reference.tryResolve() ?: annotated ?: return false
    when (element) {
      is PsiClass -> {
        val qualifiedName = element.qualifiedName ?: return false
        return apiDatabase.getClassVersions(qualifiedName).isFinalized()
      }
      is PsiMember -> {
        val cls = element.containingClass ?: return false
        val qualifiedName = cls.qualifiedName ?: return false
        if (element is PsiField) {
          return apiDatabase.getFieldVersions(qualifiedName, element.name).isFinalized()
        } else if (element is PsiMethod) {
          val desc = context.evaluator.getMethodDescription(element, false, false)
          if (desc != null) {
            val internalName = getInternalMethodName(element)
            return apiDatabase.getMethodVersions(qualifiedName, internalName, desc).isFinalized()
          }
        } // PsiClass is also a PsiMember
      }
    }

    return false
  }

  private fun ApiConstraint.isFinalized(): Boolean {
    return getSdk() == ANDROID_SDK_ID && min() < CUR_DEVELOPMENT
  }

  private fun getFlagMethodName(flagName: String): String = constantNameToCamelCase(flagName.removePrefix("FLAG_"))

  private fun checkFlagApiDeclaration(
    annotation: UAnnotation,
    context: JavaContext,
    usageInfo: AnnotationUsageInfo,
    qualifiedName: String,
  ) {
    val expression = annotation.attributeValues.firstOrNull()?.expression
    if (expression is ULiteralExpression) {
      val flagString = ConstantEvaluator.evaluateString(context, expression, false)
      if (usageInfo.type == AnnotationUsageType.DEFINITION) {
        val label = qualifiedName.substringAfterLast('.')
        if (flagString != null && flagString.indexOf('.') == -1) {
          context.report(ISSUE, expression, context.getLocation(expression), "Invalid @$label descriptor; should be `package.name`")
        } else {
          val incident =
            Incident(
              ISSUE,
              expression,
              context.getLocation(expression),
              "@$label should specify an actual flag constant; " + "raw strings are discouraged (and more importantly, **not enforced**)",
            )
          incident.overrideSeverity(Severity.WARNING)
          context.report(incident)
        }
      }
    }
  }

  private fun reportError(context: JavaContext, element: UElement, flagClassName: String, flagName: String, flagMethodName: String) {
    val referenced = element.tryResolve()
    val description =
      when {
        referenced is PsiMethod -> "Method `${referenced.name}()`"
        element is UCallExpression -> "Method `${getMethodName(element)}()`"
        referenced is PsiField -> "Field `${referenced.name}`"
        referenced is PsiClass -> "Class `${referenced.name}`"
        element is UClassLiteralExpression -> "Class `${element.expression?.sourcePsi?.text}`"
        referenced is PsiNamedElement -> "Reference `${referenced.name}`"
        else -> "This"
      }
    val name = element.getParentOfType<UMethod>()?.name ?: "?"
    val message =
      "$description is a flagged API and should be inside an `if (${flagClassName}.$flagMethodName())` check " +
        "(or annotate the surrounding method `$name` with `@RequiresFlag(${flagClassName}.$flagName) to transfer requirement to caller`)"
    context.report(ISSUE, element, context.getLocation(element), message)
  }

  /**
   * Represents one or two flag fields; this is primarily a result object from the [getFlagFieldsFromSource] and [getFlaggedApiFromString]
   * methods which need to return a pair of flags.
   */
  private class OneOrTwoFlagFields(val flag1: PsiField, val flag2: PsiField?) {
    operator fun component1(): PsiField = flag1

    operator fun component2(): PsiField? = flag2
  }

  /** Given a `@FlaggedApi` or `@RequiresFlag` annotation, returns the resolved field. */
  private fun getFlagFieldsFromSource(evaluator: JavaEvaluator, annotation: UAnnotation): OneOrTwoFlagFields? {
    val expression = annotation.attributeValues.firstOrNull()?.expression
    val flag = expression?.tryResolve() as? PsiField
    if (flag == null) {
      if (expression is ULiteralExpression) {
        val value = expression.evaluateString() ?: return null
        return getFlaggedApiFromString(evaluator, value)
      }
      return null
    }
    val name = flag.containingClass?.qualifiedName
    if (name != null && name.endsWith(".Flags")) {
      val fieldName = flag.name
      val packageName = name.substringBeforeLast(".")
      return OneOrTwoFlagFields(flag, findFlagField(evaluator, packageName, "ExportedFlags", fieldName))
    }
    return OneOrTwoFlagFields(flag, null)
  }

  /** Given a `@FlaggedApi` annotation in bytecode, returns the flag constant value which should be a string */
  private fun getFlaggedApiString(annotation: UAnnotation): String? {
    val sourcePsi = annotation.sourcePsi
    if (sourcePsi is PsiAnnotation) {
      val value = sourcePsi.findAttributeValue(ATTR_VALUE) as? PsiLiteralValue
      return value?.value as? String
    }
    return null
  }

  private fun getFlaggedApiFromString(evaluator: JavaEvaluator, flag: String): OneOrTwoFlagFields? {
    val separator = flag.lastIndexOf('.')
    if (separator != -1) {
      val packageName = flag.substring(0, separator)
      val fieldName = "FLAG_" + flag.substring(separator + 1).uppercase()
      val primary = findFlagField(evaluator, packageName, "Flags", fieldName)
      val secondary = findFlagField(evaluator, packageName, "ExportedFlags", fieldName)
      if (primary != null) {
        return OneOrTwoFlagFields(primary, secondary)
      } else if (secondary != null) {
        return OneOrTwoFlagFields(secondary, null)
      }
    }

    return null
  }

  private fun findFlagField(evaluator: JavaEvaluator, packageName: String, className: String, fieldName: String): PsiField? {
    return evaluator.findClass("$packageName.$className")?.findFieldByName(fieldName, true)
  }

  /** Is the given [element] within a code block already annotated with the same flagged api as [flag]. */
  private fun isAlreadyAnnotated(evaluator: JavaEvaluator, element: UElement?, flag: PsiField): Boolean {
    var current = element
    while (current != null) {
      if (current is UAnnotated) {
        //noinspection AndroidLintExternalAnnotations
        for (annotation in current.uAnnotations) {
          val (flag1, flag2) = getFlagFieldsFromSource(evaluator, annotation) ?: continue
          if (flag1.isEquivalentTo(flag) || flag2 != null && flag2.isEquivalentTo(flag)) {
            return true
          }
        }
      }
      if (current is UAnnotation) {
        if (TypedefDetector.isTypeDef(current.qualifiedName)) {
          return true
        }
      } else if (current is UFile) {
        // Also consult any package annotations
        val pkg = evaluator.getPackage(current.javaPsi ?: current.sourcePsi)
        if (pkg != null) {
          for (psiAnnotation in pkg.annotations) {
            val annotation = UastFacade.convertElement(psiAnnotation, null) as? UAnnotation ?: continue
            val (flag1, flag2) = getFlagFieldsFromSource(evaluator, annotation) ?: continue
            if (flag1.isEquivalentTo(flag) || flag2 != null && flag2.isEquivalentTo(flag)) {
              return true
            }
          }
        }

        break
      }
      current = current.uastParent
    }

    return false
  }

  /**
   * Is the given [element] inside a flag check (where the class is [flagClass1] and [flagMethodName] is the flag checking method name), or
   * after an early return of the flag not being set?
   */
  private fun isFlagChecked(element: UElement, flagClass1: PsiClass, flagClass2: PsiClass?, flagMethodName: String): Boolean {
    var curr = element.uastParent ?: return false

    var prev = element
    while (curr !is UFile) {
      if (curr is UIfExpression) {
        val condition = curr.condition
        if (prev !== condition) {
          val fromThen = prev == curr.thenExpression
          if (fromThen) {
            if (isFlagExpression(condition, flagClass1, flagClass2, flagMethodName)) {
              return true
            }
          } else {
            // Handle "if (!Flags.X) else <CALL>"
            val op = condition.skipParenthesizedExprDown()
            if (
              op is UUnaryExpression &&
                op.operator == UastPrefixOperator.LOGICAL_NOT &&
                isFlagExpression(op.operand, flagClass1, flagClass2, flagMethodName)
            ) {
              return true
            } else if (
              op is UPolyadicExpression &&
                op.operator == UastBinaryOperator.LOGICAL_OR &&
                (op.operands.any {
                  val nested = it.skipParenthesizedExprDown()
                  nested is UUnaryExpression &&
                    nested.operator == UastPrefixOperator.LOGICAL_NOT &&
                    isFlagExpression(nested.operand, flagClass1, flagClass2, flagMethodName)
                })
            ) {
              return true
            }
          }
        }
      } else if (curr is UPolyadicExpression && curr.operator == UastBinaryOperator.LOGICAL_AND) {
        for (operand in curr.operands) {
          if (operand === curr) {
            break
          } else if (isFlagExpression(operand, flagClass1, flagClass2, flagMethodName)) {
            return true
          }
        }
      } else if (curr is UMethod) {
        // See if there's an early return. We *only* handle a very simple canonical format here;
        // must be first statement in method.
        val body = curr.uastBody
        if (body is UBlockExpression && body.expressions.size > 1) {
          val first = body.expressions[0]
          if (first is UIfExpression) {
            val condition = first.condition.skipParenthesizedExprDown()
            if (
              condition is UUnaryExpression &&
                condition.operator == UastPrefixOperator.LOGICAL_NOT &&
                isFlagExpression(condition.operand, flagClass1, flagClass2, flagMethodName)
            ) {
              // It's a flag check; make sure we just return
              val then = first.thenExpression?.skipParenthesizedExprDown()
              if (then != null && then.isUnconditionalReturn()) {
                return true
              }
            }
          }
        }
      }

      prev = curr
      curr = curr.uastParent ?: break
    }

    return false
  }

  /** Is the given [element] a flag expression (e.g. "Flags.set()") ? */
  private fun isFlagExpression(element: UElement, flagClass1: PsiClass, flagClass2: PsiClass?, flagMethodName: String): Boolean {
    if (element is UReferenceExpression || element is UCallExpression) {
      val resolved = element.tryResolve()
      if (resolved is PsiMethod) {
        if (resolved.name == flagMethodName) {
          val cls = resolved.containingClass
          if (flagClass1.isEquivalentTo(cls) || flagClass2 != null && flagClass2.isEquivalentTo(cls)) {
            return true
          }
        }
      } else if (resolved is PsiField) {
        // Arguably we should look for final fields here, but on the other hand
        // there may be cases where it's initialized later, so it's a bit like
        // Kotlin's "lateinit". Treat them all as constant.
        val initializer = UastFacade.getInitializerBody(resolved)
        if (initializer != null) {
          return isFlagExpression(initializer, flagClass1, flagClass2, flagMethodName)
        }
      }
    } else if (element is UParenthesizedExpression) {
      return isFlagExpression(element.expression, flagClass1, flagClass2, flagMethodName)
    } else if (element is UPolyadicExpression) {
      if (element.operator == UastBinaryOperator.LOGICAL_AND) {
        for (operand in element.operands) {
          if (isFlagExpression(operand, flagClass1, flagClass2, flagMethodName)) {
            return true
          }
        }
      }
    }
    return false
  }
}
