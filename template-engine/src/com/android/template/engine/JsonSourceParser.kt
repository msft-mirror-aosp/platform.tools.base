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

import com.google.gson.ToNumberPolicy
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import java.io.StringReader

internal class JsonSourceParser(private val reader: JsonReader) {

  fun parse(): JsonSourceElement {
    val line = getLineNumber()
    return when (reader.peek()) {
      JsonToken.BEGIN_OBJECT -> parseObject(line)
      JsonToken.BEGIN_ARRAY -> parseArray(line)
      else -> parsePrimitive(line)
    }
  }

  private fun parseObject(line: Int): JsonSourceObject {
    val members = mutableMapOf<String, JsonSourceElement>()
    reader.beginObject()
    while (reader.hasNext()) {
      val key = reader.nextName()
      members[key] = parse() // Recursive call
    }
    reader.endObject()
    return JsonSourceObject(line, members)
  }

  private fun parseArray(line: Int): JsonSourceArray {
    val items = mutableListOf<JsonSourceElement>()
    reader.beginArray()
    while (reader.hasNext()) {
      items.add(parse())
    }
    reader.endArray()
    return JsonSourceArray(line, items)
  }

  private fun parsePrimitive(line: Int): JsonSourcePrimitive {
    val value =
      when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> ToNumberPolicy.LONG_OR_DOUBLE.readNumber(reader)
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> {
          reader.nextNull()
          null
        }
        else -> throw IllegalStateException("Unexpected token: ${reader.peek()}")
      }
    return JsonSourcePrimitive(line, value)
  }

  // Accessing the private line number field in JsonReader via reflection
  private fun getLineNumber(): Int {
    val field = lineNumberField ?: return 0
    return runCatching { field.get(reader) as Int }.getOrDefault(0)
  }

  companion object {
    private val lineNumberField: java.lang.reflect.Field? =
      runCatching { JsonReader::class.java.getDeclaredField("lineNumber").apply { isAccessible = true } }.getOrNull()

    fun parseReader(reader: Reader): JsonSourceElement {
      return parseJsonReader(JsonReader(reader))
    }

    fun parseJsonReader(reader: JsonReader): JsonSourceElement {
      return JsonSourceParser(reader).parse()
    }

    fun parseString(json: String): JsonSourceElement {
      return parseReader(StringReader(json))
    }
  }
}

sealed class JsonSourceElement(val lineNumber: Int) {

  val asJsonPrimitive: JsonSourcePrimitive?
    get() = this as? JsonSourcePrimitive

  val asJsonArray: JsonSourceArray?
    get() = this as? JsonSourceArray

  open val asString: String?
    get() = (this as? JsonSourcePrimitive)?.asString

  open val isJsonNull: Boolean
    get() = (this as? JsonSourcePrimitive)?.isJsonNull ?: false

  val asJsonObject: JsonSourceObject?
    get() = this as? JsonSourceObject
}

// Represents a JSON Object: { "key": value }
class JsonSourceObject(line: Int, val members: Map<String, JsonSourceElement>) :
  JsonSourceElement(line), Map<String, JsonSourceElement> by members {
  fun has(name: String): Boolean {
    return members.containsKey(name)
  }
}

// Represents a JSON Array: [ value1, value2 ]
class JsonSourceArray(line: Int, val items: List<JsonSourceElement>) : JsonSourceElement(line), List<JsonSourceElement> by items

// Represents a Primitive: "string", 123, true, or null
class JsonSourcePrimitive(line: Int, val value: Any?) : JsonSourceElement(line) {
  init {
    check(value == null || value is String || value is Number || value is Boolean)
  }

  val asInt: Int?
    get() = asNumber?.toInt()

  val asDouble: Double?
    get() = asNumber?.toDouble()

  val asBoolean: Boolean?
    get() = value as? Boolean

  override val asString: String?
    get() = value as? String

  val asNumber: Number?
    get() = value as? Number

  override val isJsonNull: Boolean
    get() = value == null

  val isTrue: Boolean
    get() = value == true

  val isFalse: Boolean
    get() = value == false
}
