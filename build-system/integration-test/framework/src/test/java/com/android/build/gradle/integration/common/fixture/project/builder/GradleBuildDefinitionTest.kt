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

package com.android.build.gradle.integration.common.fixture.project.builder

import com.android.Version
import com.android.build.gradle.integration.common.fixture.project.GenericProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.GenericProjectImpl
import com.android.build.gradle.integration.common.fixture.project.GradleBuildImpl
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import com.google.common.truth.Truth
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GradleBuildDefinitionTest {

  @get:Rule val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun testSettingsWithSingleProject() {
    val folder = writeBuild { genericProject(":app") {} }

    checkFile(
      folder.resolve("settings.gradle"),
      "settings file presence",
      """
      pluginManagement {
        repositories {
        }
      }

      plugins {
      }

      dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        repositories {
        }
      }

      rootProject.name = 'root'

      include(':app')

      """
        .trimIndent(),
    )
  }

  @Test
  fun testSettingsWithIncludedBuild() {
    val folder = writeBuild { includedBuild("build-logic") {} }

    checkFile(
      folder.resolve("settings.gradle"),
      "settings file presence",
      """
      pluginManagement {
        repositories {
        }
      }

      plugins {
      }

      dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        repositories {
        }
      }

      rootProject.name = 'root'

      includeBuild('build-logic')


      """
        .trimIndent(),
    )
  }

  @Test
  fun testRepositories() {
    val repo = temporaryFolder.newFolder().toPath()
    val folder = writeBuild(listOf(repo)) {}

    val repoUri = repo.toUri().toString()

    checkFile(
      folder.resolve("settings.gradle"),
      "settings file presence",
      """
                pluginManagement {
                  repositories {
                    maven {
                      url = uri('$repoUri')
                      metadataSources {
                        mavenPom()
                        artifact()
                      }
                    }
                  }
                }

                plugins {
                }

                dependencyResolutionManagement {
                  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                  repositories {
                    maven {
                      url = uri('$repoUri')
                      metadataSources {
                        mavenPom()
                        artifact()
                      }
                    }
                  }
                }

                rootProject.name = 'root'


            """
        .trimIndent(),
    )
  }

  @Test
  fun testPlugins() {
    val folder = writeBuild {
      androidApplication {}
      genericProject(":library") { applyPlugin(PluginType.JAVA_LIBRARY) }
    }

    checkFile(
      folder.resolve("build.gradle"),
      "root build file presence",
      """
                plugins {
                  id('com.android.application') version '${Version.ANDROID_GRADLE_PLUGIN_VERSION}' apply false
                }


            """
        .trimIndent(),
    )

    checkFile(
      folder.resolve("app/build.gradle"),
      "app build file presence",
      """
                plugins {
                  id('com.android.application')
                }

                android {
                  namespace = 'pkg.name.app'
                  compileSdk {
                    version = release(${GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION})
                  }
                }


            """
        .trimIndent(),
    )

    checkFile(
      folder.resolve("library/build.gradle"),
      "library build file presence",
      """
      plugins {
        id('java-library')
      }


      """
        .trimIndent(),
    )
  }

  @Test
  fun testKtsPlugins() {
    val folder = writeBuild {
      buildFileType = BuildFileType.KTS
      androidApplication {}
      genericProject(":library") { applyPlugin(PluginType.JAVA_LIBRARY) }
    }

    checkFile(
      folder.resolve("build.gradle.kts"),
      "root build file presence",
      """
                plugins {
                  id("com.android.application") version "${Version.ANDROID_GRADLE_PLUGIN_VERSION}" apply false
                }


            """
        .trimIndent(),
    )

    checkFile(
      folder.resolve("app/build.gradle.kts"),
      "app build file presence",
      """
                plugins {
                  id("com.android.application")
                }

                android {
                  namespace = "pkg.name.app"
                  compileSdk {
                    version = release(${GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION})
                  }
                }


            """
        .trimIndent(),
    )

    checkFile(
      folder.resolve("library/build.gradle.kts"),
      "library build file presence",
      """
      plugins {
        id("java-library")
      }


      """
        .trimIndent(),
    )
  }

  @Test
  fun testDependencies() {
    val folder = writeBuild {
      genericProject(":app") { dependencies { api(project(":library")) } }
      genericProject(":library") {}
    }

    checkFile(
      folder.resolve("app/build.gradle"),
      "app build file presence",
      """
      plugins {
      }

      dependencies {
        api(project(':library'))
      }


      """
        .trimIndent(),
    )
  }

  @Test
  fun testKgpTestLayer() {
    Truth.assertThat(KgpTestLayer.MINIMUM.buildscriptClasspathVersion).isEqualTo(TestUtils.MINIMUM_BUILT_IN_KOTLIN_VERSION)
    Truth.assertThat(KgpTestLayer.DEFAULT.buildscriptClasspathVersion).isNull()
    Truth.assertThat(KgpTestLayer.LATEST.buildscriptClasspathVersion).isEqualTo(TestUtils.KOTLIN_VERSION_FOR_TESTS)

    val defaultFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.DEFAULT
      androidApplication {}
    }
    checkFile(
      defaultFolder.resolve("build.gradle"),
      "root build file for DEFAULT layer",
      """
      plugins {
        id('com.android.application') version '${Version.ANDROID_GRADLE_PLUGIN_VERSION}' apply false
      }


      """
        .trimIndent(),
    )

    val minimumFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.MINIMUM
      androidApplication {}
    }
    checkFile(
      minimumFolder.resolve("build.gradle"),
      "root build file for MINIMUM layer",
      """
      buildscript {
        dependencies {
          classpath('org.jetbrains.kotlin:kotlin-gradle-plugin:${TestUtils.MINIMUM_BUILT_IN_KOTLIN_VERSION}')
        }
      }
      plugins {
        id('com.android.application') version '${Version.ANDROID_GRADLE_PLUGIN_VERSION}' apply false
      }


      """
        .trimIndent(),
    )

    val latestFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.LATEST
      androidApplication {}
    }
    checkFile(
      latestFolder.resolve("build.gradle"),
      "root build file for LATEST layer",
      """
      buildscript {
        dependencies {
          classpath('org.jetbrains.kotlin:kotlin-gradle-plugin:${TestUtils.KOTLIN_VERSION_FOR_TESTS}')
        }
      }
      plugins {
        id('com.android.application') version '${Version.ANDROID_GRADLE_PLUGIN_VERSION}' apply false
      }


      """
        .trimIndent(),
    )

    val kotlinPluginDefaultFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.DEFAULT
      genericProject(":lib") { applyPlugin(PluginType.KOTLIN_JVM) }
    }
    checkFile(
      kotlinPluginDefaultFolder.resolve("build.gradle"),
      "root build file for KOTLIN_JVM on DEFAULT layer",
      """
      plugins {
        id('org.jetbrains.kotlin.jvm') version '${TestUtils.BUILT_IN_KOTLIN_VERSION}' apply false
      }


      """
        .trimIndent(),
    )

    val kotlinPluginMinimumFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.MINIMUM
      genericProject(":lib") { applyPlugin(PluginType.KOTLIN_JVM) }
    }
    checkFile(
      kotlinPluginMinimumFolder.resolve("build.gradle"),
      "root build file for KOTLIN_JVM on MINIMUM layer",
      """
      plugins {
        id('org.jetbrains.kotlin.jvm') version '${TestUtils.MINIMUM_BUILT_IN_KOTLIN_VERSION}' apply false
      }


      """
        .trimIndent(),
    )

    val kotlinPluginLatestFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.LATEST
      genericProject(":lib") { applyPlugin(PluginType.KOTLIN_JVM) }
    }
    checkFile(
      kotlinPluginLatestFolder.resolve("build.gradle"),
      "root build file for KOTLIN_JVM on LATEST layer",
      """
      plugins {
        id('org.jetbrains.kotlin.jvm') version '${TestUtils.KOTLIN_VERSION_FOR_TESTS}' apply false
      }


      """
        .trimIndent(),
    )

    val kotlinAndroidMinimumFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.MINIMUM
      androidApplication { @Suppress("DEPRECATION") applyPlugin(PluginType.KOTLIN_ANDROID) }
      gradleProperties {
        add(BooleanOption.BUILT_IN_KOTLIN, false)
        add(BooleanOption.USE_NEW_DSL, false)
      }
    }
    checkFile(
      kotlinAndroidMinimumFolder.resolve("build.gradle"),
      "root build file for KOTLIN_ANDROID on MINIMUM layer (no duplicate buildscript classpath)",
      """
      plugins {
        id('com.android.application') version '${Version.ANDROID_GRADLE_PLUGIN_VERSION}' apply false
        id('org.jetbrains.kotlin.android') version '${TestUtils.MINIMUM_BUILT_IN_KOTLIN_VERSION}' apply false
      }


      """
        .trimIndent(),
    )

    val kaptLatestFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.LATEST
      androidApplication { applyPlugin(PluginType.KAPT) }
    }
    checkFile(
      kaptLatestFolder.resolve("build.gradle"),
      "root build file for KAPT on LATEST layer (no duplicate buildscript classpath)",
      """
      plugins {
        id('com.android.application') version '${Version.ANDROID_GRADLE_PLUGIN_VERSION}' apply false
        id('org.jetbrains.kotlin.kapt') version '${TestUtils.KOTLIN_VERSION_FOR_TESTS}' apply false
      }


      """
        .trimIndent(),
    )

    val explicitVersionFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.LATEST
      genericProject(":lib") { applyPlugin(PluginType.KOTLIN_JVM, version = "1.9.22") }
    }
    checkFile(
      explicitVersionFolder.resolve("build.gradle"),
      "root build file for explicit KOTLIN_JVM version on LATEST layer",
      """
      plugins {
        id('org.jetbrains.kotlin.jvm') version '1.9.22' apply false
      }


      """
        .trimIndent(),
    )

    val replacedVersionFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.LATEST
      genericProject(":lib") {
        applyPlugin(PluginType.KOTLIN_JVM)
        replaceAppliedPlugin(PluginType.KOTLIN_JVM, "2.2.10")
      }
    }
    checkFile(
      replacedVersionFolder.resolve("build.gradle"),
      "root build file for replaced KOTLIN_JVM version on LATEST layer",
      """
      plugins {
        id('org.jetbrains.kotlin.jvm') version '2.2.10' apply false
      }


      """
        .trimIndent(),
    )

    val oldPluginStyleMinimumFolder = writeBuild {
      kgpTestLayer = KgpTestLayer.MINIMUM
      useOldPluginStyleForSeparateClassloaders = true
      genericProject(":lib") {
        applyPlugin(PluginType.KOTLIN_JVM)
        applyPlugin(PluginType.KAPT)
      }
    }
    checkFile(
      oldPluginStyleMinimumFolder.resolve("lib/build.gradle"),
      "subproject build file for oldPluginStyle on MINIMUM layer",
      """
      buildscript {
        repositories {
        }
        dependencies {
          classpath('org.jetbrains.kotlin:kotlin-gradle-plugin:${TestUtils.MINIMUM_BUILT_IN_KOTLIN_VERSION}')
        }
      }

      apply plugin: 'org.jetbrains.kotlin.jvm'
      apply plugin: 'org.jetbrains.kotlin.kapt'


      """
        .trimIndent(),
    )
  }

  @Test
  fun testReconfigureSubProjectPreservesKgpTestLayer() {
    val buildDefinition =
      GradleBuildDefinitionImpl("root", "root", enableDefaultContentCreation = true).apply {
        kgpTestLayer = KgpTestLayer.MINIMUM
        useOldPluginStyleForSeparateClassloaders = true
        genericProject(":lib") { applyPlugin(PluginType.KOTLIN_JVM) }
      }
    val folder = temporaryFolder.newFolder().toPath()
    val globalState =
      GlobalDefinitionStateImpl(
        additionalProperties = listOf(),
        repositories = listOf(),
        customPluginMap = mapOf(),
      )
    buildDefinition.write(
      location = folder,
      globalDefinitionState = globalState,
      disableUnnecessaryBuiltInKotlinOptOutChecks = false,
      disableUnnecessaryNewDslOptOutChecks = false,
    )
    val libDefinition = buildDefinition.subProjects.getValue(":lib") as GenericProjectDefinition
    val libProject = GenericProjectImpl(folder.resolve("lib"), libDefinition)
    val gradleBuild =
      GradleBuildImpl(
        directory = folder,
        subProjects = mapOf(":lib" to libProject),
        definition = buildDefinition,
        executorProvider = { throw UnsupportedOperationException() },
        modelBuilderProvider = { throw UnsupportedOperationException() },
        mavenRepoPath = folder.resolve("maven"),
        profileDirectory = null,
      )
    libProject.build = gradleBuild

    libProject.reconfigure { group = "com.example" }

    checkFile(
      folder.resolve("lib/build.gradle"),
      "reconfigured subproject build file on MINIMUM layer",
      """
      buildscript {
        repositories {
        }
        dependencies {
          classpath('org.jetbrains.kotlin:kotlin-gradle-plugin:${TestUtils.MINIMUM_BUILT_IN_KOTLIN_VERSION}')
        }
      }

      apply plugin: 'org.jetbrains.kotlin.jvm'

      group = 'com.example'


      """
        .trimIndent(),
    )
  }

  @Test
  fun testAppliedPluginResolveVersion() {
    val defaultKgp = AppliedPlugin(PluginType.KOTLIN_JVM)
    Truth.assertThat(defaultKgp.explicitVersion).isNull()
    Truth.assertThat(defaultKgp.resolveVersion(KgpTestLayer.MINIMUM)).isEqualTo(TestUtils.MINIMUM_BUILT_IN_KOTLIN_VERSION)
    Truth.assertThat(defaultKgp.resolveVersion(KgpTestLayer.DEFAULT)).isEqualTo(TestUtils.BUILT_IN_KOTLIN_VERSION)
    Truth.assertThat(defaultKgp.resolveVersion(KgpTestLayer.LATEST)).isEqualTo(TestUtils.KOTLIN_VERSION_FOR_TESTS)

    val explicitKgp = AppliedPlugin(PluginType.KOTLIN_JVM, explicitVersion = "1.9.22")
    Truth.assertThat(explicitKgp.resolveVersion(KgpTestLayer.MINIMUM)).isEqualTo("1.9.22")
    Truth.assertThat(explicitKgp.resolveVersion(KgpTestLayer.DEFAULT)).isEqualTo("1.9.22")
    Truth.assertThat(explicitKgp.resolveVersion(KgpTestLayer.LATEST)).isEqualTo("1.9.22")

    val defaultAgp = AppliedPlugin(PluginType.ANDROID_APP)
    Truth.assertThat(defaultAgp.resolveVersion(KgpTestLayer.MINIMUM)).isEqualTo(Version.ANDROID_GRADLE_PLUGIN_VERSION)
    Truth.assertThat(defaultAgp.resolveVersion(KgpTestLayer.LATEST)).isEqualTo(Version.ANDROID_GRADLE_PLUGIN_VERSION)

    val explicitAgp = AppliedPlugin(PluginType.ANDROID_APP, explicitVersion = "8.9.0")
    Truth.assertThat(explicitAgp.resolveVersion(KgpTestLayer.MINIMUM)).isEqualTo("8.9.0")

    val internalPlugin = AppliedPlugin(PluginType.JAVA_LIBRARY)
    Truth.assertThat(internalPlugin.resolveVersion(KgpTestLayer.MINIMUM)).isEqualTo(INTERNAL_PLUGIN_VERSION)
    Truth.assertThat(internalPlugin.resolveVersion(KgpTestLayer.DEFAULT)).isEqualTo(INTERNAL_PLUGIN_VERSION)
  }

  private fun checkFile(file: Path, named: String, content: String) {
    Truth.assertThat(file.isRegularFile()).named(named).isTrue()
    Truth.assertThat(file.readText()).isEqualTo(content)
  }

  private fun writeBuild(repositories: List<Path> = listOf(), action: GradleBuildDefinition.() -> Unit): Path {
    val build = GradleBuildDefinitionImpl("root", "root", enableDefaultContentCreation = true).also { action(it) }

    val folder = temporaryFolder.newFolder().toPath()

    build.write(
      location = folder,
      globalDefinitionState =
        GlobalDefinitionStateImpl(additionalProperties = listOf(), repositories = repositories, customPluginMap = mapOf()),
      disableUnnecessaryBuiltInKotlinOptOutChecks = false,
      disableUnnecessaryNewDslOptOutChecks = false,
    )

    return folder
  }
}
