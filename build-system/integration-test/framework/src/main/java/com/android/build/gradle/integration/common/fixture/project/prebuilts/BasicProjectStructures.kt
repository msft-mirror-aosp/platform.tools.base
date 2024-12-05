/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.project.prebuilts

import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition

/**
 * Utility methods to create basic Android build structure.
 */
class BasicProjectStructures {
    companion object {
        /**
         * Basic Android Application module
         */
        fun appWithLibrary(
            gradleBuild: GradleBuildDefinition,
            appPath: String = ":app",
            libPath: String = ":library"
        ) {
            gradleBuild.apply {
                androidLibrary(libPath) { }
                androidApplication(appPath) {
                    dependencies {
                        implementation(project(libPath))
                    }
                }
            }
        }

        fun appWithTwoLibraries(gradleBuild: GradleBuildDefinition) {
            gradleBuild.apply {
                androidLibrary(":library1") { }
                androidLibrary(":library2") { }
                androidApplication(":app") {
                    dependencies {
                        implementation(project(":library1"))
                        implementation(project(":library2"))
                    }
                }
            }
        }
    }
}
