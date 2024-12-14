/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.api.apiTest.kotlin

import com.android.build.api.apiTest.VariantApiBaseTest
import com.google.common.truth.Truth
import org.junit.Test
import kotlin.test.assertNotNull

class LintLifecycleDslTest: VariantApiBaseTest(TestType.Script, ScriptingLanguage.Kotlin) {

    @Test
    fun lintOptionsCustomizationInLintPlugin() {
        lintOptionsCustomization("com.android.lint")
    }

    @Test
    fun lintOptionsCustomizationInAndroidPlugin() {
        lintOptionsCustomization("com.android.library")
    }

    private fun lintOptionsCustomization(pluginType: String) {
        given {
            addModule(":module") {
                buildFile =
                        // language=kotlin
                    """
                        plugins {
                                kotlin("jvm")
                                id("$pluginType")
                        }

                        lintLifecycle {
                            finalizeDsl { lint -> lint.enable.plusAssign("StopShip") }
                        }
                        """.trimIndent()
                addStopShipCode(this)
            }
            addModule(":app") {
                buildFile =
                        // language=kotlin
                    """
            plugins {
                    id("com.android.application")
                    kotlin("android")
            }
            android {
                    ${testingElements.addCommonAndroidBuildLogic()}
            }
            dependencies {
                api(project(":module"))
            }
            """.trimIndent()
                testingElements.addManifest(this)
                addApplicationSources(this)
            }
        }
        check {
            assertNotNull(this)
            Truth.assertThat(output).contains("BUILD SUCCESSFUL")
        }
    }

    @Test
    fun lintOptionsCustomizationInApp() {
        given {
            addModule(":app") {
                buildFile =
                        // language=kotlin
                    """
                        plugins {
                                id("com.android.application")
                                kotlin("android")
                        }
                        android {
                                ${testingElements.addCommonAndroidBuildLogic()}
                        }
                        lintLifecycle {
                            finalizeDsl { lint -> lint.enable.plusAssign("StopShip") }
                        }
                    """.trimIndent()
            testingElements.addManifest(this)
            addStopShipCode(this)
            addApplicationSources(this)
            }
        }
        check {
            assertNotNull(this)
            Truth.assertThat(output).contains("BUILD SUCCESSFUL")
        }
    }

    private fun addStopShipCode(givenBuilder: GivenBuilder) {
        givenBuilder.addSource("src/main/kotlin/com/example/foo/SomeClass.kt", """
                package com.example.foo

                class SomeClass {
                    // STOPSHIP
                    val foo = System.currentTimeMillis()
                }
                """.trimIndent()
        )
    }

    private fun addApplicationSources(givenBuilder: GivenBuilder) {
        givenBuilder.addSource(
            "src/main/kotlin/com/android/build/example/minimal/MainActivity.kt",
            //language=kotlin
            """
            package com.android.build.example.minimal

            import android.app.Activity

            class MainActivity : Activity() {
            }
            """.trimIndent())
    }
}
