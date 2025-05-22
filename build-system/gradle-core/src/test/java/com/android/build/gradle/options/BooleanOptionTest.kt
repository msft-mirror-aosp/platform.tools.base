/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the ,License,);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an ,AS IS, BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.options

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Tests for [BooleanOption]. */
class BooleanOptionTest {

    @Test
    fun `check Boolean options are put in correct order`() {
        // Expected order of Boolean options
        val order = listOf(
                ApiStage.Stable::class.java,
                FeatureStage.Supported::class.java,
                ApiStage.Experimental::class.java,
                FeatureStage.Experimental::class.java,
                FeatureStage.SoftlyEnforced::class.java,
                ApiStage.Deprecated::class.java,
                FeatureStage.Deprecated::class.java,
                FeatureStage.Enforced::class.java,
                ApiStage.Removed::class.java,
                FeatureStage.Removed::class.java
        )

        BooleanOption.entries.forEachIndexed { index, currentOption ->
            if (index == 0) return@forEachIndexed
            val previousOption = BooleanOption.entries[index - 1]
            assertWithMessage(
                "Boolean option `${previousOption.name}` with stage `${previousOption.stage.javaClass.name}`" +
                        " should be positioned after Boolean option `${currentOption.name}` with stage `${currentOption.stage.javaClass.name}`." +
                        " Rearrange their positions to put them in the correct groups."
            ).that(order.indexOf(currentOption.stage.javaClass) >= order.indexOf(previousOption.stage.javaClass))
                .isTrue()
        }
    }

    @Test
    fun `check features are not in SUPPORTED stage`() {
        // The use of FeatureStage.Supported is not recommended as it doesn't specify a clear
        // timeline and the feature may stay in this stage for too long, thus increasing maintenance
        // cost to AGP and users.
        // In some cases, FeatureStage.Supported may be suitable (e.g., if we don't want to show a
        // warning when users set a different value than the default). If so, we can add the feature
        // to the following ignore list.
        val ignoreList = listOf(
            BooleanOption.ENABLE_SDK_DOWNLOAD,
            BooleanOption.ENFORCE_UNIQUE_PACKAGE_NAMES,
            BooleanOption.FORCE_JACOCO_OUT_OF_PROCESS,
            BooleanOption.PRECOMPILE_DEPENDENCIES_RESOURCES,
            BooleanOption.INCLUDE_DEPENDENCY_INFO_IN_APKS,
            BooleanOption.ENABLE_LEGACY_API,
            BooleanOption.FULL_R8,
            BooleanOption.R8_STRICT_FULL_MODE_FOR_KEEP_RULES,
            BooleanOption.ONLY_ENABLE_UNIT_TEST_BY_DEFAULT_FOR_THE_TESTED_BUILD_TYPE,
        )

        checkViolatingProjectOptions(
            violatingOptions = BooleanOption.entries.filter { it.stage is FeatureStage.Supported },
            ignoreList = ignoreList,
            requirement = "Features should not be in `FeatureStage.Supported` stage."
        )
    }

    @Test
    fun `check softly-enforced and enforced features have default value 'true'`() {
        checkViolatingProjectOptions(
            violatingOptions = BooleanOption.entries.filter {
                (it.stage is FeatureStage.SoftlyEnforced || it.stage is FeatureStage.Enforced)
                        && !it.defaultValue
            },
            requirement = "Softly-enforced and enforced features must have default value `true`."
        )
    }

    @Test
    fun `check deprecated and removed features have default value 'false'`() {
        checkViolatingProjectOptions(
            violatingOptions = BooleanOption.entries.filter {
                (it.stage is FeatureStage.Deprecated || it.stage is FeatureStage.Removed)
                        && it.defaultValue
            },
            requirement = "Deprecated and removed features must have default value `false`."
        )
    }

    @Test
    fun `check that FutureStage is different from the current stage`() {
        checkViolatingProjectOptions(
            violatingOptions = BooleanOption.entries.filter {
                it.futureStage != null && it.futureStage.stage::class == it.stage::class
                        && it.futureStage.defaultValue == it.defaultValue
            },
            requirement = "FutureStage must be different from the current stage."
        )
    }
}
