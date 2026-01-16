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
import java.io.File

class GradualR8KeepRulesSourceSetTest {

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
                implementation(project(":javaLib"))
            }
        }.files {
            add(
                "src/main/java/com/example/app/ClassInAndroidApp.kt",
                //language=kotlin
                """
                        package com.example.app
                        class ClassInAndroidApp {
                            fun method(){}
                        }
                    """.trimIndent()
            )
            add(
                "src/main/java/com/example/app/ClassInAndroidApp2.kt",
                //language=kotlin
                """
                        package com.example.app
                        class ClassInAndroidApp2 {
                            fun method(){}
                        }
                    """.trimIndent()
            )
        }
        androidLibrary(":androidLib") {
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
                    "src/main/java/com/example/androidlib/ClassInAndroidLib2.kt",
                    //language=kotlin
                    """
                        package com.example.androidlib
                        class ClassInAndroidLib2 {
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
    fun `test app r8 optimization everything by default`(){
        val build = rule.build
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().subPackage("com/example/app").containsExactly(listOf())
            classes().subPackage("com/example/androidlib").containsExactly(listOf())
            classes().subPackage("com/example/javalib").containsExactly(listOf())
        }
    }

    @Test
    fun `test app r8 optimization with app keepRules sourceSet`(){
        val build = rule.build {
            androidApplication {
            }.files{
                add("src/main/keepRules/my.keep", "-keep class com.example.app.ClassInAndroidApp { *; }")
            }
        }
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().subPackage("com/example/app").containsExactly("ClassInAndroidApp")
        }
    }

    @Test
    fun `test app r8 optimization with app keepRules sourceSet with folder tree`(){
        val build = rule.build {
            androidApplication {
            }.files{
                add("src/main/keepRules/some/folder/my.keep", "-keep class com.example.app.ClassInAndroidApp { *; }")
            }
        }
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().subPackage("com/example/app").containsExactly("ClassInAndroidApp")
        }
    }

    @Test
    fun `test lib r8 optimization with app keepRules sourceSet`(){
        val build = rule.build {
            androidLibrary(":androidLib") {
            }.files{
                add("src/main/keepRules/my.keep", "-keep class com.example.androidlib.ClassInAndroidLib { *; }")
            }
        }
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().subPackage("com/example/androidlib").containsExactly("ClassInAndroidLib")
        }
    }

    @Test
    fun `test r8 optimization skip java lib with app keepRules in sourceSet`(){
        val build = rule.build {
            androidApplication {
            }.files{
                add("src/main/keepRules/my.keep", "-keep class com.example.javalib.ClassInJavaLib { *; }")
            }
        }
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().subPackage("com/example/javalib").containsExactly("ClassInJavaLib")
            classes().subPackage("com/example/app").containsExactly(listOf())
            classes().subPackage("com/example/androidlib").containsExactly(listOf())
        }
    }

    @Test
    fun `test r8 full optimization with keep rules and sourceSet`() {
        val build = rule.build {
            androidApplication {
                android {
                    buildTypes {
                        named("release") {
                            it.optimization {
                                keepRules {
                                    files.add(File("keep.pro"))
                                }
                            }
                        }
                    }
                }
            }.files {
                add("keep.pro", "-keep class com.example.androidlib.ClassInAndroidLib { *; }")
                add("src/main/keepRules/my.keep", "-keep class com.example.androidlib.ClassInAndroidLib2 { *; }")
            }
        }
        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            // keep these classes
            classes().subPackage("com/example/androidlib").containsExactly("ClassInAndroidLib","ClassInAndroidLib2")
            // optimize everything else
            classes().subPackage("com/example/app").containsExactly(listOf())
            classes().subPackage("com/example/javaLib").containsExactly(listOf())
        }
    }

}
