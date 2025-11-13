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

import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleDefinitionDsl
import com.android.build.gradle.integration.common.fixture.project.builder.LocalTestProjectSpec
import com.android.build.gradle.integration.common.fixture.project.builder.MavenRepository
import com.android.build.gradle.integration.common.fixture.project.builder.MavenRepositoryImpl
import com.android.build.gradle.integration.common.fixture.project.options.DefaultRuleOptionBuilder
import com.android.build.gradle.integration.common.fixture.project.options.GradleLocationBuilder
import com.android.build.gradle.integration.common.fixture.project.options.GradleOptionBuilder
import com.android.build.gradle.integration.common.fixture.project.options.RuleOptionBuilder
import com.android.build.gradle.integration.common.fixture.project.options.SdkConfigurationBuilder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Builder for [GradleRule].
 *
 * Don't use directly, use [GradleRule.configure].
 */
@GradleDefinitionDsl
interface GradleRuleBuilder: GradleRuleEntryPoint, RuleOptionBuilder, TestRule {

    override fun disableBrokenBuiltInKotlinOptOutChecks(): GradleRuleBuilder
    override fun disableBrokenNewDslOptOutChecks(): GradleRuleBuilder

    override fun withGradleLocation(action: GradleLocationBuilder.() -> Unit): GradleRuleBuilder
    override fun withGradleOptions(action: GradleOptionBuilder<*>.() -> Unit): GradleRuleBuilder
    override fun withSdk(action: SdkConfigurationBuilder.() -> Unit): GradleRuleBuilder

    fun withMavenRepository(action: MavenRepository.() -> Unit): GradleRuleBuilder

    fun withProfileOutput(): GradleRuleBuilder
}

// --------------------------

internal class GradleRuleBuilderImpl internal constructor(): GradleRuleBuilder {

    private val ruleOptionBuilder = DefaultRuleOptionBuilder()
    private val mavenRepository = MavenRepositoryImpl()
    private var enableProfileOutput = false

    override fun from(
        folderName: String,
        logicalName: String?,
        configAction: GradleBuildDefinition.() -> Unit
    ): GradleRule {
        val builder = GradleBuildDefinitionImpl(
            name = logicalName ?: folderName,
            rootFolderName = folderName,
            enableDefaultContentCreation = true
        )
        configAction(builder)

        return create(builder)
    }

    override fun fromProject(
        testProjectName: String,
        folderName: String,
        logicalName: String?,
        configAction: GradleBuildDefinition.() -> Unit
    ): GradleRule {
        val builder = GradleBuildDefinitionImpl(
            name = logicalName ?: folderName,
            rootFolderName = folderName,
            enableDefaultContentCreation = false,
        )
        configAction(builder)

        return create(builder, testProjectName)
    }

    override fun fromProject(
        testProjectSpec: LocalTestProjectSpec,
        folderName: String,
        logicalName: String?,
        configAction: (GradleBuildDefinition.() -> Unit)?
    ): GradleRule {
        val builder = GradleBuildDefinitionImpl(
            name = logicalName ?: folderName,
            rootFolderName = folderName,
            enableDefaultContentCreation = false,
        )
        testProjectSpec.configAction(builder)
        configAction?.invoke(builder)

        return create(builder, testProjectSpec.projectName)
    }

    override fun disableBrokenBuiltInKotlinOptOutChecks(): GradleRuleBuilder {
        ruleOptionBuilder.disableBrokenBuiltInKotlinOptOutChecks()
        return this
    }

    override fun disableBrokenNewDslOptOutChecks(): GradleRuleBuilder {
        ruleOptionBuilder.disableBrokenNewDslOptOutChecks()
        return this
    }

    override fun withGradleLocation(action: GradleLocationBuilder.() -> Unit): GradleRuleBuilder {
        ruleOptionBuilder.withGradleLocation(action)
        return this
    }

    override fun withGradleOptions(action: GradleOptionBuilder<*>.() -> Unit): GradleRuleBuilder {
        ruleOptionBuilder.withGradleOptions(action)
        return this
    }

    override fun withSdk(action: SdkConfigurationBuilder.() -> Unit): GradleRuleBuilder {
        ruleOptionBuilder.withSdk(action)
        return this
    }

    override fun withMavenRepository(action: MavenRepository.() -> Unit): GradleRuleBuilder {
        action(mavenRepository)
        return this
    }

    override fun withProfileOutput(): GradleRuleBuilder {
        enableProfileOutput = true
        return this
    }

    // -------------------------

    internal fun create(
        gradleBuild: GradleBuildDefinitionImpl,
        testProjectName: String? = null
    ): GradleRule {
        return GradleRuleImpl(
            buildDefinition = gradleBuild,
            ruleOptionBuilder = ruleOptionBuilder,
            externalLibraries = mavenRepository.libraries,
            enableProfileOutput,
            testProjectName,
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
