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
import com.android.SdkConstants.DOT_XML
import com.android.SdkConstants.FD_RES_RAW
import com.android.SdkConstants.TAG_APPLICATION
import com.android.SdkConstants.TAG_PROPERTY
import com.android.SdkConstants.TAG_WATCH_FACE
import com.android.SdkConstants.WATCH_FACE_FORMAT_VERSION_PROPERTY
import com.android.tools.lint.detector.api.Project
import com.android.utils.XmlUtils
import com.android.utils.subtags
import com.android.xml.AndroidManifest.ATTRIBUTE_NAME
import com.google.common.annotations.VisibleForTesting
import org.w3c.dom.Element

object WatchFaceFormatUtils {
  @VisibleForTesting const val KEY_IS_WFF_PROJECT = "lint.is.wff.project"
  @VisibleForTesting const val KEY_HAS_WFF_VERSION_PROPERTY = "lint.has.wff.version.property"
  @VisibleForTesting const val KEY_HAS_DECLARATIVE_WFF_FILE = "lint.has.declarative.wff.file"

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

  /**
   * Returns `true` if the project manifest defines a `<property>` in `<application>` with an `android:name` attribute equal to
   * `[WATCH_FACE_FORMAT_VERSION_PROPERTY]`.
   */
  fun hasWatchFaceFormatVersionProperty(project: Project): Boolean {
    val cached = project.getClientProperty<Boolean>(KEY_HAS_WFF_VERSION_PROPERTY)
    if (cached != null) {
      return cached
    }
    val merged = project.mergedManifest?.documentElement
    val result =
      if (merged != null) {
        val application = XmlUtils.getFirstSubTagByName(merged, TAG_APPLICATION)
        application != null && hasWatchFaceFormatVersionProperty(application)
      } else {
        project.manifestFiles.any { file ->
          if (!file.isFile) return@any false
          val xml = project.client.readFile(file).toString()
          val manifest = XmlUtils.parseDocumentSilently(xml, true)?.documentElement
          val application = manifest?.let { XmlUtils.getFirstSubTagByName(it, TAG_APPLICATION) }
          application != null && hasWatchFaceFormatVersionProperty(application)
        }
      }
    project.putClientProperty(KEY_HAS_WFF_VERSION_PROPERTY, result)
    return result
  }

  /** Returns `true` if there is a Declarative Watch Face file (a file with a `<WatchFace>` root tag) in a `res/raw` folder. */
  fun hasDeclarativeWatchFaceFile(project: Project): Boolean {
    val cached = project.getClientProperty<Boolean>(KEY_HAS_DECLARATIVE_WFF_FILE)
    if (cached != null) {
      return cached
    }
    val result =
      project.resourceFolders
        .flatMap { it.listFiles()?.toList() ?: emptyList() }
        .filter { it.name.startsWith(FD_RES_RAW) }
        .any { rawFolder ->
          rawFolder.listFiles()?.any {
            if (!it.isFile || !it.name.endsWith(DOT_XML)) return@any false
            val xml = project.client.readFile(it).toString()
            val document = XmlUtils.parseDocumentSilently(xml, false)
            val rootTag = document?.documentElement?.tagName
            rootTag == TAG_WATCH_FACE
          } ?: false
        }
    project.putClientProperty(KEY_HAS_DECLARATIVE_WFF_FILE, result)
    return result
  }

  /** Clears any cached WFF inspection state for the given [project]. */
  @VisibleForTesting
  fun clearCache(project: Project) {
    project.putClientProperty(KEY_IS_WFF_PROJECT, null)
    project.putClientProperty(KEY_HAS_WFF_VERSION_PROPERTY, null)
    project.putClientProperty(KEY_HAS_DECLARATIVE_WFF_FILE, null)
  }

  /**
   * Returns `true` if the project is a Watch Face Format (WFF) project, determined either by declaring the WFF version property in its
   * manifest or by containing a declarative watch face file in `res/raw/`.
   */
  fun isWatchFaceFormatProject(project: Project): Boolean {
    val cached = project.getClientProperty<Boolean>(KEY_IS_WFF_PROJECT)
    if (cached != null) {
      return cached
    }
    val result = hasWatchFaceFormatVersionProperty(project) || hasDeclarativeWatchFaceFile(project)
    project.putClientProperty(KEY_IS_WFF_PROJECT, result)
    return result
  }
}
