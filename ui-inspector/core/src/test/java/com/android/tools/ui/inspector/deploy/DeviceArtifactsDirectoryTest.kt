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

package com.android.tools.ui.inspector.deploy

import com.android.testutils.TestUtils
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.isRegularFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Checks the paths of [DeviceArtifactsDirectory], and that device_artifacts_pkg_files packages the files at those paths. */
class DeviceArtifactsDirectoryTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val zipPath: Path = TestUtils.resolveWorkspacePath("tools/base/ui-inspector/core/device_artifacts.zip")

  @Test
  fun testRelativeDirectoryGivesAbsolutePaths() {
    // A relative path would be looked up in the classpath resources when the file is missing, instead of failing as missing.
    val deviceArtifacts = DeviceArtifactsDirectory(Paths.get("relative"))

    val paths =
      listOf(
        deviceArtifacts.agentPath("arm64-v8a"),
        deviceArtifacts.libraryDexPath,
        deviceArtifacts.payloadJarPath,
        deviceArtifacts.viewInspectorJarPath,
      )

    for (path in paths) {
      assertWithMessage(path.toString()).that(path.isAbsolute).isTrue()
    }
  }

  // The tests below check that device_artifacts_pkg_files, which tools ship in a zip instead of bundling the files, is laid out as
  // DeviceArtifactsDirectory reads it.

  @Test
  fun testZipHasExactlyTheArtifacts() {
    val entries = ZipFile(zipPath.toFile()).use { zip -> zip.entries().toList().filterNot { it.isDirectory }.map { it.name } }

    assertThat(entries)
      .containsExactlyElementsIn(
        listOf("libarttooling.jar", "lib_ui_inspector_payload.jar", "view-inspector.jar") +
          SUPPORTED_ABIS.map { "$it/libarttooling_agent.so" }
      )
  }

  @Test
  fun testExtractedZipHasEveryArtifactWhereDeviceArtifactsDirectoryReadsIt() {
    val dir = tempFolder.newFolder().toPath()
    ZipFile(zipPath.toFile()).use { zip ->
      for (entry in zip.entries().toList().filterNot { it.isDirectory }) {
        val target = dir.resolve(entry.name)
        Files.createDirectories(target.parent)
        zip.getInputStream(entry).use { Files.copy(it, target) }
      }
    }
    val deviceArtifacts = DeviceArtifactsDirectory(dir)

    for (path in listOf(deviceArtifacts.libraryDexPath, deviceArtifacts.payloadJarPath, deviceArtifacts.viewInspectorJarPath)) {
      assertThat(path.isRegularFile()).isTrue()
      assertThat(Files.size(path)).isGreaterThan(0L)
    }
    for (abi in SUPPORTED_ABIS) {
      assertThat(deviceArtifacts.agentPath(abi).isRegularFile()).isTrue()
      assertThat(Files.size(deviceArtifacts.agentPath(abi))).isGreaterThan(0L)
    }
  }
}
