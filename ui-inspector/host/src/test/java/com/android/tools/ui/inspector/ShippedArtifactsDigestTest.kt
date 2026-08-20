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

  private fun digestsOf(
    agent: String,
    service: String,
    payload: String,
    viewInspector: String,
    composeOverride: String? = null,
  ): ShippedArtifactsDigests {
    return computeArtifactDigests(
      agentBinary = file("agent-${counter++}", agent),
      serviceJar = file("service-${counter++}", service),
      payloadJar = file("payload-${counter++}", payload),
      viewInspectorJar = file("view-${counter++}", viewInspector),
      composeInspectorOverrideJar = composeOverride?.let { file("compose-override-${counter++}", it) },
    )
  }

  private fun digestOf(agent: String, service: String, payload: String, viewInspector: String, composeOverride: String? = null): String =
    digestsOf(agent, service, payload, viewInspector, composeOverride).combined

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
  fun digest_absentOverride_isTheSameAsExplicitNull() {
    val agent = file("agent", "a")
    val service = file("service", "bb")
    val payload = file("payload", "ccc")
    val view = file("view", "dddd")
    assertThat(computeArtifactDigests(agent, service, payload, view))
      .isEqualTo(computeArtifactDigests(agent, service, payload, view, composeInspectorOverrideJar = null))
  }

  @Test
  fun digest_withComposeOverride_matchesKnownVector() {
    // The override jar contributes as a fifth file with the same length-plus-bytes framing.
    assertThat(digestOf("a", "bb", "ccc", "dddd", composeOverride = "eeeee")).isEqualTo("7e8104c20970")
  }

  @Test
  fun digest_composeOverrideChangesTheDigest() {
    val withoutOverride = digestOf("a", "b", "c", "d")
    // Even an empty override differs from no override: its length framing still enters the hash.
    assertThat(digestOf("a", "b", "c", "d", composeOverride = "")).isNotEqualTo(withoutOverride)
    assertThat(digestOf("a", "b", "c", "d", composeOverride = "X")).isNotEqualTo(withoutOverride)
    assertThat(digestOf("a", "b", "c", "d", composeOverride = "X")).isNotEqualTo(digestOf("a", "b", "c", "d", composeOverride = "Y"))
  }

  @Test
  fun digest_composeOverrideLeavesPerFileDigestsUnchanged() {
    val digests = digestsOf("", "abc", "", "abc", composeOverride = "anything")
    assertThat(digests.agentBinary).isEqualTo("e3b0c44298fc")
    assertThat(digests.serviceJar).isEqualTo("ba7816bf8f01")
    assertThat(digests.payloadJar).isEqualTo("e3b0c44298fc")
    assertThat(digests.viewInspectorJar).isEqualTo("ba7816bf8f01")
  }

  @Test
  fun digest_missingArtifactThrows() {
    val missing = tempFolder.root.toPath().resolve("does-not-exist")
    assertThrows(NoSuchFileException::class.java) {
      computeArtifactDigests(
        agentBinary = missing,
        serviceJar = file("service", ""),
        payloadJar = file("payload", ""),
        viewInspectorJar = file("view", ""),
      )
    }
  }

  @Test
  fun perFileDigests_matchTruncatedSha256Vectors() {
    // First 12 hex characters of the files' SHA-256: empty file and "abc".
    val digests = digestsOf("", "abc", "", "abc")
    assertThat(digests.agentBinary).isEqualTo("e3b0c44298fc")
    assertThat(digests.serviceJar).isEqualTo("ba7816bf8f01")
    assertThat(digests.payloadJar).isEqualTo("e3b0c44298fc")
    assertThat(digests.viewInspectorJar).isEqualTo("ba7816bf8f01")
  }

  @Test
  fun computeContentDigest_matchesKnownVectors() {
    assertThat(computeContentDigest(file("empty", ""))).isEqualTo("e3b0c44298fc")
    assertThat(computeContentDigest(file("abc", "abc"))).isEqualTo("ba7816bf8f01")
  }

  @Test
  fun computeContentDigest_missingFileThrows() {
    assertThrows(NoSuchFileException::class.java) { computeContentDigest(tempFolder.root.toPath().resolve("does-not-exist")) }
  }

  @Test
  fun contentDigestPattern_staysAnchoredForDottedAndExtensionlessNames() {
    // The sweep pattern substitutes a digest-shaped hex class for the digest, so it cannot match across artifact boundaries
    // (foo.<pattern>.jar never matches versions of foo.bar.jar) nor a push's own .tmp-suffixed temporary file.
    val hexSegment = "[0-9a-f]".repeat(12)
    assertThat(fileNameWithHash("foo.bar.jar", CONTENT_DIGEST_PATTERN)).isEqualTo("foo.bar.$hexSegment.jar")
    assertThat(fileNameWithHash("noextension", CONTENT_DIGEST_PATTERN)).isEqualTo("noextension.$hexSegment")
  }

  @Test
  fun fileNameWithHash_insertsDigestBeforeExtension() {
    assertThat(fileNameWithHash("lib_agent.so", "e3b0c44298fc")).isEqualTo("lib_agent.e3b0c44298fc.so")
    assertThat(fileNameWithHash("ui-android-1.10.6-inspector.jar", "ba7816bf8f01"))
      .isEqualTo("ui-android-1.10.6-inspector.ba7816bf8f01.jar")
    assertThat(fileNameWithHash("noextension", "e3b0c44298fc")).isEqualTo("noextension.e3b0c44298fc")
  }
}
