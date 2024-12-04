/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.dsl

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.forEachLine
import com.google.common.truth.Truth
import junit.framework.TestCase
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class NdkVersionTest {
    @get:Rule
    val rule = GradleRule.from {
        settings {
            applyPlugin(PluginType.ANDROID_SETTINGS)
            android {
                ndkVersion = "1.2"
            }
        }
        androidApplication {
            componentCallback = AppCallback::class.java
        }
    }

    class AppCallback: ApplicationComponentCallback {
        override fun handleComponents(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.finalizeDsl { extension ->
                println("$PREFIX${extension.ndkVersion}")
            }
        }
    }

    @Test
    fun testNdkVersionFromSettings() {
        val result = rule.build.executor.run("projects")

        var found = false
        result.stdout.forEachLine {
            if (it.startsWith(PREFIX)) {
                found = true
                val value = it.substring(PREFIX.length)
                Truth.assertThat(value).isEqualTo("1.2")
                return@forEachLine
            }
        }

        if (!found) {
            TestCase.fail("Did not find ndkVersion value in stdout")
        }
    }
}

private const val PREFIX = "NDKVERSION: "
