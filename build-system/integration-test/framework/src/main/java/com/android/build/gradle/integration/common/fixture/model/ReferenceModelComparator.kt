/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.model

import com.android.build.gradle.integration.common.fixture.ModelBuilderV2
import com.android.build.gradle.integration.common.fixture.ModelContainerV2
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.options.RuleOptionBuilder
import org.junit.Rule

/**
 * Compare models to golden files that contains only the delta to a reference project state.
 *
 * This is meant to be used as a base class for tests running sync.
 */
abstract class ReferenceModelComparator(
    /**
     * A lambda that configures the base project state to be compared against
     */
    referenceConfig: GradleBuildDefinition.() -> Unit,
    /**
     * A lambda that configures the project with the modification.
     * This is applied on top of [referenceConfig]
     */
    deltaConfig: GradleBuildDefinition.() -> Unit,
    /**
     * an optional configuration for the reference project
     */
    referenceOptions: (RuleOptionBuilder.() -> Unit)? = null,
    /**
     * an optional configuration for the delta project.
     * this is applied on top of the `referenceOptions`
     */
    deltaOptions: (RuleOptionBuilder.() -> Unit)? = null,
    /**
     * Sync options to be used when syncing both the base state and the modified project state.
     */
    private val syncOptions: ModelBuilderV2.() -> ModelBuilderV2 = { this },
    /**
     * Name of the variant to sync for dependencies
     */
    private val variantName: String? = null
) : BaseModelComparator {

    @get:Rule
    val referenceRule = GradleRule.configure().run {
        referenceOptions?.let { optionAction ->
            optionAction(this)
        }

        withCreationOptions {
            rootFolderName = "referenceProject"
        }.from {
            name = "project"
            referenceConfig(this)
        }
    }

    @get:Rule
    val deltaRule = GradleRule.configure().run {
        referenceOptions?.let { optionAction ->
            optionAction(this)
        }

        deltaOptions?.let { optionAction ->
            optionAction(this)
        }

        withCreationOptions {
            rootFolderName = "deltaProject"
        }.from {
            name = "project"
            referenceConfig(this)
            deltaConfig(this)
        }
    }

    private val referenceResult: ModelBuilderV2.FetchResult<ModelContainerV2> by lazy {
        syncOptions(referenceRule.build.modelBuilder).fetchModels(variantName)
    }

    private val result: ModelBuilderV2.FetchResult<ModelContainerV2> by lazy {
        syncOptions(deltaRule.build.modelBuilder).fetchModels(variantName)
    }

    fun compareBasicAndroidProjectWith(
        projectPath: String? = null,
        goldenFileSuffix: String = ""
    ) {
        Comparator(this, result, referenceResult).compareBasicAndroidProject(
            projectAction =  { getProject(projectPath) },
            goldenFile = goldenFileSuffix
        )
    }

    fun compareAndroidProjectWith(
        projectPath: String? = null,
        goldenFileSuffix: String = ""
    ) {
        Comparator(this, result, referenceResult).compareAndroidProject(
            projectAction =  { getProject(projectPath) },
            goldenFile = goldenFileSuffix
        )
    }

    fun ensureAndroidProjectDeltaIsEmpty(projectPath: String? = null) {
        Comparator(this, result, referenceResult).ensureAndroidProjectIsEmpty {
            getProject(projectPath)
        }
    }

    fun compareAndroidDslWith(
        projectPath: String? = null,
        goldenFileSuffix: String = ""
    ) {
        Comparator(this, result, referenceResult).compareAndroidDsl(
            projectAction = { getProject(projectPath) },
            goldenFile = goldenFileSuffix
        )
    }

    fun ensureAndroidDslDeltaIsEmpty(projectPath: String? = null) {
        Comparator(this, result, referenceResult).ensureAndroidDslIsEmpty {
            getProject(projectPath)
        }
    }

    fun compareVariantDependenciesWith(
        projectPath: String? = null,
        goldenFileSuffix: String = ""
    ) {
        Comparator(this, result, referenceResult).compareVariantDependencies(
            projectAction = { getProject(projectPath) },
            goldenFile = goldenFileSuffix
        )
    }

    fun ensureVariantDependenciesDeltaIsEmpty(projectPath: String? = null) {
        Comparator(this, result, referenceResult).ensureVariantDependenciesIsEmpty {
            getProject(projectPath)
        }
    }
}
