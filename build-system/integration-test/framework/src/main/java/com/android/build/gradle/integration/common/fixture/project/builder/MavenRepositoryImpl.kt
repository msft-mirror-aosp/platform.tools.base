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

package com.android.build.gradle.integration.common.fixture.project.builder

import com.android.build.gradle.integration.common.dependencies.AarBuilder
import com.android.build.gradle.integration.common.dependencies.AarBuilderImpl
import com.android.build.gradle.integration.common.dependencies.JarBuilderImpl
import com.android.build.gradle.integration.common.dependencies.JarWithDependenciesBuilder
import com.android.build.gradle.integration.common.dependencies.JarWithDependenciesBuilderImpl
import com.android.testutils.MavenRepoGenerator.Library

internal class MavenRepositoryImpl: MavenRepository {
    private val libraryList = mutableListOf<Library>()

    private val aarBuilders = mutableListOf<AarBuilderImpl>()
    private val jarBuilders = mutableListOf<JarBuilderImpl>()

    internal val libraries: List<Library>
        get() {
            val result = mutableListOf<Library>()
            result += libraryList
            result += aarBuilders.map { it.toLibrary() }
            result += jarBuilders.map { it.toLibrary() }
            return result.toList()
        }

    override fun library(library: Library) {
        libraryList.add(library)
    }

    override fun jar(mavenCoordinate: String): JarWithDependenciesBuilder =
        JarWithDependenciesBuilderImpl(mavenCoordinate).also {
            jarBuilders.add(it)
        }

    override fun aar(groupId: String, artifactId: String?, version: String): AarBuilder =
        AarBuilderImpl(groupId, artifactId, version).also {
            aarBuilders.add(it)
        }
}
