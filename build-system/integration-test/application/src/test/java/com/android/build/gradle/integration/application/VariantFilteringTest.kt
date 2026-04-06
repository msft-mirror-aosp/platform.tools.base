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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/** Tests to validate the different filtering mechanisms */
class VariantFilteringTest {

  @get:Rule
  val rule =
    GradleRule.configure().disableBrokenNewDslOptOutChecks().from {
      gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
      androidApplication(":app") { android { namespace = "com.android.tests.basic" } }
    }

  @Test
  fun `filtering via old api on abi and flavor names`() {
    rule.build {
      androidApplication(":app") {
        android {
          flavorDimensions += listOf("abi", "api")
          productFlavors {
            create("x86") { it.dimension = "abi" }
            create("mips") { it.dimension = "abi" }
            create("arm") { it.dimension = "abi" }
            create("cupcake") { it.dimension = "api" }
            create("gingerbread") { it.dimension = "api" }
          }
        }
        pluginCallbacks += AbiAndFlavorFilteringCallback::class.java
      }
    }

    val androidProject = getApplication().androidProject!!
    val actual =
      androidProject.variants.map {
        VariantInfo(
          it.name,
          unitTest = it.unitTestArtifact != null,
          androidTest = it.androidTestArtifact != null,
          testFixtures = it.testFixturesArtifact != null,
        )
      }

    val expected =
      listOf(
        VariantInfo("x86GingerbreadDebug"),
        VariantInfo("x86GingerbreadRelease", unitTest = false, androidTest = false),
        VariantInfo("mipsGingerbreadDebug"),
        VariantInfo("mipsGingerbreadRelease", unitTest = false, androidTest = false),
        VariantInfo("armGingerbreadDebug"),
        VariantInfo("armGingerbreadRelease", unitTest = false, androidTest = false),
        VariantInfo("armCupcakeDebug"),
        VariantInfo("armCupcakeRelease", unitTest = false, androidTest = false),
      )

    Truth.assertThat(actual).containsExactlyElementsIn(expected)
  }

  class AbiAndFlavorFilteringCallback : LegacyApplicationCallback {
    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
      extension.variantFilter {
        val abi = it.flavors[0].name
        if (it.flavors[1].name == "cupcake" && (abi == "x86" || abi == "mips")) {
          it.ignore = true
        }
      }
    }
  }

  @Test
  fun `filtering via old api on build type names`() {
    rule.build { androidApplication(":app") { pluginCallbacks += BuildTypeFilteringCallback::class.java } }

    val androidProject = getApplication().androidProject!!
    val actual =
      androidProject.variants.map {
        VariantInfo(
          it.name,
          unitTest = it.unitTestArtifact != null,
          androidTest = it.androidTestArtifact != null,
          testFixtures = it.testFixturesArtifact != null,
        )
      }

    val expected = listOf(VariantInfo("release", unitTest = false, androidTest = false))

    Truth.assertThat(actual).containsExactlyElementsIn(expected)
  }

  class BuildTypeFilteringCallback : LegacyApplicationCallback {
    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
      extension.variantFilter {
        if (it.buildType.name == "debug") {
          it.ignore = true
        }
      }
    }
  }

  data class VariantInfo(val name: String, val unitTest: Boolean = true, val androidTest: Boolean = true, val testFixtures: Boolean = false)

  private fun getApplication() =
    rule.build.modelBuilder.allowOptionWarning(BooleanOption.USE_NEW_DSL).fetchModels().container.getProject(":app")
}
