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

package com.android.build.gradle.integration.lint

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox.privacySandboxSampleProject
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test

class PrivacySandboxSdkLintTest {
    @get:Rule
    val rule = privacySandboxSampleProject()

    private fun executor() = rule.build.executor
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
        .with(BooleanOption.PRIVACY_SANDBOX_SDK_ENABLE_LINT, true)
        .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
        .withPerTestPrefsRoot(true)
        .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679

    @Test
    fun testTargetSdkVersionLintReporting() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk") {
                android {
                    targetSdk = 32
                }
            }
        }
        val sdkProject = build.privacySandboxSdk(":privacy-sandbox-sdk")

        executor().expectFailure().run(":privacy-sandbox-sdk:lint").apply {
            assertTask(":privacy-sandbox-sdk:lintAnalyze").didWork()
        }
        val lintTextReport = sdkProject.buildDir.resolve("reports/lint-results-main.txt")
        assertThat(lintTextReport).exists()
        assertThat(lintTextReport).contains("""
privacy-sandbox-sdk/build.gradle:14: Error: Google Play requires that apps target API level 33 or higher. [ExpiredTargetSdkVersion]
  targetSdk = 32
  ~~~~~~~~~~~~~~""".trimIndent())
    }


    @Test
    fun testSdkLintReporting() {
        val build = rule.build
        executor().run(":privacy-sandbox-sdk:lint")
        val sdkProject = build.privacySandboxSdk(":privacy-sandbox-sdk")

        val lintTextReport = sdkProject.buildDir.resolve("reports/lint-results-main.txt")
        assertThat(lintTextReport).exists()
        assertThat(lintTextReport).contains("""
sdk-impl-a/src/main/res/values/strings.xml:2: Warning: The resource R.string.string_from_sdk_impl_a appears to be unused [UnusedResources]
    <string name="string_from_sdk_impl_a">fromSdkImplA</string>
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
""".trimIndent())
        val lintHtmlReport = sdkProject.buildDir.resolve("reports/lint-results-main.html")
        assertThat(lintHtmlReport).exists()
        assertThat(lintHtmlReport).contains("The resource <code>R.string.string_from_sdk_impl_a</code> appears to be unused")
        val lintXmlReport = sdkProject.buildDir.resolve("reports/lint-results-main.xml")
        assertThat(lintXmlReport).exists()
        assertThat(lintXmlReport).contains("message=\"The resource `R.string.string_from_sdk_impl_a` appears to be unused\"")
    }

    @Test
    fun checkUpdateLintBaseline() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk") {
                android {
                    lint.baseline = projectDotFile("lint-baseline.xml")
                }
            }
        }
        val sdkProject = build.privacySandboxSdk(":privacy-sandbox-sdk")


        // First test the case when there is no existing baseline.
        val baselineFile = sdkProject.resolve("lint-baseline.xml")
        assertThat(baselineFile).doesNotExist()
        executor().run(":privacy-sandbox-sdk:updateLintBaseline").apply {
            assertTask(":android-lib:lintAnalyzeDebug").didWork()
            assertTask(":sdk-impl-a:lintAnalyzeDebug").didWork()
            assertTask(":privacy-sandbox-sdk:updateLintBaseline").didWork()
        }
        assertThat(baselineFile).exists()
        // Check if the baseline contains an existing lint issue.
        assertThat(baselineFile).contains("""The resource `R.string.string_from_sdk_impl_a` appears to be unused""")

        // Run lint and ensure that this issue is not reported again because it is recorded in the baseline.
        executor().run(":privacy-sandbox-sdk:lint")
        val lintXmlReport = sdkProject.buildDir.resolve("reports/lint-results-main.xml")
        assertThat(lintXmlReport).exists()
        assertThat(lintXmlReport).doesNotContain("""The resource `R.string.string_from_sdk_impl_a` appears to be unused""")
        assertThat(lintXmlReport).contains("Baseline Applied")
    }
    @Test
    fun checkLintVital() {
        val build = rule.build

        executor().run(":privacy-sandbox-sdk:assemble").apply {
            assertTask(":android-lib:lintVitalAnalyzeDebug").didWork()
            assertTask(":sdk-impl-a:lintVitalAnalyzeDebug").didWork()
            assertTask(":privacy-sandbox-sdk:lintVital").didWork()
        }
        val lintVitalReport = build
            .privacySandboxSdk(":privacy-sandbox-sdk")
            .intermediatesDir
            .resolve("lint_vital_intermediate_text_report/single/lintVitalReport/lint-results-main.txt")
        assertThat(lintVitalReport).exists()
        assertThat(lintVitalReport).contains("No issues found.")
    }

    @Test
    fun checkLintFix() {
        val sourceTestPath = "src/main/java/com/example/androidlib/AccessTest.java"

        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk") {
                android {
                    lint.error += "SyntheticAccessor"
                }
            }
            androidLibrary(":android-lib") {
                android {
                    lint.error += "SyntheticAccessor"
                }
                files.add(
                    sourceTestPath,
                    //language=java
                    """
                        package com.example.androidlib;

                        class AccessTest {
                            private AccessTest() {}
                            class Inner {
                                private void innerMethod() {
                                    new AccessTest();
                                }
                            }
                            public void f2() {}
                        }
                    """.trimIndent()
                )
            }
        }

        val result = executor().expectFailure().run(":privacy-sandbox-sdk:lintFix")
        assertThat(result.stderr)
            .contains(
                "Aborting build since sources were modified to apply quickfixes after compilation"
            )
        // Make sure quickfixes worked too
        val sourceTestFile = build.androidLibrary(":android-lib").resolve(sourceTestPath)
        assertThat(sourceTestFile).doesNotContain("private AccessTest()")
        assertThat(sourceTestFile).contains("AccessTest()")
    }
}
