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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.internal.dsl.ModulePropertyKey.BooleanWithDefault.SCREENSHOT_TEST
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class BuiltInKotlinForScreenshotTestTest {

    @get:Rule
    val rule = GradleRule.from {
        androidLibrary {
            applyPlugin(PluginType.KOTLIN_ANDROID)
            android {
                defaultConfig.minSdk = 21
            }
            kotlin {
                jvmToolchain(17)
            }
        }
        gradleProperties {
            add(BooleanOption.BUILT_IN_KOTLIN, false)
            add(BooleanOption.USE_NEW_DSL, false)
        }
    }

    /**
     * Include dependency on "androidx.compose.ui:ui-tooling-preview:1.6.5" as a regression test for
     * b/338512598
     */
    @Test
    fun testModuleAndExternalDependencies() {
        val build = rule.build {
            gradleProperties {
                add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
            }
            androidLibrary {
                android.experimentalProperties[SCREENSHOT_TEST.key] = true
                dependencies {
                    screenshotTestImplementation("androidx.compose.ui:ui-tooling-preview:1.6.5")
                    screenshotTestImplementation(project(":lib2"))
                }
                files.add(
                    "src/screenshotTest/kotlin/LibScreenshotTest.kt",
                    //language=kotlin
                    """
                        package com.foo.library

                        import com.foo.library.two.LibTwoClass
                        import androidx.compose.ui.tooling.preview.Preview

                        class LibScreenshotTest
                    """.trimIndent()
                )
            }
            androidLibrary(":lib2") {
                applyPlugin(PluginType.KOTLIN_ANDROID)
                kotlin {
                    jvmToolchain(17)
                }
                files.add(
                    "src/main/kotlin/LibTwoClass.kt",
                    //language=kotlin
                    """
                        package com.foo.library.two
                        class LibTwoClass
                    """.trimIndent()
                )
            }
        }

        build.executor
            .with(BooleanOption.USE_ANDROID_X, true)
            .run(":lib:compileDebugScreenshotTestKotlin")

        val screenshotTestClassFile =
            build.androidLibrary().intermediatesDir.resolve(
                "built_in_kotlinc/debugScreenshotTest/compileDebugScreenshotTestKotlin/classes/com/foo/library/LibScreenshotTest.class"
            )
        PathSubject.assertThat(screenshotTestClassFile).exists()
    }

    @Test
    fun testInternalModifierAccessible() {
        val build = rule.build {
            gradleProperties {
                add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
            }
            androidLibrary {
                android.experimentalProperties[SCREENSHOT_TEST.key] = true
                files.add(
                    "src/screenshotTest/kotlin/LibScreenshotTestFoo.kt",
                    //language=kotlin
                    """
                        package com.foo.library
                        class LibScreenshotTestFoo {
                            init { LibFoo().bar() }
                        }
                    """.trimIndent()
                )
                files.add(
                    "src/main/java/LibFoo.kt",
                    //language=kotlin
                    """
                    package com.foo.library
                    class LibFoo {
                        internal fun bar() {}
                    }
                    """.trimIndent()
                )
            }
        }
        build.executor.run(":lib:compileDebugScreenshotTestKotlin")
    }

}
