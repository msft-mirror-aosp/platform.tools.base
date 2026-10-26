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

import com.google.common.base.Throwables
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.util.zip.ZipException
import javax.net.ssl.SSLException
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

/** The outcome of looking for an inspector jar in one place. */
internal sealed interface JarLookup {
  /** The inspector jar was found at [location], e.g. a URL, and is now in the local file [jar], e.g. extracted into the cache. */
  data class Found(val jar: File, val location: String) : JarLookup

  /** The place at [location] did not have the inspector jar, for [reason]. */
  data class NotFound(val location: String, val reason: String) : JarLookup
}

/**
 * Finds the inspector jars of libraries in Google's Maven repository: downloads a library's AAR and extracts its nested "inspector.jar"
 * into [cacheDir], where later runs find it.
 *
 * The versions passed to it must be valid Maven versions, without path separators: they become part of a file name and a URL.
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
    private const val GOOGLE_MAVEN_BASE_URL = "https://maven.google.com"

    /**
     * The 4 bytes every AAR starts with. An AAR is a zip archive, and a zip archive with entries starts with the signature of its first
     * local file header: 0x04034b50 stored little-endian, i.e. "PK" followed by the bytes 3 and 4. The ZIP format specification (PKWARE
     * APPNOTE.TXT, section 4.3.7) fixes this value, so it never changes.
     */
    private val ZIP_LOCAL_FILE_HEADER_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
  }

  /** Returns the inspector jar of the given artifact (e.g. "androidx.compose.ui", "ui-android", "1.5.4") in [cacheDir], or null. */
  fun cachedJar(groupId: String, artifactId: String, version: String): File? =
    cachedJarFile(groupId, artifactId, version).takeIf { it.isFile }

  /**
   * Downloads the AAR of the given artifact (e.g. "androidx.compose.ui", "ui-android", "1.5.4") from Google Maven and extracts its nested
   * "inspector.jar" into [cacheDir].
   *
   * @return [JarLookup.Found] with the cached jar, or [JarLookup.NotFound] with why the AAR could not be downloaded or has no inspector
   *   jar.
   * @throws IOException when the extracted jar cannot be written to [cacheDir], or the temporary file for the download cannot be created.
   */
  fun download(groupId: String, artifactId: String, version: String): JarLookup {
    val url = "$GOOGLE_MAVEN_BASE_URL/${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.aar"
    val tempAar =
      try {
        File.createTempFile("$groupId-$artifactId-$version-", ".aar")
      } catch (e: IOException) {
        throw IOException("Could not create a temporary file for the download of $url: ${e.messageOrClassName()}", e)
      }
    try {
      try {
        downloader.download(url, tempAar)
        checkIsAar(tempAar)
      } catch (e: IOException) {
        // This includes a disk that fills up while the download is written: the downloader reports it like a network failure.
        return JarLookup.NotFound(url, downloadFailureReason(e, url))
      }
      val jar =
        try {
          installInspectorJar(tempAar, cachedJarFile(groupId, artifactId, version), fileMover)
        } catch (e: ZipException) {
          return JarLookup.NotFound(url, "the AAR is corrupt or truncated (${e.messageOrClassName()})")
        } catch (e: EOFException) {
          return JarLookup.NotFound(url, "the AAR is corrupt or truncated (${e.messageOrClassName()})")
        } catch (e: IOException) {
          throw IOException("Could not extract $INSPECTOR_JAR_ENTRY_NAME to $cacheDir: ${e.messageOrClassName()}", e)
        }
      return if (jar != null) JarLookup.Found(jar, url) else JarLookup.NotFound(url, "the AAR has no $INSPECTOR_JAR_ENTRY_NAME")
    } finally {
      tempAar.delete()
    }
  }

  private fun cachedJarFile(groupId: String, artifactId: String, version: String) =
    File(cacheDir, "$groupId-$artifactId-$version-inspector.jar")

  /**
   * Throws a [NotAnAarException] when the downloaded [file] is not a zip archive. Reading a file that is not one, e.g. the HTML page a
   * proxy returns instead of the AAR, would otherwise just find no "inspector.jar" in it.
   */
  private fun checkIsAar(file: File) {
    val header = file.inputStream().use { it.readNBytes(ZIP_LOCAL_FILE_HEADER_SIGNATURE.size) }
    if (header.isEmpty()) {
      throw NotAnAarException("not an AAR: the response is empty")
    }
    if (!header.contentEquals(ZIP_LOCAL_FILE_HEADER_SIGNATURE)) {
      throw NotAnAarException("not an AAR: the response is not a zip archive (a proxy or firewall may have returned an HTML page)")
    }
  }
}

/** The downloaded file is not an AAR, for [reason]. */
private class NotAnAarException(val reason: String) : IOException(reason)

/**
 * Returns why the download of [url] failed with [e], for a message. The whole cause chain is inspected, since downloaders wrap the network
 * exceptions (e.g. java.net.http reports an unknown host as a ConnectException caused by an UnresolvedAddressException), and the most
 * specific cause wins.
 */
private fun downloadFailureReason(e: IOException, url: String): String {
  val causes = Throwables.getCausalChain(e)
  causes
    .firstNotNullOfOrNull { it as? NotAnAarException }
    ?.let {
      return it.reason
    }
  causes
    .firstNotNullOfOrNull { it as? ArtifactHttpStatusException }
    ?.let {
      return httpStatusReason(it.statusCode)
    }
  if (causes.any { it is UnknownHostException || it is UnresolvedAddressException }) {
    return "cannot connect: unknown host ${URL(url).host}"
  }
  if (causes.any { it is SocketTimeoutException || it is HttpTimeoutException }) {
    return "timed out"
  }
  causes
    .firstOrNull { it is SSLException }
    ?.let {
      return "TLS error (${it.messageOrClassName()}); a proxy may be intercepting HTTPS"
    }
  causes
    .firstOrNull { it is ConnectException || it is NoRouteToHostException }
    ?.let { cause ->
      return cause.message?.let { "cannot connect ($it)" } ?: "cannot connect"
    }
  return "download failed: ${e.messageOrClassName()}"
}

/** Returns what the HTTP error status [statusCode] means for a download, for a message. */
private fun httpStatusReason(statusCode: Int): String =
  when (statusCode) {
    404,
    410 -> "not found (HTTP $statusCode): this version is not on Google Maven"
    401 -> "authentication required (HTTP 401)"
    403 -> "access denied (HTTP 403)"
    407 -> "proxy authentication required (HTTP 407)"
    in 300..399 -> "redirected (HTTP $statusCode); a proxy may require signing in"
    in 500..599 -> "server error (HTTP $statusCode)"
    else -> "HTTP $statusCode"
  }

private fun Throwable.messageOrClassName(): String = message ?: javaClass.simpleName
