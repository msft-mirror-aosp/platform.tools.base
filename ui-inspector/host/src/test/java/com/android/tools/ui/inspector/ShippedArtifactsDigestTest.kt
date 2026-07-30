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

package com.android.tools.ui.inspector

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShippedArtifactsDigestTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private var counter = 0

  private fun file(name: String, content: String): Path {
    val path = tempFolder.newFile(name).toPath()
    Files.write(path, content.toByteArray())
    return path
  }

  private fun digestOf(agent: String, service: String, payload: String, viewInspector: String): String {
    return shippedArtifactsDigest(
      agentBinary = file("agent-${counter++}", agent),
      serviceJar = file("service-${counter++}", service),
      payloadJar = file("payload-${counter++}", payload),
      viewInspectorJar = file("view-${counter++}", viewInspector),
    )
  }

  @Test
  fun digest_matchesKnownVector() {
    // SHA-256 over u64be(len) || bytes per file, in order: "a", "bb", "ccc", "dddd".
    assertThat(digestOf("a", "bb", "ccc", "dddd")).isEqualTo("d590eef51fb3")
  }

  @Test
  fun digest_emptyArtifacts_matchesKnownVector() {
    assertThat(digestOf("", "", "", "")).isEqualTo("66687aadf862")
  }

  @Test
  fun digest_isTwelveLowercaseHexCharacters() {
    assertThat(digestOf("agent", "service", "payload", "view")).matches("[0-9a-f]{12}")
  }

  @Test
  fun digest_eachArtifactAffectsTheDigest() {
    val baseline = digestOf("a", "b", "c", "d")
    assertThat(digestOf("X", "b", "c", "d")).isNotEqualTo(baseline)
    assertThat(digestOf("a", "X", "c", "d")).isNotEqualTo(baseline)
    assertThat(digestOf("a", "b", "X", "d")).isNotEqualTo(baseline)
    assertThat(digestOf("a", "b", "c", "X")).isNotEqualTo(baseline)
  }

  @Test
  fun digest_fileBoundariesAffectTheDigest() {
    // The same overall byte stream split differently across files must not collide: the length framing makes boundaries part of the hash.
    assertThat(digestOf("ab", "", "", "")).isNotEqualTo(digestOf("a", "b", "", ""))
  }

  @Test
  fun digest_missingArtifactThrows() {
    val missing = tempFolder.root.toPath().resolve("does-not-exist")
    assertThrows(NoSuchFileException::class.java) {
      shippedArtifactsDigest(
        agentBinary = missing,
        serviceJar = file("service", ""),
        payloadJar = file("payload", ""),
        viewInspectorJar = file("view", ""),
      )
    }
  }
}
