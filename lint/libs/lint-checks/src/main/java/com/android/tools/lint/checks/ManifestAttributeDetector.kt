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
import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.TAG_ACTIVITY_ALIAS
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScanner
import org.w3c.dom.Attr
import org.w3c.dom.Element

/**
 * Reports manifest attributes in `<activity-alias>` that have no effect.
 *
 * Specifically, reports attributes that are valid for `<activity>`, but have no effect when used on `<activity-alias>`.
 *
 * We could report additional manifest attributes in the future (hence the generic name).
 *
 * See also: [ManifestPermissionAttributeDetector].
 */
class ManifestAttributeDetector : Detector(), XmlScanner {
  override fun getApplicableElements() = listOf(TAG_ACTIVITY_ALIAS)

  override fun visitElement(context: XmlContext, element: Element) {
    val aliasName = element.getAttributeNS(ANDROID_URI, ATTR_NAME)
    if (aliasName.isEmpty()) return

    val attributes = element.attributes ?: return
    for (i in 0 until attributes.length) {
      val attr = attributes.item(i) as? Attr ?: continue
      if (ANDROID_URI != attr.namespaceURI) continue
      val attributeName = attr.localName ?: continue
      if (attributeName.isEmpty()) continue
      if (!attributeName.invalidAttribute) continue

      // No quick-fix to remove the attribute because the developer probably wants the effect of the
      // attribute to apply, so they may need to move it to the target activity, or come up with
      // some other solution.

      context.report(
          Incident(
              ISSUE,
              element,
              context.getLocation(attr),
              "Attribute `$attributeName` on `<activity-alias>` `$aliasName` " +
                  "is invalid, and will be silently ignored. This attribute is always ignored on " +
                  "`<activity-alias>`.",
          )
      )
    }
  }

  companion object {
    @JvmField
    val ISSUE =
        Issue.create(
            id = "InvalidManifestAttribute",
            briefDescription = "Invalid manifest attribute",
            explanation =
                """
          Most manifest attributes can be added to any tag without triggering any build-time \
          warnings or errors, but those attributes may be invalid, and will be silently ignored.

          For example, only a very specific subset of `<activity>` attributes are valid on \
          an `<activity-alias>`; the rest are ignored, which can be confusing.
          """,
            category = Category.CORRECTNESS,
            severity = Severity.WARNING,
            androidSpecific = true,
            implementation = Implementation(ManifestAttributeDetector::class.java, Scope.MANIFEST_SCOPE),
        )
  }
}

// Based on Android SDK: platforms/android-36/data/res/values/attrs_manifest.xml
// And checking:
// https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/com/android/internal/pm/pkg/component/ParsedActivityUtils.java;l=301;drc=c5c6c1a7ca1c73eed444cfcd1511d5fe2244fdbb
// We only report _valid_ activity attributes that are _invalid_ on activity aliases.
// TODO: Rather than hardcoding, we could read (and cache) these from:
//  platforms/android-xx/data/res/values/attrs_manifest.xml
//  See PurposeDeclarationDetector for a similar example.
//  At the time of writing, it looks like activity alias attributes are only parsed using the
//  attributes defined in attrs_manifest.xml. However, the Android Platform code can parse
//  additional attributes that are not defined in attrs_manifest.xml, so hardcoding a set of
//  definitely invalid attributes might be safest.
private val String.invalidAttribute
  get() =
      when (this) {
        "allowEmbedded",
        "allowTaskReparenting",
        "alwaysFocusable",
        "alwaysRetainTaskState",
        "autoRemoveFromRecents",
        "canDisplayOnRemoteDevices",
        "clearTaskOnLaunch",
        "colorMode",
        "configChanges",
        "directBootAware",
        "documentLaunchMode",
        "enableOnBackInvokedCallback",
        "enableVrMode",
        "excludeFromRecents",
        "finishOnCloseSystemDialogs",
        "finishOnTaskLaunch",
        "forceQueryable",
        "hardwareAccelerated",
        "immersive",
        "inheritShowWhenLocked",
        "launchMode",
        "lockTaskMode",
        "maxAspectRatio",
        "maxRecents",
        "minAspectRatio",
        "multiprocess",
        "noHistory",
        "persistableMode",
        "playHomeTransitionSound",
        "preferMinimalPostProcessing",
        "process",
        "recreateOnConfigChanges",
        "relinquishTaskIdentity",
        "requireContentUriPermissionFromCaller",
        "requiredDisplayCategory",
        "resizeableActivity",
        "resumeWhilePausing",
        "rotationAnimation",
        "screenOrientation",
        "showForAllUsers",
        "showOnLockScreen",
        "showWhenLocked",
        "singleUser",
        "splitName",
        "stateNotNeeded",
        "supportsPictureInPicture",
        "systemUserOnly",
        "taskAffinity",
        "theme",
        "turnScreenOn",
        "uiOptions",
        "visibleToInstantApps",
        "windowSoftInputMode" -> true
        else -> false
      }
