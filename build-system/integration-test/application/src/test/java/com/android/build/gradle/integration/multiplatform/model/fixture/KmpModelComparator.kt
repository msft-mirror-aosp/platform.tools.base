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

package com.android.build.gradle.integration.multiplatform.model.fixture

import com.android.SdkConstants.DOT_JSON
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.FileNormalizerImpl
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.ModelContainerV2
import com.android.build.gradle.integration.common.fixture.model.BaseModelComparator
import com.android.build.gradle.integration.common.fixture.model.BasicComparator
import com.android.build.gradle.integration.common.fixture.model.normaliseCompileTarget
import com.android.build.gradle.integration.common.fixture.model.normalizeBuildToolsVersion
import com.android.build.gradle.integration.common.fixture.model.normalizeVersionsOfCommonDependencies
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File

class KmpModelComparator(
  testClass: BaseModelComparator,
  private val project: GradleTestProject,
  private val modelSnapshotTask: String,
  private val taskOutputsLocator: (String) -> List<File>,
  private val configCacheMode: BaseGradleExecutor.ConfigurationCaching = BaseGradleExecutor.ConfigurationCaching.OFF,
) : BasicComparator(testClass) {

  private fun GradleTestProject.getBuildMap() =
    mapOf(
      ModelContainerV2.ROOT_BUILD_ID to
        ModelContainerV2.BuildInfo(
          name = ":kotlinMultiplatform",
          rootDir = projectDir,
          projects =
            settingsFile.readText().split("\n").mapNotNull {
              it
                .takeIf { it.startsWith("include ") }
                ?.substringAfter("include ")
                ?.trim('\'', ':')
                ?.let { it to getSubproject(it).projectDir }
            },
        )
    )

  private val buildMap = project.getBuildMap()

  private fun fetchModels(projectPath: String, executor: GradleTaskExecutor, printModelToStdout: Boolean = true): Map<String, String> {
    executor.withFailOnWarning(false).run("$projectPath:$modelSnapshotTask")

    val outputs = taskOutputsLocator(projectPath)

    val normalizer =
      FileNormalizerImpl(
        buildMap = buildMap,
        gradleUserHome = executor.location.testLocation.gradleUserHome.toFile(),
        gradleCacheDir = executor.location.testLocation.gradleCacheDir,
        androidSdkDir = project.androidSdkDir,
        androidPrefsDir = executor.preferencesRootDir,
        androidNdkSxSRoot = project.androidNdkSxSRootSymlink,
        localRepos = GradleTestProject.localRepositories,
        additionalMavenRepo = project.additionalMavenRepoDir,
        defaultNdkSideBySideVersion = GradleTestProject.DEFAULT_NDK_SIDE_BY_SIDE_VERSION,
      ) {
        val normalizedString = normalizeBuildToolsVersion(normaliseCompileTarget(it).toString()).toString()

        // the normalizer doesn't cover the modules-2 files, since the path contains the library
        // itself, we just override it here.
        if (
          normalizedString.startsWith("{GRADLE}/caches/modules-2/files-2.1/com.example/kmpSecondLib-android/1.0/") ||
            normalizedString.startsWith("{GRADLE}/caches/modules-2/files-2.1/com.example/kmpLibraryPlugin-android/1.0/")
        ) {
          "{GRADLE_CACHE}/{MODULES_2}/" +
            normalizedString.substringAfter("{GRADLE}/caches/modules-2/files-2.1/").substringBeforeLast("/").substringBeforeLast("/") +
            "/{CHECKSUM}/" +
            normalizedString.substringAfterLast("/")
        } else if (normalizedString.endsWith("transformed/local-api.jar")) {
          // kotlin gradle plugin uses relative path to represent local file coordinates
          "{GRADLE_CACHE}/{CHECKSUM}/transformed/local-api.jar"
        } else if (normalizedString.endsWith("transformed/local-runtime.jar")) {
          // kotlin gradle plugin uses relative path to represent local file coordinates
          "{GRADLE_CACHE}/{CHECKSUM}/transformed/local-runtime.jar"
        } else {
          normalizedString
        }
      }

    val gson = GsonBuilder().setPrettyPrinting().create()

    return outputs.associate { jsonReport ->
      val reportName = jsonReport.name.removeSuffix(DOT_JSON).removeSuffix(".module")

      val content =
        normalizer
          .normalize(jsonReport.inputStream().buffered().reader().use { JsonParser.parseReader(it) })
          .let {
            gson
              .toJson(it)
              .normalizeVersionsOfCommonDependencies()
              .replace(Regex("\"sha512\": \".*\""), "\"sha512\": \"{DIGEST}\"")
              .replace(Regex("\"sha256\": \".*\""), "\"sha256\": \"{DIGEST}\"")
              .replace(Regex("\"sha1\": \".*\""), "\"sha1\": \"{DIGEST}\"")
              .replace(Regex("\"md5\": \".*\""), "\"md5\": \"{DIGEST}\"")
              .replace(Regex("\"size\": .*,"), "\"size\": {SIZE},")
              .replace(Regex("-commonMain-.{6}\\.klib"), "-commonMain-{HASH_CODE}\\.klib")
          }
          .also {
            if (printModelToStdout) {
              generateStdoutHeader(normalizer)
              println(it)
            }
          }

      reportName to content
    }
  }

  private fun fetchPomModels(projectPath: String, executor: GradleTaskExecutor): Map<String, String> {
    executor.run("$projectPath:$modelSnapshotTask")

    val outputs = taskOutputsLocator(projectPath)

    return outputs.associate { output ->
      val reportName = output.name
      val content = output.readText().normalizeVersionsOfCommonDependencies()
      reportName to content
    }
  }

  fun fetchAndCompareModels(projects: List<String>) = compareFetchedModels(projects, ::fetchModels)

  fun fetchAndComparePomModels(projects: List<String>) = compareFetchedModels(projects, ::fetchPomModels)

  private fun compareFetchedModels(projects: List<String>, fetchModels: (String, GradleTaskExecutor) -> Map<String, String>) {
    // Generate project structure metadata json file for all subproject
    // They are needed in order to resolve project dependencies
    val executor = project.executor().withConfigurationCaching(configCacheMode)
    executor
      .withFailOnWarning(false) // b/455891987
      .run("generateProjectStructureMetadata")

    projects.forEach { projectPath ->
      fetchModels(projectPath, executor).forEach { (reportName, content) ->
        runComparison(
          name = projectPath.substringAfterLast(":") + "/" + reportName,
          actualContent = content,
          goldenFile = projectPath.removePrefix(":").replace(':', '_') + "_" + reportName,
        )
      }
    }
  }
}
