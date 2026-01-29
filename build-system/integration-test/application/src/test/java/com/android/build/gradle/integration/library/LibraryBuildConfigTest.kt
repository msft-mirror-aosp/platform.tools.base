/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.integration.library

import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyLibraryCallback
import com.android.build.gradle.integration.common.utils.getBuildType
import com.android.build.gradle.integration.common.utils.getProductFlavor
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.dsl.ClassField
import com.android.builder.model.v2.models.AndroidDsl
import com.android.testutils.truth.PathSubject
import com.google.common.truth.Truth.assertAbout
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import org.gradle.api.Project
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Test for BuildConfig field declared in build type, flavors, and variant and how they override each other.
 *
 * Forked from [com.android.build.gradle.integration.application.BuildConfigTest].
 */
class LibraryBuildConfigTest {

  @get:Rule
  val project =
    GradleRule.configure().disableBrokenNewDslOptOutChecks().from {
      androidLibrary {
        android {
          defaultConfig {
            buildConfigField("int", "VALUE_DEFAULT", "1")
            buildConfigField("int", "VALUE_DEBUG", "1")
            buildConfigField("int", "VALUE_FLAVOR", "1")
            buildConfigField("int", "VALUE_VARIANT", "1")
          }
          buildTypes {
            named("debug") {
              it.buildConfigField("int", "VALUE_DEBUG", "100")
              it.buildConfigField("int", "VALUE_VARIANT", "100")
            }
          }
          flavorDimensions += "foo"
          productFlavors {
            create("flavor1") {
              it.buildConfigField("int", "VALUE_DEBUG", "10")
              it.buildConfigField("int", "VALUE_FLAVOR", "10")
              it.buildConfigField("int", "VALUE_VARIANT", "10")
            }
            create("flavor2") {
              it.buildConfigField("int", "VALUE_DEBUG", "20")
              it.buildConfigField("int", "VALUE_FLAVOR", "20")
              it.buildConfigField("int", "VALUE_VARIANT", "20")
            }
          }
          buildFeatures { buildConfig = true }
        }
        pluginCallbacks += Callback::class.java
      }
      gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
    }

  class Callback : LegacyLibraryCallback {
    override fun handleExtension(project: Project, extension: LibraryExtension) {
      extension.libraryVariants.all { variant ->
        if (variant.buildType.name == "debug") {
          variant.buildConfigField("int", "VALUE_VARIANT", "1000")
        }
      }
    }
  }

  var _dslModel: AndroidDsl? = null
  val dslModel: AndroidDsl
    get() = _dslModel ?: throw AssertionError("dslModel unexpectedly null")

  var _build: GradleBuild? = null
  val build: GradleBuild
    get() = _build ?: throw AssertionError("build unexpectedly null")

  @Before
  fun setup() {
    _build =
      project.build.also {
        it.executor.run(
          "clean",
          "generateFlavor1DebugBuildConfig",
          "generateFlavor1ReleaseBuildConfig",
          "generateFlavor2DebugBuildConfig",
          "generateFlavor2ReleaseBuildConfig",
        )
        _dslModel = it.modelBuilder.allowOptionWarning(BooleanOption.USE_NEW_DSL).fetchModels().container.getProject().androidDsl
      }
  }

  @Test
  fun testFlavor1Debug() {
    val expected =
      """
      /**
       * Automatically generated file. DO NOT MODIFY
       */
      package pkg.name.lib;

      public final class BuildConfig {
        public static final boolean DEBUG = Boolean.parseBoolean("true");
        public static final String LIBRARY_PACKAGE_NAME = "pkg.name.lib";
        public static final String BUILD_TYPE = "debug";
        public static final String FLAVOR = "flavor1";
        // Field from build type: debug
        public static final int VALUE_DEBUG = 100;
        // Field from default config.
        public static final int VALUE_DEFAULT = 1;
        // Field from product flavor: flavor1
        public static final int VALUE_FLAVOR = 10;
        // Field from the variant API
        public static final int VALUE_VARIANT = 1000;
      }
      """
        .trimIndent()
    checkGeneratedFile("flavor1/debug", expected)
  }

  @Test
  fun modelDefaultConfig() {
    val map = mapOf("VALUE_DEFAULT" to "1", "VALUE_FLAVOR" to "1", "VALUE_DEBUG" to "1", "VALUE_VARIANT" to "1")
    checkMaps(map, dslModel.defaultConfig.buildConfigFields!!, "defaultConfig")
  }

  @Test
  fun modelFlavor1() {
    val map = mapOf("VALUE_FLAVOR" to "10", "VALUE_DEBUG" to "10", "VALUE_VARIANT" to "10")
    checkProductFlavor(dslModel, "flavor1", map)
  }

  @Test
  @Throws(IOException::class)
  fun buildFlavor2Debug() {
    val expected =
      """
      /**
       * Automatically generated file. DO NOT MODIFY
       */
      package pkg.name.lib;

      public final class BuildConfig {
        public static final boolean DEBUG = Boolean.parseBoolean("true");
        public static final String LIBRARY_PACKAGE_NAME = "pkg.name.lib";
        public static final String BUILD_TYPE = "debug";
        public static final String FLAVOR = "flavor2";
        // Field from build type: debug
        public static final int VALUE_DEBUG = 100;
        // Field from default config.
        public static final int VALUE_DEFAULT = 1;
        // Field from product flavor: flavor2
        public static final int VALUE_FLAVOR = 20;
        // Field from the variant API
        public static final int VALUE_VARIANT = 1000;
      }
      """
        .trimIndent()
    checkGeneratedFile("flavor2/debug", expected)
  }

  @Test
  fun modelFlavor2() {
    val map = mapOf("VALUE_FLAVOR" to "20", "VALUE_DEBUG" to "20", "VALUE_VARIANT" to "20")
    checkProductFlavor(dslModel, "flavor2", map)
  }

  @Test
  fun buildFlavor1Release() {
    val expected =
      """
      /**
       * Automatically generated file. DO NOT MODIFY
       */
      package pkg.name.lib;

      public final class BuildConfig {
        public static final boolean DEBUG = false;
        public static final String LIBRARY_PACKAGE_NAME = "pkg.name.lib";
        public static final String BUILD_TYPE = "release";
        public static final String FLAVOR = "flavor1";
        // Field from product flavor: flavor1
        public static final int VALUE_DEBUG = 10;
        // Field from default config.
        public static final int VALUE_DEFAULT = 1;
        // Field from product flavor: flavor1
        public static final int VALUE_FLAVOR = 10;
        // Field from product flavor: flavor1
        public static final int VALUE_VARIANT = 10;
      }
      """
        .trimIndent()
    checkGeneratedFile("flavor1/release", expected)
  }

  @Test
  fun modelDebug() {
    val map = mapOf("VALUE_DEBUG" to "100", "VALUE_VARIANT" to "100")
    checkBuildType(dslModel, "debug", map)
  }

  @Test
  fun buildFlavor2Release() {
    val expected =
      """
      /**
       * Automatically generated file. DO NOT MODIFY
       */
      package pkg.name.lib;

      public final class BuildConfig {
        public static final boolean DEBUG = false;
        public static final String LIBRARY_PACKAGE_NAME = "pkg.name.lib";
        public static final String BUILD_TYPE = "release";
        public static final String FLAVOR = "flavor2";
        // Field from product flavor: flavor2
        public static final int VALUE_DEBUG = 20;
        // Field from default config.
        public static final int VALUE_DEFAULT = 1;
        // Field from product flavor: flavor2
        public static final int VALUE_FLAVOR = 20;
        // Field from product flavor: flavor2
        public static final int VALUE_VARIANT = 20;
      }
      """
        .trimIndent()
    checkGeneratedFile("flavor2/release", expected)
  }

  @Test
  fun modelRelease() {
    checkBuildType(dslModel, "release", emptyMap())
  }

  private fun checkBuildType(dsl: AndroidDsl, name: String, valueMap: Map<String, String>) {
    val buildType = dsl.getBuildType(name)
    checkMaps(valueMap, buildType.buildConfigFields!!, name)
  }

  private fun checkProductFlavor(dsl: AndroidDsl, name: String, valueMap: Map<String, String>) {
    val productFlavor = dsl.getProductFlavor(name)
    checkMaps(valueMap, productFlavor.buildConfigFields!!, name)
  }

  private fun checkMaps(valueMap: Map<String, String>, value: Map<String, ClassField>, name: String) {
    assertThat(value.keys).isEqualTo(valueMap.keys)
    for (key in valueMap.keys) {
      val field = value[key]!!
      assertThat(field.value).named("$name: check value of $key").isEqualTo(valueMap[key])
    }
  }

  private fun checkGeneratedFile(variantDir: String, expected: String) {
    val output =
      build
        .subProject(":lib")
        .buildDir
        .resolve("generated/source/buildConfig/")
        .resolve(variantDir)
        .resolve("pkg/name/lib/BuildConfig.java")
    assertAbout(PathSubject.paths()).that(output).exists()
    assertAbout(PathSubject.paths()).that(output).contentWithUnixLineSeparatorsIsExactly(expected)
  }
}
