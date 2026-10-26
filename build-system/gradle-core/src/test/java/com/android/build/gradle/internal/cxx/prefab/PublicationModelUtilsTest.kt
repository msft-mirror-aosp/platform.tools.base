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

import com.android.build.api.dsl.Prefab
import com.android.build.gradle.internal.component.LibraryCreationConfig
import com.android.build.gradle.internal.component.features.NativeBuildCreationConfig
import com.android.build.gradle.internal.cxx.gradle.generator.CxxConfigurationModel
import com.android.build.gradle.internal.cxx.gradle.generator.tryCreateConfigurationParameters
import com.android.build.gradle.internal.cxx.model.BasicCmakeMock
import com.android.build.gradle.internal.cxx.model.CxxAbiModel
import com.android.build.gradle.internal.cxx.model.CxxModuleModel
import com.android.build.gradle.internal.cxx.model.CxxVariantModel
import com.android.build.gradle.internal.cxx.settings.BuildSettingsConfiguration
import com.android.build.gradle.internal.dsl.PrefabImpl
import com.android.build.gradle.internal.ndk.AbiInfo
import com.android.build.gradle.internal.scope.ProjectInfo
import com.android.build.gradle.internal.services.TaskCreationServices
import com.android.build.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.android.repository.Revision
import com.google.common.truth.Truth.assertThat
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class PublicationModelUtilsTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun `createPrefabPublication produces empty abis for headerOnly modules`() {
    val abiInfo =
      AbiInfo(
        name = "x86_64",
        bitness = 64,
        isDefault = true,
        isDeprecated = false,
        architecture = "x86_64",
        triple = "x86_64-linux-android",
        llvmTriple = "x86_64-none-linux-android",
      )
    val moduleModel = mock<CxxModuleModel>()
    doReturn(Revision.parseRevision("25.0.0")).whenever(moduleModel).ndkVersion
    doReturn(":foo").whenever(moduleModel).gradleModulePathName

    val variantModel = mock<CxxVariantModel>()
    doReturn("c++_shared").whenever(variantModel).stlType
    doReturn(moduleModel).whenever(variantModel).module

    val cxxFolder = temporaryFolder.newFolder("cxx")
    val abiModel =
      CxxAbiModel(
        info = abiInfo,
        cxxBuildFolder = cxxFolder,
        soFolder = temporaryFolder.newFolder("so"),
        soRepublishFolder = temporaryFolder.newFolder("republish"),
        abiPlatformVersion = 21,
        cmake = null,
        variant = variantModel,
        buildSettings = BuildSettingsConfiguration(),
        prefabFolder = temporaryFolder.newFolder("prefab"),
        isActiveAbi = true,
        fullConfigurationHash = "hash",
        fullConfigurationHashKey = "hashKey",
        configurationArguments = emptyList(),
        stlLibraryFile = null,
        intermediatesParentFolder = temporaryFolder.newFolder("intermediatesParent"),
      )

    val configurationModel = mock<CxxConfigurationModel>()
    doReturn(listOf(abiModel)).whenever(configurationModel).activeAbis
    doReturn(variantModel).whenever(configurationModel).variant

    val libraryVariant = mock<LibraryCreationConfig>()
    val globalConfig = mock<GlobalTaskCreationConfig>()
    val nativeBuildCreationConfig = mock<NativeBuildCreationConfig>()
    val taskCreationServices = mock<TaskCreationServices>()
    val projectInfo = mock<ProjectInfo>()

    val dirProvider = mock<Provider<Directory>>()
    val gradleDir = mock<Directory>()
    val intermediatesDir = temporaryFolder.newFolder("intermediates")
    doReturn(intermediatesDir).whenever(gradleDir).asFile
    doReturn(gradleDir).whenever(dirProvider).get()

    doReturn(globalConfig).whenever(libraryVariant).global
    doReturn(taskCreationServices).whenever(libraryVariant).services
    doReturn(projectInfo).whenever(taskCreationServices).projectInfo
    doReturn(dirProvider).whenever(projectInfo).intermediatesDirectory
    doReturn("fooLib").whenever(projectInfo).name
    doReturn("1.0.0").whenever(projectInfo).version
    doReturn("debug").whenever(libraryVariant).name
    doReturn(emptyMap<String, Any>()).whenever(nativeBuildCreationConfig).externalNativeExperimentalProperties

    val headerOnlyModule =
      PrefabImpl("headerOnlyModule").apply {
        headerOnly = true
        headers = null
      }
    val builtModule =
      PrefabImpl("builtModule").apply {
        headerOnly = false
        headers = null
      }

    doReturn(setOf(headerOnlyModule, builtModule)).whenever(globalConfig).prefab

    val publication = createPrefabPublication(configurationModel, libraryVariant, nativeBuildCreationConfig)
    val modulesByName = publication.packageInfo.modules.associateBy { it.moduleName }

    assertThat(modulesByName["headerOnlyModule"]!!.abis).isEmpty()
    assertThat(modulesByName["builtModule"]!!.abis).isNotEmpty()
    assertThat(modulesByName["builtModule"]!!.abis.map { it.abiName }).containsExactly("x86_64")
  }

  @Test
  fun `CxxConfigurationModel implicitBuildTargetSet excludes headerOnly modules`() {
    BasicCmakeMock().apply {
      val headerOnlyModule = PrefabImpl("headerOnlyLib").apply { headerOnly = true }
      val builtModule = PrefabImpl("builtLib").apply { headerOnly = false }

      val global = variantImpl.global
      doReturn(setOf<Prefab>(headerOnlyModule, builtModule)).whenever(global).prefabOrEmpty

      val params = tryCreateConfigurationParameters(projectOptions, variantImpl)!!
      assertThat(params.implicitBuildTargetSet).containsExactly("builtLib")
    }
  }
}
