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

package com.android.build.gradle.integration.common.fixture.project.prebuilts

import com.android.build.gradle.integration.common.fixture.PLAY_SERVICES_VERSION
import com.android.build.gradle.integration.common.fixture.SUPPORT_LIB_VERSION
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.builder.LocalTestProjectSpec

class BasicSpec : LocalTestProjectSpec {

  override val projectName: String = "basic"

  override val configAction: GradleBuildDefinition.() -> Unit = {
    androidApplication(":app") {
      dependencies {
        api("com.android.support:support-v4:$SUPPORT_LIB_VERSION")
        api("com.google.android.gms:play-services-base:$PLAY_SERVICES_VERSION")

        add("debugApi", "com.android.support:support-v13:$SUPPORT_LIB_VERSION")
        add("releaseApi", "com.android.support:support-v13:$SUPPORT_LIB_VERSION")

        // hamcrest-library depends on hamcrest-core, both provide a /LICENSE.txt file
        // which used to cause packaging conflict. We added a special case for license files.
        androidTestImplementation("org.hamcrest:hamcrest-library:1.3")

        testImplementation("junit:junit:4.12")
        androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
        androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
      }

      android {
        namespace = "com.android.tests.basic"
        compileSdk { version = release(DEFAULT_COMPILE_SDK_VERSION) }
        enableKotlin = false

        testBuildType = "debug"

        defaultConfig {
          versionCode = 12
          versionName = "2.0"
          minSdk { version = release(16) }
          targetSdk { version = release(16) }

          testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          testInstrumentationRunnerArguments += "size" to "medium"

          testHandleProfiling = false

          buildConfigField("boolean", "DEFAULT", "true")
          buildConfigField("String", "FOO", "\"foo\"")
          buildConfigField("String", "FOO", "\"foo2\"")

          resValue("string", "foo", "foo")

          resConfig("en")
          resConfigs("hdpi")

          manifestPlaceholders += "someKey" to 12
        }

        buildTypes {
          named("debug") {
            it.applicationIdSuffix = ".debug"

            it.enableUnitTestCoverage = true
            it.enableAndroidTestCoverage = true

            it.buildConfigField("String", "FOO", "\"bar1\"")
            it.buildConfigField("String", "FOO", "\"bar\"")

            it.resValue("string", "foo", "foo2")

            it.matchingFallbacks += "release"
          }
        }

        androidResources {
          noCompress += "txt"
          ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~"
        }

        installation { installOptions += listOf("-d", "-t") }

        lint {
          // set to true to turn off analysis progress reporting by lint
          quiet = true
          // if true, stop the gradle build if errors are found
          abortOnError = false
          // if true, only report errors
          ignoreWarnings = true
          // if true, emit full/absolute paths to files with errors (true by default)
          // absolutePaths true
          // if true, check all issues, including those that are off by default
          checkAllWarnings = true
          // if true, treat all warnings as errors
          warningsAsErrors = true
          // turn off checking the given issue id's
          disable += listOf("TypographyFractions", "TypographyQuotes")
          // turn on the given issue id's
          enable += listOf("RtlHardcoded", "RtlCompat", "RtlEnabled")
          // check *only* the given issue id's
          checkOnly += listOf("NewApi", "InlinedApi")
          // if true, don't include source code lines in the error output
          noLines = true
          // if true, show all locations for an error, do not truncate lists, etc.
          showAll = true
          // Fallback lint configuration (default severities, etc.)
          lintConfig = projectDotFile("default-lint.xml")
          // if true, generate a text report of issues (false by default)
          textReport = true
          // if true, generate an XML report for use by for example Jenkins
          xmlReport = false
          // file to write report to (if not specified, defaults to lint-results.xml)
          xmlOutput = projectDotFile("lint-report.xml")
          // if true, generate an HTML report (with issue explanations, sourcecode, etc)
          htmlReport = true
          // optional path to report (default will be lint-results.html in the builddir)
          htmlOutput = projectDotFile("lint-report.html")
          // Reduce severity of this check to just informational
          informational += "LogConditional"
          // Run all lint checks on all test sources, not just production code
          checkTestSources = true
          // Run all lint checks on generated sources
          checkGeneratedSources = true
        }

        buildFeatures {
          buildConfig = true
          resValues = true
        }
      }
    }
  }
}
