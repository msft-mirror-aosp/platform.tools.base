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

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.declarative.dsl.model.annotations.Configuring

/**
 * Extension for the Android Gradle Plugin Application plugin.
 *
 * This is the `android` block when the `com.android.application` plugin is applied.
 *
 * Only the Android Gradle Plugin should create instances of interfaces in com.android.build.api.dsl.
 */
interface ApplicationExtension :
    CommonExtension<
            ApplicationDefaultConfig,
            ApplicationProductFlavor>,
    ApkExtension,
    TestedExtension {
    // TODO(b/140406102)

    /** Specify whether to include SDK dependency information in APKs and Bundles. */
    val dependenciesInfo: DependenciesInfo

    /** Specify whether to include SDK dependency information in APKs and Bundles. */
    fun dependenciesInfo(action: DependenciesInfo.() -> Unit)

    /**
     * Encapsulates all build type configurations for this project.
     *
     * Unlike using [ApplicationProductFlavor] to create
     * different versions of your project that you expect to co-exist on a single device, build
     * types determine how Gradle builds and packages each version of your project. Developers
     * typically use them to configure projects for various stages of a development lifecycle. For
     * example, when creating a new project from Android Studio, the Android plugin configures a
     * 'debug' and 'release' build type for you. By default, the 'debug' build type enables
     * debugging options and signs your APK with a generic debug keystore. Conversely, The 'release'
     * build type strips out debug symbols and requires you to
     * [create a release key and keystore](https://developer.android.com/studio/publish/app-signing.html#sign-apk)
     * for your app. You can then combine build types with product flavors to
     * [create build variants](https://developer.android.com/studio/build/build-variants.html).
     *
     * @see BuildType
     */
    override val buildTypes: NamedDomainObjectContainer<out ApplicationBuildType>

    /**
     * Encapsulates all build type configurations for this project.
     *
     * For more information about the properties you can configure in this block, see [ApplicationBuildType]
     */
    fun buildTypes(action: NamedDomainObjectContainer<ApplicationBuildType>.() -> Unit)

    /**
     * Shortcut extension method to allow easy access to the predefined `debug` [ApplicationBuildType]
     *
     * For example:
     * ```
     *  android {
     *      buildTypes {
     *          debug {
     *              // ...
     *          }
     *      }
     * }
     * ```
     */
    fun NamedDomainObjectContainer<ApplicationBuildType>.debug(action: ApplicationBuildType.() -> Unit)
    /**
     * Shortcut extension method to allow easy access to the predefined `release` [ApplicationBuildType]
     *
     * For example:
     * ```
     *  android {
     *      buildTypes {
     *          release {
     *              // ...
     *          }
     *      }
     * }
     * ```
     */
    fun NamedDomainObjectContainer<ApplicationBuildType>.release(action: ApplicationBuildType.() -> Unit)


    val bundle: Bundle

    @Configuring
    fun bundle(action: Bundle.() -> Unit)

    val dynamicFeatures: MutableSet<String>

    /**
     * Set of asset pack subprojects to be included in the app's bundle.
     */
    val assetPacks: MutableSet<String>

    /**
     * Customizes publishing build variant artifacts from app module to a Maven repository.
     *
     * For more information about the properties you can configure in this block, see [ApplicationPublishing]
     */
    val publishing: ApplicationPublishing

    /**
     * Customizes publishing build variant artifacts from app module to a Maven repository.
     *
     * For more information about the properties you can configure in this block, see [ApplicationPublishing]
     */
    fun publishing(action: ApplicationPublishing.() -> Unit)

    /**
     * Specifies options related to the processing of Android Resources.
     *
     * For more information about the properties you can configure in this block, see [ApplicationAndroidResources].
     */
    override val androidResources: ApplicationAndroidResources

    /**
     * Specifies options related to the processing of Android Resources.
     *
     * For more information about the properties you can configure in this block, see [ApplicationAndroidResources].
     */
    fun androidResources(action: ApplicationAndroidResources.() -> Unit)

    /**
     * A list of build features that can be enabled or disabled on the Android Project.
     */
    override val buildFeatures: ApplicationBuildFeatures

    /**
     * A list of build features that can be enabled or disabled on the Android Project.
     */
    fun buildFeatures(action: ApplicationBuildFeatures.() -> Unit)

    /**
     * Specifies options for the
     * [Android Debug Bridge (ADB)](https://developer.android.com/studio/command-line/adb.html),
     * such as APK installation options.
     *
     * For more information about the properties you can configure in this block, see [AdbOptions].
     */
    override val installation: ApplicationInstallation

    /**
     * Specifies options for the
     * [Android Debug Bridge (ADB)](https://developer.android.com/studio/command-line/adb.html),
     * such as APK installation options.
     *
     * For more information about the properties you can configure in this block, see [AdbOptions].
     */
    fun installation(action: ApplicationInstallation.() -> Unit)

    /** Options related to the consumption of privacy sandbox libraries */
    val privacySandbox: PrivacySandbox
    fun privacySandbox(action: PrivacySandbox.() -> Unit)
}
