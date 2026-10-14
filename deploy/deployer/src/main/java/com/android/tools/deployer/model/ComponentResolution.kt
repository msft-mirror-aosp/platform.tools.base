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
package com.android.tools.deployer.model

import com.android.tools.deployer.model.component.AppComponent
import com.android.tools.deployer.model.component.ComponentType

sealed interface ComponentResolution {
  data class Resolved(val component: AppComponent, val reason: String) : ComponentResolution

  data class Ambiguous(val candidates: List<AppComponent>) : ComponentResolution

  data class NoValidCandidates(val candidates: List<AppComponent>) : ComponentResolution

  object NotFound : ComponentResolution
}

fun App.resolveDefaultComponent(type: ComponentType = ComponentType.ACTIVITY): ComponentResolution {
  if (type == ComponentType.ACTIVITY) {
    resolveLauncherEnabledReferences()
  }
  val allComponents = getMatchingComponents(type)
  if (allComponents.isEmpty()) {
    return ComponentResolution.NotFound
  }

  val validCandidates = allComponents.filter { it.isEnabled && it.isExported }
  if (validCandidates.isEmpty()) {
    return ComponentResolution.NoValidCandidates(allComponents)
  }

  // MAIN and LAUNCHER have to be in the same intent filter, as for the platform; one of each in different filters launches nothing.
  val launcherActivities = validCandidates.filter { it.hasIntentFilter(ACTION_MAIN, CATEGORY_LAUNCHER) }

  if (launcherActivities.size == 1) {
    return ComponentResolution.Resolved(launcherActivities[0], "single launcher activity")
  }
  if (launcherActivities.size > 1) {
    return preferredLauncherActivity(launcherActivities)
  }
  if (validCandidates.size == 1) {
    return ComponentResolution.Resolved(validCandidates[0], "single exported activity fallback")
  }

  return ComponentResolution.Ambiguous(allComponents)
}

/**
 * Picks one of several enabled, exported MAIN/LAUNCHER activities the way the platform orders them when the app is opened from its icon
 * (`PackageManager.getLaunchIntentForPackage`): one whose launcher filter also has CATEGORY_DEFAULT, then manifest order. Activities and
 * aliases are treated alike, and with AGP's manifest merger (which puts the app's own components before its libraries'), manifest order
 * already favors the app over e.g. LeakCanary's launcher alias. Android Studio's DefaultActivityLocator makes the same choice.
 *
 * Intent filter `android:priority`, which the platform sorts on first, is not parsed; launcher filters practically never set it.
 */
private fun preferredLauncherActivity(launchers: List<AppComponent>): ComponentResolution.Resolved {
  val withDefault = launchers.firstOrNull { it.hasIntentFilter(ACTION_MAIN, CATEGORY_LAUNCHER, CATEGORY_DEFAULT) }
  if (withDefault != null) {
    return ComponentResolution.Resolved(withDefault, "launcher activity with CATEGORY_DEFAULT")
  }
  return ComponentResolution.Resolved(launchers[0], "first launcher activity in manifest order")
}

private const val ACTION_MAIN = "android.intent.action.MAIN"
private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
private const val CATEGORY_DEFAULT = "android.intent.category.DEFAULT"

/**
 * Resolves resource-valued `android:enabled` (e.g. LeakCanary's `@bool/leak_canary_add_launcher_icon`) on the activities that could be
 * picked as the default, reading `resources.arsc` only for the APKs that have one.
 *
 * This is the only place `isEnabled` of such a component matters, so it is done here rather than in [ApkParser]: [App.fromPaths] runs on
 * every deploy, and would otherwise read the resource table for any app using LeakCanary. Feature splits usually refer to the base APK's
 * resources, which their own table does not have; those stay enabled, as before.
 *
 * TODO: Refactor to an immutable OO design (e.g. a `ResourceResolvedApk` wrapper or `Apk.withResolvedResources()` that produces new
 *   `ManifestActivityInfo` instances) so that `ManifestAppComponentInfo` and `Apk` are not mutated in place.
 */
private fun App.resolveLauncherEnabledReferences() {
  for (apk in getApks()) {
    val pending =
      apk.activities.filter { info ->
        info.enabledResourceId != null &&
          info.isExported &&
          info.intentFilters.any { it.hasAction(ACTION_MAIN) && it.hasCategory(CATEGORY_LAUNCHER) }
      }
    if (pending.isNotEmpty() && !apk.path.isNullOrEmpty()) {
      ApkParser.resolveResourceReferences(apk.path, pending)
    }
  }
}

fun App.resolveSpecificComponent(type: ComponentType = ComponentType.ACTIVITY, activityName: String): ComponentResolution {
  val allComponents = getMatchingComponents(type)
  if (allComponents.isEmpty()) {
    return ComponentResolution.NotFound
  }

  // 1. Exact FQN match
  val exact = allComponents.firstOrNull { it.qualifiedName == activityName }
  if (exact != null) {
    return ComponentResolution.Resolved(exact, "exact match")
  }

  // 2. Relative name (e.g. .SecondActivity or .settings.CastSettingsActivity)
  if (activityName.startsWith(".")) {
    val relative = allComponents.firstOrNull { it.qualifiedName == appId + activityName }
    if (relative != null) {
      return ComponentResolution.Resolved(relative, "relative name match")
    }
  }

  // 3. Suffix / subpackage / simple class name match
  val cleanName = activityName.removePrefix(".")
  val matching = allComponents.filter {
    it.qualifiedName.substringAfterLast('.') == cleanName ||
      it.qualifiedName.endsWith(".$cleanName") ||
      it.qualifiedName == "$appId.$cleanName"
  }

  if (matching.size == 1) {
    val reason = if (matching[0].qualifiedName.substringAfterLast('.') == cleanName) "simple name match" else "subpackage match"
    return ComponentResolution.Resolved(matching[0], reason)
  }

  if (matching.size > 1) {
    val exported = matching.filter { it.isEnabled && it.isExported }
    if (exported.size == 1) {
      return ComponentResolution.Resolved(exported[0], "single exported candidate match")
    }
    return ComponentResolution.Ambiguous(matching)
  }

  return ComponentResolution.NotFound
}

fun App.getShortestComponentName(component: AppComponent, type: ComponentType = ComponentType.ACTIVITY): String {
  val allComponents = getMatchingComponents(type)
  val simpleName = component.qualifiedName.substringAfterLast('.')
  val simpleMatches = allComponents.filter { it.qualifiedName.substringAfterLast('.') == simpleName }
  if (simpleMatches.size == 1) {
    return simpleName
  }
  if (component.qualifiedName.startsWith(appId)) {
    return component.qualifiedName.removePrefix(appId)
  }
  return component.qualifiedName
}
