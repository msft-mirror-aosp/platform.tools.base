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

package com.android.build.gradle.tasks

import com.android.build.api.artifact.impl.ArtifactsImpl
import com.android.build.gradle.internal.component.LibraryCreationConfig
import com.android.build.gradle.internal.cxx.json.readJsonFile
import com.android.build.gradle.internal.cxx.prefab.PREFAB_PACKAGE
import com.android.build.gradle.internal.cxx.prefab.PrefabModulePublication
import com.android.build.gradle.internal.cxx.prefab.PrefabPackagePublication
import com.android.build.gradle.internal.cxx.prefab.PrefabPublication
import com.android.build.gradle.internal.cxx.prefab.PrefabPublicationType.Configuration
import com.android.build.gradle.internal.cxx.prefab.publicationFileFrom
import com.android.build.gradle.internal.fixtures.FakeNoOpAnalyticsService
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** Unit tests for [PrefabPackageConfigurationTask]. */
class PrefabPackageConfigurationTaskTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var project: Project
  private lateinit var publication: PrefabPublication

  @Before
  fun setUp() {
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder("lib")).build()
    publication =
      PrefabPublication(
        installationFolder = project.layout.buildDirectory.get().asFile.resolve("intermediates/$PREFAB_PACKAGE/freeDebug/prefab"),
        gradlePath = ":lib",
        packageInfo =
          PrefabPackagePublication(
            packageName = "lib",
            packageVersion = "1.0",
            packageSchemaVersion = 2,
            packageDependencies = listOf(),
            // Header-only module so that no C/C++ mini-config is needed to add library information.
            modules =
              listOf(
                PrefabModulePublication(
                  moduleName = "foo",
                  moduleHeaders = null,
                  moduleExportLibraries = listOf(),
                  moduleLibraryName = null,
                  abis = listOf(),
                )
              ),
          ),
      )
  }

  /** The task must write the publication to its declared output, otherwise Gradle can't tell when it is missing (b/551999754). */
  @Test
  fun `task writes publication to declared output file`() {
    val task = project.tasks.register("prefabFreeDebugConfigurePackage", PrefabPackageConfigurationTask::class.java).get()
    val declaredOutput = File(temporaryFolder.newFolder("declared"), "declared_publication.json")
    task.publication = publication
    task.publicationFile.set(declaredOutput)
    task.analyticsService.set(FakeNoOpAnalyticsService())

    task.taskAction()

    assertThat(declaredOutput.isFile).named("$declaredOutput exists").isTrue()
    assertThat(readJsonFile<PrefabPublication>(declaredOutput)).isEqualTo(publication)
  }

  /**
   * The declared output (and so the published PREFAB_PACKAGE_CONFIGURATION artifact) must be the same file that consumers find through
   * [publicationFileFrom].
   */
  @Test
  fun `declared output is the file consumers read`() {
    val artifacts = ArtifactsImpl(project, "freeDebug")
    val creationConfig = mock<LibraryCreationConfig> { on { this.artifacts } doReturn artifacts }
    val creationAction = PrefabPackageConfigurationTask.CreationAction(publication, "prefabFreeDebugConfigurePackage", creationConfig)
    val taskProvider = project.tasks.register(creationAction.name, creationAction.type)

    creationAction.handleProvider(taskProvider)

    val expected = Configuration.publicationFileFrom(publication.installationFolder)!!
    assertThat(expected)
      .isEqualTo(
        project.layout.buildDirectory.get().asFile.resolve("intermediates/prefab_package_configuration/prefab_publication.json/freeDebug")
      )
    assertThat(taskProvider.get().publicationFile.get().asFile).isEqualTo(expected)
    assertThat(artifacts.get(InternalArtifactType.PREFAB_PACKAGE_CONFIGURATION).get().asFile).isEqualTo(expected)
  }
}
