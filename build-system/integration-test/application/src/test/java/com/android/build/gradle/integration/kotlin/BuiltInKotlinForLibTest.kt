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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

class BuiltInKotlinForLibTest {

    @get:Rule
    val rule = GradleRule.from {
        androidLibrary {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            files {
                add(
                    "src/main/java/LibFoo.kt",
                    //language=kotlin
                    """
                        package com.foo.library
                        class LibFoo
                    """.trimIndent())
                add(
                    "src/main/kotlin/KotlinLibFoo.kt",
                    //language=kotlin
                    """
                        package com.foo.library
                        class KotlinLibFoo
                    """.trimIndent()
                )
            }
        }
    }

    @Test
    fun testKotlinClassesInAar() {
        val build = rule.build
        build.executor.run(":lib:assembleDebug")

        build.androidLibrary().assertAar(AarSelector.DEBUG) {
            containsMainClass("Lcom/foo/library/LibFoo;")
            containsMainClass("Lcom/foo/library/KotlinLibFoo;")
        }
    }

    @Test
    fun testKotlinClassesInTestApk() {
        val build = rule.build {
            androidLibrary {
                files {
                    add(
                        "src/androidTest/java/LibFooTest.kt",
                        //language=kotlin
                        """
                            package com.foo.library
                            class LibFooTest
                        """.trimIndent()
                    )
                    add(
                        "src/androidTest/kotlin/KotlinLibFooTest.kt",
                        //language=kotlin
                        """
                            package com.foo.library
                            class KotlinLibFooTest
                        """.trimIndent()
                    )
                }
            }
        }

        build.executor.run(":lib:assembleDebugAndroidTest")
        build.androidLibrary().assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
            hasClass("Lcom/foo/library/LibFoo;")
            hasClass("Lcom/foo/library/KotlinLibFoo;")
            hasClass("Lcom/foo/library/LibFooTest;")
            hasClass("Lcom/foo/library/KotlinLibFooTest;")
        }
    }

    /**
     * Test that general built-in Kotlin support is compatible with Kotlin support for testFixtures,
     * in contrast to [BuiltInKotlinForTestFixturesTest], which tests Kotlin support for
     * testFixtures in isolation.
     */
    @Test
    fun testTestFixtures() {
        val build = rule.build {
            androidLibrary {
                android {
                    testFixtures {
                        enable = true
                    }
                }
                files {
                    add(
                        "src/testFixtures/kotlin/LibFooTestFixture.kt",
                        //language=kotlin
                        """
                            package com.foo.library
                            import com.foo.library.LibFoo
                            class LibFooTestFixture
                        """.trimIndent()
                    )
                }
            }
        }

        build.executor.run(":lib:assembleDebugTestFixtures")

        build.androidLibrary().assertAar(AarSelector.DEBUG.forTestFixtures()) {
            containsMainClass("Lcom/foo/library/LibFooTestFixture;")
        }

        // Kotlin support for testFixtures should work with or without the gradle property when
        // general built-in Kotlin support is enabled
        build.reconfigureGradleProperties {
            add(BooleanOption.ENABLE_TEST_FIXTURES_KOTLIN_SUPPORT, true)
        }

        build.executor.run(":lib:assembleDebugTestFixtures")
        build.androidLibrary().assertAar(AarSelector.DEBUG.forTestFixtures()) {
            containsMainClass("Lcom/foo/library/LibFooTestFixture;")
        }
    }
}
