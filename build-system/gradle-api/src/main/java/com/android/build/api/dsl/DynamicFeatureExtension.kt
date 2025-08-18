/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.api.dsl

/**
 * Extension for the Android Dynamic Feature Gradle Plugin.
 *
 * This is the `android` block when the `com.android.dynamic-feature` plugin is applied.
 *
 * Only the Android Gradle Plugin should create instances of interfaces in com.android.build.api.dsl.
 */
interface DynamicFeatureExtension :
    CommonExtension<
            DynamicFeatureBuildType,
            DynamicFeatureDefaultConfig,
            DynamicFeatureProductFlavor,
            DynamicFeatureInstallation>,
    ApkExtension,
    TestedExtension {

    /**
     * Specifies options related to the processing of Android Resources.
     *
     * For more information about the properties you can configure in this block, see [DynamicFeatureAndroidResources].
     */
    override val androidResources: DynamicFeatureAndroidResources
    /**
     * Specifies options related to the processing of Android Resources.
     *
     * For more information about the properties you can configure in this block, see [DynamicFeatureAndroidResources].
     */
    fun androidResources(action: DynamicFeatureAndroidResources.() -> Unit)

    /**
     * A list of build features that can be enabled or disabled on the Android Project.
     */
    override val buildFeatures: DynamicFeatureBuildFeatures

    /**
     * A list of build features that can be enabled or disabled on the Android Project.
     */
    fun buildFeatures(action: DynamicFeatureBuildFeatures.() -> Unit)

    val privacySandbox: PrivacySandbox
    fun privacySandbox(action: PrivacySandbox.() -> Unit)
}
