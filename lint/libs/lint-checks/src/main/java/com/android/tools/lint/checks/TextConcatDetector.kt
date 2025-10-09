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
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UParenthesizedExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.UastBinaryOperator
import org.jetbrains.uast.skipParenthesizedExprDown

/** Look for missing spaces in string concatenations */
class TextConcatDetector : Detector(), SourceCodeScanner {
  companion object Issues {
    private val IMPLEMENTATION =
      Implementation(TextConcatDetector::class.java, Scope.JAVA_FILE_SCOPE)

    @JvmField
    val ISSUE =
      Issue.create(
        id = "TextConcatSpace",
        briefDescription = "Missing space in text concatenation?",
        explanation =
          """
          When splitting strings up across separate lines, it's easy to accidentally \
          miss a separating space. This lint check looks for cases of string concatenation \
          where a separating space may be missing.
          """,
        category = Category.CORRECTNESS,
        priority = 6,
        severity = Severity.WARNING,
        implementation = IMPLEMENTATION,
      )
  }

  override fun getApplicableUastTypes(): List<Class<out UElement>> =
    listOf(UBinaryExpression::class.java, UPolyadicExpression::class.java)

  override fun createUastHandler(context: JavaContext): UElementHandler {
    return object : UElementHandler() {
      override fun visitBinaryExpression(node: UBinaryExpression) {
        if (node.operator == UastBinaryOperator.PLUS) {
          check(context, node, node.leftOperand, node.rightOperand)
        }
      }

      override fun visitPolyadicExpression(node: UPolyadicExpression) {
        if (node.operator == UastBinaryOperator.PLUS) {
          val operands = node.operands
          for (i in 0 until operands.size - 1) {
            check(context, node, operands[i], operands[i + 1])
          }
        }
      }
    }
  }

  private fun findString(expression: PsiElement?, biasLeft: Boolean): PsiElement? {
    expression ?: return null
    when (expression) {
      is KtParenthesizedExpression -> return findString(expression.expression, biasLeft)
      is PsiParenthesizedExpression -> return findString(expression.expression, biasLeft)
      is KtLiteralStringTemplateEntry -> return expression
      is PsiLiteralExpression -> {
        val stringWithQuotes = expression.text
        if (
          stringWithQuotes.startsWith('"') &&
            stringWithQuotes.endsWith('"') &&
            expression.value is String
        ) {
          return expression.firstChild ?: expression
        }
      }
      is KtBinaryExpression -> {
        if (expression.operationToken == KtTokens.PLUS) {
          val follow = if (biasLeft) expression.left else expression.right
          return findString(follow, biasLeft)
        }
      }
      is PsiBinaryExpression -> {
        if (expression.operationSign.tokenType == JavaTokenType.PLUS) {
          val follow = if (biasLeft) expression.lOperand else expression.rOperand
          return findString(follow, biasLeft)
        }
      }
      is PsiPolyadicExpression -> {
        if (expression.operationTokenType == JavaTokenType.PLUS) {
          val operands = expression.operands
          if (operands.isNotEmpty()) {
            val follow = if (biasLeft) operands.first() else operands.last()
            return findString(follow, biasLeft)
          }
        }
      }
      is KtStringTemplateExpression -> {
        val entries = expression.entries
        if (entries.isNotEmpty()) {
          val string = if (biasLeft) entries.first() else entries.last()
          return string as? KtLiteralStringTemplateEntry
        }
      }
    }

    return null
  }

  private fun findString(expression: UExpression, biasLeft: Boolean): ULiteralExpression? {
    if (expression is ULiteralExpression) {
      return expression
    } else if (
      expression is UPolyadicExpression && expression.operator == UastBinaryOperator.PLUS
    ) {
      val operands = expression.operands
      if (operands.isNotEmpty()) {
        val element =
          if (biasLeft) operands.first().skipParenthesizedExprDown()
          else operands.last().skipParenthesizedExprDown()
        if (element is ULiteralExpression) {
          return element
        }
      }
    } else if (expression is UParenthesizedExpression) {
      return findString(expression.expression, biasLeft)
    }
    return null
  }

  fun getWords(line: String): List<String> {
    return line.split(" ")
  }

  private fun differentLines(element1: PsiElement, element2: PsiElement): Boolean {
    var curr: PsiElement? = element1
    // Traverse the AST nodes from element1 to element2 and see if we have any line separators
    while (curr != null) {
      if (curr === element2) {
        return false
      }
      if (curr is PsiWhiteSpace && curr.text.contains("\n")) {
        return true
      }

      var next = curr.firstChild
      if (next == null) {
        next = curr.nextSibling
        if (next == null) {
          var parent = curr.parent
          while (parent != null) {
            next = parent.nextSibling
            if (next != null) {
              break
            }
            parent = parent.parent
          }
        }
      }
      curr = next
    }

    // unexpected - we should always reach element2
    return false
  }

  private fun PsiElement.getStringText(): String {
    val text = this.text
    return if (this is PsiJavaToken) {
      text.removeSurrounding("\"")
    } else {
      text
    }
  }

  private fun check(
    context: JavaContext,
    node: UPolyadicExpression,
    lhs: UExpression,
    rhs: UExpression,
  ) {
    val leftPsi = findString(lhs.sourcePsi, biasLeft = false) ?: return
    val leftString = leftPsi.getStringText()
    if (leftString.isEmpty() || !leftString.last().isLetter()) {
      return
    }

    // Make sure the previous string ends with a "word" after a space, so
    // we don't match things like "The end.\n\n").
    var i = leftString.length
    while (i > 0 && leftString[i - 1].isLetterOrDigit()) {
      i--
    }
    if (i == 0 || leftString[i - 1] != ' ') {
      return
    }

    if (!leftString.contains(" ")) {
      return
    }

    val rightPsi = findString(rhs.sourcePsi, biasLeft = true) ?: return
    val rightString = rightPsi.getStringText()
    if (rightString.isEmpty() || !rightString.first().isLetter()) {
      return
    }

    // Heuristics - make sure the string looks like a "language" by having multiple words
    val leftWords = getWords(leftString)
    if (leftWords.size < 2) {
      return
    }
    val rightWords = getWords(rightString)
    if (!differentLines(leftPsi, rightPsi)) {
      return
    }

    val fixBuilder = fix().name("Insert space").replace().range(context.getLocation(rightPsi))
    val fix =
      when (rightPsi) {
        is KtLiteralStringTemplateEntry -> fixBuilder.beginning().with(" ").build()
        is PsiJavaToken ->
          fixBuilder.text(rightPsi.text).with("\" " + rightPsi.text.substring(1)).build()
        else -> null
      }

    val lastWord = leftWords.last()
    val firstWord = rightWords.first()
    context.report(
      ISSUE,
      node,
      context
        .getLocation(rightPsi)
        .withSecondary(context.getLocation(leftPsi), "Previous text here"),
      "Missing space between \"$lastWord\" on the previous line and \"$firstWord\" here? Resulting string is \"$lastWord$firstWord\".",
      fix,
    )
  }
}
