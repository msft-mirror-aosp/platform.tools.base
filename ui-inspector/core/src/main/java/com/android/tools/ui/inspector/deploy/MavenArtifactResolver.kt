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

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Downloads remote artifacts, e.g. the Compose inspector from Google Maven. */
interface ArtifactDownloader {

  /**
   * Downloads the artifact payload from the specified [url] and writes it to the [outputFile].
   *
   * @param url The URL of the remote artifact.
   * @param outputFile The local file where the downloaded bytes will be written.
   * @throws IOException when the artifact cannot be downloaded. Other exceptions are reported as defects, not as download failures.
   */
  @Throws(IOException::class) fun download(url: String, outputFile: File)
}

/** Thrown by [HttpArtifactDownloader] when the server answers [url] with the HTTP error status [statusCode]. */
internal class ArtifactHttpStatusException(val statusCode: Int, url: String) : IOException("HTTP $statusCode for $url")

/** Default connect timeout for artifact downloads. */
private val DEFAULT_CONNECT_TIMEOUT = 30.seconds

/** Default read timeout for artifact downloads. Bounds each read stall, not the total transfer time. */
private val DEFAULT_READ_TIMEOUT = 30.seconds

/**
 * Default implementation of [ArtifactDownloader] that downloads files over standard HTTP. Connect and read timeouts prevent an unresponsive
 * server from hanging the CLI forever; a read timeout bounds each stall rather than the total transfer, so large artifacts on slow
 * connections still complete. An HTTP error status is reported with an [ArtifactHttpStatusException].
 */
internal class HttpArtifactDownloader(
  private val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
  private val readTimeout: Duration = DEFAULT_READ_TIMEOUT,
) : ArtifactDownloader {
  override fun download(url: String, outputFile: File) {
    val connection = URL(url).openConnection()
    connection.connectTimeout = connectTimeout.inWholeMilliseconds.toInt()
    connection.readTimeout = readTimeout.inWholeMilliseconds.toInt()
    try {
      if (connection is HttpURLConnection) {
        val statusCode = connection.responseCode
        if (statusCode == -1) {
          throw IOException("the server did not answer with an HTTP response")
        }
        if (statusCode !in 200..299) {
          throw ArtifactHttpStatusException(statusCode, url)
        }
      }
      connection.getInputStream().use { input -> outputFile.outputStream().use { output -> input.copyTo(output) } }
    } finally {
      (connection as? HttpURLConnection)?.disconnect()
    }
  }
}

/**
 * Resolves and downloads library artifacts from Google's Maven repository and extracts their nested payload jars.
 *
 * @param downloader The downloader used to download remote AAR artifacts.
 * @param cacheDir The local directory where resolved payload JARs are cached.
 * @param fileMover The function used to move the temporary extracted JAR to its final cached destination path.
 */
internal class MavenArtifactResolver(
  private val downloader: ArtifactDownloader = HttpArtifactDownloader(),
  private val cacheDir: File,
  private val fileMover: (File, File) -> Unit = ::moveFileReplacing,
) {

  companion object {
    // TODO: support downloading from other sources too, for example a company's own Maven repository.
    private const val GOOGLE_MAVEN_BASE_URL = "https://maven.google.com"

    /**
     * The versions [resolve] accepts. The version comes from the inspected app (the Compose version file the app ships), so it can be any
     * string, and [resolve] puts it in the name of the cached jar and in the download URL. A '/' or '\' would make the cached jar a path
     * outside the cache directory (e.g. "../../x") and change which URL is downloaded; spaces and control characters don't belong in a URL.
     * Released Maven versions, e.g. "1.10.4", "1.11.0-alpha05" or "1.7.0-SNAPSHOT", only use the characters allowed here.
     */
    private val VALID_VERSION = Regex("[0-9A-Za-z][0-9A-Za-z._+-]*")

    /**
     * The 4 bytes every AAR starts with. An AAR is a zip archive, and a zip archive with entries starts with the signature of its first
     * local file header: 0x04034b50 stored little-endian, i.e. "PK" followed by the bytes 3 and 4. The ZIP format specification (PKWARE
     * APPNOTE.TXT, section 4.3.7) fixes this value, so it never changes.
     */
    private val ZIP_LOCAL_FILE_HEADER_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
  }

  /**
   * Resolves the given artifact (e.g. "androidx.compose.ui", "ui-android", "1.5.4") from Google Maven, downloads its AAR, and extracts the
   * nested "inspector.jar".
   *
   * @return The local cached file containing the extracted "inspector.jar".
   * @throws IOException when [version] cannot be used in a file name and a URL, when the download fails, or when the downloaded file is not
   *   an AAR containing "inspector.jar".
   */
  fun resolve(groupId: String, artifactId: String, version: String): File {
    checkVersion(version)
    val cachedJarName = "$groupId-$artifactId-$version-inspector.jar"
    val cachedJar = File(cacheDir, cachedJarName)
    if (cachedJar.isFile) {
      return cachedJar
    }

    val url = "$GOOGLE_MAVEN_BASE_URL/${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.aar"
    val tempAar = downloadFromMaven(url, groupId, artifactId, version)
    try {
      return installInspectorJar(tempAar, cachedJar, fileMover) ?: throw IOException("$INSPECTOR_JAR_ENTRY_NAME not found in $url")
    } finally {
      tempAar.delete()
    }
  }

  /** Throws an [IOException] when [version] is not one of the [VALID_VERSION]s. */
  private fun checkVersion(version: String) {
    if (version.isEmpty()) {
      throw IOException("The app reports an empty version")
    }
    if (!VALID_VERSION.matches(version)) {
      throw IOException(
        "The version the app reports is not a valid Maven version: it must start with a letter or a digit, and contain only letters, " +
          "digits, '.', '_', '+' and '-'"
      )
    }
  }

  /** Downloads the AAR at [url] to a local temporary file, and checks that it is a zip archive. */
  private fun downloadFromMaven(url: String, groupId: String, artifactId: String, version: String): File {
    val tempAar = File.createTempFile("$groupId-$artifactId-$version-", ".aar")
    try {
      downloader.download(url, tempAar)
      checkIsZipArchive(tempAar, url)
      return tempAar
    } catch (e: Exception) {
      tempAar.delete()
      throw e
    }
  }

  /**
   * Throws an [IOException] when the file downloaded from [url] is not a zip archive. Reading a file that is not one, e.g. the HTML page a
   * proxy returns instead of the AAR, would otherwise just find no "inspector.jar" in it.
   */
  private fun checkIsZipArchive(file: File, url: String) {
    val header = file.inputStream().use { it.readNBytes(ZIP_LOCAL_FILE_HEADER_SIGNATURE.size) }
    if (header.isEmpty()) {
      throw IOException("$url is not an AAR: the response is empty")
    }
    if (!header.contentEquals(ZIP_LOCAL_FILE_HEADER_SIGNATURE)) {
      throw IOException("$url is not an AAR: the response is not a zip archive (a proxy or firewall may have returned an HTML page)")
    }
  }
}
