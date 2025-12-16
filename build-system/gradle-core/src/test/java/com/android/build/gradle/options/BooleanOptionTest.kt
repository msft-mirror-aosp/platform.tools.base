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
@OptIn(ExperimentalStdlibApi::class)  // For Enum.entries
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
    fun `check experimental features have a FutureStage`() {
        // Experimental features should have an (estimated) FutureStage -- see FutureStage's kdoc.
        // If you can't estimate a FutureStage, add it to the following ignore list.
        val ignoreList = listOf(
            BooleanOption.KMP_USE_JVM_PLATFORM_TYPE,
            BooleanOption.DISABLE_KMP_RUNTIME_CLASSPATH,
            BooleanOption.BUILD_FEATURE_MLMODELBINDING,
            BooleanOption.ENABLE_DEFAULT_DEBUG_SIGNING_CONFIG,
            BooleanOption.ENABLE_PROFILE_JSON,
            BooleanOption.DISALLOW_DEPENDENCY_RESOLUTION_AT_CONFIGURATION,
            BooleanOption.VERSION_CHECK_OVERRIDE_PROPERTY,
            BooleanOption.OVERRIDE_PATH_CHECK_PROPERTY,
            BooleanOption.DISABLE_RESOURCE_VALIDATION,
            BooleanOption.CONSUME_DEPENDENCIES_AS_SHARED_LIBRARIES,
            BooleanOption.SUPPORT_OEM_TOKEN_LIBRARIES,
            BooleanOption.DISABLE_EARLY_MANIFEST_PARSING,
            BooleanOption.CONDITIONAL_KEEP_RULES,
            BooleanOption.KEEP_SERVICES_BETWEEN_BUILDS,
            BooleanOption.ENABLE_PARTIAL_R_INCREMENTAL_BUILDS,
            BooleanOption.ENABLE_LOCAL_TESTING,
            BooleanOption.DISABLE_MINSDKLIBRARY_CHECK,
            BooleanOption.ENABLE_INSTRUMENTATION_TEST_DESUGARING,
            BooleanOption.DISABLE_KOTLIN_ATTRIBUTE_SETUP,
            BooleanOption.UNINSTALL_INCOMPATIBLE_APKS,
            BooleanOption.GRADLE_MANAGED_DEVICE_EMULATOR_SHOW_KERNEL_LOGGING,
            BooleanOption.GRADLE_MANAGED_DEVICE_ALLOW_OLD_API_LEVEL_DEVICES,
            BooleanOption.ENABLE_ADDITIONAL_ANDROID_TEST_OUTPUT,
            BooleanOption.ENABLE_EXTRACT_ANNOTATIONS,
            BooleanOption.BUILD_ONLY_TARGET_ABI,
            BooleanOption.ENABLE_PARALLEL_NATIVE_JSON_GEN,
            BooleanOption.ENABLE_SIDE_BY_SIDE_CMAKE,
            BooleanOption.ENABLE_NATIVE_COMPILER_SETTINGS_CACHE,
            BooleanOption.ENABLE_CMAKE_BUILD_COHABITATION,
            BooleanOption.ENABLE_PROGUARD_RULES_EXTRACTION,
            BooleanOption.ENABLE_DUPLICATE_CLASSES_CHECK,
            BooleanOption.MINIMAL_KEEP_RULES,
            BooleanOption.EXCLUDE_RES_SOURCES_FOR_RELEASE_BUNDLES,
            BooleanOption.ENABLE_BUILD_CONFIG_AS_BYTECODE,
            BooleanOption.RUN_LINT_IN_PROCESS,
            BooleanOption.ENABLE_TEST_FIXTURES,
            BooleanOption.USE_DECLARATIVE_INTERFACES,
            BooleanOption.FORCE_DETERMINISTIC_APK,
            BooleanOption.SKIP_APKS_VIA_BUNDLE_IF_POSSIBLE,
            BooleanOption.MISSING_LINT_BASELINE_IS_EMPTY_BASELINE,
            BooleanOption.LEGACY_TRANSFORM_TASK_FORCE_NON_INCREMENTAL,
            BooleanOption.PRIVACY_SANDBOX_SDK_PLUGIN_SUPPORT,
            BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT,
            BooleanOption.PRIVACY_SANDBOX_SDK_REQUIRE_SERVICES,
            BooleanOption.VERIFY_AAR_CLASSES,
            BooleanOption.DISABLE_COMPILE_SDK_CHECKS,
            BooleanOption.SUPPRESS_EXTRACT_NATIVE_LIBS_WARNINGS,
            BooleanOption.FUSED_LIBRARY_PUBLICATION_ONLY_MODE,
            BooleanOption.LINT_BASELINE_OMIT_LINE_NUMBERS,
            BooleanOption.ENABLE_NEW_TEST_DSL,
            BooleanOption.ENABLE_SCREENSHOT_TEST,
            BooleanOption.ENABLE_TEST_FIXTURES_KOTLIN_SUPPORT,
            BooleanOption.SUPPRESS_MANIFEST_PACKAGE_WARNING,
            BooleanOption.DISABLE_INLINE_SCOPES_NUMBERS,
            BooleanOption.ENABLE_DEVICE_TARGETING_CONFIG_API,
            BooleanOption.DUMP_ARTIFACTS_LOCATIONS,
            BooleanOption.ENABLE_PROBLEMS_API,
            BooleanOption.R8_GRADUAL_API,
            BooleanOption.ENABLE_CLASSPATH_CHECK_TASKS,
            BooleanOption.DISABLE_ALL_CONSTRAINTS,
            BooleanOption.ENABLE_IDENTITY_TRANSFORMS_FOR_PROCESSED_ARTIFACTS,
            BooleanOption.TREAT_MANIFEST_MERGER_WARNINGS_AS_ERRORS
        )

        checkViolatingProjectOptions(
            violatingOptions = BooleanOption.entries.filter {
                (it.stage is FeatureStage.Experimental || it.stage is ApiStage.Experimental)
                        && it.futureStage == null
            },
            ignoreList = ignoreList,
            requirement = "Experimental features should have an (estimated) FutureStage."
        )
    }

    @Test
    fun `check supported features have a FutureStage`() {
        // Supported features should have an (estimated) FutureStage -- see FutureStage's kdoc.
        //   - If you don't intend to ever move it to the next stage, consider changing the current
        //   stage from FeatureStage.Supported to ApiStage.Stable.
        //   - If you intend to move it to the next stage at some point but can't estimate a
        //   FutureStage, add it to the following ignore list.
        val ignoreList = listOf(
            BooleanOption.ENABLE_SDK_DOWNLOAD,
            BooleanOption.FORCE_JACOCO_OUT_OF_PROCESS,
            BooleanOption.PRECOMPILE_DEPENDENCIES_RESOURCES,
            BooleanOption.INCLUDE_DEPENDENCY_INFO_IN_APKS,
            BooleanOption.ONLY_ENABLE_UNIT_TEST_BY_DEFAULT_FOR_THE_TESTED_BUILD_TYPE
        )

        checkViolatingProjectOptions(
            violatingOptions = BooleanOption.entries.filter {
                it.stage is FeatureStage.Supported && it.futureStage == null
            },
            ignoreList = ignoreList,
            requirement = "Supported features should have an (estimated) FutureStage."
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
        val ignoreList = listOf(
            BooleanOption.ENABLE_LEGACY_API,
        )
        checkViolatingProjectOptions(
            violatingOptions = BooleanOption.entries.filter {
                (it.stage is FeatureStage.Deprecated || it.stage is FeatureStage.Removed)
                        && it.defaultValue
            },
            ignoreList = ignoreList,
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
