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

package com.android.build.gradle.internal.plugins

import com.android.build.api.dsl.Lint
import com.android.build.gradle.internal.dsl.LintImpl
import com.android.build.gradle.internal.services.createDslServices
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test

class LintSettingsUtilTest {

  private val dslServices = createDslServices()
  private lateinit var lint: Lint
  private lateinit var settingsLint: Lint

  @Before
  fun setup() {
    lint = dslServices.newDecoratedInstance(LintImpl::class.java, dslServices)
    settingsLint = dslServices.newDecoratedInstance(LintImpl::class.java, dslServices)
  }

  @Test
  fun testApplySettings() {
    // Set values on settingsLint different from default
    settingsLint.abortOnError = false
    settingsLint.absolutePaths = false
    settingsLint.checkAllWarnings = true
    settingsLint.checkDependencies = true
    settingsLint.checkGeneratedSources = true
    settingsLint.checkReleaseBuilds = false
    // Setting checkTestSources = true sets ignoreTestSources = false.
    settingsLint.checkTestSources = true
    settingsLint.explainIssues = false
    settingsLint.htmlReport = false
    settingsLint.ignoreTestFixturesSources = true
    settingsLint.ignoreWarnings = true
    settingsLint.noLines = true
    settingsLint.quiet = true
    settingsLint.sarifReport = true
    settingsLint.showAll = true
    settingsLint.textReport = true
    settingsLint.warningsAsErrors = true
    settingsLint.xmlReport = false

    settingsLint.checkOnly += "NewApi"
    settingsLint.disable += "HardcodedText"
    settingsLint.enable += "StopShip"
    settingsLint.error += "MissingTranslation"
    settingsLint.fatal += "UnusedResources"
    settingsLint.informational += "TypographyQuotes"
    settingsLint.warning += "IconMissingDensityFolder"

    lint.checkOnly += "FieldGetter"
    lint.disable += "UsesMinSdkAttributes"
    lint.enable += "ObsoleteLayoutParam"
    lint.error += "MultipleUsesSdk"
    lint.fatal += "WrongThread"
    lint.informational += "ContentDescription"
    lint.warning += "DrawAllocation"

    val baselineFile = File("baseline.xml")
    settingsLint.baseline = baselineFile
    val configFile = File("lint.xml")
    settingsLint.lintConfig = configFile

    settingsLint.targetSdk = 33

    // Apply settings
    lint.applySettings(settingsLint)

    // Verify values are copied
    assertThat(lint.abortOnError).named("abortOnError").isFalse()
    assertThat(lint.absolutePaths).named("absolutePaths").isFalse()
    assertThat(lint.checkAllWarnings).named("checkAllWarnings").isTrue()
    assertThat(lint.checkDependencies).named("checkDependencies").isTrue()
    assertThat(lint.checkGeneratedSources).named("checkGeneratedSources").isTrue()
    assertThat(lint.checkReleaseBuilds).named("checkReleaseBuilds").isFalse()
    assertThat(lint.checkTestSources).named("checkTestSources").isTrue()
    // ignoreTestSources should be false because checkTestSources is true
    assertThat(lint.ignoreTestSources).named("ignoreTestSources").isFalse()

    assertThat(lint.explainIssues).named("explainIssues").isFalse()
    assertThat(lint.htmlReport).named("htmlReport").isFalse()
    assertThat(lint.ignoreTestFixturesSources).named("ignoreTestFixturesSources").isTrue()
    assertThat(lint.ignoreWarnings).named("ignoreWarnings").isTrue()
    assertThat(lint.noLines).named("noLines").isTrue()
    assertThat(lint.quiet).named("quiet").isTrue()
    assertThat(lint.sarifReport).named("sarifReport").isTrue()
    assertThat(lint.showAll).named("showAll").isTrue()
    assertThat(lint.textReport).named("textReport").isTrue()
    assertThat(lint.warningsAsErrors).named("warningsAsErrors").isTrue()
    assertThat(lint.xmlReport).named("xmlReport").isFalse()

    assertThat(lint.checkOnly).named("checkOnly").containsExactly("NewApi", "FieldGetter")
    assertThat(lint.disable).named("disable").containsExactly("HardcodedText", "UsesMinSdkAttributes")
    assertThat(lint.enable).named("enable").containsExactly("StopShip", "ObsoleteLayoutParam")
    assertThat(lint.error).named("error").containsExactly("MissingTranslation", "MultipleUsesSdk")
    assertThat(lint.fatal).named("fatal").containsExactly("UnusedResources", "WrongThread")
    assertThat(lint.informational).named("informational").containsExactly("TypographyQuotes", "ContentDescription")
    assertThat(lint.warning).named("warning").containsExactly("IconMissingDensityFolder", "DrawAllocation")

    assertThat(lint.baseline).named("baseline").isEqualTo(baselineFile)
    assertThat(lint.lintConfig).named("lintConfig").isEqualTo(configFile)

    assertThat(lint.targetSdk).named("targetSdk").isEqualTo(33)
  }

  @Test
  fun testApplySettings_targetSdkPreview() {
    settingsLint.targetSdkPreview = "UpsideDownCake"

    lint.applySettings(settingsLint)

    assertThat(lint.targetSdkPreview).named("targetSdkPreview").isEqualTo("UpsideDownCake")
  }

  @Test
  fun testApplySettings_targetSdkBlock() {
    settingsLint.targetSdk { version = release(33) }

    lint.applySettings(settingsLint)

    assertThat(lint.targetSdk).named("targetSdk").isEqualTo(33)

    settingsLint.targetSdk { version = preview("UpsideDownCake") }

    lint.applySettings(settingsLint)

    assertThat(lint.targetSdkPreview).named("targetSdkPreview").isEqualTo("UpsideDownCake")
  }

  @Test
  fun testApplySettings_nulls() {
    // Test that nulls in settings (where allowed) do not overwrite existing values or throw errors

    val baselineFile = File("original_baseline.xml")
    lint.baseline = baselineFile
    val configFile = File("original_lint.xml")
    lint.lintConfig = configFile
    lint.targetSdk = 30
    lint.targetSdkPreview = "S"

    // settingsLint has nulls by default for these
    assertThat(settingsLint.baseline).isNull()
    assertThat(settingsLint.lintConfig).isNull()
    assertThat(settingsLint.targetSdk).isNull()
    assertThat(settingsLint.targetSdkPreview).isNull()

    lint.applySettings(settingsLint)

    // Verify values are NOT overwritten with null
    assertThat(lint.baseline).named("baseline").isEqualTo(baselineFile)
    assertThat(lint.lintConfig).named("lintConfig").isEqualTo(configFile)
    assertThat(lint.targetSdk).named("targetSdk").isEqualTo(30)
    assertThat(lint.targetSdkPreview).named("targetSdkPreview").isEqualTo("S")
  }
}
