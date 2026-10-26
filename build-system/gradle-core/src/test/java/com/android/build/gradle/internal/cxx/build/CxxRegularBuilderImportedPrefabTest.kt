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

package com.android.build.gradle.internal.cxx.build

import com.android.build.gradle.internal.cxx.gradle.generator.NativeBuildOutputOptions
import com.android.build.gradle.internal.cxx.json.AndroidBuildGradleJsons
import com.android.build.gradle.internal.cxx.json.NativeBuildConfigValue
import com.android.build.gradle.internal.cxx.json.NativeLibraryValue
import com.android.build.gradle.internal.cxx.model.CxxAbiModel
import com.android.build.gradle.internal.cxx.model.CxxModuleModel
import com.android.build.gradle.internal.cxx.model.CxxVariantModel
import com.android.build.gradle.internal.cxx.process.JvmProcessBuilder
import com.android.build.gradle.internal.cxx.settings.BuildSettingsConfiguration
import com.android.build.gradle.internal.ndk.AbiInfo
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CxxRegularBuilderImportedPrefabTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private val providers: ProviderFactory by lazy {
    ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build().providers
  }

  private fun setupAbi(
    buildTargetSet: Set<String> = emptySet(),
    implicitBuildTargetSet: Set<String> = emptySet(),
    stlLibraryFile: File? = null,
  ): Pair<CxxAbiModel, File> {
    val abiDir = temporaryFolder.newFolder()
    val jsonFile = abiDir.resolve("android_gradle_build.json")
    val soFolder = abiDir.resolve("obj/x86_64")

    val module = mock<CxxModuleModel>()
    doReturn(emptySet<NativeBuildOutputOptions>()).whenever(module).outputOptions
    doReturn(abiDir).whenever(module).moduleRootFolder

    val variant = mock<CxxVariantModel>()
    doReturn(module).whenever(variant).module
    doReturn(buildTargetSet).whenever(variant).buildTargetSet
    doReturn(implicitBuildTargetSet).whenever(variant).implicitBuildTargetSet

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

    val abi =
      CxxAbiModel(
        info = abiInfo,
        cxxBuildFolder = abiDir,
        soFolder = soFolder,
        soRepublishFolder = abiDir.resolve("republish"),
        abiPlatformVersion = 21,
        cmake = null,
        variant = variant,
        buildSettings = BuildSettingsConfiguration(),
        prefabFolder = abiDir.resolve("prefab"),
        isActiveAbi = true,
        fullConfigurationHash = "hash",
        fullConfigurationHashKey = "hashKey",
        configurationArguments = emptyList(),
        stlLibraryFile = stlLibraryFile,
        intermediatesParentFolder = abiDir.resolve("intermediates"),
      )

    return Pair(abi, jsonFile)
  }

  @Test
  fun `imported shared prefab module copies so and runtimeFiles and stl without running ninja`() {
    val stlFile = temporaryFolder.newFile("libc++_shared.so")
    val (abi, jsonFile) =
      setupAbi(
        buildTargetSet = emptySet(),
        implicitBuildTargetSet = setOf("foo"),
        stlLibraryFile = stlFile,
      )

    val prebuiltFoo = temporaryFolder.newFile("libfoo.so")
    val prebuiltDep = temporaryFolder.newFile("libdep.so")

    val config =
      NativeBuildConfigValue().apply {
        libraries =
          mapOf(
            "foo" to
              NativeLibraryValue().apply {
                artifactName = "foo"
                output = prebuiltFoo
                imported = true
                runtimeFiles = listOf(prebuiltDep)
              }
          )
      }
    AndroidBuildGradleJsons.writeNativeBuildConfigValueToJsonFile(jsonFile, config)

    CxxRegularBuilder(abi).build(providers)

    assertThat(abi.soFolder.resolve("libfoo.so").isFile).isTrue()
    assertThat(abi.soFolder.resolve("libdep.so").isFile).isTrue()
    assertThat(abi.soFolder.resolve("libc++_shared.so").isFile).isTrue()
  }

  @Test
  fun `imported static prefab module with so runtimeFiles validates a and copies runtimeFiles and stl`() {
    val stlFile = temporaryFolder.newFile("libc++_shared.so")
    val (abi, jsonFile) =
      setupAbi(
        buildTargetSet = emptySet(),
        implicitBuildTargetSet = setOf("foo_static"),
        stlLibraryFile = stlFile,
      )

    val prebuiltA = temporaryFolder.newFile("libfoo_static.a")
    val prebuiltDep = temporaryFolder.newFile("libdep.so")

    val config =
      NativeBuildConfigValue().apply {
        libraries =
          mapOf(
            "foo_static" to
              NativeLibraryValue().apply {
                artifactName = "foo_static"
                output = prebuiltA
                imported = true
                runtimeFiles = listOf(prebuiltDep)
              }
          )
      }
    AndroidBuildGradleJsons.writeNativeBuildConfigValueToJsonFile(jsonFile, config)

    CxxRegularBuilder(abi).build(providers)

    assertThat(abi.soFolder.resolve("libfoo_static.a").exists()).isFalse()
    assertThat(abi.soFolder.resolve("libdep.so").isFile).isTrue()
    assertThat(abi.soFolder.resolve("libc++_shared.so").isFile).isTrue()
  }

  @Test
  fun `imported static prefab module without so runtimeFiles does not create soFolder or copy stl`() {
    val stlFile = temporaryFolder.newFile("libc++_shared.so")
    val (abi, jsonFile) =
      setupAbi(
        buildTargetSet = emptySet(),
        implicitBuildTargetSet = setOf("foo_static"),
        stlLibraryFile = stlFile,
      )

    val prebuiltA = temporaryFolder.newFile("libfoo_static.a")

    val config =
      NativeBuildConfigValue().apply {
        libraries =
          mapOf(
            "foo_static" to
              NativeLibraryValue().apply {
                artifactName = "foo_static"
                output = prebuiltA
                imported = true
                runtimeFiles = emptyList()
              }
          )
      }
    AndroidBuildGradleJsons.writeNativeBuildConfigValueToJsonFile(jsonFile, config)

    CxxRegularBuilder(abi).build(providers)

    assertThat(abi.soFolder.exists()).isFalse()
  }

  @Test
  fun `non prefab imported library is not copied`() {
    val (abi, jsonFile) =
      setupAbi(
        buildTargetSet = emptySet(),
        implicitBuildTargetSet = setOf("foo"),
      )

    val prebuiltFoo = temporaryFolder.newFile("libfoo.so")
    val prebuiltUnrelated = temporaryFolder.newFile("libunrelated.so")

    val config =
      NativeBuildConfigValue().apply {
        libraries =
          mapOf(
            "foo" to
              NativeLibraryValue().apply {
                artifactName = "foo"
                output = prebuiltFoo
                imported = true
              },
            "unrelated" to
              NativeLibraryValue().apply {
                artifactName = "unrelated"
                output = prebuiltUnrelated
                imported = true
              },
          )
      }
    AndroidBuildGradleJsons.writeNativeBuildConfigValueToJsonFile(jsonFile, config)

    CxxRegularBuilder(abi).build(providers)

    assertThat(abi.soFolder.resolve("libfoo.so").isFile).isTrue()
    assertThat(abi.soFolder.resolve("libunrelated.so").exists()).isFalse()
  }

  @Test
  fun `explicit targets alongside imported prefab target builds native target and satisfies implicit targets`() {
    val (abi, jsonFile) =
      setupAbi(
        buildTargetSet = setOf("built_jni"),
        implicitBuildTargetSet = setOf("foo"),
      )

    val prebuiltFoo = abi.soFolder.parentFile.resolve("build_output/libfoo.so")
    val builtJni = abi.soFolder.parentFile.resolve("build_output/libbuilt_jni.so")
    builtJni.parentFile.mkdirs()

    val touchCommand =
      JvmProcessBuilder(TouchFiles::class)
        .dependsOnCurrentClassPath()
        .toProcessBuilder(builtJni.absolutePath, prebuiltFoo.absolutePath)
        .command()

    val config =
      NativeBuildConfigValue().apply {
        libraries =
          mapOf(
            "foo" to
              NativeLibraryValue().apply {
                artifactName = "foo"
                output = prebuiltFoo
                imported = true
              },
            "built_jni" to
              NativeLibraryValue().apply {
                artifactName = "built_jni"
                output = builtJni
                buildCommandComponents = touchCommand
              },
          )
      }
    AndroidBuildGradleJsons.writeNativeBuildConfigValueToJsonFile(jsonFile, config)

    CxxRegularBuilder(abi).build(providers)

    assertThat(builtJni.isFile).isTrue()
    assertThat(abi.soFolder.resolve("libbuilt_jni.so").isFile).isTrue()
    assertThat(abi.soFolder.resolve("libfoo.so").isFile).isTrue()
  }

  @Test
  fun `missing imported output file throws GradleException with clear message`() {
    val (abi, jsonFile) =
      setupAbi(
        buildTargetSet = emptySet(),
        implicitBuildTargetSet = setOf("foo"),
      )

    val missingFile = temporaryFolder.root.resolve("non_existent_libfoo.so")

    val config =
      NativeBuildConfigValue().apply {
        libraries =
          mapOf(
            "foo" to
              NativeLibraryValue().apply {
                artifactName = "foo"
                output = missingFile
                imported = true
              }
          )
      }
    AndroidBuildGradleJsons.writeNativeBuildConfigValueToJsonFile(jsonFile, config)

    val error =
      assertThrows(GradleException::class.java) {
        CxxRegularBuilder(abi).build(providers)
      }
    assertThat(error.message).contains("Imported library file")
    assertThat(error.message).contains("non_existent_libfoo.so")
    assertThat(error.message).contains("foo")
  }

  @Test
  fun `missing imported static output file throws GradleException with clear message`() {
    val (abi, jsonFile) =
      setupAbi(
        buildTargetSet = emptySet(),
        implicitBuildTargetSet = setOf("foo_static"),
      )

    val missingA = temporaryFolder.root.resolve("non_existent_libfoo.a")

    val config =
      NativeBuildConfigValue().apply {
        libraries =
          mapOf(
            "foo_static" to
              NativeLibraryValue().apply {
                artifactName = "foo_static"
                output = missingA
                imported = true
              }
          )
      }
    AndroidBuildGradleJsons.writeNativeBuildConfigValueToJsonFile(jsonFile, config)

    val error =
      assertThrows(GradleException::class.java) {
        CxxRegularBuilder(abi).build(providers)
      }
    assertThat(error.message).contains("Imported library file")
    assertThat(error.message).contains("non_existent_libfoo.a")
    assertThat(error.message).contains("foo_static")
  }

  @Test
  fun `missing imported runtimeFile throws GradleException with clear message`() {
    val (abi, jsonFile) =
      setupAbi(
        buildTargetSet = emptySet(),
        implicitBuildTargetSet = setOf("foo"),
      )

    val prebuiltFoo = temporaryFolder.newFile("libfoo.so")
    val missingDep = temporaryFolder.root.resolve("non_existent_dep.so")

    val config =
      NativeBuildConfigValue().apply {
        libraries =
          mapOf(
            "foo" to
              NativeLibraryValue().apply {
                artifactName = "foo"
                output = prebuiltFoo
                imported = true
                runtimeFiles = listOf(missingDep)
              }
          )
      }
    AndroidBuildGradleJsons.writeNativeBuildConfigValueToJsonFile(jsonFile, config)

    val error =
      assertThrows(GradleException::class.java) {
        CxxRegularBuilder(abi).build(providers)
      }
    assertThat(error.message).contains("Imported library runtime file")
    assertThat(error.message).contains("non_existent_dep.so")
    assertThat(error.message).contains("foo")
  }

  @Test
  fun `imported prefab module with null output still synchronizes runtimeFiles with case insensitive extension`() {
    val (abi, jsonFile) =
      setupAbi(
        buildTargetSet = emptySet(),
        implicitBuildTargetSet = setOf("foo_interface"),
      )

    val prebuiltDep = temporaryFolder.newFile("libdep.SO")

    val config =
      NativeBuildConfigValue().apply {
        libraries =
          mapOf(
            "foo_interface" to
              NativeLibraryValue().apply {
                artifactName = "foo_interface"
                output = null
                imported = true
                runtimeFiles = listOf(prebuiltDep)
              }
          )
      }
    AndroidBuildGradleJsons.writeNativeBuildConfigValueToJsonFile(jsonFile, config)

    CxxRegularBuilder(abi).build(providers)

    assertThat(abi.soFolder.resolve("libdep.SO").isFile).isTrue()
  }
}

/** Invoked via [JvmProcessBuilder] so Linux and Windows execute the identical JVM subprocess path. */
class TouchFiles {
  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      for (arg in args) {
        val file = File(arg)
        file.parentFile?.mkdirs()
        file.createNewFile()
      }
    }
  }
}
