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

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

/** Number of lowercase-hex characters kept as a digest, for the combined artifact fingerprint and per-file content digests alike. */
private const val DIGEST_HEX_LENGTH = 12

/** Read-buffer size for streaming artifact files into a digest. */
private const val DIGEST_BUFFER_SIZE = 64 * 1024

/** Shell wildcard used to match exactly one content-digest segment: one `[0-9a-f]` character class per digest character. */
internal val CONTENT_DIGEST_PATTERN: String = "[0-9a-f]".repeat(DIGEST_HEX_LENGTH)

/**
 * The digests of the artifacts the host ships to the device for an inspection session. All values are [DIGEST_HEX_LENGTH] lowercase-hex
 * characters.
 *
 * @property combined Fingerprint of the whole set: the SHA-256 over each file's length (big-endian 64-bit) followed by its bytes, in
 *   parameter order of [computeArtifactDigests]. The length framing makes file boundaries part of the hash input, so byte-identical
 *   artifact sets produce equal digests.
 * @property agentBinary The agent binary's own content digest (see [computeContentDigest]).
 * @property serviceJar The service jar's own content digest (see [computeContentDigest]).
 * @property payloadJar The payload jar's own content digest (see [computeContentDigest]).
 * @property viewInspectorJar The view inspector jar's own content digest (see [computeContentDigest]).
 */
internal data class ShippedArtifactsDigests(
  val combined: String,
  val agentBinary: String,
  val serviceJar: String,
  val payloadJar: String,
  val viewInspectorJar: String,
)

/** Computes [ShippedArtifactsDigests] for the given artifacts, reading each file once. */
internal fun computeArtifactDigests(
  agentBinary: Path,
  serviceJar: Path,
  payloadJar: Path,
  viewInspectorJar: Path,
): ShippedArtifactsDigests {
  val combined = MessageDigest.getInstance("SHA-256")
  val perFile = ArrayList<String>(4)
  val buffer = ByteArray(DIGEST_BUFFER_SIZE)
  for (file in listOf(agentBinary, serviceJar, payloadJar, viewInspectorJar)) {
    val single = MessageDigest.getInstance("SHA-256")
    combined.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(Files.size(file)).array())
    Files.newInputStream(file).use { input ->
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        combined.update(buffer, 0, read)
        single.update(buffer, 0, read)
      }
    }
    perFile.add(single.digest().toTruncatedHex())
  }
  return ShippedArtifactsDigests(
    combined = combined.digest().toTruncatedHex(),
    agentBinary = perFile[0],
    serviceJar = perFile[1],
    payloadJar = perFile[2],
    viewInspectorJar = perFile[3],
  )
}

/** Returns a file's content digest: [DIGEST_HEX_LENGTH] lowercase-hex characters of the SHA-256 of its contents. */
internal fun computeContentDigest(path: Path): String {
  val sha256 = MessageDigest.getInstance("SHA-256")
  val buffer = ByteArray(DIGEST_BUFFER_SIZE)
  Files.newInputStream(path).use { input ->
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      sha256.update(buffer, 0, read)
    }
  }
  return sha256.digest().toTruncatedHex()
}

private fun ByteArray.toTruncatedHex(): String =
  joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }.substring(0, DIGEST_HEX_LENGTH)
