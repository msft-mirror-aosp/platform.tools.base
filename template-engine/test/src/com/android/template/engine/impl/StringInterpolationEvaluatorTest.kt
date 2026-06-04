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
class StringInterpolationEvaluatorTest {

  @Test
  fun `evaluate simple text`() {
    val evaluator = StringInterpolationEvaluator("Hello World", mapOf())
    assertThat(evaluator.evaluate()).isEqualTo("Hello World")
  }

  @Test
  fun `evaluate single identifier`() {
    val evaluator = StringInterpolationEvaluator("Hello \${name}", mapOf("name" to "Android"))
    assertThat(evaluator.evaluate()).isEqualTo("Hello Android")
  }

  @Test
  fun `evaluate multiple identifiers`() {
    val evaluator = StringInterpolationEvaluator("\${first} \${second}", mapOf("first" to "Jetpack", "second" to "Compose"))
    assertThat(evaluator.evaluate()).isEqualTo("Jetpack Compose")
  }

  @Test
  fun `evaluate identifier with replace string literals`() {
    val evaluator = StringInterpolationEvaluator("\${namespace.replace(\".\", \"/\")}", mapOf("namespace" to "com.example.app"))
    assertThat(evaluator.evaluate()).isEqualTo("com/example/app")
  }

  @Test
  fun `evaluate identifier with replace char literals`() {
    val evaluator = StringInterpolationEvaluator("\${namespace.replace('.', '/')}", mapOf("namespace" to "com.example.app"))
    assertThat(evaluator.evaluate()).isEqualTo("com/example/app")
  }

  @Test
  fun `evaluate chained method calls`() {
    val evaluator =
      StringInterpolationEvaluator("\${namespace.replace(\"com\", \"org\").replace('.', '/')}", mapOf("namespace" to "com.example.app"))
    assertThat(evaluator.evaluate()).isEqualTo("org/example/app")
  }

  @Test
  fun `evaluate toJavaPropertyValue with characters to escape`() {
    val evaluator = StringInterpolationEvaluator("\${sdkPath.toJavaPropertyValue()}", mapOf("sdkPath" to "C:\\Users\\Name\\Android\\sdk:1"))
    assertThat(evaluator.evaluate()).isEqualTo("C\\:\\\\Users\\\\Name\\\\Android\\\\sdk\\:1")
  }

  @Test
  fun `evaluate toLower`() {
    val evaluator = StringInterpolationEvaluator("\${name.toLower()}", mapOf("name" to "FooBar"))
    assertThat(evaluator.evaluate()).isEqualTo("foobar")
  }

  @Test
  fun `evaluate toJavaPackageSegment`() {
    val evaluator = StringInterpolationEvaluator("\${name.toJavaPackageSegment()}", mapOf("name" to "Foo-Bar 123!"))
    assertThat(evaluator.evaluate()).isEqualTo("foo_bar123")
  }

  @Test
  fun `evaluate toAndroidPackageSegment`() {
    val evaluator = StringInterpolationEvaluator("\${name.toAndroidPackageSegment()}", mapOf("name" to "Foo-Bar 123!"))
    assertThat(evaluator.evaluate()).isEqualTo("foo_bar123")
  }

  @Test
  fun `evaluate replace with variable arguments`() {
    val evaluator =
      StringInterpolationEvaluator("\${namespace.replace(old, new)}", mapOf("namespace" to "com.example.app", "old" to ".", "new" to "/"))
    assertThat(evaluator.evaluate()).isEqualTo("com/example/app")
  }

  @Test
  fun `evaluate throws on unknown variable`() {
    val evaluator = StringInterpolationEvaluator("\${missing}", mapOf())
    val exception = assertThrows(StringInterpolationException::class.java) { evaluator.evaluate() }
    assertThat(exception).hasMessageThat().contains("Error evaluating expression '\${missing}' at position 3: Variable 'missing' not found")
  }

  @Test
  fun `evaluate throws on unknown argument variable`() {
    val evaluator = StringInterpolationEvaluator("\${id.replace(missing, \"a\")}", mapOf("id" to "123"))
    val exception = assertThrows(StringInterpolationException::class.java) { evaluator.evaluate() }
    assertThat(exception)
      .hasMessageThat()
      .contains("Error evaluating expression '\${id.replace(missing, \"a\")}' at position 14: Variable 'missing' not found")
  }

  @Test
  fun `evaluate throws on unknown method`() {
    val evaluator = StringInterpolationEvaluator("\${id.unknown()}", mapOf("id" to "123"))
    val exception = assertThrows(StringInterpolationException::class.java) { evaluator.evaluate() }
    assertThat(exception)
      .hasMessageThat()
      .contains("Error evaluating expression '\${id.unknown()}' at position 5: Unknown method 'unknown'")
  }

  @Test
  fun `evaluate throws on wrong replace argument count`() {
    val evaluator = StringInterpolationEvaluator("\${id.replace(\"1\")}", mapOf("id" to "123"))
    val exception = assertThrows(StringInterpolationException::class.java) { evaluator.evaluate() }
    assertThat(exception)
      .hasMessageThat()
      .contains("Error evaluating expression '\${id.replace(\"1\")}' at position 5: Method 'replace' expects 2 arguments")
  }

  @Test
  fun `evaluate throws on wrong toLower argument count`() {
    val evaluator = StringInterpolationEvaluator("\${id.toLower(\"1\")}", mapOf("id" to "ABC"))
    val exception = assertThrows(StringInterpolationException::class.java) { evaluator.evaluate() }
    assertThat(exception)
      .hasMessageThat()
      .contains("Error evaluating expression '\${id.toLower(\"1\")}' at position 5: Method 'toLower' expects 0 arguments")
  }

  @Test
  fun `evaluate throws on wrong toJavaPropertyValue argument count`() {
    val evaluator = StringInterpolationEvaluator("\${id.toJavaPropertyValue(\"1\")}", mapOf("id" to "ABC"))
    val exception = assertThrows(StringInterpolationException::class.java) { evaluator.evaluate() }
    assertThat(exception)
      .hasMessageThat()
      .contains(
        "Error evaluating expression '\${id.toJavaPropertyValue(\"1\")}' at position 5: Method 'toJavaPropertyValue' expects 0 arguments"
      )
  }

  @Test
  fun `evaluate throws on wrong toJavaPackageSegment argument count`() {
    val evaluator = StringInterpolationEvaluator("\${id.toJavaPackageSegment(\"1\")}", mapOf("id" to "ABC"))
    val exception = assertThrows(StringInterpolationException::class.java) { evaluator.evaluate() }
    assertThat(exception)
      .hasMessageThat()
      .contains(
        "Error evaluating expression '\${id.toJavaPackageSegment(\"1\")}' at position 5: Method 'toJavaPackageSegment' expects 0 arguments"
      )
  }

  @Test
  fun `evaluate throws on wrong toAndroidPackageSegment argument count`() {
    val evaluator = StringInterpolationEvaluator("\${id.toAndroidPackageSegment(\"1\")}", mapOf("id" to "ABC"))
    val exception = assertThrows(StringInterpolationException::class.java) { evaluator.evaluate() }
    assertThat(exception)
      .hasMessageThat()
      .contains(
        "Error evaluating expression '\${id.toAndroidPackageSegment(\"1\")}' at position 5: Method 'toAndroidPackageSegment' expects 0 arguments"
      )
  }
}
