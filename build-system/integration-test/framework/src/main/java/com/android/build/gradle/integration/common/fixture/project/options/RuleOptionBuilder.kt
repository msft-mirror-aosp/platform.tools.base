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

/**
 * Allows configuring a Gadle test project with various options
 */
interface RuleOptionBuilder {

    /**
     * Disable checks for improper built-in Kotlin opt-out
     *
     * There are cases where it's not possible for the fixture to accurately detect
     * that the test requires opt-out.
     *
     * Using this method will make the fixture not check for this.
     *
     * Make sure to use this only on the tests that need it, and not on whole classes.
     */
    fun disableBrokenBuiltInKotlinOptOutChecks(): RuleOptionBuilder

    /**
     * Disable checks for improper newDsl opt-out
     *
     * There are cases where it's not possible for the fixture to accurately detect
     * that the test requires opt-out.
     *
     * Using this method will make the fixture not check for this.
     *
     * Make sure to use this only on the tests that need it, and not on whole classes.
     */
    fun disableBrokenNewDslOptOutChecks(): RuleOptionBuilder

    /**
     * configures the Gradle version or location
     */
    fun withGradleLocation(action: GradleLocationBuilder.() -> Unit): RuleOptionBuilder
    /**
     * configures the Gradle options (memory, config caching)
     */
    fun withGradleOptions(action: GradleOptionBuilder<*>.() -> Unit): RuleOptionBuilder

    /**
     * configures the Android SDK/NDK
     */
    fun withSdk(action: SdkConfigurationBuilder.() -> Unit): RuleOptionBuilder
}

internal open class DefaultRuleOptionBuilder: RuleOptionBuilder {
    internal var disableBrokenBuiltInKotlinOptOutChecks = false
    internal var disableBrokenNewDslOptOutChecks = false
    private val gradleLocationDelegate = GradleLocationDelegate()
    private val sdkConfigurationDelegate = SdkConfigurationDelegate()
    private val gradleOptionsDelegate = GradleOptionsDelegate(null)

    val gradleLocation: GradleLocation
        get() = gradleLocationDelegate.asGradleLocation

    val sdkConfiguration: SdkConfiguration
        get() = sdkConfigurationDelegate.asSdkConfiguration

    val gradleOptions: GradleOptions
        get() = gradleOptionsDelegate.asGradleOptions

    override fun disableBrokenBuiltInKotlinOptOutChecks(): DefaultRuleOptionBuilder {
        disableBrokenBuiltInKotlinOptOutChecks = true
        return this
    }

    override fun disableBrokenNewDslOptOutChecks(): DefaultRuleOptionBuilder {
        disableBrokenNewDslOptOutChecks = true
        return this
    }

    override fun withGradleLocation(action: GradleLocationBuilder.() -> Unit): DefaultRuleOptionBuilder {
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

    internal fun mergeWith(other: DefaultRuleOptionBuilder) {
        disableBrokenBuiltInKotlinOptOutChecks =
            disableBrokenBuiltInKotlinOptOutChecks || other.disableBrokenBuiltInKotlinOptOutChecks
        disableBrokenNewDslOptOutChecks =
            disableBrokenNewDslOptOutChecks || other.disableBrokenNewDslOptOutChecks
        gradleLocationDelegate.mergeWith(other.gradleLocationDelegate)
        sdkConfigurationDelegate.mergeWith(other.sdkConfigurationDelegate)
        gradleOptionsDelegate.mergeWith(other.gradleOptionsDelegate)
    }
}
