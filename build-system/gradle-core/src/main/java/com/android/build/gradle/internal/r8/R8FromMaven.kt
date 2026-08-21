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

package com.android.build.gradle.internal.r8

import com.android.build.gradle.options.ProjectOptions
import com.android.build.gradle.options.StringOption
import com.android.builder.dexing.R8Version
import org.gradle.api.Project
import org.gradle.api.file.FileCollection

/**
 * The R8 compiler JARs as fetched dynamically from Maven or default fallback.
 *
 * Contains a [FileCollection] representing the resolved R8 classpath, and a String identifying the R8 version being used.
 */
class R8FromMaven(val r8Classpath: FileCollection, val version: String) {
  companion object {
    const val R8_GROUP: String = "com.android.tools"
    const val R8_MODULE: String = "r8"

    @JvmStatic
    fun create(project: Project, projectOptions: ProjectOptions): R8FromMaven {
      return create(project, projectOptions::get)
    }

    @JvmStatic
    fun create(project: Project, stringOption: (option: StringOption) -> String?): R8FromMaven {
      val versionOverride = stringOption(StringOption.R8_VERSION_OVERRIDE)?.trim()
      val version =
        if (!versionOverride.isNullOrEmpty()) {
          versionOverride
        } else {
          R8Version.VERSION_AGP_WAS_SHIPPED_WITH
        }

      val configuration =
        project.configurations.detachedConfiguration(project.dependencies.create("$R8_GROUP:$R8_MODULE:$version")).also {
          it.isTransitive = true
          it.isCanBeConsumed = false
          it.isCanBeResolved = true
          it.description = "Detached configuration for resolving R8 compiler JARs."
        }

      return R8FromMaven(configuration, version)
    }
  }
}
