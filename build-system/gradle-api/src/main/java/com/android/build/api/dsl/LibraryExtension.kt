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

import org.gradle.api.Incubating
import org.gradle.api.NamedDomainObjectContainer

/**
 * Extension for the Android Library Gradle Plugin.
 *
 * This is the `android` block when the `com.android.library` plugin is applied.
 *
 * Only the Android Gradle Plugin should create instances of interfaces in com.android.build.api.dsl.
 */
interface LibraryExtension :
    CommonExtension<
        LibraryDefaultConfig,
        LibraryProductFlavor>,
    TestedExtension {
    // TODO(b/140406102)

    /** Aidl files to package in the aar. */
    @get:Incubating
    val aidlPackagedList: MutableCollection<String>?

    /**
     * Specifies options related to the processing of Android Resources.
     *
     * For more information about the properties you can configure in this block, see [LibraryAndroidResources].
     */
    override val androidResources: LibraryAndroidResources

    /**
     * Specifies options related to the processing of Android Resources.
     *
     * For more information about the properties you can configure in this block, see [LibraryAndroidResources].
     */
    fun androidResources(action: LibraryAndroidResources.() -> Unit)

    /**
     * A list of build features that can be enabled or disabled on the Android Project.
     */
    override val buildFeatures: LibraryBuildFeatures

    /**
     * A list of build features that can be enabled or disabled on the Android Project.
     */
    fun buildFeatures(action: LibraryBuildFeatures.() -> Unit)



    /**
     * Encapsulates all build type configurations for this project.
     *
     * Unlike using [LibraryProductFlavor] to create
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
    override val buildTypes: NamedDomainObjectContainer<out LibraryBuildType>

    /**
     * Encapsulates all build type configurations for this project.
     *
     * For more information about the properties you can configure in this block, see [LibraryBuildType]
     */
    fun buildTypes(action: NamedDomainObjectContainer<LibraryBuildType>.() -> Unit)

    /**
     * Shortcut extension method to allow easy access to the predefined `debug` [LibraryBuildType]
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
    fun NamedDomainObjectContainer<LibraryBuildType>.debug(action: LibraryBuildType.() -> Unit)
    /**
     * Shortcut extension method to allow easy access to the predefined `release` [LibraryBuildType]
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
    fun NamedDomainObjectContainer<LibraryBuildType>.release(action: LibraryBuildType.() -> Unit)


    /**
     * Specifies options for the
     * [Android Debug Bridge (ADB)](https://developer.android.com/studio/command-line/adb.html),
     * such as APK installation options.
     *
     * For more information about the properties you can configure in this block, see [AdbOptions].
     */
    override val installation: LibraryInstallation

    /**
     * Specifies options for the
     * [Android Debug Bridge (ADB)](https://developer.android.com/studio/command-line/adb.html),
     * such as APK installation options.
     *
     * For more information about the properties you can configure in this block, see [AdbOptions].
     */
    fun installation(action: LibraryInstallation.() -> Unit)

    /**
     * container of Prefab options
     */
    @get:Incubating
    val prefab: NamedDomainObjectContainer<Prefab>

    /**
     * Customizes publishing build variant artifacts from library module to a Maven repository.
     *
     * For more information about the properties you can configure in this block, see [LibraryPublishing]
     */
    val publishing: LibraryPublishing

    /**
     * Customizes publishing build variant artifacts from library module to a Maven repository.
     *
     * For more information about the properties you can configure in this block, see [LibraryPublishing]
     */
    fun publishing(action: LibraryPublishing.() -> Unit)

    val privacySandbox: PrivacySandbox
    fun privacySandbox(action: PrivacySandbox.() -> Unit)
}
