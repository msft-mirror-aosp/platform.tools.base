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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.output.AarSubject
import com.android.build.gradle.integration.common.output.ZipSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class KotlinMultiplatformAndroidPluginTest(private val publishLibs: Boolean) {

    companion object {

        @JvmStatic
        @Parameterized.Parameters(name = "publishLibs={0}")
        fun getOptions() = listOf(false, true)
    }

    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .disableBuiltInKotlin()
        .create()

    @Before
    fun setUpProject() {
        if (publishLibs) {
            project.publishLibs()
        }
    }

    @Test
    fun testKmpLibraryTestApkContentsWithBuildTypeSelection() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            """
                kotlin.android {
                    localDependencySelection {
                      selectBuildTypeFrom.set(listOf("debug"))
                    }
                }
            """.trimIndent()
        )

        project.executor()
            .run(":kmpFirstLib:mergeAndroidDeviceTestJavaResource")

        val androidTestMergedRes = project.getSubproject("kmpFirstLib").getIntermediateFile(
            InternalArtifactType.MERGED_JAVA_RES.getFolderName() + "/androidDeviceTest/mergeAndroidDeviceTestJavaResource/feature-kmpFirstLib.jar"
        )

        ZipSubject.assertThat(androidTestMergedRes) {
            textFile("android_lib_resource.txt").isEqualTo("android lib debug resource")
        }
    }

    @Test
    fun testRunningUnitTests() {
        Assume.assumeFalse(publishLibs)
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            """
                kotlin.androidLibrary.compilations.withType(
                    com.android.build.api.dsl.KotlinMultiplatformAndroidHostTestCompilation::class.java
                ) {
                    enableCoverage = true
                }
            """.trimIndent()
        )

        project.executor()
            .run(":kmpFirstLib:createAndroidHostTestCoverageReport")

        assertWithMessage(
            "Running kmp unit tests should run common tests as well"
        ).that(
            FileUtils.join(
                project.getSubproject("kmpFirstLib").buildDir,
                "reports",
                "tests",
                "testAndroidHostTest",
                "classes"
            ).listFiles()!!.map { it.name }
        ).containsExactly(
            "com.example.kmpfirstlib.KmpAndroidFirstLibClassTest.html",
            "com.example.kmpfirstlib.KmpCommonFirstLibClassTest.html"
        )

        val coveragePackageFolder = FileUtils.join(
            project.getSubproject("kmpFirstLib").buildDir,
            "reports", "coverage", "test", "com.example.kmpfirstlib"
        )
        assertThat(coveragePackageFolder.exists()).isTrue()

        assertThat(coveragePackageFolder.listFiles()!!.map { it.name }).containsExactly(
            "index.html",
            "index.source.html",

            "KmpCommonFirstLibClass.html",
            "KmpCommonFirstLibClass.kt.html",

            "KmpAndroidActivity.html",
            "KmpAndroidActivity.kt.html",

            "KmpAndroidFirstLibClass.html",
            "KmpAndroidFirstLibClass.kt.html",

            "KmpAndroidFirstLibJavaClass.html",
            "KmpAndroidFirstLibJavaClass.java.html",
        )

        val packageCoverageReport = FileUtils.join(
            coveragePackageFolder,
            "index.html"
        )

        val generatedCoverageReportHTML = packageCoverageReport.readLines().joinToString("\n")

        val totalCoverageMetricsContents = Regex("<tfoot>(.*?)</tfoot>")
            .find(generatedCoverageReportHTML)
        val totalCoverageInfo = Regex("<td class=\"ctr2\">(.*?)</td>")
            .find(totalCoverageMetricsContents?.groups?.first()!!.value)

        val packageCoveragePercentage = totalCoverageInfo!!.groups[1]!!.value

        assertThat(packageCoveragePercentage.trimEnd('%').toInt() > 0).isTrue()

        project.executor().run(":app:testDebugUnitTest")
    }

    @Test
    fun testAppApkContents() {
        project.executor().run(":app:assembleDebug")

        project.getSubproject("app").assertApk(ApkSelector.DEBUG) {
            classes().containsAtLeast(
                // classes from commonMain are packaged
                "com/example/kmpfirstlib/KmpCommonFirstLibClass",
                "com/example/kmpsecondlib/KmpCommonSecondLibClass",
                // classes from androidMain are packaged
                "com/example/kmpfirstlib/KmpAndroidFirstLibClass",
                "com/example/kmpfirstlib/KmpAndroidFirstLibJavaClass",
                "com/example/kmpsecondlib/KmpAndroidSecondLibClass",
                // transitive deps are packaged
                "com/example/androidlib/AndroidLib",

                "com/example/kmpjvmonly/KmpJvmOnlyLibClass",
                "com/example/kmpjvmonly/KmpCommonJvmOnlyLibClass",

                "com/example/kmplibraryplugin/KmpLibraryPluginAndroidClass",
                "com/example/kmplibraryplugin/KmpLibraryPluginCommonClass",

                "com/example/app/AndroidApp"
            )

            manifest().contains("com.example.kmpfirstlib.KmpAndroidActivity")

            javaResources {
                resourceAsText("kmp_resource.txt").isEqualTo("kmp resource")
                resourceAsText("android_lib_resource.txt").isEqualTo("android lib debug resource")
            }
        }
    }

    @Test
    fun testKmpLibraryAarContents() {
        val action: AarSubject.() -> Unit = {
            textFile("R.txt")
            mainJar {
                classes().containsExactly(
                    "com/example/kmpfirstlib/KmpCommonFirstLibClass",
                    "com/example/kmpfirstlib/KmpAndroidFirstLibClass",
                    "com/example/kmpfirstlib/KmpAndroidFirstLibJavaClass",
                    "com/example/kmpfirstlib/KmpAndroidActivity",
                )
                resources {
                    containsExactly(
                        "kmp_resource.txt",
                        "META-INF/kmpFirstLib.kotlin_module"
                    )
                    resourceAsText("kmp_resource.txt").isEqualTo("kmp resource")
                }
            }
            manifest().apply {
                contains("uses-sdk android:minSdkVersion=\"22\"")
                contains("package=\"com.example.kmpfirstlib\"")
            }
            aarMetadata().minAgpVersion().isEqualTo("7.2.0")
        }

        if (publishLibs) {
            val file = FileUtils.join(
                project.projectDir,
                "testRepo",
                "com", "example", "kmpFirstLib-android", "1.0", "kmpFirstLib-android-1.0.aar"
            )

            AarSubject.assertThat(file, action)
        } else {
            project.executor().run(":kmpFirstLib:assemble")
            project.getSubproject("kmpFirstLib").assertAar(AarSelector.NO_BUILD_TYPE, action)
        }

        if (publishLibs) {
            val zipPath = FileUtils.join(
                project.projectDir,
                "testRepo",
                "com", "example", "kmpFirstLib-android", "1.0", "kmpFirstLib-android-1.0-sources.jar"
            ).toPath()
            ZipSubject.assertThat(zipPath) {
                entries().containsAtLeast(
                    "commonMain/com/example/kmpfirstlib/KmpCommonFirstLibClass.kt",
                    "androidMain/com/example/kmpfirstlib/KmpAndroidActivity.kt",
                    "androidMain/com/example/kmpfirstlib/KmpAndroidFirstLibClass.kt",
                    "androidMain/com/example/kmpfirstlib/KmpAndroidFirstLibJavaClass.java",
                )
            }
        }
    }

    @Test
    fun testKmpLibraryTestApkContents() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            """
                kotlin.android {
                    packaging.resources.excludes.addAll(listOf(
                        "**/*.java",
                        "junit/**",
                        "LICENSE-junit.txt"
                    ))
                }
            """.trimIndent()
        )

        project.executor().run(":kmpFirstLib:assembleDeviceTest")

        project.getSubproject("kmpFirstLib").assertApk(
            ApkSelector.NO_BUILD_TYPE.forTestSuite("androidTest")
        ) {
            // Test apk should be signed by debug signing config
            hasApkSigningBlock()

            applicationId().isEqualTo("com.example.kmpfirstlib.test")

            // full check on this to validate that
            // classes from common tests and unit tests are not packaged
            classes {
                subPackage("com/example/kmpfirstlib").containsExactly(
                    "KmpCommonFirstLibClass",
                    // instrumented test classes are packaged
                    "test/KmpAndroidFirstLibActivityTest$",
                    // classes from androidMain are packaged
                    "KmpAndroidFirstLibClass",
                    "KmpAndroidFirstLibJavaClass",
                    // other
                    "KmpAndroidActivity",
                    "test/R$"
                )
                containsAtLeast(
                    // classes from commonMain are packaged
                    "com/example/kmpsecondlib/KmpCommonSecondLibClass",
                    // classes from androidMain are packaged
                    "com/example/kmpsecondlib/KmpAndroidSecondLibClass",
                    // classes from library dependencies are packaged
                    "com/example/androidlib/AndroidLib",
                    "androidx/test/core/app/ActivityScenario",
                    // classes from jvm only project are packaged
                    "com/example/kmpjvmonly/KmpJvmOnlyLibClass",
                    "com/example/kmpjvmonly/KmpCommonJvmOnlyLibClass",

                    // classes from kmp + library plugin are packaged
                    "com/example/kmplibraryplugin/KmpLibraryPluginAndroidClass",
                    "com/example/kmplibraryplugin/KmpLibraryPluginCommonClass",
                )
            }

            // resources from dependencies are packaged
            contains("resources.arsc")

            manifest().contains("com.example.kmpfirstlib.KmpAndroidActivity")

            javaResources {
                resourceAsText("kmp_resource.txt").isEqualTo("kmp resource")
                resourceAsText("android_lib_resource.txt").isEqualTo("android lib resource")
            }

            // validate all contents by looking at the java resources which in
            // apks are what's left after you remove res, assets, dexfiles, etc...
            javaResources().containsExactly(
                "META-INF/",
                "kmp_resource.txt",
                "android_lib_resource.txt",
                "kotlin/annotation/annotation.kotlin_builtins",
                "kotlin/collections/collections.kotlin_builtins",
                "kotlin/concurrent/atomics/atomics.kotlin_builtins",
                "kotlin/coroutines/coroutines.kotlin_builtins",
                "kotlin/internal/internal.kotlin_builtins",
                "kotlin/kotlin.kotlin_builtins",
                "kotlin/ranges/ranges.kotlin_builtins",
                "kotlin/reflect/reflect.kotlin_builtins"
            )
        }

        val apkIdeRedirectFile = FileUtils.join(
            project.getSubproject("kmpFirstLib").intermediatesDir,
            "apk_ide_redirect_file",
            "androidDeviceTest",
            "createAndroidDeviceTestApkListingFileRedirect",
            "redirect.txt"
        )
        assertThat(apkIdeRedirectFile.exists()).isTrue()
        assertThat(apkIdeRedirectFile.readText())
            .contains("listingFile=../../../../outputs/apk/androidTest/output-metadata.json")
    }
}
