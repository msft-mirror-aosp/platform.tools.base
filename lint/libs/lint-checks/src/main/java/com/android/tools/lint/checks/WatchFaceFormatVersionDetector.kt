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
import com.android.SdkConstants.ATTR_VALUE
import com.android.SdkConstants.TAG_PROPERTY
import com.android.SdkConstants.WATCH_FACE_FORMAT_DEFAULT_VERSION
import com.android.SdkConstants.WATCH_FACE_FORMAT_VERSION_PROPERTY
import com.android.tools.lint.checks.WatchFaceFormatUtils.hasDeclarativeWatchFaceFile
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector.XmlScanner
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.isManifestPlaceHolderExpression
import com.android.tools.lint.detector.api.minSdkLessThan
import com.android.tools.lint.detector.api.resolvePlaceHolders
import com.android.utils.XmlUtils
import com.android.xml.AndroidManifest.ATTRIBUTE_NAME
import com.android.xml.AndroidManifest.NODE_APPLICATION
import org.w3c.dom.Element

/**
 * Reports the [WATCH_FACE_FORMAT_VERSION_PROPERTY] property in manifest files if the following conditions hold:
 * - The `android:value` attribute is missing
 * - The `android:value` is invalid (not an integer literal or placeholder)
 * - The declared Watch Face Format version requires a higher `minSdkVersion` than the project's `minSdkVersion`
 *
 * It also reports the `<application>` element if a Declarative Watch Face file (file with a `<WatchFace>` root tag) exists in `res/raw` but
 * the [WATCH_FACE_FORMAT_VERSION_PROPERTY] property is missing.
 */
class WatchFaceFormatVersionDetector : WearDetector(), XmlScanner {
  private var hasWffVersionProperty = false

  override fun beforeCheckFile(context: Context) {
    super.beforeCheckFile(context)
    hasWffVersionProperty = false
  }

  override fun getApplicableElements() = listOf(TAG_PROPERTY)

  override fun visitElement(context: XmlContext, element: Element) {
    if (element.tagName != TAG_PROPERTY) return
    if (element.parentNode.nodeName != NODE_APPLICATION) return
    if (element.getAttributeNS(ANDROID_URI, ATTRIBUTE_NAME) != WATCH_FACE_FORMAT_VERSION_PROPERTY) return

    hasWffVersionProperty = true

    val wffVersionValueAttribute = element.getAttributeNodeNS(ANDROID_URI, ATTR_VALUE)
    if (wffVersionValueAttribute == null) {
      context.report(
        MISSING_VERSION_ISSUE,
        context.getNameLocation(element),
        "The `android:value` attribute is missing",
        fix().set(ANDROID_URI, ATTR_VALUE, WATCH_FACE_FORMAT_DEFAULT_VERSION).build(),
      )
      return
    }

    // It's currently not possible to use a resource reference (e.g. `@string/version`) in the
    // manifest to specify the WFF version. It has to be a literal string or a placeholder,
    // otherwise the watch face will not deploy on the device.
    val isPlaceholder = isManifestPlaceHolderExpression(wffVersionValueAttribute.value)
    val wffVersion =
      if (isPlaceholder) {
        resolvePlaceHolders(context.project, wffVersionValueAttribute.value) ?: return
      } else {
        wffVersionValueAttribute.value
      }

    val versionNumber = wffVersion.toIntOrNull()
    if (versionNumber == null) {
      context.report(INVALID_VERSION_ISSUE, context.getLocation(wffVersionValueAttribute), "The Watch Face Format version is invalid")
      return
    }

    val requiredMinSdk = getRequiredMinSdk(versionNumber) ?: return
    val minSdk = context.project.minSdkVersion.featureLevel

    // For WFF v1, Play accepts minSdk 30 for Watch Face Studio bundles with an embedded runtime.
    if (versionNumber == 1 && minSdk >= 30) {
      return
    }

    if (minSdk < requiredMinSdk) {
      val maxSupportedVersion = minSdk - 32
      val fix =
        if (!isPlaceholder && maxSupportedVersion >= 1) {
          fix().name("Lower version to $maxSupportedVersion").set(ANDROID_URI, ATTR_VALUE, maxSupportedVersion.toString()).build()
        } else {
          null
        }

      val constraint = if (versionNumber == 1) minSdkLessThan(30) else minSdkLessThan(requiredMinSdk)
      context.report(
        Incident(
          VERSION_REQUIRES_HIGHER_MIN_SDK_ISSUE,
          wffVersionValueAttribute,
          context.getLocation(wffVersionValueAttribute),
          "Watch Face Format version $versionNumber requires a `minSdkVersion` of at least $requiredMinSdk",
          fix,
        ),
        constraint,
      )
    }
  }

  override fun afterCheckFile(context: Context) {
    if (!isWearProject) return
    if (hasWffVersionProperty) return
    if (!hasDeclarativeWatchFaceFile(context.project)) return
    val manifest = (context as? XmlContext)?.document ?: return
    val application = XmlUtils.getFirstSubTagByName(manifest.documentElement, NODE_APPLICATION) ?: return
    context.report(MISSING_VERSION_ISSUE, context.getNameLocation(application), "The Watch Face Format version property must be set")
  }

  companion object {
    fun getRequiredMinSdk(wffVersion: Int): Int? = if (wffVersion >= 1) 32 + wffVersion else null

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
          implementation = Implementation(WatchFaceFormatVersionDetector::class.java, Scope.MANIFEST_SCOPE),
          androidSpecific = true,
        )
        .addMoreInfo("https://developer.android.com/training/wearables/wff/features")

    @JvmField
    val INVALID_VERSION_ISSUE =
      Issue.create(
        id = "WatchFaceFormatInvalidVersion",
        briefDescription = "The Watch Face Format version is invalid",
        explanation =
          """
               The Watch Face Format version must be an integer literal or a placeholder and cannot reference a resource.
            """,
        category = Category.CORRECTNESS,
        priority = 7,
        severity = Severity.ERROR,
        moreInfo = "https://developer.android.com/training/wearables/wff/features",
        implementation = Implementation(WatchFaceFormatVersionDetector::class.java, Scope.MANIFEST_SCOPE),
        androidSpecific = true,
      )

    @JvmField
    val VERSION_REQUIRES_HIGHER_MIN_SDK_ISSUE =
      Issue.create(
        id = "WatchFaceFormatRequiresHigherMinSdk",
        briefDescription = "Watch Face Format version requires higher `minSdkVersion`",
        explanation =
          """
          The Watch Face Format version specified in the manifest is not supported by the project's `minSdkVersion`. \
          Google Play requires each Watch Face Format version to target a compatible minimum SDK version:

          - Version 1 requires `minSdkVersion` 33 (or 30 for Watch Face Studio bundles with an embedded runtime)
          - Version 2 requires `minSdkVersion` 34
          - Version 3 requires `minSdkVersion` 35
          - Version 4 requires `minSdkVersion` 36
          - Version 5 requires `minSdkVersion` 37

          Either increase the module's `minSdkVersion` or lower the declared Watch Face Format version.
          """,
        category = Category.CORRECTNESS,
        priority = 7,
        severity = Severity.ERROR,
        moreInfo = "https://developer.android.com/training/wearables/wff/features",
        implementation = Implementation(WatchFaceFormatVersionDetector::class.java, Scope.MANIFEST_SCOPE),
        androidSpecific = true,
      )
  }
}
