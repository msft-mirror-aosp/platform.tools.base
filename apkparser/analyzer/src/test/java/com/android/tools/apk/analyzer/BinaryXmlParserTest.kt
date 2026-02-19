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
package com.android.tools.apk.analyzer

import com.google.common.primitives.Bytes
import com.google.common.primitives.Ints
import com.google.common.truth.Truth.assertThat
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue.Type.DIMENSION
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue.Type.FLOAT
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue.Type.FRACTION
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue.Type.INT_COLOR_ARGB4
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue.Type.INT_COLOR_ARGB8
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue.Type.INT_COLOR_RGB4
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue.Type.INT_COLOR_RGB8
import com.google.devrel.gmscore.tools.apk.arsc.ResourceValue.Type.REFERENCE
import java.nio.ByteBuffer
import org.junit.Test

class BinaryXmlParserTest {
  @Test
  fun testFormatFloatValue() {
    val value42 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, FLOAT.code()), Ints.toByteArray(42.0f.toBits()))))

    val value425 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, FLOAT.code()), Ints.toByteArray(42.5f.toBits()))))

    assertThat(BinaryXmlParser.formatValue(value42, null)).isEqualTo("42")
    assertThat(BinaryXmlParser.formatValue(value425, null)).isEqualTo("42.5")
  }

  @Test
  fun testFormatDimensionValue() {
    val value1 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, DIMENSION.code()), Ints.toByteArray((384 shl 8) + 1))))

    val value2 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, DIMENSION.code()), Ints.toByteArray(0x11400024))))

    assertThat(BinaryXmlParser.formatValue(value1, null)).isEqualTo("384dp")
    assertThat(BinaryXmlParser.formatValue(value2, null)).isEqualTo("34.5in")
  }

  @Test
  fun testFormatFractionValue() {
    val value1 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, FRACTION.code()), Ints.toByteArray(0x01000030))))

    val value2 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, FRACTION.code()), Ints.toByteArray(0x20000030))))

    val value3 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, FRACTION.code()), Ints.toByteArray(0x40000030))))

    val value4 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, FRACTION.code()), Ints.toByteArray(0x40000031))))

    assertThat(BinaryXmlParser.formatValue(value1, null)).isEqualTo("0.78125%")
    assertThat(BinaryXmlParser.formatValue(value2, null)).isEqualTo("25%")
    assertThat(BinaryXmlParser.formatValue(value3, null)).isEqualTo("50%")
    assertThat(BinaryXmlParser.formatValue(value4, null)).isEqualTo("50%p")
  }

  @Test
  fun testFormatColorValue() {
    val value1 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, INT_COLOR_ARGB8.code()), Ints.toByteArray(-0xaa9989))))

    val value2 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, INT_COLOR_RGB8.code()), Ints.toByteArray(-0xaa9989))))

    val value3 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, INT_COLOR_ARGB4.code()), Ints.toByteArray(0x1234F567))))

    val value4 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, INT_COLOR_RGB4.code()), Ints.toByteArray(0x1234F567))))

    assertThat(BinaryXmlParser.formatValue(value1, null)).isEqualTo("#FF556677")
    assertThat(BinaryXmlParser.formatValue(value2, null)).isEqualTo("#556677")
    assertThat(BinaryXmlParser.formatValue(value3, null)).isEqualTo("#F567")
    assertThat(BinaryXmlParser.formatValue(value4, null)).isEqualTo("#567")
  }

  @Test
  fun testFormatReferenceValue() {
    val resolver = ResourceIdResolver { i: Int ->
      if (i == 0x12345678) {
        return@ResourceIdResolver "@package:restype/res_name"
      } else {
        return@ResourceIdResolver ""
      }
    }

    val value1 =
      ResourceValue.create(ByteBuffer.wrap(Bytes.concat(byteArrayOf(0x0, 0x8, 0x0, REFERENCE.code()), Ints.toByteArray(0x12345678))))

    assertThat(BinaryXmlParser.formatValue(value1, null)).isEqualTo("@ref/0x12345678")
    assertThat(BinaryXmlParser.formatValue(value1, null, resolver)).isEqualTo("@package:restype/res_name")
  }
}
