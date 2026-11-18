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

package com.android.build.gradle.internal.cxx.prefab

import com.android.build.gradle.internal.cxx.json.readJsonFile
import com.android.build.gradle.internal.cxx.prefab.PrefabPublicationType.Configuration
import com.android.build.gradle.internal.cxx.prefab.PrefabPublicationType.HeaderOnly
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrefabPublicationTypeTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val intermediates by lazy { temporaryFolder.newFolder("build", "intermediates") }

  private fun installationFolder(variant: String) = intermediates.resolve(PREFAB_PACKAGE).resolve(variant).resolve("prefab")

  private fun publication(variant: String) =
    PrefabPublication(
      installationFolder = installationFolder(variant),
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

  @Test
  fun `publicationFileFrom layout for flavored variant`() {
    val installationFolder = installationFolder("freeDebug")
    assertThat(Configuration.publicationFileFrom(installationFolder))
      .isEqualTo(intermediates.resolve("prefab_package_configuration/prefab_publication.json/freeDebug"))
    assertThat(HeaderOnly.publicationFileFrom(installationFolder))
      .isEqualTo(intermediates.resolve("prefab_package_header_only/prefab_publication.json/freeDebug"))
  }

  @Test
  fun `publicationFileFrom returns null when not under prefab_package`() {
    val installationFolder = intermediates.resolve("something_else/freeDebug/prefab")
    assertThat(Configuration.publicationFileFrom(installationFolder)).isNull()
    assertThat(HeaderOnly.publicationFileFrom(installationFolder)).isNull()
  }

  @Test
  fun `writePublicationFile writes to derived location by default`() {
    val publication = publication("freeDebug")
    Configuration.writePublicationFile(publication)

    val derived = Configuration.publicationFileFrom(publication.installationFolder)!!
    assertThat(derived.isFile).isTrue()
    assertThat(Configuration.readPublicationFileOrNull(publication.installationFolder)).isEqualTo(publication)
  }

  @Test
  fun `writePublicationFile writes to explicit file`() {
    val publication = publication("freeDebug")
    val target = File(temporaryFolder.newFolder("elsewhere"), "publication.json")
    Configuration.writePublicationFile(publication, target)

    assertThat(target.isFile).isTrue()
    assertThat(readJsonFile<PrefabPublication>(target)).isEqualTo(publication)
    assertThat(Configuration.publicationFileFrom(publication.installationFolder)!!.exists()).isFalse()
  }
}
