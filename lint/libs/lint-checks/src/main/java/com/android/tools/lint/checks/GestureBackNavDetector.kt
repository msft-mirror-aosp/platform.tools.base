/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.SdkConstants
import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.TAG_APPLICATION
import com.android.SdkConstants.VALUE_FALSE
import com.android.SdkConstants.VALUE_TRUE
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
import com.android.utils.subtag
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiTypes
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.USwitchClauseExpression
import org.jetbrains.uast.UastVisibility
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.skipParenthesizedExprUp

/**
 * Reports:
 * - `KeyEvent.KEYCODE_BACK` in an if/switch condition
 * - overrides of `{Activity,Dialog}.onBackPressed`
 *
 *   Incidents are filtered (and severity may be increased) depending on a manifest flag and the
 *   targetSdkVersion.
 */
class GestureBackNavDetector : Detector(), SourceCodeScanner {
  override fun getApplicableReferenceNames(): List<String> = listOf("KEYCODE_BACK")

  override fun visitReference(
    context: JavaContext,
    reference: UReferenceExpression,
    referenced: PsiElement,
  ) {
    if (
      referenced is PsiField &&
        context.evaluator.isMemberInClass(referenced, "android.view.KeyEvent")
    ) {
      val keycodeBack = skipParenthesizedExprUp(reference.uastParent) ?: return
      val parent = skipParenthesizedExprUp(keycodeBack.uastParent) ?: return
      val ifExpression = skipParenthesizedExprUp(parent.uastParent) ?: return

      val containingMethod = reference.getParentOfType<UMethod>()?.javaPsi ?: return
      // Only report if in a (non-static) method (see below).
      if (context.evaluator.isStatic(containingMethod)) return

      // Only report if the containing method is in an Activity or Dialog, as these classes
      // have AndroidX equivalents that support backward compatible use of `OnBackInvokedDispatcher`
      // as a replacement. In other cases, the reference to KeyEvent probably needs to remain for
      // old versions of Android, so we can't report it.
      if (
        !context.evaluator.isMemberInSubClassOf(
          containingMethod,
          SdkConstants.CLASS_ACTIVITY,
          true,
        ) &&
          !context.evaluator.isMemberInSubClassOf(containingMethod, DIALOG_CLASS, true) &&
          !context.evaluator.isMemberInSubClassOf(
            containingMethod,
            DIALOG_INTERFACE_ON_KEY_LISTENER,
            true,
          )
      ) {
        return
      }

      if (
        ifExpression is UIfExpression ||
          ifExpression is USwitchClauseExpression ||
          parent is USwitchClauseExpression
      ) {
        val message =
          "If intercepting back events, this should be handled through " +
            "the registration of callbacks; " +
            "see https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture"
        val fix =
          fix()
            .url(
              "https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture"
            )
            .build()
        context.report(
          Incident(ISSUE, reference, context.getLocation(keycodeBack), message, fix),
          map(),
        )
      }
    }
  }

  override fun applicableSuperClasses() = listOf("android.app.Activity", "android.app.Dialog")

  override fun visitClass(context: JavaContext, declaration: UClass) {
    for (method in declaration.methods) {
      if (
        method.name != "onBackPressed" ||
          method.visibility != UastVisibility.PUBLIC ||
          method.uastParameters.isNotEmpty() ||
          method.returnType != PsiTypes.voidType()
      )
        continue

      val fix =
        fix()
          .url("https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture")
          .build()

      context.report(
        Incident(
          ISSUE,
          method,
          context.getNameLocation(method),
          "`onBackPressed` is no longer called for back gestures; migrate to AndroidX's backward compatible `OnBackPressedDispatcher`",
          fix,
        ),
        LintMap(),
      )
    }
  }

  override fun filterIncident(context: Context, incident: Incident, map: LintMap): Boolean {
    val project = context.mainProject

    // Don't report for library modules.
    if (project.isLibrary) return false

    val applicationTag =
      project.mergedManifest?.documentElement?.subtag(TAG_APPLICATION) ?: return false
    val flag = applicationTag.getAttributeNS(ANDROID_URI, ENABLE_ON_BACK_INVOKED_CALLBACK)

    if (project.targetSdk < 36) {
      // Only report if the app has opted in.
      return flag == VALUE_TRUE
    } else {
      // Don't report if the app has opted out.
      if (flag == VALUE_FALSE) return false
      incident.severity = Severity.ERROR
      return true
    }
  }

  override fun sameMessage(issue: Issue, new: String, old: String): Boolean {
    return true
  }

  companion object {
    private const val ENABLE_ON_BACK_INVOKED_CALLBACK = "enableOnBackInvokedCallback"

    private const val DIALOG_CLASS = "android.app.Dialog"
    private const val DIALOG_INTERFACE_ON_KEY_LISTENER =
      "android.content.DialogInterface.OnKeyListener"

    @JvmField
    val ISSUE =
      Issue.create(
        id = "GestureBackNavigation",
        briefDescription = "Usage of KeyEvent.KEYCODE_BACK",
        explanation =
          """
          For apps targeting and running on Android 16+ (API 36+), predictive back animations \
          are enabled by default. A back gesture does not trigger `{Activity,Dialog}.onBackPressed`, \
          and does not dispatch `KeyEvent.KEYCODE_BACK`.

          Apps should migrate to AndroidX's backward compatible `OnBackPressedDispatcher`.

          This lint check does not consider per-activity opt-in/opt-out, so you may need to suppress \
          or baseline reported incidents if migrating per-activity.
          """,
        category = Category.CORRECTNESS,
        priority = 7,
        severity = Severity.WARNING,
        implementation = Implementation(GestureBackNavDetector::class.java, Scope.JAVA_FILE_SCOPE),
        androidSpecific = true,
        moreInfo =
          "https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture",
      )
  }
}
