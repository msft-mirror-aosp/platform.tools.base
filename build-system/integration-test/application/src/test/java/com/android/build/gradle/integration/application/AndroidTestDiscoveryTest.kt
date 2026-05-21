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

package com.android.build.gradle.integration.application

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.KotlinMultiplatformCallback
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import java.io.File
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Rule
import org.junit.Test

class AndroidTestDiscoveryTest {

  @get:Rule
  val rule =
    GradleRule.configure().from {
      gradleProperties { add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, true) }
      androidKotlinMultiplatformLibrary(":kmpLibrary") {
        files {
          add(
            "src/androidDeviceTest/kotlin/pkg/name/kmpLibrary/ExampleInstrumentedTest.kt",
            // language=kotlin
            """
            package pkg.name.kmpLibrary

            import androidx.test.ext.junit.runners.AndroidJUnit4

            import org.junit.Test
            import org.junit.runner.RunWith

            @RunWith(AndroidJUnit4::class)
            class ExampleInstrumentedTest {
              @Test
              fun exampleTest() {}
            }
            """
              .trimIndent(),
          )
        }
        pluginCallbacks += KmpCallback::class.java
      }
    }

  private val executor: GradleTaskExecutor
    get() = rule.build.executor.withEnableInfoLogging(false)

  class KmpCallback : KotlinMultiplatformCallback {
    override fun handleExtension(project: Project, extension: KotlinMultiplatformExtension) {
      extension.apply {
        (this as ExtensionAware).extensions.findByType(KotlinMultiplatformAndroidLibraryTarget::class.java)!!.apply {
          minSdk = 21
          withDeviceTest { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
        }
        sourceSets.getByName("androidDeviceTest") {
          it.dependencies {
            implementation("androidx.test:core:1.4.0-alpha06")
            implementation("androidx.test.ext:junit:1.1.5")
            implementation("androidx.test:monitor:1.4.0-alpha06")
            implementation("androidx.test:rules:1.4.0-alpha06")
            implementation("androidx.test:runner:1.4.0-alpha06")
          }
        }
      }
    }
  }

  @Test
  fun testAndroidTestDiscovery() {
    executor.run(":kmpLibrary:mergeAndroidDeviceTestAndroidTestDiscovery")

    val testListFile =
      File(
        rule.build.subProject(":kmpLibrary").buildDir.toFile(),
        "intermediates/android_test_discovery_list/androidDeviceTest/mergeAndroidDeviceTestAndroidTestDiscovery/test-list.txt",
      )

    assertThat(testListFile).exists()
    assertThat(testListFile).containsAllOf("pkg.name.kmpLibrary.ExampleInstrumentedTest")
  }
}
