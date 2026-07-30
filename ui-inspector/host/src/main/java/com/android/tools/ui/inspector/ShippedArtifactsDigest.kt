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

/** Number of lowercase-hex characters kept as the shipped-artifact digest. */
private const val DIGEST_HEX_LENGTH = 12

/** Read-buffer size for streaming artifact files into the digest. */
private const val DIGEST_BUFFER_SIZE = 64 * 1024

/**
 * Fingerprints the artifacts the host ships to the device for an inspection session.
 *
 * The fingerprint is [DIGEST_HEX_LENGTH] lowercase-hex characters of the SHA-256 over each file's length (big-endian 64-bit) followed by
 * its bytes, in parameter order. The length framing makes file boundaries part of the hash input, so byte-identical artifact sets produce
 * equal digests.
 */
internal fun shippedArtifactsDigest(agentBinary: Path, serviceJar: Path, payloadJar: Path, viewInspectorJar: Path): String {
  val sha256 = MessageDigest.getInstance("SHA-256")
  val buffer = ByteArray(DIGEST_BUFFER_SIZE)
  for (file in listOf(agentBinary, serviceJar, payloadJar, viewInspectorJar)) {
    sha256.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(Files.size(file)).array())
    Files.newInputStream(file).use { input ->
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        sha256.update(buffer, 0, read)
      }
    }
  }
  return sha256.digest().joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }.substring(0, DIGEST_HEX_LENGTH)
}
