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

package com.android.build.gradle.internal.plugins

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.internal.fixture.TestConstants
import com.android.build.gradle.internal.fixture.TestProjects
import com.android.build.gradle.internal.packaging.defaultExcludes
import com.android.build.gradle.internal.packaging.defaultMerges
import com.android.build.gradle.internal.utils.importOfflineMavenRepo
import com.android.build.gradle.options.BooleanOption
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.test.assertFailsWith
import org.gradle.api.Project
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for source compatibility with deprecated methods in Kotlin DSL. */
class KotlinDslCompatibilityTest {

  @get:Rule val projectDirectory = TemporaryFolder()

  private lateinit var plugin: AppPlugin
  private lateinit var android: ApplicationExtension
  private lateinit var project: Project

  init {
    importOfflineMavenRepo()
  }

  @Before
  fun setUp() {
    project =
      TestProjects.builder(projectDirectory.newFolder("project").toPath())
        .withPlugin(TestProjects.Plugin.APP)
        .withProperty(BooleanOption.USE_NEW_DSL, false)
        .build()

    initFieldsFromProject()
  }

  private fun initFieldsFromProject() {
    android = project.extensions.getByType(ApplicationExtension::class.java)
    android.compileSdk = TestConstants.COMPILE_SDK_VERSION
    android.namespace = "com.example.namespace"
    plugin = project.plugins.getPlugin(AppPlugin::class.java)
  }

  private inline fun attempt(action: () -> Unit): String? {
    return try {
      action()
      null
    } catch (t: Throwable) {
      t.message
    }
  }

  @Test
  fun `test compileSdkVersion compatibility`() {
    android.compileSdkVersion(29)
    assertThat(android.compileSdk).isEqualTo(29)

    android.compileSdkVersion("android-31")
    assertThat(android.compileSdk).isEqualTo(31)

    android.compileSdkVersion("android-S")
    assertThat(android.compileSdkPreview).isEqualTo("S")

    assertThat(attempt { android.compileSdkVersion("MadeUp") })
      .isEqualTo(
        """
        Unsupported value: MadeUp. Format must be one of:
        - android-31
        - android-36.2
        - android-31-ext2
        - android-36.2-ext2
        - android-canary-20250617
        - android-36.0-beta1
        - android-T
        - vendorName:addonName:31
        """
          .trimIndent()
      )
  }

  @Test
  fun `baseFlavor source compatibility`() {
    android.defaultConfig {
      setTestFunctionalTest(true)
      assertThat(testFunctionalTest).isTrue()
      setTestHandleProfiling(true)
      assertThat(testHandleProfiling).isTrue()
      resConfig("one")
      resConfigs("two", "three")
      resConfigs(listOf("four"))
      assertThat(resourceConfigurations).containsExactly("one", "two", "three", "four")
      assertFailsWith<Exception> { resConfigs("") }
    }
  }

  @Ignore("Disabled because it fails with ClassCastException in unit test setup. It was previously skipped due to assume() on USE_NEW_DSL.")
  @Test
  fun `mergedFlavor source compatibility`() {
    val applicationVariants = (android as BaseAppModuleExtension).applicationVariants
    val fileF = File("f")
    val fileG = File("g")
    val fileH = File("h")
    applicationVariants.all { variant ->
      variant.mergedFlavor.manifestPlaceholders += mapOf("a" to "b")
      variant.mergedFlavor.testInstrumentationRunnerArguments += mapOf("c" to "d")
      variant.mergedFlavor.resourceConfigurations += "e"
      variant.mergedFlavor.proguardFiles += fileF
      variant.mergedFlavor.consumerProguardFiles += fileG
      variant.mergedFlavor.testProguardFiles += fileH
    }
    plugin.createAndroidTasks(project)
    assertThat(applicationVariants).hasSize(2)
    applicationVariants.first().also { variant ->
      assertThat(variant.mergedFlavor.manifestPlaceholders).containsExactly("a", "b")
      assertThat(variant.mergedFlavor.testInstrumentationRunnerArguments).containsExactly("c", "d")
      assertThat(variant.mergedFlavor.resourceConfigurations).containsExactly("e")
      assertThat(variant.mergedFlavor.proguardFiles).containsExactly(fileF)
      assertThat(variant.mergedFlavor.consumerProguardFiles).containsExactly(fileG)
      assertThat(variant.mergedFlavor.testProguardFiles).containsExactly(fileH)
    }
  }

  @Test
  fun `testInstrumentationRunnerArguments source compatibility`() {
    android.defaultConfig.testInstrumentationRunnerArguments.put("a", "b")
    assertThat(android.defaultConfig.testInstrumentationRunnerArguments).containsExactly("a", "b")

    android.defaultConfig.testInstrumentationRunnerArguments += "c" to "d"
    assertThat(android.defaultConfig.testInstrumentationRunnerArguments).containsExactly("a", "b", "c", "d")

    android.defaultConfig.setTestInstrumentationRunnerArguments(mutableMapOf("x" to "y"))
    assertThat(android.defaultConfig.testInstrumentationRunnerArguments).containsExactly("x", "y")
  }

  @Test
  fun `LintOptions source compatibility`() {
    android.lintOptions {
      enable += "a"
      assertThat(enable).containsExactly("a")
      disable += "b"
      assertThat(disable).containsExactly("b")
      checkOnly += "c"
      assertThat(checkOnly).containsExactly("c")
    }
  }

  @Test
  fun `matchingFallbacks source compatibility`() {
    android.productFlavors.create("example").apply {
      matchingFallbacks += "a"
      matchingFallbacks.add("b")
      assertThat(matchingFallbacks).containsExactly("a", "b")
      setMatchingFallbacks("c")
      assertThat(matchingFallbacks).containsExactly("c")
      setMatchingFallbacks("d", "e")
      assertThat(matchingFallbacks).containsExactly("d", "e")
      setMatchingFallbacks(ImmutableList.of("f"))
      assertThat(matchingFallbacks).containsExactly("f")
      setMatchingFallbacks(matchingFallbacks)
      assertThat(matchingFallbacks).containsExactly("f")
    }
    android.buildTypes.create("qa").apply {
      matchingFallbacks += "a"
      matchingFallbacks.add("b")
      assertThat(matchingFallbacks).containsExactly("a", "b")
      setMatchingFallbacks("c")
      assertThat(matchingFallbacks).containsExactly("c")
      setMatchingFallbacks("d", "e")
      assertThat(matchingFallbacks).containsExactly("d", "e")
      setMatchingFallbacks(ImmutableList.of("f"))
      assertThat(matchingFallbacks).containsExactly("f")
      setMatchingFallbacks(matchingFallbacks)
      assertThat(matchingFallbacks).containsExactly("f")
    }
  }

  @Test
  fun `java resource packaging options`() {
    android.packagingOptions {
      resources {
        excludes += "a"
        assertThat(excludes).containsExactlyElementsIn(defaultExcludes.plus("a"))
        pickFirsts += "b"
        assertThat(pickFirsts).containsExactly("b")
        merges += "c"
        assertThat(merges).containsExactlyElementsIn(defaultMerges.plus("c"))
      }
    }
  }

  @Test
  fun `native libs packaging options`() {
    android.packagingOptions {
      jniLibs {
        excludes += "a"
        assertThat(excludes).containsExactly("a")
        pickFirsts += "b"
        assertThat(pickFirsts).containsExactly("b")
        keepDebugSymbols += "c"
        assertThat(keepDebugSymbols).containsExactly("c")
      }
    }
  }

  @Test
  fun `compatibility for compile sdk`() {
    android.apply {
      compileSdkVersion(TestConstants.COMPILE_SDK_VERSION)
      compileSdkVersion("android-${TestConstants.COMPILE_SDK_VERSION}")
    }
  }
}
