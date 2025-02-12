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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import org.junit.Rule
import org.junit.Test

/**
 * Tests that [JacocoTransform] performs the expected actions when there are file changes.
 */
class JacocoIncrementalTransformTest {

    @get:Rule
    val project = GradleRule.configure().from {
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

            android {
                namespace = "com.agpTest.appWithCoverage"
                defaultConfig {
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    minSdk = 24

                }
                buildTypes {
                    named("debug") {
                        it.enableAndroidTestCoverage = true
                    }
                }
                files {
                    // The presence of an androidTest causes the JacocoTransform to select
                    // AndroidArtifacts.ArtifactType.CLASSES (allowing for the transform to run
                    // incrementally) rather than consuming AndroidArtifacts.ArtifactType.CLASSES_JAR.
                    add("src/androidTest/java/com/agpTest/agpWithCoverage/ExampleInstrumentedTest.kt",
                        """
                        package com.agpTest.appWithCoverage

                        import androidx.test.platform.app.InstrumentationRegistry
                        import androidx.test.ext.junit.runners.AndroidJUnit4

                        import org.junit.Test
                        import org.junit.runner.RunWith

                        import org.junit.Assert.*

                        @RunWith(AndroidJUnit4::class)
                        class ExampleInstrumentedTest {
                            @Test
                            fun useAppContext() {
                                // Context of the app under test.
                                val appContext = InstrumentationRegistry.getInstrumentation().targetContext
                                assertEquals("com.agpTest.appWithCoverage", appContext.packageName)
                            }
                        }
                        """.trimIndent())
                }
                dependencies {
                    implementation(project(AndroidProjectDefinition.DEFAULT_LIB_PATH))
                    androidTestImplementation("com.android.support.test:runner:1.0.1")
                    androidTestImplementation("com.android.support.test.espresso:espresso-core:3.0.1")
                }
            }
        }
        androidLibrary {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android {
                namespace = "com.agpTest.libWithClasses"
                buildTypes {
                    named("debug") {
                        it.enableAndroidTestCoverage = true
                    }
                }
            }
            files {
                add(
                    "src/main/java/com/agpTest/libWithClasses/A.kt",
                    //language=kotlin
                    """
                    package com.agpTest.libWithClasses

                    class A {}
                """.trimIndent()
                )
                add(
                    "src/main/java/com/agpTest/libWithClasses/B.kt",
                    //language=kotlin
                    """
                    package com.agpTest.libWithClasses

                    class B {}
                """.trimIndent()
                )
                add(
                    "src/main/java/com/agpTest/libWithClasses/C.kt",
                    //language=kotlin
                    """
                    package com.agpTest.libWithClasses

                    class C {}
                """.trimIndent()
                )
            }
        }
    }

    @Test
    fun testAddingClassIncrementally() {
        val build = project.build
        build.executor.run("${AndroidProjectDefinition.DEFAULT_APP_PATH}:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().classes().apply {
                contains("com/agpTest/libWithClasses/A")
                contains("com/agpTest/libWithClasses/B")
                contains("com/agpTest/libWithClasses/C")
            }
        }
        build.androidLibrary().files.update("src/main/java/com/agpTest/libWithClasses/B.kt").append(
            "\nfun bar() {}"
        )
        build.executor.run("${AndroidProjectDefinition.DEFAULT_APP_PATH}:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().classes().apply {
                contains("com/agpTest/libWithClasses/A")
                contains("com/agpTest/libWithClasses/B")
                contains("com/agpTest/libWithClasses/BKt")
                contains("com/agpTest/libWithClasses/C")
            }
        }
    }


    // Regression test for b/390736538 and b/393549309
    @Test
    fun testRemovingClassIncrementally() {
        val build = project.build
        build.executor.run("${AndroidProjectDefinition.DEFAULT_APP_PATH}:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().classes().apply {
                contains("com/agpTest/libWithClasses/A")
                contains("com/agpTest/libWithClasses/B")
                contains("com/agpTest/libWithClasses/C")
            }
        }
        build.androidLibrary().files.remove("src/main/java/com/agpTest/libWithClasses/A.kt")
        build.executor.run("${AndroidProjectDefinition.DEFAULT_APP_PATH}:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().classes().apply {
                doesNotContain("com/agpTest/libWithClasses/A")
                contains("com/agpTest/libWithClasses/B")
                contains("com/agpTest/libWithClasses/C")
            }
        }
    }

    @Test
    fun testModifyingClassIncrementally() {
        val build = project.build
        build.executor.run("${AndroidProjectDefinition.DEFAULT_APP_PATH}:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().classes().apply {
                contains("com/agpTest/libWithClasses/A")
                contains("com/agpTest/libWithClasses/B")
                contains("com/agpTest/libWithClasses/C")
            }
        }
        // Remove file that impacts file ordering.
        build.androidLibrary().files.remove("src/main/java/com/agpTest/libWithClasses/A.kt")
        build.androidLibrary().files.update("src/main/java/com/agpTest/libWithClasses/B.kt")
            .searchAndReplace("class B {}", """class B { fun bar () {} }""")
        build.executor.run("${AndroidProjectDefinition.DEFAULT_APP_PATH}:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().classes().apply {
                doesNotContain("com/agpTest/libWithClasses/A")
                contains("com/agpTest/libWithClasses/C")
            }
            secondaryDexes().classDefinition("com/agpTest/libWithClasses/B").methods().contains("bar")
        }
    }
}
