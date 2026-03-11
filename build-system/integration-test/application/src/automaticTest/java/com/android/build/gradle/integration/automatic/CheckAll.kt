/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.build.gradle.integration.automatic

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor.ConfigurationCaching
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.builder
import com.android.build.gradle.integration.common.fixture.TestProjectPaths
import com.android.build.gradle.integration.common.runner.FilterableParameterized
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.AssumeUtil
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Lists
import java.io.File
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Test case that executes "standard" gradle tasks in all our tests projects.
 *
 * You can run only one test like this:
 *
 * `./gradlew :base:build-system:integration-test:application:automaticTest --tests=*[abiPureSplits]`
 */
@RunWith(FilterableParameterized::class)
class CheckAll(projectName: String, private val newDsl: Boolean) {
  @Rule var project: GradleTestProject

  @Test
  @Throws(Exception::class)
  fun assembleAndLint() {
    AssumeUtil.assumeNotWindows() // b/73306170
    Assume.assumeTrue(canAssemble(project))
    if (newDsl) Assume.assumeFalse(requiresOldDsl(project))
    project
      .executor() // Test project depends on vector drawable libraries that violate unique
      // namespacing.
      .with(BooleanOption.ENFORCE_UNIQUE_PACKAGE_NAMES, false)
      .with(BooleanOption.ENABLE_LEGACY_API, true)
      .withEnableInfoLogging(false)
      .run("assembleDebug", "assembleAndroidTest", "lint")
  }

  init {
    project =
      builder()
        .fromTestProject(projectName)
        .withConfigurationCaching(ConfigurationCaching.ON)
        .withHeap("2048M")
        .withComposeCompilerGradlePlugin(true)
        .addGradleProperty(BooleanOption.USE_NEW_DSL, newDsl)
        .create()
  }

  companion object {
    @Parameterized.Parameters(name = "{0}_newDsl_{1}")
    fun data(): MutableCollection<Array<Any?>?> {
      val parameters: MutableList<Array<Any?>?> = Lists.newArrayList<Array<Any?>?>()

      val testProjects = TestProjectPaths.getTestProjectDir().listFiles()
      Preconditions.checkState(testProjects != null)

      for (testProject in testProjects!!) {
        if (!isValidProjectDirectory(testProject)) {
          continue
        }
        for (newDsl in mutableListOf<Boolean?>(true, false)) {
          parameters.add(arrayOf(testProject.getName(), newDsl))
        }
      }

      return parameters
    }

    private fun isValidProjectDirectory(testProject: File): Boolean {
      if (!testProject.isDirectory) {
        return false
      }
      // RenderScript support is removed after NDK r23 LTS
      if (testProject.name == "renderscriptNdk") {
        return false
      }

      val buildGradle = File(testProject, "build.gradle")
      val settingsGradle = File(testProject, "settings.gradle")

      return buildGradle.exists() || settingsGradle.exists()
    }

    private fun canAssemble(project: GradleTestProject): Boolean {
      return !BROKEN_ALWAYS_ASSEMBLE.contains(project.name)
    }

    private fun requiresOldDsl(project: GradleTestProject): Boolean {
      return OLD_DSL_PROJECTS.contains(project.name)
    }

    private val BROKEN_ALWAYS_ASSEMBLE: ImmutableSet<String?> =
      ImmutableSet.of<String?>( // These require ndk.dir, but that's deprecated
        "ndkJniLib",
        "vulkan",
        "ndkSanAngeles",
        "combinedAbiDensitySplits", // Don't work with the version of the NDK that's in ndk-bundle. Enable after
        // moving all test projects off ndk-bundle and onto ndk.

        "prefabApp",
        "prefabPublishing", // Data binding projects are tested in
        // tools/base/build-system/integration-test/databinding

        "databindingIncremental",
        "databindingAndDagger",
        "databinding",
        "databindingAndKotlin",
        "databindingAndJetifier",
        "databindingMultiModule",
        "databindingWithDynamicFeatures", // Requires ml models to be in place
        "mlModelBinding", // These are all right:
        "genFolderApi", // Has a required injectable property
        "ndkJniPureSplitLib", // Doesn't build until externalNativeBuild {} is added.
        "duplicateNameImport", // Fails on purpose.
        "filteredOutBuildType", // assembleDebug does not exist as debug build type is
        // removed.
        "projectWithLocalDeps", // Doesn't have a build.gradle, not much to check
        // anyway.
        "externalBuildPlugin", // Not an Android Project.
        "lintKotlin", // deliberately contains lint errors (missing baseline file)
        "lintBaseline", // deliberately contains lint errors
        "lintStandalone", // Not an Android project
        "lintStandaloneVital", // Not an Android project
        "lintStandaloneCustomRules", // Not an Android project
        "lintCustomRules", // contains integ test for lint itself
        "lintCustomLocalAndPublishRules", // contains integ test for lint itself
        "simpleCompositeBuild", // broken composite build project.
        "multiCompositeBuild", // too complex composite build project to setup
        "sourceDependency", // not set up fully, just used for sync tests
        "kotlinMultiplatform", // java multiplatform project has its own assemble
        // tests.
      )

    private val OLD_DSL_PROJECTS: ImmutableSet<String?> =
      ImmutableSet.of<String?>("api", "artifactApi", "bytecodeGenerationHooks", "renamedApk", "splitAwareSeparateTestModule")
  }
}
