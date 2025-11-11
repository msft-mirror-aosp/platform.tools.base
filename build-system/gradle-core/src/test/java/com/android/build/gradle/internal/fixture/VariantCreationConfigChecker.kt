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

import com.android.build.gradle.internal.component.ApkCreationConfig
import com.android.build.gradle.internal.component.ComponentCreationConfig
import com.android.build.gradle.internal.component.TestComponentCreationConfig
import com.android.build.gradle.internal.component.TestCreationConfig
import com.android.build.gradle.internal.component.VariantCreationConfig
import com.android.build.gradle.internal.plugins.AppPlugin
import com.android.build.gradle.internal.plugins.BasePlugin
import com.android.build.gradle.internal.plugins.LibraryPlugin
import com.google.common.collect.Lists
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat

interface VariantCreationConfigChecker {

    val mainVariants: Set<VariantCreationConfig>
    val testComponents: Set<TestComponentCreationConfig>
    fun checkTestedVariant(
        variantName: String,
        testedVariantName: String,
        withMainVariant: ((VariantCreationConfig) -> Unit)? = null,
        withTestedVariant: ((VariantCreationConfig) -> Unit)? = null,
    )

    fun checkNonTestedVariant(
        variantName: String,
        withMainVariant: ((VariantCreationConfig) -> Unit)? = null,
    )
}

class CommonVariantCreationConfigChecker(val plugin: BasePlugin<*, *, *, *, *, *>) :
    VariantCreationConfigChecker {

    override val mainVariants: Set<VariantCreationConfig>
        get() = plugin.variantManager.mainComponents.map { it.variant }.toSet()

    override val testComponents: Set<TestComponentCreationConfig>
        get() = plugin.variantManager.testComponents.toSet()

    override fun checkTestedVariant(
        variantName: String,
        testedVariantName: String,
        withMainVariant: ((VariantCreationConfig) -> Unit)?,
        withTestedVariant: ((VariantCreationConfig) -> Unit)?,
    ) {
        variant(variantName) { requestedVariant ->
            checkTasks(requestedVariant)
            withMainVariant?.invoke(requestedVariant)

            testedComponent(requestedVariant) { testComponent ->
                assertThat(testComponent).isNotNull()
                assertThat(testComponent?.name).isEqualTo(testedVariantName)
                testComponent?.onTestedVariant {
                    checkTasks(it)
                    withTestedVariant?.invoke(it)
                }
            }
        }
    }

    override fun checkNonTestedVariant(
        variantName: String,
        withMainVariant: ((VariantCreationConfig) -> Unit)?,
    ) {
        variant(variantName) { requestedVariant ->
            checkTasks(requestedVariant)
            withMainVariant?.invoke(requestedVariant)

            testedComponent(requestedVariant) {
                assertThat(it).isNull()
            }
        }
    }

    private fun checkTasks(variant: VariantCreationConfig) {
        with (variant.taskContainer) {
            assertThat(aidlCompileTask).isNotNull()
            assertThat(mergeResourcesTask).isNotNull()
            assertThat(javacTask).isNotNull()
            assertThat(processJavaResourcesTask).isNotNull()
            if (variant !is TestComponentCreationConfig) {
                assertThat(assembleTask).isNotNull()
            }
        }
    }

    private fun variant(
        variantName: String,
        withVariant: (VariantCreationConfig) -> Unit,
    ) {
        val requestedVariant = mainVariants.find { it.name == variantName }
        assertThat(requestedVariant).isNotNull()
        withVariant(requestedVariant ?: error("Variant $variantName not found."))
    }

    private fun testedComponent(
        variant: VariantCreationConfig,
        withTestedComponent: (TestComponentCreationConfig?) -> Unit,
    ) {
        val maybeTestComponent = testComponents.find {
            it.onTestedVariant {
                it.name == variant.name
            }
        }
        withTestedComponent(maybeTestComponent)
    }
}

class AppVariantCreationConfigChecker private constructor(val checker: CommonVariantCreationConfigChecker) :
    VariantCreationConfigChecker by checker {

    constructor(plugin: AppPlugin) : this(CommonVariantCreationConfigChecker(plugin))

    override fun checkTestedVariant(
        variantName: String,
        testedVariantName: String,
        withMainVariant: ((VariantCreationConfig) -> Unit)?,
        withTestedVariant: ((VariantCreationConfig) -> Unit)?,
    ) {
        return checker.checkTestedVariant(
            variantName, testedVariantName,
            {
                checkTasks(it)
                withMainVariant?.invoke(it)
            },
            {
                checkTasks(it)
                withTestedVariant?.invoke(it)
            }
        )
    }

    override fun checkNonTestedVariant(
        variantName: String,
        withMainVariant: ((VariantCreationConfig) -> Unit)?,
    ) {
        return checker.checkNonTestedVariant(variantName) {
            checkTasks(it)
            withMainVariant?.invoke(it)
        }
    }

    private fun checkTasks(variant: VariantCreationConfig) {
        with(variant.taskContainer) {
            assertThat(uninstallTask).isNotNull()
            assertThat(packageAndroidTask).isNotNull()
            assertThat(mergeAssetsTask).isNotNull()
            assertThat(packageAndroidTask).isNotNull()
            if (variant is TestComponentCreationConfig) {
                assertThat(connectedTestTask).isNotNull()
                variant.onTestedVariant {
                    assertThat(it).isNotNull()
                }
            }
        }
    }
}

class LibraryVariantCreationConfigChecker private constructor(val checker: CommonVariantCreationConfigChecker) :
    VariantCreationConfigChecker by checker {

    constructor(plugin: LibraryPlugin) : this(CommonVariantCreationConfigChecker(plugin))

    override fun checkNonTestedVariant(
        variantName: String,
        withMainVariant: ((VariantCreationConfig) -> Unit)?,
    ) {
        return checker.checkNonTestedVariant(variantName) {
            checkTasks(it)
            withMainVariant?.invoke(it)
        }
    }

    private fun checkTasks(variant: VariantCreationConfig) {
        with(variant.taskContainer) {
            assertThat(checkManifestTask).isNotNull()
            if (variant is TestCreationConfig) {
                if (variant is ApkCreationConfig &&
                    variant.signingConfig?.isSigningReady() == true) {
                    assertThat(installTask).isNotNull()
                } else {
                    assertThat(installTask).isNull()
                }
                assertThat(variant.instrumentationCreationConfig).isNotNull()
            }
        }
    }
}

fun countVariants(variants: MutableMap<String?, Int?>): Int {
    return variants.values.filterNotNull().sum()
}

fun checkDefaultVariants(components: MutableList<ComponentCreationConfig?>) {
    Truth.assertThat(
        Lists.transform<ComponentCreationConfig?, String>(
            components,
            ComponentCreationConfig::name
        )
    ).containsExactly("release", "debug", "debugAndroidTest", "debugUnitTest")
}

/**
 * Returns the component with the given name. Fails if there is no such variant.
 *
 * @param components the item collection to search for a match
 * @param name the name of the item to return
 * @return the found variant
 */
fun findComponent(
    components: MutableCollection<ComponentCreationConfig?>, name: String,
): ComponentCreationConfig {
    val result =
        components.find { it!!.name == name }
    return result ?: throw AssertionError("Component for $name not found.")
}
