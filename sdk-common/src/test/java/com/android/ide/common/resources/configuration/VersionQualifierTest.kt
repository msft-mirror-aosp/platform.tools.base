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
package com.android.ide.common.resources.configuration

import com.android.sdklib.AndroidApiLevel
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify

@RunWith(JUnit4::class)
class VersionQualifierTest {
  @Test
  fun defaultVersion() {
    val vq = VersionQualifier()

    assertThat(vq.version).isEqualTo(VersionQualifier.DEFAULT.version)
    assertThat(vq.androidApiLevel).isNull()
    assertThat(vq.isValid).isFalse()
    assertThat(vq.folderSegment).isEqualTo("")
    assertThat(vq.shortDisplayValue).isEqualTo("")
    assertThat(vq.longDisplayValue).isEqualTo("")

    assertThat(vq).isEqualTo(VersionQualifier(-1))
  }

  @Test
  fun nonZeroMinorVersionMustBeIncluded() {
    assertThrows(IllegalArgumentException::class.java) { VersionQualifier(AndroidApiLevel(37, 1), includeMinorVersion = false) }
  }

  @Test
  fun majorVersionOnly() {
    val vq = VersionQualifier(AndroidApiLevel(15))

    assertThat(vq.version).isEqualTo(15)
    assertThat(vq.androidApiLevel).isEqualTo(AndroidApiLevel(15))
    assertThat(vq.isValid).isTrue()
    assertThat(vq.folderSegment).isEqualTo("v15")
    assertThat(vq.shortDisplayValue).isEqualTo("API 15")
    assertThat(vq.longDisplayValue).isEqualTo("API Level 15")

    assertThat(vq).isEqualTo(VersionQualifier(15))
    assertThat(vq).isEqualTo(VersionQualifier(AndroidApiLevel(15), false))
  }

  @Test
  fun majorAndMinorVersion() {
    val vq = VersionQualifier(AndroidApiLevel(37, 1))

    assertThat(vq.version).isEqualTo(37)
    assertThat(vq.androidApiLevel).isEqualTo(AndroidApiLevel(37, 1))
    assertThat(vq.isValid).isTrue()
    assertThat(vq.folderSegment).isEqualTo("v37.1")
    assertThat(vq.shortDisplayValue).isEqualTo("API 37.1")
    assertThat(vq.longDisplayValue).isEqualTo("API Level 37.1")

    assertThat(vq).isNotEqualTo(VersionQualifier(37))
  }

  @Test
  fun getQualifier() {
    assertThat(VersionQualifier.getQualifier("v15")?.folderSegment).isEqualTo("v15")
    assertThat(VersionQualifier.getQualifier("v36")?.folderSegment).isEqualTo("v36")
    assertThat(VersionQualifier.getQualifier("v36.0")?.folderSegment).isEqualTo("v36.0")
    assertThat(VersionQualifier.getQualifier("v36.1")?.folderSegment).isEqualTo("v36.1")

    assertThat(VersionQualifier.getQualifier("")).isNull()
    assertThat(VersionQualifier.getQualifier("15")).isNull()
    assertThat(VersionQualifier.getQualifier("abdscdsa")).isNull()
    assertThat(VersionQualifier.getQualifier("v15s")).isNull()
  }

  @Test
  fun getFolderSegment() {
    assertThat(VersionQualifier.DEFAULT.getFolderSegment()).isEqualTo("")
    assertThat(VersionQualifier(AndroidApiLevel(15)).getFolderSegment()).isEqualTo("v15")

    assertThat(VersionQualifier(AndroidApiLevel(36)).getFolderSegment()).isEqualTo("v36.0")
    assertThat(VersionQualifier(AndroidApiLevel(36), false).getFolderSegment()).isEqualTo("v36")
    assertThat(VersionQualifier(AndroidApiLevel(36), true).getFolderSegment()).isEqualTo("v36.0")
    assertThat(VersionQualifier(AndroidApiLevel(36, 1)).getFolderSegment()).isEqualTo("v36.1")
  }

  @Test
  fun checkAndSet() {
    val mockFolderConfiguration: FolderConfiguration = mock()

    VersionQualifier().checkAndSet("v15", mockFolderConfiguration)
    verify(mockFolderConfiguration).setVersionQualifier(VersionQualifier(AndroidApiLevel(15), false))
    reset(mockFolderConfiguration)

    VersionQualifier().checkAndSet("v36", mockFolderConfiguration)
    verify(mockFolderConfiguration).setVersionQualifier(VersionQualifier(AndroidApiLevel(36), false))
    reset(mockFolderConfiguration)

    VersionQualifier().checkAndSet("v36.0", mockFolderConfiguration)
    verify(mockFolderConfiguration).setVersionQualifier(VersionQualifier(AndroidApiLevel(36), true))
    reset(mockFolderConfiguration)

    VersionQualifier().checkAndSet("v36.1", mockFolderConfiguration)
    verify(mockFolderConfiguration).setVersionQualifier(VersionQualifier(AndroidApiLevel(36, 1), true))
    reset(mockFolderConfiguration)

    VersionQualifier().checkAndSet("", mockFolderConfiguration)
    verify(mockFolderConfiguration, never()).setVersionQualifier(any())
    reset(mockFolderConfiguration)

    VersionQualifier().checkAndSet("15", mockFolderConfiguration)
    verify(mockFolderConfiguration, never()).setVersionQualifier(any())
    reset(mockFolderConfiguration)

    VersionQualifier().checkAndSet("abdscdsa", mockFolderConfiguration)
    verify(mockFolderConfiguration, never()).setVersionQualifier(any())
    reset(mockFolderConfiguration)

    VersionQualifier().checkAndSet("v15s", mockFolderConfiguration)
    verify(mockFolderConfiguration, never()).setVersionQualifier(any())
    reset(mockFolderConfiguration)
  }

  @Test
  fun validateEquals() {
    val defaultVQ = VersionQualifier()
    val api15VQ = VersionQualifier(AndroidApiLevel(15))
    val api36VQ = VersionQualifier(AndroidApiLevel(36), true)
    val api36VQNoMinor = VersionQualifier(AndroidApiLevel(36), false)

    assertThat(VersionQualifier()).isEqualTo(defaultVQ)
    assertThat(VersionQualifier()).isNotEqualTo(api15VQ)
    assertThat(VersionQualifier()).isNotEqualTo(api36VQ)
    assertThat(VersionQualifier()).isNotEqualTo(api36VQNoMinor)

    assertThat(VersionQualifier(AndroidApiLevel(15))).isNotEqualTo(defaultVQ)
    assertThat(VersionQualifier(AndroidApiLevel(15))).isEqualTo(api15VQ)
    assertThat(VersionQualifier(AndroidApiLevel(15))).isNotEqualTo(api36VQ)
    assertThat(VersionQualifier(AndroidApiLevel(15))).isNotEqualTo(api36VQNoMinor)

    assertThat(VersionQualifier(AndroidApiLevel(36))).isNotEqualTo(defaultVQ)
    assertThat(VersionQualifier(AndroidApiLevel(36))).isNotEqualTo(api15VQ)
    assertThat(VersionQualifier(AndroidApiLevel(36))).isEqualTo(api36VQ)
    assertThat(VersionQualifier(AndroidApiLevel(36))).isNotEqualTo(api36VQNoMinor)

    assertThat(VersionQualifier(AndroidApiLevel(36), false)).isNotEqualTo(defaultVQ)
    assertThat(VersionQualifier(AndroidApiLevel(36), false)).isNotEqualTo(api15VQ)
    assertThat(VersionQualifier(AndroidApiLevel(36), false)).isNotEqualTo(api36VQ)
    assertThat(VersionQualifier(AndroidApiLevel(36), false)).isEqualTo(api36VQNoMinor)
  }

  @Test
  fun validateHashCode() {
    val defaultVQ = VersionQualifier()
    val api15VQ = VersionQualifier(AndroidApiLevel(15))
    val api36VQ = VersionQualifier(AndroidApiLevel(36), true)
    val api36VQNoMinor = VersionQualifier(AndroidApiLevel(36), false)
    val api361VQ = VersionQualifier(AndroidApiLevel(36, 1))

    assertThat(VersionQualifier().hashCode()).isEqualTo(defaultVQ.hashCode())
    assertThat(VersionQualifier().hashCode()).isNotEqualTo(api15VQ.hashCode())
    assertThat(VersionQualifier().hashCode()).isNotEqualTo(api36VQ.hashCode())
    assertThat(VersionQualifier().hashCode()).isNotEqualTo(api36VQNoMinor.hashCode())
    assertThat(VersionQualifier().hashCode()).isNotEqualTo(api361VQ.hashCode())

    assertThat(VersionQualifier(AndroidApiLevel(15)).hashCode()).isNotEqualTo(defaultVQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(15)).hashCode()).isEqualTo(api15VQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(15)).hashCode()).isNotEqualTo(api36VQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(15)).hashCode()).isNotEqualTo(api36VQNoMinor.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(15)).hashCode()).isNotEqualTo(api361VQ.hashCode())

    assertThat(VersionQualifier(AndroidApiLevel(36)).hashCode()).isNotEqualTo(defaultVQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36)).hashCode()).isNotEqualTo(api15VQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36)).hashCode()).isEqualTo(api36VQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36)).hashCode()).isNotEqualTo(api36VQNoMinor.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36)).hashCode()).isNotEqualTo(api361VQ.hashCode())

    assertThat(VersionQualifier(AndroidApiLevel(36), false).hashCode()).isNotEqualTo(defaultVQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36), false).hashCode()).isNotEqualTo(api15VQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36), false).hashCode()).isNotEqualTo(api36VQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36), false).hashCode()).isEqualTo(api36VQNoMinor.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36), false).hashCode()).isNotEqualTo(api361VQ.hashCode())

    assertThat(VersionQualifier(AndroidApiLevel(36, 1)).hashCode()).isNotEqualTo(defaultVQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36, 1)).hashCode()).isNotEqualTo(api15VQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36, 1)).hashCode()).isNotEqualTo(api36VQ.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36, 1)).hashCode()).isNotEqualTo(api36VQNoMinor.hashCode())
    assertThat(VersionQualifier(AndroidApiLevel(36, 1)).hashCode()).isEqualTo(api361VQ.hashCode())
  }

  @Test
  fun isMatchFor_defaultVersion() {
    val vq = VersionQualifier()

    assertThat(vq.isMatchFor(VersionQualifier())).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(15)))).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(36)))).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(36, 1)))).isTrue()

    val otherResourceQualifier: ResourceQualifier = mock()
    assertThat(vq.isMatchFor(otherResourceQualifier)).isFalse()
  }

  @Test
  fun isMatchFor_specifiedVersion() {
    val vq = VersionQualifier(AndroidApiLevel(37, 1))

    assertThat(vq.isMatchFor(VersionQualifier())).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(36)))).isFalse()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(36, 4)))).isFalse()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(37)))).isFalse()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(37, 0)))).isFalse()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(37, 1)))).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(37, 2)))).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(38)))).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(38, 0)))).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(AndroidApiLevel(38, 1)))).isTrue()

    val otherResourceQualifier: ResourceQualifier = mock()
    assertThat(vq.isMatchFor(otherResourceQualifier)).isFalse()
  }

  @Test
  fun isBetterMatchThan_referenceIsDefaultVersion() {
    val reference = VersionQualifier()

    val vqDefault = VersionQualifier()
    val vq36 = VersionQualifier(AndroidApiLevel(36))

    assertThat(vqDefault.isBetterMatchThan(null, reference)).isTrue()
    assertThat(vqDefault.isBetterMatchThan(VersionQualifier(), reference)).isFalse()
    assertThat(vqDefault.isBetterMatchThan(VersionQualifier(AndroidApiLevel(36)), reference)).isTrue()
    assertThat(vqDefault.isBetterMatchThan(VersionQualifier(AndroidApiLevel(36, 1)), reference)).isTrue()

    assertThat(vq36.isBetterMatchThan(null, reference)).isTrue()
    assertThat(vq36.isBetterMatchThan(VersionQualifier(), reference)).isFalse()
    assertThat(vq36.isBetterMatchThan(VersionQualifier(AndroidApiLevel(35)), reference)).isTrue()
    assertThat(vq36.isBetterMatchThan(VersionQualifier(AndroidApiLevel(36)), reference)).isFalse()
    assertThat(vq36.isBetterMatchThan(VersionQualifier(AndroidApiLevel(37)), reference)).isFalse()
  }

  @Test
  fun isBetterMatchThan_referenceIsApi361() {
    val reference = VersionQualifier(AndroidApiLevel(36, 1))

    val vqDefault = VersionQualifier()
    val vq361 = VersionQualifier(AndroidApiLevel(36, 1))

    assertThat(vqDefault.isBetterMatchThan(null, reference)).isTrue()
    assertThat(vqDefault.isBetterMatchThan(VersionQualifier(), reference)).isFalse()
    assertThat(vqDefault.isBetterMatchThan(VersionQualifier(AndroidApiLevel(36)), reference)).isFalse()
    assertThat(vqDefault.isBetterMatchThan(VersionQualifier(AndroidApiLevel(36, 1)), reference)).isFalse()
    assertThat(vqDefault.isBetterMatchThan(VersionQualifier(AndroidApiLevel(36, 2)), reference)).isFalse()

    assertThat(vq361.isBetterMatchThan(null, reference)).isTrue()
    assertThat(vq361.isBetterMatchThan(VersionQualifier(), reference)).isTrue()
    assertThat(vq361.isBetterMatchThan(VersionQualifier(AndroidApiLevel(35)), reference)).isTrue()
    assertThat(vq361.isBetterMatchThan(VersionQualifier(AndroidApiLevel(36)), reference)).isTrue()
    assertThat(vq361.isBetterMatchThan(VersionQualifier(AndroidApiLevel(36, 1)), reference)).isFalse()
    assertThat(vq361.isBetterMatchThan(VersionQualifier(AndroidApiLevel(37)), reference)).isTrue()
  }

  @Test
  fun isBetterMatchThan_referenceHasMinorVersionZeroNotIncluded() {
    val reference = VersionQualifier(AndroidApiLevel(36, 0), false)

    val vq36 = VersionQualifier(AndroidApiLevel(36, 0), false)
    val vq360 = VersionQualifier(AndroidApiLevel(36, 0), true)

    val vq35 = VersionQualifier(AndroidApiLevel(35))
    val vq37 = VersionQualifier(AndroidApiLevel(37, 0), false)
    val vq370 = VersionQualifier(AndroidApiLevel(37, 0), true)

    assertThat(vq36.isBetterMatchThan(vq360, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq36, reference)).isFalse()

    assertThat(vq35.isBetterMatchThan(vq36, reference)).isFalse()
    assertThat(vq35.isBetterMatchThan(vq360, reference)).isFalse()
    assertThat(vq37.isBetterMatchThan(vq36, reference)).isFalse()
    assertThat(vq37.isBetterMatchThan(vq360, reference)).isFalse()
    assertThat(vq370.isBetterMatchThan(vq36, reference)).isFalse()
    assertThat(vq370.isBetterMatchThan(vq360, reference)).isFalse()

    assertThat(vq360.isBetterMatchThan(vq35, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq37, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq370, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq35, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq37, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq370, reference)).isTrue()
  }

  @Test
  fun isBetterMatchThan_referenceHasMinorVersionZeroIncluded() {
    val reference = VersionQualifier(AndroidApiLevel(36, 0), true)

    val vq36 = VersionQualifier(AndroidApiLevel(36, 0), false)
    val vq360 = VersionQualifier(AndroidApiLevel(36, 0), true)

    val vq35 = VersionQualifier(AndroidApiLevel(35))
    val vq37 = VersionQualifier(AndroidApiLevel(37, 0), false)
    val vq370 = VersionQualifier(AndroidApiLevel(37, 0), true)

    assertThat(vq36.isBetterMatchThan(vq360, reference)).isFalse()
    assertThat(vq360.isBetterMatchThan(vq36, reference)).isTrue()

    assertThat(vq35.isBetterMatchThan(vq36, reference)).isFalse()
    assertThat(vq35.isBetterMatchThan(vq360, reference)).isFalse()
    assertThat(vq37.isBetterMatchThan(vq36, reference)).isFalse()
    assertThat(vq37.isBetterMatchThan(vq360, reference)).isFalse()
    assertThat(vq370.isBetterMatchThan(vq36, reference)).isFalse()
    assertThat(vq370.isBetterMatchThan(vq360, reference)).isFalse()

    assertThat(vq360.isBetterMatchThan(vq35, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq37, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq370, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq35, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq37, reference)).isTrue()
    assertThat(vq360.isBetterMatchThan(vq370, reference)).isTrue()
  }
}
