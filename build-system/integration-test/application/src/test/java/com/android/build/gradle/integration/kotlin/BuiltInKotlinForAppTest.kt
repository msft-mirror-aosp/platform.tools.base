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
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.internal.dsl.ModulePropertyKey.BooleanWithDefault.SCREENSHOT_TEST
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils.KOTLIN_VERSION_FOR_TESTS
import com.android.testutils.truth.PathSubject
import org.junit.Rule
import org.junit.Test

class BuiltInKotlinForAppTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication(":app") {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

            HelloWorldAndroid.setupKotlin(files)
            HelloWorldAndroid.setupKotlinDependencies(this)
        }
    }

    @Test
    fun testKotlinClassesInApk() {
        val build = rule.configureBuild {
            androidApplication(":app") {
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
        build.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
            hasClass("Lcom/foo/application/AppFoo;")
            hasClass("Lcom/foo/application/KotlinAppFoo;")
        }
    }

    @Test
    fun testKotlinClassesInTestApk() {
        val build = rule.configureBuild {
            androidApplication(":app") {
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
        build.androidApplication(":app").assertApk(ApkSelector.DEBUG.forTestSuite("androidTest")) {
            hasClass("Lcom/foo/application/AppFooTest;")
            hasClass("Lcom/foo/application/KotlinAppFooTest;")
        }
    }

    @Test
    fun testUnitTests() {
        val build = rule.configureBuild {
            androidApplication(":app") {
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
        val app = build.androidApplication(":app")
        val testResults =
            app.location
                .resolve(
                    "build/test-results/testDebugUnitTest/TEST-com.foo.application.test.AppFooTest.xml"
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
        val build = rule.configure()
            .withProperties {
                add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
            }.from {
                androidApplication(":app") {
                    android.experimentalProperties[SCREENSHOT_TEST.key] = true

                    dependencies {
                        screenshotTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION_FOR_TESTS")
                    }

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
            }

        build.executor.run(":app:compileDebugScreenshotTestKotlin")
        build.executor.run(":app:assembleDebug")
    }

    @Test
    fun testInternalModifierAccessibleFromTests() {
        val build = rule.configureBuild {
            androidApplication(":app") {
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
        val build = rule.configureBuild {
            androidLibrary(":lib") {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

                HelloWorldAndroid.setupKotlin(files)
                HelloWorldAndroid.setupKotlinDependencies(this)

                files {
                    add("src/main/java/com/foo/library/LibFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.library
                            open class LibFoo
                        """.trimIndent())
                }
            }
            androidApplication(":app") {
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
        build.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
            hasClass("Lcom/foo/application/AppFoo;")
            hasClass("Lcom/foo/library/LibFoo;")
        }
    }

    @Test
    fun testKotlinAndJavaCrossReferences() {
        val build = rule.configureBuild {
            androidApplication(":app") {
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
        build.androidApplication(":app").assertApk(ApkSelector.DEBUG) {
            hasClass("Lcom/foo/application/AppJavaFoo;")
            hasClass("Lcom/foo/application/AppKotlinFoo;")
            hasClass("Lcom/foo/application/AppKotlinBar;")
        }
    }

    @Test
    fun testKotlinStdLibMissing() {
        val build = rule.configureBuild {
            androidApplication(":app") {
                // reset to remove the main dependency on kotlin stdlib
                dependencies.clear()
                // only setup the test, not the main one.
                HelloWorldAndroid.setupTestKotlinDependencies(this)

                files {
                    add("src/main/java/com/foo/application/AppFoo.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class AppFoo
                        """.trimIndent())
                }
            }
        }

        val result = build.executor.expectFailure().run(":app:assembleDebug")
        result.assertErrorContains("Kotlin standard library is missing")
    }

    @Test
    fun testErrorWhenBuiltInKotlinSupportAndKagpUsedInSameModule() {
        val build = rule.configureBuild {
            androidApplication(":app") {
                applyPlugin(PluginType.KOTLIN_ANDROID, applyFirst = true)
            }
        }

        val result = build.executor.expectFailure().run(":app:assembleDebug")
        result.assertErrorContains(
            "The \"org.jetbrains.kotlin.android\" plugin has been applied, but it is not compatible"
        )
    }

    @Test
    fun testKotlinDsl() {
        val build = rule.configureBuild {
            androidApplication(":app") {
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

        val result = build.executor.expectFailure().run(":app:compileDebugKotlin")
        ScannerSubject.assertThat(result.stderr)
            .contains("Visibility must be specified in explicit API mode")
    }
}

internal val builtInKotlinSupportDependencies =
    """
        dependencies {
            api("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION_FOR_TESTS")
            androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION_FOR_TESTS")
            testImplementation("org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VERSION_FOR_TESTS")
        }
        """.trimIndent()
