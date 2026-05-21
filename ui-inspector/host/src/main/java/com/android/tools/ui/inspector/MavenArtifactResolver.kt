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

import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

/** Abstraction for downloading remote artifacts. */
interface ArtifactDownloader {

  /**
   * Downloads the artifact payload from the specified [url] and writes it to the [outputFile].
   *
   * @param url The URL of the remote artifact.
   * @param outputFile The local file where the downloaded bytes will be written.
   */
  fun download(url: String, outputFile: File)
}

/** Default implementation of [ArtifactDownloader] that downloads files over standard HTTP. */
class HttpArtifactDownloader : ArtifactDownloader {
  override fun download(url: String, outputFile: File) {
    URL(url).openStream().use { input -> outputFile.outputStream().use { output -> input.copyTo(output) } }
  }
}

/** Resolves and downloads library artifacts from Google's Maven repository and extracts their nested payload jars. */
class MavenArtifactResolver(private val downloader: ArtifactDownloader = HttpArtifactDownloader()) {

  companion object {
    private const val GOOGLE_MAVEN_BASE_URL = "https://maven.google.com"
    private const val PAYLOAD_JAR_ENTRY_NAME = "inspector.jar"
  }

  /**
   * Resolves the given artifact (e.g. "androidx.compose.ui", "ui-android", "1.5.4") from Google Maven, downloads its AAR, and extracts the
   * nested "inspector.jar".
   *
   * @return The local temporary file containing the extracted "inspector.jar".
   */
  fun resolve(groupId: String, artifactId: String, version: String): File {
    val fileName = "$artifactId-$version.aar"
    val tempAar = downloadFromMaven(groupId, artifactId, version, fileName)
    val inspectorJar = extractInspectorJar(tempAar) ?: throw IllegalStateException("$PAYLOAD_JAR_ENTRY_NAME not found in $fileName")
    return inspectorJar
  }

  /** Downloads the target AAR artifact from Google Maven to a local temporary file. */
  private fun downloadFromMaven(groupId: String, artifactId: String, version: String, fileName: String): File {
    val groupPath = groupId.replace('.', '/')
    val url = "$GOOGLE_MAVEN_BASE_URL/$groupPath/$artifactId/$version/$fileName"

    val tempAar = File.createTempFile("$groupId-$artifactId-$version-", ".aar")
    tempAar.deleteOnExit()

    downloader.download(url, tempAar)
    return tempAar
  }

  /** Extracts the nested "inspector.jar" from the downloaded [aarFile]. */
  private fun extractInspectorJar(aarFile: File): File? {
    val prefix = "${aarFile.nameWithoutExtension}-extracted-"
    val inspectorJar = File.createTempFile(prefix, ".jar")
    inspectorJar.deleteOnExit()

    ZipInputStream(aarFile.inputStream()).use { zipInputStream ->
      var entry = zipInputStream.nextEntry
      while (entry != null) {
        if (entry.name == PAYLOAD_JAR_ENTRY_NAME) {
          inspectorJar.outputStream().use { output -> zipInputStream.copyTo(output) }
          return inspectorJar
        }
        entry = zipInputStream.nextEntry
      }
    }
    return null
  }
}
