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

import com.android.build.gradle.integration.common.fixture.GradleOptionBuilder
import com.android.build.gradle.integration.common.fixture.GradleOptionBuilderDelegate
import com.android.build.gradle.integration.common.fixture.GradleOptions

/**
 * Allows configuring a Gadle test project with various options
 */
interface RuleOptionBuilder {

    /**
     * configures the Gradle version or location
     */
    fun withGradle(action: GradleLocationBuilder.() -> Unit): RuleOptionBuilder
    /**
     * configures the Gradle options (memory, config caching)
     */
    fun withGradleOptions(action: GradleOptionBuilder<*>.() -> Unit): RuleOptionBuilder

    /**
     * configures the Android SDK/NDK
     */
    fun withSdk(action: SdkConfigurationBuilder.() -> Unit): RuleOptionBuilder

    /**
     * configures the Gradler properties
     */
    fun withProperties(action: GradlePropertiesBuilder.() -> Unit): RuleOptionBuilder
}

internal open class DefaultRuleOptionBuilder: RuleOptionBuilder {
    private val gradleLocationDelegate = GradleLocationDelegate()
    private val sdkConfigurationDelegate = SdkConfigurationDelegate()
    private val gradleOptionsDelegate = GradleOptionBuilderDelegate(null)
    private val propertiesDelegate = GradlePropertiesBuilderDelegate()

    val gradleLocation: GradleLocation
        get() = gradleLocationDelegate.asGradleLocation

    val sdkConfiguration: SdkConfiguration
        get() = sdkConfigurationDelegate.asSdkConfiguration

    val gradleOptions: GradleOptions
        get() = gradleOptionsDelegate.asGradleOptions

    val gradleProperties: List<String>
        get() = propertiesDelegate.properties

    override fun withGradle(action: GradleLocationBuilder.() -> Unit): DefaultRuleOptionBuilder {
        action(gradleLocationDelegate)
        return this
    }

    override fun withGradleOptions(action: GradleOptionBuilder<*>.() -> Unit): DefaultRuleOptionBuilder {
        action(gradleOptionsDelegate)
        return this
    }

    override fun withSdk(action: SdkConfigurationBuilder.() -> Unit): DefaultRuleOptionBuilder {
        action(sdkConfigurationDelegate)
        return this
    }

    override fun withProperties(action: GradlePropertiesBuilder.() -> Unit): RuleOptionBuilder {
        action(propertiesDelegate)
        return this
    }

    internal fun mergeWith(other: DefaultRuleOptionBuilder) {
        gradleLocationDelegate.mergeWith(other.gradleLocationDelegate)
        sdkConfigurationDelegate.mergeWith(other.sdkConfigurationDelegate)
        gradleOptionsDelegate.mergeWith(other.gradleOptionsDelegate)
        propertiesDelegate.mergeWith(other.propertiesDelegate)
    }
}
