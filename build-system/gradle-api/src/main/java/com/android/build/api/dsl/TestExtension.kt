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

/**
 * Extension for the Android Test Gradle Plugin.
 *
 * This is the `android` block when the `com.android.test` plugin is applied.
 *
 * Only the Android Gradle Plugin should create instances of interfaces in com.android.build.api.dsl.
*/
interface TestExtension :
    CommonExtension<
            TestDefaultConfig,
            TestProductFlavor> {
    // TODO(b/140406102)

    /**
     * Specifies options related to the processing of Android Resources.
     *
     * For more information about the properties you can configure in this block, see [TestAndroidResources].
     */
    override val androidResources: TestAndroidResources

    /**
     * Specifies options related to the processing of Android Resources.
     *
     * For more information about the properties you can configure in this block, see [TestAndroidResources].
     */
    fun androidResources(action: TestAndroidResources.() -> Unit)

    /**
     * A list of build features that can be enabled or disabled on the Android Project.
     */
    override val buildFeatures: TestBuildFeatures

    /**
     * A list of build features that can be enabled or disabled on the Android Project.
     */
    fun buildFeatures(action: TestBuildFeatures.() -> Unit)

    /**
     * Encapsulates all build type configurations for this project.
     *
     * Unlike using [TestProductFlavor] to create
     * different versions of your project that you expect to co-exist on a single device, build
     * types determine how Gradle builds and packages each version of your project. Developers
     * typically use them to configure projects for various stages of a development lifecycle. For
     * example, when creating a new project from Android Studio, the Android plugin configures a
     * 'debug' and 'release' build type for you.
     * You can then combine build types with product flavors to
     * [create build variants](https://developer.android.com/studio/build/build-variants.html).
     *
     * @see BuildType
     */
    override val buildTypes: NamedDomainObjectContainer<out TestBuildType>

    /**
     * Encapsulates all build type configurations for this project.
     *
     * For more information about the properties you can configure in this block, see [TestBuildType]
     */
    fun buildTypes(action: NamedDomainObjectContainer<TestBuildType>.() -> Unit)

    /**
     * Shortcut extension method to allow easy access to the predefined `debug` [TestBuildType]
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
    fun NamedDomainObjectContainer<TestBuildType>.debug(action: TestBuildType.() -> Unit)
    /**
     * Shortcut extension method to allow easy access to the predefined `release` [TestBuildType]
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
    fun NamedDomainObjectContainer<TestBuildType>.release(action: TestBuildType.() -> Unit)

    /**
     * Specifies options for the
     * [Android Debug Bridge (ADB)](https://developer.android.com/studio/command-line/adb.html),
     * such as APK installation options.
     *
     * For more information about the properties you can configure in this block, see [AdbOptions].
     */
    override val installation: TestInstallation

    /**
     * Specifies options for the
     * [Android Debug Bridge (ADB)](https://developer.android.com/studio/command-line/adb.html),
     * such as APK installation options.
     *
     * For more information about the properties you can configure in this block, see [AdbOptions].
     */
    fun installation(action: TestInstallation.() -> Unit)

    /**
     * The Gradle path of the project that this test project tests.
     */
    var targetProjectPath: String?

    /** Options related to the consumption of privacy sandbox libraries */
    val privacySandbox: PrivacySandbox
    fun privacySandbox(action: PrivacySandbox.() -> Unit)
}
