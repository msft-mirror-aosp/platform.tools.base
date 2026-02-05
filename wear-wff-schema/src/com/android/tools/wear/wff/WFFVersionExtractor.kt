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
package com.android.tools.wear.wff

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.ATTR_VALUE
import com.android.SdkConstants.TAG_APPLICATION
import com.android.SdkConstants.TAG_PROPERTY
import com.android.SdkConstants.WATCH_FACE_FORMAT_VERSION_PROPERTY
import com.android.utils.subtag
import com.android.utils.subtags
import org.w3c.dom.Document

/** Utility class that extracts [WFFVersion]s from manifest files. */
class WFFVersionExtractor {

  fun extractFromManifest(manifest: Document): WFFVersion? {
    val root = manifest.documentElement ?: return null
    val application = root.subtag(TAG_APPLICATION) ?: return null
    val properties = application.subtags(TAG_PROPERTY)
    val wffVersionProperty =
      properties.asSequence().firstOrNull { it.getAttributeNS(ANDROID_URI, ATTR_NAME) == WATCH_FACE_FORMAT_VERSION_PROPERTY } ?: return null
    val wffVersion = wffVersionProperty.getAttributeNS(ANDROID_URI, ATTR_VALUE)
    return WFFVersion.entries.firstOrNull { it.version == wffVersion }
  }
}
