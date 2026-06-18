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
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@Suppress("FunctionName", "CanConvertToMultiDollarString")
class StringInterpolationLexerTest {
  @Test
  fun `test empty string`() {
    val lexer = StringInterpolationLexer("")
    val tokens = lexer.tokenize()
    assertThat(tokens).hasSize(1)
    val iterator = tokens.iterator()
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.EOF, "", 0, 0))
  }

  @Test
  fun `test empty interpolation`() {
    val lexer = StringInterpolationLexer("\${}")
    val tokens = lexer.tokenize()
    assertThat(tokens).hasSize(3)
    val iterator = tokens.iterator()
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.START_INTERPOLATION, "\${", 0, 2))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.END_INTERPOLATION, "}", 2, 3))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.EOF, "", 3, 3))
  }

  @Test
  fun `test identifier interpolation`() {
    val lexer = StringInterpolationLexer("\${id}")
    val tokens = lexer.tokenize()
    assertThat(tokens).hasSize(4)
    val iterator = tokens.iterator()
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.START_INTERPOLATION, "\${", 0, 2))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.IDENTIFIER, "id", 2, 4))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.END_INTERPOLATION, "}", 4, 5))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.EOF, "", 5, 5))
  }

  @Test
  fun `test string literal interpolation`() {
    val lexer = StringInterpolationLexer("\${'hello'}")
    val tokens = lexer.tokenize()
    assertThat(tokens).hasSize(4)
    val iterator = tokens.iterator()
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.START_INTERPOLATION, "\${", 0, 2))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.STRING_LITERAL, "hello", 2, 9))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.END_INTERPOLATION, "}", 9, 10))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.EOF, "", 10, 10))
  }

  @Test
  fun `test multiple identifiers interpolation`() {
    val lexer = StringInterpolationLexer("\${id1 id2}")
    val tokens = lexer.tokenize()
    assertThat(tokens).hasSize(5)
    val iterator = tokens.iterator()
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.START_INTERPOLATION, "\${", 0, 2))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.IDENTIFIER, "id1", 2, 5))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.IDENTIFIER, "id2", 6, 9))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.END_INTERPOLATION, "}", 9, 10))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.EOF, "", 10, 10))
  }

  @Test
  fun `test dotted expression interpolation`() {
    val lexer = StringInterpolationLexer("\${id1.foo('5','6')}")
    val tokens = lexer.tokenize()
    assertThat(tokens).hasSize(11)
    val iterator = tokens.iterator()
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.START_INTERPOLATION, "\${", 0, 2))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.IDENTIFIER, "id1", 2, 5))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.DOT, ".", 5, 6))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.IDENTIFIER, "foo", 6, 9))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.OPEN_PAREN, "(", 9, 10))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.STRING_LITERAL, "5", 10, 13))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.COMMA, ",", 13, 14))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.STRING_LITERAL, "6", 14, 17))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.CLOSE_PAREN, ")", 17, 18))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.END_INTERPOLATION, "}", 18, 19))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.EOF, "", 19, 19))
  }

  @Test
  fun `test mixed literal and interpolation`() {
    val lexer = StringInterpolationLexer("hello \${world}!")
    val tokens = lexer.tokenize()
    assertThat(tokens).hasSize(6)
    val iterator = tokens.iterator()
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.TEXT, "hello ", 0, 6))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.START_INTERPOLATION, "\${", 6, 8))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.IDENTIFIER, "world", 8, 13))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.END_INTERPOLATION, "}", 13, 14))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.TEXT, "!", 14, 15))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.EOF, "", 15, 15))
  }

  @Test
  fun `test string literal with escaped characters`() {
    val lexer = StringInterpolationLexer("\${'hello \\'world\\''}")
    val tokens = lexer.tokenize()
    assertThat(tokens).hasSize(4)
    val iterator = tokens.iterator()
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.START_INTERPOLATION, "\${", 0, 2))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.STRING_LITERAL, "hello \'world\'", 2, 19))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.END_INTERPOLATION, "}", 19, 20))
    assertThat(iterator.next()).isEqualTo(StringInterpolationToken(TokenType.EOF, "", 20, 20))
  }
}
