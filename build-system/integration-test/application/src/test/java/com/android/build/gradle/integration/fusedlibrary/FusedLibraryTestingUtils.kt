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

package com.android.build.gradle.integration.fusedlibrary

import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.internal.impldep.org.apache.maven.model.io.xpp3.MavenXpp3Reader

object FusedLibraryTestConstants {
    const val FUSED_LIBRARY_GROUP = "my-company"
    const val FUSED_LIBRARY_ARTIFACT_NAME = "my-fused-library"
    const val FUSED_LIBRARY_VERSION = "1.0"
    const val FUSED_LIBRARY_REPO_NAME = "repo"
}

fun assertExpectedPomDependencies(pom: Path, dependencies: List<String>) {
    Truth.assertThat(pom.isRegularFile()).isTrue()
    val xmlMavenPomReader = MavenXpp3Reader()
    pom.toFile().inputStream().use { inStream ->
        val parsedPom = xmlMavenPomReader.read(inStream)
        assertThat(parsedPom.dependencies.map {
            "${it.groupId}:${it.artifactId}:${it.version} scope:${it.scope}"
        }).containsExactlyElementsIn(dependencies)
    }
}

class FusedLibPublicationCallback: GenericCallback {
    override fun handleProject(project: Project) {
        project.plugins.apply("maven-publish")

        val publishing = project.extensions.findByType(PublishingExtension::class.java)
            ?: throw RuntimeException("Could not find extension of type PublishingExtension")
        publishing.apply {
            publications.create("release", MavenPublication::class.java) {
                it.groupId = FusedLibraryTestConstants.FUSED_LIBRARY_GROUP
                it.artifactId = FusedLibraryTestConstants.FUSED_LIBRARY_ARTIFACT_NAME
                it.version = FusedLibraryTestConstants.FUSED_LIBRARY_VERSION
                it.from(project.components.getByName("fusedLibraryComponent"))
            }
            repositories {
                it.maven {
                    it.name = "myrepo"
                    it.url = project.uri(project.layout.buildDirectory.dir(FusedLibraryTestConstants.FUSED_LIBRARY_REPO_NAME))
                }
            }
        }
    }
}
