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

package com.android.build.gradle.integration.testing.suites

import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.api.dsl.BackupTestSuite
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HasTestSuites
import com.android.build.api.variant.TestSuite
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/**
 * Integration test verifying the registration, DSL configuration, and input parameter wiring of the specialized [BackupTestSuite] under an
 * application module.
 */
class BackupTestSuiteDeclarationTest {
  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("androidx.test.backup:backup-host:1.0.0-alpha01")
        jar("androidx.test.backup:backup:1.0.0-alpha01")
        jar("org.junit.jupiter:junit-jupiter-engine:5.10.0")
        jar("org.junit.platform:junit-platform-launcher:1.10.0")
      }
      .from {
        rootProject { buildscript { classpath("com.google.truth:truth:0.44") } }
        gradleProperties {
          add(BooleanOption.TEST_SUITE_SUPPORT, true)
          add(BooleanOption.ENABLE_BACKUP_TEST, true)
        }
        androidApplication {
          pluginCallbacks += BackupSuiteCallback::class.java
          android {
            testOptions.backupTests.create("myBackup") {
              it.backupTestLibraryVersion = "1.0.0-alpha01"
              it.targetVariants.add("debug")
            }
          }
          dependencies {
            implementation("com.google.truth:truth:0.44")
          }
        }
      }

  @Test
  fun testBackupTestSuiteConfiguration() {
    rule.build.executor.run(":app:tasks")
  }
}

class BackupSuiteCallback : ApplicationComponentCallback {
  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.finalizeDsl { applicationExtension ->
      val backupSuite = applicationExtension.testOptions.backupTests.getByName("myBackup")
      Truth.assertThat(backupSuite.backupTestLibraryVersion).isEqualTo("1.0.0-alpha01")
      Truth.assertThat(backupSuite.targetVariants).containsExactly("debug")
    }

    androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) { variantBuilder ->
      val listOfTestSuites = variantBuilder.suites.values.joinToString { it.name }
      if (variantBuilder.suites.size != 1) {
        throw RuntimeException("Expected 1 testSuites Tests, got $listOfTestSuites")
      }
      val testSuiteBuilder =
        variantBuilder.suites["myBackup"]
          ?: throw RuntimeException("Cannot find myBackup test suite in test suites : " + variantBuilder.suites.keys.joinToString(", "))

      Truth.assertThat(testSuiteBuilder.junitEngineSpec.includeEngines).contains("junit-jupiter")
      val inputs = testSuiteBuilder.junitEngineSpec.inputs
      Truth.assertThat(inputs)
        .containsExactly(
          AgpTestSuiteInputParameters.TEST_CLASSES,
          AgpTestSuiteInputParameters.TEST_CLASSPATH,
          AgpTestSuiteInputParameters.MAIN_CLASSES,
          AgpTestSuiteInputParameters.MAIN_CLASSPATH,
          AgpTestSuiteInputParameters.TEST_APKS,
          AgpTestSuiteInputParameters.TESTED_APKS,
        )
    }

    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
      val testSuites = variant as HasTestSuites
      val myBackupTestSuite =
        testSuites.suites["myBackup"]
          ?: throw RuntimeException("Cannot find myBackup test suite in test suites : " + testSuites.suites.keys.joinToString(", "))
      Truth.assertThat(myBackupTestSuite).isInstanceOf(TestSuite::class.java)
    }

    // Verify targetVariants.add("debug") excluded the release build type
    androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
      val testSuites = variant as HasTestSuites
      Truth.assertThat(testSuites.suites).isEmpty()
    }
  }
}
