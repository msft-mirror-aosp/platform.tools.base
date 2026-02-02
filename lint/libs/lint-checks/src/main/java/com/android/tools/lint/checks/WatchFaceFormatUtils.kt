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

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.FD_RES_RAW
import com.android.SdkConstants.TAG_APPLICATION
import com.android.SdkConstants.TAG_PROPERTY
import com.android.SdkConstants.TAG_WATCH_FACE
import com.android.SdkConstants.WATCH_FACE_FORMAT_VERSION_PROPERTY
import com.android.tools.lint.detector.api.Project
import com.android.utils.XmlUtils
import com.android.utils.subtags
import com.android.xml.AndroidManifest.ATTRIBUTE_NAME
import com.google.common.io.Files
import org.w3c.dom.Element

object WatchFaceFormatUtils {
  /**
   * Returns `true` if an `<application>` [Element] has a `<property>` element with an `android:name` attribute equal to
   * `[WATCH_FACE_FORMAT_VERSION_PROPERTY]`.
   */
  fun hasWatchFaceFormatVersionProperty(application: Element): Boolean {
    assert(application.tagName == TAG_APPLICATION)
    return application.subtags(TAG_PROPERTY).asSequence().any {
      it.getAttributeNS(ANDROID_URI, ATTRIBUTE_NAME) == WATCH_FACE_FORMAT_VERSION_PROPERTY
    }
  }

  /** Returns `true` if there is a Declarative Watch Face file (a file with a `<WatchFace>` root tag) in a `res/raw` folder. */
  fun hasDeclarativeWatchFaceFile(project: Project) =
    project.resourceFolders
      .flatMap { it.listFiles().toList() }
      .filter { it.name.startsWith(FD_RES_RAW) }
      .any { rawFolder ->
        rawFolder.listFiles().any {
          val xml = Files.asCharSource(it, Charsets.UTF_8).read()
          val document = XmlUtils.parseDocumentSilently(xml, false)
          val rootTag = document?.documentElement?.tagName
          rootTag == TAG_WATCH_FACE
        }
      }
}
