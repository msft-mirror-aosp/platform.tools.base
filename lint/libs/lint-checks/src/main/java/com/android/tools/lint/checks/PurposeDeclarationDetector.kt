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
import com.android.SdkConstants.ATTR_MAX_SDK_VERSION
import com.android.SdkConstants.ATTR_MIN_SDK_VERSION
import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.TAG_PERMISSION
import com.android.SdkConstants.TAG_PURPOSE
import com.android.SdkConstants.TAG_USES_PERMISSION
import com.android.SdkConstants.TAG_USES_PERMISSION_SDK_23
import com.android.SdkConstants.TAG_VALID_PURPOSE
import com.android.ide.common.util.toPathString
import com.android.sdklib.IAndroidTarget.PERMISSION_VERSIONS
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Project
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlScanner
import com.android.utils.XmlUtils.getFirstSubTag
import com.android.utils.XmlUtils.getNextTag
import com.android.utils.XmlUtils.getSubTagsByName
import com.google.common.annotations.VisibleForTesting
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.START_TAG

/**
 * The detector flags cases where an app requests a permission that requires purpose but fails to
 * provide one in the manifest. The check applies this rule across all appropriate SDK versions by
 * taking into account the app's targetSdkVersion and various other parameters.
 */
class PurposeDeclarationDetector : Detector(), XmlScanner {
  override fun checkMergedProject(context: Context) {
    // Purpose declarations is only supported from SDK version 37.
    if (context.mainProject.targetSdkVersion.featureLevel < 37) return

    val root = context.mainProject.mergedManifest?.documentElement ?: return
    var element = getFirstSubTag(root)
    while (element != null) {
      if (element.tagName == TAG_USES_PERMISSION || element.tagName == TAG_USES_PERMISSION_SDK_23) {
        evaluatePermissionRequest(context, element)
      }
      element = getNextTag(element)
    }
  }

  /**
   * Evaluate whether the permission requires purposes. If yes, ensure valid purpose(s) are declared
   * for all applicable SDKs.
   */
  private fun evaluatePermissionRequest(context: Context, element: Element) {
    val permissionName = element.getAttributeNS(ANDROID_URI, ATTR_NAME) ?: return

    val permissionsMap = getPermissionsMap(context.mainProject)
    val permissionInfo = permissionsMap[permissionName] ?: return

    val requiresPurposeMinSdkVersion = permissionInfo.requiresPurposeSdkRange.min
    val projectMinSdk = context.mainProject.minSdkVersion.featureLevel
    val usesPermissionMinSdkVersion =
      element.getAttributeNS(ANDROID_URI, ATTR_MIN_SDK_VERSION).toIntOrNull()
        ?: MIN_SDK_VERSION_DEFAULT
    // Max of the above parameters provides the earliest SDK version for which the app is required
    // to provide purpose for this permission,
    val minSdk = maxOf(requiresPurposeMinSdkVersion, projectMinSdk, usesPermissionMinSdkVersion)

    val requiresPurposeMaxSdkVersion = permissionInfo.requiresPurposeSdkRange.max
    val projectTargetSdk = context.mainProject.targetSdkVersion.featureLevel
    val usesPermissionMaxSdkVersion =
      element.getAttributeNS(ANDROID_URI, ATTR_MAX_SDK_VERSION).toIntOrNull()
        ?: MAX_SDK_VERSION_DEFAULT
    // Min of the above parameters provides the last SDK version for which the app is required to
    // provide purpose for this permission.
    val maxSdk = minOf(requiresPurposeMaxSdkVersion, projectTargetSdk, usesPermissionMaxSdkVersion)

    // No SDK range targeted by this app requires a purpose to be declared for the permission.
    if (minSdk > maxSdk) {
      return
    }

    val requiredRange = SdkRange(minSdk, maxSdk)
    val purposeElements = getSubTagsByName(element, TAG_PURPOSE).toList()

    // Handle the most common case: no <purpose> tags are declared at all.
    if (purposeElements.isEmpty()) {
      val message =
        "`$permissionName` will not be granted due to missing `<purpose>`. Possible valid purposes: " +
          permissionInfo.validPurposes.keys.joinToString()
      context.report(MISSING_PURPOSE, context.getLocation(element), message)
      return
    }

    // Calculate the effective SDK range for each declared purpose and merge them into a set of
    // disjoint intervals representing all SDKs covered by all the declared valid purpose(s).
    val validCoverageRanges = getValidCoverageRanges(purposeElements, permissionInfo)

    // Find the gaps by subtracting the valid coverage range(s) from the required range.
    val uncoveredRanges = subtractRanges(requiredRange, validCoverageRanges)

    // Report all the SDK range(s) that do not have a valid purpose declared. For now, simply show
    // all possible purposes. We could improve the error message in the future to provide customized
    // valid purposes per SDK level if we receive any user feedback and/or future use cases warrant
    // it.
    if (uncoveredRanges.isNotEmpty()) {
      val formattedUncoveredRanges = uncoveredRanges.joinToString { formatRange(it) }
      val message =
        "`$permissionName` will not be granted on API level(s) $formattedUncoveredRanges due to no " +
          "valid `<purpose>`. Ensure valid purpose(s) cover all API level(s). Possible valid " +
          "purposes: ${permissionInfo.validPurposes.keys.joinToString()}"
      context.report(MISSING_PURPOSE, context.getLocation(element), message)
    }
  }

  /** Calculates the union of all valid SDK ranges from the declared purpose elements. */
  private fun getValidCoverageRanges(
    purposeElements: List<Element>,
    permissionInfo: PermissionInfo,
  ): List<SdkRange> {
    val effectiveRanges =
      purposeElements.mapNotNull { purposeElement ->
        val declaredPurposeName =
          purposeElement.getAttributeNS(ANDROID_URI, ATTR_NAME).takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        val platformRange =
          permissionInfo.validPurposes[declaredPurposeName] ?: return@mapNotNull null

        // The purpose's own min/max SDK attributes need to be considered.
        val purposeMinSdk =
          purposeElement.getAttributeNS(ANDROID_URI, ATTR_MIN_SDK_VERSION).toIntOrNull()
            ?: MIN_SDK_VERSION_DEFAULT
        val purposeMaxSdk =
          purposeElement.getAttributeNS(ANDROID_URI, ATTR_MAX_SDK_VERSION).toIntOrNull()
            ?: MAX_SDK_VERSION_DEFAULT

        // The effective range for this single purpose is the intersection of its
        // platform-defined validity and its element-defined validity.
        val effectiveMin = maxOf(platformRange.min, purposeMinSdk)
        val effectiveMax = minOf(platformRange.max, purposeMaxSdk)

        if (effectiveMin <= effectiveMax) SdkRange(effectiveMin, effectiveMax) else null
      }

    return mergeRanges(effectiveRanges)
  }

  /** Merges a list of potentially overlapping SdkRanges into a list of disjoint ranges. */
  private fun mergeRanges(ranges: List<SdkRange>): List<SdkRange> {
    if (ranges.size <= 1) return ranges

    val sortedRanges = ranges.sortedBy { it.min }
    val merged = mutableListOf<SdkRange>()
    var currentMerge = sortedRanges.first()

    for (i in 1 until sortedRanges.size) {
      val nextRange = sortedRanges[i]
      // Note: [26, 27] should be merged for both cases: [27, 28] and [28, 29]
      if (nextRange.min <= currentMerge.max + 1) {
        currentMerge = SdkRange(currentMerge.min, maxOf(currentMerge.max, nextRange.max))
      } else {
        merged.add(currentMerge)
        currentMerge = nextRange
      }
    }
    merged.add(currentMerge)

    return merged
  }

  /**
   * Subtracts a list of sorted, disjoint ranges (`toSubtract`) from a main range (`source`).
   * Returns a list of ranges representing the parts of `source` that were not covered.
   */
  private fun subtractRanges(source: SdkRange, toSubtract: List<SdkRange>): List<SdkRange> {
    val uncovered = mutableListOf<SdkRange>()
    var currentSdk = source.min

    for (subtractRange in toSubtract) {
      // If there's a gap before the next subtraction range starts, add it.
      if (currentSdk < subtractRange.min) {
        uncovered.add(SdkRange(currentSdk, minOf(source.max, subtractRange.min - 1)))
      }
      // Move the pointer to the end of the subtracted range.
      currentSdk = maxOf(currentSdk, subtractRange.max + 1)
      if (currentSdk > source.max) break
    }

    // If the pointer hasn't reached the end of the source range, add the final remaining part.
    if (currentSdk <= source.max) {
      uncovered.add(SdkRange(currentSdk, source.max))
    }

    return uncovered
  }

  /** Formats an SdkRange for error messages. */
  private fun formatRange(range: SdkRange): String {
    return if (range.min == range.max) "${range.min}" else "${range.min}-${range.max}"
  }

  companion object {
    private const val ATTR_MIN = "min"
    private const val ATTR_MAX = "max"
    private const val ATTR_REQUIRES_PURPOSE_MIN = "requiresPurposeMin"
    private const val ATTR_REQUIRES_PURPOSE_MAX = "requiresPurposeMax"
    private const val MIN_SDK_VERSION_DEFAULT = 1

    // For convenience to avoid overflow errors with interval math when adding 1
    private const val MAX_SDK_VERSION_DEFAULT = 999999

    private val IMPLEMENTATION =
      Implementation(PurposeDeclarationDetector::class.java, Scope.MANIFEST_SCOPE)

    @JvmField
    val MISSING_PURPOSE: Issue =
      Issue.create(
        id = "MissingPurpose",
        briefDescription = "Missing purpose for permission",
        explanation =
          """
              Some permissions require a valid `<purpose>` child element(s) to be included under \
              the `<uses-permission>` declaration.
              """,
        category = Category.CORRECTNESS,
        priority = 10,
        severity = Severity.FATAL,
        androidSpecific = true,
        implementation = IMPLEMENTATION,
      )

    private data class SdkRange(val min: Int, val max: Int)

    /** Captures the SDKs that require purpose as well valid purposes and their SDK validity. */
    private data class PermissionInfo(
      val requiresPurposeSdkRange: SdkRange,
      val validPurposes: Map<String, SdkRange>,
    )

    // A map where the key is the build hash and the value is a map containing information in the
    // permission versions XML file from the SDK; the keys of the inner map are permission names
    // and value is the associated metadata for that permission.
    private val cache = ConcurrentHashMap<String, Map<String, PermissionInfo>>()

    @VisibleForTesting
    fun clearPermissionsMap() {
      cache.clear()
    }

    private fun getPermissionsMap(project: Project): Map<String, PermissionInfo> {
      val targetHash =
        project.buildTarget?.hashString()
          // If we can't get a hash, we shouldn't cache. Compute directly.
          ?: return computePermissionsMap(project)

      // Atomically get the existing entry or compute and store a new one.
      // This is thread-safe.
      return cache.computeIfAbsent(targetHash) { computePermissionsMap(project) }
    }

    private fun computePermissionsMap(project: Project): Map<String, PermissionInfo> {
      // Uses compileSdkVersion of app to get access to the latest permission versions file.
      val dataFile =
        project.buildTarget
          ?.getPath(PERMISSION_VERSIONS)
          ?.let { File(it.toString()) }
          ?.takeIf { it.exists() }

      // Handle gracefully if file doesn't exist. Using empty map signifies no permissions
      // require purpose.
      if (dataFile == null) {
        return emptyMap()
      }

      val mapBuilder = mutableMapOf<String, PermissionInfo>()
      try {
        val client = project.client
        val path = dataFile.toPathString()
        val parser = client.createXmlPullParser(path) ?: return emptyMap()

        while (parser.next() != END_DOCUMENT) {
          if (parser.eventType == START_TAG && parser.name == TAG_PERMISSION) {
            val permissionName =
              parser.getAttributeValue(null, ATTR_NAME).takeIf { !it.isNullOrEmpty() } ?: continue
            val requiresMinSdk =
              parser.getAttributeValue(null, ATTR_REQUIRES_PURPOSE_MIN)?.toIntOrNull() ?: continue
            val requiresMaxSdk =
              parser.getAttributeValue(null, ATTR_REQUIRES_PURPOSE_MAX)?.toIntOrNull()
                ?: MAX_SDK_VERSION_DEFAULT

            val purposesMap = mutableMapOf<String, SdkRange>()
            val depth = parser.depth
            while (true) {
              val event = parser.next()
              if (
                event == END_DOCUMENT || (event == XmlPullParser.END_TAG && parser.depth == depth)
              ) {
                break
              } else if (event != START_TAG) {
                continue
              } else if (parser.name == TAG_VALID_PURPOSE) {
                val purposeName =
                  parser.getAttributeValue(null, ATTR_NAME).takeIf { !it.isNullOrEmpty() }
                    ?: continue
                val purposeMinSdk =
                  parser.getAttributeValue(null, ATTR_MIN)?.toIntOrNull() ?: continue
                val purposeMaxSdk =
                  parser.getAttributeValue(null, ATTR_MAX)?.toIntOrNull() ?: MAX_SDK_VERSION_DEFAULT
                purposesMap[purposeName] = SdkRange(min = purposeMinSdk, max = purposeMaxSdk)
              }
            }

            // Add permission to map only if the element's metadata is not malformed, and it
            // contains
            // at least one valid purpose.
            if (purposesMap.isNotEmpty()) {
              mapBuilder[permissionName] =
                PermissionInfo(
                  requiresPurposeSdkRange = SdkRange(min = requiresMinSdk, max = requiresMaxSdk),
                  validPurposes = purposesMap.toMap(),
                )
            }
          }
        }
      } catch (_: Exception) {
        return emptyMap()
      }

      return mapBuilder.toMap()
    }
  }
}
