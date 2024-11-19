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

import com.android.SdkConstants.DOT_ANDROID_PACKAGE
import com.android.utils.combineAsCamelCase

/**
 * Represents an APK location, based on parameters like build type, flavors, whether this is a test,
 * or whether the APK is signed.
 *
 * All these parameters influences the path to the APK.
 *
 * Creation of an instance can be done via [of] or by making a modified copy of an existing
 * instance, for example with [withFlavor]
 */
interface ApkSelector: OutputSelector {

    val testName: String?
    val isSigned: Boolean
    val filter: String?
    val suffix: String?

    /** returns a new instance with the added flavor. */
    fun withFlavor(name: String): ApkSelector

    /** returns a new instance with the new filter. If a filter already exist, it is replaced. */
    fun withFilter(newFilter: String): ApkSelector

    /** returns a new instance with the added suffix. If a suffix already exist, it is replaced */
    fun withSuffix(newSuffix: String): ApkSelector

    fun forTestSuite(name: String): ApkSelector

    /** returns a new instance setup to represent APK in the intermediate folder */
    fun fromIntermediates(): ApkSelector

    companion object {
        @JvmField
        val DEBUG = of("debug", true)

        @JvmField
        val RELEASE = of("release", false)

        @JvmField
        val RELEASE_SIGNED = of("release", true)

        @JvmField
        val ANDROIDTEST_DEBUG = of("debug", "androidTest", true)

        @JvmStatic
        fun of(
            buildType: String,
            isSigned: Boolean
        ): ApkSelector {
            return ApkSelectorImp(
                buildType = buildType,
                testName =  null,
                flavors = listOf(),
                isSigned = isSigned
            )
        }

        @JvmStatic
        fun of(
            buildType: String,
            testName: String?,
            isSigned: Boolean
        ): ApkSelector {
            return ApkSelectorImp(
                buildType = buildType,
                testName =  testName,
                flavors = listOf(),
                isSigned = isSigned
            )
        }
    }
}

internal data class ApkSelectorImp(
    override val buildType: String,
    override val testName: String?,
    override val flavors: List<String>,
    override val isSigned: Boolean,
    override val filter: String? = null,
    override val suffix: String? = null,
    override val fromIntermediates: Boolean = false,
): ApkSelector {

    override fun withFlavor(name: String): ApkSelector =
        ApkSelectorImp(buildType, testName, flavors + name, isSigned, filter, suffix, fromIntermediates)

    override fun withFilter(newFilter: String): ApkSelector =
        ApkSelectorImp(buildType, testName, flavors, isSigned, newFilter, suffix, fromIntermediates)

    override fun withSuffix(newSuffix: String): ApkSelector =
        ApkSelectorImp(buildType, testName, flavors, isSigned, filter, newSuffix, fromIntermediates)

    override fun forTestSuite(name: String): ApkSelector =
        ApkSelectorImp(buildType, name, flavors, isSigned, filter, suffix, fromIntermediates)

    override fun fromIntermediates(): ApkSelector = ApkSelectorImp(
        buildType, testName, flavors, isSigned, filter, suffix,
        fromIntermediates = true
    )

    override fun getFileName(projectName: String): String {
        val segments = mutableListOf<String>()

        segments.add(projectName)
        flavors.let { segments.addAll(it) }
        filter?.let { segments.add(it) }
        buildType.let { segments.add(it) }
        testName?.let { segments.add(it) }
        suffix?.let { segments.add(it) }
        if (!isSigned) { segments.add("unsigned") }

        return segments.joinToString(separator = "-") + DOT_ANDROID_PACKAGE
    }

    override fun getPath(): String {
        val pathBuilder = StringBuilder()

        // path always starts with this
        pathBuilder.append("apk/")
        testName?.let {
            pathBuilder.append(it).append('/')
        }
        if (flavors.isNotEmpty()) {
            pathBuilder.append(flavors.combineAsCamelCase()).append('/')
        }

        pathBuilder.append(buildType).append('/')
        return pathBuilder.toString()
    }
}
