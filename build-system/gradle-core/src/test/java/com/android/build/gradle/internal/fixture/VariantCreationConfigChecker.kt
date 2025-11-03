/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.internal.fixture

import com.android.build.gradle.internal.plugins.AppPlugin
import com.android.build.gradle.internal.component.TestComponentCreationConfig
import com.android.build.gradle.internal.component.VariantCreationConfig
import com.google.common.truth.Truth.assertThat

interface VariantCreationConfigChecker {

    val testComponents: Set<TestComponentCreationConfig>
    val mainVariants: Set<VariantCreationConfig>
    fun checkTestedVariant(
        variantName: String,
        testedVariantName: String,
    )

    fun checkNonTestedVariant(variantName: String)
}

class AppVariantCreationConfigChecker(val plugin: AppPlugin) : VariantCreationConfigChecker {

    override val testComponents: Set<TestComponentCreationConfig>
        get() = plugin.variantManager.testComponents.toSet()

    override val mainVariants: Set<VariantCreationConfig>
        get() = plugin.variantManager.mainComponents.map { it.variant }.toSet()

    override fun checkTestedVariant(
        variantName: String,
        testedVariantName: String,
    ) {
        val requestedVariant = getVariant(mainVariants, variantName)
        val testComponent = getTestedVariant(requestedVariant)

        assertThat(testComponent).isNotNull()
        assertThat(testComponent?.name).isEqualTo(testedVariantName)
        testComponent?.onTestedVariant {
            checkTasks(it)
        }
    }

    override fun checkNonTestedVariant(
        variantName: String,
    ) {
        val requestedVariant = getVariant(mainVariants, variantName)
        checkTasks(requestedVariant)

        val testComponent = getTestedVariant(requestedVariant)
        assertThat(testComponent).isNull()
    }

    private fun getVariant(
        variants: Collection<VariantCreationConfig>,
        variantName: String,
    ): VariantCreationConfig {
        val requestedVariant = variants.find { it.name == variantName }
            ?: error("$variantName does not exist in the collection of variants provided.")
        return requestedVariant
    }

    private fun getTestedVariant(
        variant: VariantCreationConfig
    ): TestComponentCreationConfig? {
        return testComponents.find {
            it.onTestedVariant {
                it.name == variant.name
            }
        }
    }

    private fun checkTasks(variant: VariantCreationConfig) {
        assertThat(variant.taskContainer.mergeResourcesTask).isNotNull()
        assertThat(variant.taskContainer.aidlCompileTask).isNotNull()
        assertThat(variant.taskContainer.mergeAssetsTask).isNotNull()
        assertThat(variant.taskContainer.javacTask).isNotNull()
        assertThat(variant.taskContainer.processAndroidResTask).isNotNull()
        assertThat(variant.taskContainer.assembleTask).isNotNull()
        assertThat(variant.taskContainer.uninstallTask).isNotNull()
        assertThat(variant.taskContainer.processAndroidResTask).isNotNull()
        assertThat(variant.taskContainer.processManifestTask).isNotNull()
        if (variant is TestComponentCreationConfig) {
            assertThat(variant.taskContainer.connectedTestTask).isNotNull()
            variant.onTestedVariant {
                assertThat(it).isNotNull()
            }
        }
    }
}
