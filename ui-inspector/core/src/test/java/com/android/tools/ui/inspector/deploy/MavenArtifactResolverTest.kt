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
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

private const val AAR_URL = "https://maven.google.com/androidx/compose/ui/ui-android/1.5.4/ui-android-1.5.4.aar"

class MavenArtifactResolverTest {

  @get:Rule val tempFolder = TemporaryFolder()

  /** A fake downloader that writes an AAR containing an "inspector.jar" with [jarContent], and records what it was asked. */
  private class FakeArtifactDownloader(private val jarContent: String) : ArtifactDownloader {
    var recordedUrl: String? = null
    var outputFile: File? = null
    var downloadCalls = 0

    override fun download(url: String, outputFile: File) {
      recordedUrl = url
      this.outputFile = outputFile
      downloadCalls++
      writeAar(outputFile, jarContent)
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
  fun testHttpArtifactDownloader_writesTheResponseBody() {
    withServer(200, "aar bytes") { url ->
      val output = tempFolder.newFile("downloaded.aar")

      HttpArtifactDownloader().download(url, output)

      assertThat(output.readText()).isEqualTo("aar bytes")
    }
  }

  @Test
  fun testHttpArtifactDownloader_reportsHttpErrorStatuses() {
    for (status in listOf(404, 403, 500)) {
      withServer(status, "error page") { url ->
        val exception =
          assertThrows(ArtifactHttpStatusException::class.java) { HttpArtifactDownloader().download(url, tempFolder.newFile()) }

        assertThat(exception.statusCode).isEqualTo(status)
        assertThat(exception).hasMessageThat().isEqualTo("HTTP $status for $url")
      }
    }
  }

  @Test
  fun testDownload_extractsInspectorJarIntoTheCache() {
    val fakeDownloader = FakeArtifactDownloader("fake pre-compiled dex classes")
    val cacheDir = tempFolder.newFolder()
    val resolver = MavenArtifactResolver(fakeDownloader, cacheDir)

    val lookup = resolver.download("androidx.compose.ui", "ui-android", "1.5.4")

    val expectedJar = File(cacheDir, "androidx.compose.ui-ui-android-1.5.4-inspector.jar")
    assertThat(lookup).isEqualTo(JarLookup.Found(expectedJar, AAR_URL))
    assertThat(expectedJar.readText()).isEqualTo("fake pre-compiled dex classes")
    assertThat(fakeDownloader.recordedUrl).isEqualTo(AAR_URL)
    // The downloaded AAR is a temporary file.
    assertThat(fakeDownloader.outputFile!!.exists()).isFalse()
  }

  @Test
  fun testDownload_failure_isNotFoundAndLeavesNoFiles() {
    val failingDownloader =
      object : ArtifactDownloader {
        var outputFile: File? = null

        override fun download(url: String, outputFile: File) {
          this.outputFile = outputFile
          outputFile.writeText("partial")
          throw ArtifactHttpStatusException(404, url)
        }
      }
    val cacheDir = tempFolder.newFolder()

    val lookup = MavenArtifactResolver(failingDownloader, cacheDir).download("androidx.compose.ui", "ui-android", "1.5.4")

    assertThat((lookup as JarLookup.NotFound).location).isEqualTo(AAR_URL)
    assertThat(cacheDir.listFiles()).isEmpty()
    assertThat(failingDownloader.outputFile!!.exists()).isFalse()
  }

  @Test
  fun testDownload_unknownHostWithUnsafeVersion_reportsTheHost() {
    // The resolver does not validate the version: its callers do. The failure must still be reported, not replaced by a URL parsing error.
    val unknownHostDownloader =
      object : ArtifactDownloader {
        override fun download(url: String, outputFile: File) {
          throw UnknownHostException("maven.google.com")
        }
      }

    val lookup = MavenArtifactResolver(unknownHostDownloader, tempFolder.newFolder()).download("androidx.compose.ui", "ui-android", "1.0 2")

    assertThat((lookup as JarLookup.NotFound).reason).isEqualTo("cannot connect: unknown host maven.google.com")
  }

  @Test
  fun testCachedJar_isNullUntilTheJarIsDownloaded() {
    val fakeDownloader = FakeArtifactDownloader("fake pre-compiled dex classes")
    val resolver = MavenArtifactResolver(fakeDownloader, tempFolder.newFolder())

    assertThat(resolver.cachedJar("androidx.compose.ui", "ui-android", "1.5.4")).isNull()
    val lookup = resolver.download("androidx.compose.ui", "ui-android", "1.5.4") as JarLookup.Found

    assertThat(resolver.cachedJar("androidx.compose.ui", "ui-android", "1.5.4")).isEqualTo(lookup.jar)
    assertThat(fakeDownloader.downloadCalls).isEqualTo(1)
  }

  @Test
  fun testDownload_concurrentDownloads() {
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
          writeAar(outputFile, jarContent)
        }
      }
    val resolver = MavenArtifactResolver(fakeDownloader, tempFolder.newFolder())

    val executor = Executors.newFixedThreadPool(threadCount)
    val futures = (1..threadCount).map { executor.submit(Callable { resolver.download("androidx.compose.ui", "ui-android", "1.5.4") }) }

    futures.forEach { future ->
      val jarFile = (future.get() as JarLookup.Found).jar
      assertThat(jarFile.exists()).isTrue()
      assertThat(jarFile.readText()).isEqualTo(jarContent)
    }
    executor.shutdown()
  }

  @Test
  fun testDownload_ignoresMoveExceptionIfDestinationExists() {
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

    // Since the destination file is concurrently written and exists, this should succeed despite the move exception.
    val jarFile = (resolver.download("androidx.compose.ui", "ui-android", "1.5.4") as JarLookup.Found).jar

    assertThat(moveAttempted).isTrue()
    assertThat(jarFile.isFile).isTrue()
    assertThat(jarFile.readText()).isEqualTo("concurrently written correct jar content")
  }

  /** Runs [block] with the URL of a local HTTP server that answers every request with [status] and [body]. */
  private fun withServer(status: Int, body: String, block: (url: String) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      block("http://127.0.0.1:${server.address.port}/x.aar")
    } finally {
      server.stop(0)
    }
  }
}

/** Writes an AAR containing an "inspector.jar" with [jarContent] to [file]. */
private fun writeAar(file: File, jarContent: String) {
  ZipOutputStream(file.outputStream()).use { zos ->
    zos.putNextEntry(ZipEntry("inspector.jar"))
    zos.write(jarContent.toByteArray())
    zos.closeEntry()
  }
}
