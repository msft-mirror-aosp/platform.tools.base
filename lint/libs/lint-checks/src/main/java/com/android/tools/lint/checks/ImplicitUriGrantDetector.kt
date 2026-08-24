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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiVariable
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.skipParenthesizedExprDown
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Detector that warns when intents carrying URI payloads (e.g. ACTION_SEND, ACTION_IMAGE_CAPTURE) are dispatched to external apps without
 * explicit URI grant flags (FLAG_GRANT_READ_URI_PERMISSION / FLAG_GRANT_WRITE_URI_PERMISSION).
 */
class ImplicitUriGrantDetector : Detector(), SourceCodeScanner {

  override fun getApplicableMethodNames(): List<String> = DISPATCH_METHODS

  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
    if (!isDispatchMethod(context, method)) {
      return
    }

    val rawIntentArg = findIntentArgument(context, node) ?: return
    val unwrappedIntent = unwrapIntent(rawIntentArg)
    val surroundingMethod = node.getParentOfType<UMethod>() ?: node.getParentOfType<UElement>() ?: return
    val targetVariable = (unwrappedIntent.skipParenthesizedExprDown() as? UReferenceExpression)?.resolve() as? PsiVariable

    // 1. Intra-App Filter: Skip if explicitly targeting an internal class or own package
    if (isIntraAppExplicitIntent(context, surroundingMethod, rawIntentArg, targetVariable)) {
      return
    }

    // 2. Action Filter: Verify action is SEND, SEND_MULTIPLE, or Media capture
    val action = resolveIntentAction(surroundingMethod, rawIntentArg, targetVariable) ?: return
    if (action !in IMPACTED_ACTIONS) {
      return
    }

    // 3. Payload Filter: Verify if URI payload (e.g. EXTRA_STREAM, EXTRA_OUTPUT) is attached
    if (!hasUriPayloadAttached(surroundingMethod, rawIntentArg, targetVariable)) {
      return
    }

    // 4. Flags Check: Verify whether required grant flags are present
    val isCaptureAction = action in CAPTURE_ACTIONS
    val isMissingReadGrant = !hasGrantFlag(surroundingMethod, rawIntentArg, targetVariable, FLAG_GRANT_READ_URI_PERMISSION)
    val isMissingWriteGrant =
      isCaptureAction && !hasGrantFlag(surroundingMethod, rawIntentArg, targetVariable, FLAG_GRANT_WRITE_URI_PERMISSION)

    if (isMissingReadGrant || isMissingWriteGrant) {
      val fix = createAddFlagsQuickFix(context, node, unwrappedIntent, isMissingReadGrant, isMissingWriteGrant)
      context.report(
        ISSUE,
        node,
        context.getLocation(node),
        "Implicit URI grants for this action are discontinued from Android 18 onwards. " +
          "Please set the grant explicitly on the intent. " +
          "See https://goo.gle/implicit-uri-grants for more info.",
        fix,
      )
    }
  }

  private fun isDispatchMethod(context: JavaContext, method: PsiMethod): Boolean {
    val containingClass = method.containingClass ?: return true
    val evaluator = context.evaluator
    return DISPATCH_RECEIVER_CLASSES.any { evaluator.extendsClass(containingClass, it, false) }
  }

  private fun findIntentArgument(context: JavaContext, node: UCallExpression): UExpression? =
    node.valueArguments.firstOrNull { isIntentType(context, it) }

  private fun isIntentType(context: JavaContext, expression: UExpression): Boolean {
    val unwrapped = unwrapIntent(expression)
    val type = unwrapped.skipParenthesizedExprDown()?.getExpressionType()
    val canonical = type?.canonicalText

    when {
      canonical == "android.app.PendingIntent" || canonical?.endsWith(".PendingIntent") == true -> return false
      canonical == "android.content.Intent" || canonical?.endsWith(".Intent") == true -> return true
      type != null -> {
        val psiClass = context.evaluator.getTypeClass(type)
        if (psiClass != null && context.evaluator.extendsClass(psiClass, "android.content.Intent", false)) {
          return true
        }
      }
    }

    val source = unwrapped.asSourceString()
    return when {
      source.contains("PendingIntent") -> false
      source.contains("Intent") -> true
      else -> type == null
    }
  }

  private fun unwrapIntent(expression: UExpression): UExpression {
    var current = expression.skipParenthesizedExprDown() ?: expression
    while (true) {
      val unwrapped = current.skipParenthesizedExprDown() ?: current
      val call =
        when (unwrapped) {
          is UCallExpression -> unwrapped
          is UQualifiedReferenceExpression -> unwrapped.selector.skipParenthesizedExprDown() as? UCallExpression
          else -> null
        }
      if (call != null) {
        when (call.methodName) {
          "createChooser" -> {
            current = call.valueArguments.firstOrNull()?.skipParenthesizedExprDown() ?: break
            continue
          }
          "apply",
          "also" -> {
            current = (unwrapped as? UQualifiedReferenceExpression)?.receiver?.skipParenthesizedExprDown() ?: break
            continue
          }
        }
      }
      break
    }
    return current.skipParenthesizedExprDown() ?: current
  }

  private fun isTargetIntentCall(call: UCallExpression, intentExpression: UExpression, targetVariable: PsiElement?): Boolean {
    if (targetVariable != null) {
      val receiver = call.receiver?.skipParenthesizedExprDown()
      if (receiver is UReferenceExpression && receiver.resolve() == targetVariable) {
        return true
      }
      val varDecl = targetVariable.toUElementOfType<UVariable>()
      return varDecl?.uastInitializer != null && isAncestor(varDecl.uastInitializer!!, call)
    }
    return isAncestor(intentExpression, call)
  }

  private fun isTargetIntentProperty(expr: UBinaryExpression, intentExpression: UExpression, targetVariable: PsiElement?): Boolean {
    if (targetVariable != null) {
      val left = expr.leftOperand.skipParenthesizedExprDown()
      if (left is UQualifiedReferenceExpression) {
        val receiver = left.receiver.skipParenthesizedExprDown()
        if (receiver is UReferenceExpression && receiver.resolve() == targetVariable) {
          return true
        }
      }
      val varDecl = targetVariable.toUElementOfType<UVariable>()
      return varDecl?.uastInitializer != null && isAncestor(varDecl.uastInitializer!!, expr)
    }
    return isAncestor(intentExpression, expr)
  }

  private fun isAncestor(ancestor: UElement, node: UElement): Boolean {
    if (ancestor == node) {
      return true
    }
    var uastCurr: UElement? = node
    while (uastCurr != null) {
      if (uastCurr == ancestor) {
        return true
      }
      uastCurr = uastCurr.uastParent
    }
    val ancestorPsi = ancestor.sourcePsi ?: return false
    val nodePsi = node.sourcePsi ?: return false
    val ancestorRange = ancestorPsi.textRange
    val nodeRange = nodePsi.textRange
    if (ancestorRange != null && nodeRange != null && ancestorRange.contains(nodeRange)) {
      return true
    }
    var currentPsi: PsiElement? = nodePsi
    while (currentPsi != null) {
      if (currentPsi == ancestorPsi) {
        return true
      }
      currentPsi = currentPsi.parent
    }
    return false
  }

  private fun isIntraAppExplicitIntent(
    context: JavaContext,
    scope: UElement,
    intentExpression: UExpression,
    targetVariable: PsiElement?,
  ): Boolean {
    var isIntraApp = false

    scope.accept(
      object : AbstractUastVisitor() {
        override fun visitCallExpression(node: UCallExpression): Boolean {
          if (!isTargetIntentCall(node, intentExpression, targetVariable)) {
            return super.visitCallExpression(node)
          }
          // Check constructor: new Intent(context, LocalActivity.class)
          if (node.classReference != null || node.methodName == null) {
            val targetClass = node.valueArguments.getOrNull(1)?.getExpressionType()?.canonicalText
            if (targetClass?.startsWith("java.lang.Class") == true) {
              isIntraApp = true
            }
          }

          when (node.methodName) {
            "setClass" -> isIntraApp = true
            "setPackage",
            "setClassName" -> {
              val firstArg = node.valueArguments.firstOrNull()
              if (firstArg != null && isLocalPackageOrContext(context, firstArg)) {
                isIntraApp = true
              }
            }
            "setComponent" -> {
              val firstArg = node.valueArguments.firstOrNull()
              if (firstArg != null && isLocalComponentName(context, firstArg)) {
                isIntraApp = true
              }
            }
          }
          return super.visitCallExpression(node)
        }

        override fun visitBinaryExpression(node: UBinaryExpression): Boolean {
          if (!isTargetIntentProperty(node, intentExpression, targetVariable)) {
            return super.visitBinaryExpression(node)
          }
          val leftOperandString = node.leftOperand.asSourceString().replace("`", "").replace("(", "").replace(")", "").trim()
          when {
            leftOperandString == "package" ||
              leftOperandString.endsWith(".package") ||
              leftOperandString == "className" ||
              leftOperandString.endsWith(".className") -> {
              if (isLocalPackageOrContext(context, node.rightOperand)) {
                isIntraApp = true
              }
            }
            leftOperandString == "component" || leftOperandString.endsWith(".component") -> {
              if (isLocalComponentName(context, node.rightOperand)) {
                isIntraApp = true
              }
            }
          }
          return super.visitBinaryExpression(node)
        }
      }
    )

    return isIntraApp
  }

  private fun isLocalPackageOrContext(context: JavaContext, expression: UExpression): Boolean {
    // 1. Android Context instance (this, context, requireContext(), etc.)
    val type = expression.getExpressionType()?.canonicalText
    if (type?.contains("Context") == true) {
      return true
    }

    // 2. Direct call to getPackageName()
    if ((expression as? UCallExpression)?.methodName == "getPackageName") {
      return true
    }

    // 3. String constant matching project's package name
    val constantValue = ConstantEvaluator.evaluateString(null, expression, false)
    val projectPackage = context.project.getPackage()
    if (constantValue != null && projectPackage != null && constantValue == projectPackage) {
      return true
    }

    // 4. Resolve reference expression
    val resolved = (expression as? UReferenceExpression)?.resolve()
    return when (resolved) {
      is PsiMethod -> resolved.name == "getPackageName"
      is PsiVariable -> {
        val initializer = resolved.toUElementOfType<UVariable>()?.uastInitializer
        initializer != null && isLocalPackageOrContext(context, initializer)
      }
      else -> false
    }
  }

  private fun isLocalComponentName(context: JavaContext, expression: UExpression): Boolean {
    var isLocal = false
    expression.accept(
      object : AbstractUastVisitor() {
        override fun visitCallExpression(node: UCallExpression): Boolean {
          val firstArg = node.valueArguments.firstOrNull()
          if (firstArg != null && isLocalPackageOrContext(context, firstArg)) {
            isLocal = true
          }
          return super.visitCallExpression(node)
        }
      }
    )
    if (isLocal) {
      return true
    }

    val resolved = (expression as? UReferenceExpression)?.resolve()
    if (resolved != null) {
      val uVar = resolved.toUElementOfType<UVariable>()
      val initializer = uVar?.uastInitializer
      if (initializer != null && isLocalComponentName(context, initializer)) {
        return true
      }
    }

    return false
  }

  private fun resolveIntentAction(scope: UElement, intentExpression: UExpression, targetVariable: PsiElement?): String? {
    var resolvedAction: String? = null
    scope.accept(
      object : AbstractUastVisitor() {
        override fun visitCallExpression(node: UCallExpression): Boolean {
          if (!isTargetIntentCall(node, intentExpression, targetVariable)) {
            return super.visitCallExpression(node)
          }
          node.valueArguments.mapNotNull { evaluateActionString(it) }.firstOrNull { it in IMPACTED_ACTIONS }?.let { resolvedAction = it }
          return super.visitCallExpression(node)
        }
      }
    )
    return resolvedAction
  }

  private fun evaluateActionString(expression: UExpression): String? {
    ConstantEvaluator.evaluateString(null, expression, false)?.let {
      return it
    }
    val source = expression.asSourceString()
    return when {
      source.endsWith("ACTION_SEND") -> "android.intent.action.SEND"
      source.endsWith("ACTION_SEND_MULTIPLE") -> "android.intent.action.SEND_MULTIPLE"
      source.endsWith("ACTION_IMAGE_CAPTURE") -> "android.media.action.IMAGE_CAPTURE"
      source.endsWith("ACTION_IMAGE_CAPTURE_SECURE") -> "android.media.action.IMAGE_CAPTURE_SECURE"
      source.endsWith("ACTION_VIDEO_CAPTURE") -> "android.media.action.VIDEO_CAPTURE"
      source.endsWith("ACTION_MOTION_PHOTO_CAPTURE") -> "android.media.action.MOTION_PHOTO_CAPTURE"
      source.endsWith("ACTION_MOTION_PHOTO_CAPTURE_SECURE") -> "android.media.action.MOTION_PHOTO_CAPTURE_SECURE"
      else -> null
    }
  }

  private fun hasUriPayloadAttached(scope: UElement, intentExpression: UExpression, targetVariable: PsiElement?): Boolean {
    var foundPayload = false

    scope.accept(
      object : AbstractUastVisitor() {
        override fun visitCallExpression(node: UCallExpression): Boolean {
          if (!isTargetIntentCall(node, intentExpression, targetVariable)) {
            return super.visitCallExpression(node)
          }
          when (node.methodName) {
            "setData",
            "setClipData" -> foundPayload = true
            "putExtra",
            "putParcelableArrayListExtra" -> {
              val hasPayload = node.valueArguments.any { evaluatePayloadKey(it) in URI_PAYLOAD_KEYS }
              if (hasPayload) {
                foundPayload = true
              }
            }
          }
          return super.visitCallExpression(node)
        }

        override fun visitBinaryExpression(node: UBinaryExpression): Boolean {
          if (!isTargetIntentProperty(node, intentExpression, targetVariable)) {
            return super.visitBinaryExpression(node)
          }
          val leftOperandString = node.leftOperand.asSourceString().replace("`", "").replace("(", "").replace(")", "").trim()
          if (
            leftOperandString == "data" ||
              leftOperandString.endsWith(".data") ||
              leftOperandString == "clipData" ||
              leftOperandString.endsWith(".clipData")
          ) {
            foundPayload = true
          }
          return super.visitBinaryExpression(node)
        }
      }
    )
    return foundPayload
  }

  private fun evaluatePayloadKey(expression: UExpression): String? {
    ConstantEvaluator.evaluateString(null, expression, false)?.let {
      return it
    }
    val source = expression.asSourceString()
    return when {
      source.endsWith("EXTRA_STREAM") -> "android.intent.extra.STREAM"
      source.endsWith("EXTRA_OUTPUT") -> "output"
      else -> null
    }
  }

  private fun hasGrantFlag(
    scope: UElement,
    intentExpression: UExpression,
    targetVariable: PsiElement?,
    flagMask: Int,
  ): Boolean {
    var hasFlag = false

    scope.accept(
      object : AbstractUastVisitor() {
        override fun visitCallExpression(node: UCallExpression): Boolean {
          if (!isTargetIntentCall(node, intentExpression, targetVariable)) {
            return super.visitCallExpression(node)
          }
          when (node.methodName) {
            "addFlags",
            "setFlags" -> {
              val containsFlag =
                node.valueArguments.any { argument ->
                  val flagValue = evaluateFlagValue(argument)
                  flagValue != null && (flagValue and flagMask) == flagMask
                }
              if (containsFlag) {
                hasFlag = true
              }
            }
          }
          return super.visitCallExpression(node)
        }

        override fun visitBinaryExpression(node: UBinaryExpression): Boolean {
          if (!isTargetIntentProperty(node, intentExpression, targetVariable)) {
            return super.visitBinaryExpression(node)
          }
          val leftOperandString = node.leftOperand.asSourceString().replace("`", "").replace("(", "").replace(")", "").trim()
          if (leftOperandString == "flags" || leftOperandString.endsWith(".flags")) {
            val flagValue = evaluateFlagValue(node.rightOperand)
            if (flagValue != null && (flagValue and flagMask) == flagMask) {
              hasFlag = true
            }
          }
          return super.visitBinaryExpression(node)
        }
      }
    )
    return hasFlag
  }

  private fun evaluateFlagValue(expression: UExpression): Int? {
    (ConstantEvaluator.evaluate(null, expression) as? Number)?.toInt()?.let {
      return it
    }
    val source = expression.asSourceString()
    var flag = 0
    if (source.contains("FLAG_GRANT_READ_URI_PERMISSION")) {
      flag = flag or FLAG_GRANT_READ_URI_PERMISSION
    }
    if (source.contains("FLAG_GRANT_WRITE_URI_PERMISSION")) {
      flag = flag or FLAG_GRANT_WRITE_URI_PERMISSION
    }
    return if (flag != 0) flag else null
  }

  private fun createAddFlagsQuickFix(
    context: JavaContext,
    node: UElement,
    intentExpression: UExpression,
    isMissingReadGrant: Boolean,
    isMissingWriteGrant: Boolean,
  ): LintFix? {
    val refExpr = intentExpression.skipParenthesizedExprDown() as? UReferenceExpression ?: return null
    if (refExpr.resolve() !is PsiVariable) {
      return null
    }
    val intentVariableName = refExpr.resolvedName ?: return null

    val isKotlin = isKotlin(node.lang)
    val bitwiseOr = if (isKotlin) " or " else " | "
    val terminator = if (isKotlin) "" else ";"

    val flagString =
      when {
        isMissingReadGrant && isMissingWriteGrant ->
          "Intent.FLAG_GRANT_READ_URI_PERMISSION${bitwiseOr}Intent.FLAG_GRANT_WRITE_URI_PERMISSION"
        isMissingWriteGrant -> "Intent.FLAG_GRANT_WRITE_URI_PERMISSION"
        else -> "Intent.FLAG_GRANT_READ_URI_PERMISSION"
      }

    val indent = getLineIndent(context, node)
    return fix()
      .name("Add explicit URI grant flags")
      .replace()
      .pattern("(.*)")
      .with("$intentVariableName.addFlags($flagString)$terminator\n$indent\\k<1>")
      .reformat(true)
      .build()
  }

  private fun getLineIndent(context: JavaContext, node: UElement): String {
    val contents = context.getContents() ?: return ""
    val offset = context.getLocation(node).start?.offset ?: return ""
    var lineStart = offset - 1
    while (lineStart >= 0 && contents[lineStart] != '\n') {
      lineStart--
    }
    lineStart++
    var indentEnd = lineStart
    while (indentEnd < offset && (contents[indentEnd] == ' ' || contents[indentEnd] == '\t')) {
      indentEnd++
    }
    return contents.substring(lineStart, indentEnd)
  }

  companion object {
    private const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
    private const val FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002

    private val DISPATCH_METHODS = listOf("startActivity", "startActivityForResult", "startActivities", "launch")

    private val DISPATCH_RECEIVER_CLASSES =
      listOf(
        "android.content.Context",
        "android.app.Fragment",
        "androidx.fragment.app.Fragment",
        "androidx.activity.result.ActivityResultLauncher",
      )

    private val URI_PAYLOAD_KEYS = setOf("android.intent.extra.STREAM", "output")

    private val SEND_ACTIONS = setOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE")

    private val CAPTURE_ACTIONS =
      setOf(
        "android.media.action.IMAGE_CAPTURE",
        "android.media.action.IMAGE_CAPTURE_SECURE",
        "android.media.action.VIDEO_CAPTURE",
        "android.media.action.MOTION_PHOTO_CAPTURE",
        "android.media.action.MOTION_PHOTO_CAPTURE_SECURE",
      )

    private val IMPACTED_ACTIONS = SEND_ACTIONS + CAPTURE_ACTIONS

    @JvmField
    val ISSUE: Issue =
      Issue.create(
        id = "MissingExplicitUriGrant",
        briefDescription = "Explicit URI grant flags required for Intent actions",
        explanation =
          """
          Starting from Android 18 onwards, the system will no longer automatically \
          grant URI read/write permissions when delivering intents with URI payloads \
          (such as ACTION_SEND or ACTION_IMAGE_CAPTURE) to external apps. Sender \
          applications must explicitly set FLAG_GRANT_READ_URI_PERMISSION or \
          FLAG_GRANT_WRITE_URI_PERMISSION on the intent. \
          See https://goo.gle/implicit-uri-grants for more info.
          """,
        category = Category.SECURITY,
        priority = 6,
        severity = Severity.WARNING,
        moreInfo = "https://goo.gle/implicit-uri-grants",
        implementation = Implementation(ImplicitUriGrantDetector::class.java, Scope.JAVA_FILE_SCOPE),
      )
  }
}
