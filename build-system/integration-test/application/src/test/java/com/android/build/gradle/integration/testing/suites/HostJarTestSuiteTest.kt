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

package com.android.build.gradle.integration.testing.suites

import com.android.Version
import com.android.build.api.dsl.AgpTestSuite
import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject
import com.google.common.truth.Truth
import org.jetbrains.kotlin.konan.file.File
import org.junit.Rule
import org.junit.Test

class HostJarTestSuiteJavaResProcessingTest {
  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("org.junit.platform:junit-platform-engine:1.10.1")
        jar("org.junit.platform:junit-platform-launcher:1.10.1")
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
            flavorDimensions += "color"
            productFlavors {
              create("red") { it.dimension = "color" }
              create("blue") { it.dimension = "color" }
            }
            testOptions.suites.create("first", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                includeEngines.add("[engine:toy-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:toy-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
              }
              it.hostJar {}
              it.targetVariants.add("redDebug")
              it.targetVariants.add("blueDebug")
              it.targets.apply { create("t1") {} }
              it.targets.apply { create("t2") {} }
            }
          }
          files {
            add("src/first/resources/some/random/file.txt", "some random text")
            add("src/first/resources/some/random/res.txt", "another text")
          }
          dependencies { implementation("com.google.truth:truth:0.44") }
        }
      }

  @Test
  fun upToDateCheck() {
    val project = rule.build
    var result: GradleBuildResult =
      project.executor
        .expectFailure() // TODO: it fails because Gradle complains I have no tests.
        .run("testFirstT1RedDebugTestSuite")

    Truth.assertThat(result.didWorkTasks).contains(":app:processFirstRedDebugJavaRes")
    val javaRes = getJavaRes(project)
    PathSubject.assertThat(javaRes).exists()
    PathSubject.assertThat(javaRes.resolve("some/random/file.txt")).contains("some random text")

    // Run it again to check that we are up to date.
    result = project.executor.expectFailure().run("testFirstT1RedDebugTestSuite")
    Truth.assertThat(result.upToDateTasks).contains(":app:processFirstRedDebugJavaRes")
  }

  @Test
  fun fileRemovedCheck() {
    val project = rule.build
    var result: GradleBuildResult =
      project.executor
        .expectFailure() // TODO: it fails because Gradle complains I have no tests.
        .run("testFirstT1RedDebugTestSuite")

    Truth.assertThat(result.didWorkTasks).contains(":app:processFirstRedDebugJavaRes")
    val javaRes = getJavaRes(project).resolve("some${File.separatorChar}random")

    PathSubject.assertThat(javaRes).exists()
    Truth.assertThat(javaRes.listFiles().map { it.name }).containsExactly("file.txt", "res.txt")

    project.withReversibleModifications { build ->
      build.subProject(":app").files.run { remove("src/first/resources/some/random/res.txt") }

      // Run it again to check that we are not up to date.
      result = build.executor.expectFailure().run("testFirstT1RedDebugTestSuite")
      Truth.assertThat(result.didWorkTasks).contains(":app:processFirstRedDebugJavaRes")
      Truth.assertThat(javaRes.listFiles().map { it.name }).containsExactly("file.txt")
    }
  }

  @Test
  fun fileAddedCheck() {
    val project = rule.build
    var result: GradleBuildResult =
      project.executor
        .expectFailure() // TODO: it fails because Gradle complains I have no tests.
        .run("testFirstT1RedDebugTestSuite")

    Truth.assertThat(result.didWorkTasks).contains(":app:processFirstRedDebugJavaRes")
    val javaRes = getJavaRes(project).resolve("some${File.separatorChar}random")
    PathSubject.assertThat(javaRes).exists()
    Truth.assertThat(javaRes.listFiles().map { it.name }).containsExactly("file.txt", "res.txt")

    project.withReversibleModifications { build ->
      build.subProject(":app").files.run { add("src/first/resources/some/random/third.txt", "yet another one") }

      // Run it again to check that we are not up to date.
      result = build.executor.expectFailure().run("testFirstT1RedDebugTestSuite")
      Truth.assertThat(result.didWorkTasks).contains(":app:processFirstRedDebugJavaRes")
      Truth.assertThat(javaRes.listFiles().map { it.name }).containsExactly("file.txt", "res.txt", "third.txt")
    }
  }

  @Test
  fun fileChangedCheck() {
    val project: GradleBuild = rule.build
    var result: GradleBuildResult =
      project.executor
        .expectFailure() // TODO: it fails because Gradle complains I have no tests.
        .run("testFirstT1RedDebugTestSuite")

    Truth.assertThat(result.didWorkTasks).contains(":app:processFirstRedDebugJavaRes")
    val javaRes = getJavaRes(project)
    PathSubject.assertThat(javaRes).exists()

    project.withReversibleModifications { build ->
      build.subProject(":app").files.update("src/first/resources/some/random/file.txt") { replaceWith("some update") }

      // Run it again to check that we are not up to date.
      result = build.executor.expectFailure().run("testFirstT1RedDebugTestSuite")
      Truth.assertThat(result.didWorkTasks).contains(":app:processFirstRedDebugJavaRes")
      PathSubject.assertThat(javaRes.resolve("some/random/file.txt")).contains("some update")
    }
  }

  private fun getJavaRes(project: GradleBuild) =
    project
      .subProject(":app")
      .resolve(InternalArtifactType.JAVA_RES)
      .resolve("firstRedDebug")
      .resolve("processFirstRedDebugJavaRes")
      .resolve("out")
      .toFile()
}
