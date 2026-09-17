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

import com.android.tools.deployer.model.Apk
import com.android.tools.deployer.model.App
import com.android.tools.manifest.parser.ManifestInfo
import java.io.ByteArrayInputStream

/**
 * Entry point DSL for constructing in-memory [App] instances with synthetic manifest structures for testing.
 *
 * Example usage:
 * ```
 * val app = manifest("com.example.app") {
 *   activity(".MainActivity", isLauncher = true)
 *   activity(".SettingsActivity") {
 *     exported = false
 *   }
 * }
 * ```
 */
fun manifest(packageName: String, block: ManifestBuilder.() -> Unit): App {
  return ManifestBuilder(packageName).apply(block).build()
}

/** DSL builder for defining application packages and manifest components for testing. */
class ManifestBuilder(val packageName: String) {
  private val activityBuilders = mutableListOf<ActivityBuilder>()

  /**
   * Adds an activity to the manifest.
   *
   * @param name The relative or fully qualified activity name.
   * @param isLauncher Convenience flag to mark this activity as a launcher without a nested block.
   * @param block Optional builder block for custom configuration.
   */
  fun activity(name: String, isLauncher: Boolean = false, block: (ActivityBuilder.() -> Unit)? = null) {
    val builder = ActivityBuilder(packageName, name)
    if (isLauncher) {
      builder.launcher()
    }
    block?.invoke(builder)
    activityBuilders.add(builder)
  }

  fun toXmlElement(): XmlElement {
    val manifest = XmlElement("manifest")
    manifest.addAttribute(null, "package", packageName)
    val app = XmlElement("application")
    manifest.addChild(app)
    activityBuilders.forEach { app.addChild(it.toXmlElement()) }
    return manifest
  }

  fun buildBinaryXml(): ByteArray {
    return BinaryXmlEncoder.encode(toXmlElement())
  }

  fun build(): App {
    val manifestBytes = buildBinaryXml()
    val manifestInfo = ManifestInfo.parseBinaryFromStream(ByteArrayInputStream(manifestBytes))
    val apk = Apk.builder().setPackageName(packageName).setActivities(manifestInfo.activities()).build()
    return App.fromApk(packageName, apk)
  }
}
