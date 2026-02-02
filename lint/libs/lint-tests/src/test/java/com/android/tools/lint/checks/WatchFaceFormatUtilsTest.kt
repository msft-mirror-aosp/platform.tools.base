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
import com.android.tools.lint.detector.api.Project
import com.android.utils.XmlUtils
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    assertThat(hasDeclarativeWatchFaceFile(project)).isTrue()
  }
}
