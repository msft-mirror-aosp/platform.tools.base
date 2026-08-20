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

import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.RemoteFileMode
import com.android.adblib.shellAsText
import com.android.adblib.syncSend
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * A globally writable directory on the device used as a staging area for pushed files before they are copied into the app's private
 * directory or loaded directly. Every file in it is named by a hash of its own content (see [fileNameWithHash]), so runs shipping different
 * content write different paths.
 */
private const val DEVICE_STAGING_DIR = "/data/local/tmp/ui-inspector"

/**
 * The value we expect `stat %f` to print for a file we pushed. `stat %f` reports a file's type and permissions as a single hex number; 8124
 * decodes to "regular file with 0444 permissions".
 */
private const val STAGED_FILE_RAW_MODE_HEX = "8124"

/** Validates that staged file names only contain characters that are safe in shell commands. */
private val SHELL_SAFE_FILE_NAME_REGEX = Regex("^[a-zA-Z0-9._-]+$")

/**
 * Matches a line of `stat -c '%f %n'` output: one line per file, its type and permissions as a single hex number, a space, and its path.
 * e.g. `8124 /data/local/tmp/ui-inspector/lib_ui_inspector_payload.e3b0c44298fc.jar`.
 */
private val STAT_LINE_REGEX = Regex("^([0-9a-f]+) (.+)$")

/** A local file to make available on the device under its digest-carrying staged name (see [fileNameWithHash]). */
internal data class ArtifactToStage(val localPath: Path, val fileName: String, val digest: String)

/**
 * Stages files in [DEVICE_STAGING_DIR]. File names contain the file content hash, allowing to avoid pushing files to the device that are
 * already staged.
 */
internal class ArtifactStaging(
  private val adbSession: AdbSession,
  private val deviceSelector: DeviceSelector,
  private val tempFileSuffixGenerator: () -> String,
) {

  /**
   * Makes each artifact available at the staging path and returns those paths in input order. Artifacts whose path already holds a
   * correctly-staged file are skipped; the rest are pushed concurrently.
   */
  suspend fun stage(artifacts: List<ArtifactToStage>): List<String> = coroutineScope {
    for (artifact in artifacts) {
      // A regex to make sure file names are valid. A name containing spaces or characters the shell treats
      // specially (quotes, semicolons, wildcards, ...) would break those commands. So only names built from letters, digits, dots,
      // underscores, and hyphens are accepted.
      require(SHELL_SAFE_FILE_NAME_REGEX.matches(artifact.fileName)) { "Invalid staged file name: ${artifact.fileName}" }
    }
    val remotePaths = artifacts.map { "$DEVICE_STAGING_DIR/${fileNameWithHash(it.fileName, it.digest)}" }
    val stagedModes = queryStagedFileModes(remotePaths)

    artifacts
      .zip(remotePaths)
      .filterNot { (_, remotePath) -> stagedModes?.get(remotePath) == STAGED_FILE_RAW_MODE_HEX }
      .map { (artifact, remotePath) -> async { pushFileAtomically(artifact, remotePath) } }
      .awaitAll()

    remotePaths
  }

  /**
   * Queries the raw `stat %f` mode of each remote path in one shell invocation. The command's exit code is deliberately ignored: missing
   * files fail the command while present files still produce records, which is exactly the partial answer wanted on a first run.
   */
  private suspend fun queryStagedFileModes(remotePaths: List<String>): Map<String, String>? {
    val quotedPaths = remotePaths.joinToString(separator = " ") { "'$it'" }
    val cmd = "stat -c '%f %n' $quotedPaths 2>/dev/null"
    val stdout =
      try {
        adbSession.deviceServices.shellAsText(deviceSelector, cmd).stdout
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        return null
      }
    return parseStagedFileModes(stdout, remotePaths)
  }

  /**
   * Pushes a file to its device staging path using an atomic rename to prevent concurrent read/write corruption, sweeping other staged
   * versions of the same artifact.
   */
  private suspend fun pushFileAtomically(artifact: ArtifactToStage, remotePath: String) {
    // App needs read permission to copy the file from /data/local/tmp.
    // Setting read-only permissions (444) directly during syncSend also satisfies ART W^X read-only
    // dex file requirements on API 34+ without needing an extra chmod shell round trip.
    val permissions =
      RemoteFileMode.fromPosixPermissions(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ)
    val tempRemotePath = "$remotePath.${tempFileSuffixGenerator()}"

    // Built like a staged name, but with a wildcard standing in where the hash would go: the result is a shell pattern matching every
    // staged version of this artifact rather than one file.
    val staleVersionsPattern = "$DEVICE_STAGING_DIR/${fileNameWithHash(artifact.fileName, CONTENT_DIGEST_PATTERN)}"

    var moveSuccessful = false
    try {
      adbSession.deviceServices.syncSend(deviceSelector, artifact.localPath, tempRemotePath, permissions)
      // Push to unique temporary files and atomically move to the final file name to prevent concurrency conflicts.
      adbSession.deviceServices.shellAsTextOrThrow(
        deviceSelector,
        "rm -f $staleVersionsPattern && test ! -d '$remotePath' && mv -f '$tempRemotePath' '$remotePath'",
      )
      moveSuccessful = true
    } finally {
      if (!moveSuccessful) {
        withContext(NonCancellable) {
          try {
            // Clean-up
            adbSession.deviceServices.shellAsText(deviceSelector, "rm -f '$tempRemotePath'")
          } catch (_: Exception) {}
        }
      }
    }
  }
}

/** Builds a file's staged name by inserting its content hash before the extension: `base.<hash>.ext`. */
internal fun fileNameWithHash(fileName: String, hash: String): String {
  val extensionStart = fileName.lastIndexOf('.')
  if (extensionStart < 0) {
    return "$fileName.$hash"
  }
  return "${fileName.substring(0, extensionStart)}.$hash${fileName.substring(extensionStart)}"
}

/**
 * Parses the staging probe's `stat` transcript into raw modes per path. Returns null when the transcript is unusable — any malformed line,
 * a path outside [remotePaths], or a duplicate record — because an ambiguous transcript cannot justify skipping a push. A path without a
 * record is simply absent from the map: absence is the expected outcome for files not yet staged, while stderr content never participates
 * in the decision.
 */
internal fun parseStagedFileModes(stdout: String, remotePaths: List<String>): Map<String, String>? {
  val requested = remotePaths.toSet()
  val modes = HashMap<String, String>()
  for (line in stdout.lineSequence()) {
    if (line.isBlank()) continue
    val match = STAT_LINE_REGEX.matchEntire(line) ?: return null
    val (mode, path) = match.destructured
    if (path !in requested || modes.put(path, mode) != null) return null
  }
  return modes
}
