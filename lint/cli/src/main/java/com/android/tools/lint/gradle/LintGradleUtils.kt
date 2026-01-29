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
package com.android.tools.lint.gradle

import com.android.SdkConstants
import com.android.tools.lint.client.api.LintClient
import com.android.tools.lint.detector.api.Project
import com.android.tools.lint.detector.api.findGradleRootDir
import com.android.tools.lint.model.LintModelArtifactType
import com.android.tools.lint.model.LintModelModuleType
import java.io.File
import java.io.IOException
import org.jetbrains.annotations.VisibleForTesting

/**
 * TOML version catalog files, and other Gradle files in the root project, aren't actually part of the Gradle projects lint is asked to
 * analyze.
 *
 * Initially, we just had projects pull in version catalog files from `../gradle/`, but that meant that we'd potentially end up repeating
 * analysis (and reporting) on the same files over and over, from each project. We should only do this once. For now, we're assigning the
 * responsibility to the first module alphabetically in the root directory.
 *
 * (We're also asked to analyze this same folder multiple times, for each source set type, so we only return true for the main artifact
 * type.)
 */
fun Project.isDesignatedGradleRootHolder(client: LintClient): Boolean {
  // In AGP we'll be invoked for each artifact (main, test, androidTest, testFixtures)
  // as if it's a whole project; we only want to report TOML and gradle properties
  // files from the main artifact.

  val buildVariant = buildVariant
  if (buildVariant != null) {
    val artifact = buildVariant.artifact
    if (artifact.type != LintModelArtifactType.MAIN) {
      return false
    }
  }

  val root = client.getRootDir() ?: findGradleRootDir(dir) ?: return false

  val buildModule = buildModule
  if (buildModule != null) {
    val path = findFirstIncludedModulePath(root)
    if (path != null) {
      return buildModule!!.modulePath == path
    }
  }

  // Didn't get module paths from the settings files,
  // so instead just find the first module alphabetically
  // in the root directory and pick it.
  val moduleDirs = root.listFiles()?.sorted()
  if (moduleDirs != null) {
    for (moduleDir in moduleDirs) {
      if (
          File(moduleDir, SdkConstants.FN_BUILD_GRADLE).exists() ||
              File(moduleDir, SdkConstants.FN_BUILD_GRADLE_KTS).exists() ||
              File(moduleDir, SdkConstants.FN_BUILD_GRADLE_DECLARATIVE).exists()
      ) {
        return dir.path.equals(moduleDir.path, ignoreCase = true)
      }
    }
  }

  // No directories match -- so none of the projects are directly nested
  // inside the root project folder. Just fall back to assigning the
  // responsibility to the app module; there's usually exactly one.
  return type == LintModelModuleType.APP
}

@VisibleForTesting
private fun findFirstIncludedModulePath(root: File): String? {
  val rootFiles = root.listFiles() ?: return null
  for (file in rootFiles) {
    val name = file.name
    if (
        name == SdkConstants.FN_SETTINGS_GRADLE ||
            name == SdkConstants.FN_SETTINGS_GRADLE_KTS ||
            name == SdkConstants.FN_SETTINGS_GRADLE_DECLARATIVE
    ) {
      if (file.isFile) {
        try {
          return findFirstIncludedModulePath(file.readText())
        } catch (_: IOException) {}
      }
    }
  }
  return null
}

@VisibleForTesting
fun findFirstIncludedModulePath(settings: String): String? {
  val match = regex.find(settings) ?: return null
  val path = match.groups[3]?.value ?: match.groups[5]?.value ?: return null
  if (path.contains("$")) {
    return null
  }
  return path
}

private val regex = Regex("""^\s*include\s*\(?\s*(('(:[^']+)')|("(:[^"]+)"))""", RegexOption.MULTILINE)
