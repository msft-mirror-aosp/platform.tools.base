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

import com.google.common.truth.Truth.assertThat
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
    assertThat(vq.isValid).isFalse()
    assertThat(vq.folderSegment).isEqualTo("")
    assertThat(vq.shortDisplayValue).isEqualTo("")
    assertThat(vq.longDisplayValue).isEqualTo("")
  }

  @Test
  fun majorVersionOnly() {
    val vq = VersionQualifier(15)

    assertThat(vq.version).isEqualTo(15)
    assertThat(vq.isValid).isTrue()
    assertThat(vq.folderSegment).isEqualTo("v15")
    assertThat(vq.shortDisplayValue).isEqualTo("API 15")
    assertThat(vq.longDisplayValue).isEqualTo("API Level 15")
  }

  @Test
  fun getQualifier() {
    assertThat(VersionQualifier.getQualifier("v15")).isEqualTo(VersionQualifier(15))
    assertThat(VersionQualifier.getQualifier("v36")).isEqualTo(VersionQualifier(36))
    assertThat(VersionQualifier.getQualifier("")).isNull()
    assertThat(VersionQualifier.getQualifier("15")).isNull()
    assertThat(VersionQualifier.getQualifier("abdscdsa")).isNull()
    assertThat(VersionQualifier.getQualifier("v15s")).isNull()
  }

  @Test
  fun checkAndSet() {
    val mockFolderConfiguration: FolderConfiguration = mock()

    VersionQualifier().checkAndSet("v15", mockFolderConfiguration)
    verify(mockFolderConfiguration).setVersionQualifier(VersionQualifier(15))
    reset(mockFolderConfiguration)

    VersionQualifier().checkAndSet("v36", mockFolderConfiguration)
    verify(mockFolderConfiguration).setVersionQualifier(VersionQualifier(36))
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
    val api15VQ = VersionQualifier(15)
    val api36VQ = VersionQualifier(36)

    assertThat(VersionQualifier()).isEqualTo(defaultVQ)
    assertThat(VersionQualifier()).isNotEqualTo(api15VQ)
    assertThat(VersionQualifier()).isNotEqualTo(api36VQ)

    assertThat(VersionQualifier(15)).isNotEqualTo(defaultVQ)
    assertThat(VersionQualifier(15)).isEqualTo(api15VQ)
    assertThat(VersionQualifier(15)).isNotEqualTo(api36VQ)

    assertThat(VersionQualifier(36)).isNotEqualTo(defaultVQ)
    assertThat(VersionQualifier(36)).isNotEqualTo(api15VQ)
    assertThat(VersionQualifier(36)).isEqualTo(api36VQ)
  }

  @Test
  fun validateHashCode() {
    val defaultVQ = VersionQualifier()
    val api15VQ = VersionQualifier(15)
    val api36VQ = VersionQualifier(36)

    assertThat(VersionQualifier().hashCode()).isEqualTo(defaultVQ.hashCode())
    assertThat(VersionQualifier().hashCode()).isNotEqualTo(api15VQ.hashCode())
    assertThat(VersionQualifier().hashCode()).isNotEqualTo(api36VQ.hashCode())

    assertThat(VersionQualifier(15).hashCode()).isNotEqualTo(defaultVQ.hashCode())
    assertThat(VersionQualifier(15).hashCode()).isEqualTo(api15VQ.hashCode())
    assertThat(VersionQualifier(15).hashCode()).isNotEqualTo(api36VQ.hashCode())

    assertThat(VersionQualifier(36).hashCode()).isNotEqualTo(defaultVQ.hashCode())
    assertThat(VersionQualifier(36).hashCode()).isNotEqualTo(api15VQ.hashCode())
    assertThat(VersionQualifier(36).hashCode()).isEqualTo(api36VQ.hashCode())
  }

  @Test
  fun isMatchFor_defaultVersion() {
    val vq = VersionQualifier()

    assertThat(vq.isMatchFor(VersionQualifier())).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(15))).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(36))).isTrue()

    val otherResourceQualifier: ResourceQualifier = mock()
    assertThat(vq.isMatchFor(otherResourceQualifier)).isFalse()
  }

  @Test
  fun isMatchFor_specifiedVersion() {
    val vq = VersionQualifier(15)

    assertThat(vq.isMatchFor(VersionQualifier())).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(14))).isFalse()
    assertThat(vq.isMatchFor(VersionQualifier(15))).isTrue()
    assertThat(vq.isMatchFor(VersionQualifier(16))).isTrue()

    val otherResourceQualifier: ResourceQualifier = mock()
    assertThat(vq.isMatchFor(otherResourceQualifier)).isFalse()
  }

  @Test
  fun isBetterMatchThan_referenceIsDefaultVersion() {
    val reference = VersionQualifier()

    assertThat(VersionQualifier().isBetterMatchThan(null, reference)).isTrue()
    assertThat(VersionQualifier().isBetterMatchThan(VersionQualifier(), reference)).isFalse()
    assertThat(VersionQualifier().isBetterMatchThan(VersionQualifier(15), reference)).isTrue()

    assertThat(VersionQualifier(15).isBetterMatchThan(null, reference)).isTrue()
    assertThat(VersionQualifier(15).isBetterMatchThan(VersionQualifier(), reference)).isFalse()
    assertThat(VersionQualifier(15).isBetterMatchThan(VersionQualifier(14), reference)).isTrue()
    assertThat(VersionQualifier(15).isBetterMatchThan(VersionQualifier(15), reference)).isFalse()
    assertThat(VersionQualifier(15).isBetterMatchThan(VersionQualifier(16), reference)).isFalse()
  }

  @Test
  fun isBetterMatchThan_referenceIsApi15() {
    val reference = VersionQualifier(15)

    assertThat(VersionQualifier().isBetterMatchThan(null, reference)).isTrue()
    assertThat(VersionQualifier().isBetterMatchThan(VersionQualifier(), reference)).isFalse()
    assertThat(VersionQualifier().isBetterMatchThan(VersionQualifier(15), reference)).isFalse()

    assertThat(VersionQualifier(15).isBetterMatchThan(null, reference)).isTrue()
    assertThat(VersionQualifier(15).isBetterMatchThan(VersionQualifier(), reference)).isTrue()
    assertThat(VersionQualifier(15).isBetterMatchThan(VersionQualifier(14), reference)).isTrue()
    assertThat(VersionQualifier(15).isBetterMatchThan(VersionQualifier(15), reference)).isFalse()
    assertThat(VersionQualifier(15).isBetterMatchThan(VersionQualifier(16), reference)).isTrue()
  }
}
