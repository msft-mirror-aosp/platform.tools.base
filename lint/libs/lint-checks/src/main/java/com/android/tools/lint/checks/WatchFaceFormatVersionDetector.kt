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

import com.android.SdkConstants.WATCH_FACE_FORMAT_DEFAULT_VERSION
import com.android.SdkConstants.WATCH_FACE_FORMAT_VERSION_PROPERTY
import com.android.tools.lint.checks.WatchFaceFormatUtils.hasDeclarativeWatchFaceFile
import com.android.tools.lint.checks.WatchFaceFormatUtils.hasWatchFaceFormatVersionProperty
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector.XmlScanner
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlContext
import com.android.utils.XmlUtils
import com.android.xml.AndroidManifest.NODE_APPLICATION

/**
 * Detector that checks that the manifest's application contains a
 * [WATCH_FACE_FORMAT_VERSION_PROPERTY] property if the project contains a Declarative Watch Face
 * file (file with a `<WatchFace>` root tag) in the `res/raw` folder.
 */
class WatchFaceFormatVersionDetector : WearDetector(), XmlScanner {
  override fun afterCheckFile(context: Context) {
    if (!isWearProject) return
    if (!hasDeclarativeWatchFaceFile(context.project)) return
    val xmlContext = context as? XmlContext ?: return
    val root = xmlContext.document.documentElement
    val application = XmlUtils.getFirstSubTagByName(root, NODE_APPLICATION) ?: return
    if (hasWatchFaceFormatVersionProperty(application)) return

    context.report(
      MISSING_VERSION_ISSUE,
      context.getNameLocation(application),
      "The Watch Face Format version property must be set",
    )
  }

  companion object {
    @JvmField
    val MISSING_VERSION_ISSUE =
      Issue.create(
          id = "WatchFaceFormatMissingVersion",
          briefDescription = "The Watch Face Format version is missing",
          explanation =
            """
             When creating a watch face using the Watch Face Format, you need to add the "$WATCH_FACE_FORMAT_VERSION_PROPERTY" application \
             property. This property specifies which feature version the Watch Face Format is using.

             Add the property to your application element:
             ```xml
             <property android:name="$WATCH_FACE_FORMAT_VERSION_PROPERTY"
                       android:value="$WATCH_FACE_FORMAT_DEFAULT_VERSION" />
             ```
          """,
          category = Category.CORRECTNESS,
          priority = 7,
          severity = Severity.ERROR,
          moreInfo = "https://developer.android.com/training/wearables/wff/setup#declare-wff-use",
          implementation =
            Implementation(WatchFaceFormatVersionDetector::class.java, Scope.MANIFEST_SCOPE),
          androidSpecific = true,
        )
        .addMoreInfo("https://developer.android.com/training/wearables/wff/features")
  }
}
