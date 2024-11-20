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

package com.android.build.gradle.integration.common.fixture.project

import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption

/**
 * Object to add Gradle Properties to a test project (via [GradleRule]
 */
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
}

internal class GradlePropertiesBuilderDelegate: GradlePropertiesBuilder {
    private val mutableProperties = mutableMapOf<String, String>()
    private val mutableBooleans = mutableMapOf<BooleanOption, Boolean>()
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

    internal fun mergeWith(other: GradlePropertiesBuilderDelegate) {
        mutableProperties += other.mutableProperties
        mutableBooleans += other.mutableBooleans
        mutableStrings += other.mutableStrings
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
