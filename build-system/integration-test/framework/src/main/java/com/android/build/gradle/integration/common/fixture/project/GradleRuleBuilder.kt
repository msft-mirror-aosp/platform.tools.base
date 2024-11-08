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
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.DEFAULT_TEST_PROJECT_NAME
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.testprojects.TestProjectBuilder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Builder for [GradleRule].
 *
 * Don't use directly, use [GradleRule.configure].
 */
class GradleRuleBuilder internal constructor(): TestRule {

    private val name: String = DEFAULT_TEST_PROJECT_NAME

    private val gradleLocation = GradleLocationDelegate()
    private val sdkConfiguration = SdkConfigurationDelegate()

    private val gradleOptionsDelegate = GradleOptionBuilderDelegate(null)

    /**
     * Returns the [GradleRule], for a project initialized with the [TestProjectBuilder]
     */
    fun from(action: GradleBuildDefinition.() -> Unit): GradleRule {
        val builder = GradleBuildDefinitionImpl("project")
        action(builder)

        return create(builder)
    }

    fun withGradle(action: GradleLocationBuilder.() -> Unit): GradleRuleBuilder {
        action(gradleLocation)
        return this
    }

    fun withGradleOptions(action: GradleOptionBuilder<*>.() -> Unit): GradleRuleBuilder {
        action(gradleOptionsDelegate)
        return this
    }

    fun withSdk(action: SdkConfigurationBuilder.() -> Unit): GradleRuleBuilder {
        action(sdkConfiguration)
        return this
    }


    // -------------------------

    internal fun create(
        gradleBuild: GradleBuildDefinitionImpl
    ): GradleRule {
        return GradleRule(
            name = name,
            gradleBuild = gradleBuild,

            gradleLocation = gradleLocation.asGradleLocation,
            sdkConfiguration = sdkConfiguration.asSdkConfiguration,
            gradleOptions = gradleOptionsDelegate.asGradleOptions,
            gradleProperties = listOf(), // FIXME
        )
    }

    /**
     * This implements TestRule only to detect if someone forgot to call [from]
     */
    override fun apply(
        p0: Statement?,
        p1: Description?
    ): Statement? {
        throw RuntimeException("GradleRuleBuilder used as a TestRule. Did you forgot to call from()")
    }
}
