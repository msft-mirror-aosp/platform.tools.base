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

import com.android.tools.ui.inspector.deploy.ArtifactDownloader
import java.io.File
import java.nio.file.Path

/**
 * How the Compose inspector jar for the app's Compose version is found.
 *
 * @param overrideJar A local Compose inspector jar to use instead of resolving one, or null.
 * @param cacheDir Where the Compose inspector jars downloaded from Maven are kept.
 * @param downloader Downloads the AARs the Compose inspector jars are extracted from.
 */
internal class ComposeInspectorResolution(val overrideJar: Path?, val cacheDir: Path, val downloader: ArtifactDownloader) {

  /**
   * Returns the function that resolves the jar for a Compose version: [overrideJar] when set, else the jar from Google Maven, kept in
   * [cacheDir].
   */
  fun jarResolver(): (version: String) -> File =
    overrideJar?.let { path -> { _: String -> path.toFile() } } ?: mavenComposeInspectorResolver(cacheDir, downloader)
}
