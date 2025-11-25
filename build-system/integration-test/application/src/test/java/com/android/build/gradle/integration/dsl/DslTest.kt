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

package com.android.build.gradle.integration.dsl

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject
import com.android.utils.XmlUtils
import com.google.common.truth.Truth.assertAbout
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.readText

class DslTest {
    @get:Rule
    val rule = GradleRule.configure()
        .disableBrokenNewDslOptOutChecks()
        .from {
            androidApplication { }
        }

    @Test
    fun versionNameSuffix() {
        rule.build {
            androidApplication {
                android {
                    defaultConfig {
                        versionName = "foo"
                    }
                    buildTypes {
                        named("debug") {
                            it.versionNameSuffix = "-suffix"
                        }
                    }
                }
            }
        }

        rule.build.executor.run("processDebugManifest")
        val path = rule.build.androidApplication().buildDir
            .resolve("intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
        assertAbout(PathSubject.paths()).that(path).exists()
        val document = XmlUtils.parseDocument(path.readText(), false)
        val versionName = document.firstChild.attributes.getNamedItem("android:versionName").nodeValue
        assertThat(versionName).named("version name in the manifest").isEqualTo("foo-suffix")
    }

    @Test
    fun extraPropTest() {
        rule.build {
            gradleProperties {
                add(BooleanOption.USE_NEW_DSL, false)
            }
        }
        rule.build.androidApplication(":app").apply {
            files.update("build.gradle") {
                append("""
                    android {
                        buildTypes {
                            debug {
                                ext.foo = "bar"
                            }
                        }
                    }

                    android.applicationVariants.all { variant ->
                        if (variant.buildType.name == "debug") {
                            def foo = variant.buildType.foo
                            if (!foo.equals("bar")) {
                                throw new RuntimeException("direct access to dynamic property failed, got " + foo)
                            }
                            def hasProperty = variant.buildType.hasProperty("foo")
                            if (!hasProperty) {
                                throw new RuntimeException("hasProperty not returning property value, got " + hasProperty)
                            }
                        } else {
                            def hasProperty = variant.buildType.hasProperty("foo")
                            if (hasProperty) {
                                throw new RuntimeException("hasProperty returning property value for buildType " + variant.buildType.name)
                            }
                        }
                    }
                """.trimIndent())
            }
        }
        rule.build.executor.run("tasks")
    }

    @Test
    fun buildConfigEncoding() {
        rule.build {
            androidApplication {
                android {
                    buildFeatures {
                        buildConfig = true
                    }
                    defaultConfig {
                        buildConfigField("String", "test2", "\\u0105")
                    }
                }
            }
        }

        rule.build.executor.run("generateDebugBuildConfig")

        val path = rule.build.androidApplication(":app").buildDir
            .resolve("generated/source/buildConfig/debug/pkg/name/app/BuildConfig.java")
        assertAbout(PathSubject.paths()).that(path).exists()
        val expected = """
            /**
             * Automatically generated file. DO NOT MODIFY
             */
            package pkg.name.app;

            public final class BuildConfig {
              public static final boolean DEBUG = Boolean.parseBoolean("true");
              public static final String APPLICATION_ID = "pkg.name.app";
              public static final String BUILD_TYPE = "debug";
              public static final int VERSION_CODE = -1;
              public static final String VERSION_NAME = "";
              // Field from default config.
              public static final String test2 = ą;
            }
        """.trimIndent()
        // contentWithUnixLineSeparatorsIsExactly is documented to read using the UTF-8 charset.
        assertAbout(PathSubject.paths()).that(path).contentWithUnixLineSeparatorsIsExactly(expected)
    }

    @Test
    fun validateVersionCodeDefaultConfig() {
        rule.build {
            androidApplication {
                android {
                    defaultConfig {
                        versionCode = 0
                    }
                }
            }
        }
        val model = rule.build.modelBuilder.ignoreSyncIssues().fetchModels().container.getProject()
        val issues = model.issues!!.syncIssues
        assertThat(issues).hasSize(1)
        assertThat(issues.first().message).contains("android.defaultConfig.versionCode is set to 0")
    }

    @Test
    fun validateVersionCodeProductFlavor() {
        rule.build {
            androidApplication {
                android {
                    defaultConfig {
                        versionCode = 1
                    }
                    flavorDimensions += "color"
                    productFlavors {
                        create("red") {
                            it.versionCode = -1
                            it.dimension = "color"
                        }
                    }
                }
            }
        }
        val model = rule.build.modelBuilder.ignoreSyncIssues().fetchModels().container.getProject()
        val issues = model.issues!!.syncIssues
        assertThat(issues).hasSize(1)
        assertThat(issues.first().message).contains("versionCode is set to -1 in product flavor red")
    }

    @Test
    fun projectConfigurationWithEmptyFlavorDimension() {
        rule.build {
            androidApplication {
                android {
                    flavorDimensions += "foo"
                }
            }
        }
        rule.build.executor.run("help")
    }
}
