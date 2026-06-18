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
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.fail
import org.junit.Test

class MavenArtifactResolverTest {

  /** A fake downloader that creates a valid ZIP file representing the AAR, containing "inspector.jar". */
  private class FakeArtifactDownloader(private val jarContent: String) : ArtifactDownloader {
    var recordedUrl: String? = null

    override fun download(url: String, outputFile: File) {
      recordedUrl = url
      ZipOutputStream(outputFile.outputStream()).use { zos ->
        zos.putNextEntry(ZipEntry("inspector.jar"))
        zos.write(jarContent.toByteArray())
        zos.closeEntry()
      }
    }
  }

  @Test
  fun testResolve_extractsInspectorJarSuccessfully() {
    val fakeDownloader = FakeArtifactDownloader("fake pre-compiled dex classes")
    val resolver = MavenArtifactResolver(fakeDownloader)

    val jarFile = resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4")

    assertThat(jarFile.exists()).isTrue()
    assertThat(jarFile.readText()).isEqualTo("fake pre-compiled dex classes")
    assertThat(fakeDownloader.recordedUrl).isEqualTo("https://maven.google.com/androidx/compose/ui/ui-android/1.5.4/ui-android-1.5.4.aar")
  }

  @Test
  fun testResolve_throwsIfInspectorJarMissing() {
    val emptyDownloader =
      object : ArtifactDownloader {
        override fun download(url: String, outputFile: File) {
          // Create an empty zip archive with NO "inspector.jar"
          ZipOutputStream(outputFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write("<manifest/>".toByteArray())
            zos.closeEntry()
          }
        }
      }
    val resolver = MavenArtifactResolver(emptyDownloader)

    try {
      resolver.resolve("androidx.compose.ui", "ui", "1.5.4")
      fail("Expected IllegalStateException was not thrown")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("inspector.jar not found in ui-1.5.4.aar")
    }
  }
}
