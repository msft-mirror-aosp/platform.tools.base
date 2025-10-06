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

import com.android.build.gradle.integration.common.fixture.project.AndroidApplicationProject
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.r8.android.ExternalAndroidLibClass
import com.android.build.gradle.integration.r8.java.ExternalJavaLibClass
import com.android.build.gradle.internal.scope.InternalArtifactType.MERGED_PACKAGES_FOR_R8
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.TestInputsGenerator.jarWithClasses
import com.android.testutils.ZipContents
import com.android.testutils.generateAarWithContent
import com.android.testutils.truth.PathSubject
import com.google.common.truth.Truth
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.junit.Rule
import org.junit.Test
import kotlin.collections.plus

/** Integration test for gradual R8 feature. */
class GradualR8CollectPackagesTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android {
                defaultConfig.minSdk = 24
                buildTypes {
                    named("release") {
                        it.isMinifyEnabled = true
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
                implementation(getExternalAndroidLib())
                implementation(getExternalAndroidLib2()) // no-op
                implementation(getExternalJavaLib())
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
                """.trimIndent())
                add("src/main/resources/META-INF/proguard/proguard.ext", "# Proguard rules")
            }
        }
    }

    private fun getExternalAndroidLib(): MavenRepoGenerator.Library {
        val aar = generateAarWithContent(
            packageName = "com.external.android.lib.name",
            mainJar = jarWithClasses(listOf(ExternalAndroidLibClass::class.java)),
            extraFiles = mapOf("proguard.txt" to "".toByteArray())
        )
        return MavenRepoGenerator.Library(
            mavenCoordinate = "com.external.dependency:androidlib:1.0",
            packaging = "aar",
            artifact = aar
        )
    }

    // An external Android library without proguard rules to validate no-op
    private fun getExternalAndroidLib2(): MavenRepoGenerator.Library {
        val aar = generateAarWithContent(
            packageName = "com.external.android.lib2.name",
            mainJar = jarWithClasses(listOf(ExternalAndroidLib2Class::class.java))
        )
        return MavenRepoGenerator.Library(
            mavenCoordinate = "com.external.dependency:androidlib2:1.0",
            packaging = "aar",
            artifact = aar
        )
    }

    // A class to place in an external Android lib to test no-op scenario
    internal class ExternalAndroidLib2Class {
        fun methodToKeep() {}
        fun methodToRemove() {}
    }

    private fun getExternalJavaLib(): MavenRepoGenerator.Library {
        val jar = jarWithClasses(listOf(ExternalJavaLibClass::class.java))
        val shrinkRules =
            mapOf("META-INF/proguard/proguard.ext" to "# Proguard rules")
        val updatedJar = addShrinkRulesToJar(jar, shrinkRules.mapValues { it.value.toByteArray() })
        return MavenRepoGenerator.Library(
            mavenCoordinate = "com.external.dependency:javalib:1.0",
            packaging = "jar",
            artifact = updatedJar
        )
    }

    @Test
    fun `test gradual r8 filter all`() {
        val build = rule.build
        val app = build.androidApplication()

        // Validate package list artifact does not exist when boolean option flag is not present
        build.executor.run(":app:assembleRelease")

        checkNoPackageTxt(app)

        build.executor.with(BooleanOption.GRADUAL_R8_SHRINKING, true).run(":app:assembleRelease")

        verifyPackagesTxt(app)

        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().containsAtLeast(
                ExternalAndroidLib2Class::class.java.filePath(),
                "com/example/androidlib2/ClassInAndroidLib2"
            )
        }
    }

    @Test
    fun `test gradual r8 no optimization for application classes`() {
        val build = rule.build
        val app = build.androidApplication()
        app.files.add(
            "src/main/java/com/example/app/ClassInAndroidApp.kt",
            //language=kotlin
            """
                        package com.example.app
                        class ClassInAndroidApp {
                            fun method1() {}
                            fun method2() {}
                        }
                    """.trimIndent()
        )

        build.executor.with(BooleanOption.GRADUAL_R8_SHRINKING, true).run(":app:assembleRelease")

        verifyPackagesTxt(app)

        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            classes().containsAtLeast(
                ExternalAndroidLib2Class::class.java.filePath(),
                "com/example/app/ClassInAndroidApp"
            )
        }
    }

    private fun Class<*>.filePath() = name.replace(".","/")

    private fun checkNoPackageTxt(app: AndroidApplicationProject) {
        // no packages.txt
        val intermediateMergedPackageList = app
            .resolve(MERGED_PACKAGES_FOR_R8)
            .resolve("release/mergeReleasePackageListsForR8/packages.txt")
            .toFile()
        PathSubject.assertThat(intermediateMergedPackageList).doesNotExist()
    }

    private fun verifyPackagesTxt(app: AndroidApplicationProject) {
        // Should include package names from:
        // - local Android module class
        // - local Java module class
        // - external Android lib manifest package name
        // - ExternalAndroidLibClass path
        // - ExternalJavaLibClass path
        Truth.assertThat(
            app.resolve(MERGED_PACKAGES_FOR_R8).resolve(
                "release/mergeReleasePackageListsForR8/packages.txt"
            ).toFile().readText()
        ).isEqualTo(
            """
                    com.example.androidlib.*
                    com.example.javalib.*
                    com.external.android.lib.name.*
                    com.android.build.gradle.integration.r8.android.*
                    com.android.build.gradle.integration.r8.java.*
                """.trimIndent()
        )
    }
}

private fun addShrinkRulesToJar(jar: ByteArray, shrinkRules: Map<String, ByteArray>): ByteArray {
    return (ZipContents.fromByteArray(jar) + ZipContents(shrinkRules)).toByteArray()
}
