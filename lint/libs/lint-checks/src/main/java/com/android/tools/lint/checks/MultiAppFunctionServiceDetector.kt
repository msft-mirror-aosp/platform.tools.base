/*
 * Copyright (C) 2026 The Android Open Source Project
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
import com.android.SdkConstants.ATTR_ENABLED
import com.android.SdkConstants.ATTR_NAME
import com.android.SdkConstants.TAG_ACTION
import com.android.SdkConstants.TAG_APPLICATION
import com.android.SdkConstants.TAG_INTENT_FILTER
import com.android.SdkConstants.TAG_SERVICE
import com.android.SdkConstants.VALUE_FALSE
import com.android.SdkConstants.VALUE_TRUE
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.ResourceItem
import com.android.ide.common.resources.ResourceRepository
import com.android.ide.common.resources.configuration.VersionQualifier
import com.android.resources.ResourceType
import com.android.resources.ResourceUrl
import com.android.tools.lint.client.api.ResourceRepositoryScope
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.LocationType
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlScanner
import com.android.utils.XmlUtils.getFirstSubTagByName
import com.android.utils.XmlUtils.getSubTagsByName
import org.w3c.dom.Element

/**
 * Reports `<service>` elements in the merged manifest if the following conditions hold:
 * - the service has an intent filter with action `android.app.appfunctions.AppFunctionService`
 * - for some API level in [max(minSdk, 34), 36], the application and more than one such service are
 *   enabled
 *
 * Why: prior to API 37, the platform only indexes a single `AppFunctionService`.
 */
class MultiAppFunctionServiceDetector : Detector(), XmlScanner {

  override fun checkMergedProject(context: Context) {
    val project = context.mainProject
    if (project.isLibrary) {
      return
    }
    val minSdk = project.minSdkVersion.featureLevel
    if (minSdk >= MULTI_SERVICE_SDK) {
      return
    }

    val root = project.mergedManifest?.documentElement ?: return
    val application = getFirstSubTagByName(root, TAG_APPLICATION) ?: return

    val services = getSubTagsByName(application, TAG_SERVICE).filter(::isAppFunctionService)
    if (services.size <= 1) {
      return
    }

    val resources by lazy {
      context.client.getResources(project, ResourceRepositoryScope.ALL_DEPENDENCIES)
    }

    val conflictingServices = linkedSetOf<Element>()
    val startSdk = maxOf(minSdk, MIN_APP_FUNCTION_SDK)
    for (sdk in startSdk until MULTI_SERVICE_SDK) {
      if (!isEnabledAtSdk(application, sdk) { resources }) {
        continue
      }
      val enabledServices = services.filter { isEnabledAtSdk(it, sdk) { resources } }
      if (enabledServices.size > 1) {
        conflictingServices.addAll(enabledServices)
      }
    }

    if (conflictingServices.isNotEmpty()) {
      val serviceList = conflictingServices.toList()
      val primaryService = serviceList.first()
      val primaryLocation = context.getLocation(primaryService, LocationType.NAME)
      var currLocation = primaryLocation
      for (i in 1 until serviceList.size) {
        val secondaryLocation = context.getLocation(serviceList[i], LocationType.NAME)
        secondaryLocation.message = "Also enabled here"
        currLocation.secondary = secondaryLocation
        currLocation = secondaryLocation
      }

      val message =
        "Multiple `AppFunctionService` declarations are enabled prior to API level 37, " +
          "where the platform only indexes a single `AppFunctionService`"
      context.report(
        Incident(ISSUE, primaryLocation.source ?: primaryService, primaryLocation, message)
      )
    }
  }

  private fun isAppFunctionService(service: Element): Boolean {
    for (intentFilter in getSubTagsByName(service, TAG_INTENT_FILTER)) {
      for (action in getSubTagsByName(intentFilter, TAG_ACTION)) {
        val actionName = action.getAttributeNS(ANDROID_URI, ATTR_NAME)
        if (actionName == APP_FUNCTION_SERVICE_ACTION) {
          return true
        }
      }
    }
    return false
  }

  private fun isEnabledAtSdk(
    element: Element,
    sdk: Int,
    resourcesProvider: () -> ResourceRepository,
  ): Boolean {
    val enabledAttr = element.getAttributeNodeNS(ANDROID_URI, ATTR_ENABLED) ?: return true
    val value = enabledAttr.value.trim()
    if (value.isEmpty()) {
      return true
    }
    return resolveBooleanValueAtSdk(value, sdk, resourcesProvider)
  }

  /**
   * Returns `true` only if [value] unambiguously resolves to `true` at [sdk]. Returns `false` if it
   * resolves to `false` or cannot be statically determined, erring on the side of avoiding false
   * positives.
   */
  private fun resolveBooleanValueAtSdk(
    value: String,
    sdk: Int,
    resourcesProvider: () -> ResourceRepository,
    depth: Int = 0,
  ): Boolean {
    if (value.equals(VALUE_TRUE, ignoreCase = true)) {
      return true
    }
    if (value.equals(VALUE_FALSE, ignoreCase = true)) {
      return false
    }
    if (depth >= MAX_RESOURCE_INDIRECTION) {
      return false
    }

    // Only resolve app/library @bool resources; ALL_DEPENDENCIES does not index android: framework
    // resources.
    val resourceUrl = ResourceUrl.parse(value) ?: return false
    if (resourceUrl.type != ResourceType.BOOL || resourceUrl.isFramework) {
      return false
    }

    val items =
      resourcesProvider().getResources(ResourceNamespace.TODO(), ResourceType.BOOL, resourceUrl.name)
    // Bail out if any definition uses non-version qualifiers, which take precedence over API
    // version qualifiers during resource resolution.
    if (
      items.isEmpty() ||
        items.any { item -> item.configuration.any { it !is VersionQualifier } }
    ) {
      return false
    }

    fun ResourceItem.version() = configuration.versionQualifier?.androidApiLevel?.majorVersion ?: 0

    // Select the resource definition from the highest matching version qualifier (<= sdk).
    val candidates = items.filter { it.version() <= sdk }
    val maxVersion = candidates.maxOfOrNull { it.version() } ?: return false
    // ALL_DEPENDENCIES returns both library and app definitions without deduplicating overrides;
    // bail out if multiple definitions match at the winning version.
    val bestItem = candidates.singleOrNull { it.version() == maxVersion } ?: return false
    val itemValue = bestItem.resourceValue?.value?.trim() ?: return false
    return resolveBooleanValueAtSdk(itemValue, sdk, resourcesProvider, depth + 1)
  }

  companion object {
    const val APP_FUNCTION_SERVICE_ACTION = "android.app.appfunctions.AppFunctionService"

    private const val MIN_APP_FUNCTION_SDK = 34
    private const val MULTI_SERVICE_SDK = 37
    private const val MAX_RESOURCE_INDIRECTION = 2

    private val IMPLEMENTATION =
      Implementation(MultiAppFunctionServiceDetector::class.java, Scope.MANIFEST_SCOPE)

    @JvmField
    val ISSUE: Issue =
      Issue.create(
        id = "MultiAppFunctionService",
        briefDescription = "Multiple enabled AppFunctionServices prior to API level 37",
        explanation =
          """
          Prior to API level 37, the platform only expects a single `AppFunctionService` when indexing \
          app functions. Declaring more than one enabled `AppFunctionService` (including the \
          sidecar `com.android.extensions.appfunctions.AppFunctionService` on API level 34 to 36) \
          means only one of them will be indexed, leading to unexpected behavior.

          If your app declares multiple `AppFunctionService` components in `AndroidManifest.xml` \
          (for example, to transition between the extensions sidecar on API level 34-35, a single \
          platform service on API level 36, or multiple platform services on API level 37+), use \
          `android:enabled` with version-qualified boolean resources (such as `values-v36` or \
          `values-v37`) so that at most one `AppFunctionService` is enabled on any API level \
          below 37.
          """,
        category = Category.CORRECTNESS,
        priority = 6,
        severity = Severity.ERROR,
        androidSpecific = true,
        implementation = IMPLEMENTATION,
      )
  }
}
