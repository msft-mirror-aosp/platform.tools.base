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

package com.android.build.gradle.integration.model

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.model.ReferenceModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.utils.disableBuiltInKotlin
import com.android.builder.model.v2.ide.SyncIssue
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class FlavoredAppModelTest: ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                flavorDimensions += listOf("model")
                productFlavors {
                    create("basic") { it.dimension = "model" }
                    create("pro") { it.dimension = "model" }
                }
            }
            pluginCallbacks += DisableSomeVariantCallback::class.java
        }
        disableBuiltInKotlin()
    }

    class DisableSomeVariantCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                beforeVariants(selector().withName("basicDebug")) {
                    it.enable = false
                }
                beforeVariants(selector().withName("basicRelease")) {
                    it.enable = false
                }
                beforeVariants(selector().withName("proRelease")) {
                    it.enable = false
                }
            }
        }
    }

    @Test
    fun `test models`() {
        val result = rule.build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        with(result).compareBasicAndroidProject(goldenFile = "BasicAndroidProject")
        with(result).compareAndroidProject(goldenFile = "AndroidProject")
        with(result).compareAndroidDsl(goldenFile = "AndroidDsl")
    }
}

class MultiFlavoredAppModelTest: ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                flavorDimensions += listOf("model", "market")
                productFlavors {
                    create("basic") { it.dimension = "model" }
                    create("pro") { it.dimension = "model" }
                    create("play") { it.dimension = "market" }
                    create("other") { it.dimension = "market" }
                }
            }
            pluginCallbacks += DisableBunchOfVariantCallback::class.java
        }
        disableBuiltInKotlin()
    }

    class DisableBunchOfVariantCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                // disable all but proPlayDebug
                beforeVariants(selector().withBuildType("release")) {
                    it.enable = false
                }
                beforeVariants(selector().withName("basicPlayDebug")) {
                    it.enable = false
                }
                beforeVariants(selector().withName("proOtherDebug")) {
                    it.enable = false
                }
                beforeVariants(selector().withName("basicOtherDebug")) {
                    it.enable = false
                }
            }
        }
    }

    @Test
    fun `test models`() {
        val result = rule.build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        with(result).compareBasicAndroidProject(goldenFile = "BasicAndroidProject")
        with(result).compareAndroidProject(goldenFile = "AndroidProject")
        with(result).compareAndroidDsl(goldenFile = "AndroidDsl")
    }
}

/**
 * This test disables the debug variant.
 */
class DisabledVariantInAppModelTest: ReferenceModelComparator(
    referenceConfig = {
        androidApplication { }
    },
    deltaConfig = {
        androidApplication {
            pluginCallbacks += DisableDebugVariantCallback::class.java
        }
    },
    syncOptions = {
        ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
    }
) {
    class DisableDebugVariantCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                beforeVariants(selector().withBuildType("debug")) {
                    it.enable = false
                }
            }
        }
    }

    @Test
    fun `test BasicAndroidProject model`() {
        compareBasicAndroidProjectWith(goldenFileSuffix = "BasicAndroidProject")
    }

    @Test
    fun `test AndroidProject model`() {
        compareAndroidProjectWith(goldenFileSuffix = "AndroidProject")
    }

    @Test
    fun `test AndroidDsl model`() {
        ensureAndroidDslDeltaIsEmpty()
    }
}

/**
 * This test disables the only AndroidTest component of the project (debug)
 */
class DisabledAndroidTestInAppModelTest: ReferenceModelComparator(
    referenceConfig = {
        androidApplication { }
    },
    deltaConfig = {
        androidApplication {
            pluginCallbacks += DisableDebugAndroidTestCallback::class.java
        }
    },
    syncOptions = {
        ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
    }
) {
    class DisableDebugAndroidTestCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                beforeVariants(selector().withName("debug")) {
                    it.androidTest.enable = false
                }
            }
        }
    }

    @Test
    fun `test BasicAndroidProject model`() {
        compareBasicAndroidProjectWith(goldenFileSuffix = "BasicAndroidProject")
    }

    @Test
    fun `test AndroidProject model`() {
        compareAndroidProjectWith(goldenFileSuffix = "AndroidProject")
    }

    @Test
    fun `test AndroidDsl model`() {
        ensureAndroidDslDeltaIsEmpty()
    }
}

/**
 * This test disables the debug UnitTest component of the project (There is still the release
 * one)
 */
class DisabledUnitTestInAppModelTest: ReferenceModelComparator(
    referenceConfig = {
        androidApplication {
        }
    },
    deltaConfig = {
        androidApplication {
            pluginCallbacks += DisableDebugUnitTestCallback::class.java
        }
    },
    syncOptions = {
        ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
    }
) {
    class DisableDebugUnitTestCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                beforeVariants(selector().withName("debug")) {
                    it.hostTests.values.first().enable = false
                }
            }
        }
    }

    @Test
    fun `test BasicAndroidProject model`() {
        compareBasicAndroidProjectWith(goldenFileSuffix = "BasicAndroidProject")
    }

    @Test
    fun `test AndroidProject model`() {
        compareAndroidProjectWith(goldenFileSuffix = "AndroidProject")
    }

    @Test
    fun `test AndroidDsl model`() {
        ensureAndroidDslDeltaIsEmpty()
    }
}

/**
 * This test disables a single variant in a 4 variant setup (2 build types, 2 flavors)
 */
class DisabledSingleVariantInFlavorAppModelTest: ReferenceModelComparator(
    referenceConfig = {
        androidApplication {
            android {
                flavorDimensions += "foo"
                productFlavors {
                    create("one") {
                        it.dimension = "foo"
                    }
                    create("two") {
                        it.dimension = "foo"
                    }
                }
            }
        }
    },
    deltaConfig = {
        androidApplication {
            pluginCallbacks += DisableOneDebugVariantCallback::class.java
        }
    },
    syncOptions = {
        ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
    }
) {
    class DisableOneDebugVariantCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                beforeVariants(selector().withName("oneDebug")) {
                    it.enable = false
                }
            }
        }
    }

    @Test
    fun `test BasicAndroidProject model`() {
        compareBasicAndroidProjectWith(goldenFileSuffix = "BasicAndroidProject")
    }

    @Test
    fun `test AndroidProject model`() {
        compareAndroidProjectWith(goldenFileSuffix = "AndroidProject")
    }

    @Test
    fun `test AndroidDsl model`() {
        ensureAndroidDslDeltaIsEmpty()
    }
}

/**
 * This test disables  variants in a 4 variant setup (2 build types, 2 flavors) by the build type
 * (ie all variant of build type debug)
 */
class DisabledVariantByBuildTypeInFlavorAppModelTest: ReferenceModelComparator(
    referenceConfig = {
        androidApplication {
            android {
                flavorDimensions += "foo"
                productFlavors {
                    create("one") {
                        it.dimension = "foo"
                    }
                    create("two") {
                        it.dimension = "foo"
                    }
                }
            }
        }
    },
    deltaConfig = {
        androidApplication {
            pluginCallbacks += DisableAllDebugVariantsCallback::class.java
        }
    },
    syncOptions = {
        ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
    }
) {
    class DisableAllDebugVariantsCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                beforeVariants(selector().withBuildType("debug")) {
                    it.enable = false
                }
            }
        }
    }

    @Test
    fun `test BasicAndroidProject model`() {
        compareBasicAndroidProjectWith(goldenFileSuffix = "BasicAndroidProject")
    }

    @Test
    fun `test AndroidProject model`() {
        compareAndroidProjectWith(goldenFileSuffix = "AndroidProject")
    }

    @Test
    fun `test AndroidDsl model`() {
        ensureAndroidDslDeltaIsEmpty()
    }
}

/**
 * This test disables  variants in a 4 variant setup (2 build types, 2 flavors) by the flavor
 * (ie all variant of flavor "one")
 */
class DisabledVariantByFlavorInFlavorAppModelTest: ReferenceModelComparator(
    referenceConfig = {
        androidApplication {
            android {
                flavorDimensions += "foo"
                productFlavors {
                    create("one") {
                        it.dimension = "foo"
                    }
                    create("two") {
                        it.dimension = "foo"
                    }
                }
            }
        }
    },
    deltaConfig = {
        androidApplication {
            pluginCallbacks += DisableAllOneVariantsCallback::class.java
        }
    },
    syncOptions = {
        ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
    }
) {
    class DisableAllOneVariantsCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                beforeVariants(selector().withFlavor("foo" to "one")) {
                    it.enable = false
                }
            }
        }
    }

    @Test
    fun `test BasicAndroidProject model`() {
        compareBasicAndroidProjectWith(goldenFileSuffix = "BasicAndroidProject")
    }

    @Test
    fun `test AndroidProject model`() {
        compareAndroidProjectWith(goldenFileSuffix = "AndroidProject")
    }

    @Test
    fun `test AndroidDsl model`() {
        ensureAndroidDslDeltaIsEmpty()
    }
}

class NoVariantModelTest: ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            pluginCallbacks += DisableAllVariantsCallback::class.java
        }
    }

    class DisableAllVariantsCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                beforeVariants(selector().all()) {
                    it.enable = false
                }
            }
        }
    }

    @Test
    fun `test models`() {
        val result = rule.build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels()

        with(result).compareBasicAndroidProject(goldenFile = "BasicAndroidProject")
        with(result).compareAndroidProject(goldenFile = "AndroidProject")
        with(result).compareAndroidDsl(goldenFile = "AndroidDsl")
    }
}
