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
package com.android.template.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

@Suppress("FunctionName")
class JsonSourceParserTest {
  @Test
  fun `test parser stores line numbers for each element type`() {
    val json =
      """
      {
        "key1": "value1",
        "key2": 123,
        "key3": true,
        "key4": null,
        "key5": [
          "item1",
          {
            "nestedKey": "nestedValue"
          }
        ],
        "key6": {
          "anotherKey": false
        }
      }
      """
        .trimIndent()
    val root = JsonSourceParser.parseString(json)

    assertThat(root.lineNumber).isEqualTo(0)
    assertThat(root).isInstanceOf(JsonSourceObject::class.java)
    assertThat(root.asJsonObject?.get("key1")?.lineNumber).isEqualTo(1)
    assertThat(root.asJsonObject?.get("key5")?.lineNumber).isEqualTo(5)
  }

  @Test
  fun `test parser stores line numbers for nested objects`() {
    val json =
      """
      {
        "outer": {
          "inner1": "value1",
          "inner2": {
            "deepInner": 123
          }
        }
      }
      """
        .trimIndent()
    val root = JsonSourceParser.parseString(json)

    assertThat(root.asJsonObject?.get("outer")?.lineNumber).isEqualTo(1)
    assertThat(root.asJsonObject?.get("outer")?.asJsonObject?.get("inner1")?.lineNumber).isEqualTo(2)
    assertThat(root.asJsonObject?.get("outer")?.asJsonObject?.get("inner2")?.lineNumber).isEqualTo(3)
    assertThat(root.asJsonObject?.get("outer")?.asJsonObject?.get("inner2")?.asJsonObject?.get("deepInner")?.lineNumber).isEqualTo(4)
  }

  @Test
  fun `test parser stores line numbers for array elements`() {
    val json =
      """
      {
        "myArray": [
          "first",
          123,
          {
            "nested": "value"
          }
        ]
      }
      """
        .trimIndent()
    val root = JsonSourceParser.parseString(json)

    assertThat(root.asJsonObject?.get("myArray")?.lineNumber).isEqualTo(1)
    assertThat(root.asJsonObject?.get("myArray")?.asJsonArray?.get(0)?.lineNumber).isEqualTo(2)
    assertThat(root.asJsonObject?.get("myArray")?.asJsonArray?.get(1)?.lineNumber).isEqualTo(3)
    assertThat(root.asJsonObject?.get("myArray")?.asJsonArray?.get(2)?.lineNumber).isEqualTo(4)
    assertThat(root.asJsonObject?.get("myArray")?.asJsonArray?.get(2)?.asJsonObject?.get("nested")?.lineNumber).isEqualTo(5)
  }

  @Test
  fun `test parser throws exception for invalid json`() {
    val invalidJson =
      """
      {
        "key": "value",
      }
      """
        .trimIndent()
    val exception = assertThrows(Exception::class.java) { JsonSourceParser.parseString(invalidJson) }
    assertThat(exception).hasMessageThat().contains("Expected name at line 3 column 2 path \$.key")
  }

  @Test
  fun `test parser handles empty json object`() {
    val json = "{}"
    val root = JsonSourceParser.parseString(json)

    assertThat(root.lineNumber).isEqualTo(0)
    assertThat(root).isInstanceOf(JsonSourceObject::class.java)
    assertThat(root.asJsonObject?.size).isEqualTo(0)
  }

  @Test
  fun `test parser handles empty json array`() {
    val json = "[]"
    val root = JsonSourceParser.parseString(json)

    assertThat(root.lineNumber).isEqualTo(0)
    assertThat(root).isInstanceOf(JsonSourceArray::class.java)
    assertThat(root.asJsonArray?.size).isEqualTo(0)
  }

  @Test
  fun `test parser handles various data types and their line numbers`() {
    val json =
      """
      {
        "string": "hello",
        "int": 10,
        "double": 3.14,
        "boolean": true,
        "null": null
      }
      """
        .trimIndent()
    val root = JsonSourceParser.parseString(json)

    assertThat(root.asJsonObject?.get("string")?.lineNumber).isEqualTo(1)
    assertThat(root.asJsonObject?.get("int")?.lineNumber).isEqualTo(2)
    assertThat(root.asJsonObject?.get("double")?.lineNumber).isEqualTo(3)
    assertThat(root.asJsonObject?.get("boolean")?.lineNumber).isEqualTo(4)
    assertThat(root.asJsonObject?.get("null")?.lineNumber).isEqualTo(5)

    assertThat(root.asJsonObject?.get("string")?.asJsonPrimitive?.asString).isEqualTo("hello")
    assertThat(root.asJsonObject?.get("int")?.asJsonPrimitive?.asInt).isEqualTo(10)
    assertThat(root.asJsonObject?.get("double")?.asJsonPrimitive?.asDouble).isEqualTo(3.14)
    assertThat(root.asJsonObject?.get("boolean")?.asJsonPrimitive?.asBoolean).isTrue()
    assertThat(root.asJsonObject?.get("null")?.isJsonNull).isTrue()
  }

  @Test
  fun `test asDouble and asBoolean return null for incorrect types`() {
    val json =
      """
      {
        "string": "hello",
        "int": 10,
        "double": 3.14,
        "boolean": true
      }
      """
        .trimIndent()
    val root = JsonSourceParser.parseString(json)

    assertThat(root.asJsonObject?.get("string")?.asJsonPrimitive?.asDouble).isNull()
    assertThat(root.asJsonObject?.get("int")?.asJsonPrimitive?.asBoolean).isNull()
    assertThat(root.asJsonObject?.get("double")?.asJsonPrimitive?.asBoolean).isNull()
    assertThat(root.asJsonObject?.get("boolean")?.asJsonPrimitive?.asDouble).isNull()
  }
}
