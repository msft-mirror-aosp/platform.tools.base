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
import com.android.tools.ui.inspector.RecordingLogger
import com.android.tools.ui.inspector.deploy.ArtifactDownloader
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val VERSION = "1.10.6"
private const val AAR_URL = "https://maven.google.com/androidx/compose/ui/ui-android/$VERSION/ui-android-$VERSION.aar"

class ComposeInspectorResolutionTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val logger = RecordingLogger()

  /** A downloader that fails the test: the case under test must not download. */
  private val noDownloads =
    object : ArtifactDownloader {
      override fun download(url: String, outputFile: File) {
        throw AssertionError("Unexpected download of $url")
      }
    }

  /** A downloader that answers every URL with [respond], which writes the response to the file or throws, and records the URLs. */
  private class FakeDownloader(private val respond: (File) -> Unit) : ArtifactDownloader {
    val urls = mutableListOf<String>()

    override fun download(url: String, outputFile: File) {
      urls += url
      respond(outputFile)
    }
  }

  @Test
  fun testResolve_overrideJar_isUsedWithoutLookingElsewhere() {
    val overrideJar = tempFolder.newFile("override.jar").toPath()
    val resolution = ComposeInspectorResolution(overrideJar, tempFolder.newFolder().toPath(), noDownloads)

    // Even a version that could not be looked up is fine: the override is used as is.
    val jar = resolution.jarResolver(logger)("../x")

    assertThat(jar).isEqualTo(overrideJar.toFile())
    assertThat(logger.messages).containsExactly(LogLevel.PROGRESS to "Using the Compose inspector $overrideJar")
  }

  @Test
  fun testResolve_cachedJar_isUsedWithoutDownloading() {
    val cacheDir = tempFolder.newFolder()
    val cachedJar = File(cacheDir, "androidx.compose.ui-ui-android-$VERSION-inspector.jar").apply { writeText("cached") }
    val resolution = ComposeInspectorResolution(overrideJar = null, cacheDir.toPath(), noDownloads)

    val jar = resolution.jarResolver(logger)(VERSION)

    assertThat(jar).isEqualTo(cachedJar)
    assertThat(logger.messages).containsExactly(LogLevel.PROGRESS to "Using the cached Compose inspector $cachedJar")
  }

  @Test
  fun testResolve_downloadsAndCachesTheJar() {
    val downloader = FakeDownloader { it.writeBytes(aarWithInspectorJar("downloaded")) }
    val cacheDir = tempFolder.newFolder()
    val resolution = ComposeInspectorResolution(overrideJar = null, cacheDir.toPath(), downloader)

    val jar = resolution.jarResolver(logger)(VERSION)

    assertThat(jar.readText()).isEqualTo("downloaded")
    assertThat(jar.parentFile).isEqualTo(cacheDir)
    assertThat(downloader.urls).containsExactly(AAR_URL)
    assertThat(logger.messages).containsExactly(LogLevel.PROGRESS to "Using the Compose inspector downloaded from $AAR_URL")
  }

  @Test
  fun testResolve_preKmpVersion_downloadsTheUiArtifact() {
    val downloader = FakeDownloader { it.writeBytes(aarWithInspectorJar("downloaded")) }
    val resolution = ComposeInspectorResolution(overrideJar = null, tempFolder.newFolder().toPath(), downloader)

    resolution.jarResolver(logger)("1.4.3")

    assertThat(downloader.urls).containsExactly("https://maven.google.com/androidx/compose/ui/ui/1.4.3/ui-1.4.3.aar")
  }

  @Test
  fun testResolve_failedDownload_failsWithTheDownloadFailure() {
    val cacheDir = tempFolder.newFolder()
    val resolution =
      ComposeInspectorResolution(overrideJar = null, cacheDir.toPath(), FakeDownloader { throw IOException("Simulated download failure") })

    val exception = assertThrows(IOException::class.java) { resolution.jarResolver(logger)(VERSION) }

    assertThat(exception).hasMessageThat().isEqualTo("Simulated download failure")
    assertThat(cacheDir.listFiles()).isEmpty()
  }

  @Test
  fun testResolve_aarWithoutInspectorJar_failsWithTheUrl() {
    val cacheDir = tempFolder.newFolder()
    val resolution =
      ComposeInspectorResolution(
        overrideJar = null,
        cacheDir.toPath(),
        FakeDownloader { it.writeBytes(zipOf("AndroidManifest.xml" to "<manifest/>".toByteArray())) },
      )

    val exception = assertThrows(IOException::class.java) { resolution.jarResolver(logger)(VERSION) }

    assertThat(exception).hasMessageThat().isEqualTo("inspector.jar not found in $AAR_URL")
    assertThat(cacheDir.listFiles()).isEmpty()
  }

  @Test
  fun testResolve_invalidVersion_isNotLookedUp() {
    val resolution = ComposeInspectorResolution(overrideJar = null, tempFolder.newFolder().toPath(), noDownloads)

    // "1.0\n" cannot come from the agent, which trims the version it reads, but nothing else stops it before the version becomes a path.
    for (version in listOf("../x", "1.0/2", "1.0\\2", "1.0 2", ".1", "-1", "1.0\n", "1.0\u0000")) {
      val exception = assertThrows("version=$version", IOException::class.java) { resolution.jarResolver(logger)(version) }
      assertWithMessage("version=$version")
        .that(exception)
        .hasMessageThat()
        .isEqualTo(
          "the version the app reports is not a valid Maven version: it must start with a letter or a digit, and contain only letters, " +
            "digits, '.', '_', '+' and '-'"
        )
    }
    val empty = assertThrows(IOException::class.java) { resolution.jarResolver(logger)("") }
    assertThat(empty).hasMessageThat().isEqualTo("the app reports an empty version")
  }

  @Test
  fun testResolve_acceptsTypicalVersions() {
    for (version in listOf("1.10.4", "1.11.0-alpha05", "1.5.0-beta01", "1.7.2+build_4")) {
      val downloader = FakeDownloader { it.writeBytes(aarWithInspectorJar("downloaded")) }
      val resolution = ComposeInspectorResolution(overrideJar = null, tempFolder.newFolder().toPath(), downloader)

      resolution.jarResolver(logger)(version)

      assertWithMessage("version=$version")
        .that(downloader.urls)
        .containsExactly("https://maven.google.com/androidx/compose/ui/ui-android/$version/ui-android-$version.aar")
    }
  }

  private fun aarWithInspectorJar(content: String) = aarWithInspectorJar(content.toByteArray())

  private fun aarWithInspectorJar(content: ByteArray) =
    zipOf("AndroidManifest.xml" to "<manifest/>".toByteArray(), "inspector.jar" to content)

  private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      for ((name, content) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content)
        zip.closeEntry()
      }
    }
    return bytes.toByteArray()
  }
}
