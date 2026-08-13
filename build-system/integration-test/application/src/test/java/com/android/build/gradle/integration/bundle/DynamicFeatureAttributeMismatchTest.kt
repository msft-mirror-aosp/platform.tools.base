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

package com.android.build.gradle.integration.bundle

import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

/** Test verifying execution-time validation of dynamic feature module variant parity. */
class DynamicFeatureAttributeMismatchTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication(DEFAULT_APP_PATH) {
        android {
          defaultConfig { applicationId = "com.example.test" }
          dynamicFeatures.add(DEFAULT_FEATURE_PATH)
          dynamicFeatures.add(":feature2")
        }
      }
      androidFeature(DEFAULT_FEATURE_PATH) {
        android { namespace = "com.example.test.feature" }
        dependencies { implementation(project(DEFAULT_APP_PATH)) }
      }
      androidFeature(":feature2") {
        android { namespace = "com.example.test.feature2" }
        dependencies { implementation(project(DEFAULT_APP_PATH)) }
      }
    }

  /**
   * Configures a single mismatching feature scenario where app defines 'free' (falling back to 'pro') and 'pro', while :feature only
   * defines 'pro'. Used to verify that building 'freeDebug' fails at execution time on :feature, but building 'proDebug' succeeds.
   */
  private fun setupSingleFeatureMismatchFlavors(build: GradleBuild) {
    build
      .androidApplication(DEFAULT_APP_PATH)
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier'
                productFlavors {
                    free {
                        dimension 'tier'
                        matchingFallbacks = ['pro']
                    }
                    pro {
                        dimension 'tier'
                    }
                }
            }
            """
      )
    build
      .androidFeature(DEFAULT_FEATURE_PATH)
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier'
                productFlavors {
                    pro {
                        dimension 'tier'
                    }
                }
            }
            """
      )
    build
      .androidFeature(":feature2")
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier'
                productFlavors {
                    free {
                        dimension 'tier'
                    }
                    pro {
                        dimension 'tier'
                    }
                }
            }
            """
      )
  }

  /**
   * Configures a multi-feature scenario where :feature matches app ('free' and 'pro'), but :feature2 only defines 'pro'. Used to verify
   * that execution-time parity checks properly validate and catch mismatches across multiple dynamic feature modules.
   */
  private fun setupMultiFeatureMismatchFlavors(build: GradleBuild) {
    build
      .androidApplication(DEFAULT_APP_PATH)
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier'
                productFlavors {
                    free {
                        dimension 'tier'
                        matchingFallbacks = ['pro']
                    }
                    pro {
                        dimension 'tier'
                    }
                }
            }
            """
      )
    build
      .androidFeature(DEFAULT_FEATURE_PATH)
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier'
                productFlavors {
                    free {
                        dimension 'tier'
                    }
                    pro {
                        dimension 'tier'
                    }
                }
            }
            """
      )
    build
      .androidFeature(":feature2")
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier'
                productFlavors {
                    pro {
                        dimension 'tier'
                    }
                }
            }
            """
      )
  }

  /**
   * Configures a multi-feature scenario where features define strict subsets of the application's dimensions ('tier', 'env'). :feature
   * defines only 'tier' (with missingDimensionStrategy for 'env'), while :feature2 defines only 'env' (with missingDimensionStrategy for
   * 'tier'). Used to verify that exact 1:1 variant parity is enforced even when missingDimensionStrategy is used.
   */
  private fun setupMultiFeatureSubsetDimensions(build: GradleBuild) {
    build
      .androidApplication(DEFAULT_APP_PATH)
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier', 'env'
                productFlavors {
                    free {
                        dimension 'tier'
                    }
                    pro {
                        dimension 'tier'
                    }
                    staging {
                        dimension 'env'
                    }
                    prod {
                        dimension 'env'
                    }
                }
            }
            """
      )
    build
      .androidFeature(DEFAULT_FEATURE_PATH)
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier'
                defaultConfig {
                    missingDimensionStrategy 'env', 'staging'
                }
                productFlavors {
                    free {
                        dimension 'tier'
                    }
                    pro {
                        dimension 'tier'
                    }
                }
            }
            """
      )
    build
      .androidFeature(":feature2")
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'env'
                defaultConfig {
                    missingDimensionStrategy 'tier', 'free'
                }
                productFlavors {
                    staging {
                        dimension 'env'
                    }
                    prod {
                        dimension 'env'
                    }
                }
            }
            """
      )
  }

  @Test
  fun checkFlavorMismatchLogsWarningByDefault() {
    val build = rule.build
    setupSingleFeatureMismatchFlavors(build)
    val result = build.executor.withFailOnWarning(false).run(":app:assembleFreeDebug")
    result.assertOutputContains(
      "Module :feature has product flavor 'pro' for dimension 'tier' which does not match application product flavor 'free'."
    )
  }

  @Test
  fun checkFlavorMismatchFailsAtExecutionTimeWhenEnforced() {
    val build = rule.build
    setupSingleFeatureMismatchFlavors(build)
    val failure =
      build.executor.with(BooleanOption.ENFORCE_DYNAMIC_FEATURE_VARIANT_MATCHING, true).expectFailure().run(":app:assembleFreeDebug")
    failure.assertErrorContains(
      "Module :feature has product flavor 'pro' for dimension 'tier' which does not match application product flavor 'free'."
    )
  }

  @Test
  fun checkMatchingFlavorsBuildSuccessfully() {
    val build = rule.build
    setupSingleFeatureMismatchFlavors(build)
    build.executor.run(":app:assembleProDebug")
  }

  @Test
  fun checkMultiFeatureMismatchFailsAtExecutionTimeWhenEnforced() {
    val build = rule.build
    setupMultiFeatureMismatchFlavors(build)
    val failure =
      build.executor.with(BooleanOption.ENFORCE_DYNAMIC_FEATURE_VARIANT_MATCHING, true).expectFailure().run(":app:assembleFreeDebug")
    failure.assertErrorContains(
      "Module :feature2 has product flavor 'pro' for dimension 'tier' which does not match application product flavor 'free'."
    )
  }

  @Test
  fun checkMultiFeatureSubsetDimensionsFailsWhenEnforced() {
    val build = rule.build
    setupMultiFeatureSubsetDimensions(build)
    val failure =
      build.executor.with(BooleanOption.ENFORCE_DYNAMIC_FEATURE_VARIANT_MATCHING, true).expectFailure().run(":app:assembleFreeStagingDebug")
    failure.assertErrorContains(
      "Module :feature is missing product flavor dimension 'env' which is defined by the application with value 'staging'."
    )
    failure.assertErrorContains(
      "Module :feature2 is missing product flavor dimension 'tier' which is defined by the application with value 'free'."
    )
  }

  /**
   * Configures a scenario where :feature defines an extra flavor dimension ('ui') not present in the application, while the app uses
   * missingDimensionStrategy during dependency resolution. Used to verify that extra feature dimensions fail exact 1:1 variant parity
   * checks.
   */
  private fun setupExtraFeatureDimension(build: GradleBuild) {
    build
      .androidApplication(DEFAULT_APP_PATH)
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier'
                defaultConfig {
                    missingDimensionStrategy 'ui', 'phone'
                }
                productFlavors {
                    free {
                        dimension 'tier'
                    }
                    pro {
                        dimension 'tier'
                    }
                }
            }
            """
      )
    build
      .androidFeature(DEFAULT_FEATURE_PATH)
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier', 'ui'
                productFlavors {
                    free {
                        dimension 'tier'
                    }
                    pro {
                        dimension 'tier'
                    }
                    phone {
                        dimension 'ui'
                    }
                    tablet {
                        dimension 'ui'
                    }
                }
            }
            """
      )
    build
      .androidFeature(":feature2")
      .files
      .update("build.gradle")
      .append(
        """
            android {
                flavorDimensions 'tier'
                productFlavors {
                    free {
                        dimension 'tier'
                    }
                    pro {
                        dimension 'tier'
                    }
                }
            }
            """
      )
  }

  @Test
  fun checkExtraFeatureDimensionFailsWhenEnforced() {
    val build = rule.build
    setupExtraFeatureDimension(build)
    val failure =
      build.executor.with(BooleanOption.ENFORCE_DYNAMIC_FEATURE_VARIANT_MATCHING, true).expectFailure().run(":app:assembleFreeDebug")
    failure.assertErrorContains(
      "Module :feature defines product flavor 'phone' for dimension 'ui' which is not defined by the application."
    )
  }
}
