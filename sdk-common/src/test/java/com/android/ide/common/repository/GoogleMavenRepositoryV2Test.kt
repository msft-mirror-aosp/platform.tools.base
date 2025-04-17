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
import java.util.function.Predicate

class GoogleMavenRepositoryV2Test : BaseTestCase() {

    private lateinit var mavenRepository: GoogleMavenRepositoryV2
    private val testGroupId = "test-group-id"
    private val testArtifactId = "test-artifact-id"
    private val testVersionId = "test-version-id"

    @Before
    fun setUp() {
        mavenRepository = GoogleMavenRepositoryV2.create()
    }

    @Test
    fun findVersionWithGroupIdArtifactIdAndPredicate_returnsNull() {
        assertThat(
            mavenRepository.findVersion(
                testGroupId,
                testArtifactId,
                Predicate { true })
        ).isNull()
    }

    @Test
    fun findVersionWithGroupIdArtifactIdAndFilter_returnsNull() {
        assertThat(mavenRepository.findVersion(testGroupId, testArtifactId, { true })).isNull()
    }

    @Test
    fun findCompileDependencies_returnsEmpty() {
        assertThat(
            mavenRepository.findCompileDependencies(
                testGroupId,
                testArtifactId,
                Version.parse(testVersionId)
            )
        ).isEmpty()
    }
}
