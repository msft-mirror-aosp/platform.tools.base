/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.build.gradle.integration.application

import com.android.build.gradle.api.ApkVariantOutput
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.integration.common.category.SmokeTests
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/** Assemble tests for basic. */
@Category(SmokeTests::class)
class BasicTest {

  @get:Rule val rule = GradleRule.fromProject(BasicSpec())

  @Test
  fun weDontFailOnLicenceDotTxtWhenPackagingDependencies() {
    rule.build.executor.run("assembleAndroidTest")
  }

  @Test
  fun testRenderscriptDidNotRun() {
    // First enable renderscript, then execute renderscript task and check if it was skipped
    val build = rule.build { androidApplication(":app") { android { buildFeatures { renderScript = true } } } }

    val result = build.executor.run("compileDebugRenderscript")
    Truth.assertThat(result.getTask(":app:compileDebugRenderscript").executionState.toString()).isEqualTo("SKIPPED")
  }

  @Test
  fun testOutputs() {
    val build =
      rule.build {
        androidApplication(":app") { pluginCallbacks += BasicTestCallback::class.java }
        gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
      }

    val result = build.executor.run(":app:assembleRelease")

    result.assertOutputContains("Customizing release / 12")
    result.assertOutputContains("Done with release / 13")
  }
}

class BasicTestCallback : LegacyApplicationCallback {
  override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
    // Override the versionCode of the release version
    extension.applicationVariants.all { variant: ApplicationVariant ->
      println(variant.name)
      if (variant.buildType.name == "release") {
        variant.outputs.all { output ->
          output as ApkVariantOutput
          println("Customizing ${output.name} / ${output.versionCodeOverride}")
          output.setVersionCodeOverride(13)
          println("Done with ${output.name} / ${output.versionCodeOverride}")
        }
      }
    }
  }
}
