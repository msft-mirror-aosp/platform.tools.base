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
  val allComponents = getMatchingComponents(type)
  if (allComponents.isEmpty()) {
    return ComponentResolution.NotFound
  }

  val validCandidates = allComponents.filter { it.isEnabled && it.isExported }
  if (validCandidates.isEmpty()) {
    return ComponentResolution.NoValidCandidates(allComponents)
  }

  val launcherActivities = allComponents.filter {
    it.isEnabled && it.isExported && it.hasAction("android.intent.action.MAIN") && it.hasCategory("android.intent.category.LAUNCHER")
  }

  if (launcherActivities.size == 1) {
    return ComponentResolution.Resolved(launcherActivities[0], "single launcher activity")
  }
  if (launcherActivities.isEmpty() && validCandidates.size == 1) {
    return ComponentResolution.Resolved(validCandidates[0], "single exported activity fallback")
  }

  return ComponentResolution.Ambiguous(allComponents)
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
