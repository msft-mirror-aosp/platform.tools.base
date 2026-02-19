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
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.getIntermediateOutputDir
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import com.android.builder.model.v2.models.BasicTestSuite
import com.android.builder.model.v2.models.SourceType
import com.android.builder.model.v2.models.TestApkTestSuiteSource
import com.google.common.truth.Truth
import junit.framework.AssertionFailedError
import org.gradle.api.Project
import org.jetbrains.kotlin.konan.file.File
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TestSuiteWithCustomSourceSetTest(val testType: TestType) {

  enum class TestType {
    HOST_JAR,
    TEST_APK,
  }

  companion object {
    @JvmStatic @Parameterized.Parameters(name = "{0}") fun data() = TestType.values()
  }

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
              testOptions.suites.create("first", AgpTestSuite::class.java) {
                it.useJunitEngine.apply {
                  includeEngines.add("[engine:toy-junit-engine-for-tests]")
                  enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                  enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                  enginesDependencies.add("com.test:toy-junit-engine:1.0")
                  enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
                }
                it.targetVariants.add("debug")
                it.targets.apply { create("t1") {} }
              }
            }
            dependencies { implementation("com.google.truth:truth:0.44") }
            pluginCallbacks +=
              if (testType == TestType.HOST_JAR) AddStaticFolderToHostJarTestSuiteCallback::class.java
              else AddStaticFolderToTestApkTestSuiteCallback::class.java
          }
          .files {
            add("src/first/resources/some/random/file.txt", "some random text")
            add("src/shared/resources/some/random/res.txt", "another text")
          }
      }

  @Test
  fun upToDateCheck() {
    // Enable once test apk task manager starts processing java resources.
    Assume.assumeFalse(testType == TestType.TEST_APK)

    val project = rule.build
    var result: GradleBuildResult =
      project.executor
        .expectFailure() // TODO: it fails because Gradle complains I have no tests.
        .run("testFirstT1DebugTestSuite")

    Truth.assertThat(result.didWorkTasks).contains(":app:processFirstDebugJavaRes")
    val javaRes = getJavaRes(project)
    Truth.assertThat(javaRes.exists()).isTrue()
    Truth.assertThat(javaRes.resolve("some/random").listFiles().map { it.name }).containsExactly("file.txt", "res.txt")

    // Run it again to check that we are up to date.
    result = project.executor.expectFailure().run("testFirstT1DebugTestSuite")
    Truth.assertThat(result.upToDateTasks).contains(":app:processFirstDebugJavaRes")
  }

  @Test
  fun fileAddedCheck() {
    // Enable once test apk task manager starts processing java resources.
    Assume.assumeFalse(testType == TestType.TEST_APK)

    val project = rule.build
    var result: GradleBuildResult =
      project.executor
        .expectFailure() // TODO: it fails because Gradle complains I have no tests.
        .run("testFirstT1DebugTestSuite")

    Truth.assertThat(result.didWorkTasks).contains(":app:processFirstDebugJavaRes")
    val javaRes = getJavaRes(project).resolve("some${File.separatorChar}random")
    Truth.assertThat(javaRes.exists()).isTrue()
    Truth.assertThat(javaRes.listFiles().map { it.name }).containsExactly("file.txt", "res.txt")

    project.withReversibleModifications { build ->
      build.subProject(":app").files.run { add("src/shared/resources/some/random/third.txt", "yet another one") }

      // Run it again to check that we are not up to date.
      result = build.executor.expectFailure().run("testFirstT1DebugTestSuite")
      Truth.assertThat(result.didWorkTasks).contains(":app:processFirstDebugJavaRes")
      Truth.assertThat(javaRes.listFiles().map { it.name }).containsExactly("file.txt", "res.txt", "third.txt")
    }
  }

  @Test
  fun testModel() {
    val project = rule.build
    val result = project.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels()
    Truth.assertThat(result).isNotNull()
    val models = result.container.getProject(":app")

    // verify the test suite model
    val testSuites = models.basicAndroidProject?.testSuites ?: throw AssertionFailedError("no test suites defined in the project")
    Truth.assertThat(testSuites).hasSize(1)
    val firstTestSuite: BasicTestSuite = testSuites.single()
    Truth.assertThat(firstTestSuite.name).isEqualTo("first")
    val firstTestSuiteResources =
      if (testType == TestType.HOST_JAR) {
        val sources = firstTestSuite.hostJars.single()
        Truth.assertThat(sources.type).isEqualTo(SourceType.HOST_JAR)
        sources.resources
      } else {
        val sources: TestApkTestSuiteSource = firstTestSuite.testApks.single()
        Truth.assertThat(sources.type).isEqualTo(SourceType.TEST_APK)
        sources.sourceProvider.resourcesDirectories
      }
    Truth.assertThat(firstTestSuiteResources)
      .containsExactly(
        project.subProject(":app").resolve("src/first/resources").toFile(),
        project.subProject(":app").resolve("src/shared/resources").toFile(),
      )
  }

  private fun getJavaRes(project: GradleBuild) =
    InternalArtifactType.JAVA_RES.getIntermediateOutputDir(project.subProject(":app").buildDir.toFile())
      .resolve("firstDebug")
      .resolve("processFirstDebugJavaRes")
      .resolve("out")
}

open class AddStaticFolderToHostJarTestSuiteCallback : ApplicationComponentCallback {

  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.finalizeDsl { android ->
      android.testOptions.suites.getByName("first") { first ->
        first.hostJar {
          // use reflection as the API is not public yet.
          javaClass.getMethod("addStaticSourceSet", String::class.java).invoke(this, "src/shared")
        }
      }
    }
  }
}

open class AddStaticFolderToTestApkTestSuiteCallback : ApplicationComponentCallback {

  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.finalizeDsl { android ->
      android.testOptions.suites.getByName("first") { first ->
        first.testApk {
          // use reflection as the API is not public yet.
          javaClass.getMethod("addStaticSourceSet", String::class.java).invoke(this, "src/shared")
        }
      }
    }
  }
}
