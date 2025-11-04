/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.internal.dsl

import com.google.common.truth.ComparableSubject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

internal class SettingsExtensionImplTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @get:Rule val mockitoJUnitRule: MockitoRule = MockitoJUnit.rule()

  private val project: Project by lazy { ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build() }

  private lateinit var settings: SettingsExtensionImpl

  @Before
  fun setup() {
    settings = SettingsExtensionImpl(project.objects)
  }

  @Test
  fun testDefaults() {
    testCompileValues()
    testMinSdkValues()
  }

  @Test
  fun compileSdk() {
    settings.compileSdk = 12
    testCompileValues(compileSdk = 12)

    settings.compileSdkExtension = 2
    testCompileValues(compileSdk = 12, compileSdkExtension = 2)

    // test reset to null from other values
    settings.compileSdkPreview = "Foo"
    testCompileValues(compileSdkPreview = "Foo")

    settings.compileSdk = 12
    settings.compileSdkAddon("foo", "bar", 42)
    testCompileValues(addOnVendor = "foo", addOnName = "bar", addOnVersion = 42)

    settings.compileSdk { version = release(33) }
    testCompileValues(compileSdk = 33)

    settings.compileSdk {
      version =
        release(33) {
          sdkExtension = 18
          minorApiLevel = 0
        }
      assertThat(version?.minorApiLevel).isEqualTo(0)
    }
    testCompileValues(compileSdk = 33, compileSdkExtension = 18)
  }

  @Test
  fun compileSdkPreview() {
    settings.compileSdkPreview = "S"
    testCompileValues(compileSdkPreview = "S")

    // test reset to null from other values
    settings.compileSdk = 12
    testCompileValues(compileSdk = 12)

    settings.compileSdkPreview = "S"
    settings.compileSdkAddon("foo", "bar", 42)
    testCompileValues(addOnVendor = "foo", addOnName = "bar", addOnVersion = 42)

    settings.compileSdk { version = preview("S") }
    settings.compileSdk {}

    testCompileValues(compileSdkPreview = "S")
  }

  @Test
  fun compileSdkAddon() {
    settings.compileSdkAddon("foo", "bar", 42)
    testCompileValues(addOnVendor = "foo", addOnName = "bar", addOnVersion = 42)

    // test reset to null from other values
    settings.compileSdk = 12
    testCompileValues(compileSdk = 12)

    settings.compileSdkAddon("foo", "bar", 42)
    settings.compileSdkPreview = "S"
    testCompileValues(compileSdkPreview = "S")

    settings.compileSdk { version = addon("foo", "bar", 41) }
    testCompileValues(addOnVendor = "foo", addOnName = "bar", addOnVersion = 41)
  }

  @Test
  fun minSdk() {
    settings.minSdk = 12
    testMinSdkValues(minSdk = 12)

    // test reset to null from other values
    settings.minSdkPreview = "S"
    testMinSdkValues(minSdkPreview = "S")

    settings.minSdk { version = release(33) }
    testMinSdkValues(minSdk = 33)
  }

  @Test
  fun minSdkPreview() {
    settings.minSdkPreview = "S"
    testMinSdkValues(minSdkPreview = "S")

    // test reset to null from other values
    settings.minSdk = 12
    testMinSdkValues(minSdk = 12)

    settings.minSdk { version = preview("S") }
    testMinSdkValues(minSdkPreview = "S")
  }

  @Test
  fun targetSdk() {
    settings.targetSdk { version = release(33) }
    testTargetSdkValues(targetSdk = 33)

    settings.targetSdk { version = preview("S") }
    testTargetSdkValues(targetSdkPreview = "S")
  }

  @Test
  fun lint() {
    // Boolean options
    settings.lint {
      abortOnError = false
      absolutePaths = false
      explainIssues = false
      checkReleaseBuilds = false
      htmlReport = false
      xmlReport = false
      checkDependencies = true
      noLines = true
      quiet = true
      checkAllWarnings = true
      ignoreWarnings = true
      warningsAsErrors = true
      checkTestSources = true
      ignoreTestSources = true
      ignoreTestFixturesSources = true
      checkGeneratedSources = true
      showAll = true
      textReport = true
      sarifReport = true
    }
    assertThat(settings.lint.abortOnError).isFalse()
    assertThat(settings.lint.absolutePaths).isFalse()
    assertThat(settings.lint.explainIssues).isFalse()
    assertThat(settings.lint.checkReleaseBuilds).isFalse()
    assertThat(settings.lint.htmlReport).isFalse()
    assertThat(settings.lint.xmlReport).isFalse()
    assertThat(settings.lint.checkDependencies).isTrue()
    assertThat(settings.lint.noLines).isTrue()
    assertThat(settings.lint.quiet).isTrue()
    assertThat(settings.lint.checkAllWarnings).isTrue()
    assertThat(settings.lint.ignoreWarnings).isTrue()
    assertThat(settings.lint.warningsAsErrors).isTrue()
    assertThat(settings.lint.checkTestSources).isFalse()
    assertThat(settings.lint.ignoreTestSources).isTrue()
    assertThat(settings.lint.ignoreTestFixturesSources).isTrue()
    assertThat(settings.lint.checkGeneratedSources).isTrue()
    assertThat(settings.lint.showAll).isTrue()
    assertThat(settings.lint.textReport).isTrue()
    assertThat(settings.lint.sarifReport).isTrue()

    settings.lint {
      abortOnError = true
      absolutePaths = true
      explainIssues = true
      checkReleaseBuilds = true
      htmlReport = true
      xmlReport = true
      checkDependencies = false
      noLines = false
      quiet = false
      checkAllWarnings = false
      ignoreWarnings = false
      warningsAsErrors = false
      checkTestSources = false
      ignoreTestSources = false
      ignoreTestFixturesSources = false
      checkGeneratedSources = false
      showAll = false
      textReport = false
      sarifReport = false
    }
    assertThat(settings.lint.abortOnError).isTrue()
    assertThat(settings.lint.absolutePaths).isTrue()
    assertThat(settings.lint.explainIssues).isTrue()
    assertThat(settings.lint.checkReleaseBuilds).isTrue()
    assertThat(settings.lint.htmlReport).isTrue()
    assertThat(settings.lint.xmlReport).isTrue()
    assertThat(settings.lint.checkDependencies).isFalse()
    assertThat(settings.lint.noLines).isFalse()
    assertThat(settings.lint.quiet).isFalse()
    assertThat(settings.lint.checkAllWarnings).isFalse()
    assertThat(settings.lint.ignoreWarnings).isFalse()
    assertThat(settings.lint.warningsAsErrors).isFalse()
    assertThat(settings.lint.checkTestSources).isFalse()
    assertThat(settings.lint.ignoreTestSources).isFalse()
    assertThat(settings.lint.ignoreTestFixturesSources).isFalse()
    assertThat(settings.lint.checkGeneratedSources).isFalse()
    assertThat(settings.lint.showAll).isFalse()
    assertThat(settings.lint.textReport).isFalse()
    assertThat(settings.lint.sarifReport).isFalse()

    // Sets
    settings.lint {
      disable.add("DisableIssue")
      enable.add("EnableIssue")
      checkOnly.add("CheckOnlyIssue")
      informational.add("InformationalIssue")
      warning.add("WarningIssue")
      error.add("ErrorIssue")
      fatal.add("FatalIssue")
      ignore.add("IgnoreIssue")
    }
    assertThat(settings.lint.disable).contains("DisableIssue")
    assertThat(settings.lint.enable).contains("EnableIssue")
    assertThat(settings.lint.checkOnly).contains("CheckOnlyIssue")
    assertThat(settings.lint.informational).contains("InformationalIssue")
    assertThat(settings.lint.warning).contains("WarningIssue")
    assertThat(settings.lint.error).contains("ErrorIssue")
    assertThat(settings.lint.fatal).contains("FatalIssue")
    assertThat(settings.lint.ignore).contains("IgnoreIssue")
    assertThat(settings.lint.disable).contains("IgnoreIssue") // ignore is alias for disable

    // Files
    val baselineFile = temporaryFolder.newFile("baseline.xml")
    val configFile = temporaryFolder.newFile("lint.xml")
    settings.lint {
      baseline = baselineFile
      lintConfig = configFile
    }
    assertThat(settings.lint.baseline).isEqualTo(baselineFile)
    assertThat(settings.lint.lintConfig).isEqualTo(configFile)

    // Unsupported files
    val outputFile = temporaryFolder.newFile("output.xml")
    assertThrows(UnsupportedOperationException::class.java) { settings.lint.textOutput = outputFile }
    assertThrows(UnsupportedOperationException::class.java) { settings.lint.htmlOutput = outputFile }
    assertThrows(UnsupportedOperationException::class.java) { settings.lint.xmlOutput = outputFile }
    assertThrows(UnsupportedOperationException::class.java) { settings.lint.sarifOutput = outputFile }

    // TargetSdk
    settings.lint { targetSdk = 33 }
    assertThat(settings.lint.targetSdk).isEqualTo(33)

    settings.lint { targetSdkPreview = "Tiramisu" }
    assertThat(settings.lint.targetSdkPreview).isEqualTo("Tiramisu")

    settings.lint { targetSdk { version = release(35) } }
    assertThat(settings.lint.targetSdk).isEqualTo(35)
  }

  @Test
  fun testCheckTestSourcesAndIgnoreTestSourcesInteraction() {
    settings.lint.checkTestSources = true
    assertThat(settings.lint.checkTestSources).isTrue()
    assertThat(settings.lint.ignoreTestSources).isFalse()

    settings.lint.ignoreTestSources = true
    assertThat(settings.lint.checkTestSources).isFalse()
    assertThat(settings.lint.ignoreTestSources).isTrue()

    settings.lint.checkTestSources = true
    assertThat(settings.lint.checkTestSources).isTrue()
    assertThat(settings.lint.ignoreTestSources).isFalse()
  }

  private fun testCompileValues(
    compileSdk: Int? = null,
    compileSdkExtension: Int? = null,
    compileSdkPreview: String? = null,
    addOnVendor: String? = null,
    addOnName: String? = null,
    addOnVersion: Int? = null,
  ) {
    assertWithMessage("compileSdk").that(settings.compileSdk).compareTo(compileSdk)

    assertWithMessage("compileSdkExtension").that(settings.compileSdkExtension).compareTo(compileSdkExtension)

    assertWithMessage("compileSdkPreview").that(settings.compileSdkPreview).compareTo(compileSdkPreview)

    assertWithMessage("addOnVendor").that(settings.addOnVendor).compareTo(addOnVendor)
    assertWithMessage("addOnName").that(settings.addOnName).compareTo(addOnName)
    assertWithMessage("addOnVersion").that(settings.addOnVersion).compareTo(addOnVersion)
  }

  private fun testMinSdkValues(minSdk: Int? = null, minSdkPreview: String? = null) {
    assertWithMessage("minSdk").that(settings.minSdk).compareTo(minSdk)

    assertWithMessage("minSdkPreview").that(settings.minSdkPreview).compareTo(minSdkPreview)
  }

  private fun testTargetSdkValues(targetSdk: Int? = null, targetSdkPreview: String? = null) {
    assertWithMessage("targetSdk").that(settings.targetSdk).compareTo(targetSdk)

    assertWithMessage("targetSdkPreview").that(settings.targetSdkPreview).compareTo(targetSdkPreview)
  }

  private fun <TypeT, SubjectT : ComparableSubject<SubjectT, TypeT>> SubjectT.compareTo(value: TypeT?) =
    if (value == null) {
      this.isNull()
    } else {
      this.isEqualTo(value)
    }
}
