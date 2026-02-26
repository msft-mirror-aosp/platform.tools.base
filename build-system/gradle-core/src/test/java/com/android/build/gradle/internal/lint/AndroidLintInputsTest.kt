/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.internal.lint

import com.android.build.gradle.internal.fixtures.FakeSyncIssueReporter
import com.android.build.gradle.options.ProjectOptions
import com.android.testutils.SystemPropertyOverrides
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidLintInputsTest {

  @get:Rule val temporaryFolder = TemporaryFolder()
  private val project: Project by lazy { ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build() }

  @Test
  fun `check default when override is not set`() {
    val issueReporter = FakeSyncIssueReporter(throwOnError = false)
    val lintVersion =
      getLintMavenArtifactVersion(
        versionOverride = null,
        reporter = issueReporter,
        defaultVersion = "30.0.0-rc01",
        agpVersion = "7.0.0-rc01",
      )
    assertThat(lintVersion).isEqualTo("30.0.0-rc01")
    assertThat(issueReporter.errors).hasSize(0)
    assertThat(issueReporter.warnings).hasSize(0)
  }

  @Test
  fun `check lint version can be overridden with a valid newer version`() {
    val issueReporter = FakeSyncIssueReporter(throwOnError = false)
    val lintVersion =
      getLintMavenArtifactVersion(
        versionOverride = "7.1.0-alpha04",
        reporter = issueReporter,
        defaultVersion = "30.0.0-rc01",
        agpVersion = "7.0.0-rc01",
      )
    assertThat(lintVersion).isEqualTo("30.1.0-alpha04")
    assertThat(issueReporter.errors).hasSize(0)
    assertThat(issueReporter.warnings).hasSize(0)
  }

  @Test
  fun `check lint version override with invalid version`() {
    val issueReporter = FakeSyncIssueReporter(throwOnError = false)
    val lintVersion =
      getLintMavenArtifactVersion(
        versionOverride = "+",
        reporter = issueReporter,
        defaultVersion = "30.0.0-rc01",
        agpVersion = "7.0.0-rc01",
      )
    assertThat(lintVersion).isEqualTo("30.0.0-rc01")
    assertThat(issueReporter.errors).hasSize(1)
    assertThat(issueReporter.warnings).hasSize(0)
    assertThat(issueReporter.errors.single())
      .isEqualTo(
        """
        Could not parse lint version override '+'
        Recommendation: Remove or update the gradle property android.experimental.lint.version to be at least 7.0.0-rc01
        """
          .trimIndent()
      )
  }

  @Test
  fun `check lint version override with outdated version`() {
    val issueReporter = FakeSyncIssueReporter(throwOnError = false)

    val lintVersion =
      getLintMavenArtifactVersion(
        versionOverride = "7.0.0-alpha05",
        reporter = issueReporter,
        defaultVersion = "30.0.0-alpha06",
        agpVersion = "7.0.0-alpha06",
      )
    assertThat(lintVersion).isEqualTo("30.0.0-alpha06")
    assertThat(issueReporter.errors).hasSize(1)
    assertThat(issueReporter.warnings).hasSize(0)
    assertThat(issueReporter.errors.single())
      .isEqualTo(
        """
        Lint must be at least version 7.0.0-alpha06
        Recommendation: Remove or update the gradle property android.experimental.lint.version to be at least 7.0.0-alpha06
        """
          .trimIndent()
      )
  }

  @Test
  fun `check java version normalization`() {
    SystemPropertyOverrides().use { systemPropertyOverrides ->
      fun check(javaVersion: String, expectedMajorVersion: String) {
        val systemPropertyInputs = project.objects.newInstance(SystemPropertyInputs::class.java)
        systemPropertyOverrides.setProperty("java.version", javaVersion)
        systemPropertyInputs.initialize(project.providers, LintMode.ANALYSIS)
        assertThat(systemPropertyInputs.javaVersion.get()).isEqualTo(expectedMajorVersion)
      }

      check("1.8.0_292", "8")
      check("11.0.1", "11")
      check("17", "17")
      check("17.0.17+10-LTS", "17")
      check("17+35-LTS-2724", "17")
    }
  }

  @Test
  fun `check androidLint configuration is accessible and configurable`() {
    val repoDir = temporaryFolder.newFolder("repo")
    project.repositories.maven { it.url = repoDir.toURI() }

    createMavenArtifact(repoDir, "org.ow2.asm", "asm", "9.0")
    createMavenArtifact(repoDir, "org.ow2.asm", "asm", "9.1")

    project.configurations.create("androidLintTool") { config ->
      config.resolutionStrategy.eachDependency {
        if (it.requested.group == "org.ow2.asm") {
          it.useVersion("9.1")
        }
      }
    }

    val issueReporter = FakeSyncIssueReporter(throwOnError = false)
    val projectOptions = ProjectOptions(project.providers)

    val lintFromMaven = LintFromMaven.from(project, projectOptions, issueReporter)
    val config = lintFromMaven.files as Configuration

    assertThat(config.name).isEqualTo("androidLintTool")
    assertThat(project.configurations.findByName("androidLintTool")).isNotNull()

    config.dependencies.clear()
    config.dependencies.add(project.dependencies.create("org.ow2.asm:asm:9.0"))

    val resolved = config.resolvedConfiguration.firstLevelModuleDependencies
    assertThat(resolved).hasSize(1)
    assertThat(resolved.first().moduleGroup).isEqualTo("org.ow2.asm")
    assertThat(resolved.first().moduleName).isEqualTo("asm")
    assertThat(resolved.first().moduleVersion).isEqualTo("9.1")
  }

  private fun createMavenArtifact(repoDir: File, group: String, artifact: String, version: String) {
    val dir = File(repoDir, "${group.replace('.', '/')}/$artifact/$version")
    dir.mkdirs()
    File(dir, "$artifact-$version.pom")
      .writeText(
        "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version></project>"
      )
    File(dir, "$artifact-$version.jar").writeText("foo-bar")
  }
}
