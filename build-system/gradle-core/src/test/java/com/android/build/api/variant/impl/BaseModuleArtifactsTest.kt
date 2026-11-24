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

package com.android.build.api.variant.impl

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class BaseModuleArtifactsTest {

  @Test
  fun singleArtifactIsReturned() {
    val file = File("app/build/metadata.json")
    assertThat(requireSingleBaseModuleArtifact(listOf(file), ":feature", "playRelease", "base module metadata")).isSameInstanceAs(file)
  }

  @Test
  fun noArtifactFailsWithActionableMessage() {
    val e =
      assertThrows(RuntimeException::class.java) {
        requireSingleBaseModuleArtifact(emptyList<File>(), ":features:dfm", "playNoE2eeRelease", "base module metadata")
      }
    assertThat(e).isNotInstanceOf(NoSuchElementException::class.java)
    assertThat(e).hasMessageThat().contains("Dynamic feature module ':features:dfm' (variant 'playNoE2eeRelease')")
    assertThat(e).hasMessageThat().contains("could not find a base application module on its compile classpath")
    assertThat(e).hasMessageThat().contains("base module metadata")
    assertThat(e).hasMessageThat().contains("implementation(project(\":app\"))")
    assertThat(e).hasMessageThat().contains("android.dynamicFeatures")
    assertThat(e).hasMessageThat().contains("not runtimeOnly")
    assertThat(e).hasMessageThat().doesNotContain("Collection is empty")
  }

  @Test
  fun multipleArtifactsFailWithActionableMessage() {
    val e =
      assertThrows(RuntimeException::class.java) {
        requireSingleBaseModuleArtifact(
          listOf(File("app1/metadata.json"), File("app2/metadata.json")),
          ":feature",
          "debug",
          "feature set metadata",
        )
      }
    assertThat(e).isNotInstanceOf(IllegalArgumentException::class.java)
    assertThat(e).hasMessageThat().contains("Dynamic feature module ':feature' (variant 'debug') found 2 base application modules")
    assertThat(e).hasMessageThat().contains("feature set metadata")
    assertThat(e).hasMessageThat().contains("app1${File.separator}metadata.json")
    assertThat(e).hasMessageThat().contains("app2${File.separator}metadata.json")
    assertThat(e).hasMessageThat().contains("must depend on exactly one base application module")
  }
}
