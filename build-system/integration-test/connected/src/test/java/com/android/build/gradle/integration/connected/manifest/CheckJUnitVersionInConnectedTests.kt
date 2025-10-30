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

package com.android.build.gradle.integration.connected.manifest

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.connected.utils.getEmulator
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/**
 * Checks if JUnit version in runtime matches declared androidTestImplementation one.
 * b/318003571
 */
class CheckJUnitVersionInConnectedTests {

    @get:Rule
    val project = GradleRule.Companion.from {
        androidApplication {
            android {
                dependencies {
                    androidTestImplementation("androidx.test.ext:junit:1.1.5")
                    androidTestImplementation("junit:junit:4.13.2")
                    androidTestImplementation("androidx.test:core:1.5.0")
                    androidTestImplementation("androidx.test:runner:1.5.0")
                }
            }
        }.files {
            add("src/androidTest/java/com/example/JUnitVersionTest.kt",
                """
                package com.example

                import androidx.test.ext.junit.runners.AndroidJUnit4
                import org.junit.Assert.assertEquals
                import org.junit.Test
                import org.junit.runner.RunWith

                @RunWith(AndroidJUnit4::class)
                class JUnitVersionTest {
                    @Test
                    fun testJUnitVersionMatchingDependency() {
                        assertEquals("4.13.2", junit.runner.Version.id())
                    }
                }
            """.trimIndent()
                )
        }
    }

    @Test
    fun `connected test uses correct JUnit version`() {
        project.build.executor.run("connectedAndroidTest");
    }

    companion object {
        @JvmField
        @ClassRule
        val emulator: ExternalResource = getEmulator()
    }
}
