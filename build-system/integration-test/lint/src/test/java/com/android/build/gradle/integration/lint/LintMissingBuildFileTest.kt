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

package com.android.build.gradle.integration.lint

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import org.junit.Rule
import org.junit.Test

class LintMissingBuildFileTest {

    @get:Rule
    val rule = GradleRule.from {
        settings {
            applyPlugin(PluginType.ANDROID_SETTINGS)
        }
        androidApplication(createMinimumProject = false) {
            android.namespace = "com.example.app"
            files.setupMinimumManifest()
        }
    }

    @Test
    fun lintRunsWithoutBuildFile() {
        val build = rule.build
        build.directory.resolve("settings.gradle").toFile().appendText("""

            include ':app'

            gradle.lifecycle.beforeProject { project ->
                if (project.path == ':app') {
                    project.apply plugin: 'com.android.application'

                    project.android {
                        namespace = "com.example.app"
                        compileSdkVersion ${GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION}

                        defaultConfig {
                            minSdkVersion 21
                            targetSdkVersion ${GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION}
                        }
                    }
                }
            }
        """.trimIndent())

        // Ensure app/build.gradle does not exist
        val appBuild = build.directory.resolve("app/build.gradle").toFile()
        if (appBuild.exists()) {
            appBuild.delete()
        }

        // Run lint on the app module
        val result = build.executor.run(":app:lintDebug")

        // Verify task execution
        result.assertTask(":app:lintDebug").didWork()
    }
}
