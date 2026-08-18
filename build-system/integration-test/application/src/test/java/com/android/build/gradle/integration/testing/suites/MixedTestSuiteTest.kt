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

import com.android.Version
import com.android.build.api.dsl.AgpTestSuite
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import com.android.builder.model.v2.models.SourceType
import com.android.testutils.TestUtils
import com.android.tools.bazel.avd.Emulator
import com.google.common.truth.Truth
import java.io.File
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class MixedTestSuiteTest {

  companion object {
    @ClassRule
    @JvmField
    val emulator =
      if (TestUtils.runningFromBazel()) {
        Emulator(System.getProperty("EMULATOR_SCRIPT_PATH"), 5554)
      } else {
        object : ExternalResource() {}
      }
  }

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
        jar("com.test:toy-junit-engine:1.0")
          .addClasses(ToyJunitEngineForTesting::class.java, ToyTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", ToyJunitEngineForTesting::class.java.name)
      }
      .from {
        rootProject { buildscript { classpath("com.google.truth:truth:0.44") } }
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication {
          android {
            namespace = "com.example.app"
            testOptions.suites.create("mixed", AgpTestSuite::class.java) {
              it.requiresUpdateTask = true
              it.useJunitEngine.apply {
                includeEngines.add("[engine:toy-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher:1.13.3")
                enginesDependencies.add("com.test:toy-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
                inputs.add(AgpTestSuiteInputParameters.TEST_APKS)
              }
              it.hostJar {}
              it.testApk {}
              it.assets {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          dependencies { implementation("com.google.truth:truth:0.44") }
          files {
            add(
              "src/mixed/test/java/com/example/app/HostTest.java",
              """
              package com.example.app;
              public class HostTest {}
              """
                .trimIndent(),
            )
            add(
              "src/mixed/androidTest/java/com/example/app/DeviceTest.java",
              """
              package com.example.app;
              public class DeviceTest {}
              """
                .trimIndent(),
            )
            add("src/mixed/assetsTest/sample_asset.txt", "sample asset content")
          }
        }
      }

  @Test
  fun testMixedSuiteExecution() {
    val project = rule.build
    val result = project.executor.run(":app:testMixedT1DebugTestSuite")

    // Verify compilation tasks ran
    Truth.assertThat(result.didWorkTasks).contains(":app:compileMixedHostJarDebugJavaWithJavac")
    Truth.assertThat(result.didWorkTasks).contains(":app:compileMixedTestApkDebugJavaWithJavac")

    // Verify packaging task ran
    Truth.assertThat(result.didWorkTasks).contains(":app:packageMixedTestApkDebug")

    // Verify junit_inputs.txt exists and has the APK
    val buildDir = project.subProject(":app").buildDir.toFile()
    val junitInputsFile = buildDir.resolve("intermediates/debug/testMixedT1DebugTestSuite/junit_inputs.txt")
    Truth.assertThat(junitInputsFile.exists()).isTrue()

    val properties = java.util.Properties().also { props -> junitInputsFile.reader().use { props.load(it) } }
    val apkPath = properties.getProperty("com.android.agp.test.TEST_APKS")

    Truth.assertThat(apkPath).isNotNull()
    Truth.assertThat(apkPath).isNotEmpty()
    val apkDir = File(apkPath)
    Truth.assertThat(apkDir.exists()).isTrue()
    Truth.assertThat(apkPath).endsWith("mixedTestApkDebug")
    Truth.assertThat(File(apkDir, "app-mixedTestApkDebug.apk").exists()).isTrue()
  }

  @Test
  fun testMixedSuiteUpdateExecution() {
    val project = rule.build
    val result = project.executor.run(":app:updateMixedT1DebugTestSuite")

    // Verify compilation tasks ran (should be the same as test task)
    Truth.assertThat(result.didWorkTasks).contains(":app:compileMixedHostJarDebugJavaWithJavac")
    Truth.assertThat(result.didWorkTasks).contains(":app:compileMixedTestApkDebugJavaWithJavac")

    // Verify packaging task ran
    Truth.assertThat(result.didWorkTasks).contains(":app:packageMixedTestApkDebug")

    // Verify junit_inputs.txt exists and has the APK
    val buildDir = project.subProject(":app").buildDir.toFile()
    val junitInputsFile = buildDir.resolve("intermediates/debug/updateMixedT1DebugTestSuite/junit_inputs.txt")
    Truth.assertThat(junitInputsFile.exists()).isTrue()
  }

  @Test
  fun testMixedSuiteAssets() {
    val project = rule.build
    val result = project.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels()
    Truth.assertThat(result).isNotNull()
    val models = result.container.getProject(":app")

    val testSuites = models.basicAndroidProject?.testSuites
    Truth.assertThat(testSuites).isNotNull()
    val mixedTestSuite = testSuites!!.first { it.name == "mixed" }

    val assetsFolder = mixedTestSuite.assets.single()
    Truth.assertThat(assetsFolder.type).isEqualTo(SourceType.ASSETS)
    Truth.assertThat(assetsFolder.directories).containsExactly(project.subProject(":app").resolve("src/mixed/assetsTest").toFile())

    val hostJarFolder = mixedTestSuite.hostJars.single()
    Truth.assertThat(hostJarFolder.type).isEqualTo(SourceType.HOST_JAR)
    Truth.assertThat(hostJarFolder.java).containsExactly(project.subProject(":app").resolve("src/mixed/test/java").toFile())

    val testApkFolder = mixedTestSuite.testApks.single()
    Truth.assertThat(testApkFolder.type).isEqualTo(SourceType.TEST_APK)
    Truth.assertThat(testApkFolder.sourceProvider.javaDirectories)
      .containsExactly(project.subProject(":app").resolve("src/mixed/androidTest/java").toFile())
  }
}
