/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.build.gradle.internal.dependency

import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.capabilities.Capability
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ProvidedClasspathTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var project: Project

  private val app = mock(ComponentIdentifier::class.java)
  private val lib = mock(ComponentIdentifier::class.java)

  @Before
  fun setUp() {
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
  }

  @Test
  fun compileOnlyArtifactIsProvided() {
    val libJar = artifact("lib.jar", lib, capability("com.example", "lib"))

    val provided = getProvidedClasspath(artifactCollection(libJar), artifactCollection())

    assertThat(provided.files).containsExactly(libJar.file)
  }

  @Test
  fun sameComponentWithDifferentCompileAndRuntimeFilesIsNotProvided() {
    val libCapability = capability("com.example", "lib")
    val compileJar = artifact("lib-compile.jar", lib, libCapability)
    val runtimeJar = artifact("lib-runtime.jar", lib, libCapability)

    val provided = getProvidedClasspath(artifactCollection(compileJar), artifactCollection(runtimeJar))

    assertThat(provided.files).isEmpty()
  }

  @Test
  fun capabilityVersionIsIgnored() {
    val compileJar = artifact("lib-compile.jar", lib, capability("com.example", "lib", "unspecified"))
    val runtimeJar = artifact("lib-runtime.jar", lib, capability("com.example", "lib", "1.0"))

    val provided = getProvidedClasspath(artifactCollection(compileJar), artifactCollection(runtimeJar))

    assertThat(provided.files).isEmpty()
  }

  @Test
  fun fileDependencyWithoutCapabilitiesOnBothClasspathsIsNotProvided() {
    val localJar = mock(ComponentIdentifier::class.java)
    val compileJar = artifact("local.jar", localJar)
    val runtimeJar = artifact("local.jar", localJar)

    val provided = getProvidedClasspath(artifactCollection(compileJar), artifactCollection(runtimeJar))

    assertThat(provided.files).isEmpty()
  }

  /** Regression test for b/548701041. */
  @Test
  fun testFixturesOnRuntimeClasspathDoNotRemoveMainVariantFromProvided() {
    val appCompileJar = artifact("app-compile.jar", app, capability("", "app"))
    val appTestFixturesCompileJar = artifact("app-test-fixtures-compile.jar", app, capability("", "app-test-fixtures"))
    val appTestFixturesRuntimeJar = artifact("app-test-fixtures-runtime.jar", app, capability("", "app-test-fixtures"))

    val provided =
      getProvidedClasspath(
        artifactCollection(appCompileJar, appTestFixturesCompileJar),
        artifactCollection(appTestFixturesRuntimeJar),
      )

    assertThat(provided.files).containsExactly(appCompileJar.file)
  }

  private fun capability(group: String, name: String, version: String? = null): Capability {
    val capability = mock(Capability::class.java)
    `when`(capability.group).thenReturn(group)
    `when`(capability.name).thenReturn(name)
    `when`(capability.version).thenReturn(version)
    return capability
  }

  private fun artifact(
    fileName: String,
    componentIdentifier: ComponentIdentifier,
    vararg capabilities: Capability,
  ): ResolvedArtifactResult {
    val file = temporaryFolder.root.resolve(fileName).also { it.createNewFile() }
    val artifactId = mock(ComponentArtifactIdentifier::class.java)
    `when`(artifactId.componentIdentifier).thenReturn(componentIdentifier)
    val variant = mock(ResolvedVariantResult::class.java)
    `when`(variant.capabilities).thenReturn(capabilities.toList())
    val artifact = mock(ResolvedArtifactResult::class.java)
    `when`(artifact.file).thenReturn(file)
    `when`(artifact.id).thenReturn(artifactId)
    `when`(artifact.variant).thenReturn(variant)
    return artifact
  }

  private fun artifactCollection(vararg artifacts: ResolvedArtifactResult): ArtifactCollection {
    val artifactSet = artifacts.toMutableSet()
    val files: FileCollection = project.files(artifacts.map { it.file })
    return object : ArtifactCollection {
      override fun getArtifactFiles(): FileCollection = files

      override fun getArtifacts(): MutableSet<ResolvedArtifactResult> = artifactSet

      override fun getResolvedArtifacts(): Provider<Set<ResolvedArtifactResult>> = project.provider { artifactSet }

      override fun getFailures(): MutableCollection<Throwable> = mutableListOf()

      override fun iterator(): MutableIterator<ResolvedArtifactResult> = artifactSet.iterator()
    }
  }
}
