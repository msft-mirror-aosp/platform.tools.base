/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.SdkConstants.CLASS_CONTEXT
import com.android.SdkConstants.CLASS_VIEW
import com.android.SdkConstants.CLASS_VIEWGROUP
import com.android.SdkConstants.DOT_LAYOUT_PARAMS
import com.android.resources.ResourceType
import com.android.tools.lint.client.api.ResourceReference.Companion.get
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getParentOfType

/** Makes sure that custom views use a declare styleable that matches the name of the custom view */
class CustomViewDetector : Detector(), SourceCodeScanner {
  // ---- implements SourceCodeScanner ----
  override fun getApplicableMethodNames(): List<String> {
    return listOf(OBTAIN_STYLED_ATTRIBUTES, WITH_STYLED_ATTRIBUTES)
  }

  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
    val arguments = node.valueArguments
    val size = arguments.size

    val parameterIndex: Int
    if (method.name == OBTAIN_STYLED_ATTRIBUTES) {
      if (!context.evaluator.isMemberInSubClassOf(method, CLASS_CONTEXT, false)) {
        return
      }

      parameterIndex =
        if (size == 1) {
          // obtainStyledAttributes(int[] attrs)
          0
        } else {
          // obtainStyledAttributes(int resid, int[] attrs)
          // obtainStyledAttributes(AttributeSet set, int[] attrs)
          // obtainStyledAttributes(AttributeSet set, int[] attrs, int defStyleAttr, int
          //   defStyleRes)
          1
        }
    } else {
      if (!context.evaluator.isMemberInSubClassOf(method, "androidx.core.content.ContextKt", false)) {
        return
      }

      // withStyledAttributes(Context, AttributeSet set, int[] attrs, int defStyleAttr, int
      // defStyleRes, block)
      // withStyledAttributes(Context, int resId, int[] attrs, int, int, block)
      parameterIndex = 2
    }

    val expression = node.getArgumentForParameter(parameterIndex) ?: return
    val reference = get(expression)
    if (reference == null || reference.type != ResourceType.STYLEABLE) {
      return
    }

    val cls = node.getParentOfType(UClass::class.java, false) ?: return

    @Suppress("UElementAsPsi") val className = cls.name
    val styleableName = reference.name
    val psiClass = cls.javaPsi
    if (context.evaluator.extendsClass(psiClass, CLASS_VIEW, false)) {
      if (styleableName != className) {
        val message =
          "By convention, the custom view (`$className`) and the declare-styleable " +
            "(`$styleableName`) should have the same name (various editor features " +
            "rely on this convention)"
        context.report(ISSUE, node, context.getLocation(expression), message)
      }
    } else if (context.evaluator.extendsClass(psiClass, CLASS_VIEWGROUP + DOT_LAYOUT_PARAMS, false)) {
      val outer = cls.getParentOfType(UClass::class.java, true) ?: return
      @Suppress("UElementAsPsi") val layoutClassName = outer.name
      val expectedName = layoutClassName + "_Layout"
      if (styleableName != expectedName) {
        val message =
          "By convention, the declare-styleable (`$styleableName`) for a layout parameter " +
            "class (`$className`) is expected to be the surrounding class " +
            "(`$layoutClassName`) plus \"`_Layout`\", e.g. `$expectedName`. " +
            "(Various editor features rely on this convention.)"
        context.report(ISSUE, node, context.getLocation(expression), message)
      }
    }
  }

  companion object {
    private val IMPLEMENTATION = Implementation(CustomViewDetector::class.java, Scope.JAVA_FILE_SCOPE)

    /** Mismatched style and class names */
    @JvmField
    val ISSUE: Issue =
      Issue.create(
        id = "CustomViewStyleable",
        briefDescription = "Mismatched Styleable/Custom View Name",
        explanation =
          """
          The convention for custom views is to use a `declare-styleable` whose \
          name matches the custom view class name. The IDE relies on this convention \
          such that for example code completion can be offered for attributes \
          in a custom view in layout XML resource files.

          (Similarly, layout parameter classes should use the suffix `_Layout`.)
          """,
        category = Category.CORRECTNESS,
        priority = 6,
        severity = Severity.WARNING,
        implementation = IMPLEMENTATION,
        androidSpecific = true,
      )

    private const val OBTAIN_STYLED_ATTRIBUTES = "obtainStyledAttributes"
    private const val WITH_STYLED_ATTRIBUTES = "withStyledAttributes"
  }
}
