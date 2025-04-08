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
import com.android.SdkConstants.VALUE_FALSE
import com.android.SdkConstants.WATCH_FACE_FORMAT_VERSION_PROPERTY
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.resources.ResourceUrl
import com.android.tools.lint.checks.WatchFaceFormatUtils.hasDeclarativeWatchFaceFile
import com.android.tools.lint.checks.WatchFaceFormatUtils.hasWatchFaceFormatVersionProperty
import com.android.tools.lint.client.api.ResourceRepositoryScope
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector.XmlScanner
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.LocationType
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlContext
import com.android.utils.XmlUtils
import com.android.xml.AndroidManifest.ATTRIBUTE_HASCODE
import com.android.xml.AndroidManifest.NODE_APPLICATION
import org.w3c.dom.Attr

/**
 * Detector that checks that the `android:hasCode` application attribute is set to false when we
 * detect a Watch Face Format usage.
 *
 * We detect the usage of WFF by checking the presence of the [WATCH_FACE_FORMAT_VERSION_PROPERTY]
 * property or the presence of a Declarative Watch Face file in `res/raw`.
 */
class WatchFaceFormatDeclaresHasNoCodeDetector : WearDetector(), XmlScanner {
  override fun afterCheckFile(context: Context) {
    if (!isWearProject) return
    val xmlContext = context as? XmlContext ?: return
    val root = xmlContext.document.documentElement
    val application = XmlUtils.getFirstSubTagByName(root, NODE_APPLICATION) ?: return
    if (
      !hasWatchFaceFormatVersionProperty(application) &&
        !hasDeclarativeWatchFaceFile(context.project)
    ) {
      return
    }

    val hasCodeAttribute = application.getAttributeNodeNS(ANDROID_URI, ATTRIBUTE_HASCODE)
    if (hasCodeAttribute != null && hasCodeAttribute.isFalse(context)) return

    context.report(
      ISSUE,
      context.getLocation(
        node = hasCodeAttribute ?: application,
        type = if (hasCodeAttribute != null) LocationType.DEFAULT else LocationType.NAME,
      ),
      "Applications using Watch Face Format must declare `hasCode=false`",
      fix().set(ANDROID_URI, ATTRIBUTE_HASCODE, VALUE_FALSE).build(),
    )
  }

  private fun Attr.isFalse(context: Context): Boolean {
    val resourceUrl = ResourceUrl.parse(value)
    if (resourceUrl == null) {
      return value == VALUE_FALSE
    }

    return context.client
      // Declarative Watch Faces can only use resources declared within the project
      .getResources(context.project, ResourceRepositoryScope.PROJECT_ONLY)
      .getResources(ResourceNamespace.RES_AUTO, resourceUrl.type, resourceUrl.name)
      .mapNotNull { it.resourceValue?.value }
      .all { it == VALUE_FALSE }
  }

  companion object {
    @JvmField
    val ISSUE =
      Issue.create(
        id = "WatchFaceFormatDeclaresHasNoCode",
        briefDescription = "The `hasCode` attribute should be set to `false`",
        explanation =
          "Watch Face Format is a resource-only format, so the `hasCode` attribute should be set to `false` to reflect this.",
        category = Category.CORRECTNESS,
        priority = 7,
        severity = Severity.ERROR,
        moreInfo = "https://developer.android.com/training/wearables/wff/setup#declare-wff-use",
        implementation =
          Implementation(
            WatchFaceFormatDeclaresHasNoCodeDetector::class.java,
            Scope.MANIFEST_SCOPE,
          ),
        androidSpecific = true,
      )
  }
}
