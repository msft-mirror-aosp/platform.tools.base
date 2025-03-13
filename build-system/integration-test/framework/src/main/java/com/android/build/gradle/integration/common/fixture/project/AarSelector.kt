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

import com.android.SdkConstants.DOT_AAR

/**
 * Represents an AAR location, based on parameters like build type, and flavors.
 *
 * All these parameters influences the path to the AAR.
 *
 * Creation of an instance can be done via [of] or by making a modified copy of an existing
 * instance, for example with [withFlavor]
 */
sealed interface AarSelector: OutputSelector {

    /** Returns a new instance with a new project name */
    override fun withName(name: String): AarSelector

    /** returns a new instance with the added flavor. */
    fun withFlavor(name: String): AarSelector

    /** returns a new instance with the new filter. If a filter already exist, it is replaced. */
    fun withFilter(newFilter: String): AarSelector

    /** returns a new instance with the added suffix. If a suffix already exist, it is replaced */
    fun withSuffix(newSuffix: String): AarSelector

    /**
     * returns a new instance of the selector, targeting the test fixture output.
     * This is the same as using withSuffix("testFixtures")
     */
    fun forTestFixtures(): AarSelector

    companion object {
        @JvmField
        val DEBUG = of(buildType = "debug")

        @JvmField
        val RELEASE = of(buildType = "release")

        @JvmField
        val NO_BUILD_TYPE = of(buildType = null)

        @JvmStatic
        fun of(
            buildType: String?,
        ): AarSelector {
            return AarSelectorImp(
                buildType = buildType,
                flavors = listOf(),
            )
        }
    }
}

internal data class AarSelectorImp(
    override val name: String? = null,
    private val buildType: String?,
    private val flavors: List<String>,
    private val filter: String? = null,
    private val suffix: String? = null,
): AarSelector {

    override fun withName(name: String): AarSelector =
        AarSelectorImp(name, buildType, flavors, filter, suffix)

    override fun withFlavor(name: String): AarSelector =
        AarSelectorImp(this.name, buildType, flavors + name, filter, suffix)

    override fun withFilter(newFilter: String): AarSelector =
        AarSelectorImp(this.name, buildType, flavors, newFilter, suffix)

    override fun withSuffix(newSuffix: String): AarSelector =
        AarSelectorImp(this.name, buildType, flavors, filter, newSuffix)

    override fun forTestFixtures(): AarSelector {
        return AarSelectorImp(this.name, buildType, flavors, filter, "testFixtures")
    }

    override fun getFileName(projectName: String): String {
        val segments = mutableListOf<String>()

        segments.add(projectName)
        flavors.let { segments.addAll(it) }
        filter?.let { segments.add(it) }
        buildType?.let { segments.add(it) }
        suffix?.let { segments.add(it) }

        return segments.joinToString(separator = "-") + DOT_AAR
    }

    override fun getPath(): String {
        // there are no dimension-related segments in the AAR path (unlike APK)
        return "aar/"
    }
}
