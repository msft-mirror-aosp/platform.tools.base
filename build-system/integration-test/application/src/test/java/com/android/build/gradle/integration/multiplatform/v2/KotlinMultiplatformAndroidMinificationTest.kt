/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.utils.FileUtils
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformAndroidMinificationTest {

    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .disableBuiltInKotlin()
        .create()

    @Before
    fun setUp() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            """
                kotlin.android {
                    optimization {
                        minify = true
                        consumerKeepRules.files.add(
                            File(project.projectDir, "consumer-proguard-rules.pro")
                        )
                        keepRules.file("proguard-rules.pro")
                        consumerKeepRules.publish = true
                    }
                }
            """.trimIndent()
        )

        TestFileUtils.appendToFile(
            project.getSubproject("app").ktsBuildFile,
            """
                android {
                    buildTypes {
                        getByName("debug") {
                            isMinifyEnabled = true
                            isShrinkResources = true
                            proguardFiles(
                                getDefaultProguardFile("proguard-android-optimize.txt"),
                                "proguard-rules.pro"
                            )
                        }
                    }
                }
            """.trimIndent()
        )
    }

    @Test
    fun testKmpLibClassesAreMinified() {
        executor().run(":kmpFirstLib:assemble")

        project.getSubproject("kmpFirstLib").assertAar(AarSelector.NO_BUILD_TYPE) {
            mainJar {
                classes().containsExactly("com/example/kmpfirstlib/KmpAndroidActivity")
                resources().containsExactly("kmp_resource.txt")
            }
        }
    }

    @Test
    fun testAppClassesAreMinified() {
        executor().run(":app:assembleDebug")

        project.getSubproject("app").assertApk(ApkSelector.DEBUG) {
            // only the main activity is left
            mainDex().containsExactly("com/example/kmpfirstlib/KmpAndroidActivity")
        }
    }

    @Test
    fun testProguardRulesInKmpLib() {
        FileUtils.writeToFile(
            project.getSubproject("kmpFirstLib").file("proguard-rules.pro"),
            """
                -keep public class com.example.kmpfirstlib.KmpAndroidFirstLibClass {
                    java.lang.String callCommonLibClass();
                    java.lang.String callAndroidLibClass();
                 }

                 -keep public class com.example.kmpfirstlib.KmpAndroidFirstLibJavaClass {
                    java.lang.String callCommonLibClass();
                    java.lang.String callAndroidLibClass();
                 }
            """.trimIndent()
        )

        executor().run(":kmpFirstLib:assemble")

        project.getSubproject("kmpFirstLib").assertAar(AarSelector.NO_BUILD_TYPE) {
            mainJar {
                // code is optimized by default, and so the invocations to classes from common
                // and androidLib are replaced by a literal string and removed.
                classes().containsExactly(
                    "com/example/kmpfirstlib/KmpAndroidActivity",
                    "com/example/kmpfirstlib/KmpAndroidFirstLibClass",
                    "com/example/kmpfirstlib/KmpAndroidFirstLibJavaClass",
                )
                resources().containsExactly("kmp_resource.txt")
            }
        }
    }

    @Test
    fun testProguardRulesInApp() {
        FileUtils.writeToFile(
            project.getSubproject("app").file("proguard-rules.pro"),
            """
                -keep public class com.example.app.AndroidApp { *; }
                -processkotlinnullchecks keep
            """.trimIndent()
        )

        executor().run(":app:assembleDebug")

        project.getSubproject("app").assertApk(ApkSelector.DEBUG) {
            mainDex().containsExactly(
                "com/example/androidlib/AndroidLib",
                "com/example/app/AndroidApp",
                "com/example/kmpfirstlib/KmpAndroidActivity",
                "com/example/kmpfirstlib/KmpAndroidFirstLibClass",
                "com/example/kmpfirstlib/KmpCommonFirstLibClass",
                "com/example/kmpsecondlib/KmpAndroidSecondLibClass",
                "com/example/kmplibraryplugin/KmpLibraryPluginAndroidClass",
                "com/example/kmplibraryplugin/KmpLibraryPluginCommonClass",
                "com/example/kmpjvmonly/KmpCommonJvmOnlyLibClass",
                "com/example/kmpjvmonly/KmpJvmOnlyLibClass",
                "kotlin/jvm/internal/Intrinsics"
            )
        }
    }

    @Test
    fun testConsumerProguardRulesFromKmpLib() {
        FileUtils.writeToFile(
            project.getSubproject("kmpFirstLib").file("consumer-proguard-rules.pro"),
            """
                -keep public class com.example.kmpfirstlib.KmpAndroidFirstLibClass {
                    java.lang.String callCommonLibClass();
                    java.lang.String callKmpSecondLibClass();
                 }
            """.trimIndent()
        )

        executor().run(":app:assembleDebug")

        project.getSubproject("app").assertApk(ApkSelector.DEBUG) {
            mainDex().containsExactly(
                "com/example/kmpfirstlib/KmpAndroidActivity",
                "com/example/kmpfirstlib/KmpAndroidFirstLibClass",
                "com/example/kmpfirstlib/KmpCommonFirstLibClass",
                "com/example/kmpsecondlib/KmpAndroidSecondLibClass",
                "com/example/kmplibraryplugin/KmpLibraryPluginAndroidClass",
                "com/example/kmplibraryplugin/KmpLibraryPluginCommonClass",
            )
        }
    }

    @Test
    fun testProguardTxtIncludedInAar() {
        FileUtils.writeToFile(
            project.getSubproject("kmpFirstLib").file("consumer-proguard-rules.pro"),
            """
                -keep class com.example.kmpfirstlib.** { *; }
            """.trimIndent()
        )

        executor().run(":kmpFirstLib:bundleAndroidMainAar")

        project.getSubproject("kmpFirstLib").assertAar(AarSelector.NO_BUILD_TYPE) {
            textFile("proguard.txt").isEqualTo(
                """
                   -keep class com.example.kmpfirstlib.** { *; }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `test disabling consumer proguard rules from kmp lib`() {
        TestFileUtils.searchAndReplace(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            "consumerKeepRules.publish = true",
            "consumerKeepRules.publish = false"
        )
        FileUtils.writeToFile(
            project.getSubproject("kmpFirstLib").file("consumer-proguard-rules.pro"),
            """
                -keep public class com.example.kmpfirstlib.KmpAndroidFirstLibClass {
                    java.lang.String callCommonLibClass();
                    java.lang.String callKmpSecondLibClass();
                 }
            """.trimIndent()
        )

        executor().run(":app:assembleDebug")

        project.getSubproject("app").assertApk(ApkSelector.DEBUG) {
            mainDex().containsExactly("com/example/kmpfirstlib/KmpAndroidActivity")
        }
    }

    private fun executor() = project.executor().withFailOnWarning(false) // b/455891987
}
