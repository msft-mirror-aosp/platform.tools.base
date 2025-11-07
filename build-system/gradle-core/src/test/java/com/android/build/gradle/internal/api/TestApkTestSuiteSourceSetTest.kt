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

package com.android.build.gradle.internal.api

import com.android.build.api.dsl.AgpTestSuiteDependencies
import com.android.build.gradle.internal.services.VariantServices
import com.android.build.gradle.internal.services.VariantServicesImpl
import com.android.build.gradle.internal.services.createProjectServices
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.quality.Strictness

class TestApkTestSuiteSourceSetTest {
    @get:Rule
    val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    @Mock
    lateinit var dependencies: AgpTestSuiteDependencies

    lateinit var variantServices: VariantServices
    lateinit var project: Project

    @Before
    fun init() {
        project = ProjectBuilder.builder()
            .withProjectDir(tmpFolder.newFolder())
            .build()
        variantServices = VariantServicesImpl(
            createProjectServices(project),
            true
        )
    }

    @Test
    fun testKotlinDisabled() {
        val sourceSet = TestApkTestSuiteSourceSet(
            sourceSetName = "test",
            variantServices = variantServices,
            userAddedSourceSets = emptyList(),
            javaEnabled = true,
            kotlinEnabled = false,
            dependencies
        )
        Truth.assertThat(sourceSet.kotlin).isNull()
    }

    @Test
    fun testJavaDisabled() {
        val sourceSet = TestApkTestSuiteSourceSet(
            sourceSetName = "test",
            variantServices = variantServices,
            userAddedSourceSets = emptyList(),
            javaEnabled = false,
            kotlinEnabled = true,
            dependencies
        )
        Truth.assertThat(sourceSet.java).isNull()
    }

    @Test
    fun testDefaultCase() {
        val sourceSet = TestApkTestSuiteSourceSet(
            sourceSetName = "test",
            variantServices = variantServices,
            userAddedSourceSets = emptyList(),
            javaEnabled = true,
            kotlinEnabled = true,
            dependencies
        )
        Truth.assertThat(sourceSet.kotlin).isNotNull()
        Truth.assertThat(sourceSet.java).isNotNull()
    }

    @Test
    fun testDefaultSourceSet() {
        val sourceSet = TestApkTestSuiteSourceSet(
            sourceSetName = "test",
            variantServices = variantServices,
            userAddedSourceSets = emptyList(),
            javaEnabled = true,
            kotlinEnabled = true,
            dependencies
        )
        Truth.assertThat(sourceSet.kotlin?.all?.get()).containsExactly(
            project.layout.projectDirectory.dir("src/test/kotlin")
        )
        Truth.assertThat(sourceSet.java?.all?.get()).containsExactly(
            project.layout.projectDirectory.dir("src/test/java")
        )
        Truth.assertThat(sourceSet.resources.all.get()).containsExactly(
            project.layout.projectDirectory.dir("src/test/resources")
        )
    }

    @Test
    fun testUserAddedSourceSet() {
        val userAddedSourceSet = tmpFolder.newFolder("userAdded")
        val dir = project.layout.projectDirectory.dir(userAddedSourceSet.absolutePath)
        val sourceSet = TestApkTestSuiteSourceSet(
            sourceSetName = "test",
            variantServices = variantServices,
            userAddedSourceSets = listOf(dir),
            javaEnabled = true,
            kotlinEnabled = true,
            dependencies
        )
        Truth.assertThat(sourceSet.kotlin?.all?.get()).containsExactly(
            project.layout.projectDirectory.dir("src/test/kotlin"),
            dir.dir("kotlin")
        )
        Truth.assertThat(sourceSet.java?.all?.get()).containsExactly(
            project.layout.projectDirectory.dir("src/test/java"),
            dir.dir("java")
        )
        Truth.assertThat(sourceSet.resources.all.get()).containsExactly(
            project.layout.projectDirectory.dir("src/test/resources"),
            dir.dir("resources")
        )
    }
}
