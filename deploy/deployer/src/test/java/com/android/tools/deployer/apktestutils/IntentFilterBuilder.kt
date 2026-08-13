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

/** DSL builder for defining intent filters on components in test manifests. */
class IntentFilterBuilder {
  val actions = mutableSetOf<String>()
  val categories = mutableSetOf<String>()

  fun action(name: String) {
    actions.add(name)
  }

  fun category(name: String) {
    categories.add(name)
  }

  fun toXmlElement(): XmlElement {
    val filter = XmlElement("intent-filter")
    actions.forEach { actionName -> filter.addChild(XmlElement("action").addAttribute(ResourceTypes.ANDROID_URI, "name", actionName)) }
    categories.forEach { catName -> filter.addChild(XmlElement("category").addAttribute(ResourceTypes.ANDROID_URI, "name", catName)) }
    return filter
  }
}
