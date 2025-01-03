/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.build.gradle.integration.library

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.TestInputsGenerator.jarWithClasses
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.junit.Rule
import org.junit.Test

/** Tests for libraries with resources.  */
class LibraryClassVerificationTest {
    @get:Rule
    val rule = GradleRule.from {
        androidLibrary(":lib") {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android {
                namespace = "com.example.lib"
                defaultConfig.minSdk = 21
            }
            kotlin {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
            dependencies {
                implementation(project(":otherlib"))
                implementation(MavenRepoGenerator.Library("com.example.base:base:0.1",
                    jarWithClasses(listOf(BaseClass::class.java)),
                ))
                implementation(localJar("embedded.jar") { addEmptyClasses("com/example/EmbeddedJarClass") })
                compileOnly(localJar("compileOnly.jar") { addEmptyClasses("com/example/CompileOnlyJarClass") })
            }
            files {
                add(
                    "src/main/java/com/example/lib/Use.kt",
                    //language=kotlin
                    """
                        package com.example.lib
                        import com.android.build.gradle.integration.library.BaseClass
                        import com.example.EmbeddedJarClass
                        import com.example.otherlib.OtherLibClass
                        import com.example.otherlib.R as OtherLibR

                        class Use : EmbeddedJarClass() {
                            fun useBaseClass(): Int {
                                return BaseClass().x
                            }
                            fun useOtherLibClass(otherLibClass: OtherLibClass): Int {
                                return otherLibClass.y
                            }
                            fun useMyString(): Int {
                                return R.string.my_string
                            }
                           fun useOtherLibString(): Int {
                                return OtherLibR.string.otherlib_string
                            }
                        }
                    """.trimIndent())
                add(
                    "src/main/res/values/strings.xml",
                    //language=xml
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources><string name="my_string">My String</string></resources>
                    """.trimIndent())
            }
        }
        androidLibrary(":otherlib") {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android {
                namespace = "com.example.otherlib"
                defaultConfig.minSdk = 21
            }
            kotlin {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
            files {
                add(
                    "src/main/java/com/example/otherlib/OtherLibClass.kt",
                    //language=kotlin
                    """
                        package com.example.otherlib

                        class OtherLibClass(val y: Int = R.string.otherlib_string)
                    """.trimIndent())
                add(
                    "src/main/res/values/strings.xml",
                    //language=xml
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <resources><string name="otherlib_string">Other Lib String</string></resources>
                    """.trimIndent())
            }
        }
        gradleProperties {
            add(BooleanOption.VERIFY_AAR_CLASSES, true)
        }
    }

    @Test
    fun checkInvalidClasses() {
        val build = rule.build
        val androidLibrary = build.androidLibrary(":lib")

        // Check the debug and release builds pass
        build.executor.run(":lib:assembleDebug")
        build.executor.run(":lib:assembleRelease")

        // Add reference to compile only jar, which should fail the release build
        androidLibrary.files.update("src/main/java/com/example/lib/Use.kt")
            .searchAndReplace(
                "EmbeddedJarClass",
                "CompileOnlyJarClass")

        // Verify debug build still passes, as verification only affects release builds
        build.executor.run(":lib:assembleDebug")
        // Verify release build fails with a useful error message
        val result = build.executor.expectFailure().run(":lib:assembleRelease")
        assertThat(result.stderr).contains("Error: Missing class com.example.CompileOnlyJarClass (referenced from: void com.example.lib.Use.<init>() and 1 other context)")

        // override to disable in that particular project
        androidLibrary.reconfigure {
            android {
                experimentalProperties["android.experimental.verifyLibraryClasses"] = false
            }
        }
        build.executor.run(":lib:assembleRelease")
    }
}

open class BaseClass {
    var x: Int = 2
}
