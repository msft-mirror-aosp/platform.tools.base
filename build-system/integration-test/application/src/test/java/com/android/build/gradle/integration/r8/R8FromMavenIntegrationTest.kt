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
import com.android.build.gradle.options.StringOption
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
    val overrideVersion = "8.2.47"

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
  fun testR8OptimizationWithVersionOverride() {
    val build = rule.build
    val overrideVersion = "8.2.47"

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
}
