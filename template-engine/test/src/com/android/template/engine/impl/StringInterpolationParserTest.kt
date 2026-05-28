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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

@Suppress("FunctionName", "CanConvertToMultiDollarString")
class StringInterpolationParserTest {
  @Test
  fun `test empty string`() {
    val parser = StringInterpolationParser("")
    val ast = parser.parse()
    assertThat(ast.parts).isEmpty()
  }

  @Test
  fun `test simple interpolation`() {
    val parser = StringInterpolationParser("\${id}")
    val ast = parser.parse()
    assertThat(ast.parts).hasSize(1)
    val interp = ast.parts[0] as InterpolationNode
    assertThat(interp.identifier.name).isEqualTo("id")
    assertThat(interp.methodCalls).isEmpty()
  }

  @Test
  fun `test invalid interpolation`() {
    val parser = StringInterpolationParser("\${id id2}")
    val exception = assertThrows(StringInterpolationException::class.java) { parser.parse() }
    assertThat(exception.message)
      .contains("Error evaluating expression '\${id id2}' at position 6: Expected END_INTERPOLATION but got IDENTIFIER")
  }

  @Test
  fun `test simple interpolation with literals`() {
    val parser = StringInterpolationParser("hello \${id} foo")
    val ast = parser.parse()
    assertThat(ast.parts).hasSize(3)
    assertThat((ast.parts[0] as TextNode).text).isEqualTo("hello ")
    val interp = ast.parts[1] as InterpolationNode
    assertThat(interp.identifier.name).isEqualTo("id")
    assertThat(interp.methodCalls).isEmpty()
    assertThat((ast.parts[2] as TextNode).text).isEqualTo(" foo")
  }

  @Test
  fun `test method call`() {
    val parser = StringInterpolationParser("\${id.replace(\"5\",\"6\")}")
    val ast = parser.parse()
    assertThat(ast.parts).hasSize(1)
    val interp = ast.parts[0] as InterpolationNode
    assertThat(interp.identifier.name).isEqualTo("id")
    assertThat(interp.methodCalls).hasSize(1)
    assertThat(interp.methodCalls[0].methodName).isEqualTo("replace")
    assertThat(interp.methodCalls[0].arguments).hasSize(2)
    assertThat((interp.methodCalls[0].arguments[0] as StringLiteralNode).text).isEqualTo("5")
    assertThat((interp.methodCalls[0].arguments[1] as StringLiteralNode).text).isEqualTo("6")
  }

  @Test
  fun `test method call with escaped quotes`() {
    val parser = StringInterpolationParser("\${id.replace('hello \\'world\\'', \"goodbye \\\"galaxy\\\"\")}")
    val ast = parser.parse()
    assertThat(ast.parts).hasSize(1)
    val interp = ast.parts[0] as InterpolationNode
    assertThat(interp.identifier.name).isEqualTo("id")
    assertThat(interp.methodCalls).hasSize(1)
    assertThat(interp.methodCalls[0].methodName).isEqualTo("replace")
    assertThat(interp.methodCalls[0].arguments).hasSize(2)
    assertThat((interp.methodCalls[0].arguments[0] as StringLiteralNode).text).isEqualTo("hello 'world'")
    assertThat((interp.methodCalls[0].arguments[1] as StringLiteralNode).text).isEqualTo("goodbye \"galaxy\"")
  }

  @Test
  fun `test mixed text`() {
    val parser = StringInterpolationParser("\${id} test \${name}")
    val ast = parser.parse()
    assertThat(ast.parts).hasSize(3)
    assertThat((ast.parts[0] as InterpolationNode).identifier.name).isEqualTo("id")
    assertThat((ast.parts[1] as TextNode).text).isEqualTo(" test ")
    assertThat((ast.parts[2] as InterpolationNode).identifier.name).isEqualTo("name")
  }

  @Test
  fun `test multiple method calls and text`() {
    val parser = StringInterpolationParser("\${id.replace(\"5\",\"6\")} test \${name.replace('b', 'c')}")
    val ast = parser.parse()
    assertThat(ast.parts).hasSize(3)

    val interp1 = ast.parts[0] as InterpolationNode
    assertThat(interp1.identifier.name).isEqualTo("id")
    assertThat(interp1.methodCalls).hasSize(1)
    assertThat(interp1.methodCalls[0].methodName).isEqualTo("replace")
    assertThat((interp1.methodCalls[0].arguments[0] as StringLiteralNode).text).isEqualTo("5")
    assertThat((interp1.methodCalls[0].arguments[1] as StringLiteralNode).text).isEqualTo("6")

    assertThat((ast.parts[1] as TextNode).text).isEqualTo(" test ")

    val interp2 = ast.parts[2] as InterpolationNode
    assertThat(interp2.identifier.name).isEqualTo("name")
    assertThat(interp2.methodCalls).hasSize(1)
    assertThat(interp2.methodCalls[0].methodName).isEqualTo("replace")
    assertThat((interp2.methodCalls[0].arguments[0] as StringLiteralNode).text).isEqualTo("b")
    assertThat((interp2.methodCalls[0].arguments[1] as StringLiteralNode).text).isEqualTo("c")
  }
}
