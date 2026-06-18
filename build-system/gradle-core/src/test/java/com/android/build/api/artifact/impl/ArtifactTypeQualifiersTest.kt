/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.api.artifact.impl

import com.android.build.api.artifact.Artifact
import com.google.common.truth.Truth.assertThat
import java.lang.RuntimeException
import kotlin.test.fail
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ArtifactTypeQualifiersTest {

  @Test
  fun testSorting() {
    val attributes = ArtifactTypeQualifiers(mapOf("b" to "2", "a" to "1"))
    assertThat(attributes.value.keys).containsExactly("a", "b").inOrder()
  }

  @Test
  fun testToString() {
    val attributes = ArtifactTypeQualifiers(mapOf("b" to "2", "a" to "1"))
    val sb = StringBuffer()
    attributes.toString(sb)
    assertThat(sb.toString()).isEqualTo("a=1, b=2")
  }

  @Test
  fun testToStringOverride() {
    val attributes = ArtifactTypeQualifiers(mapOf("b" to "2", "a" to "1"))
    assertThat(attributes.toString()).contains("a=1, b=2")
  }

  @Test
  fun testToPath() {
    val attributes = ArtifactTypeQualifiers(mapOf("b" to "2", "a" to "1"))
    val path = mutableListOf<String>()
    attributes.toPath(path)
    assertThat(path).containsExactly("a", "1", "b", "2").inOrder()
  }

  @Test
  fun testEnsureAttributesCorrectness() {
    val attributes = ArtifactTypeQualifiers(mapOf("valid" to "1"))

    val artifactType =
      object : Artifact.WithQualifiers {
        override val qualifierKeys: List<String>
          get() = listOf("valid", "other")
      }

    // Should not throw
    attributes.ensureAttributesCorrectness(artifactType)
  }

  @Test
  fun testEnsureAttributesCorrectnessFailure() {
    val attributes = ArtifactTypeQualifiers(mapOf("invalid" to "1", "valid" to "2"))

    val artifactType =
      object : Artifact.WithQualifiers {
        override val qualifierKeys: List<String>
          get() = listOf("valid")
      }

    try {
      attributes.ensureAttributesCorrectness(artifactType)
      fail("Should have thrown RuntimeException")
    } catch (e: RuntimeException) {
      assertThat(e.message).contains("is using undeclared qualifier key(s) <invalid>")
      assertThat(e.message).contains("possible keys are <valid>")
    }
  }

  @Test
  fun testEnsureAttributesUniqueness() {
    val attributes = ArtifactTypeQualifiers(mapOf("key" to "value"))
    val container = mock(MultipleArtifactContainer::class.java)

    // Case 1: No duplicate
    `when`(container.getImplWithAttributes(attributes)).thenReturn(null)
    attributes.ensureAttributesUniqueness(container)

    // Case 2: Duplicate exists
    val existingArtifact = mock(RegisteredArtifactWithQualifiersImpl::class.java)
    `when`(existingArtifact.producerName).thenReturn("existingTask")
    `when`(container.getImplWithAttributes(attributes)).thenReturn(existingArtifact as RegisteredArtifactWithQualifiersImpl<*>?)

    try {
      attributes.ensureAttributesUniqueness(container)
      fail("Should have thrown RuntimeException")
    } catch (e: RuntimeException) {
      assertThat(e.message).contains("has already been added by Task named `existingTask`")
    }
  }
}
