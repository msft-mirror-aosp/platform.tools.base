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

package com.android.build.gradle.integration.testing

import com.android.build.api.variant.ApkOutput
import com.android.build.api.variant.DeviceSpec
import com.android.build.api.variant.TestAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.TestComponentCallback
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class ApkInstallGroupsTest {

  @get:Rule
  val rule: GradleRule =
    GradleRule.from {
      androidApplication {
        android {
          namespace = "com.example.android.kotlin"
          defaultConfig {
            minSdk = 21
            versionCode = 1
            versionName = "1.0"
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          dynamicFeatures.add(":feature")
        }
        kotlin { jvmToolchain(17) }
      }

      androidTest {
        android {
          namespace = "com.example.android.kotlin.testonly"
          defaultConfig {
            minSdk = 21
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          targetProjectPath = ":feature"
        }
        kotlin { jvmToolchain(17) }
        pluginCallbacks += MyTestCallback::class.java
      }

      androidFeature {
        android {
          namespace = "com.example.android.kotlin.feature"
          defaultConfig {
            minSdk = 21
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          dependencies { implementation(project(":app")) }
        }
        kotlin { jvmToolchain(17) }
      }
    }

  @Test
  fun checkAllApks() {
    val result = rule.build.executor.run(":test:debugApkOutputCustomTask")
    // dynamic feature and app APKs
    result.assertOutputContains("Main Apk Group:[feature-debug.apk,app-debug.apk]")
    // test module
    result.assertOutputContains("Testing Apk:[test-debug.apk]")
  }
}

class MyTestCallback : TestComponentCallback {

  override fun handleExtension(project: Project, androidComponents: TestAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
      val task = project.tasks.register("${variant.name}ApkOutputCustomTask", ApksOutputCustomTask::class.java)
      variant.outputProviders.provideApkOutputToTask(task, ApksOutputCustomTask::apkOutput, DeviceSpec.Builder().setApiLevel(21).build())
    }
  }
}

abstract class ApksOutputCustomTask : DefaultTask() {

  @get:Internal abstract val apkOutput: Property<ApkOutput>

  @TaskAction
  fun run() {
    apkOutput.get().apkInstallGroups.forEach { installGroup ->
      println(installGroup.description + ":[${installGroup.apks.joinToString(",") { it.asFile.name }}]")
    }
  }
}
