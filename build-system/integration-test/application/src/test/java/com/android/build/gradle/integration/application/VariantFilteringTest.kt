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
package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.AbstractReturnGivenBuildResultTest
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

/** Tests to validate the different filtering mechanisms */
class VariantFilteringTest :
  AbstractReturnGivenBuildResultTest<String, VariantFilteringTest.VariantBuilder, List<VariantFilteringTest.VariantInfo>>() {

  @get:Rule
  val project = GradleTestProject.builder().fromTestProject("emptyApp").addGradleProperty(BooleanOption.USE_NEW_DSL, false).create()

  @Test
  fun `filtering via old api on abi and flavor names`() {
    given {
      """
                |    flavorDimensions "abi", "api"
                |    productFlavors {
                |        x86 {
                |            dimension "abi"
                |        }
                |        mips {
                |            dimension "abi"
                |        }
                |        arm {
                |            dimension "abi"
                |        }
                |        cupcake {
                |            dimension "api"
                |        }
                |        gingerbread {
                |            dimension "api"
                |        }
                |    }
                |    variantFilter {
                |        String abi = it.flavors.get(0).name
                |        if ("cupcake".equals(it.flavors.get(1).name) && ("x86".equals(abi) || "mips".equals(abi))) {
                |            it.ignore = true
                |        }
                |    }
            """
    }

    expect {
      variant { name = "x86GingerbreadDebug" }
      variant {
        name = "x86GingerbreadRelease"
        unitTest = false
        androidTest = false
      }
      variant { name = "mipsGingerbreadDebug" }
      variant {
        name = "mipsGingerbreadRelease"
        unitTest = false
        androidTest = false
      }
      variant { name = "armGingerbreadDebug" }
      variant {
        name = "armGingerbreadRelease"
        unitTest = false
        androidTest = false
      }

      variant { name = "armCupcakeDebug" }
      variant {
        name = "armCupcakeRelease"
        unitTest = false
        androidTest = false
      }
    }
  }

  @Test
  fun `filtering via old api on build type names`() {
    given {
      """
                |    variantFilter {
                |        if (it.buildType.name.equals("debug")) {
                |            it.ignore = true
                |        }
                |    }
            """
    }

    expect {
      variant {
        name = "release"
        unitTest = false
        androidTest = false
      }
    }
  }

  // ---------------------------------------------------------------------------------------------

  var androidComponentsBlock: (() -> String)? = null

  fun withAndroidComponents(action: () -> String) {
    androidComponentsBlock = action
    state = TestState.GIVEN
  }

  override fun noGivenData(): String {
    // it's ok to not have any given data, if there is some androidComponents customization.
    if (androidComponentsBlock != null) return "" else throw RuntimeException("No given data")
  }

  override fun defaultWhen(given: String): List<VariantInfo>? {
    project.buildFile.appendText(
      """
                |android {
                |${given.trimMargin()}
                |}
            """
        .trimMargin()
    )
    this.androidComponentsBlock?.let {
      project.buildFile.appendText(
        """
                |
                |androidComponents {
                |${it().trimMargin()}
                |}
            """
          .trimMargin()
      )
    }

    return project
      .modelV2()
      .allowOptionWarning(BooleanOption.USE_NEW_DSL)
      .fetchModels()
      .container
      .getProject()
      .androidProject!!
      .variants
      .map {
        VariantInfo(
          it.name,
          unitTest = it.unitTestArtifact != null,
          androidTest = it.androidTestArtifact != null,
          testFixtures = it.testFixturesArtifact != null,
        )
      }
  }

  override fun compareResult(expected: List<VariantInfo>?, actual: List<VariantInfo>?, given: String) {
    Truth.assertThat(actual).containsExactlyElementsIn(expected)
  }

  override fun instantiateResulBuilder(): VariantBuilder = VariantBuilder()

  class VariantBuilder : ResultBuilder<List<VariantInfo>> {
    private val variants = mutableListOf<VariantInfo>()

    fun variant(action: VariantInfo.() -> Unit) {
      variants.add(VariantInfo().also { action(it) })
    }

    override fun toResult(): List<VariantInfo> {
      return variants
    }
  }

  data class VariantInfo(
    var name: String = "",
    var unitTest: Boolean = true,
    var androidTest: Boolean = true,
    var testFixtures: Boolean = false,
  )
}
