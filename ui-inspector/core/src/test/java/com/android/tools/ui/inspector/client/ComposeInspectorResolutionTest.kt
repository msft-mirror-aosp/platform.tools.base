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

import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.ui.inspector.LogLevel
import com.android.tools.ui.inspector.RecordingLogger
import com.android.tools.ui.inspector.deploy.ArtifactDownloader
import com.android.tools.ui.inspector.deploy.ArtifactHttpStatusException
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLHandshakeException
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
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
      override fun download(url: String, outputFile: Path) {
        throw AssertionError("Unexpected download of $url")
      }
    }

  /** A downloader that answers every URL with [respond], which writes the response to the file or throws, and records the URLs. */
  private class FakeDownloader(private val respond: (Path) -> Unit) : ArtifactDownloader {
    val urls = mutableListOf<String>()

    override fun download(url: String, outputFile: Path) {
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

    assertThat(jar).isEqualTo(overrideJar)
    assertThat(logger.messages).containsExactly(LogLevel.PROGRESS to "Using the Compose inspector $overrideJar")
  }

  @Test
  fun testResolve_cachedJar_isUsedWithoutDownloading() {
    val cacheDir = tempFolder.newFolder().toPath()
    val cachedJar = cacheDir.resolve("androidx.compose.ui-ui-android-$VERSION-inspector.jar").apply { writeText("cached") }
    val resolution = ComposeInspectorResolution(overrideJar = null, cacheDir, noDownloads)

    val jar = resolution.jarResolver(logger)(VERSION)

    assertThat(jar).isEqualTo(cachedJar)
    assertThat(logger.messages).containsExactly(LogLevel.PROGRESS to "Using the cached Compose inspector $cachedJar")
  }

  @Test
  fun testResolve_downloadsAndCachesTheJar() {
    val downloader = FakeDownloader { it.writeBytes(aarWithInspectorJar("downloaded")) }
    val cacheDir = tempFolder.newFolder().toPath()
    val resolution = ComposeInspectorResolution(overrideJar = null, cacheDir, downloader)

    val jar = resolution.jarResolver(logger)(VERSION)

    assertThat(jar.readText()).isEqualTo("downloaded")
    assertThat(jar.parent).isEqualTo(cacheDir)
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
  fun testResolve_failedDownload_explainsWhy() {
    // Each failure, with causes wrapped the way java.net.http and android-cli's downloader wrap them, and the reason it is reported with.
    val cases: List<Pair<(Path) -> Unit, String>> =
      listOf(
        { _: Path ->
          throw ArtifactHttpStatusException(404, AAR_URL)
        } to "not found (HTTP 404): this version is not on Google Maven",
        { _: Path ->
          throw ArtifactHttpStatusException(410, AAR_URL)
        } to "not found (HTTP 410): this version is not on Google Maven",
        { _: Path -> throw ArtifactHttpStatusException(401, AAR_URL) } to "authentication required (HTTP 401)",
        { _: Path -> throw ArtifactHttpStatusException(403, AAR_URL) } to "access denied (HTTP 403)",
        { _: Path -> throw ArtifactHttpStatusException(407, AAR_URL) } to "proxy authentication required (HTTP 407)",
        { _: Path -> throw ArtifactHttpStatusException(302, AAR_URL) } to "redirected (HTTP 302); a proxy may require signing in",
        { _: Path -> throw ArtifactHttpStatusException(503, AAR_URL) } to "server error (HTTP 503)",
        { _: Path -> throw ArtifactHttpStatusException(418, AAR_URL) } to "HTTP 418",
        { _: Path -> throw UnknownHostException("maven.google.com") } to "cannot connect: unknown host maven.google.com",
        { _: Path ->
          throw IOException("Failed to connect to $AAR_URL", ConnectException().initCause(UnresolvedAddressException()))
        } to "cannot connect: unknown host maven.google.com",
        { _: Path -> throw SocketTimeoutException("Read timed out") } to "timed out",
        { _: Path ->
          throw IOException(
            "Failed to connect to $AAR_URL",
            HttpConnectTimeoutException("HTTP connect timed out").initCause(ConnectException()),
          )
        } to "timed out",
        { _: Path ->
          throw IOException("Failed to connect to $AAR_URL", SSLHandshakeException("PKIX path building failed"))
        } to "TLS error (PKIX path building failed); a proxy may be intercepting HTTPS",
        { _: Path -> throw ConnectException("Connection refused") } to "cannot connect (Connection refused)",
        // A FileSystemException often has no reason, and its message is only the file.
        { _: Path -> throw AccessDeniedException("/tmp/x.aar") } to "download failed: AccessDeniedException (/tmp/x.aar)",
        { _: Path -> throw IOException("Failed to connect to $AAR_URL", ConnectException()) } to "cannot connect",
        { _: Path ->
          throw IOException("Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 407\"")
        } to "download failed: Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 407\"",
        { file: Path ->
          file.writeText("<html>Blocked by your network administrator</html>")
        } to "not an AAR: the response is not a zip archive (a proxy or firewall may have returned an HTML page)",
        { _: Path -> } to "not an AAR: the response is empty",
        { file: Path -> file.writeBytes(zipOf("AndroidManifest.xml" to "<manifest/>".toByteArray())) } to "the AAR has no inspector.jar",
      )
    for ((respond, reason) in cases) {
      val cacheDir = tempFolder.newFolder().toPath()
      val resolution = ComposeInspectorResolution(overrideJar = null, cacheDir, FakeDownloader(respond))

      val exception = assertThrows(reason, IOException::class.java) { resolution.jarResolver(logger)(VERSION) }

      assertWithMessage(reason).that(exception).hasMessageThat().isEqualTo("$AAR_URL: $reason")
      assertWithMessage(reason).that(cacheDir.listDirectoryEntries()).isEmpty()
    }
  }

  @Test
  fun testResolve_truncatedAar_isReportedAsCorrupt() {
    val aar = aarWithInspectorJar(Random(0).nextBytes(64 * 1024))
    val resolution =
      ComposeInspectorResolution(
        overrideJar = null,
        tempFolder.newFolder().toPath(),
        FakeDownloader { it.writeBytes(aar.copyOf(aar.size / 2)) },
      )

    val exception = assertThrows(IOException::class.java) { resolution.jarResolver(logger)(VERSION) }

    assertThat(exception).hasMessageThat().startsWith("$AAR_URL: the AAR is corrupt or truncated (")
  }

  @Test
  fun testResolve_cacheThatCannotBeWritten_failsWithTheCacheDirectory() {
    // A file where the cache directory should be: extracting the jar there fails, which says nothing about the download.
    val cacheDir = tempFolder.newFile("not-a-directory").toPath()
    val resolution = ComposeInspectorResolution(overrideJar = null, cacheDir, FakeDownloader { it.writeBytes(aarWithInspectorJar("x")) })

    val exception = assertThrows(IOException::class.java) { resolution.jarResolver(logger)(VERSION) }

    assertThat(exception).hasMessageThat().startsWith("Could not extract inspector.jar to $cacheDir: ")
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
