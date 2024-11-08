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
 * A set of [GradleBuild] configuration that are out of the box usable test projects
 */
class BasicBuilds {
    companion object {
        val HELLO_WORLD_APP: GradleBuildDefinition.() -> Unit = {
            androidApplication(":app") {
                HelloWorldAndroid.setupJava(layout)
            }
        }

        val HELLO_WORLD_LIBRARY: GradleBuildDefinition.() -> Unit = {
            androidLibrary(":library") {
                HelloWorldAndroid.setupJava(layout)
            }
        }

        val COMPOSE_APP: GradleBuildDefinition.() -> Unit = {
            androidApplication(":app") {
                // TODO
            }
        }
    }
}
