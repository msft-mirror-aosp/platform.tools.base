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
package com.android.ide.common.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WellKnownMavenArtifactIdTest {
  @Test
  fun testFindKotlinArtifacts() {
    assertThat(WellKnownMavenArtifactId.find("org.jetbrains.kotlin", "kotlin-stdlib")).isEqualTo(WellKnownMavenArtifactId.KOTLIN_STDLIB)
    assertThat(WellKnownMavenArtifactId.find("org.jetbrains.kotlin", "kotlin-reflect")).isEqualTo(WellKnownMavenArtifactId.KOTLIN_REFLECT)
  }

  @Test
  fun testDoesNotFindNonexistentKotlinArtifact() {
    assertThat(WellKnownMavenArtifactId.find("org.jetbrains.kotlin", "kotlin-nonexistent")).isNull()
  }

  @Test
  fun testFindTfliteArtifacts() {
    assertThat(WellKnownMavenArtifactId.find("org.tensorflow", "tensorflow-lite-gpu")).isEqualTo(WellKnownMavenArtifactId.TFLITE_GPU)
    assertThat(WellKnownMavenArtifactId.find("org.tensorflow", "tensorflow-lite-metadata"))
      .isEqualTo(WellKnownMavenArtifactId.TFLITE_METADATA)
    assertThat(WellKnownMavenArtifactId.find("org.tensorflow", "tensorflow-lite-support"))
      .isEqualTo(WellKnownMavenArtifactId.TFLITE_SUPPORT)
  }

  @Test
  fun testDoesNotFindNonexistentTfliteArtifact() {
    assertThat(WellKnownMavenArtifactId.find("org.tensorflow", "tensorflow-lite-nonexistent")).isNull()
  }

  @Test
  fun testFindGuavaArtifacts() {
    assertThat(WellKnownMavenArtifactId.find("com.google.guava", "guava")).isEqualTo((WellKnownMavenArtifactId.GUAVA_GUAVA))
  }

  @Test
  fun testDoesNotFindNonexistentGuavaArtifact() {
    assertThat(WellKnownMavenArtifactId.find("com.google.guava", "guava-nonexistent")).isNull()
  }

  @Test
  fun testFindJunitArtifacts() {
    assertThat(WellKnownMavenArtifactId.find("junit", "junit")).isEqualTo(WellKnownMavenArtifactId.JUNIT_JUNIT)
  }

  @Test
  fun testDoesNotFindNonexistentJunitArtifact() {
    assertThat(WellKnownMavenArtifactId.find("junit", "junit-nonexistent")).isNull()
  }

  @Test
  fun testFindGMavenArtifacts() {
    assertThat(WellKnownMavenArtifactId.find("com.android.support", "animated-vector-drawable"))
      .isEqualTo(GoogleMavenArtifactId.SUPPORT_ANIMATED_VECTOR_DRAWABLE)
    assertThat(WellKnownMavenArtifactId.find("androidx.core", "core-ktx")).isEqualTo(GoogleMavenArtifactId.ANDROIDX_CORE_KTX)
  }
}
