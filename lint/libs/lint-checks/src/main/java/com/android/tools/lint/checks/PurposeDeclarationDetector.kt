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
import com.android.SdkConstants.ATTR_PURPOSE_STRING
import com.android.SdkConstants.TAG_PERMISSION
import com.android.SdkConstants.TAG_SPECIFIC_PURPOSE
import com.android.SdkConstants.TAG_USES_PERMISSION
import com.android.SdkConstants.TAG_USES_PERMISSION_SDK_23
import com.android.SdkConstants.TAG_VALID_SPECIFIC_PURPOSE
import com.android.ide.common.util.toPathString
import com.android.resources.ResourceType
import com.android.resources.ResourceUrl
import com.android.sdklib.IAndroidTarget.PERMISSION_VERSIONS
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.LocationType
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
 * provide appropriate purpose elements. The check applies this rule across all appropriate SDK
 * versions by taking into account the app's targetSdkVersion and various other parameters.
 */
class PurposeDeclarationDetector : Detector(), XmlScanner {
  override fun checkMergedProject(context: Context) {
    // Purpose declarations is only supported from SDK version 37.
    if (context.mainProject.targetSdkVersion.featureLevel < ANDROID_C_SDK_VERSION) return

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
   * Evaluate whether the permission requires purposes. If yes, ensure appropriate purpose elements
   * are declared for all applicable SDKs.
   */
  private fun evaluatePermissionRequest(context: Context, element: Element) {
    // Check if permission requires purpose.
    val permissionName = element.getAttributeNS(ANDROID_URI, ATTR_NAME) ?: return
    val permissionsMap = getPermissionsMap(context.mainProject)
    val permissionInfo = permissionsMap[permissionName] ?: return

    // Compute relevant parameters for identifying the target SDK range for which purpose is needed.
    val projectMinSdk = context.mainProject.minSdkVersion.featureLevel
    val usesPermissionMinSdkVersion =
      element.getAttributeNS(ANDROID_URI, ATTR_MIN_SDK_VERSION).toIntOrNull()
        ?: MIN_SDK_VERSION_DEFAULT
    val projectTargetSdk = context.mainProject.targetSdkVersion.featureLevel
    val usesPermissionMaxSdkVersion =
      element.getAttributeNS(ANDROID_URI, ATTR_MAX_SDK_VERSION).toIntOrNull()
        ?: MAX_SDK_VERSION_DEFAULT
    val usesPermissionSdkRange = SdkRange(usesPermissionMinSdkVersion, usesPermissionMaxSdkVersion)

    val missingPurposeErrorMessages = mutableListOf<String>()

    // Validate specific purpose declarations.
    val specificPurposeError =
      validateDeclaredPurposes(
        element,
        permissionInfo.validSpecificPurposes,
        TAG_SPECIFIC_PURPOSE,
        computeRequiresPurposeSdkRange(
          permissionInfo.requiresSpecificPurposeSdkRange,
          projectMinSdk,
          projectTargetSdk,
          usesPermissionSdkRange,
        ),
      )
    specificPurposeError?.let { missingPurposeErrorMessages.add(it) }

    // Validate purpose string declaration (only required for apps)
    if (!context.mainProject.isLibrary) {
      val purposeStringError =
        validatePurposeResourceString(
          context,
          element,
          computeRequiresPurposeSdkRange(
            permissionInfo.requiresPurposeStringSdkRange,
            projectMinSdk,
            projectTargetSdk,
            usesPermissionSdkRange,
          ),
        )
      purposeStringError?.let { missingPurposeErrorMessages.add(it) }
    }

    // Combine all error messages for MISSING_PURPOSE issue and report as one.
    reportMissingPurposeErrors(context, permissionName, element, missingPurposeErrorMessages)
  }

  private fun computeRequiresPurposeSdkRange(
    purposeTypeSdkRange: SdkRange,
    projectMinSdk: Int,
    projectTargetSdk: Int,
    usesPermissionSdkRange: SdkRange,
  ): SdkRange {
    val minPurposeStringSdk =
      maxOf(purposeTypeSdkRange.min, projectMinSdk, usesPermissionSdkRange.min)
    val maxPurposeStringSdk =
      minOf(purposeTypeSdkRange.max, projectTargetSdk, usesPermissionSdkRange.max)
    return SdkRange(minPurposeStringSdk, maxPurposeStringSdk)
  }

  private fun validateDeclaredPurposes(
    permissionElement: Element,
    possiblePurposes: Map<String, SdkRange>,
    purposeTag: String,
    requiredRange: SdkRange,
  ): String? {
    // No SDK range targeted by this app requires a purpose to be declared for the permission.
    if (requiredRange.min > requiredRange.max) {
      return null
    }

    val purposeElements = getSubTagsByName(permissionElement, purposeTag).toList()

    // Handle the most common case: no purposes are declared at all.
    if (purposeElements.isEmpty()) {
      return "missing one or more `<$purposeTag>` tags required for API level(s) ${formatRange(requiredRange)}"
    }

    // Calculate the effective SDK range for each declared purpose and merge them into a set of
    // disjoint intervals representing all SDKs covered by all the declared valid purpose(s).
    val validCoverageRanges = getValidCoverageRanges(purposeElements, possiblePurposes)

    // Find the gaps by subtracting the valid coverage range(s) from the required range.
    val uncoveredRanges = subtractRanges(requiredRange, validCoverageRanges)

    // Report all the SDK range(s) that do not have a valid purpose declared. For now, simply show
    // all possible purposes. We could improve the error message in the future to provide customized
    // valid purposes per SDK level if we receive any user feedback and/or future use cases warrant
    // it.
    if (uncoveredRanges.isNotEmpty()) {
      val formattedUncoveredRanges = uncoveredRanges.joinToString { formatRange(it) }
      return "missing one or more `<$purposeTag>` tags required for API level(s) $formattedUncoveredRanges"
    }

    return null
  }

  private fun validatePurposeResourceString(
    context: Context,
    permissionElement: Element,
    requiredRange: SdkRange,
  ): String? {
    // No SDK range targeted by this app requires a purpose string resource to be declared.
    if (requiredRange.min > requiredRange.max) {
      return null
    }

    val purposeStringRef = permissionElement.getAttributeNS(ANDROID_URI, ATTR_PURPOSE_STRING)
    if (purposeStringRef.isEmpty()) {
      return "missing `purposeString` attribute"
    }

    val resourceUrl = ResourceUrl.parse(purposeStringRef)
    if (resourceUrl == null || resourceUrl.type != ResourceType.STRING) {
      val attrNode = permissionElement.getAttributeNodeNS(ANDROID_URI, ATTR_PURPOSE_STRING)
      // More user-friendly to highlight the incorrectly defined attribute rather than report it as
      // part of the generic error message referencing <uses-permission>. So, we report instead of
      // returning the error message.
      context.report(
        MISSING_PURPOSE,
        context.getLocation(attrNode, LocationType.VALUE),
        "`purposeString` must reference a string resource (e.g. `@string/my_purpose_resource`)",
      )
    }

    return null
  }

  /** Calculates the union of all valid SDK ranges from the declared purpose elements. */
  private fun getValidCoverageRanges(
    purposeElements: List<Element>,
    validPurposesMap: Map<String, SdkRange>,
  ): List<SdkRange> {
    val effectiveRanges =
      purposeElements.mapNotNull { purposeElement ->
        val declaredPurposeName =
          purposeElement.getAttributeNS(ANDROID_URI, ATTR_NAME).takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        val platformRange = validPurposesMap[declaredPurposeName] ?: return@mapNotNull null

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

  private fun reportMissingPurposeErrors(
    context: Context,
    permissionName: String,
    permissionElement: Element,
    missingPurposeErrorMessages: List<String>,
  ) {
    if (missingPurposeErrorMessages.isEmpty()) {
      return
    }

    val detailsString = missingPurposeErrorMessages.joinToString(separator = "; ")
    val finalMessage =
      "$permissionName permission is missing required purpose attributes/elements: $detailsString"

    context.report(MISSING_PURPOSE, context.getLocation(permissionElement), finalMessage)
  }

  companion object {
    private const val ATTR_MIN_SDK = "minSdkVersion"
    private const val ATTR_MAX_SDK = "maxSdkVersion"
    private const val ATTR_REQUIRES_SPECIFIC_PURPOSE_MIN =
      "requiresSpecificPurposeMinTargetSdkVersion"
    private const val ATTR_REQUIRES_SPECIFIC_PURPOSE_MAX = "requiresSpecificPurposeMaxSdkVersion"
    private const val ATTR_REQUIRES_PURPOSE_STRING_MIN = "requiresPurposeStringMinTargetSdkVersion"
    private const val ATTR_REQUIRES_PURPOSE_STRING_MAX = "requiresPurposeStringMaxSdkVersion"
    private const val MIN_SDK_VERSION_DEFAULT = 1
    private const val ANDROID_C_SDK_VERSION = 37

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
              When requesting a permission that requires purpose declaration, appropriate tags \
              and/or attributes must be defined based on the required purpose types. Failure to do \
              so will lead to unexpected runtime issues.

              Specific Purpose: If a permission requires a specific purpose, at least one \
              `<specific-purpose>` child tag must be declared. This tag must use appropriate \
              preset valid purpose string found in the permission's documentation. The declared \
              purpose(s) must cover all API levels for which the permission requires purpose.

              Purpose String: If a permission requires a purpose string, a valid \
              `android:purposeString` attribute should be declared. This attribute must reference a \
              localized string resource that is under 300 characters (150 recommended) for all \
              locales the app supports as the text is displayed on user-facing surfaces.
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
      val requiresSpecificPurposeSdkRange: SdkRange,
      val requiresPurposeStringSdkRange: SdkRange,
      val validSpecificPurposes: Map<String, SdkRange>,
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
            val requiresSpecificPurposeMinSdk =
              parser.getAttributeValue(null, ATTR_REQUIRES_SPECIFIC_PURPOSE_MIN)?.toIntOrNull()
                ?: MAX_SDK_VERSION_DEFAULT
            val requiresSpecificPurposeMaxSdk =
              parser.getAttributeValue(null, ATTR_REQUIRES_SPECIFIC_PURPOSE_MAX)?.toIntOrNull()
                ?: MAX_SDK_VERSION_DEFAULT
            val requiresPurposeStringMinSdk =
              parser.getAttributeValue(null, ATTR_REQUIRES_PURPOSE_STRING_MIN)?.toIntOrNull()
                ?: MAX_SDK_VERSION_DEFAULT
            val requiresPurposeStringMaxSdk =
              parser.getAttributeValue(null, ATTR_REQUIRES_PURPOSE_STRING_MAX)?.toIntOrNull()
                ?: MAX_SDK_VERSION_DEFAULT

            if (
              requiresSpecificPurposeMinSdk == MAX_SDK_VERSION_DEFAULT &&
                requiresPurposeStringMinSdk == MAX_SDK_VERSION_DEFAULT
            ) {
              continue
            }

            val specificPurposesMap = mutableMapOf<String, SdkRange>()
            val depth = parser.depth
            while (true) {
              val event = parser.next()
              if (
                event == END_DOCUMENT || (event == XmlPullParser.END_TAG && parser.depth == depth)
              ) {
                break
              } else if (event != START_TAG) {
                continue
              } else if (parser.name == TAG_VALID_SPECIFIC_PURPOSE) {
                val purposeName =
                  parser.getAttributeValue(null, ATTR_NAME).takeIf { !it.isNullOrEmpty() }
                    ?: continue
                val purposeMinSdk =
                  parser.getAttributeValue(null, ATTR_MIN_SDK)?.toIntOrNull() ?: continue
                val purposeMaxSdk =
                  parser.getAttributeValue(null, ATTR_MAX_SDK)?.toIntOrNull()
                    ?: MAX_SDK_VERSION_DEFAULT
                specificPurposesMap[purposeName] =
                  SdkRange(min = purposeMinSdk, max = purposeMaxSdk)
              }
            }

            // Add permission to map only if the element's metadata is not malformed, and it
            // contains at least one valid purpose and/or requires purpose string.
            if (
              specificPurposesMap.isNotEmpty() ||
                requiresPurposeStringMinSdk != MAX_SDK_VERSION_DEFAULT
            ) {
              mapBuilder[permissionName] =
                PermissionInfo(
                  requiresSpecificPurposeSdkRange =
                    SdkRange(
                      min = requiresSpecificPurposeMinSdk,
                      max = requiresSpecificPurposeMaxSdk,
                    ),
                  requiresPurposeStringSdkRange =
                    SdkRange(min = requiresPurposeStringMinSdk, max = requiresPurposeStringMaxSdk),
                  validSpecificPurposes = specificPurposesMap.toMap(),
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
