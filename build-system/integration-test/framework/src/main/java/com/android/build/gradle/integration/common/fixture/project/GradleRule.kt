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

import com.android.build.gradle.integration.common.fixture.project.GradleRule.Companion.configure
import com.android.build.gradle.integration.common.fixture.project.GradleRule.Companion.from
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.options.CreationOptions
import com.android.build.gradle.integration.common.fixture.project.options.LocalRuleOptionBuilder
import com.android.build.gradle.integration.common.fixture.testprojects.TestProjectBuilder
import org.junit.rules.TestRule
import java.nio.file.Path

/**
 * JUnit Rule to automatically set up Gradle projects for tests via a DSL.
 *
 * Entry point to create instances via [from] and [configure]
 */
interface GradleRule: TestRule {
    companion object {
        /**
         * Returns a [GradleRule] for a project configured with the [TestProjectBuilder].
         *
         * To configure the rule, use [configure] instead
         */
        fun from(action: GradleBuildDefinition.() -> Unit): GradleRule =
            GradleRuleBuilderImpl().create(GradleBuildDefinitionImpl(CreationOptions.DEFAULT_BUILD_NAME).also { action(it) })

        /**
         * Returns a [GradleRuleBuilder] that can be configured before calling [GradleRuleBuilder.from]
         */
        fun configure(): GradleRuleBuilder = GradleRuleBuilderImpl()
    }

    /**
     * The directory where the root build will be written.
     *
     * This can safely be queried before a call to [build]. This is the same value as
     * [GradleBuild.directory].
     *
     * Calls to [GradleBuildDefinition.name] or [GradleBuildDefinition.rootFolderName] will
     * impact the returned value
     */
    fun getMainBuildDirectory(): Path

    /**
     * The generated [GradleBuild].
     *
     * Once this is called the project is written on disk and it's not possible to change
     * its structure.
     *
     * It is possible after the fact to add more source files can be added via [GenericProject.files]
     * and it's possible to amend the build file with [AndroidProject.reconfigure]
     */
    val build: GradleBuild

    /**
     * Configures the build with one final action and returns the [GradleBuild]
     *
     * Once this is called the project is written on disk and it's not possible to change
     * its structure.
     *
     * It is possible after the fact to add more source files can be added via [GenericProject.files]
     * and it's possible to amend the build file with [AndroidProject.reconfigure]
     */
    fun build(action: GradleBuildDefinition.() -> Unit): GradleBuild

    /**
     * Configures the build with multi-step actions before returning the [GradleBuild]
     *
     * The returned instance of [LocalRuleOptionBuilder] allows configuring many Gradle options.
     *
     * The gradle build must be queried at the end with either versions of
     * [LocalRuleOptionBuilder.build]
     *
     * Once this is called the project is written on disk and it's not possible to change
     * its structure.
     *
     * It is possible after the fact to add more source files can be added via [GenericProject.files]
     * and it's possible to amend the build file with [AndroidProject.reconfigure]
     */
    fun configure(): LocalRuleOptionBuilder

}
