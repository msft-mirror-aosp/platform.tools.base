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

import com.android.SdkConstants.CLASS_CONTEXT
import com.android.SdkConstants.CLASS_INTENT
import com.android.tools.lint.client.api.TYPE_STRING
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.isKotlin
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.getQualifiedName
import org.jetbrains.uast.util.isConstructorCall

/**
 * Reports `Intent` constructors if the following conditions hold:
 *
 * - The intent is constructed without a `packageContext: Context` argument.
 * - We do not see `setClass[Name]`, `setPackage`, nor `setComponent`.
 * - We see a send action (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`) or media capture action (`ACTION_IMAGE_CAPTURE`,
 *   `ACTION_IMAGE_CAPTURE_SECURE`, ...) flow into the intent via the constructor, or `setAction`.
 * - A URI payload is attached to the intent via `putExtra` (`Intent.EXTRA_STREAM` for a send action; `MediaStore.EXTRA_OUTPUT` for a media
 *   capture action).
 * - We see the intent being passed into a method that launches the intent (`startActivity`, `launch`, ...), or into `createChooser`.
 * - We do not see `FLAG_GRANT_READ_URI_PERMISSION` (for send actions)
 * - We do not see `FLAG_GRANT_READ_URI_PERMISSION` or `FLAG_GRANT_WRITE_URI_PERMISSION` (for capture actions).
 * - The intent does not escape.
 *
 * Why: Starting from Android 18 onwards, the system will no longer implicitly grant URI read/write permissions for send or media capture
 * actions. Sender applications must explicitly set `FLAG_GRANT_READ_URI_PERMISSION` or `FLAG_GRANT_WRITE_URI_PERMISSION` on the intent.
 *
 * We do not check `setData` or `setClipData` because these did not trigger an implicit permission grant.
 *
 * Note: for calls to `Intent.createChooser(intent)`, the app must add the flags to the passed intent, not to the returned intent.
 *
 * False-negative: If we see any evidence of a package (application ID) being set, then we give up. The problem might still remain, but
 * skipping these cases avoids possible false-positives where the permission flag(s) are not needed. E.g. if the intent is targeting the
 * current app. E.g. if the app has explicitly granted another app permission via `Context.grantUriPermission()` or in its manifest.
 *
 * False-positive: We don't check if the launch methods (`startActivity`, `launch`, etc.) are on any particular class. This is common in our
 * other Intent detectors. This could lead to a false-positive because it could be some custom method that sets flags before _actually_
 * launching the intent. We assume the risk of this is low, and it means we are likely to handle various utility/compat versions of these
 * methods.
 */
class ImplicitUriGrantDetector : Detector(), SourceCodeScanner {

  // TODO: Track ShareCompat.IntentBuilder(context) ... .intent ->

  // TODO: Handle setClipData? ClipData never triggered an implicit permission grant, so apps that work on Android 17 should
  //  work on Android 18. However, forgetting to set the permission flag is still possible, so we could warn about it.
  //  But we may also introduce false-positives, as ClipData in an Intent can technically contain other content, not just URIs
  //  (although this is perhaps quite rare). If we see setClipData, we could run another DataFlowAnalyzer for ClipData, but that is
  //  a lot of effort for just this one scenario.

  override fun getApplicableConstructorTypes(): List<String> = listOf(CLASS_INTENT)

  override fun visitConstructor(context: JavaContext, node: UCallExpression, constructor: PsiMethod) {
    for (parameter in constructor.parameterList.parameters) {
      when (parameter.type.canonicalText) {
        CLASS_INTENT -> {
          // Intent being passed another intent.
          // Skip this, as we can track from the original intent.
          return
        }
        CLASS_CONTEXT -> {
          // `packageContext: Context` argument.
          // We give up if the package (application id) is being set.
          return
        }
      }
    }

    val parentMethod = node.getParentOfType<UMethod>() ?: return
    val analyzer = IntentDataFlowAnalyzer(context, node)
    parentMethod.accept(analyzer)
    analyzer.reportIncident()
  }

  private class IntentDataFlowAnalyzer(
    val context: JavaContext,
    val startNode: UCallExpression,
  ) : EscapeCheckingDataFlowAnalyzer(setOf(startNode)) {

    // Note: we use "escaped" as our "give up" property.
    var seenSendAction = false
    var seenCaptureAction = false
    var seenSendPayload = false
    var seenCapturePayload = false
    var seenReadFlag = false
    var seenWriteFlag = false
    var seenLaunchCall = false

    /**
     * If we see a call to `setFlag`, then we do not try to provide a quick-fix that adds `addFlag`, since the flags probably get
     * overwritten.
     */
    var seenSetFlag = false

    init {
      inspectConstructor(startNode)
    }

    override fun visitElement(node: UElement): Boolean {
      // Minor optimization: stop visiting if the Intent has escaped.
      return escaped
    }

    fun reportIncident() {
      if (escaped) return

      // If we failed to resolve something then we may have missed a call that sets the flags.
      if (failedResolve) return

      if (!seenLaunchCall) return

      if (!(seenSendAction && seenSendPayload) && !(seenCaptureAction && seenCapturePayload)) return

      val missingRead = !seenReadFlag
      val missingWrite = !seenWriteFlag && (seenCaptureAction && seenCapturePayload)

      if (!missingRead && !missingWrite) return

      val fix =
        when {
          seenSetFlag -> null
          else -> createAddFlagsQuickFix(startNode, missingRead, missingWrite)
        }

      context.report(
        ISSUE,
        startNode,
        context.getLocation(startNode),
        getMessage(missingRead, missingWrite),
        fix,
      )
    }

    private fun getMessage(missingRead: Boolean, missingWrite: Boolean): String {
      val flags =
        when {
          missingRead && missingWrite -> "`FLAG_GRANT_READ_URI_PERMISSION` and `FLAG_GRANT_WRITE_URI_PERMISSION`"
          missingWrite -> "`FLAG_GRANT_WRITE_URI_PERMISSION`"
          else -> "`FLAG_GRANT_READ_URI_PERMISSION`"
        }
      return "This intent attaches a URI but is missing $flags; the receiving app will not be granted access to the URI on " +
        "Android 18 and higher"
    }

    private fun createAddFlagsQuickFix(
      node: UElement,
      missingRead: Boolean,
      missingWrite: Boolean,
    ): LintFix {

      val bitwiseOr =
        when {
          isKotlin(node.lang) -> " or "
          else -> " | "
        }

      val (name, flagString) =
        when {
          missingRead && missingWrite ->
            "Add FLAG_GRANT_READ_URI_PERMISSION and FLAG_GRANT_WRITE_URI_PERMISSION" to
              "android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION${bitwiseOr}android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION"
          missingWrite -> "Add FLAG_GRANT_WRITE_URI_PERMISSION" to "android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION"
          else -> "Add FLAG_GRANT_READ_URI_PERMISSION" to "android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION"
        }

      return LintFix.create().name(name).replace().end().with(".addFlags($flagString)").shortenNames().reformat(true).build()
    }

    private fun inspectConstructor(call: UCallExpression) {
      val constructor =
        call.resolve()
          ?: run {
            escaped = true
            return
          }

      // First arg can be action String.
      val type = constructor.parameterList.parameters.firstOrNull()?.type?.canonicalText ?: return
      if (type != TYPE_STRING) return
      val arg = call.getArgumentForParameter(0) ?: return
      checkActionString(arg)
    }

    private fun checkActionString(expression: UExpression) {
      val actionString = ConstantEvaluator.evaluateString(context, expression, false) ?: return
      if (actionString.isEmpty()) return

      when (actionString) {
        // Value of: Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE
        "android.intent.action.SEND",
        "android.intent.action.SEND_MULTIPLE" -> seenSendAction = true

        // Value of: MediaStore.ACTION_IMAGE_CAPTURE, MediaStore...., etc.
        "android.media.action.IMAGE_CAPTURE",
        "android.media.action.IMAGE_CAPTURE_SECURE",
        "android.media.action.VIDEO_CAPTURE",
        "android.media.action.MOTION_PHOTO_CAPTURE",
        "android.media.action.MOTION_PHOTO_CAPTURE_SECURE" -> seenCaptureAction = true
      }
    }

    private fun checkPayloadKey(expression: UExpression) {
      val key = ConstantEvaluator.evaluateString(context, expression, false) ?: return
      if (key.isEmpty()) return

      when (key) {
        // Value of: Intent.EXTRA_STREAM
        "android.intent.extra.STREAM" -> seenSendPayload = true
        // Value of: MediaStore.EXTRA_OUTPUT
        "output" -> seenCapturePayload = true
      }
    }

    private fun checkFlag(expression: UExpression) {
      // Note: we aggressively give up if we cannot evaluate a flag argument.
      // TODO: Could try to handle final private fields/properties.
      val flag =
        (ConstantEvaluator.evaluate(context, expression) as? Number)?.toInt()
          ?: run {
            escaped = true
            return
          }

      if (flag and FLAG_GRANT_READ_URI_PERMISSION != 0) {
        seenReadFlag = true
      }

      if (flag and FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
        seenWriteFlag = true
      }

      // Minor optimization: if we have seen both flags already then we can give up.
      if (seenReadFlag && seenWriteFlag) {
        escaped = true
      }
    }

    override fun receiver(call: UCallExpression) {
      when (call.methodName) {
        "setClass",
        "setClassName",
        "setPackage",
        "setComponent" -> {
          // We give up if the package (application id) is being set.
          escaped = true
        }
        "setAction" -> {
          call.valueArguments.firstOrNull()?.let { action ->
            checkActionString(action)
          }
        }
        "putExtra",
        "putParcelableArrayListExtra" -> {
          call.valueArguments.firstOrNull()?.let { key ->
            checkPayloadKey(key)
          }
        }
        "addFlags" -> {
          // Note: we aggressively give up if we cannot evaluate a flag argument.
          val flagExpression =
            call.valueArguments.firstOrNull()
              ?: run {
                escaped = true
                return
              }
          checkFlag(flagExpression)
        }
        "setFlags" -> {
          // We track seeing setFlags to skip providing a quick-fix.
          seenSetFlag = true
          // Note: we treat setFlags like addFlags, even though setFlags _replaces_ the flags. So we
          // could see `addFlags(FLAG_GRANT_READ_URI_PERMISSION)` followed by
          // `setFlags(0)`, and we won't report (false-negative).
          // Note: we aggressively give up if we cannot evaluate a flag argument.
          val flagExpression =
            call.valueArguments.firstOrNull()
              ?: run {
                escaped = true
                return
              }
          checkFlag(flagExpression)
        }
        else -> super.receiver(call)
      }
    }

    override fun argument(call: UCallExpression, reference: UElement) {
      when (call.methodName) {
        "startActivity",
        "startActivityForResult",
        "startActivityIfNeeded",
        "startActivityFromFragment",
        // PendingIntent.getActivity
        "getActivity",
        "launch",
        "createChooser" -> {
          seenLaunchCall = true
          return
        }
      }
      // If an Intent is being constructed from this Intent, we track it.
      if (call.isConstructorCall() && call.classReference.getQualifiedName() == CLASS_INTENT) {
        track(call, reference)
        return
      }
      // The Intent is passed somewhere that we cannot follow; it might have the flags set there, so
      // we must give up.
      super.argument(call, reference)
    }
  }

  companion object {
    private const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
    private const val FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002

    @JvmField
    val ISSUE: Issue =
      Issue.create(
        id = "MissingExplicitUriGrant",
        briefDescription = "Explicit URI grant flags required for Intent actions",
        explanation =
          """
          Starting from Android 18 onwards, the system will no longer automatically \
          grant URI read/write permissions for send or media capture intents (such as `ACTION_SEND` or `ACTION_IMAGE_CAPTURE`) \
          with URI payloads. Sender applications must explicitly set `FLAG_GRANT_READ_URI_PERMISSION` or \
          `FLAG_GRANT_WRITE_URI_PERMISSION` on the intent.
          """,
        category = Category.CORRECTNESS,
        priority = 6,
        severity = Severity.WARNING,
        moreInfo = "https://goo.gle/implicit-uri-grants",
        implementation = Implementation(ImplicitUriGrantDetector::class.java, Scope.JAVA_FILE_SCOPE),
      )
  }
}
