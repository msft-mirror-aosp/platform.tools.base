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

import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition

/**
 * class to configure gradle options inside a test method. see [GradleRule.configure]
 *
 * This allows reconfiguring the project before writing it on disk. Because it's inside a test, either [build] method
 * must be called at the end to write the test and return a [GradleBuild]
 */
class LocalRuleOptionBuilder internal constructor(
    private val gradleRule: GradleRule,
    private val ruleOptionBuilder: DefaultRuleOptionBuilder
): RuleOptionBuilder {
    private val delegate = DefaultRuleOptionBuilder()

    val build: GradleBuild
        get() {
            ruleOptionBuilder.mergeWith(delegate)
            return gradleRule.build
        }

    fun build(action: GradleBuildDefinition.() -> Unit): GradleBuild {
        ruleOptionBuilder.mergeWith(delegate)
        return gradleRule.build(action)
    }

    override fun withGradleLocation(action: GradleLocationBuilder.() -> Unit): LocalRuleOptionBuilder {
        delegate.withGradleLocation(action)
        return this
    }

    override fun withGradleOptions(action: GradleOptionBuilder<*>.() -> Unit): LocalRuleOptionBuilder {
        delegate.withGradleOptions(action)
        return this
    }

    override fun withSdk(action: SdkConfigurationBuilder.() -> Unit): LocalRuleOptionBuilder {
        delegate.withSdk(action)
        return this
    }

    override fun withProperties(action: GradlePropertiesBuilder.() -> Unit): LocalRuleOptionBuilder {
        delegate.withProperties(action)
        return this
    }

    override fun withCreationOptions(action: CreationOptionsBuilder.() -> Unit): LocalRuleOptionBuilder {
        delegate.withCreationOptions(action)
        return this
    }
}
