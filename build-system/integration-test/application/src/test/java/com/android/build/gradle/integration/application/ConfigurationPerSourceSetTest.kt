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

package com.android.build.gradle.integration.application

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.plugins.AndroidKotlinMultiplatformLibraryComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.internal.tasks.factory.dependsOn
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

/** Tests for [AndroidComponentsExtension.addSourceSetConfigurations]. */
class ConfigurationPerSourceSetTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.example:main:1.0.0")
        jar("com.example:debug:1.0.0")
        jar("com.example:release:1.0.0")
        jar("com.example:basic:1.0.0")
        jar("com.example:pro:1.0.0")
        jar("com.example:play:1.0.0")
        jar("com.example:other:1.0.0")
        jar("com.example:pro-play:1.0.0")
        jar("com.example:pro-play-debug:1.0.0")
        jar("com.example:test:1.0.0")
        jar("com.example:android-test:1.0.0")
        jar("com.example:android-test-debug:1.0.0")
        jar("com.example:android-test-pro:1.0.0")
        jar("com.example:android-test:1.0.0")
        jar("com.example:test-fixtures:1.0.0")
      }
      .from {
        androidApplication {
          android {
            flavorDimensions += listOf("model", "market")
            productFlavors {
              create("basic") { it.dimension = "model" }
              create("pro") { it.dimension = "model" }
              create("play") { it.dimension = "market" }
              create("other") { it.dimension = "market" }
            }
            testFixtures { enable = true }
          }
        }
      }

  @get:Rule
  val kmpRule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.example:main:1.0.0")
        jar("com.example:other:1.0.0")
      }
      .from {
        androidKotlinMultiplatformLibrary(":library", createMinimumProject = false) {
          android {
            namespace = "com.mylibrary.foo"
            compileSdk = DEFAULT_COMPILE_SDK_VERSION
          }
        }
      }

  @Test
  fun testBasicUsageKmp() {
    val build =
      kmpRule.build {
        androidKotlinMultiplatformLibrary(":library") {
          pluginCallbacks += BasicUsageCallbackKMP::class.java
          dependencies { add("androidMainFoo", "com.example:main:1.0.0") }
        }
      }

    val result =
      build.executor
        .withFailOnWarning(false) // b/455891987
        .with(BooleanOption.ENABLE_PROFILE_JSON, true)
        .run("printFooInputs")
    ScannerSubject.assertThat(result.stdout).contains("androidMain: main-1.0.0.jar, other-1.0.0.jar;")
  }

  class BasicUsageCallbackKMP : AndroidKotlinMultiplatformLibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: KotlinMultiplatformAndroidComponentsExtension) {
      val globalTaskProvider = project.tasks.register("printFooInputs")
      androidComponents.onVariants { variant ->
        project.configurations.getByName("androidMainFoo").dependencies.add(project.dependencies.create("com.example:other:1.0.0"))
        val variantTaskProvider =
          project.tasks.register("print${variant.name.replaceFirstChar { it.uppercase() }}FooInputs", PrintInputFilesTask::class.java)
        globalTaskProvider.dependsOn(variantTaskProvider)
        variantTaskProvider.configure {
          it.componentName.set(variant.name)
          it.inputFiles.from(variant.getResolvableConfiguration("foo"))
        }
      }
      // Call addSourceSetConfigurations after onVariants to check that the order of these
      // calls doesn't matter.
      androidComponents.addSourceSetConfigurations(suffix = "foo")
    }
  }

  @Test
  fun testBasicUsage() {
    val build =
      rule.build {
        androidApplication {
          pluginCallbacks += BasicUsageCallback::class.java
          dependencies {
            add("foo", "com.example:main:1.0.0")
            add("debugFoo", "com.example:debug:1.0.0")
            add("releaseFoo", "com.example:release:1.0.0")
            add("basicFoo", "com.example:basic:1.0.0")
            add("proFoo", "com.example:pro:1.0.0")
            add("playFoo", "com.example:play:1.0.0")
            add("otherFoo", "com.example:other:1.0.0")
            add("testFoo", "com.example:test:1.0.0")
            add("androidTestFoo", "com.example:android-test:1.0.0")
            add("androidTestDebugFoo", "com.example:android-test-debug:1.0.0")
            add("androidTestProFoo", "com.example:android-test-pro:1.0.0")
            add("testFixturesFoo", "com.example:test-fixtures:1.0.0")
          }
        }
      }
    // Add .with(BooleanOption.ENABLE_PROFILE_JSON, true) as regression test for b/393189008
    val result = build.executor.with(BooleanOption.ENABLE_PROFILE_JSON, true).run("printFooInputs")
    ScannerSubject.assertThat(result.stdout)
      .contains(
        "proPlayDebug: debug-1.0.0.jar, main-1.0.0.jar, play-1.0.0.jar, pro-1.0.0.jar, pro-play-1.0.0.jar, pro-play-debug-1.0.0.jar;"
      )
    ScannerSubject.assertThat(result.stdout).contains("proPlayDebugUnitTest: test-1.0.0.jar;")
    ScannerSubject.assertThat(result.stdout)
      .contains("proPlayDebugAndroidTest: android-test-1.0.0.jar, android-test-debug-1.0.0.jar, android-test-pro-1.0.0.jar;")
    ScannerSubject.assertThat(result.stdout).contains("proPlayDebugTestFixtures: test-fixtures-1.0.0.jar;")
  }

  class BasicUsageCallback : ApplicationComponentCallback {

    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      val globalTaskProvider = project.tasks.register("printFooInputs")
      androidComponents.onVariants { variant ->
        project.configurations.getByName("proPlayFoo").dependencies.add(project.dependencies.create("com.example:pro-play:1.0.0"))
        project.configurations
          .getByName("proPlayDebugFoo")
          .dependencies
          .add(project.dependencies.create("com.example:pro-play-debug:1.0.0"))
        val variantTaskProvider =
          project.tasks.register("print${variant.name.replaceFirstChar { it.uppercase() }}FooInputs", PrintInputFilesTask::class.java)
        globalTaskProvider.dependsOn(variantTaskProvider)
        variantTaskProvider.configure {
          it.componentName.set(variant.name)
          it.inputFiles.from(variant.getResolvableConfiguration("foo"))
        }
        variant.nestedComponents.forEach { component ->
          val componentTaskProvider =
            project.tasks.register("print${component.name.replaceFirstChar { it.uppercase() }}FooInputs", PrintInputFilesTask::class.java)
          globalTaskProvider.dependsOn(componentTaskProvider)
          componentTaskProvider.configure {
            it.componentName.set(component.name)
            it.inputFiles.from(component.getResolvableConfiguration("foo"))
          }
        }
      }
      // Call addSourceSetConfigurations after onVariants to check that the order of these
      // calls doesn't matter.
      androidComponents.addSourceSetConfigurations(suffix = "foo")
    }
  }

  @Test
  fun testLegacyUsage() {
    val build =
      rule.build {
        androidApplication {
          pluginCallbacks += LegacyUsageCallback::class.java
          dependencies {
            add("ksp", "com.example:main:1.0.0")
            add("kspDebug", "com.example:debug:1.0.0")
            add("kspRelease", "com.example:release:1.0.0")
            add("kspBasic", "com.example:basic:1.0.0")
            add("kspPro", "com.example:pro:1.0.0")
            add("kspPlay", "com.example:play:1.0.0")
            add("kspOther", "com.example:other:1.0.0")
            add("kspTest", "com.example:test:1.0.0")
            add("kspAndroidTest", "com.example:android-test:1.0.0")
            add("kspAndroidTestDebug", "com.example:android-test-debug:1.0.0")
            add("kspAndroidTestPro", "com.example:android-test-pro:1.0.0")
            add("kspTestFixtures", "com.example:test-fixtures:1.0.0")
          }
        }
      }
    val result = build.executor.run("printKspInputs")
    ScannerSubject.assertThat(result.stdout)
      .contains(
        "proPlayDebug: debug-1.0.0.jar, main-1.0.0.jar, play-1.0.0.jar, pro-1.0.0.jar, pro-play-1.0.0.jar, pro-play-debug-1.0.0.jar;"
      )
    // The main jar is included in the nested component dependencies because useGlobalConfiguration is set to true.
    ScannerSubject.assertThat(result.stdout).contains("proPlayDebugUnitTest: main-1.0.0.jar, test-1.0.0.jar;")
    ScannerSubject.assertThat(result.stdout)
      .contains(
        "proPlayDebugAndroidTest: android-test-1.0.0.jar, android-test-debug-1.0.0.jar, android-test-pro-1.0.0.jar, main-1.0.0.jar;"
      )
    ScannerSubject.assertThat(result.stdout).contains("proPlayDebugTestFixtures: main-1.0.0.jar, test-fixtures-1.0.0.jar;")
  }

  class LegacyUsageCallback : ApplicationComponentCallback {

    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.addKspConfigurations(useGlobalConfiguration = true)
      val globalTaskProvider = project.tasks.register("printKspInputs")
      androidComponents.onVariants { variant ->
        project.configurations.getByName("kspProPlay").dependencies.add(project.dependencies.create("com.example:pro-play:1.0.0"))
        project.configurations
          .getByName("kspProPlayDebug")
          .dependencies
          .add(project.dependencies.create("com.example:pro-play-debug:1.0.0"))
        val variantTaskProvider =
          project.tasks.register("print${variant.name.replaceFirstChar { it.uppercase() }}KspInputs", PrintInputFilesTask::class.java)
        globalTaskProvider.dependsOn(variantTaskProvider)
        variantTaskProvider.configure {
          it.componentName.set(variant.name)
          it.inputFiles.from(variant.getResolvableConfiguration("ksp"))
        }
        variant.nestedComponents.forEach { component ->
          val componentTaskProvider =
            project.tasks.register("print${component.name.replaceFirstChar { it.uppercase() }}KspInputs", PrintInputFilesTask::class.java)
          globalTaskProvider.dependsOn(componentTaskProvider)
          componentTaskProvider.configure {
            it.componentName.set(component.name)
            it.inputFiles.from(component.getResolvableConfiguration("ksp"))
          }
        }
      }
    }
  }

  @Test
  fun testCollision() {
    val build = rule.build { androidApplication { pluginCallbacks += CollisionCallback::class.java } }
    val result = build.executor.expectFailure().run("tasks")
    ScannerSubject.assertThat(result.stderr).contains("Multiple identical calls to addSourceSetConfigurations is not supported.")
  }

  class CollisionCallback : ApplicationComponentCallback {

    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.addSourceSetConfigurations(suffix = "foo")
      androidComponents.addSourceSetConfigurations(suffix = "Foo")
    }
  }

  @Test
  fun testInvalidResolvableConfiguration() {
    val build = rule.build { androidApplication { pluginCallbacks += InvalidResolvableConfigurationCallback::class.java } }
    val result = build.executor.expectFailure().run("tasks")
    ScannerSubject.assertThat(result.stderr).contains("Invalid call to getResolvableConfiguration(\"foo\"")
  }

  class InvalidResolvableConfigurationCallback : ApplicationComponentCallback {

    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants { variant ->
        val foo = variant.getResolvableConfiguration("foo")
      }
    }
  }
}

abstract class PrintInputFilesTask : DefaultTask() {

  @get:Input abstract val componentName: Property<String>

  @get:InputFiles abstract val inputFiles: ConfigurableFileCollection

  @TaskAction
  fun taskAction() {
    print("${componentName.get()}: ${inputFiles.files.map { it.name }.sorted().joinToString()};")
  }
}
