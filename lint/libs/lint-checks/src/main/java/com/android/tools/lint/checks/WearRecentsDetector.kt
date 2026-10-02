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

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_EXCLUDE_FROM_RECENTS
import com.android.SdkConstants.ATTR_NO_HISTORY
import com.android.SdkConstants.ATTR_TASK_AFFINITY
import com.android.SdkConstants.CLASS_ACTIVITY
import com.android.SdkConstants.CLASS_APPLICATION
import com.android.SdkConstants.CLASS_BROADCASTRECEIVER
import com.android.SdkConstants.CLASS_SERVICE
import com.android.SdkConstants.Intent.REF_FLAG_ACTIVITY_CLEAR_TOP
import com.android.SdkConstants.Intent.REF_FLAG_ACTIVITY_NEW_TASK
import com.android.SdkConstants.TAG_ACTIVITY
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScanner
import com.intellij.psi.PsiElement
import java.util.EnumSet
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.getParentOfType
import org.w3c.dom.Element

class WearRecentsDetector : WearDetector(), XmlScanner, SourceCodeScanner {

  companion object Issues {
    private val IMPLEMENTATION =
      Implementation(
        WearRecentsDetector::class.java,
        EnumSet.of(Scope.JAVA_FILE, Scope.MANIFEST),
        Scope.JAVA_FILE_SCOPE,
        Scope.MANIFEST_SCOPE,
      )

    @JvmField
    val ISSUE =
      Issue.create(
          id = "WearRecents",
          briefDescription = "Wear OS: Recents and app resume",
          explanation =
            """
          In recents, correctly represent your app's activities, consistent with device implementation.
        """,
          category = Category.USABILITY,
          severity = Severity.WARNING,
          implementation = IMPLEMENTATION,
          enabledByDefault = true,
        )
        .addMoreInfo("https://developer.android.com/training/wearables/apps/launcher")
  }

  override fun getApplicableElements() = listOf(TAG_ACTIVITY)

  override fun visitElement(context: XmlContext, element: Element) {
    if (!isWearProject) {
      return
    }
    if (element.hasAttributeNS(ANDROID_URI, ATTR_EXCLUDE_FROM_RECENTS)) {
      if (!element.hasAttributeNS(ANDROID_URI, ATTR_NO_HISTORY)) {
        context.report(
          ISSUE,
          context.getLocation(element),
          "In addition to `excludeFromRecents`, set `noHistory` flag to avoid showing this activity in recents",
          fix().name("Set noHistory").set(ANDROID_URI, ATTR_NO_HISTORY, "true").build(),
        )
      }
      return
    }
    if (!element.hasAttributeNS(ANDROID_URI, ATTR_TASK_AFFINITY)) {
      context.report(
        ISSUE,
        context.getLocation(element),
        "Set `taskAffinity` for Wear activities to make them appear correctly in recents",
        fix()
          .alternatives(
            fix().name("Set `taskAffinity`").set(ANDROID_URI, ATTR_TASK_AFFINITY, "").autoFix().build(),
            fix()
              .name("Exclude from recents")
              .composite(
                fix().set(ANDROID_URI, ATTR_EXCLUDE_FROM_RECENTS, "true").build(),
                fix().set(ANDROID_URI, ATTR_NO_HISTORY, "true").build(),
              )
              .autoFix(),
          ),
      )
    }
  }

  override fun getApplicableReferenceNames() = listOf(REF_FLAG_ACTIVITY_NEW_TASK, REF_FLAG_ACTIVITY_CLEAR_TOP)

  override fun visitReference(context: JavaContext, reference: UReferenceExpression, referenced: PsiElement) {
    if (!isWearProject) {
      return
    }
    if (reference.resolvedName == REF_FLAG_ACTIVITY_NEW_TASK && isExempt(context, reference)) {
      return
    }
    context.report(ISSUE, context.getLocation(reference), "Avoid using `FLAG_ACTIVITY_NEW_TASK` and `FLAG_ACTIVITY_CLEAR_TOP`")
  }

  /**
   * Returns `true` if the [reference] to `FLAG_ACTIVITY_NEW_TASK` is enclosed within a non-Activity context class ([Service],
   * [BroadcastReceiver], or [Application]), which requires the flag at runtime when calling `startActivity`. Walks up the enclosing class
   * hierarchy so that calls inside anonymous classes, lambdas, or runnables (e.g. `executor.execute { startActivity(...) }`) within a
   * service are exempted, while calls enclosed within an [Activity] are not.
   */
  private fun isExempt(context: JavaContext, reference: UReferenceExpression): Boolean {
    var curr: UElement? = reference
    while (curr != null) {
      val enclosingClass = curr.getParentOfType<UClass>() ?: break
      if (isActivityClass(context, enclosingClass)) {
        return false
      }
      if (isNonActivityContextClass(context, enclosingClass)) {
        return true
      }
      curr = enclosingClass.uastParent
    }
    return false
  }

  private fun isActivityClass(context: JavaContext, uClass: UClass): Boolean {
    return context.evaluator.inheritsFrom(uClass.javaPsi, CLASS_ACTIVITY, false)
  }

  private fun isNonActivityContextClass(context: JavaContext, uClass: UClass): Boolean {
    val evaluator = context.evaluator
    val psiClass = uClass.javaPsi
    return evaluator.inheritsFrom(psiClass, CLASS_SERVICE, false) ||
      evaluator.inheritsFrom(psiClass, CLASS_BROADCASTRECEIVER, false) ||
      evaluator.inheritsFrom(psiClass, CLASS_APPLICATION, false)
  }
}
