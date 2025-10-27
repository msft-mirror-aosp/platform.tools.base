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

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.integration.common.fixture.LoggingLevel
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.truth.TruthHelper
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.integration.common.utils.disableBuiltInKotlin
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.IntegerOption
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Integration test for the R8 task. */
class R8TaskTest {
    @get:Rule
    val rule = GradleRule.from {
        androidJavaApplication {
            android {
                buildTypes {
                    named("release") {
                        it.isMinifyEnabled = true
                        it.proguardFiles += listOf(
                            File("proguard-rules.pro"),
                            getDefaultProguardFile("proguard-android-optimize.txt")
                        )
                    }
                }
                testBuildType = "release"
            }
            files.add("proguard-rules.pro", "")
        }
        disableBuiltInKotlin()
    }

    @Test
    fun testCheckDuplicateClassesTaskDidWork() {
        val build = rule.build

        build.executor.run(":app:minifyReleaseWithR8").apply {
            assertTask(":app:checkReleaseDuplicateClasses").didWork()
        }
    }

    @Test
    fun testTestedClassesPassedAsClasspathToR8() {
        val build = rule.build
        val app = build.androidApplication()

        val buildResult = build.executor.withLoggingLevel(LoggingLevel.DEBUG)
            .run(":app:assembleReleaseAndroidTest")
        val appClasses = app
            .resolve(InternalArtifactType.COMPILE_APP_CLASSES_JAR)
            .resolve("release/bundleReleaseClassesToCompileJar/classes.jar")
        buildResult.assertOutputContains("[R8] Classpath classes: [$appClasses]")
    }

    @Test
    fun testMissingKeepRules() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    implementation(localJar("lib.jar") {
                        addClassWithEmptyMethods(
                            "test/A",
                            "foo()Ltest/B;", "bar()Ltest/C;")
                    })
                }
                files.update("proguard-rules.pro").replaceWith( "-keep class test.A { *; }")
            }
        }
        val app = build.androidApplication()


        build.executor.expectFailure().run(":app:assembleRelease")
        val missingRules = app.outputsDir.resolve("mapping/release/missing_rules.txt")
        assertThat(missingRules).contentWithUnixLineSeparatorsIsExactly(
                """
                    # Please add these rules to your existing keep rules in order to suppress warnings.
                    # This is generated automatically by the Android Gradle plugin.
                    -dontwarn test.B
                    -dontwarn test.C
                """.trimIndent()
        )

        val result = build.executor.expectFailure().run(":app:assembleRelease")
        result.assertErrorContains("Missing classes detected while running R8.")
    }

    @Test
    fun testOutputMainDexList() {
        val build = rule.build {
            androidApplication {
                enableMultiDex()
            }
        }
        val app = build.androidApplication()

        build.executor.run(":app:assembleRelease")
        val mainDexListFile = app.resolve(InternalArtifactType.LEGACY_MULTIDEX_MAIN_DEX_LIST)
            .resolve("release/minifyReleaseWithR8/mainDexList.txt")
        assertThat(mainDexListFile).exists()
    }

    /**
     * b/181858113
     */
    @Test
    fun testMultiDexKeepFileDeprecationStillAllowed() {
        val build = rule.build {
            androidApplication {
                enableMultiDex()
                android.buildTypes {
                    named("release") {
                        it.multiDexKeepFile = File("multidex-keep-file.txt")
                    }
                }
                files.add("multidex-keep-file.txt", "")
            }
            gradleProperties {
                add(BooleanOption.R8_MAIN_DEX_LIST_DISALLOWED, false)
            }
        }

        val result = build.executor.run(":app:assembleRelease")
        result.assertOutputContains(
                "WARNING: Using multiDexKeepFile property with R8 is deprecated and will be fully " +
                        "removed in AGP 9.0. Please migrate to use multiDexKeepProguard instead."
        )
    }

    @Test
    fun testMultiDexKeepFileDeprecation() {
        val build = rule.build {
            androidApplication {
                enableMultiDex()
                android.buildTypes {
                    named("release") {
                        it.multiDexKeepFile = File("multidex-keep-file.txt")
                    }
                }
                files.add("multidex-keep-file.txt", "")
            }
            gradleProperties {
                add(BooleanOption.R8_MAIN_DEX_LIST_DISALLOWED, true)
            }
        }


        val result = build.executor
            .expectFailure()
            .run(":app:assembleRelease")
        assertThat(result.exception).isNotNull()
    }

    @Test
    fun testInjectedDeviceApi() {
        val build = rule.build {
            androidApplication {
                android {
                    defaultConfig.minSdk = 21
                }
                files.add(
                    "src/main/java/example/MyInterface.java",
                    //language=java
                    """
                        package example;

                        interface MyInterface {
                            static void printContent() { System.out.println("hello"); }
                        }
                    """.trimIndent()
                )
                files.add(
                    "src/main/java/example/MyInterfaceUser.java",
                    //language=java
                    """
                        package example;

                        class MyInterfaceUser {
                            static void printContent() { MyInterface.printContent(); }
                        }
                    """.trimIndent()
                )
                files.update(
                    "proguard-rules.pro").replaceWith(
                    """
                        -keep class example.MyInterface { static void printContent(); }
                        -keep class example.MyInterfaceUser { static void printContent(); }
                        -dontobfuscate
                        -dontoptimize
                    """.trimIndent()
                )
            }
        }
        val app = build.androidApplication()


        build.executor.with(IntegerOption.IDE_TARGET_DEVICE_API, 24).run(":app:assembleRelease")
        app.assertApk(ApkSelector.RELEASE.fromIntermediates()) {
            classes().containsExactly(
                "example/MyInterface",
                "example/MyInterfaceUser",
                "pkg/name/app/HelloWorld",
                "pkg/name/app/R\$layout"
            )
        }

        build.executor.with(IntegerOption.IDE_TARGET_DEVICE_API, 23).run(":app:assembleRelease")
        app.assertApk(ApkSelector.RELEASE.fromIntermediates()) {
            classes().containsExactly(
                "example/MyInterface",
                "example/MyInterface\$-CC",
                "example/MyInterfaceUser",
                "pkg/name/app/HelloWorld",
                "pkg/name/app/R\$layout"
            )
        }
    }

    // regression test for b/210573363
    @Test
    fun testDefaultProguardRules() {
        val build = rule.build
        val app = build.androidApplication()

        build.executor.run(":app:assembleRelease")

        app.reconfigure {
            android {
                buildTypes {
                    named("release") {
                        it.proguardFiles.clear()
                        // have to put back the original file
                        it.proguardFiles += File("proguard-rules.pro")
                    }
                }
            }
        }

        build.executor.run(":app:assembleRelease").apply {
            assertTask(":app:minifyReleaseWithR8").didWork()
        }
    }

    /** Regression test for b/380110863. */
    @Test
    fun `test system properties are passed to forked process`() {
        val build = rule.build {
            settings {
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
        }

        val result = build.executor
            .withArgument("-Dcom.android.tools.r8.desugar.minimizeSyntheticNames=invalid_value")
            .expectFailure()
            .run(":app:minifyReleaseWithR8")
        TruthHelper.assertThat(result.failureMessage).contains(
            "Expected value of com.android.tools.r8.desugar.minimizeSyntheticNames to be a boolean, but was: invalid_value"
        )
    }
}

private fun AndroidProjectDefinition<ApplicationExtension>.enableMultiDex() {
    android {
        defaultConfig {
            minSdk = 20
            multiDexEnabled = true
        }
    }
}
