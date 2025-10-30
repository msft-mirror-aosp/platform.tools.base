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
package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.BasicSpec
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class BasicConnectedTest {
    companion object {
        @JvmField
        @ClassRule
        val emulator: ExternalResource = getEmulator()
    }

    @get:Rule
    val rule = GradleRule.fromProject(BasicSpec()) {
        androidApplication(":app") {
            android {
                installation {
                    // fail fast (30s) if no response
                    timeOutInMs = 30000
                }
            }
        }
    }

    fun GradleBuild.uninstall() {
        // run the uninstall tasks in order to (1) make sure nothing is installed at the beginning
        // of each test and (2) check the adb connection before taking the time to build anything.
        this.executor.run("uninstallAll")
    }

    @Test
    @Throws(Exception::class)
    fun install() {
        val build = rule.build {
            gradleProperties {
                add(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, false)
            }
        }

        build.uninstall()

        build.executor.run("installDebug", "uninstallAll")
        // b/37498215 - Try again.  Behavior may be different when tasks are up-to-date.
        build.executor.run("installDebug", "uninstallAll")
    }

    @Test
    @Throws(Exception::class)
    fun connectedCheck() {
        val build = rule.build
        build.uninstall()
        build.executor.run("connectedCheck")
    }
}
