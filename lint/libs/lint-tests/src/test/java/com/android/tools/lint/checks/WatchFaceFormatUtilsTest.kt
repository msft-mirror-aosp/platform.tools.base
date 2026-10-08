/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.lint.checks

import com.android.SdkConstants.WATCH_FACE_FORMAT_VERSION_PROPERTY
import com.android.tools.lint.checks.WatchFaceFormatUtils.hasDeclarativeWatchFaceFile
import com.android.tools.lint.checks.WatchFaceFormatUtils.hasWatchFaceFormatVersionProperty
import com.android.tools.lint.checks.infrastructure.TestLintClient
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Project
import com.android.utils.XmlUtils
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class WatchFaceFormatUtilsTest {

  @get:Rule var temporaryFolder = TemporaryFolder()

  @Test
  fun `test hasWatchFaceFormatVersionProperty`() {
    val applicationWithWFFVersionProperty =
      XmlUtils.parseDocument(
          // language=XML
          """
      <application xmlns:android="http://schemas.android.com/apk/res/android">
        <property android:name='$WATCH_FACE_FORMAT_VERSION_PROPERTY' android:value='1' />
      </application>
    """,
          true,
        )
        .documentElement

    val applicationWithout =
      XmlUtils.parseDocument(
          // language=XML
          """
      <application>
      </application>
    """,
          true,
        )
        .documentElement

    assertThat(hasWatchFaceFormatVersionProperty(applicationWithWFFVersionProperty)).isTrue()
    assertThat(hasWatchFaceFormatVersionProperty(applicationWithout)).isFalse()
  }

  @Test
  fun `test hasDeclarativeWatchFaceFile`() {
    val projectFolder = temporaryFolder.newFolder("project")
    val project = Project.create(TestLintClient(), projectFolder, projectFolder)

    val nonDWFResourceFile = File(projectFolder, "res/raw/resources.xml").absoluteFile
    nonDWFResourceFile.parentFile.mkdirs()
    nonDWFResourceFile.createNewFile()
    nonDWFResourceFile.writeText(
      // language=XML
      """
        <resource>
        </resource>
      """
    )

    assertThat(hasDeclarativeWatchFaceFile(project)).isFalse()

    val declarativeWatchFaceFile = File(projectFolder, "res/raw/watch_face.xml").absoluteFile
    declarativeWatchFaceFile.createNewFile()
    declarativeWatchFaceFile.writeText(
      // language=XML
      """
        <WatchFace>
        </WatchFace>
      """
    )
    WatchFaceFormatUtils.clearCache(project)

    assertThat(hasDeclarativeWatchFaceFile(project)).isTrue()
    assertThat(WatchFaceFormatUtils.isWatchFaceFormatProject(project)).isTrue()
  }

  @Test
  fun `test hasDeclarativeWatchFaceFile ignores non-xml and binary files`() {
    val projectFolder = temporaryFolder.newFolder("non_xml_project")
    val project = Project.create(TestLintClient(), projectFolder, projectFolder)

    val rawFolder = File(projectFolder, "res/raw")
    rawFolder.mkdirs()

    val binaryFile = File(rawFolder, "sound.mp3")
    binaryFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte()))

    val txtFile = File(rawFolder, "watchface.txt")
    txtFile.writeText("<WatchFace></WatchFace>")

    val subDir = File(rawFolder, "subfolder")
    subDir.mkdirs()

    assertThat(hasDeclarativeWatchFaceFile(project)).isFalse()

    val validXml = File(rawFolder, "watch_face.xml")
    validXml.writeText(
      // language=XML
      """
        <WatchFace>
        </WatchFace>
      """
    )
    WatchFaceFormatUtils.clearCache(project)

    assertThat(hasDeclarativeWatchFaceFile(project)).isTrue()
    assertThat(WatchFaceFormatUtils.isWatchFaceFormatProject(project)).isTrue()
  }

  @Test
  fun `test hasWatchFaceFormatVersionProperty with Project`() {
    val projectFolder = temporaryFolder.newFolder("manifest_project")
    val project = Project.create(TestLintClient(), projectFolder, projectFolder)

    val manifestFile = File(projectFolder, "AndroidManifest.xml")
    manifestFile.writeText(
      // language=XML
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.pkg">
        <application>
          <property android:name='$WATCH_FACE_FORMAT_VERSION_PROPERTY' android:value='1' />
        </application>
      </manifest>
      """
    )
    assertThat(hasWatchFaceFormatVersionProperty(project)).isTrue()
    assertThat(WatchFaceFormatUtils.isWatchFaceFormatProject(project)).isTrue()
  }

  @Test
  fun `test isWatchFaceFormatProject caching`() {
    val projectFolder = temporaryFolder.newFolder("cached_project")
    val project = Project.create(TestLintClient(), projectFolder, projectFolder)

    assertThat(WatchFaceFormatUtils.isWatchFaceFormatProject(project)).isFalse()
    // Explicitly override cached value to verify caching
    project.putClientProperty(WatchFaceFormatUtils.KEY_IS_WFF_PROJECT, true)
    assertThat(WatchFaceFormatUtils.isWatchFaceFormatProject(project)).isTrue()
  }

  @Test
  fun `test isWatchFaceFormatProject with Context`() {
    val projectFolder = temporaryFolder.newFolder("context_project")
    val project = Project.create(TestLintClient(), projectFolder, projectFolder)
    val mainFolder = temporaryFolder.newFolder("context_main_project")
    val mainProject = Project.create(TestLintClient(), mainFolder, mainFolder)

    val context = mock<Context>()
    whenever(context.project).thenReturn(project)
    whenever(context.mainProject).thenReturn(mainProject)

    // Neither is WFF
    whenever(context.isGlobalAnalysis()).thenReturn(false)
    assertThat(WatchFaceFormatUtils.isWatchFaceFormatProject(context)).isFalse()

    // project is WFF
    project.putClientProperty(WatchFaceFormatUtils.KEY_IS_WFF_PROJECT, true)
    assertThat(WatchFaceFormatUtils.isWatchFaceFormatProject(context)).isTrue()

    // project is not WFF, mainProject is WFF, but not global analysis
    project.putClientProperty(WatchFaceFormatUtils.KEY_IS_WFF_PROJECT, false)
    mainProject.putClientProperty(WatchFaceFormatUtils.KEY_IS_WFF_PROJECT, true)
    assertThat(WatchFaceFormatUtils.isWatchFaceFormatProject(context)).isFalse()

    // project is not WFF, mainProject is WFF, and global analysis is true
    whenever(context.isGlobalAnalysis()).thenReturn(true)
    assertThat(WatchFaceFormatUtils.isWatchFaceFormatProject(context)).isTrue()
  }
}
