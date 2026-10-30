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

package com.android.build.gradle.integration.model

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.TestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.fixture.model.toValueString
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.builder.model.v2.ide.SyncIssue
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Integration tests for fetching javadoc, source and samples. */
class AdditionalArtifactsModelTest {

  @get:Rule val project = GradleTestProject.builder().fromTestApp(setUpTestProject()).withKotlinGradlePlugin(true).create()

  private fun setUpTestProject(): TestProject {
    return MultiModuleTestProject.builder()
      .subproject(APP_MODULE, HelloWorldApp.forPlugin("com.android.application"))
      .subproject(LIBRARY_MODULE, MinimalSubProject.lib(LIBRARY_PACKAGE))
      .build()
  }

  private lateinit var app: GradleTestProject
  private lateinit var library: GradleTestProject

  @Before
  fun setUp() {
    app = project.getSubproject(APP_MODULE)
    library = project.getSubproject(LIBRARY_MODULE)

    TestFileUtils.appendToFile(
      project.settingsFile,
      """
      dependencyResolutionManagement {
          repositories {
              maven { url = 'testrepo' }
          }
      }
      """
        .trimIndent(),
    )

    TestFileUtils.appendToFile(
      app.buildFile,
      """
      dependencies {
          implementation 'com.example.android:myLib:1.0'
      }
      """
        .trimIndent(),
    )

    TestFileUtils.appendToFile(
      library.buildFile,
      """
      apply plugin: 'maven-publish'

      afterEvaluate {
          publishing {
              repositories {
                  maven { url = '../testrepo' }
              }
          }
      }
      """
        .trimIndent(),
    )
  }

  @Test
  fun testModelFetchingArtifactsWithMultipleVariants() {
    addPublication(DEFAULT)
    TestFileUtils.appendToFile(
      library.buildFile,
      """
                android {
                    flavorDimensions "version"
                    productFlavors {
                        demo { }
                        full { }
                    }

                    publishing {
                        multipleVariants("$DEFAULT") {
                            allVariants()
                            withSourcesJar()
                            withJavadocJar()
                        }
                    }
                }
            """
        .trimIndent(),
    )
    TestFileUtils.appendToFile(
      app.buildFile,
      """
      android {
          flavorDimensions "version"
          productFlavors {
              demo { }
              full { }
          }
      }
      """
        .trimIndent(),
    )

    library.execute("clean", "publish")
    val result = app.modelV2().ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels(variantName = "demoDebug")

    val variantDeps =
      result.container.getProject(":app").variantDependencies ?: throw RuntimeException("No VariantDependencies model for :app")

    val lib =
      variantDeps.libraries.values.singleOrNull {
        it.libraryInfo?.let { info -> info.name == "myLib" && info.attributes["org.gradle.usage"] == "java-runtime" } ?: false
      }
    Truth.assertWithMessage("myLib").that(lib).isNotNull()
    Truth.assertThat(lib?.srcJars?.toValueString(result.normalizer))
      .isEqualTo("[{PROJECT}/testrepo/com/example/android/myLib/1.0/myLib-1.0-demoDebug-sources.jar{F}]")
    Truth.assertThat(lib?.docJar?.toValueString(result.normalizer))
      .isEqualTo("{PROJECT}/testrepo/com/example/android/myLib/1.0/myLib-1.0-demoDebug-javadoc.jar{F}")
  }

  @Test
  fun testModelFetchingArtifactsWithSingleVariant() {
    addPublication(RELEASE)
    TestFileUtils.appendToFile(
      library.buildFile,
      """
                android {
                    publishing {
                        singleVariant("$RELEASE") {
                            withSourcesJar()
                            withJavadocJar()
                        }
                    }
                }
            """
        .trimIndent(),
    )
    library.execute("clean", "publish")
    val result = app.modelV2().ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels(variantName = "release")

    val variantDeps =
      result.container.getProject(":app").variantDependencies ?: throw RuntimeException("No VariantDependencies model for :app")

    val lib =
      variantDeps.libraries.values.singleOrNull {
        it.libraryInfo?.let { info -> info.name == "myLib" && info.attributes["org.gradle.usage"] == "java-runtime" } ?: false
      }
    Truth.assertWithMessage("myLib").that(lib).isNotNull()
    Truth.assertThat(lib?.srcJars?.toValueString(result.normalizer))
      .isEqualTo("[{PROJECT}/testrepo/com/example/android/myLib/1.0/myLib-1.0-sources.jar{F}]")
    Truth.assertThat(lib?.docJar?.toValueString(result.normalizer))
      .isEqualTo("{PROJECT}/testrepo/com/example/android/myLib/1.0/myLib-1.0-javadoc.jar{F}")
  }

  @Test
  fun testModelFetchingArtifactsFromMultiplatformLibrary() {
    library.buildFile.writeText(
      """
      plugins {
          id 'org.jetbrains.kotlin.multiplatform'
          id 'com.android.kotlin.multiplatform.library'
          id 'maven-publish'
      }

      group = 'com.example.android'
      version = '1.0'

      kotlin {
          jvm()
          android {
              namespace = '$LIBRARY_PACKAGE'
              compileSdk = ${GradleTestProject.DEFAULT_COMPILE_SDK_VERSION}
          }
      }

      publishing {
          repositories {
              maven { url = '../testrepo' }
          }
      }
      """
        .trimIndent()
    )
    TestFileUtils.searchAndReplace(
      app.buildFile,
      "com.example.android:myLib:1.0",
      "com.example.android:library:1.0",
    )

    library.execute("publish")
    val result = app.modelV2().ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels(variantName = "debug")

    val variantDeps =
      result.container.getProject(":app").variantDependencies ?: throw RuntimeException("No VariantDependencies model for :app")

    val runtimeLib =
      variantDeps.libraries.values.singleOrNull {
        it.libraryInfo?.let { info ->
          info.name == "library-android" && info.attributes["org.gradle.usage"] == "java-runtime"
        } ?: false
      }
    Truth.assertWithMessage("library-android (java-runtime)").that(runtimeLib).isNotNull()
    Truth.assertThat(runtimeLib?.srcJars?.toValueString(result.normalizer))
      .isEqualTo("[{PROJECT}/testrepo/com/example/android/library-android/1.0/library-android-1.0-sources.jar{F}]")

    val compileLib =
      variantDeps.libraries.values.singleOrNull {
        it.libraryInfo?.let { info ->
          info.name == "library-android" && info.attributes["org.gradle.usage"] == "java-api"
        } ?: false
      }
    Truth.assertWithMessage("library-android (java-api)").that(compileLib).isNotNull()
    Truth.assertThat(compileLib?.srcJars?.toValueString(result.normalizer))
      .isEqualTo("[{PROJECT}/testrepo/com/example/android/library-android/1.0/library-android-1.0-sources.jar{F}]")
  }

  private fun addPublication(componentName: String) {
    TestFileUtils.appendToFile(
      library.buildFile,
      """
                afterEvaluate {
                    publishing {

                        publications {
                            myPublication(MavenPublication) {
                                groupId = 'com.example.android'
                                artifactId = 'myLib'
                                version = '1.0'

                                from components.$componentName
                            }
                        }
                    }
                }
            """
        .trimIndent(),
    )
  }

  companion object {
    private const val APP_MODULE = ":app"
    private const val LIBRARY_MODULE = ":library"
    private const val LIBRARY_PACKAGE = "com.example.lib"
    private const val DEFAULT: String = "default"
    private const val RELEASE: String = "release"
  }
}
