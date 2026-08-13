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

import com.android.build.api.dsl.Lint
import com.android.build.gradle.internal.dsl.LintImpl
import com.android.build.gradle.internal.fixtures.FakeSyncIssueReporter
import com.android.build.gradle.internal.services.createDslServices
import com.android.build.gradle.options.ProjectOptions
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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
  fun `check java version initialization`() {
    val systemPropertyInputs = project.objects.newInstance(SystemPropertyInputs::class.java)
    systemPropertyInputs.initialize(project.providers, LintMode.ANALYSIS)

    // Verify that the lint input gets populated with the current JVM's major version
    assertThat(systemPropertyInputs.javaVersion.get()).isEqualTo(JavaVersion.current().majorVersion)
  }

  @Test
  fun `check java version normalization expectations`() {
    // Since we now rely on Gradle's JavaVersion to parse the system property,
    // we verify that Gradle correctly normalizes these expected version strings.
    fun check(javaVersion: String, expectedMajorVersion: String) {
      assertThat(JavaVersion.toVersion(javaVersion).majorVersion).isEqualTo(expectedMajorVersion)
    }

    check("1.8.0_292", "8")
    check("11.0.1", "11")
    check("17", "17")
    check("17.0.17+10-LTS", "17")
    check("17+35-LTS-2724", "17")
  }

  @Test
  fun `check isLintRunInProcess logic`() {
    // When no toolchain spec is provided, runInProcess respects its parameter
    assertThat(isLintRunInProcess(runInProcess = true)).isTrue()
    assertThat(isLintRunInProcess(runInProcess = false)).isFalse()

    // When toolchain spec is present, runInProcess is always forced to false
    assertThat(isLintRunInProcess(runInProcess = true, hasToolchainSpec = true)).isFalse()
    assertThat(isLintRunInProcess(runInProcess = false, hasToolchainSpec = true)).isFalse()
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

  @Test
  fun `test baseline convention with DSL baseline set`() {
    checkBaselineConvention(
      setup = { projectDir, lintOptions ->
        val dslBaseline = File(projectDir, "dsl-baseline.xml")
        dslBaseline.createNewFile()
        lintOptions.baseline = dslBaseline
        dslBaseline
      },
      useBaselineConvention = true,
      expectedDefaultBaseline = false,
    )
  }

  @Test
  fun `test baseline convention with default file exists`() {
    checkBaselineConvention(
      setup = { projectDir, _ ->
        val defaultBaseline = File(projectDir, "lint-baseline.xml")
        defaultBaseline.createNewFile()
        defaultBaseline
      },
      useBaselineConvention = true,
      expectedDefaultBaseline = true,
    )
  }

  @Test
  fun `test baseline convention with default file does not exist, not updating`() {
    checkBaselineConvention(
      setup = { projectDir, _ -> File(projectDir, "lint-baseline.xml") },
      useBaselineConvention = true,
      expectedDefaultBaseline = true,
    )
  }

  @Test
  fun `test baseline convention with default file does not exist, updating`() {
    checkBaselineConvention(
      setup = { projectDir, _ -> File(projectDir, "lint-baseline.xml") },
      mode = LintMode.UPDATE_BASELINE,
      useBaselineConvention = true,
      expectedDefaultBaseline = true,
    )
  }

  @Test
  fun `test baseline convention disabled by property`() {
    checkBaselineConvention(
      setup = { projectDir, _ ->
        val defaultBaseline = File(projectDir, "lint-baseline.xml")
        defaultBaseline.createNewFile()
        null
      },
      useBaselineConvention = false,
      expectedDefaultBaseline = false,
    )
  }

  private fun checkBaselineConvention(
    setup: (File, LintImpl) -> File?,
    mode: LintMode = LintMode.REPORTING,
    useBaselineConvention: Boolean,
    expectedDefaultBaseline: Boolean,
  ) {
    val projectDir = temporaryFolder.newFolder()
    val projectDirectory = project.layout.projectDirectory.dir(projectDir.absolutePath)
    val lintOptionsInput = project.objects.newInstance(LintOptionsInput::class.java)
    val dslServices = createDslServices()
    val lintOptions = dslServices.newDecoratedInstance(LintImpl::class.java, dslServices)

    val expectedBaselineFile = setup(projectDir, lintOptions)

    lintOptionsInput.initialize(lintOptions, mode, projectDirectory, useBaselineConvention)

    assertThat(lintOptionsInput.toLintModel().baselineFile?.absolutePath).isEqualTo(expectedBaselineFile?.absolutePath)
    assertThat(lintOptionsInput.defaultBaseline.get()).isEqualTo(expectedDefaultBaseline)
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

  @Test
  fun `check getLintJavaLauncherProvider with valid toolchain spec`() {
    project.pluginManager.apply("jvm-toolchains")
    val dslServices = createDslServices()
    val lintOptions = dslServices.newDecoratedInstance(LintImpl::class.java, dslServices)
    val currentJavaVersion = JavaVersion.current().majorVersion.toInt()
    lintOptions.toolchain { languageVersion.set(JavaLanguageVersion.of(currentJavaVersion)) }

    val launcherProvider = getLintJavaLauncherProvider(project, lintOptions, JavaVersion.VERSION_17)
    assertThat(launcherProvider.isPresent).isTrue()
  }

  @Test
  fun `check getLintJavaLauncherProvider below baseline throws GradleException`() {
    project.pluginManager.apply("jvm-toolchains")
    val dslServices = createDslServices()
    val lintOptions = dslServices.newDecoratedInstance(LintImpl::class.java, dslServices)
    lintOptions.toolchain { languageVersion.set(JavaLanguageVersion.of(11)) }

    val exception =
      assertThrows(GradleException::class.java) { getLintJavaLauncherProvider(project, lintOptions, JavaVersion.VERSION_11).get() }
    assertThat(exception.message).contains("below AGP's minimum required JDK (17)")
  }

  @Test
  fun `check getLintJavaLauncherProvider below bytecode compatibility throws GradleException`() {
    project.pluginManager.apply("jvm-toolchains")
    val dslServices = createDslServices()
    val lintOptions = dslServices.newDecoratedInstance(LintImpl::class.java, dslServices)
    lintOptions.toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }

    val exception =
      assertThrows(GradleException::class.java) { getLintJavaLauncherProvider(project, lintOptions, JavaVersion.VERSION_21).get() }
    assertThat(exception.message).contains("cannot analyze project code compiled for Java 21")
  }

  @Test
  fun `check getLintRunInProcessProvider with toolchain spec forces out of process`() {
    val dslServices = createDslServices()
    val lintOptions = dslServices.newDecoratedInstance(LintImpl::class.java, dslServices)
    lintOptions.toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }

    val runInProcess = getLintRunInProcessProvider(ProjectOptions(project.providers), lintOptions).get()
    assertThat(runInProcess).isFalse()
  }

  @Test
  fun `check getLintJavaLauncherProvider without toolchain below bytecode compatibility throws GradleException`() {
    val dslServices = createDslServices()
    val lintOptions = dslServices.newDecoratedInstance(LintImpl::class.java, dslServices)
    // No toolchain specified

    val currentJava = JavaVersion.current()
    val availableVersions = JavaVersion.values()

    // Skip the test if we are already on the highest known Java version
    Assume.assumeTrue("Test requires a higher known Java version to be available", currentJava < availableVersions.last())

    val higherJavaVersion = availableVersions.first { it > currentJava }

    val exception = assertThrows(GradleException::class.java) { getLintJavaLauncherProvider(project, lintOptions, higherJavaVersion).get() }
    assertThat(exception.message).contains("The Gradle daemon is running on Java")
    assertThat(exception.message).contains("but the project is compiled for Java")
    assertThat(exception.message).contains("Please run Gradle on a newer JVM or configure a toolchain for Lint")
  }

  @Test
  fun `check getLintJavaLauncherProvider and getLintRunInProcessProvider evaluate toolchain spec lazily`() {
    project.pluginManager.apply("jvm-toolchains")
    val dslServices = createDslServices()
    val lintOptions = dslServices.newDecoratedInstance(LintImpl::class.java, dslServices)

    // Obtain providers BEFORE configuring toolchain
    val runInProcessProvider = getLintRunInProcessProvider(ProjectOptions(project.providers), lintOptions)
    val launcherProvider = getLintJavaLauncherProvider(project, lintOptions, JavaVersion.VERSION_17)

    // Initially toolchain is empty, so runInProcess is true and launcher is null
    assertThat(runInProcessProvider.get()).isTrue()
    assertThat(launcherProvider.orNull).isNull()

    // Configure toolchain AFTER provider creation
    val currentJavaVersion = JavaVersion.current().majorVersion.toInt()
    lintOptions.toolchain { languageVersion.set(JavaLanguageVersion.of(currentJavaVersion)) }

    // After configuring, providers lazily reflect the new toolchain configuration
    assertThat(runInProcessProvider.get()).isFalse()
    assertThat(launcherProvider.orNull).isNotNull()
  }

  @Test
  fun `check custom Lint implementation toolchainSpec support`() {
    project.pluginManager.apply("jvm-toolchains")
    val dslServices = createDslServices()
    val currentJavaVersion = JavaVersion.current().majorVersion.toInt()
    val toolchainSpec = dslServices.newInstance(JavaToolchainSpec::class.java)
    toolchainSpec.languageVersion.set(JavaLanguageVersion.of(currentJavaVersion))

    val customLint = mock<Lint>()
    whenever(customLint.toolchain).thenReturn(toolchainSpec)

    val runInProcess = getLintRunInProcessProvider(ProjectOptions(project.providers), customLint).get()
    assertThat(runInProcess).isFalse()

    val launcherProvider = getLintJavaLauncherProvider(project, customLint, JavaVersion.VERSION_17)
    assertThat(launcherProvider.isPresent).isTrue()
  }

  @Test
  fun `check getLintJavaLauncherProvider with vendor specified without languageVersion`() {
    project.pluginManager.apply("jvm-toolchains")
    val dslServices = createDslServices()
    val lintOptions = dslServices.newDecoratedInstance(LintImpl::class.java, dslServices)
    lintOptions.toolchain { vendor.set(JvmVendorSpec.ADOPTIUM) }

    val runInProcess = getLintRunInProcessProvider(ProjectOptions(project.providers), lintOptions).get()
    assertThat(runInProcess).isFalse()

    val launcherProvider = getLintJavaLauncherProvider(project, lintOptions, JavaVersion.VERSION_17)
    val exception = assertThrows(GradleException::class.java) { launcherProvider.get() }
    assertThat(exception.message).contains("must specify a 'languageVersion'")
  }
}
