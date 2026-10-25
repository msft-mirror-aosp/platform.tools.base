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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MavenArtifactResolverTest {

  @get:Rule val tempFolder = TemporaryFolder()

  /** A fake downloader that creates a valid ZIP file representing the AAR, containing "inspector.jar". */
  private class FakeArtifactDownloader(private val jarContent: String) : ArtifactDownloader {
    var recordedUrl: String? = null
    var downloadCalls = 0

    override fun download(url: String, outputFile: File) {
      recordedUrl = url
      downloadCalls++
      ZipOutputStream(outputFile.outputStream()).use { zos ->
        zos.putNextEntry(ZipEntry("inspector.jar"))
        zos.write(jarContent.toByteArray())
        zos.closeEntry()
      }
    }
  }

  /** A downloader that writes the response with [writeResponse] and records the file it wrote. */
  private class RecordingDownloader(private val writeResponse: (File) -> Unit) : ArtifactDownloader {
    var outputFile: File? = null

    override fun download(url: String, outputFile: File) {
      this.outputFile = outputFile
      writeResponse(outputFile)
    }
  }

  @Test(timeout = 5_000)
  fun testHttpArtifactDownloader_timesOutOnStalledServer() {
    // A server that accepts the connection but never writes a byte: without a read timeout the download hangs forever.
    ServerSocket(0).use { server ->
      val downloader = HttpArtifactDownloader(connectTimeout = 5.seconds, readTimeout = 200.milliseconds)
      val output = tempFolder.newFile("stalled.aar")

      assertThrows(SocketTimeoutException::class.java) { downloader.download("http://127.0.0.1:${server.localPort}/x.aar", output) }
    }
  }

  @Test
  fun testResolve_extractsInspectorJarSuccessfully() {
    val fakeDownloader = FakeArtifactDownloader("fake pre-compiled dex classes")
    val resolver = MavenArtifactResolver(fakeDownloader, tempFolder.newFolder())

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
    val resolver = MavenArtifactResolver(emptyDownloader, tempFolder.newFolder())

    val exception = assertThrows(IOException::class.java) { resolver.resolve("androidx.compose.ui", "ui", "1.5.4") }
    assertThat(exception)
      .hasMessageThat()
      .isEqualTo("inspector.jar not found in https://maven.google.com/androidx/compose/ui/ui/1.5.4/ui-1.5.4.aar")
  }

  @Test
  fun testResolve_nonZipResponse_throwsAndCachesNothing() {
    val downloader = RecordingDownloader { outputFile -> outputFile.writeText("<html>Blocked by your network administrator</html>") }
    val cacheDir = tempFolder.newFolder()
    val resolver = MavenArtifactResolver(downloader, cacheDir)

    val exception = assertThrows(IOException::class.java) { resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4") }

    assertThat(exception)
      .hasMessageThat()
      .isEqualTo(
        "https://maven.google.com/androidx/compose/ui/ui-android/1.5.4/ui-android-1.5.4.aar is not an AAR: the response is not a zip " +
          "archive (a proxy or firewall may have returned an HTML page)"
      )
    assertThat(cacheDir.listFiles()).isEmpty()
    assertThat(downloader.outputFile!!.exists()).isFalse()
  }

  @Test
  fun testResolve_emptyResponse_throwsAndCachesNothing() {
    val downloader = RecordingDownloader { _ -> }
    val cacheDir = tempFolder.newFolder()
    val resolver = MavenArtifactResolver(downloader, cacheDir)

    val exception = assertThrows(IOException::class.java) { resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4") }

    assertThat(exception)
      .hasMessageThat()
      .isEqualTo("https://maven.google.com/androidx/compose/ui/ui-android/1.5.4/ui-android-1.5.4.aar is not an AAR: the response is empty")
    assertThat(cacheDir.listFiles()).isEmpty()
    assertThat(downloader.outputFile!!.exists()).isFalse()
  }

  @Test
  fun testResolve_invalidVersion_rejectedBeforeDownload() {
    val failingDownloader =
      object : ArtifactDownloader {
        override fun download(url: String, outputFile: File) {
          throw AssertionError("An invalid version must not be downloaded: $url")
        }
      }
    val cacheDir = tempFolder.newFolder()
    val resolver = MavenArtifactResolver(failingDownloader, cacheDir)

    // "1.0\n" cannot come from the agent, which trims the version it reads, but nothing else stops it before the version becomes a path.
    for (version in listOf("../x", "1.0/2", "1.0\\2", "1.0 2", ".1", "-1", "1.0\n", "1.0\u0000")) {
      val exception =
        assertThrows("version=$version", IOException::class.java) { resolver.resolve("androidx.compose.ui", "ui-android", version) }
      assertWithMessage("version=$version")
        .that(exception)
        .hasMessageThat()
        .startsWith("The version the app reports is not a valid Maven version: ")
    }
    val emptyVersionException = assertThrows(IOException::class.java) { resolver.resolve("androidx.compose.ui", "ui-android", "") }
    assertThat(emptyVersionException).hasMessageThat().isEqualTo("The app reports an empty version")
    assertThat(cacheDir.listFiles()).isEmpty()
  }

  @Test
  fun testResolve_acceptsTypicalVersions() {
    val versions = listOf("1.10.4", "1.11.0-alpha05", "1.0.0-beta01", "1.7.0-SNAPSHOT", "1.2.3+build_4")
    for (version in versions) {
      val fakeDownloader = FakeArtifactDownloader("fake pre-compiled dex classes")
      val resolver = MavenArtifactResolver(fakeDownloader, tempFolder.newFolder())

      val jarFile = resolver.resolve("androidx.compose.ui", "ui-android", version)

      assertWithMessage("version=$version").that(jarFile.readText()).isEqualTo("fake pre-compiled dex classes")
      assertWithMessage("version=$version")
        .that(fakeDownloader.recordedUrl)
        .isEqualTo("https://maven.google.com/androidx/compose/ui/ui-android/$version/ui-android-$version.aar")
    }
  }

  @Test
  fun testResolve_servesFromCacheOnSecondCall() {
    val fakeDownloader = FakeArtifactDownloader("fake pre-compiled dex classes")
    val resolver = MavenArtifactResolver(fakeDownloader, tempFolder.newFolder())

    val jarFile1 = resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4")
    val jarFile2 = resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4")

    assertThat(jarFile1).isEqualTo(jarFile2)
    assertThat(jarFile2.exists()).isTrue()
    assertThat(jarFile2.readText()).isEqualTo("fake pre-compiled dex classes")
    assertThat(fakeDownloader.downloadCalls).isEqualTo(1)
  }

  @Test
  fun testResolve_concurrentResolutions() {
    val jarContent = "fake pre-compiled dex classes"
    val threadCount = 10
    val barrier = CyclicBarrier(threadCount)
    val fakeDownloader =
      object : ArtifactDownloader {
        override fun download(url: String, outputFile: File) {
          // Synchronize all threads here so they run the download and resolution concurrently
          try {
            barrier.await(10, TimeUnit.SECONDS)
          } catch (e: Exception) {
            throw RuntimeException(e)
          }
          ZipOutputStream(outputFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("inspector.jar"))
            zos.write(jarContent.toByteArray())
            zos.closeEntry()
          }
        }
      }
    val resolver = MavenArtifactResolver(fakeDownloader, tempFolder.newFolder())

    val executor = Executors.newFixedThreadPool(threadCount)
    val futures = (1..threadCount).map { executor.submit(Callable { resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4") }) }

    futures.forEach { future ->
      val jarFile = future.get()
      assertThat(jarFile.exists()).isTrue()
      assertThat(jarFile.readText()).isEqualTo(jarContent)
    }
    executor.shutdown()
  }

  @Test
  fun testResolve_ignoresMoveExceptionIfDestinationExists() {
    val fakeDownloader = FakeArtifactDownloader("fake jar content")
    val cacheDir = tempFolder.newFolder()

    var moveAttempted = false
    val resolver =
      MavenArtifactResolver(
        fakeDownloader,
        cacheDir,
        fileMover = { _, destination ->
          moveAttempted = true
          // Simulate Thread A creating the destination file concurrently
          destination.writeText("concurrently written correct jar content")
          throw IOException("Simulated file system lock / access denied error")
        },
      )

    // Call resolve. Since the destination file is concurrently written and exists,
    // this should return successfully despite the move exception.
    val jarFile = resolver.resolve("androidx.compose.ui", "ui-android", "1.5.4")

    assertThat(moveAttempted).isTrue()
    assertThat(jarFile.exists()).isTrue()
    assertThat(jarFile.isFile).isTrue()
    assertThat(jarFile.readText()).isEqualTo("concurrently written correct jar content")
  }
}
