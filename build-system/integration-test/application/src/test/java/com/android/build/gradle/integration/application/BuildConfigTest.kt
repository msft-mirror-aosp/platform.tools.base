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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.utils.getBuildType
import com.android.build.gradle.integration.common.utils.getProductFlavor
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.dsl.ClassField
import com.android.builder.model.v2.models.AndroidDsl
import com.android.testutils.truth.PathSubject
import com.google.common.truth.Truth.assertAbout
import com.google.common.truth.Truth.assertThat
import java.lang.AssertionError
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BuildConfigTest {
  @get:Rule
  val rule =
    GradleRule.configure().disableBrokenNewDslOptOutChecks().from {
      androidApplication {
        android {
          defaultConfig {
            buildConfigField("int", "VALUE_DEFAULT", "1")
            buildConfigField("int", "VALUE_DEBUG", "1")
            buildConfigField("java.util.OptionalInt", "VALUE_EXPRESSION", "java.util.OptionalInt.empty()")
            buildConfigField("String[]", "VALUE_STRING_ARRAY", "new String[]{\"hello\", \"world\"}")
            buildConfigField("String[]", "CALCULATED_STRING", "String.format(\"VALUE_DEFAULT=%1\$d\", VALUE_DEFAULT)")
            buildConfigField("long", "VALUE_LONG", "50L")
            buildConfigField("int", "VALUE_FLAVOR", "1")
            buildConfigField("float", "VALUE_FLOAT", "5f")
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
      }
      gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
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
      rule.build.also {
        it.androidApplication(":app").apply {
          files.update("build.gradle") {
            append(
              """

              android.applicationVariants.all { variant ->
                  if (variant.buildType.name == "debug") {
                      variant.buildConfigField("int", "VALUE_VARIANT", "1000")
                  }
              }
              """
                .trimIndent()
            )
          }
        }
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

  @After
  fun tearDown() {
    _build = null
    _dslModel = null
  }

  @Test
  fun modelDefaultConfig() {
    val map =
      mapOf(
        "VALUE_DEBUG" to "1",
        "VALUE_DEFAULT" to "1",
        "VALUE_EXPRESSION" to "java.util.OptionalInt.empty()",
        "CALCULATED_STRING" to "String.format(\"VALUE_DEFAULT=%1\$d\", VALUE_DEFAULT)",
        "VALUE_STRING_ARRAY" to "new String[]{\"hello\", \"world\"}",
        "VALUE_FLAVOR" to "1",
        "VALUE_FLOAT" to "5f",
        "VALUE_LONG" to "50L",
        "VALUE_VARIANT" to "1",
      )
    checkMaps(map, dslModel.defaultConfig.buildConfigFields!!, "defaultConfig")
  }

  @Test
  fun modelFlavor1() {
    checkProductFlavor(dslModel, "flavor1", mapOf("VALUE_FLAVOR" to "10", "VALUE_DEBUG" to "10", "VALUE_VARIANT" to "10"))
  }

  @Test
  fun modelFlavor2() {
    checkProductFlavor(dslModel, "flavor2", mapOf("VALUE_FLAVOR" to "20", "VALUE_DEBUG" to "20", "VALUE_VARIANT" to "20"))
  }

  @Test
  fun modelRelease() {
    checkBuildType(dslModel, "release", emptyMap())
  }

  @Test
  fun modelDebug() {
    checkBuildType(dslModel, "debug", mapOf("VALUE_DEBUG" to "100", "VALUE_VARIANT" to "100"))
  }

  @Test
  fun buildFlavor1Debug() {
    val expected =
      """
      /**
       * Automatically generated file. DO NOT MODIFY
       */
      package pkg.name.app;

      public final class BuildConfig {
        public static final boolean DEBUG = Boolean.parseBoolean("true");
        public static final String APPLICATION_ID = "pkg.name.app";
        public static final String BUILD_TYPE = "debug";
        public static final String FLAVOR = "flavor1";
        public static final int VERSION_CODE = -1;
        public static final String VERSION_NAME = "";
        // Field from default config.
        public static final String[] CALCULATED_STRING = String.format("VALUE_DEFAULT=%1${'$'}d", VALUE_DEFAULT);
        // Field from build type: debug
        public static final int VALUE_DEBUG = 100;
        // Field from default config.
        public static final int VALUE_DEFAULT = 1;
        // Field from default config.
        public static final java.util.OptionalInt VALUE_EXPRESSION = java.util.OptionalInt.empty();
        // Field from product flavor: flavor1
        public static final int VALUE_FLAVOR = 10;
        // Field from default config.
        public static final float VALUE_FLOAT = 5f;
        // Field from default config.
        public static final long VALUE_LONG = 50L;
        // Field from default config.
        public static final String[] VALUE_STRING_ARRAY = new String[]{"hello", "world"};
        // Field from the variant API
        public static final int VALUE_VARIANT = 1000;
      }
      """
        .trimIndent()
    checkGeneratedFile("flavor1/debug", expected)
  }

  @Test
  fun buildFlavor1Release() {
    val expected =
      """
      /**
       * Automatically generated file. DO NOT MODIFY
       */
      package pkg.name.app;

      public final class BuildConfig {
        public static final boolean DEBUG = false;
        public static final String APPLICATION_ID = "pkg.name.app";
        public static final String BUILD_TYPE = "release";
        public static final String FLAVOR = "flavor1";
        public static final int VERSION_CODE = -1;
        public static final String VERSION_NAME = "";
        // Field from default config.
        public static final String[] CALCULATED_STRING = String.format("VALUE_DEFAULT=%1${'$'}d", VALUE_DEFAULT);
        // Field from product flavor: flavor1
        public static final int VALUE_DEBUG = 10;
        // Field from default config.
        public static final int VALUE_DEFAULT = 1;
        // Field from default config.
        public static final java.util.OptionalInt VALUE_EXPRESSION = java.util.OptionalInt.empty();
        // Field from product flavor: flavor1
        public static final int VALUE_FLAVOR = 10;
        // Field from default config.
        public static final float VALUE_FLOAT = 5f;
        // Field from default config.
        public static final long VALUE_LONG = 50L;
        // Field from default config.
        public static final String[] VALUE_STRING_ARRAY = new String[]{"hello", "world"};
        // Field from product flavor: flavor1
        public static final int VALUE_VARIANT = 10;
      }
      """
        .trimIndent()
    checkGeneratedFile("flavor1/release", expected)
  }

  @Test
  fun buildFlavor2Debug() {
    val expected =
      """
      /**
       * Automatically generated file. DO NOT MODIFY
       */
      package pkg.name.app;

      public final class BuildConfig {
        public static final boolean DEBUG = Boolean.parseBoolean("true");
        public static final String APPLICATION_ID = "pkg.name.app";
        public static final String BUILD_TYPE = "debug";
        public static final String FLAVOR = "flavor2";
        public static final int VERSION_CODE = -1;
        public static final String VERSION_NAME = "";
        // Field from default config.
        public static final String[] CALCULATED_STRING = String.format("VALUE_DEFAULT=%1${'$'}d", VALUE_DEFAULT);
        // Field from build type: debug
        public static final int VALUE_DEBUG = 100;
        // Field from default config.
        public static final int VALUE_DEFAULT = 1;
        // Field from default config.
        public static final java.util.OptionalInt VALUE_EXPRESSION = java.util.OptionalInt.empty();
        // Field from product flavor: flavor2
        public static final int VALUE_FLAVOR = 20;
        // Field from default config.
        public static final float VALUE_FLOAT = 5f;
        // Field from default config.
        public static final long VALUE_LONG = 50L;
        // Field from default config.
        public static final String[] VALUE_STRING_ARRAY = new String[]{"hello", "world"};
        // Field from the variant API
        public static final int VALUE_VARIANT = 1000;
      }
      """
        .trimIndent()
    checkGeneratedFile("flavor2/debug", expected)
  }

  @Test
  fun buildFlavor2Release() {
    val expected =
      """
      /**
       * Automatically generated file. DO NOT MODIFY
       */
      package pkg.name.app;

      public final class BuildConfig {
        public static final boolean DEBUG = false;
        public static final String APPLICATION_ID = "pkg.name.app";
        public static final String BUILD_TYPE = "release";
        public static final String FLAVOR = "flavor2";
        public static final int VERSION_CODE = -1;
        public static final String VERSION_NAME = "";
        // Field from default config.
        public static final String[] CALCULATED_STRING = String.format("VALUE_DEFAULT=%1${'$'}d", VALUE_DEFAULT);
        // Field from product flavor: flavor2
        public static final int VALUE_DEBUG = 20;
        // Field from default config.
        public static final int VALUE_DEFAULT = 1;
        // Field from default config.
        public static final java.util.OptionalInt VALUE_EXPRESSION = java.util.OptionalInt.empty();
        // Field from product flavor: flavor2
        public static final int VALUE_FLAVOR = 20;
        // Field from default config.
        public static final float VALUE_FLOAT = 5f;
        // Field from default config.
        public static final long VALUE_LONG = 50L;
        // Field from default config.
        public static final String[] VALUE_STRING_ARRAY = new String[]{"hello", "world"};
        // Field from product flavor: flavor2
        public static final int VALUE_VARIANT = 20;
      }
      """
        .trimIndent()
    checkGeneratedFile("flavor2/release", expected)
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
        .subProject(":app")
        .buildDir
        .resolve("generated/source/buildConfig/")
        .resolve(variantDir)
        .resolve("pkg/name/app/BuildConfig.java")
    assertAbout(PathSubject.paths()).that(output).exists()
    assertAbout(PathSubject.paths()).that(output).contentWithUnixLineSeparatorsIsExactly(expected)
  }
}
