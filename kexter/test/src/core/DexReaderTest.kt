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
package core

import com.google.common.truth.Truth.assertThat
import kexter.core.DexReader
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DexReaderTest {

  // Examples from https://source.android.com/docs/core/runtime/dex-format#leb128
  @Test
  fun sLeb128() {
    assertThat(read(0x00).sLeb128()).isEqualTo(0)
    assertThat(read(0x01).sLeb128()).isEqualTo(1)
    assertThat(read(0x7F).sLeb128()).isEqualTo(-1)
    assertThat(read(0x80, 0x7F).sLeb128()).isEqualTo(-128)
  }

  @Test
  fun sLeb128MultiByte() {
    assertThat(read(0xE5, 0x8E, 0x26).sLeb128()).isEqualTo(624485)
    assertThat(read(0xC0, 0xBB, 0x78).sLeb128()).isEqualTo(-123456)
    assertThat(read(0xFF, 0xFF, 0xFF, 0xFF, 0x07).sLeb128()).isEqualTo(Int.MAX_VALUE)
    assertThat(read(0x80, 0x80, 0x80, 0x80, 0x78).sLeb128()).isEqualTo(Int.MIN_VALUE)
  }

  @Test
  fun sLeb128AdvancesPosition() {
    val reader = read(0x80, 0x7F, 0x02)
    reader.sLeb128()
    assertThat(reader.position).isEqualTo(2u)
    assertThat(reader.sLeb128()).isEqualTo(2)
  }

  // Examples from https://source.android.com/docs/core/runtime/dex-format#leb128
  @Test
  fun uLeb128() {
    assertThat(read(0x00).uLeb128()).isEqualTo(0u)
    assertThat(read(0x01).uLeb128()).isEqualTo(1u)
    assertThat(read(0x7F).uLeb128()).isEqualTo(127u)
    assertThat(read(0x80, 0x7F).uLeb128()).isEqualTo(16256u)
  }

  @Test
  fun uLeb128MultiByte() {
    assertThat(read(0xE5, 0x8E, 0x26).uLeb128()).isEqualTo(624485u)
    assertThat(read(0xFF, 0xFF, 0xFF, 0xFF, 0x0F).uLeb128()).isEqualTo(UInt.MAX_VALUE)
  }

  @Test
  fun uLeb128AdvancesPosition() {
    val reader = read(0x80, 0x7F, 0x02)
    reader.uLeb128()
    assertThat(reader.position).isEqualTo(2u)
    assertThat(reader.uLeb128()).isEqualTo(2u)
  }

  // Examples from https://source.android.com/docs/core/runtime/dex-format#leb128
  @Test
  fun uLeb128p1() {
    assertThat(read(0x00).uLeb128p1()).isEqualTo(-1)
    assertThat(read(0x01).uLeb128p1()).isEqualTo(0)
    assertThat(read(0x7F).uLeb128p1()).isEqualTo(126)
    assertThat(read(0x80, 0x7F).uLeb128p1()).isEqualTo(16255)
  }

  @Test
  fun uLeb128p1MultiByte() {
    assertThat(read(0xE5, 0x8E, 0x26).uLeb128p1()).isEqualTo(624484)
    assertThat(read(0xFF, 0xFF, 0xFF, 0xFF, 0x0F).uLeb128p1()).isEqualTo(UInt.MAX_VALUE.toLong() - 1)
  }

  @Test
  fun uLeb128p1AdvancesPosition() {
    val reader = read(0x80, 0x7F, 0x02)
    reader.uLeb128p1()
    assertThat(reader.position).isEqualTo(2u)
    assertThat(reader.uLeb128p1()).isEqualTo(1)
  }

  private fun read(vararg bytes: Int) = DexReader(ByteArray(bytes.size) { bytes[it].toByte() })
}
