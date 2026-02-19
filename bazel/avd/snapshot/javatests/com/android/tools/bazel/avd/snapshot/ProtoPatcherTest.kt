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

package com.android.tools.bazel.avd.snapshot

import com.android.tools.bazel.avd.snapshot.test.TestData
import com.google.protobuf.Any
import com.google.protobuf.ByteString
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProtoPatcherTest {

  @Rule @JvmField val tempFolder = TemporaryFolder()

  private lateinit var testFile: File

  // Helper function to create a TestMessage with default values for tests.
  private fun createTestMessage(): TestData.TestMessage {
    val nestedMessage = TestData.NestedMessage.newBuilder().setNestedField("This is a nested_field string.").setNestedNumber(456).build()

    // Create a message to be packed into the Any field.
    val anyPayload =
      TestData.NestedMessage.newBuilder().setNestedField("An any_field_string for the Any type.").setNestedNumber(789).build()

    return TestData.TestMessage.newBuilder()
      .setTopLevelString("A top_level_string for testing.")
      .setNestedMessage(nestedMessage)
      .setUntouchedNumber(123)
      .addRepeatedStrings("repeat_one")
      .addRepeatedStrings("string_repeat_two")
      .addRepeatedStrings("repeat_three_string")
      .setRawBytes(ByteString.copyFrom("raw_bytes_data", StandardCharsets.UTF_8))
      .setAnotherTopLevelString("Another top_level_string.")
      .setOneofString("This is a oneof_string for testing.") // Set the oneof
      .setAnyField(Any.pack(anyPayload)) // Set the Any field
      .build()
  }

  // Set up a fresh test file before each test.
  @Before
  fun setUp() {
    testFile = tempFolder.newFile("test.pb")
    val message = createTestMessage()
    testFile.writeBytes(message.toByteArray())
  }

  @Test
  fun `patchProtoFile should replace string in a top-level field`() {
    val replacements = mapOf("top_level_string" to "MODIFIED_STRING")
    val expected =
      """
      top_level_string: "A MODIFIED_STRING for testing."
      nested_message {
        nested_field: "This is a nested_field string."
        nested_number: 456
      }
      untouched_number: 123
      repeated_strings: "repeat_one"
      repeated_strings: "string_repeat_two"
      repeated_strings: "repeat_three_string"
      raw_bytes: "raw_bytes_data"
      another_top_level_string: "Another MODIFIED_STRING."
      oneof_string: "This is a oneof_string for testing."
      any_field {
        type_url: "type.googleapis.com/com.android.tools.bazel.avd.snapshot.test.NestedMessage"
        value: "\n%An any_field_string for the Any type.\020\225\006"
      }
      """
        .trimIndent()

    val replacedCount = patchProtoFile(testFile, replacements)
    val patchedMessage = TestData.TestMessage.parseFrom(testFile.readBytes())

    assertEquals(expected, patchedMessage.toString().trimIndent())
    assertEquals(2, replacedCount)
  }

  @Test
  fun `patchProtoFile should replace string in a nested message field`() {
    val replacements = mapOf("nested_field" to "PATCHED_NESTED")
    val expected =
      """
      top_level_string: "A top_level_string for testing."
      nested_message {
        nested_field: "This is a PATCHED_NESTED string."
        nested_number: 456
      }
      untouched_number: 123
      repeated_strings: "repeat_one"
      repeated_strings: "string_repeat_two"
      repeated_strings: "repeat_three_string"
      raw_bytes: "raw_bytes_data"
      another_top_level_string: "Another top_level_string."
      oneof_string: "This is a oneof_string for testing."
      any_field {
        type_url: "type.googleapis.com/com.android.tools.bazel.avd.snapshot.test.NestedMessage"
        value: "\n%An any_field_string for the Any type.\020\225\006"
      }
      """
        .trimIndent()

    val replacedCount = patchProtoFile(testFile, replacements)
    val patchedMessage = TestData.TestMessage.parseFrom(testFile.readBytes())

    assertEquals(expected, patchedMessage.toString().trimIndent())
    assertEquals(1, replacedCount)
  }

  @Test
  fun `patchProtoFile should replace strings in repeated fields`() {
    val replacements = mapOf("repeat" to "REPLACED")
    val expected =
      """
      top_level_string: "A top_level_string for testing."
      nested_message {
        nested_field: "This is a nested_field string."
        nested_number: 456
      }
      untouched_number: 123
      repeated_strings: "REPLACED_one"
      repeated_strings: "string_REPLACED_two"
      repeated_strings: "REPLACED_three_string"
      raw_bytes: "raw_bytes_data"
      another_top_level_string: "Another top_level_string."
      oneof_string: "This is a oneof_string for testing."
      any_field {
        type_url: "type.googleapis.com/com.android.tools.bazel.avd.snapshot.test.NestedMessage"
        value: "\n%An any_field_string for the Any type.\020\225\006"
      }
      """
        .trimIndent()

    val replacedCount = patchProtoFile(testFile, replacements)
    val patchedMessage = TestData.TestMessage.parseFrom(testFile.readBytes())

    assertEquals(expected, patchedMessage.toString().trimIndent())
    assertEquals(3, replacedCount)
  }

  @Test
  fun `patchProtoFile should not modify file if no strings match`() {
    val originalBytes = testFile.readBytes()
    val replacements = mapOf("non_existent_string" to "WONT_BE_USED")
    val expected =
      """
      top_level_string: "A top_level_string for testing."
      nested_message {
        nested_field: "This is a nested_field string."
        nested_number: 456
      }
      untouched_number: 123
      repeated_strings: "repeat_one"
      repeated_strings: "string_repeat_two"
      repeated_strings: "repeat_three_string"
      raw_bytes: "raw_bytes_data"
      another_top_level_string: "Another top_level_string."
      oneof_string: "This is a oneof_string for testing."
      any_field {
        type_url: "type.googleapis.com/com.android.tools.bazel.avd.snapshot.test.NestedMessage"
        value: "\n%An any_field_string for the Any type.\020\225\006"
      }
      """
        .trimIndent()

    val replacedCount = patchProtoFile(testFile, replacements)
    val finalBytes = testFile.readBytes()
    val patchedMessage = TestData.TestMessage.parseFrom(finalBytes)

    assertEquals(expected, patchedMessage.toString().trimIndent())
    assertEquals(0, replacedCount)
    assertArrayEquals(originalBytes, finalBytes)
  }

  @Test
  fun `patchProtoFile should handle chained replacements in order`() {
    val replacements = linkedMapOf("string" to "STRING", "STRING" to "FINAL_FORM")
    val expected =
      """
      top_level_string: "A top_level_FINAL_FORM for testing."
      nested_message {
        nested_field: "This is a nested_field FINAL_FORM."
        nested_number: 456
      }
      untouched_number: 123
      repeated_strings: "repeat_one"
      repeated_strings: "FINAL_FORM_repeat_two"
      repeated_strings: "repeat_three_FINAL_FORM"
      raw_bytes: "raw_bytes_data"
      another_top_level_string: "Another top_level_FINAL_FORM."
      oneof_string: "This is a oneof_FINAL_FORM for testing."
      any_field {
        type_url: "type.googleapis.com/com.android.tools.bazel.avd.snapshot.test.NestedMessage"
        value: "\n)An any_field_FINAL_FORM for the Any type.\020\225\006"
      }
      """
        .trimIndent()

    val replacedCount = patchProtoFile(testFile, replacements)
    val patchedMessage = TestData.TestMessage.parseFrom(testFile.readBytes())

    assertEquals(expected, patchedMessage.toString().trimIndent())
    assertEquals(7, replacedCount)
  }

  @Test
  fun `patchProtoFile should replace string in a oneof field`() {
    val replacements = mapOf("oneof_string" to "PATCHED_ONEOF")
    val expected =
      """
      top_level_string: "A top_level_string for testing."
      nested_message {
        nested_field: "This is a nested_field string."
        nested_number: 456
      }
      untouched_number: 123
      repeated_strings: "repeat_one"
      repeated_strings: "string_repeat_two"
      repeated_strings: "repeat_three_string"
      raw_bytes: "raw_bytes_data"
      another_top_level_string: "Another top_level_string."
      oneof_string: "This is a PATCHED_ONEOF for testing."
      any_field {
        type_url: "type.googleapis.com/com.android.tools.bazel.avd.snapshot.test.NestedMessage"
        value: "\n%An any_field_string for the Any type.\020\225\006"
      }
      """
        .trimIndent()

    val replacedCount = patchProtoFile(testFile, replacements)
    val patchedMessage = TestData.TestMessage.parseFrom(testFile.readBytes())

    assertEquals(expected, patchedMessage.toString().trimIndent())
    assertEquals(1, replacedCount)
  }

  @Test
  fun `patchProtoFile should replace string in a message packed in an Any field`() {
    val replacements = mapOf("An any_field_string" to "A PATCHED_ANY")

    val replacedCount = patchProtoFile(testFile, replacements)
    val patchedMessage = TestData.TestMessage.parseFrom(testFile.readBytes())
    val unpackedPayload = patchedMessage.anyField.unpack(TestData.NestedMessage::class.java)

    assertEquals("A PATCHED_ANY for the Any type.", unpackedPayload.nestedField)
    assertEquals(789, unpackedPayload.nestedNumber)
    assertEquals(1, replacedCount)
  }

  @Test
  fun `setRawBytes with non-utf8 bytes is not modified`() {
    // These bytes are not valid UTF-8.
    val nonUtf8Bytes = byteArrayOf(0xC3.toByte(), 0x28.toByte())

    // Overwrite the file with a message containing these bytes.
    val message = createTestMessage().toBuilder().setRawBytes(ByteString.copyFrom(nonUtf8Bytes)).build()
    testFile.writeBytes(message.toByteArray())

    val replacements = mapOf("some_string" to "WONT_BE_USED")
    val replacedCount = patchProtoFile(testFile, replacements)
    val patchedMessage = TestData.TestMessage.parseFrom(testFile.readBytes())

    // The raw_bytes field should be untouched because it's not a string.
    assertArrayEquals(nonUtf8Bytes, patchedMessage.rawBytes.toByteArray())
    assertEquals(0, replacedCount)
  }

  @Test(expected = FileNotFoundException::class)
  fun `patchProtoFile should throw exception for non-existent file`() {
    val nonExistentFile = File(tempFolder.root, "no_such_file.pb")
    patchProtoFile(nonExistentFile, mapOf("a" to "b"))
  }
}
