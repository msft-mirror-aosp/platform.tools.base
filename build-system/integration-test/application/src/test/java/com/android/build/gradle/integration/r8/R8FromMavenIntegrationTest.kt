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

package com.android.build.gradle.integration.r8

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.StringOption
import com.android.builder.dexing.R8Version
import com.android.testutils.ZipContents
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Integration tests verifying detached Gradle configuration dependency resolution and [StringOption.R8_VERSION_OVERRIDE] for R8 from Maven.
 */
class R8FromMavenIntegrationTest {

  @get:Rule
  val rule =
    GradleRule.configure().from {
      buildFileType = BuildFileType.KTS
      androidApplication {}
    }

  @Before
  fun setUp() {
    val build = rule.build
    build.androidApplication().files.update("build.gradle.kts") {
      append(
        """

        abstract class VerifyR8Task : DefaultTask() {
            @get:InputFiles
            abstract val r8Classpath: Property<FileCollection>

            @get:Input
            abstract val expectedVersion: Property<String>

            @TaskAction
            fun execute() {
                val files = r8Classpath.get().files
                if (files.isEmpty()) {
                    throw GradleException("R8 classpath resolved no files")
                }
                val version = expectedVersion.get()
                val r8Jar = files.find { it.name.startsWith("r8") && it.name.endsWith(".jar") }
                    ?: throw GradleException("R8 jar not found in resolved files: " + files.map { it.name })

                if (!r8Jar.name.contains(version) && r8Jar.name != "r8.jar") {
                    throw GradleException("Resolved jar '" + r8Jar.name + "' does not match expected version '" + version + "'")
                }
            }
        }

        val r8FromMaven = com.android.build.gradle.internal.r8.R8FromMaven.create(project) { option ->
            providers.gradleProperty(option.propertyName).orNull
        }

        tasks.register<VerifyR8Task>("verifyR8Resolution") {
            this.r8Classpath.set(r8FromMaven.r8Classpath)
            this.expectedVersion.set(r8FromMaven.version)
        }
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun testR8VersionOverrideResolution() {
    val build = rule.build
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    val result = build.executor.with(StringOption.R8_VERSION_OVERRIDE, overrideVersion).run("verifyR8Resolution")

    assertThat(result.didWorkTasks).contains(":app:verifyR8Resolution")
  }

  @Test
  fun testInvalidR8VersionFailsResolution() {
    val build = rule.build
    val nonExistentVersion = "999.999.0-nonexistent"

    val result = build.executor.expectFailure().with(StringOption.R8_VERSION_OVERRIDE, nonExistentVersion).run("verifyR8Resolution")

    result.assertErrorContains("Could not find com.android.tools:r8:999.999.0-nonexistent")
  }

  @Test
  fun testR8VersionOverrideOlderThanMinimumFails() {
    val build = rule.build

    // Fails at configuration time, before com.android.tools:r8:8.2.47 would be resolved.
    val result = build.executor.expectFailure().with(StringOption.R8_VERSION_OVERRIDE, "8.2.47").run("help")

    result.assertErrorContains(
      "android.r8.versionOverride=8.2.47 is older than R8 ${R8Version.VERSION_AGP_WAS_SHIPPED_WITH}, the minimum R8 version supported by " +
        "this version of the Android Gradle Plugin."
    )
  }

  @Test
  fun testR8OptimizationWithVersionOverride() {
    val build = rule.build
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    build.androidApplication().files.update("build.gradle.kts") {
      append(
        """

        android {
            buildTypes {
                release {
                    isMinifyEnabled = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
            }
        }
        """
          .trimIndent()
      )
    }

    val result = build.executor.with(StringOption.R8_VERSION_OVERRIDE, overrideVersion).run(":app:minifyReleaseWithR8")

    assertThat(result.didWorkTasks).contains(":app:minifyReleaseWithR8")
  }

  @Test
  fun testR8OptimizationWithoutOverride() {
    val build = rule.build

    build.androidApplication().files.update("build.gradle.kts") {
      append(
        """

        android {
            buildTypes {
                release {
                    isMinifyEnabled = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
            }
        }
        """
          .trimIndent()
      )
    }

    val result = build.executor.run(":app:minifyReleaseWithR8")

    assertThat(result.didWorkTasks).contains(":app:minifyReleaseWithR8")
    result.assertOutputContains("Running R8 ${R8Version.VERSION_AGP_WAS_SHIPPED_WITH}")
    val mappingFile = build.androidApplication().buildDir.resolve("outputs/mapping/release/mapping.txt")
    assertThat(mappingFile).exists()
    assertThat(mappingFile.toFile().readText()).contains("# compiler_version: ${R8Version.VERSION_AGP_WAS_SHIPPED_WITH}")
  }

  @Test
  fun testMissingDefaultR8InRepositoriesFailsResolution() {
    val build = rule.build
    TestFileUtils.appendToFile(
      build.directory.resolve("settings.gradle.kts").toFile(),
      """

      dependencyResolutionManagement {
          repositories.configureEach {
              content {
                  excludeModule("com.android.tools", "r8")
              }
          }
      }
      """
        .trimIndent(),
    )

    val result = build.executor.expectFailure().run(":app:dexBuilderDebug")

    result.assertErrorContains("Could not find com.android.tools:r8:${R8Version.VERSION_AGP_WAS_SHIPPED_WITH}")
  }

  @Test
  fun testTargetedConsumerRulesFilteredByVersionOverride() {
    val build = rule.build
    val targetedRulesJar =
      ZipContents(
          mapOf(
            "META-INF/com.android.tools/r8-upto-99.0.0/upto-99.pro" to "# rules for R8 < 99.0.0".toByteArray(),
            "META-INF/com.android.tools/r8-from-99.0.0/from-99.pro" to "# rules for R8 >= 99.0.0".toByteArray(),
          )
        )
        .toByteArray()
    build.androidApplication().files.add("libs/targeted-rules.jar", targetedRulesJar)
    build.androidApplication().files.update("build.gradle.kts") {
      append(
        """

        android {
            buildTypes {
                release {
                    isMinifyEnabled = true
                }
            }
        }
        dependencies {
            implementation(files("libs/targeted-rules.jar"))
        }
        tasks.register("printFilteredShrinkerRules") {
            val filteredRules =
                configurations
                    .getByName("releaseRuntimeClasspath")
                    .incoming
                    .artifactView {
                        attributes.attribute(
                            org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                            com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType.FILTERED_PROGUARD_RULES.type,
                        )
                    }
                    .files
            inputs.files(filteredRules)
            doLast {
                println("filtered shrinker rules: " + filteredRules.files.map { it.name }.sorted())
            }
        }
        """
          .trimIndent()
      )
    }

    val defaultResult = build.executor.run(":app:printFilteredShrinkerRules")
    defaultResult.assertOutputContains("filtered shrinker rules: [upto-99.pro]")

    // com.android.tools:r8:99.0.0 does not exist in the test repository and is never resolved here, because printFilteredShrinkerRules
    // only resolves the FILTERED_PROGUARD_RULES artifact view (which uses the version string) and never runs R8.
    val overrideResult = build.executor.with(StringOption.R8_VERSION_OVERRIDE, "99.0.0").run(":app:printFilteredShrinkerRules")
    overrideResult.assertOutputContains("filtered shrinker rules: [from-99.pro]")
  }
}
