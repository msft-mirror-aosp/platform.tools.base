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

package com.android.build.gradle.integration.r8

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.junit.Rule
import org.junit.Test

class GradualR8ApiTest {

    @get:Rule
    val rule = GradleRule.from {
        gradleProperties {
            add(BooleanOption.R8_GRADUAL_API, true)
        }
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android {
                defaultConfig.minSdk = 24
                buildTypes {
                    named("release") {
                        it.optimization{
                            enable = true
                        }
                    }
                }
            }
            kotlin {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
            dependencies {
                implementation(project(":androidLib"))
                implementation(project(":androidLib2")) // no-op
                implementation(project(":javaLib"))
            }
        }
        androidLibrary(":androidLib") {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android {
                defaultConfig {
                    minSdk = 24
                    consumerProguardFiles("consumer-rules.pro")
                }
            }
            kotlin {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
            files {
                add(
                    "src/main/java/com/example/androidlib/ClassInAndroidLib.kt",
                    //language=kotlin
                    """
                        package com.example.androidlib
                        class ClassInAndroidLib {
                            fun methodToKeep() {}
                            fun methodToRemove() {}
                        }
                    """.trimIndent()
                )
                add(
                    "src/main/java/com/example/androidlib/internal/ClassInAndroidLib2.kt",
                    //language=kotlin
                    """
                        package com.example.androidlib.internal
                        class ClassInAndroidLib2 {
                            fun methodToKeep() {}
                            fun methodToRemove() {}
                        }
                    """.trimIndent()
                )
                add("consumer-rules.pro", "")
            }
        }
        androidLibrary(":androidLib2") { // no consumer proguard file present, added to validate no-op
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android {
                defaultConfig {
                    minSdk = 24
                }
            }
            kotlin {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
            files {
                add(
                    "src/main/java/com/example/androidlib2/ClassInAndroidLib2.kt",
                    //language=kotlin
                    """
                        package com.example.androidlib2
                        class ClassInAndroidLib2 {
                            fun methodToKeep() {}
                            fun methodToRemove() {}
                        }
                    """.trimIndent()
                )
                add(
                    "src/main/java/com/example/androidlib2/ClassInAndroidLib4.kt",
                    //language=kotlin
                    """
                        package com.example.androidlib2
                        class ClassInAndroidLib4 {
                            fun methodToKeep() {}
                            fun methodToRemove() {}
                        }
                    """.trimIndent()
                )
            }
        }
        genericProject(":javaLib") {
            applyPlugin(PluginType.JAVA_LIBRARY)
            applyPlugin(PluginType.KOTLIN_JVM)
            files {
                add(
                    "src/main/java/com/example/javalib/ClassInJavaLib.kt",
                    //language=kotlin
                    """
                    package com.example.javalib
                    class ClassInJavaLib {
                        fun methodToKeep() {}
                        fun methodToRemove() {}
                    }
                """.trimIndent()
                )
                add(
                    "src/main/resources/META-INF/com.android.tools/proguard/proguard.ext",
                    "# Proguard rules"
                )
            }
        }
    }

    @Test
    fun `test gradual r8 no optimization`() {
        val build = rule.build {
            androidApplication {
                android {
                    buildTypes {
                        named("release") {
                            it.optimization {
                                packageScope.set(listOf())
                            }
                        }
                    }
                }
            }
        }
        val result = build.executor.expectFailure().run(":app:assembleRelease")
        result.assertErrorContains("Wrong configuration.")
    }

    @Test
    fun `test gradual r8 partial optimization for package + subpackages`() {
        val build = rule.build {
            androidApplication {
                android {
                    buildTypes {
                        named("release") {
                            it.optimization.packageScope.add("com.example.androidlib.**")
                        }
                    }
                }
            }
        }
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().subPackage("com/example/androidlib").isEmpty()
            classes().subPackage("com/example/androidlib/internal").isEmpty()
        }
    }

    @Test
    fun `test gradual r8 partial optimization for package only`() {
        val build = rule.build {
            androidApplication {
                android {
                    buildTypes {
                        named("release") {
                            it.optimization.packageScope.add("com.example.androidlib.*")
                        }
                    }
                }
            }
        }
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().subPackage("com/example/androidlib")
                .containsExactly("internal/ClassInAndroidLib2")
        }
    }

    @Test
    fun `test gradual r8 partial optimization for class`() {
        val build = rule.build {
            androidApplication {
                android {
                    buildTypes {
                        named("release") {
                            it.optimization.packageScope.add("com.example.androidlib2.ClassInAndroidLib2")
                        }
                    }
                }
            }
        }
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().subPackage("com/example/androidlib2")
                .containsExactly("ClassInAndroidLib4")
        }
    }

    @Test
    fun `test gradual r8 full optimization`() {
        val build = rule.build {
            androidApplication {
                android {
                    buildTypes {
                        named("release") {
                            it.optimization.packageScope.add("**")
                        }
                    }
                }
            }
        }
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().subPackage("com/example/androidlib2").containsExactly(listOf())
            classes().subPackage("com/example/androidlib").containsExactly(listOf())
            classes().subPackage("com/example/javalib").containsExactly(listOf())
        }
        checkMappingFiles(build)
    }

    @Test
    fun `test gradual r8 full optimization with keep rules`() {
        val build = rule.build {
            androidApplication {
                android {
                    buildTypes {
                        named("release") {
                            it.optimization {
                                packageScope.add("**")
                                keepRules {
                                    files.add(java.io.File("keep.pro"))
                                }
                            }
                        }
                    }
                }
            }.files {
                add("keep.pro", "-keep class com.example.androidlib2.ClassInAndroidLib2 { *; }")
            }
        }
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            // keep this class
            classes().subPackage("com/example/androidlib2").containsExactly("ClassInAndroidLib2")
            // optimize everything else
            classes().subPackage("com/example/androidlib").containsExactly(listOf())
            classes().subPackage("com/example/javalib").containsExactly(listOf())
        }
        checkMappingFiles(build)

    }

    @Test
    fun `test gradual r8 default optimization`() {
        val build = rule.build
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().subPackage("com/example/androidlib2").containsExactly(listOf())
            classes().subPackage("com/example/androidlib").containsExactly(listOf())
            classes().subPackage("com/example/javalib").containsExactly(listOf())
        }
        checkMappingFiles(build)
    }

    private fun checkMappingFiles(build: GradleBuild) {
        build.checkMinifiedTxt("seeds.txt")
        build.checkMinifiedTxt("usage.txt")
        build.checkMinifiedTxt("configuration.txt")
    }

    private fun GradleBuild.checkMinifiedTxt(fileName:String){
        FileUtils.join(
            androidApplication().outputsDir.toFile(),
            "mapping",
            "release",
            fileName
        ).also {
            assertThat(it).exists()
        }
    }

}
