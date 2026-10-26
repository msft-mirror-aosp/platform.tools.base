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

package com.android.tools.ui.inspector.client

import com.android.tools.ui.inspector.LogLevel
import com.android.tools.ui.inspector.Logger
import com.android.tools.ui.inspector.deploy.ArtifactDownloader
import com.android.tools.ui.inspector.deploy.JarLookup
import com.android.tools.ui.inspector.deploy.MavenArtifactResolver
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * The versions [ComposeInspectorResolution] looks up. The version comes from the inspected app (the Compose version file the app ships), so
 * it can be any string, and it becomes part of the name of the cached jar and of the download URL. A '/' or '\' would make the cached jar a
 * path outside the cache directory (e.g. "../../x") and change which URL is downloaded; spaces and control characters don't belong in a
 * URL. Released Maven versions, e.g. "1.10.4", "1.11.0-alpha05" or "1.7.0-SNAPSHOT", only use the characters allowed here.
 */
private val VALID_VERSION = Regex("[0-9A-Za-z][0-9A-Za-z._+-]*")

/**
 * How the Compose inspector jar for the app's Compose version is found.
 *
 * @param overrideJar A local Compose inspector jar to use instead of resolving one, or null.
 * @param cacheDir Where the Compose inspector jars downloaded from Maven are kept.
 * @param downloader Downloads the AARs the Compose inspector jars are extracted from.
 */
internal class ComposeInspectorResolution(val overrideJar: Path?, val cacheDir: Path, val downloader: ArtifactDownloader) {

  /**
   * Returns the function that resolves the jar for a Compose version, logging where it comes from to [logger]: [overrideJar] when set, else
   * the jar kept in [cacheDir], else the jar from Google Maven. The function throws an [IOException] when there is none.
   */
  fun jarResolver(logger: Logger): (version: String) -> File = { version -> resolve(version, logger) }

  /**
   * Returns the Compose inspector jar for the Compose [version] the app reports, and logs where it comes from to [logger]: [overrideJar]
   * when set, else the jar kept in [cacheDir], else the jar downloaded from Google Maven, which is then kept in [cacheDir].
   *
   * @throws IOException when [version] is not a valid Maven version, when the jar cannot be found (the message says why), or when it cannot
   *   be written to [cacheDir].
   */
  private fun resolve(version: String, logger: Logger): File {
    if (overrideJar != null) {
      logger.log(LogLevel.PROGRESS, "Using the Compose inspector $overrideJar")
      return overrideJar.toFile()
    }
    val versionProblem = invalidVersionProblem(version)
    if (versionProblem != null) {
      throw IOException(versionProblem)
    }
    val artifactId = getComposeArtifactId(version)

    val maven = MavenArtifactResolver(downloader, cacheDir.toFile())
    val cachedJar = maven.cachedJar(COMPOSE_UI_GROUP_ID, artifactId, version)
    if (cachedJar != null) {
      logger.log(LogLevel.PROGRESS, "Using the cached Compose inspector $cachedJar")
      return cachedJar
    }
    when (val lookup = maven.download(COMPOSE_UI_GROUP_ID, artifactId, version)) {
      is JarLookup.Found -> {
        logger.log(LogLevel.PROGRESS, "Using the Compose inspector downloaded from ${lookup.location}")
        return lookup.jar
      }
      is JarLookup.NotFound -> throw IOException("${lookup.location}: ${lookup.reason}")
    }
  }
}

/** Returns why [version] cannot be used to find the jar, or null when it can. */
private fun invalidVersionProblem(version: String): String? =
  when {
    version.isEmpty() -> "the app reports an empty version"
    !VALID_VERSION.matches(version) ->
      "the version the app reports is not a valid Maven version: it must start with a letter or a digit, and contain only letters, " +
        "digits, '.', '_', '+' and '-'"
    else -> null
  }
