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
import com.android.build.gradle.integration.common.fixture.project.builder.LocalTestProjectSpec
import com.android.build.gradle.integration.common.fixture.project.options.LocalRuleOptionBuilder
import org.junit.rules.TestRule
import java.nio.file.Path

/**
 * JUnit Rule to automatically set up Gradle projects for tests via a DSL.
 *
 * Entry point to create instances via [from] and [configure]
 */
interface GradleRule: TestRule {
    companion object : GradleRuleEntryPoint {
        /**
         * Returns a [GradleRule] for a synthetic project configured with the [GradleBuildDefinition].
         *
         * To configure the rule, use [configure] instead
         *
         * @param folderName the name of the folder containing the build.
         * @param logicalName The logical name of the build in gradle. This impact the groupId information of the subprojects. if null, same as folder name
         * @param configAction the action to configure the build
         */
        override fun from(
            folderName: String,
            logicalName: String?,
            configAction: GradleBuildDefinition.() -> Unit
        ): GradleRule = fromImplementation(
            null,
            folderName,
            logicalName,
            specConfigAction = null,
            configAction
        )

        /**
         * Returns a [GradleRule] for a project configured from both an on-disk test project and
         * a [GradleBuildDefinition].
         *
         * To configure the rule, use [configure] instead
         *
         * @param testProjectName the name of the on-disk test project.
         * @param folderName the name of the folder containing the build.
         * @param logicalName The logical name of the build in gradle. This impact the groupId information of the subprojects. if null, same as folder name
         * @param configAction the action to configure the build
         */
        override fun fromProject(
            testProjectName: String,
            folderName: String,
            logicalName: String?,
            configAction: GradleBuildDefinition.() -> Unit
        ): GradleRule = fromImplementation(
           testProjectName,
            folderName,
            logicalName,
            specConfigAction = null,
            configAction
        )

        override fun fromProject(
            testProjectSpec: LocalTestProjectSpec,
            folderName: String,
            logicalName: String?,
            configAction: (GradleBuildDefinition.() -> Unit)?
        ): GradleRule = fromImplementation(
            testProjectSpec.projectName,
            folderName,
            logicalName,
            specConfigAction = testProjectSpec.configAction,
            configAction
        )

        private fun fromImplementation(
            testProjectName: String?,
            folderName: String = GradleBuildDefinition.DEFAULT_BUILD_NAME,
            logicalName: String? = null,
            specConfigAction: (GradleBuildDefinition.() -> Unit)? = null,
            configAction: (GradleBuildDefinition.() -> Unit)?
        ): GradleRule = GradleRuleBuilderImpl().create(
            GradleBuildDefinitionImpl(
                name = logicalName ?: folderName,
                rootFolderName = folderName,
                enableDefaultContentCreation = testProjectName == null,
            ).also {
                specConfigAction?.invoke( it)
                configAction?.invoke(it)
            },
            testProjectName
        )

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
