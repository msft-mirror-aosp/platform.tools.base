/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.build.gradle.integration.api

import com.android.Version
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.testutils.TestUtils
import org.junit.Rule
import org.junit.Test

/** Integration test to ensure that plugin binary compatibility of AGP APIs is preserved. */
class NewDslApiCompatibilityTest {

  @get:Rule
  val rule =
    GradleRule.from {
      buildFileType = BuildFileType.KTS

      rootProject {
        files {
          val localRepos = GradleTestProject.localRepositories
          val repoUrls = localRepos.joinToString("\n") { "maven { url = uri(\"${it.toUri()}\") }" }

          add(
            "buildSrc/settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    $repoUrls
                    mavenCentral()
                    google()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    $repoUrls
                    mavenCentral()
                    google()
                }
            }
            """
              .trimIndent(),
          )

          add(
            "buildSrc/build.gradle.kts",
            """
            plugins {
                id("org.jetbrains.kotlin.jvm") version "${TestUtils.KOTLIN_VERSION_FOR_TESTS}"
                `java-gradle-plugin`
            }

            dependencies {
                compileOnly(gradleApi())
                implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:${TestUtils.KOTLIN_VERSION_FOR_TESTS}")
                compileOnly("com.android.tools.build:gradle:7.4.1")
                runtimeOnly("com.android.tools.build:gradle:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
            }

            gradlePlugin {
                plugins {
                    create("examplePlugin") {
                        id = "com.example.apiuser.example-plugin"
                        implementationClass = "com.example.apiuser.ExamplePlugin"
                    }
                }
            }
            """
              .trimIndent(),
          )

          add(
            "buildSrc/src/main/kotlin/com/example/apiuser/ExamplePlugin.kt",
            """
            package com.example.apiuser

            import com.android.build.api.variant.AndroidComponentsExtension
            import org.gradle.api.Plugin
            import org.gradle.api.Project

            class ExamplePlugin : Plugin<Project> {
                override fun apply(project: Project) {
                    project.plugins.withId("com.android.library") {
                        configure(project)
                    }

                    project.afterEvaluate {
                        val hasApp = project.plugins.hasPlugin("com.android.application")
                        val hasLib = project.plugins.hasPlugin("com.android.library")
                        if (!hasApp && !hasLib) {
                            throw IllegalStateException(
                                "To use com.example.apiuser.example-plugin " +
                                        "you also need to apply one of the following:\n" +
                                        " * com.android.application or\n" +
                                        " * com.android.library"
                            )
                        }
                    }
                }

                private fun configure(project: Project) {
                    val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

                    androidComponents.finalizeDsl { extension ->
                        extension.buildTypes.all { buildType ->
                            val libBuildType = buildType as com.android.build.api.dsl.LibraryBuildType
                            println("Build type ${'$'}{libBuildType.name} evaluated")
                            libBuildType.manifestPlaceholders["customKey"] = "customValue"
                        }
                        extension.productFlavors.all { flavor ->
                            val libFlavor = flavor as com.android.build.api.dsl.LibraryProductFlavor
                            println("Product flavor ${'$'}{libFlavor.name} evaluated")
                            libFlavor.manifestPlaceholders["customKey"] = "customValue"
                        }
                    }

                    project.tasks.register("examplePluginTask", ExampleTask::class.java) { task ->
                        task.configure(androidComponents)
                    }
                }
            }
            """
              .trimIndent(),
          )

          add(
            "buildSrc/src/main/kotlin/com/example/apiuser/ExampleTask.kt",
            """
            package com.example.apiuser

            import com.android.build.api.variant.AndroidComponentsExtension
            import org.gradle.api.DefaultTask
            import org.gradle.api.file.DirectoryProperty
            import org.gradle.api.file.RegularFileProperty
            import org.gradle.api.tasks.InputFile
            import org.gradle.api.tasks.Internal
            import org.gradle.api.tasks.TaskAction

            abstract class ExampleTask: DefaultTask() {

                @get:InputFile
                abstract val adbExecutable: RegularFileProperty

                @get:Internal
                abstract val sdkDirectory: DirectoryProperty

                @TaskAction
                fun doThings() {
                    check(sdkDirectory.get().asFile.exists()) {
                        "Sdk dir ${'$'}sdkDirectory exists"
                    }
                    print("Custom task ran OK")
                }

                fun configure(androidComponents: AndroidComponentsExtension<*, *, *>) {
                    adbExecutable.set(androidComponents.sdkComponents.adb)
                    sdkDirectory.set(androidComponents.sdkComponents.sdkDirectory)
                }
            }
            """
              .trimIndent(),
          )
        }
      }

      androidLibrary {
        // Replaces the default version with the internal marker to prevent the framework from
        // writing an explicit version string. This bypasses the issue of the plugin already
        // being on the classpath with an unknown version.
        replaceAppliedPlugin(PluginType.ANDROID_LIB, "__internal_version__")
        applyPlugin(PluginType.Custom("com.example.apiuser.example-plugin"))

        android {
          namespace = "com.example.lib"
          compileSdk = DEFAULT_COMPILE_SDK_VERSION
          flavorDimensions += "color"
          productFlavors { create("yellow") {} }
        }
      }
    }

  @Test
  fun binaryCompatibilityTest() {
    val result = rule.build.executor.run(":lib:examplePluginTask")
    assertThat(result.stdout).contains("Custom task ran OK")
  }
}
