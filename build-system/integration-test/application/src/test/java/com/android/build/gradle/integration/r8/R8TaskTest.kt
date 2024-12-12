/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.integration.r8

import com.android.build.gradle.integration.common.fixture.LoggingLevel
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.GradleTaskSubject.assertThat
import com.android.build.gradle.integration.common.truth.TruthHelper
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.getOutputDir
import com.android.build.gradle.options.IntegerOption
import com.android.testutils.TestClassesGenerator
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Integration test for the R8 task. */
class R8TaskTest {

    @get:Rule
    val rule = GradleRule.from {
        androidJavaApplication {
            android {
                buildTypes {
                    named("release") {
                        it.isMinifyEnabled = true
                        it.proguardFiles += File("proguard-rules.pro")
                    }
                }
                testBuildType = "release"
            }
        }
    }

    private fun adhocSetup() {
        // The test infra (DslProxy) does not support getDefaultProguardFile() yet, so we need to
        // append the following text.
        // TODO(b/384016091): Clean this up (remove the adhocSetup() method) once the issue is fixed
        app.files.update("build.gradle") {
            it + "\n" +
            """
            android {
                buildTypes {
                    release {
                        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                    }
                }
            }
            """.trimIndent()
        }
    }

    private val executor
        get() = rule.build.executor

    private val app
        get() = rule.build.androidApplication()

    @Test
    fun testCheckDuplicateClassesTaskDidWork() {
        adhocSetup()
        val buildResult = executor.run(":app:minifyReleaseWithR8")
        assertThat(buildResult.getTask(":app:checkReleaseDuplicateClasses")).didWork()
    }

    @Test
    fun testTestedClassesPassedAsClasspathToR8() {
        adhocSetup()
        val buildResult = executor.withLoggingLevel(LoggingLevel.DEBUG)
            .run(":app:assembleReleaseAndroidTest")
        val appClasses = app.getIntermediateFile(
            InternalArtifactType.COMPILE_APP_CLASSES_JAR.getFolderName() + "/release/bundleReleaseClassesToCompileJar/classes.jar"
        )
        buildResult.assertOutputContains("[R8] Classpath classes: [$appClasses]")
    }

    @Test
    fun testMissingKeepRules() {
        adhocSetup()
        app.location.toFile().resolve("lib.jar").also {
            val classToWrite = TestClassesGenerator.classWithEmptyMethods(
                    "A", "foo:()Ltest/B;", "bar:()Ltest/C;")
            ZipOutputStream(it.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("test/A.class"))
                zip.write(classToWrite)
                zip.closeEntry()
            }
        }
        // TODO(b/384016091): Rewrite this code once we have support for adding non-empty local jars
        // with DSL-aware test fixtures.
        app.files.update("build.gradle") {
            it + "\n" +
            """
            dependencies {
                implementation(files("lib.jar"))
            }
            """.trimIndent()
        }
        app.files.add("proguard-rules.pro", "-keep class test.A { *; }")

        executor.expectFailure().run(":app:assembleRelease")
        val missingRules = app.outputsDir.resolve("mapping/release/missing_rules.txt")
        assertThat(missingRules).contentWithUnixLineSeparatorsIsExactly(
                """
                    # Please add these rules to your existing keep rules in order to suppress warnings.
                    # This is generated automatically by the Android Gradle plugin.
                    -dontwarn test.B
                    -dontwarn test.C
                """.trimIndent()
        )

        val result = executor.expectFailure().run(":app:assembleRelease")
        result.assertErrorContains("Missing classes detected while running R8.")
    }

    @Test
    fun testOutputMainDexList() {
        enableMultiDex()
        adhocSetup()

        executor.run(":app:assembleRelease")
        val mainDexListFile = InternalArtifactType.LEGACY_MULTIDEX_MAIN_DEX_LIST
            .getOutputDir(app.buildDir.toFile())
            .resolve("release/minifyReleaseWithR8/mainDexList.txt")
        assertThat(mainDexListFile).exists()
    }

    @Test
    fun testMultiDexKeepFileDeprecation() {
        enableMultiDex()
        app.files.add("multidex-keep-file.txt", "")
        app.reconfigure(buildFileOnly = true) {
            android.buildTypes {
                named("release") {
                    it.multiDexKeepFile = File("multidex-keep-file.txt")
                }
            }
        }
        adhocSetup()

        val result = executor.run(":app:assembleRelease")
        result.assertOutputContains(
                "WARNING: Using multiDexKeepFile property with R8 is deprecated and will be fully " +
                        "removed in AGP 8.0. Please migrate to use multiDexKeepProguard instead."
        )
    }

    @Test
    fun testInjectedDeviceApi() {
        rule.build {
            androidApplication {
                android.defaultConfig.minSdk = 21
            }
        }
        adhocSetup()
        app.files.add(
            "src/main/java/example/MyInterface.java",
            """
            package example;

            interface MyInterface {
                static void printContent() { System.out.println("hello"); }
            }
            """.trimIndent()
        )
        app.files.add(
            "proguard-rules.pro",
            """
            -keep class example.MyInterface* { *; }
            -dontobfuscate
            """.trimIndent()
        )

        executor.with(IntegerOption.IDE_TARGET_DEVICE_API, 24).run(":app:assembleRelease")
        app.assertApk(ApkSelector.RELEASE.fromIntermediates()) {
            doesNotContainClass("Lexample/MyInterface$-CC;")
        }

        executor.with(IntegerOption.IDE_TARGET_DEVICE_API, 23).run(":app:assembleRelease")
        app.assertApk(ApkSelector.RELEASE.fromIntermediates()) {
            hasClass("Lexample/MyInterface$-CC;")
        }
    }

    // regression test for b/210573363
    @Test
    fun testDefaultProguardRules() {
        adhocSetup()
        executor.run(":app:assembleRelease")
        TestFileUtils.searchAndReplace(
            app.location.resolve("build.gradle"),
            "proguardFiles(getDefaultProguardFile(\"proguard-android-optimize.txt\"))",
            ""
        )
        val result = executor.run(":app:assembleRelease")
        assertThat(result.getTask(":app:minifyReleaseWithR8")).didWork()
    }

    /** Regression test for b/380110863. */
    @Test
    fun `test system properties are passed to forked process`() {
        rule.build.reconfigureSettings {
            applyPlugin(PluginType.ANDROID_SETTINGS)
            android.execution {
                profiles {
                    create("runInSeparateProcess") {
                        it.r8.runInSeparateProcess = true
                    }
                }
                defaultProfile = "runInSeparateProcess"
            }
        }
        val result = executor
            .withArgument("-Dcom.android.tools.r8.experimental.enablewhyareyounotinlining=invalid_value")
            .expectFailure()
            .run(":app:minifyReleaseWithR8")
        TruthHelper.assertThat(result.failureMessage).contains(
            "Expected value of com.android.tools.r8.experimental.enablewhyareyounotinlining to be a boolean, but was: invalid_value"
        )
    }

    private fun enableMultiDex() {
        rule.build {
            androidApplication {
                android {
                    defaultConfig {
                        minSdk = 20
                        multiDexEnabled = true
                    }
                }
            }
        }
    }
}
