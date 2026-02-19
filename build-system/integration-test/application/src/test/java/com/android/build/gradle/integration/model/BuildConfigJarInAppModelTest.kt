/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.integration.model

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.utils.getDebugVariant
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

class BuildConfigJarInAppModelTest {
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication { android { buildFeatures { buildConfig = true } } }
      gradleProperties { add(BooleanOption.ENABLE_BUILD_CONFIG_AS_BYTECODE, true) }
    }

  @Test
  fun `test BuildConfig jar is in model`() {
    val build = rule.build
    build.executor.run("assembleDebug")
    val result = build.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels(variantName = "debug").container
    val androidProject = result.getProject().androidProject ?: throw RuntimeException("Failed to get AndroidProject model")
    val debugVariant = androidProject.getDebugVariant()

    val expectedBuildConfigPath =
      build
        .androidApplication()
        .resolve(InternalArtifactType.COMPILE_BUILD_CONFIG_JAR)
        .resolve("debug/generateDebugBuildConfig/BuildConfig.jar")
        .toFile()
    Truth.assertThat(debugVariant.mainArtifact.classesFolders).contains(expectedBuildConfigPath)
    Truth.assertThat(debugVariant.mainArtifact.generatedClassPaths["buildConfigGeneratedClasses"]).isEqualTo(expectedBuildConfigPath)
  }
}
