/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.builder.model.v2.ide.SyncIssue
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.TestInputsGenerator
import com.android.testutils.generateAarWithContent
import com.google.common.collect.ImmutableList
import org.junit.Rule
import org.junit.Test

class HelloWorldAppAndLibModelTest : ModelComparator() {
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android { enableKotlin = false }
        dependencies { implementation(project(DEFAULT_LIB_PATH)) }
      }
      androidLibrary { android { enableKotlin = false } }
    }

  @Test
  fun `test VariantDependencies`() {
    val result = rule.build.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels(variantName = "debug")

    with(result).compareVariantDependencies(projectAction = { getProject(DEFAULT_APP_PATH) }, goldenFile = "VariantDependencies")
  }
}

class AppAndLibTestFixturesModelTest : ModelComparator() {
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android { enableKotlin = false }
        dependencies {
          implementation(project(DEFAULT_LIB_PATH))
          androidTestImplementation(project(DEFAULT_LIB_PATH, testFixtures = true))
        }
      }
      androidLibrary {
        version = "1.2.3"

        android {
          testFixtures { enable = true }
          enableKotlin = false
        }
      }
    }

  @Test
  fun `test VariantDependencies`() {
    val result = rule.build.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels(variantName = "debug")

    with(result).compareVariantDependencies(projectAction = { getProject(DEFAULT_APP_PATH) }, goldenFile = "VariantDependencies")
  }
}

class AppAndJavaLibTestFixturesModelTest : ModelComparator() {
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android { enableKotlin = false }
        dependencies {
          implementation(project(":lib"))
          androidTestImplementation(project(":lib", testFixtures = true))
        }
      }
      genericProject(":lib") {
        applyPlugin(PluginType.JAVA_LIBRARY)
        applyPlugin(PluginType.JAVA_TEST_FIXTURES)
        version = "1.2.3"
      }
    }

  @Test
  fun `test VariantDependencies`() {
    val result = rule.build.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels(variantName = "debug")

    with(result).compareVariantDependencies(projectAction = { getProject(DEFAULT_APP_PATH) }, goldenFile = "VariantDependencies")
  }
}

class AppAndExternalJavaLibTestFixturesModelTest : ModelComparator() {
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android { enableKotlin = false }
        dependencies {
          implementation(
            MavenRepoGenerator.libraryWithFixtures(
              mavenCoordinate = "com.example:random-lib:1",
              packaging = "jar",
              mainLibrary = { artifact = TestInputsGenerator.jarWithEmptyClasses(listOf("com/example/jar/MyClass")) },
              fixtureLibrary = { artifact = TestInputsGenerator.jarWithEmptyClasses(listOf("com/example/jar/fixtures/MyClass")) },
            )
          )

          androidTestImplementation(externalLibrary("com.example:random-lib:1", testFixtures = true))
        }
      }
    }

  @Test
  fun `test VariantDependencies`() {
    val result = rule.build.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels(variantName = "debug")

    with(result).compareVariantDependencies(projectAction = { getProject(DEFAULT_APP_PATH) }, goldenFile = "VariantDependencies")
  }
}

class AppAndExternalAarLibTestFixturesModelTest : ModelComparator() {
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android { enableKotlin = false }
        dependencies {
          implementation(
            MavenRepoGenerator.libraryWithFixtures(
              mavenCoordinate = "com.example:aar:1",
              packaging = "aar",
              mainLibrary = {
                artifact =
                  generateAarWithContent(
                    packageName = "com.example.aar",
                    mainJar = TestInputsGenerator.jarWithEmptyClasses(ImmutableList.of("com/example/aar/AarClass")),
                    resources =
                      mapOf(
                        "values/strings.xml" to """<resources><string name="aar_string">Aar String</string></resources>""".toByteArray()
                      ),
                  )
              },
              fixtureLibrary = {
                artifact =
                  generateAarWithContent(
                    packageName = "com.example.aar.testfixtures",
                    mainJar = TestInputsGenerator.jarWithEmptyClasses(ImmutableList.of("com/example/aar/fixtures/AarClass")),
                    resources =
                      mapOf(
                        "values/strings.xml" to
                          """<resources><string name="aar_string">Aar Fixture String</string></resources>""".toByteArray()
                      ),
                  )
              },
            )
          )

          androidTestImplementation(externalLibrary("com.example:aar:1", testFixtures = true))
        }
      }
    }

  @Test
  fun `test VariantDependencies`() {
    val result = rule.build.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels(variantName = "debug")

    with(result).compareVariantDependencies(projectAction = { getProject(DEFAULT_APP_PATH) }, goldenFile = "VariantDependencies")
  }
}
