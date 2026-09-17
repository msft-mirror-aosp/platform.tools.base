/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.build.gradle.integration.packaging

import com.android.build.gradle.api.ApkVariantOutput
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/** Test to verify that the APK is packaged correctly when there is a change in the APK output file name. */
class ApkOutputFileChangeTest {

  @get:Rule val rule = GradleRule.from { androidApplication {} }

  @Test
  fun testOutputFileNameChange() {
    // Run the first build
    val result = rule.build.executor.run("assembleDebug")
    result.assertTask(":app:packageDebug").didWork()
    val app = rule.build.androidApplication(":app")

    app.assertApk(ApkSelector.DEBUG) {
      exists()
      contains("META-INF/MANIFEST.MF")
      contains("AndroidManifest.xml")
      contains("classes.dex")
      contains("resources.arsc")
    }
  }

  // Run the second build, check that the new APK is generated correctly (regression test for
  // https://issuetracker.google.com/issues/64703619)
  @Test
  fun testOutputFileNameChangeOldApi() {
    val build = rule.build {
      gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
      androidApplication { pluginCallbacks += MyAppCallback::class.java }
    }

    val result = build.executor.run("assembleDebug")
    result.assertTask(":app:packageDebug").didWork()
    val app = build.androidApplication(":app")
    app.assertApk(ApkSelector.DEBUG.withName("foo.apk")) {
      exists()
      contains("META-INF/MANIFEST.MF")
      contains("AndroidManifest.xml")
      contains("classes.dex")
      contains("resources.arsc")
    }
  }
}

class MyAppCallback : LegacyApplicationCallback {
  override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
    extension.applicationVariants.all { variant: ApplicationVariant ->
      variant.outputs.all { output ->
        output as ApkVariantOutput
        output.outputFileName = "foo.apk"
      }
    }
  }
}
