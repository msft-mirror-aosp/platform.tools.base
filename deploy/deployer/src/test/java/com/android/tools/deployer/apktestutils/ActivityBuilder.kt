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
package com.android.tools.deployer.apktestutils

/** DSL builder for defining `<activity>` or `<activity-alias>` elements in test manifests. */
class ActivityBuilder(private val pkgName: String, rawName: String) {
  val qualifiedName: String = if (rawName.startsWith(".")) pkgName + rawName else rawName
  private var aliasTarget: String? = null
  var enabled: Boolean = true
  var exported: Boolean? = null
  private val intentFilters = mutableListOf<IntentFilterBuilder>()

  fun aliasOf(targetName: String) {
    aliasTarget = if (targetName.startsWith(".")) pkgName + targetName else targetName
  }

  fun intentFilter(block: IntentFilterBuilder.() -> Unit) {
    val filter = IntentFilterBuilder().apply(block)
    intentFilters.add(filter)
  }

  fun launcher() {
    intentFilter {
      action("android.intent.action.MAIN")
      category("android.intent.category.LAUNCHER")
    }
  }

  fun toXmlElement(): XmlElement {
    val tagName = if (aliasTarget != null) "activity-alias" else "activity"
    val elem = XmlElement(tagName)
    elem.addAttribute(ResourceTypes.ANDROID_URI, "name", qualifiedName)
    if (aliasTarget != null) {
      elem.addAttribute(ResourceTypes.ANDROID_URI, "targetActivity", aliasTarget)
    }
    elem.addAttribute(ResourceTypes.ANDROID_URI, "enabled", enabled)
    val isExported = exported ?: intentFilters.isNotEmpty()
    elem.addAttribute(ResourceTypes.ANDROID_URI, "exported", isExported)

    intentFilters.forEach { elem.addChild(it.toXmlElement()) }
    return elem
  }
}
