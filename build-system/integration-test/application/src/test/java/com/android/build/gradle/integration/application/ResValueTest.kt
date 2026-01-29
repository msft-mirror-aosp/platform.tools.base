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

class ResValueTest {

  @get:Rule
  val rule =
    GradleRule.configure().disableBrokenNewDslOptOutChecks().from {
      androidApplication {
        android {
          defaultConfig {
            resValue("string", "VALUE_DEFAULT", "1")
            resValue("string", "VALUE_DEBUG", "1")
            resValue("string", "VALUE_FLAVOR", "1")
            resValue("string", "VALUE_VARIANT", "1")
          }
          buildTypes {
            named("debug") {
              it.resValue("string", "VALUE_DEBUG", "100")
              it.resValue("string", "VALUE_VARIANT", "100")
            }
          }
          flavorDimensions += "foo"
          productFlavors {
            create("flavor1") {
              it.resValue("string", "VALUE_DEBUG", "10")
              it.resValue("string", "VALUE_FLAVOR", "10")
              it.resValue("string", "VALUE_VARIANT", "10")
            }
            create("flavor2") {
              it.resValue("string", "VALUE_DEBUG", "20")
              it.resValue("string", "VALUE_FLAVOR", "20")
              it.resValue("string", "VALUE_VARIANT", "20")
            }
          }
          buildFeatures { resValues = true }
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
                      variant.resValue("string", "VALUE_VARIANT", "1000")
                  }
              }
              """
                .trimIndent()
            )
          }
        }
        it.executor.run(
          "clean",
          "generateFlavor1DebugResValue",
          "generateFlavor1ReleaseResValue",
          "generateFlavor2DebugResValue",
          "generateFlavor2ReleaseResValue",
        )

        _dslModel = it.modelBuilder.allowOptionWarning(BooleanOption.USE_NEW_DSL).fetchModels().container.getProject().androidDsl
      }
  }

  @After
  fun teardown() {
    _build = null
    _dslModel = null
  }

  @Test
  fun modelDefaultConfig() {
    val map = mapOf("string/VALUE_DEFAULT" to "1", "string/VALUE_FLAVOR" to "1", "string/VALUE_DEBUG" to "1", "string/VALUE_VARIANT" to "1")
    checkMaps(map, dslModel.defaultConfig.resValues!!, "defaultConfig")
  }

  @Test
  fun modelFlavor1() {
    val map = mapOf("string/VALUE_FLAVOR" to "10", "string/VALUE_DEBUG" to "10", "string/VALUE_VARIANT" to "10")
    checkProductFlavor(dslModel, "flavor1", map)
  }

  @Test
  fun modelFlavor2() {
    val map = mapOf("string/VALUE_FLAVOR" to "20", "string/VALUE_DEBUG" to "20", "string/VALUE_VARIANT" to "20")
    checkProductFlavor(dslModel, "flavor2", map)
  }

  @Test
  fun modelRelease() {
    checkBuildType(dslModel, "release", emptyMap())
  }

  @Test
  fun modelDebug() {
    checkBuildType(dslModel, "debug", mapOf("string/VALUE_DEBUG" to "100", "string/VALUE_VARIANT" to "100"))
  }

  @Test
  fun buildFlavor1Debug() {
    checkGeneratedFile(
      "flavor1/debug",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <resources>

          <!-- Automatically generated file. DO NOT MODIFY -->

          <!-- Value from build type: debug -->
          <string name="VALUE_DEBUG" translatable="false">100</string>
          <!-- Value from the variant -->
          <string name="VALUE_VARIANT" translatable="false">1000</string>
          <!-- Value from product flavor: flavor1 -->
          <string name="VALUE_FLAVOR" translatable="false">10</string>
          <!-- Value from default config. -->
          <string name="VALUE_DEFAULT" translatable="false">1</string>

      </resources>
      """
        .trimIndent(),
    )
  }

  @Test
  fun buildFlavor1Release() {
    checkGeneratedFile(
      "flavor1/release",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <resources>

          <!-- Automatically generated file. DO NOT MODIFY -->

          <!-- Value from product flavor: flavor1 -->
          <string name="VALUE_DEBUG" translatable="false">10</string>
          <!-- Value from product flavor: flavor1 -->
          <string name="VALUE_FLAVOR" translatable="false">10</string>
          <!-- Value from product flavor: flavor1 -->
          <string name="VALUE_VARIANT" translatable="false">10</string>
          <!-- Value from default config. -->
          <string name="VALUE_DEFAULT" translatable="false">1</string>

      </resources>
      """
        .trimIndent(),
    )
  }

  @Test
  fun buildFlavor2Debug() {
    checkGeneratedFile(
      "flavor2/debug",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <resources>

          <!-- Automatically generated file. DO NOT MODIFY -->

          <!-- Value from build type: debug -->
          <string name="VALUE_DEBUG" translatable="false">100</string>
          <!-- Value from the variant -->
          <string name="VALUE_VARIANT" translatable="false">1000</string>
          <!-- Value from product flavor: flavor2 -->
          <string name="VALUE_FLAVOR" translatable="false">20</string>
          <!-- Value from default config. -->
          <string name="VALUE_DEFAULT" translatable="false">1</string>

      </resources>
      """
        .trimIndent(),
    )
  }

  @Test
  fun buildFlavor2Release() {
    checkGeneratedFile(
      "flavor2/release",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <resources>

          <!-- Automatically generated file. DO NOT MODIFY -->

          <!-- Value from product flavor: flavor2 -->
          <string name="VALUE_DEBUG" translatable="false">20</string>
          <!-- Value from product flavor: flavor2 -->
          <string name="VALUE_FLAVOR" translatable="false">20</string>
          <!-- Value from product flavor: flavor2 -->
          <string name="VALUE_VARIANT" translatable="false">20</string>
          <!-- Value from default config. -->
          <string name="VALUE_DEFAULT" translatable="false">1</string>

      </resources>
      """
        .trimIndent(),
    )
  }

  private fun checkBuildType(dsl: AndroidDsl, name: String, valueMap: Map<String, String>) {
    val buildType = dsl.getBuildType(name)
    checkMaps(valueMap, buildType.resValues!!, name)
  }

  private fun checkProductFlavor(dsl: AndroidDsl, name: String, valueMap: Map<String, String>) {
    val productFlavor = dsl.getProductFlavor(name)
    checkMaps(valueMap, productFlavor.resValues!!, name)
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
      build.subProject(":app").buildDir.resolve("generated/res/resValues/").resolve(variantDir).resolve("values/gradleResValues.xml")
    assertAbout(PathSubject.paths()).that(output).exists()
    assertAbout(PathSubject.paths()).that(output).contentWithUnixLineSeparatorsIsExactly(expected)
  }
}
