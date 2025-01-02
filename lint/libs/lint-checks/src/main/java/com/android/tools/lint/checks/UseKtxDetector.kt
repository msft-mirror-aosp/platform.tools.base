/*
 * Copyright (C) 2025 The Android Open Source Project
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
import com.android.tools.lint.detector.api.BooleanOption
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.XmlScanner
import com.android.tools.lint.detector.api.isBelow
import com.android.tools.lint.detector.api.isKotlin
import com.android.tools.lint.detector.api.nextStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.ULoopExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.USwitchExpression
import org.jetbrains.uast.UTryExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.skipParenthesizedExprDown
import org.jetbrains.uast.skipParenthesizedExprUp
import org.jetbrains.uast.tryResolve
import org.jetbrains.uast.visitor.AbstractUastVisitor

/** Suggests replacements in Kotlin code for KTX constructs. */
class UseKtxDetector : Detector(), SourceCodeScanner, XmlScanner {
  override fun getApplicableUastTypes(): List<Class<out UElement>> =
    listOf(UCallExpression::class.java)

  override fun createUastHandler(context: JavaContext): UElementHandler? {
    val sourcePsi = context.uastFile?.sourcePsi ?: return null
    if (!isKotlin(sourcePsi.language)) {
      return null
    }
    return object : UElementHandler() {
      override fun visitCallExpression(node: UCallExpression) {
        val method = node.resolve() ?: return
        val name = method.name
        checkBlockExtensions(context, node, method, name)
      }
    }
  }

  private fun checkBlockExtensions(
    context: JavaContext,
    node: UCallExpression,
    method: PsiMethod,
    name: String,
  ) {
    when (name) {
      "obtainStyledAttributes" -> {
        checkBlock(
          USE_KTX,
          context,
          node,
          method,
          name,
          "android.content.Context",
          "android.content.res.TypedArray",
          "Context",
          "withStyledAttributes",
          "androidx.core.content.withStyledAttributes",
          "androidx.core.content.ContextKt",
          isTarget = { _, methodName, targetMethod, _ ->
            methodName == "recycle" && targetMethod.isInClass("android.content.res.TypedArray")
          },
          // For the obtainStyledAttributes(int[] attrs) overload, we have to
          // introduce a null parameter; the extension method doesn't match a single IntArray.
          replaceArgList =
            if (method.parameterList.parametersCount == 1)
              "(null, ${node.valueArguments.firstOrNull()?.sourcePsi?.text}"
            else "",
        )
      }
      "edit" -> {
        checkBlock(
          USE_KTX,
          context,
          node,
          method,
          name,
          "android.content.SharedPreferences",
          "android.content.SharedPreferences.Editor",
          "SharedPreferences",
          "edit",
          "androidx.core.content.edit",
          "androidx.core.content.SharedPreferencesKt",
          isTarget = { _, methodName, targetMethod, _ ->
            (methodName == "apply" || methodName == "commit") &&
              targetMethod.isInClass("android.content.SharedPreferences.Editor")
          },
        )
      }
      "beginTransaction",
      "beginTransactionNonExclusive" -> {
        checkBlock(
          USE_KTX,
          context,
          node,
          method,
          name,
          "android.database.sqlite.SQLiteDatabase",
          "android.database.sqlite.SQLiteDatabase",
          "SQLiteDatabase",
          "transaction",
          "androidx.core.database.sqlite.transaction",
          "androidx.core.database.sqlite.SQLiteDatabaseKt",
          isTarget = { _, methodName, targetMethod, _ ->
            methodName == "endTransaction" &&
              targetMethod.isInClass("android.database.sqlite.SQLiteDatabase")
          },
          isRequiredCall = { targetMethod ->
            val methodName = targetMethod.name
            methodName == "setTransactionSuccessful" &&
              targetMethod.isInClass("android.database.sqlite.SQLiteDatabase")
          },
          replaceArgList = if (name == "beginTransactionNonExclusive") "(exclusive = false" else "",
        )
      }
      "save" -> {
        var newName = "withSave"
        var replaceArgs = ""
        var deleteName: String? = null
        val parent = skipParenthesizedExprUp(node.uastParent)
        if (parent is UQualifiedReferenceExpression) {
          val receiver = parent.receiver.skipParenthesizedExprDown()
          val next = skipParenthesizedExprUp(node.nextStatement())
          if (next is UQualifiedReferenceExpression) {
            val nextSelector = next.selector.skipParenthesizedExprDown()
            if (nextSelector is UCallExpression) {
              val nextName = nextSelector.methodName
              val nextReceiver = next.receiver.skipParenthesizedExprDown().tryResolve()
              //noinspection LintImplPsiEquals
              if (nextReceiver != null && nextReceiver == receiver.tryResolve()) {
                val mappedName =
                  when (nextName) {
                    "translate" -> "withTranslation"
                    "rotate" -> "withRotation"
                    "scale" -> "withScale"
                    "skew" -> "withSkew"
                    "concat" -> "withMatrix"
                    "clipRect",
                    "clipPath" -> {
                      val lastType =
                        nextSelector.valueArguments.lastOrNull()?.getExpressionType()?.canonicalText
                      if (lastType == "android.graphics.Region.Op") {
                        return
                      }
                      "withClip"
                    }
                    else -> null
                  }
                if (mappedName != null) {
                  deleteName = nextName
                  newName = mappedName
                  replaceArgs =
                    (nextSelector.sourcePsi as? KtCallExpression)?.valueArgumentList?.text ?: ""
                }
              }
            }
          }
        }
        checkBlock(
          USE_KTX,
          context,
          node,
          method,
          name,
          "android.graphics.Canvas",
          "android.graphics.Canvas",
          "Canvas",
          newName,
          "androidx.core.graphics.$newName",
          "androidx.core.graphics.CanvasKt",
          isTarget = { call, methodName, targetMethod, variable ->
            var ok = false
            if (methodName == "restore" || methodName == "restoreToCount") {
              ok = targetMethod.isInClass("android.graphics.Canvas")
              if (ok) {
                if (methodName == "restoreToCount") {
                  // Check that we're passing in the same variable
                  val reference = call.valueArguments.firstOrNull()?.tryResolve()
                  if (reference != null) {
                    //noinspection LintImplPsiEquals
                    ok = reference == variable?.javaPsi || reference == variable?.sourcePsi
                  }
                }
              }
            }
            ok
          },
          isRequiredCall =
            if (deleteName == null) null
            else
              { targetMethod ->
                val methodName = targetMethod.name
                methodName == deleteName && targetMethod.isInClass("android.graphics.Canvas")
              },
          replaceArgList = replaceArgs,
        )
      }
    }
  }

  /**
   * Analyzes code for open-block-close sections like
   *
   *     var array = obtainStyledAttributes(...)
   *     block()
   *     array.recycle()
   *
   * and
   *
   *     sharedPreferences.edit()
   *         .block()
   *         .commit()
   *
   * and suggests replacing them with extension functions instead.
   *
   * It handles mapping from start() to extension() for these 3 patterns:
   *
   * Variable:
   *
   *     val var = something().start()           something().extension() {
   *     var.operation()                   =>        operation()
   *     var.finish()                            }
   *
   * Chained Calls:
   *
   *     something().start()                    something().extension() {
   *       .operation()                   =>        operation()
   *       .finish()                            }
   *
   * Existing Apply block:
   *
   *     something().start().apply {            something().extension() {
   *        operation()                   =>        operation()
   *     }.finish()                             }
   *
   * Examples of these pairs of start() and finish() are SharedPreferences.edit() and apply(), and
   * Context.obtainStyledAttributes() and recycle().
   *
   * There are many other possible variations here which we don't flag because they're not as common
   * and makes the refactoring more tricky, such as going via intermediate variables, using a
   * mixture of the above patterns, having conditional patterns around the finish call, etc.
   *
   * We also look out for potential problems where we can't extract the code into a new block, for
   * example where there is a new variable declared inside the block referenced outside of it:
   *
   *     val var = something().start()           something().extension() {
   *     val resources = getResources()   =>        val resources = getResources()
   *     var.finish()                            }
   *     process(resources)                      process(resources) // INVALID - resources not a variable
   */
  private fun checkBlock(
    issue: Issue,
    context: JavaContext,
    startCall: UCallExpression,
    method: PsiMethod,
    startName: String,
    startClass: String,
    targetClass: String,
    extensionClass: String,
    extensionMethod: String,
    import: String,
    containingClass: String?,
    isTarget: (UCallExpression, String?, PsiMethod, ULocalVariable?) -> Boolean,
    isRequiredCall: ((PsiMethod) -> Boolean)? = null,
    allowNesting: Boolean = false,
    replaceArgList: String = "",
  ) {
    if (!method.isInClass(startClass)) {
      return
    }

    val call = startCall.sourcePsi as? KtCallExpression ?: return
    val variable = findVariable(startCall)

    val (target, requiredCall, chained, thisReferences) =
      findBlockInfo(
        variable,
        startCall,
        startName,
        startClass,
        targetClass,
        allowNesting,
        isRequiredCall,
        isTarget,
      ) ?: return

    val source = context.getContents() ?: call.containingFile?.text ?: return
    // delete the left hand side of the variable; we don't need that anymore
    val deleteLhsStart = variable?.sourcePsi?.startOffset ?: -1
    val deleteLhsEnd = variable?.uastInitializer?.sourcePsi?.startOffset ?: -1

    val replaceAnchorStart = startCall.methodIdentifier?.sourcePsi?.startOffset ?: return
    var replaceAnchorEnd = startCall.methodIdentifier?.sourcePsi?.endOffset ?: return

    val rParen = call.valueArgumentList?.rightParenthesis ?: return
    val rParenStart = rParen.startOffset
    val rParenEnd = rParenStart + 1

    var replacedAnchor = extensionMethod
    if (replaceArgList.isNotEmpty()) {
      replacedAnchor = extensionMethod + replaceArgList.removeSuffix(")")
      replaceAnchorEnd = rParenStart
    }

    val receiver = target.receiver
    val recycleStart =
      if (receiver is USimpleNameReferenceExpression) receiver.sourcePsi?.startOffset ?: return
      else
        (target.sourcePsi?.parent as? KtDotQualifiedExpression)?.operationTokenNode?.startOffset
          ?: receiver?.sourcePsi?.startOffset
          ?: return
    val recycleEnd = target.sourcePsi?.endOffset ?: return

    var isApply = false
    var indent = true
    val replaceRefs =
      if (chained) {
        val list = mutableListOf<LintFix>()

        val originalParent = skipParenthesizedExprUp(startCall.uastParent)
        var p = originalParent
        while (p is UQualifiedReferenceExpression) {
          val sourcePsi = p.sourcePsi
          if (p.receiver == originalParent) {
            val selector = p.selector
            if (
              // TODO: Support other scoping functions (in particular, run and with)
              selector is UCallExpression &&
                selector.methodName == "apply" &&
                // See libraries/stdlib/jvm/build/stdlib-declarations.json
                selector.resolve().isInClass("kotlin.StandardKt__StandardKt")
            ) {
              // Apply block
              val args = selector.valueArguments
              val last = args.lastOrNull()?.sourcePsi ?: return
              if (last is KtLambdaExpression && sourcePsi is KtDotQualifiedExpression) {
                isApply = true
                indent = false
                val end = last.leftCurlyBrace.startOffset + 1
                val offset = sourcePsi.operationTokenNode.startOffset
                list.add(
                  fix()
                    .replace()
                    .range(Location.create(context.file, source, offset, end))
                    .text(source.substring(offset, end))
                    .with("")
                    .build()
                )
              } else {
                return
              }
            } else {
              if (sourcePsi is KtDotQualifiedExpression) {
                val offset = sourcePsi.operationTokenNode.startOffset
                list.add(
                  fix()
                    .replace()
                    .range(Location.create(context.file, source, offset, offset + 1))
                    .text(".")
                    .with("")
                    .build()
                )
              }
            }
            break
          }
          p = skipParenthesizedExprUp(p.uastParent)
        }
        list
      } else {
        val list =
          thisReferences.mapNotNull { ref ->
            val sourcePsi = ref.sourcePsi
            if (sourcePsi != null) {
              val refStart = sourcePsi.startOffset
              var replacement = "this"
              var refEnd = sourcePsi.endOffset
              var content = source.substring(refStart, refEnd)
              if (source[refEnd] == '.') {
                content += "."
                refEnd++
                replacement = ""
              }
              fix()
                .replace()
                .range(Location.create(context.file, source, refStart, refEnd))
                .text(content)
                .with(replacement)
                .build()
            } else {
              null
            }
          }
        if (requiredCall != null) {
          // Make sure the required call has no suffix or prefix with side effects
          val begin = source.lineBegin(requiredCall.startOffset)
          val end = source.lineEnd(requiredCall.endOffset) + 1 // +1: remove the \n as well
          list +
            fix()
              .replace()
              .range(Location.create(context.file, source, begin, end))
              .text(source.substring(begin, end))
              .with("")
              .build()
        } else {
          list
        }
      }

    // Try to indent the lines in the block as well
    val indentation = mutableListOf<LintFix>()
    val containingFile = call.containingFile
    var lineBegin = source.lineEnd(rParenEnd) + 1
    val last = source.lastIndexOf('\n', recycleStart)
    while (indent && lineBegin <= last) {
      val lineEnd = source.lineEnd(lineBegin)

      if (
        source.isNotBlankAt(lineBegin, lineEnd) &&
          // Don't add indentation on the line we plan to delete
          (requiredCall == null ||
            lineEnd < requiredCall.startOffset ||
            lineBegin > requiredCall.endOffset)
      ) {
        // Make sure we don't add whitespace to any multi-line string literals
        // for example
        if (containingFile.findElementAt(lineBegin) is PsiWhiteSpace) {
          indentation.add(
            fix()
              .replace()
              .range(Location.create(context.file, source, lineBegin, lineBegin))
              .text("")
              .with("    ")
              .build()
          )
        }
      }
      lineBegin = lineEnd + 1
    }

    val fix =
      fix()
        .name("Replace with the $extensionMethod extension function", true)
        .composite(
          // Delete variable declaration
          if (deleteLhsStart != -1) {
            fix()
              .replace()
              .range(Location.create(context.file, source, deleteLhsStart, deleteLhsEnd))
              .text(source.substring(deleteLhsStart, deleteLhsEnd))
              .with("")
              .build()
          } else {
            null
          },
          // Replace ( obtainStyledAttributes with withStyledAttributes before the argument list
          fix()
            .replace()
            .range(Location.create(context.file, source, replaceAnchorStart, replaceAnchorEnd))
            .text(source.substring(replaceAnchorStart, replaceAnchorEnd))
            .with(replacedAnchor)
            .imports(import)
            .reformat(true)
            .build(),
          // Insert a "{" after the parameter list right parenthesis
          fix()
            .replace()
            .range(Location.create(context.file, source, rParenStart, rParenEnd))
            .text(source.substring(rParenStart, rParenEnd))
            .with(
              // Special hack needed for the commit-style target prefs where we need an extra
              // parameter
              if (
                target.methodName == "commit" &&
                  targetClass == "android.content.SharedPreferences.Editor"
              )
                "commit = true) {"
              else ") {"
            )
            .build(),
          // Remove final recycle call and replace with `}`
          fix()
            .replace()
            .range(Location.create(context.file, source, recycleStart, recycleEnd))
            .text(source.substring(recycleStart, recycleEnd))
            .with(if (isApply) "" else "}")
            .build(),
          *replaceRefs.toTypedArray(),
          *indentation.toTypedArray(),
        )
        .autoFix()

    // Make sure we don't have a symbol conflict
    if (context.definesConflictingSymbol(extensionMethod, import)) {
      return
    }

    // If we require the library to be present, make sure we can access this extension function
    if (
      REQUIRE_LIBRARY.getValue(context) &&
        containingClass != null &&
        context.evaluator.findClass(containingClass) == null
    ) {
      return
    }

    val location =
      context.getCallLocation(startCall, includeReceiver = true, includeArguments = true)
    val message = createMessage(extensionClass, extensionMethod, false, import)
    context.report(issue, startCall, location, message, fix)
  }

  /**
   * For a given call, returns the variable it's assigned to, or null if not assigned to a variable
   */
  fun findVariable(startCall: UCallExpression): ULocalVariable? {
    var curr = skipParenthesizedExprUp(startCall.uastParent)
    while (true) {
      if (curr is UQualifiedReferenceExpression) {
        curr = curr.uastParent ?: return null
      } else {
        break
      }
    }
    return curr as? ULocalVariable
  }

  /**
   * Information about a particular call (such as `beginTransaction`) which could potentially be
   * replaced with an extension block call.
   *
   * This is a result object for [findBlockInfo].
   */
  private class BlockInfo(
    /**
     * The target call we were looking for. For example, for `obtainStyledAttributes` we're looking
     * for `TypedArray#recycle`.
     */
    val targetCall: UCallExpression,
    /**
     * If not null, a pointer to the required call. For example, for `beginTransaction` (which has
     * [targetCall] `endTransaction` we also require the call `setTransactionSuccessful`.
     */
    val requiredCall: KtElement?,
    /**
     * Whether the calls in the block are chained (for example,
     * `beginTransaction().setTransactionSuccessful().endTransaction()`, rather than `val t =
     * beginTransaction(); t.setTransactionSuccessful(); t.endTransaction()`.)
     */
    val chained: Boolean,
    /**
     * A list of all the references in the newly formed block which are referring to the variable
     * and need to be replaced with "this" instead. For example, if we have
     *
     * ```
     *  database.beginTransaction()
     *  create(database)
     *  database.setTransactionSuccessful()
     *  database.endTransaction()
     * ```
     *
     * the `create(database)` call needs to be changed to `create(this)` in the replacement:
     *
     *  ```
     *  database.transaction() {
     *      create(this)
     *  }
     *  ```
     */
    val thisReferences: List<USimpleNameReferenceExpression>,
  ) {
    operator fun component1() = targetCall

    operator fun component2() = requiredCall

    operator fun component3() = chained

    operator fun component4() = thisReferences
  }

  /**
   * Analyzes a method call to see if it looks like it matches the potential block extension method,
   * and if so, return a [BlockInfo].
   */
  private fun findBlockInfo(
    variable: ULocalVariable?,
    startCall: UCallExpression,
    startName: String,
    startClass: String,
    targetClass: String,
    allowNesting: Boolean,
    isRequiredCall: ((PsiMethod) -> Boolean)?,
    isTarget: (UCallExpression, String?, PsiMethod, ULocalVariable?) -> Boolean,
  ): BlockInfo? {
    val block = startCall.getParentOfType<UBlockExpression>(true) ?: return null
    val receiverIsThis = startClass == targetClass
    val variablePsi =
      variable?.javaPsi ?: if (receiverIsThis) startCall.receiver?.tryResolve() else null

    var start: UCallExpression? = null
    var target: UCallExpression? = null
    var requiredCall: KtElement? = null
    var extractable = true
    var chained = false
    val thisReferences = mutableListOf<USimpleNameReferenceExpression>()
    block.accept(
      object : AbstractUastVisitor() {
        private fun foundStart() = start != null

        private fun foundEnd() = target != null

        private var variables = mutableListOf<PsiElement>()

        init {
          if (variable != null) {
            variable.sourcePsi?.let { variables.add(it) }
            variable.javaPsi?.let { variables.add(it) }
          }
        }

        override fun visitLocalVariable(node: ULocalVariable): Boolean {
          // Record any newly declared variables inside the start to end range.
          // We want to make sure they aren't referenced AFTER the end.
          if (foundStart() && !foundEnd()) {
            node.sourcePsi?.let { variables.add(it) }
            node.javaPsi?.let { variables.add(it) }
          }
          return super.visitVariable(node)
        }

        override fun visitCallExpression(node: UCallExpression): Boolean {
          val resolved = node.resolve() ?: return super.visitCallExpression(node)

          if (start == null && node == startCall) {
            start = node
          } else if (
            start != null &&
              target == null &&
              !allowNesting &&
              resolved.name == startName &&
              resolved.isInClass(startClass)
          ) {
            extractable = false
            return true
          } else if (start != null && (target == null || !allowNesting)) {
            if (
              isTarget(node, resolved.name, resolved, variable) &&
                (variable == null ||
                  node.getParentOfType(
                    UBlockExpression::class.java,
                    true,
                    UIfExpression::class.java,
                    USwitchExpression::class.java,
                  ) == variable.getParentOfType<UBlockExpression>())
            ) {
              if (target != null && !allowNesting) {
                extractable = false
                return true
              }
              val parent = skipParenthesizedExprUp(node.uastParent)
              chained = parent != null && start.isBelow(parent, true)

              if (!chained && !sameBlockParent(start, node)) {
                return false
              }

              target = node

              // Make sure we don't try to rewrite the final "variable.recycle()" call
              // into "this.recycle()" since we'll be deleting this one

              if (parent is UQualifiedReferenceExpression) {
                thisReferences.remove(parent.receiver.skipParenthesizedExprDown())
              }
            } else if (
              target == null &&
                requiredCall == null &&
                isRequiredCall != null &&
                isRequiredCall(resolved)
            ) {
              var c: UElement? = node.uastParent
              while (c != null) {
                if (c is UQualifiedReferenceExpression) {
                  val receiver = c.receiver.skipParenthesizedExprDown()
                  if (receiver is USimpleNameReferenceExpression) {
                    if (thisReferences.contains(receiver)) {
                      thisReferences.remove(receiver)
                    }
                  } else {
                    extractable = false
                  }
                  if (c.selector is UCallExpression && c.selector != node) {
                    // Make sure we don't have some suffix call on the result that potentially
                    // has a side effect
                    extractable = false
                  }
                } else {
                  break
                }
                requiredCall = c.sourcePsi as? KtElement
                c = skipParenthesizedExprUp(c.uastParent) ?: break
              }
            }
          }
          return super.visitCallExpression(node)
        }

        override fun visitSimpleNameReferenceExpression(
          node: USimpleNameReferenceExpression
        ): Boolean {
          // Make sure we aren't accessing a new variable OUTSIDE the extraction range
          if (foundEnd() && extractable) {
            val resolved = node.resolve()
            if (variables.contains(resolved) && target != null && !node.isBelow(target, true)) {
              extractable = false
            }
          } else if (foundStart() && !foundEnd() && variablePsi != null) {
            val resolved = node.resolve()
            //noinspection LintImplPsiEquals
            if (resolved != null && (resolved == variable?.sourcePsi || resolved == variablePsi)) {
              thisReferences.add(node)
            }
          }

          return super.visitSimpleNameReferenceExpression(node)
        }
      }
    )

    return if (extractable && target != null && !(isRequiredCall != null && requiredCall == null)) {
      BlockInfo(target, requiredCall, chained, thisReferences)
    } else {
      null
    }
  }

  private fun UExpression.parentBlock(): UExpression? {
    var curr = uastParent
    while (curr != null) {
      if (
        curr is UBlockExpression ||
          curr is UIfExpression ||
          curr is USwitchExpression ||
          curr is ULoopExpression
      ) {
        return curr
      }
      curr = curr.uastParent
    }
    return null
  }

  /**
   * Given an opening and closing call, returns whether they are in the same logical block. For
   * example, for `beginTransaction` and `endTransaction` below, the following two examples are both
   * at the same level:
   *
   *     database.beginTransaction
   *     ...
   *     database.endTransaction()
   *     database.beginTransaction
   *     try {
   *     } finally {
   *         database.endTransaction()
   *     }
   *
   * but this is not:
   *
   *     database.beginTransaction
   *     if (close)
   *         database.endTransaction()
   */
  private fun sameBlockParent(start: UExpression, end: UExpression): Boolean {
    val startParent = start.parentBlock() ?: return false
    val targetParent = end.parentBlock() ?: return false
    if (startParent != targetParent) {
      val parent = targetParent.uastParent
      return parent is UTryExpression && parent.finallyClause === targetParent
    }

    return true
  }

  /**
   * Checks whether the given [symbol] (with fully qualified name [import]) has a conflict in this
   * source file. This is the case if there is a method of the same name in the file, or if there is
   * an import of a symbol with the same name (other than the fully qualified name itself.)
   */
  private fun JavaContext.definesConflictingSymbol(symbol: String, import: String): Boolean {
    val ktFile = psiFile as? KtFile ?: return false
    // Already imported a conflicting name?
    for (directive in ktFile.importDirectives) {
      val name = directive.importedName?.asString()
      if (name == symbol) {
        return directive.importedFqName?.asString() != import
      }
    }
    // First check whether the symbol appears anywhere in the file; if not
    // we don't have to visit it.
    val contents = getContents() ?: ktFile.text
    if (!contents.containsIdentifier(symbol)) {
      // No reference to the same symbol name
      return false
    }
    // Visit the file looking for conflicting declarations.
    // (In theory we could skip conflicting declarations not reachable from
    // the current context, such as methods in sibling nested classes etc.)
    var found = false
    ktFile.accept(
      object : KtTreeVisitorVoid() {
        override fun visitNamedFunction(function: KtNamedFunction) {
          // We *could* restrict the conflict check to only
          // look for conflicting functions for imported extension
          // functions, and for conflicting properties for imported
          // extension functions, but this can still lead to some
          // confusion for the user even if it doesn't confuse the
          // compiler, so we don't skip the below check if !isProperty
          // (and we don't have a check for "isProperty" in visitProperty
          // below.)
          if (function.name == symbol && !function.isLocal) {
            found = true
          }
          super.visitNamedFunction(function)
        }

        override fun visitProperty(property: KtProperty) {
          if (property.name == symbol && !property.isLocal) {
            found = true
          }
          super.visitProperty(property)
        }
      }
    )

    return found
  }

  private fun createMessage(
    extensionClass: String,
    extensionMethod: String,
    property: Boolean,
    import: String,
  ): String {
    val isStdlib = import.isBlank() || import.startsWith("kotlin.")
    val library = if (isStdlib) "stdlib" else "KTX"
    val type = if (property) "property" else "function"
    return if (extensionClass.isEmpty()) {
      "Use the $library $type `$extensionMethod` instead?"
    } else {
      "Use the $library extension $type `$extensionClass.$extensionMethod` instead?"
    }
  }

  companion object Issues {
    private val IMPLEMENTATION = Implementation(UseKtxDetector::class.java, Scope.JAVA_FILE_SCOPE)

    val REQUIRE_LIBRARY =
      BooleanOption(
        "require-present",
        "Whether to only offer extensions already available",
        false,
        """
        This option lets you only have lint suggest extension replacements if those \
        extensions are already available on the class path (in other words, you're already \
        depending on the library containing the extension method.)
        """,
      )

    /** Suggests equivalent but simpler KTX constructs. */
    @JvmField
    val USE_KTX =
      Issue.create(
          id = "UseKtx",
          briefDescription = "Use KTX extension function",
          explanation =
            """
            The Android KTX libraries decorates the Android platform SDK as well \
            as various libraries with more convenient extension functions available \
            from Kotlin, allowing you to use default parameters, named parameters, \
            and more.
            """,
          category = Category.PRODUCTIVITY, // Maybe MAD, or READABILITY, or SIMPLICITY, .... ?
          priority = 6,
          severity = Severity.WARNING,
          androidSpecific = true,
          implementation = IMPLEMENTATION,
        )
        .setOptions(listOf(REQUIRE_LIBRARY))
  }
}

fun PsiMethod?.isInClass(name: String?): Boolean {
  this ?: return false
  name ?: return false
  val qualifiedName = containingClass?.qualifiedName ?: return false

  return qualifiedName == name
}

private fun CharSequence.lineEnd(start: Int): Int {
  return this.indexOf('\n', start).let { if (it == -1) length else it }
}

private fun CharSequence.lineBegin(start: Int): Int {
  return this.lastIndexOf('\n', start).let { if (it == -1) 0 else it + 1 }
}

private fun CharSequence.isNotBlankAt(start: Int, end: Int): Boolean {
  return (start until end).any { !this[it].isWhitespace() }
}

/**
 * Returns true if this sequence contains a given identifier (and not as part of another longer
 * identifier, e.g. it is surrounded by "word" boundaries)
 */
fun CharSequence.containsIdentifier(identifier: String): Boolean {
  var i = 0
  val n = length
  while (true) {
    i = indexOf(identifier, i)
    if (i == -1) {
      return false
    }
    if (
      (i == 0 || !this[i - 1].isJavaIdentifierPart()) &&
        (i + identifier.length == n || !this[i + identifier.length].isJavaIdentifierPart())
    ) {
      return true
    }
    i++
  }
}
