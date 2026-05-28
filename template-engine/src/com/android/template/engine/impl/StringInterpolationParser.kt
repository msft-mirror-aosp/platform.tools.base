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
package com.android.template.engine.impl

import com.android.template.engine.impl.StringInterpolationToken.TokenType

internal class StringInterpolationParser(private val expressionText: String) {
  private var pos = 0

  private val tokens = mutableListOf<StringInterpolationToken>()

  private fun peek(): StringInterpolationToken = tokens.getOrNull(pos) ?: StringInterpolationToken(TokenType.EOF, "", -1, -1)

  private fun consume(): StringInterpolationToken = tokens.getOrNull(pos++) ?: StringInterpolationToken(TokenType.EOF, "", -1, -1)

  private fun consume(type: TokenType): StringInterpolationToken {
    val t = peek()
    if (t.type == type) {
      return consume()
    }
    throw StringInterpolationException(expressionText, t.start, "Expected $type but got ${t.type}")
  }

  fun parse(): TemplateNode {
    val lexer = StringInterpolationLexer(expressionText)
    pos = 0
    tokens.clear()
    tokens.addAll(lexer.tokenize())
    val startToken = peek()
    val parts = mutableListOf<StringInterpolationNode>()
    while (peek().type != TokenType.EOF) {
      val t = peek()
      when (t.type) {
        TokenType.TEXT -> {
          val textToken = consume()
          parts.add(TextNode(textToken, textToken, textToken.value))
        }
        TokenType.START_INTERPOLATION -> {
          parts.add(parseInterpolation())
        }
        else -> throw StringInterpolationException(expressionText, t.start, "Unexpected token outside interpolation: ${t.type}")
      }
    }
    val endToken = peek() // This will be the EOF token or the last token before EOF
    return TemplateNode(startToken, endToken, parts)
  }

  private fun parseInterpolation(): InterpolationNode {
    val startToken = consume(TokenType.START_INTERPOLATION)
    val idToken = consume(TokenType.IDENTIFIER)
    val identifier = IdentifierNode(idToken, idToken, idToken.value)
    val methodCalls = mutableListOf<MethodCallNode>()

    while (peek().type == TokenType.DOT) {
      val dotToken = consume(TokenType.DOT)
      val methodToken = consume(TokenType.IDENTIFIER)
      consume(TokenType.OPEN_PAREN)
      val args = mutableListOf<StringInterpolationNode>()
      if (peek().type != TokenType.CLOSE_PAREN) {
        args.add(parseArgument())
        while (peek().type == TokenType.COMMA) {
          consume(TokenType.COMMA)
          args.add(parseArgument())
        }
      }
      val closeParenToken = consume(TokenType.CLOSE_PAREN)
      methodCalls.add(MethodCallNode(dotToken, closeParenToken, methodToken.value, args))
    }

    val endToken = consume(TokenType.END_INTERPOLATION)
    return InterpolationNode(startToken, endToken, identifier, methodCalls)
  }

  private fun parseArgument(): StringInterpolationNode {
    val t = peek()
    return when (t.type) {
      TokenType.STRING_LITERAL -> {
        val token = consume()
        StringLiteralNode(token, token, token.value)
      }
      TokenType.IDENTIFIER -> {
        val token = consume()
        IdentifierNode(token, token, token.value)
      }
      else -> throw StringInterpolationException(expressionText, t.start, "Expected argument but got ${t.type}")
    }
  }
}
