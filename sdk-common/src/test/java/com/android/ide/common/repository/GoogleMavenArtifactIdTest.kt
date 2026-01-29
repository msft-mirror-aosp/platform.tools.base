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

class GoogleMavenArtifactIdTest {
  @Test
  fun testFindByModuleId() {
    assertThat(GoogleMavenArtifactId.find("com.android.support:animated-vector-drawable"))
      .isEqualTo(GoogleMavenArtifactId.SUPPORT_ANIMATED_VECTOR_DRAWABLE)
    assertThat(GoogleMavenArtifactId.find("androidx.core:core-ktx")).isEqualTo(GoogleMavenArtifactId.ANDROIDX_CORE_KTX)
    assertThat(GoogleMavenArtifactId.find("android.arch.core:common")).isEqualTo(GoogleMavenArtifactId.CORE_COMMON)
    assertThat(GoogleMavenArtifactId.find("com.google.android.support:wearable")).isEqualTo(GoogleMavenArtifactId.SUPPORT_WEARABLE)
    assertThat(GoogleMavenArtifactId.find("androidx.annotation:annotation")).isEqualTo(GoogleMavenArtifactId.ANDROIDX_ANNOTATION)
    assertThat(GoogleMavenArtifactId.find("android.arch.work:work-testing")).isEqualTo(GoogleMavenArtifactId.WORK_TESTING)
  }

  @Test
  fun testFindReturnsNullForNonGoogleMavenModuleId() {
    assertThat(GoogleMavenArtifactId.find("com.example:non-existent")).isNull()
  }

  @Test
  fun testFindByGroupArtifactPair() {
    assertThat(GoogleMavenArtifactId.find("com.android.support", "animated-vector-drawable"))
      .isEqualTo(GoogleMavenArtifactId.SUPPORT_ANIMATED_VECTOR_DRAWABLE)
    assertThat(GoogleMavenArtifactId.find("androidx.core", "core-ktx")).isEqualTo(GoogleMavenArtifactId.ANDROIDX_CORE_KTX)
    assertThat(GoogleMavenArtifactId.find("android.arch.core", "common")).isEqualTo(GoogleMavenArtifactId.CORE_COMMON)
    assertThat(GoogleMavenArtifactId.find("com.google.android.support", "wearable")).isEqualTo(GoogleMavenArtifactId.SUPPORT_WEARABLE)
    assertThat(GoogleMavenArtifactId.find("androidx.annotation", "annotation")).isEqualTo(GoogleMavenArtifactId.ANDROIDX_ANNOTATION)
    assertThat(GoogleMavenArtifactId.find("android.arch.work", "work-testing")).isEqualTo(GoogleMavenArtifactId.WORK_TESTING)
  }

  @Test
  fun testFindReturnsNullForNonGoogleMavenGroupArtifactPair() {
    assertThat(GoogleMavenArtifactId.find("com.example", "non-existent")).isNull()
  }
}
