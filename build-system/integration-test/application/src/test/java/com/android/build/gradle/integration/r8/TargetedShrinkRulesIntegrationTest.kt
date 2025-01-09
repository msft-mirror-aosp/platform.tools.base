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

package com.android.build.gradle.integration.r8

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.internal.r8.TargetedShrinkRules
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.TestInputsGenerator.jarWithClasses
import com.android.testutils.ZipContents
import com.android.testutils.generateAarWithContent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.junit.Rule
import org.junit.Test

/** Integration test for [TargetedShrinkRules]. */
class TargetedShrinkRulesIntegrationTest {

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
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
            dependencies {
                implementation(project(":androidLib"))
                implementation(project(":javaLib"))
                implementation(getExternalAndroidLib())
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
                    jvmTarget.set(JvmTarget.JVM_1_8)
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
                add("consumer-rules.pro",
                    """
                        -keep class **.ClassInAndroidLib { void methodToKeep(); }
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

                createShrinkRules("-keep class **.ClassInJavaLib { void methodToKeep(); }", forJar = true).apply {
                    (versionedShrinkRules + legacyProguardRules).forEach { (path, contents) ->
                        add("src/main/resources/$path", contents)
                    }
                }
            }
        }
    }

    private fun getExternalAndroidLib(): MavenRepoGenerator.Library {
        val aar = generateAarWithContent(
            packageName = ClassInExternalAndroidLib::class.java.packageName,
            mainJar = jarWithClasses(listOf(ClassInExternalAndroidLib::class.java)),
        )
        val shrinkRules = createShrinkRules("-keep class **.ClassInExternalAndroidLib { void methodToKeep(); }", forJar = false)
        val updatedAar = shrinkRules.addToAar(aar)

        return MavenRepoGenerator.Library(
            mavenCoordinate = "com.external.dependency:androidlib:1.0",
            packaging = "aar",
            artifact = updatedAar
        )
    }

    private fun getExternalJavaLib(): MavenRepoGenerator.Library {
        val jar = jarWithClasses(listOf(ClassInExternalJavaLib::class.java))
        val shrinkRules = createShrinkRules("-keep class **.ClassInExternalJavaLib { void methodToKeep(); }", forJar = true)
        val updatedJar = shrinkRules.addToJar(jar)

        return MavenRepoGenerator.Library(
            mavenCoordinate = "com.external.dependency:javalib:1.0",
            packaging = "jar",
            artifact = updatedJar
        )
    }

    @Test
    fun `test targeted shrinking rules are processed correctly`() {
        val build = rule.build

        build.executor.run(":app:assembleRelease")
        build.androidApplication().assertApk(ApkSelector.RELEASE) {
            hasClass("Lcom/example/androidlib/ClassInAndroidLib;").that().apply {
                hasMethods("methodToKeep")
                doesNotHaveMethod("methodToRemove")
            }
            hasClass("Lcom/example/javalib/ClassInJavaLib;").that().apply {
                hasMethods("methodToKeep")
                doesNotHaveMethod("methodToRemove")
            }
            hasClass(ClassInExternalAndroidLib::class.java).that().apply {
                hasMethods("methodToKeep")
                doesNotHaveMethod("methodToRemove")
            }
            hasClass(ClassInExternalJavaLib::class.java).that().apply {
                hasMethods("methodToKeep")
                doesNotHaveMethod("methodToRemove")
            }
        }
    }
}

@Suppress("unused") // Used in this test (indirectly)
private class ClassInExternalAndroidLib {
    fun methodToKeep() {}
    fun methodToRemove() {}
}

@Suppress("unused") // Used in this test (indirectly)
private class ClassInExternalJavaLib {
    fun methodToKeep() {}
    fun methodToRemove() {}
}

private class ShrinkRules(
    val versionedShrinkRules: Map<String, String>,
    val legacyProguardRules: Map<String, String>
)

private fun createShrinkRules(
    shrinkRules: String,
    /** Set to `true` if the shrink rules are created for a JAR, set to `false` for an AAR. */
    forJar: Boolean
): ShrinkRules {
    return ShrinkRules(
        versionedShrinkRules = mapOf(
            // For this integration test, we add the shrink rules to `r8-from-8.2.0` only as we want
            // to test that the current AGP consumes the shrink rules from that location only, not
            // from the other locations.
            "META-INF/com.android.tools/r8-from-8.2.0/r8-from-8.2.0.ext" to shrinkRules,
            "META-INF/com.android.tools/r8-from-8.0.0-upto-8.2.0/r8-from-8.0.0-upto-8.2.0.ext" to "# R8-from-8.0.0-upto-8.2.0 rules",
            "META-INF/com.android.tools/r8-upto-8.0.0/r8-upto-8.0.0.ext" to "# R8-upto-8.0.0 rules",
            "META-INF/com.android.tools/proguard/proguard.ext" to "# Proguard rules"
        ),
        legacyProguardRules = if (forJar) {
            mapOf("META-INF/proguard/proguard.pro" to "# Legacy Proguard rules")
        } else {
            mapOf("proguard.txt" to "# Legacy Proguard rules")
        }
    )
}

private fun ShrinkRules.addToJar(
    jar: ByteArray,
    includeLegacyProguardRules: Boolean = true
): ByteArray {
    val shrinkRules: Map<String, ByteArray> = if (includeLegacyProguardRules) {
        (versionedShrinkRules + legacyProguardRules).mapValues { it.value.toByteArray() }
    } else {
        versionedShrinkRules.mapValues { it.value.toByteArray() }
    }
    return (ZipContents.fromByteArray(jar) + ZipContents(shrinkRules)).toByteArray()
}

private fun ShrinkRules.addToAar(aar: ByteArray): ByteArray {
    val aarContents = ZipContents.fromByteArray(aar)
    val classesJar = aarContents.entries["classes.jar"] ?: ZipContents(emptyMap()).toByteArray()
    val updatedClassesJar = addToJar(classesJar, includeLegacyProguardRules = false)
    val updatedAarEntries = aarContents.entries + mapOf("classes.jar" to updatedClassesJar)

    check(legacyProguardRules.size <= 1) {
        "Unexpected number of legacy Proguard rule files for an AAR: ${legacyProguardRules.size}"
    }
    val legacyProguardRulesEntries = legacyProguardRules.mapValues { it.value.toByteArray() }

    return ZipContents(updatedAarEntries + legacyProguardRulesEntries).toByteArray()
}
