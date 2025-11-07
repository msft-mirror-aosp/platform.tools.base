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

package com.android.build.gradle.integration.common.fixture.project.options

import com.android.build.gradle.integration.common.fixture.project.GradleRuleImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleDefinitionDsl
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.BooleanOption.DEFAULT_TARGET_SDK_TO_COMPILE_SDK_IF_UNSET
import com.android.build.gradle.options.BooleanOption.ENABLE_APP_COMPILE_TIME_R_CLASS
import com.android.build.gradle.options.BooleanOption.ENABLE_LEGACY_VARIANT_API
import com.android.build.gradle.options.BooleanOption.USE_NEW_DSL
import com.android.build.gradle.options.StringOption
import com.android.build.gradle.options.StringOption.SUPPRESS_UNSUPPORTED_OPTION_WARNINGS

/**
 * Object to add Gradle Properties to a test project (via [GradleRule]
 */
@GradleDefinitionDsl
interface GradlePropertiesBuilder {

    /**
     * Adds a property as a single line.
     *
     * If the key is a [BooleanOption] or a [StringOption], use the more specific [add] methods
     * below.
     */
    fun add(key: String, value: String)

    /**
     * Adds a [BooleanOption]
     */
    fun add(option: BooleanOption, value: Boolean)

    /**
     * Adds a [StringOption]
     */
    fun add(option: StringOption, value: String)

    /**
     * Removes the property matching the key.
     *
     * If the key is a [BooleanOption] or [StringOption] use the specific API
     */
    fun remove(key: String)

    /**
     * Removes the property matching the key.
     */
    fun remove(option: BooleanOption)

    /**
     * Removes the property matching the key.
     */
    fun remove(option: StringOption)
}

internal class GradlePropertiesDelegate : GradlePropertiesBuilder {

    internal val mutableProperties = mutableMapOf<String, String>()
    internal val mutableBooleans = mutableMapOf<BooleanOption, Boolean>()
    private val mutableStrings = mutableMapOf<StringOption, String>()

    override fun add(key: String, value: String) {
        mutableProperties[key] = value
    }

    override fun add(option: BooleanOption, value: Boolean) {
        mutableBooleans[option] = value
    }

    override fun add(option: StringOption, value: String) {
        mutableStrings[option] = value
    }

    override fun remove(key: String) {
        mutableProperties.remove(key)
    }

    override fun remove(option: BooleanOption) {
        mutableBooleans.remove(option)
    }

    override fun remove(option: StringOption) {
        mutableStrings.remove(option)
    }

    internal fun applyOptOutForAgp9() {
        var suppressValue = mutableStrings[SUPPRESS_UNSUPPORTED_OPTION_WARNINGS] ?: ""

        for (entry in GradleRuleImpl.AGP_9_OPT_OUTS) {
            if (!mutableBooleans.containsKey(entry.key)) {
                mutableBooleans[entry.key] = entry.value
                suppressValue = if (suppressValue.isEmpty()) {
                    entry.key.propertyName
                } else {
                    suppressValue + "," + entry.key.propertyName
                }
            }
        }

        if (suppressValue.isNotEmpty()) {
            mutableStrings[SUPPRESS_UNSUPPORTED_OPTION_WARNINGS] = suppressValue
        }
    }

    internal val properties: List<String>
        get() {
            // check for raw keys in mutableProperties that are coming from the *Options
            for (option in mutableBooleans.keys) {
                if (mutableProperties.contains(option.propertyName)) {
                    throw RuntimeException("""
                        Raw property with key '$option' conflicts with BooleanOption.
                        Use GradlePropertiesBuilder.add(BooleanOption, String) instead
                    """
                    .trimIndent())
                }
            }

            for (option in mutableStrings.keys) {
                if (mutableProperties.contains(option.propertyName)) {
                    throw RuntimeException("""
                        Raw property with key '$option' conflicts with StringOption.
                        Use GradlePropertiesBuilder.add(StringOption, String) instead
                    """
                        .trimIndent())
                }
            }

            return mutableBooleans.map { (option, value) ->
                "${option.propertyName}=$value"
            } + mutableStrings.map { (option, value) ->
                "${option.propertyName}=$value"
            } + mutableProperties.map { (key, value) ->
                "$key=$value"
            }
        }
}
