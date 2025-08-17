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

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.internal.dsl.ModulePropertyKey.BooleanWithDefault.SCREENSHOT_TEST
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BuiltInKotlinForAppTest(
    private val builtInKotlinBooleanOption: Boolean,
) {

    companion object {

        @Parameterized.Parameters(name = "builtInKotlinBooleanOption_{0}")
        @JvmStatic
        fun parameters() = listOf(false, true)
    }

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

            HelloWorldAndroid.setupKotlin(files)
        }
        gradleProperties {
            add(BooleanOption.BUILT_IN_KOTLIN, builtInKotlinBooleanOption)
        }
    }

    @Test
    fun testKotlinClassesInApk() {
        val build = rule.build {
            androidApplication {
                files {
                    add("src/main/java/com/foo/application/AppFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class AppFoo
                        """.trimIndent())
                    add("src/main/kotlin/com/foo/application/KotlinAppFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class KotlinAppFoo
                        """.trimIndent())
                }
            }

        }

        build.executor.run(":app:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().containsExactly(
                "com/foo/application/AppFoo",
                "com/foo/application/KotlinAppFoo",
                "pkg/name/app/HelloWorld",
                "pkg/name/app/R\$",
                "kotlin/",
                "org/intellij/",
                "org/jetbrains/"
            )
        }
    }

    @Test
    fun testKotlinClassesInTestApk() {
        val build = rule.build {
            androidApplication {
                files {
                    add("src/androidTest/java/AppFooTest.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class AppFooTest
                        """.trimIndent())
                    add("src/androidTest/kotlin/KotlinAppFooTest.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class KotlinAppFooTest
                        """.trimIndent())
                }
            }

        }

        build.executor.run(":app:assembleDebugAndroidTest")
        build.androidApplication().assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
            classes().containsExactly(
                "com/foo/application/AppFooTest",
                "com/foo/application/KotlinAppFooTest",
                "pkg/name/app/test/R",
            )
        }
    }

    @Test
    fun testUnitTests() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    testImplementation("junit:junit:4.12")
                }

                files {
                    add("src/test/kotlin/AppFooTest.kt",
                        //language=kotlin
                        """
                        package com.foo.application.test

                        class AppFooTest {
                          @org.junit.Test
                          fun testSample() {}
                        }
                        """.trimIndent()
                    )
                }
            }
        }

        build.executor.run(":app:testDebug")
        val app = build.androidApplication()
        val testResults =
            app.buildDir
                .resolve(
                    "test-results/testDebugUnitTest/TEST-com.foo.application.test.AppFooTest.xml"
                )
        PathSubject.assertThat(testResults).exists()
    }

    /**
     * Test that general built-in Kotlin support is compatible with Kotlin support for screenshot
     * testing, in contrast to [BuiltInKotlinForScreenshotTestTest], which tests Kotlin support for
     * screenshot testing in isolation
     */
    @Test
    fun testWithScreenshotTestEnabled() {
        val build = rule.build {
            androidApplication {
                android.experimentalProperties[SCREENSHOT_TEST.key] = true

                files {
                    add(
                        "src/screenshotTest/kotlin/AppScreenshotTestFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class AppScreenshotTestFoo
                        """.trimIndent()
                    )
                    add(
                        "src/main/java/com/foo/application/AppFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class AppFoo
                        """.trimIndent()
                    )
                }
            }
            gradleProperties {
                add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
            }
        }

        build.executor.run(":app:compileDebugScreenshotTestKotlin")
        build.executor.run(":app:assembleDebug")
    }

    @Test
    fun testInternalModifierAccessibleFromTests() {
        val build = rule.build {
            androidApplication {
                files {
                    add("src/main/java/com/foo/application/AppFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class AppFoo {
                              internal fun bar() {}
                            }
                        """.trimIndent()
                    )
                    add("src/test/com/foo/application/AppFooTest.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class AppFooTest {
                              init { AppFoo().bar() }
                            }
                        """.trimIndent()
                    )
                }
            }
        }

        build.executor.run(":app:assembleDebugUnitTest")
    }

    @Test
    fun testAppCompilesAgainstKotlinClassesFromDependency() {
        val build = rule.build {
            androidLibrary {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

                HelloWorldAndroid.setupKotlin(files)

                files {
                    add("src/main/java/com/foo/library/LibFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.library
                            open class LibFoo
                        """.trimIndent())
                }
            }
            androidApplication {
                files {
                    add(
                        "src/main/kotlin/com/foo/application/AppFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class AppFoo: com.foo.library.LibFoo()
                        """.trimIndent()
                    )
                }
                dependencies {
                    api(project(":lib"))
                }
            }
        }

        build.executor.run(":app:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().containsExactly(
                "com/foo/application/AppFoo",
                "com/foo/library/LibFoo",
                "pkg/name/app/HelloWorld",
                "pkg/name/app/R\$",
                "pkg/name/lib/HelloWorld",
                "pkg/name/lib/R\$",
                "kotlin/",
                "org/intellij/",
                "org/jetbrains/"
            )
        }
    }

    @Test
    fun testKotlinAndJavaCrossReferences() {
        val build = rule.build {
            androidApplication {
                files {
                    add("src/main/java/com/foo/application/AppJavaFoo.java",
                        //language=java
                        """
                            package com.foo.application;
                            public class AppJavaFoo {
                              String prop = new AppKotlinBar().getAppJavaFooClassName();
                            }
                        """.trimIndent()
                    )
                    add("src/main/java/com/foo/application/AppKotlinFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class AppKotlinFoo: AppJavaFoo()
                        """.trimIndent()
                    )
                    add("src/main/java/com/foo/application/AppKotlinBar.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class AppKotlinBar {
                              val appJavaFooClassName = AppJavaFoo::class.java.name
                            }
                        """.trimIndent()
                    )
                }
            }
        }

        build.executor.run(":app:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().containsExactly(
                "com/foo/application/AppJavaFoo",
                "com/foo/application/AppKotlinFoo",
                "com/foo/application/AppKotlinBar",
                "pkg/name/app/HelloWorld",
                "pkg/name/app/R\$",
                "kotlin/",
                "org/intellij/",
                "org/jetbrains/"
            )
        }
    }

    @Test
    fun `fail when built-in Kotlin plugin is applied before kotlin-android plugin`() {
        val build = rule.build {
            androidApplication {
                applyPlugin(PluginType.KOTLIN_ANDROID)
            }
        }
        val result = build.executor.expectFailure().run(":app:assembleDebug")
        // See b/438711106
        result.assertErrorContains(
            "Cannot add extension with name 'kotlin', as there is an extension already registered with that name."
        )
    }

    @Test
    fun `fail when kotlin-android plugin is applied before built-in Kotlin plugin`() {
        val build = rule.build {
            androidApplication {
                applyPlugin(PluginType.KOTLIN_ANDROID, applyFirst = true)
            }
        }
        val result = build.executor.expectFailure().run(":app:assembleDebug")
        result.assertErrorContains(
            if (builtInKotlinBooleanOption) {
                "The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0."
            } else {
                "The 'org.jetbrains.kotlin.android' plugin is not compatible with the 'com.android.experimental.built-in-kotlin' plugin."
            }
        )
    }

    @Test
    fun testKotlinDsl() {
        val build = rule.build {
            androidApplication {
                files {
                    add("src/main/kotlin/com/foo/application/KotlinAppFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.application

                            // This will cause the build to fail because it's missing an explicit
                            // visibility modifier.
                            fun publicFunction() {}
                        """.trimIndent())
                }
                kotlin {
                    explicitApi()
                }
            }
        }

        build.executor.expectFailure().run(":app:compileDebugKotlin").assertErrorContains(
            "Visibility must be specified in explicit API mode"
        )
    }

    /**
     * Regression test for b/338596003
     */
    @Test
    fun testKotlinAttributeSetup() {
        val build = rule.build {
            androidApplication {
                android {
                    defaultConfig {
                        minSdk = 21
                    }
                    dependencies {
                        implementation("androidx.compose.ui:ui-tooling-preview:1.6.5")
                    }
                }
            }
        }

        // Test that kotlin compilation completes successfully
        build.executor
            .with(BooleanOption.USE_ANDROID_X, true)
            .run(":app:compileDebugKotlin")
    }

    @Test
    fun `test inconsistent JVM targets between Java and Kotlin compile tasks`() { // b/408242956
        val build = rule.build {
            androidApplication {
                kotlin {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_17)
                    }
                }
            }
        }
        build.executor.expectFailure()
            .run(":app:compileDebugJavaWithJavac")
            .assertErrorContains("Inconsistent JVM targets between Java and Kotlin compile tasks: 1.8 and 17.")
    }
}
