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
package com.android.ide.common.repository

import com.android.ide.common.gradle.Version
import com.android.ide.common.resources.BaseTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.nio.file.Path
import java.util.function.Predicate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoogleMavenRepositoryV2Test : BaseTestCase() {

    private lateinit var mavenRepository: GoogleMavenRepositoryV2

    @Before
    fun setUp() {
        mavenRepository = GoogleMavenRepositoryV2.create(FakeGoogleMavenRepositoryV2Host())
    }

    @Test
    fun findVersion_withOfflineVersions_returnsVersion() {
        val offlineMavenRepository =
            GoogleMavenRepositoryV2.create(object : GoogleMavenRepositoryV2Host {
                override val cacheDir: Path? = null

                override fun readUrlData(
                    url: String,
                    timeout: Int,
                    lastModified: Long
                ): NetworkCache.ReadUrlDataResult =
                    throw IllegalStateException("Should not be called")

                override fun error(throwable: Throwable, message: String?) {}
            })

        assertNotNull(
            offlineMavenRepository.findVersion(
                "android.arch.core",
                "core-testing",
                null as Predicate<Version>?
            )
        )
    }

    @Test
    fun findVersion_withNullPredicate_returnsVersion() {
        assertEquals(
            mavenRepository.findVersion(
                "com.android.support",
                "appcompat",
                null as Predicate<Version>?
            ),
            Version.parse("1.0.0")
        )
    }

    @Test
    fun findVersion_withGroupIdArtifactIdAndPredicate_returnsVersion() {
        assertEquals(
            mavenRepository.findVersion(
                "com.android.support",
                "appcompat",
                Predicate { true }),
            Version.parse("1.0.0")
        )
    }

    @Test
    fun findVersion_withMissingGroup_returnsNull() {
        assertNull(
            mavenRepository.findVersion(
                "com.android.missing",
                "appcompat",
                { true }
            )
        )
    }

    @Test
    fun findVersion_withMissingArtifact_returnsNull() {
        assertNull(
            mavenRepository.findVersion(
                "com.android.support",
                "missing",
                { true }
            )
        )
    }

    @Test
    fun findVersion_withNullFilter_returnsVersion() {
        assertEquals(
            mavenRepository.findVersion(
                "com.android.support",
                "appcompat",
                null as ((Version) -> Boolean)?
            ),
            Version.parse("1.0.0")
        )
    }

    @Test
    fun findVersion_withAllowPreview_returnsPreviewVersion() {
        assertEquals(
            mavenRepository.findVersion(
                "com.android.support",
                "appcompat",
                null as ((Version) -> Boolean)?,
                true
            ),
            Version.parse("1.0.1-preview")
        )
    }

    @Test
    fun findVersion_withGroupIdArtifactIdAndFilter_returnsVersion() {
        assertEquals(
            mavenRepository.findVersion("com.android.support", "appcompat", { true }),
            Version.parse("1.0.0")
        )
    }

    @Test
    fun findCompileDependencies_returnsEmpty() {
        assertThat(
            mavenRepository.findCompileDependencies(
                "com.android.support",
                "appcompat",
                Version.parse("1.0.0")
            )
        ).isEmpty()
    }
}
