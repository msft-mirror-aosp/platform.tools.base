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

/**
 * Tokenizes a Kotlin-style string interpolation into a sequence of [StringInterpolationToken]. See [StringInterpolationParser] and
 * [StringInterpolationEvaluator].
 */
internal class StringInterpolationLexer(private val input: String) {
  private var pos = 0
  private var inInterpolation = false

  fun nextToken(): StringInterpolationToken {
    if (pos >= input.length) return StringInterpolationToken(TokenType.EOF, "", pos, pos)

    if (!inInterpolation) {
      val start = pos
      val endIdx = input.indexOf("\${", pos)
      if (endIdx == -1) {
        pos = input.length
        return StringInterpolationToken(TokenType.TEXT, input.substring(start), start, pos)
      } else if (endIdx == start) {
        pos += 2
        inInterpolation = true
        return StringInterpolationToken(TokenType.START_INTERPOLATION, "\${", start, pos)
      } else {
        pos = endIdx
        return StringInterpolationToken(TokenType.TEXT, input.substring(start, endIdx), start, pos)
      }
    }

    skipWhitespace()
    if (pos >= input.length) {
      inInterpolation = false // safety
      return StringInterpolationToken(TokenType.EOF, "", pos, pos)
    }

    val start = pos
    val c = input[pos]

    when (c) {
      '}' -> {
        pos++
        inInterpolation = false
        return StringInterpolationToken(TokenType.END_INTERPOLATION, "}", start, pos)
      }
      '.' -> {
        pos++
        return StringInterpolationToken(TokenType.DOT, ".", start, pos)
      }
      '(' -> {
        pos++
        return StringInterpolationToken(TokenType.OPEN_PAREN, "(", start, pos)
      }
      ')' -> {
        pos++
        return StringInterpolationToken(TokenType.CLOSE_PAREN, ")", start, pos)
      }
      ',' -> {
        pos++
        return StringInterpolationToken(TokenType.COMMA, ",", start, pos)
      }
      '"',
      '\'' -> {
        val quote = c
        pos++
        var value = ""
        while (pos < input.length && input[pos] != quote) {
          if (input[pos] == '\\' && pos + 1 < input.length) {
            pos++
            value += input[pos]
          } else {
            value += input[pos]
          }
          pos++
        }
        if (pos < input.length) pos++ // skip closing quote
        return StringInterpolationToken(TokenType.STRING_LITERAL, value, start, pos)
      }
      else -> {
        if (c.isLetter() || c == '_') {
          while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) {
            pos++
          }
          return StringInterpolationToken(TokenType.IDENTIFIER, input.substring(start, pos), start, pos)
        }
        throw StringInterpolationException(input, pos, "Unexpected character '$c'")
      }
    }
  }

  private fun skipWhitespace() {
    while (pos < input.length && input[pos].isWhitespace()) {
      pos++
    }
  }

  fun tokenize(): List<StringInterpolationToken> {
    val tokens = mutableListOf<StringInterpolationToken>()
    while (true) {
      val token = nextToken()
      tokens.add(token)
      if (token.type == TokenType.EOF) break
    }
    return tokens
  }
}

internal data class StringInterpolationToken(val type: TokenType, val value: String, val start: Int, val end: Int) {

  internal enum class TokenType {
    TEXT,
    START_INTERPOLATION,
    END_INTERPOLATION,
    IDENTIFIER,
    DOT,
    OPEN_PAREN,
    CLOSE_PAREN,
    COMMA,
    STRING_LITERAL,
    EOF,
  }
}
