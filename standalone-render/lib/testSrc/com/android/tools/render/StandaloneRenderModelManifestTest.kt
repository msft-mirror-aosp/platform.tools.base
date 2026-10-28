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

package com.android.tools.render

import com.android.SdkConstants
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Regression test for b/575469767
class StandaloneRenderModelManifestTest {
  @get:Rule val tempFolder = TemporaryFolder()

  companion object {
    // Compiled binary AndroidManifest.xml from manifest-parser tests with android:supportsRtl="true".
    // Corresponding plain text XML:
    //
    // <?xml version="1.0" encoding="utf-8"?>
    // <manifest xmlns:android="http://schemas.android.com/apk/res/android"
    //     package="com.example.myapplication"
    //     android:versionCode="3">
    //     <application
    //         android:allowBackup="true"
    //         android:icon="@mipmap/ic_launcher"
    //         android:label="@string/app_name"
    //         android:roundIcon="@mipmap/ic_launcher_round"
    //         android:supportsRtl="true">
    //     </application>
    //     <instrumentation
    //         android:name="android.support.test.runner.AndroidJUnitRunner"
    //         android:targetPackage="com.android.shell"
    //         android:label="Tests for Shell"/>
    // </manifest>
    internal const val BXML_SUPPORTS_RTL_BASE64 =
      "AwAIAHgHAAABABwAqAQAAB4AAAAAAAAAAAAAAJQAAAAAAAAAAAAAAA4AAAAaAAAAJgAAAEQAAABiAAAAfAAAAJYAAAC6AAAA1AAA" +
        "AO4AAAAEAQAAKgEAAGABAACKAQAAkgEAAJwBAAC+AQAA0AEAADACAACAAgAAmgIAAMACAAD2AgAATgMAAHADAACEAwAAlgMAAMoD" +
        "AAD+AwAABQBsAGEAYgBlAGwAAAAEAGkAYwBvAG4AAAAEAG4AYQBtAGUAAAANAHQAYQByAGcAZQB0AFAAYQBjAGsAYQBnAGUAAAAN" +
        "AG0AaQBuAFMAZABrAFYAZQByAHMAaQBvAG4AAAALAHYAZQByAHMAaQBvAG4AQwBvAGQAZQAAAAsAdgBlAHIAcwBpAG8AbgBOAGEA" +
        "bQBlAAAAEAB0AGEAcgBnAGUAdABTAGQAawBWAGUAcgBzAGkAbwBuAAAACwBhAGwAbABvAHcAQgBhAGMAawB1AHAAAAALAHMAdQBw" +
        "AHAAbwByAHQAcwBSAHQAbAAAAAkAcgBvAHUAbgBkAEkAYwBvAG4AAAARAGMAbwBtAHAAaQBsAGUAUwBkAGsAVgBlAHIAcwBpAG8A" +
        "bgAAABkAYwBvAG0AcABpAGwAZQBTAGQAawBWAGUAcgBzAGkAbwBuAEMAbwBkAGUAbgBhAG0AZQAAABMAYQBwAHAAQwBvAG0AcABv" +
        "AG4AZQBuAHQARgBhAGMAdABvAHIAeQAAAAIAMQAxAAAAAwAzAC4AMAAAAA8AVABlAHMAdABzACAAZgBvAHIAIABTAGgAZQBsAGwA" +
        "AAAHAGEAbgBkAHIAbwBpAGQAAAAuAGEAbgBkAHIAbwBpAGQALgBzAHUAcABwAG8AcgB0AC4AdABlAHMAdAAuAHIAdQBuAG4AZQBy" +
        "AC4AQQBuAGQAcgBvAGkAZABKAFUAbgBpAHQAUgB1AG4AbgBlAHIAAAAmAGEAbgBkAHIAbwBpAGQAeAAuAGMAbwByAGUALgBhAHAA" +
        "cAAuAEMAbwByAGUAQwBvAG0AcABvAG4AZQBuAHQARgBhAGMAdABvAHIAeQAAAAsAYQBwAHAAbABpAGMAYQB0AGkAbwBuAAAAEQBj" +
        "AG8AbQAuAGEAbgBkAHIAbwBpAGQALgBzAGgAZQBsAGwAAAAZAGMAbwBtAC4AZQB4AGEAbQBwAGwAZQAuAG0AeQBhAHAAcABsAGkA" +
        "YwBhAHQAaQBvAG4AAAAqAGgAdAB0AHAAOgAvAC8AcwBjAGgAZQBtAGEAcwAuAGEAbgBkAHIAbwBpAGQALgBjAG8AbQAvAGEAcABr" +
        "AC8AcgBlAHMALwBhAG4AZAByAG8AaQBkAAAADwBpAG4AcwB0AHIAdQBtAGUAbgB0AGEAdABpAG8AbgAAAAgAbQBhAG4AaQBmAGUA" +
        "cwB0AAAABwBwAGEAYwBrAGEAZwBlAAAAGABwAGwAYQB0AGYAbwByAG0AQgB1AGkAbABkAFYAZQByAHMAaQBvAG4AQwBvAGQAZQAA" +
        "ABgAcABsAGEAdABmAG8AcgBtAEIAdQBpAGwAZABWAGUAcgBzAGkAbwBuAE4AYQBtAGUAAAAIAHUAcwBlAHMALQBzAGQAawAAAAAA" +
        "gAEIAEAAAAABAAEBAgABAQMAAQEhAAEBDAIBARsCAQEcAgEBcAIBAYACAQGvAwEBLAUBAXIFAQFzBQEBegUBAQABEAAYAAAAAgAA" +
        "AP////8RAAAAFwAAAAIBEACwAAAAAgAAAP//////////GQAAABQAFAAHAAAAAAAAABcAAAAFAAAA/////wgAABADAAAAFwAAAAYA" +
        "AAAPAAAACAAAAw8AAAAXAAAACwAAAP////8IAAAQHgAAABcAAAAMAAAADgAAAAgAAAMOAAAA/////xoAAAAWAAAACAAAAxYAAAD/" +
        "////GwAAAP////8IAAAQHgAAAP////8cAAAA/////wgAABALAAAAAgEQAEwAAAAHAAAA//////////8dAAAAFAAUAAIAAAAAAAAA" +
        "FwAAAAQAAAD/////CAAAEBUAAAAXAAAABwAAAP////8IAAAQHgAAAAMBEAAYAAAABwAAAP//////////HQAAAAIBEABgAAAACwAA" +
        "AP//////////GAAAABQAFAADAAAAAAAAABcAAAAAAAAAEAAAAAgAAAMQAAAAFwAAAAIAAAASAAAACAAAAxIAAAAXAAAAAwAAABUA" +
        "AAAIAAADFQAAAAMBEAAYAAAACwAAAP//////////GAAAAAIBEACcAAAAEQAAAP//////////FAAAABQAFAAGAAAAAAAAABcAAAAA" +
        "AAAA/////wgAAAEbAA5/FwAAAAEAAAD/////CAAAAQAADH8XAAAACAAAAP////8IAAAS/////xcAAAAJAAAA/////wgAABL/////" +
        "FwAAAAoAAAD/////CAAAAQEADH8XAAAADQAAABMAAAAIAAADEwAAAAMBEAAYAAAAEQAAAP//////////FAAAAAMBEAAYAAAAAgAA" +
        "AP//////////GQAAAAEBEAAYAAAAAgAAAP////8RAAAAFwAAAA=="
  }

  @Test
  fun testParseBinary_supportsRtlTrue() {
    val bytes = Base64.getDecoder().decode(BXML_SUPPORTS_RTL_BASE64)
    val manifest = StandaloneRenderModelManifest.parseBinary(bytes)
    assertThat(manifest).isNotNull()
    assertThat(manifest!!.isRtlSupported).isTrue()
    assertThat(manifest.applicationLabel).isNull()
    assertThat(manifest.applicationIcon).isNull()
    assertThat(manifest.getActivityAttributes("com.example.MainActivity")).isNull()
  }

  @Test
  fun testParsePlainText_supportsRtlTrue() {
    val xml =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.test">
          <application android:supportsRtl="true" />
      </manifest>
      """
        .trimIndent()
    val manifest = StandaloneRenderModelManifest.parsePlainText(xml.toByteArray(StandardCharsets.UTF_8))
    assertThat(manifest).isNotNull()
    assertThat(manifest!!.isRtlSupported).isTrue()
  }

  @Test
  fun testParsePlainText_supportsRtlFalse() {
    val xml =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.test">
          <application android:supportsRtl="false" />
      </manifest>
      """
        .trimIndent()
    val manifest = StandaloneRenderModelManifest.parsePlainText(xml.toByteArray(StandardCharsets.UTF_8))
    assertThat(manifest).isNotNull()
    assertThat(manifest!!.isRtlSupported).isFalse()
  }

  @Test
  fun testParsePlainText_supportsRtlOmitted() {
    val xml =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.test">
          <application android:label="Test" />
      </manifest>
      """
        .trimIndent()
    val manifest = StandaloneRenderModelManifest.parsePlainText(xml.toByteArray(StandardCharsets.UTF_8))
    assertThat(manifest).isNotNull()
    assertThat(manifest!!.isRtlSupported).isFalse()
  }

  @Test
  fun testParsePlainText_noApplicationTag() {
    val xml =
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.test" />
      """
        .trimIndent()
    val manifest = StandaloneRenderModelManifest.parsePlainText(xml.toByteArray(StandardCharsets.UTF_8))
    assertThat(manifest).isNotNull()
    assertThat(manifest!!.isRtlSupported).isFalse()
  }

  @Test
  fun testParseBinary_invalidContent() {
    val invalid = "not valid binary".toByteArray(StandardCharsets.UTF_8)
    assertThat(StandaloneRenderModelManifest.parseBinary(invalid)).isNull()
  }

  @Test
  fun testParsePlainText_invalidContent() {
    val invalid = "not valid xml".toByteArray(StandardCharsets.UTF_8)
    assertThat(StandaloneRenderModelManifest.parsePlainText(invalid)).isNull()
  }

  @Test
  fun testFromApk_withBinaryManifest() {
    val apkFile = tempFolder.newFile("app.apk")
    ZipOutputStream(FileOutputStream(apkFile)).use { zos ->
      zos.putNextEntry(ZipEntry(SdkConstants.FN_ANDROID_MANIFEST_XML))
      zos.write(Base64.getDecoder().decode(BXML_SUPPORTS_RTL_BASE64))
      zos.closeEntry()
    }

    val manifest = StandaloneRenderModelManifest.fromApk(apkFile.absolutePath)
    assertThat(manifest).isNotNull()
    assertThat(manifest!!.isRtlSupported).isTrue()
  }

  @Test
  fun testFromApk_withPlainTextManifest_returnsNull() {
    val apkFile = tempFolder.newFile("app_text.apk")
    val xml =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.test">
          <application android:supportsRtl="true" />
      </manifest>
      """
        .trimIndent()
    ZipOutputStream(FileOutputStream(apkFile)).use { zos ->
      zos.putNextEntry(ZipEntry(SdkConstants.FN_ANDROID_MANIFEST_XML))
      zos.write(xml.toByteArray(StandardCharsets.UTF_8))
      zos.closeEntry()
    }

    // fromApk expects compiled binary XML (APK format), so plain-text XML inside an APK returns null.
    val manifest = StandaloneRenderModelManifest.fromApk(apkFile.absolutePath)
    assertThat(manifest).isNull()
  }

  @Test
  fun testFromApk_missingEntryReturnsNull() {
    val apkFile = tempFolder.newFile("empty.apk")
    ZipOutputStream(FileOutputStream(apkFile)).use { zos ->
      zos.putNextEntry(ZipEntry("classes.dex"))
      zos.write("dex".toByteArray(StandardCharsets.UTF_8))
      zos.closeEntry()
    }

    assertThat(StandaloneRenderModelManifest.fromApk(apkFile.absolutePath)).isNull()
  }

  @Test
  fun testFromPath_directManifestFile() {
    val manifestFile = tempFolder.newFile(SdkConstants.FN_ANDROID_MANIFEST_XML)
    manifestFile.writeText(
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.test">
          <application android:supportsRtl="true" />
      </manifest>
      """
        .trimIndent()
    )

    val manifest = StandaloneRenderModelManifest.fromPath(manifestFile.absolutePath)
    assertThat(manifest).isNotNull()
    assertThat(manifest!!.isRtlSupported).isTrue()
  }

  @Test
  fun testFromPath_directoryContainingManifest() {
    val dir = tempFolder.newFolder("module_root")
    val manifestFile = File(dir, SdkConstants.FN_ANDROID_MANIFEST_XML)
    manifestFile.writeText(
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.test">
          <application android:supportsRtl="true" />
      </manifest>
      """
        .trimIndent()
    )

    val manifest = StandaloneRenderModelManifest.fromPath(dir.absolutePath)
    assertThat(manifest).isNotNull()
    assertThat(manifest!!.isRtlSupported).isTrue()
  }

  @Test
  fun testFromPath_nonExistentPathReturnsNull() {
    assertThat(StandaloneRenderModelManifest.fromPath("/non/existent/path/app.apk")).isNull()
  }
}
