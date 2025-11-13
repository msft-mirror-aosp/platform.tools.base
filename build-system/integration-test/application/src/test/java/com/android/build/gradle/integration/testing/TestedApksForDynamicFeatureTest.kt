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

import com.android.build.api.variant.TestAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.TestComponentCallback
import com.android.build.gradle.options.BooleanOption
import com.android.utils.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.pathString


// Test project has app+feature, and test module that marked as  targetProjectPath = ":feature"
// in this case variant.testedApks returns multiple APK directories but takes first one.
class TestedApksForDynamicFeatureTest {

    @get:Rule
    val rule: GradleRule = GradleRule.from {
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
            kotlin {
                jvmToolchain(17)
            }
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
            kotlin {
                jvmToolchain(17)
            }
            pluginCallbacks += MyApkTestCallback::class.java
        }

        androidFeature {
            android {
                namespace = "com.example.android.kotlin.feature"
                defaultConfig {
                    minSdk = 21
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                dependencies {
                    implementation(project(":app"))
                }
            }
            kotlin {
                jvmToolchain(17)
            }
        }
    }

    @Test
    fun checkAllApks() {
        val result = rule.build.executor.run(":test:debugApkOutputCustomTask")
        val featureApkDir = FileUtils.join(
            rule.build.androidFeature().buildDir.pathString,
            "outputs",
            "apk",
            "debug")
        result.assertOutputContains("APK: $featureApkDir")
    }
}

class MyApkTestCallback : TestComponentCallback {
    override fun handleExtension(
        project: Project,
        androidComponents: TestAndroidComponentsExtension
    ) {
        androidComponents.onVariants(
            androidComponents.selector()
                .withBuildType("debug")
        ) { variant ->
            project.tasks.register(
                "${variant.name}ApkOutputCustomTask",
                TestedApksOutputCustomTask::class.java
            ) {
                it.apkDirectories.setFrom(variant.testedApks)
            }
        }
    }
}

abstract class TestedApksOutputCustomTask : DefaultTask() {

    @get:InputFiles
    abstract val apkDirectories: ConfigurableFileCollection

    @TaskAction
    fun run() {
        apkDirectories.files.forEach {
            println(
                "APK: " + it.path
            )
        }
    }
}
