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
import com.android.build.api.testsuites.TestEngineInputProperty
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import com.android.builder.model.v2.models.SourceType
import com.google.common.truth.Truth
import java.io.File
import java.util.Properties
import kotlin.io.path.inputStream
import org.junit.Rule
import org.junit.Test

/** Tests for test suites declaring both an `assets` and a `hostJar` source set. */
class AssetsAndHostJarTestSuiteTest {

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
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication {
          android {
            namespace = "com.example.test"
            testOptions.customSuites.create("first") {
              it.useJunitEngine.apply {
                includeEngines.add("[engine:toy-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:toy-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
              }
              it.assets {}
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          files {
            add("src/first/test/java/Dummy.java", "public class Dummy {}")
            add("src/first/assetsTest/some_asset.txt", "some asset content")
          }
          dependencies { implementation("com.google.truth:truth:0.44") }
        }
      }

  @Test
  fun testSuiteWithBothSourceSetsRuns() {
    val project = rule.build
    val result = project.executor.run("testDebugFirstT1TestSuite")

    Truth.assertThat(result.didWorkTasks).contains(":app:testDebugFirstT1TestSuite")
    Truth.assertThat(result.didWorkTasks).contains(":app:compileDebugFirstTestJavaWithJavac")

    val engineInputs =
      Properties().apply {
        project.subProject(":app").buildDir.resolve("intermediates/debug/testDebugFirstT1TestSuite/junit_inputs.txt").inputStream().use {
          load(it)
        }
      }
    Truth.assertThat(engineInputs.getProperty(TestEngineInputProperty.SOURCE_FOLDERS))
      .contains("src${File.separator}first${File.separator}assetsTest")
    Truth.assertThat(engineInputs.getProperty(TestEngineInputProperty.BINARY_FOLDERS)).isNotEmpty()
  }

  @Test
  fun testSuiteWithBothSourceSetsConfigures() {
    val project = rule.build {
      androidApplication {
        android {
          testOptions.customSuites.create("second") {
            it.useJunitEngine.apply { includeEngines.add("[engine:toy-junit-engine-for-tests]") }
            it.assets {}
            it.testApk {}
            it.targetVariants.add("debug")
            it.targets.apply { create("t1") {} }
          }
        }
      }
    }
    project.executor.run(":app:tasks")

    val result = project.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels()
    val models = result.container.getProject(":app")

    val firstSuite = models.basicAndroidProject!!.testSuites.first { it.name == "first" }

    val assetsSource = firstSuite.assets.single()
    Truth.assertThat(assetsSource.name).isEqualTo("firstAssets")
    Truth.assertThat(assetsSource.type).isEqualTo(SourceType.ASSETS)
    Truth.assertThat(assetsSource.directories).containsExactly(project.subProject(":app").resolve("src/first/assetsTest").toFile())

    val hostJarSource = firstSuite.hostJars.single()
    Truth.assertThat(hostJarSource.name).isEqualTo("firstTest")
    Truth.assertThat(hostJarSource.type).isEqualTo(SourceType.HOST_JAR)
    Truth.assertThat(hostJarSource.java).containsExactly(project.subProject(":app").resolve("src/first/test/java").toFile())

    val secondSuite = models.basicAndroidProject!!.testSuites.first { it.name == "second" }

    val secondAssetsSource = secondSuite.assets.single()
    Truth.assertThat(secondAssetsSource.name).isEqualTo("secondAssets")
    Truth.assertThat(secondAssetsSource.type).isEqualTo(SourceType.ASSETS)
    Truth.assertThat(secondAssetsSource.directories).containsExactly(project.subProject(":app").resolve("src/second/assetsTest").toFile())

    val testApkSource = secondSuite.testApks.single()
    Truth.assertThat(testApkSource.name).isEqualTo("secondAndroidTest")
    Truth.assertThat(testApkSource.type).isEqualTo(SourceType.TEST_APK)
    Truth.assertThat(testApkSource.sourceProvider.javaDirectories)
      .containsExactly(project.subProject(":app").resolve("src/second/androidTest/java").toFile())

    val depsResult = project.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchVariantDependencies("debug")
    val debugDeps = depsResult.container.getProject(":app").variantDependencies!!
    Truth.assertThat(debugDeps.testSuiteArtifacts["first"]!!.sourcesDependencies.map { it.name })
      .containsExactly("debugFirstAssets", "debugFirstTest")
    Truth.assertThat(debugDeps.testSuiteArtifacts["second"]!!.sourcesDependencies.map { it.name })
      .containsExactly("debugSecondAssets", "debugSecondAndroidTest")
  }
}
