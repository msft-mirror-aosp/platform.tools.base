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

import com.android.SdkConstants.EXT_APP_BUNDLE
import com.android.utils.combineAsCamelCase

/**
 * Represents a bundle (AAB) location, based on parameters like build type, flavors, whether this
 * is a test, or whether the APK is signed.
 *
 * All these parameters influences the path to the Bundle.
 *
 * Creation of an instance can be done via [of] or by making a modified copy of an existing
 * instance, for example with [withFlavor]
 */
sealed interface AabSelector: OutputSelector {

    /** returns a new instance with the added flavor. */
    fun withFlavor(name: String): AabSelector

    /** returns a new instance with the new filter. If a filter already exist, it is replaced. */
    fun withFilter(newFilter: String): AabSelector

    /** returns a new instance with the added suffix. If a suffix already exist, it is replaced */
    fun withSuffix(newSuffix: String): AabSelector

    fun forTestSuite(name: String): AabSelector

    /** returns a new instance setup to represent APK in the intermediate folder */
    fun fromIntermediates(): AabSelector

    companion object {
        @JvmField
        val DEBUG = of(buildType = "debug", isSigned = true)

        @JvmField
        val RELEASE = of(buildType = "release", isSigned = false)

        @JvmField
        val RELEASE_SIGNED = of(buildType = "release", isSigned = true)

        @JvmField
        val NO_BUILD_TYPE = of(buildType = null, isSigned = false)

        @JvmField
        val NO_BUILD_TYPE_SIGNED = of(buildType = null, isSigned = true)

        @JvmField
        val ANDROIDTEST_DEBUG = of(buildType = "debug", testName = "androidTest", isSigned = true)

        @JvmStatic
        fun of(
            buildType: String?,
            isSigned: Boolean
        ): AabSelector {
            return AabSelectorImp(
                buildType = buildType,
                testSuite =  null,
                flavors = listOf(),
                isSigned = isSigned
            )
        }

        @JvmStatic
        fun of(
            buildType: String?,
            testName: String?,
            isSigned: Boolean
        ): AabSelector {
            return AabSelectorImp(
                buildType = buildType,
                testSuite =  testName,
                flavors = listOf(),
                isSigned = isSigned
            )
        }
    }
}

internal data class AabSelectorImp(
    private val buildType: String?,
    private val testSuite: String?,
    private val flavors: List<String>,
    private val isSigned: Boolean,
    private val filter: String? = null,
    private val suffix: String? = null,
    override val fromIntermediates: Boolean = false,
): AabSelector {

    override fun withFlavor(name: String): AabSelector =
        AabSelectorImp(buildType, testSuite, flavors + name, isSigned, filter, suffix, fromIntermediates)

    override fun withFilter(newFilter: String): AabSelector =
        AabSelectorImp(buildType, testSuite, flavors, isSigned, newFilter, suffix, fromIntermediates)

    override fun withSuffix(newSuffix: String): AabSelector =
        AabSelectorImp(buildType, testSuite, flavors, isSigned, filter, newSuffix, fromIntermediates)

    override fun forTestSuite(name: String): AabSelector =
        AabSelectorImp(buildType, name, flavors, isSigned, filter, suffix, fromIntermediates)

    override fun fromIntermediates(): AabSelector = AabSelectorImp(
        buildType, testSuite, flavors, isSigned, filter, suffix,
        fromIntermediates = true
    )

    override fun getFileName(projectName: String): String {
        val segments = mutableListOf<String>()

        segments.add(projectName)
        flavors.let { segments.addAll(it) }
        filter?.let { segments.add(it) }
        buildType?.let { segments.add(it) }
        testSuite?.let { segments.add(it) }
        suffix?.let { segments.add(it) }
        if (!isSigned) { segments.add("unsigned") }

        return segments.joinToString(separator = "-") + ".$EXT_APP_BUNDLE"
    }

    override fun getPath(): String {
        val pathBuilder = StringBuilder()

        // path always starts with this
        pathBuilder.append("bundle/")
        testSuite?.let {
            pathBuilder.append(it).append('/')
        }
        if (flavors.isNotEmpty()) {
            pathBuilder.append(flavors.combineAsCamelCase()).append('/')
        }
        buildType?.let {
            pathBuilder.append(it).append('/')
        }
        return pathBuilder.toString()
    }
}
