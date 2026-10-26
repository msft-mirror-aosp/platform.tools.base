/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.build.gradle.internal.cxx.cmake

import com.android.build.gradle.internal.cxx.logging.LoggingMessage
import com.android.build.gradle.internal.cxx.logging.ThreadLoggingEnvironment
import com.android.build.gradle.internal.cxx.logging.text
import com.android.testutils.TestUtils
import com.android.utils.cxx.streamCompileCommands
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CmakeFileApiV1Test {

  @get:Rule var temporaryFolder = TemporaryFolder()

  private val logger = TestLoggingEnvironment()

  private class TestLoggingEnvironment : ThreadLoggingEnvironment() {
    override fun log(message: LoggingMessage) {
      if (message.level == LoggingMessage.LoggingLevel.ERROR) {
        error(message.text())
      }
    }
  }

  @After
  fun after() {
    logger.close()
  }

  private val bazelFolderBase = "tools/base/build-system/gradle-core/src/test/data/cmake-file-api-samples"

  private fun prepareReplyFolder(subFolder: String): File {
    val base = TestUtils.resolveWorkspacePath(bazelFolderBase).toFile()

    val result = base.resolve(subFolder)
    if (!result.isDirectory) {
      error(result.absolutePath)
    }
    return result
  }

  private fun createNinjaCommand(arg: String): List<String> = listOf("ninja", arg)

  @Test
  fun assembler() {
    val replyFolder = prepareReplyFolder("assembler")
    val sources = mutableListOf<CmakeFileApiSourceFile>()
    readCmakeFileApiReply(replyFolder, ::createNinjaCommand) { sources.add(it) }
    assertThat(sources.map { File(it.sourcePath).extension }.toSortedSet()).containsExactly("asm", "cpp", "h")
  }

  @Test
  fun prefabPublish() {
    val replyFolder = prepareReplyFolder("prefab-publish")
    val sources = mutableListOf<CmakeFileApiSourceFile>()
    val config = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) { sources.add(it) }
    val library = config.libraries!!.values.map { it.output!!.toString().replace("\\", "/") }.single { it.endsWith(".a") }

    assertThat(library).endsWith(".cxx/cmake/debug/x86/libfoo_static.a")
  }

  @Test
  fun prefabExtractModelData() {
    val replyFolder = prepareReplyFolder("prefab")
    val sources = mutableListOf<CmakeFileApiSourceFile>()
    val config = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) { sources.add(it) }
    val runtimeFiles =
      config.libraries!!
        .values
        .flatMap { it.runtimeFiles!!.toList() }
        .map { it.path.replace("\\", "/") }
        .distinct()
        .sorted()
        .joinToString("\n")

    assertThat(runtimeFiles)
      .isEqualTo(
        """
        /{PREFAB}/jetified-curl/prefab/modules/curl/libs/android.x86_64/libcurl.so
        /{PREFAB}/jetified-jsoncpp/prefab/modules/jsoncpp/libs/android.x86_64/libjsoncpp.so
        /{PREFAB}/jetified-openssl/prefab/modules/crypto/libs/android.x86_64/libcrypto.so
        /{PREFAB}/jetified-openssl/prefab/modules/ssl/libs/android.x86_64/libssl.so
        """
          .trimIndent()
      )
  }

  @Test
  fun simpleExtractModelData() {
    val replyFolder = prepareReplyFolder("simple")
    val sources = mutableListOf<CmakeFileApiSourceFile>()
    val config = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) { sources.add(it) }

    val includes = sources.flatMap { it.compileGroup?.includes ?: listOf() }.distinct().sorted()
    assertThat(includes).isEmpty()

    val defines = sources.flatMap { it.compileGroup?.defines ?: listOf("(none)") }.distinct().sorted()
    assertThat(defines).contains("hello_jni_EXPORTS")

    val sysroots = sources.map { it.compileGroup?.sysroot ?: "(none)" }.distinct().sorted()
    assertThat(sysroots).hasSize(1)

    // The "(none)" language is "Header Files"
    val languageGroups = sources.map { it.compileGroup?.language ?: "(none)" }.distinct().sorted()
    assertThat(languageGroups).containsExactly("C")

    val sourceGroups = sources.map { it.sourceGroup }.distinct().sorted()
    assertThat(sourceGroups).containsExactly("Source Files")

    val symbolFoldersIndexContent =
      config.libraries!!
        .values
        .map { it.output!!.parent }
        .distinct()
        .sorted()
        .joinToString("\n")
        .replace("/Users/jomof/projects/dolphin/", "{PROJECT}/")
        .replace("\\", "/")
    assertThat(symbolFoldersIndexContent)
      .isEqualTo(
        """
        /{PROJECT}/build/intermediates/cmake/debug/obj/x86_64
        """
          .trimIndent()
      )

    val buildFilesIndexContent = config.buildFiles!!.map { it.path.replace("\\", "/") }.joinToString("\n")

    assertThat(buildFilesIndexContent)
      .isEqualTo(
        """
        /{PROJECT}/CMakeLists.txt
        """
          .trimIndent()
      )
  }

  @Test
  fun checkInferToolExeFromExistingTool() {
    assertThat(inferToolExeFromExistingTool("/path/to/ld.exe", "clang++").path.replace("\\", "/")).isEqualTo("/path/to/clang++.exe")
    assertThat(inferToolExeFromExistingTool("/path/to/ld", "clang++").path.replace("\\", "/")).isEqualTo("/path/to/clang++")
  }

  @Test
  fun dolphinExtractModelData() {
    val replyFolder = prepareReplyFolder("dolphin")
    val sources = mutableListOf<CmakeFileApiSourceFile>()
    val config = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) { sources.add(it) }

    val includes = sources.flatMap { it.compileGroup?.includes ?: listOf() }.distinct().sorted()
    assertThat(includes).contains("/{PROJECT}/External/minizip")

    val compileCommandFragments = sources.flatMap { it.compileGroup?.compileCommandFragments ?: listOf() }.distinct().sorted()
    assertThat(compileCommandFragments).contains("-Wall")

    val defines = sources.flatMap { it.compileGroup?.defines ?: listOf("(none)") }.distinct().sorted()
    assertThat(defines).contains("ANDROID")
    assertThat(defines).contains("CIFACE_USE_ANDROID")

    // The "(none)" sysroot is "Header Files"
    val sysroots = sources.map { it.compileGroup?.sysroot ?: "(none)" }.distinct().sorted()
    assertThat(sysroots).containsExactly("(none)", "/{SDK}/ndk/21.4.7075529/toolchains/llvm/prebuilt/darwin-x86_64/sysroot")

    // The "(none)" language is "Header Files"
    val languageGroups = sources.map { it.compileGroup?.language ?: "(none)" }.distinct().sorted()
    assertThat(languageGroups).containsExactly("(none)", "C", "CXX")

    val sourceGroups = sources.map { it.sourceGroup }.distinct().sorted()
    assertThat(sourceGroups).containsExactly("", "CMake Rules", "Header Files", "Object Libraries", "Source Files")

    val symbolFoldersIndexContent =
      config.libraries!!.values.mapNotNull { it.output?.parent?.replace("\\", "/") }.distinct().sorted().joinToString("\n")

    assertThat(symbolFoldersIndexContent)
      .isEqualTo(
        """
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Binaries
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Binaries/Tests
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/FreeSurround
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/LZO
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/SFML
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/bzip2
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/cpp-optparse
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/cubeb
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/curl/lib
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/enet
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/fmt
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/glslang
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/gtest
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/imgui
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/libiconv-1.14
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/liblzma
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/libpng
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/mbedtls/library
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/minizip
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/pugixml
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/soundtouch
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/xxhash
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Externals/zstd
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Android/jni/AndroidCommon
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/AudioCommon
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/Common
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/Core
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/DiscIO
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/InputCommon
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/UICommon
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/VideoBackends/Null
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/VideoBackends/OGL
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/VideoBackends/Software
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/VideoBackends/Vulkan
        /{PROJECT}/Source/Android/app/.cxx/RelWithDebInfo/262v3q4o/arm64-v8a/Source/Core/VideoCommon
        /{PROJECT}/Source/Android/app/build/intermediates/cxx/RelWithDebInfo/262v3q4o/obj/arm64-v8a
        """
          .trimIndent()
      )

    val buildFilesIndexContent = config.buildFiles!!.map { it.toString().replace("\\", "/") }.distinct().sorted().joinToString("\n")

    assertThat(buildFilesIndexContent)
      .isEqualTo(
        """
        /{PROJECT}/CMake/CCache.cmake
        /{PROJECT}/CMake/CheckAndAddFlag.cmake
        /{PROJECT}/CMake/CheckLib.cmake
        /{PROJECT}/CMake/CheckVendoringApproved.cmake
        /{PROJECT}/CMake/DolphinCompileDefinitions.cmake
        /{PROJECT}/CMake/FindALSA.cmake
        /{PROJECT}/CMake/FindCUBEB.cmake
        /{PROJECT}/CMake/FindEGL.cmake
        /{PROJECT}/CMake/FindFFmpeg.cmake
        /{PROJECT}/CMake/FindMBEDTLS.cmake
        /{PROJECT}/CMake/FindOpenSLES.cmake
        /{PROJECT}/CMake/FindPulseAudio.cmake
        /{PROJECT}/CMake/FindSFML.cmake
        /{PROJECT}/CMake/FindSYSTEMD.cmake
        /{PROJECT}/CMake/Findpugixml.cmake
        /{PROJECT}/CMakeLists.txt
        /{PROJECT}/Externals/FreeSurround/CMakeLists.txt
        /{PROJECT}/Externals/LZO/CMakeLists.txt
        /{PROJECT}/Externals/SFML/CMakeLists.txt
        /{PROJECT}/Externals/bzip2/CMakeLists.txt
        /{PROJECT}/Externals/cpp-optparse/CMakeLists.txt
        /{PROJECT}/Externals/cubeb/CMakeLists.txt
        /{PROJECT}/Externals/cubeb/Config.cmake.in
        /{PROJECT}/Externals/cubeb/cmake/sanitizers-cmake/cmake/FindASan.cmake
        /{PROJECT}/Externals/cubeb/cmake/sanitizers-cmake/cmake/FindMSan.cmake
        /{PROJECT}/Externals/cubeb/cmake/sanitizers-cmake/cmake/FindSanitizers.cmake
        /{PROJECT}/Externals/cubeb/cmake/sanitizers-cmake/cmake/FindTSan.cmake
        /{PROJECT}/Externals/cubeb/cmake/sanitizers-cmake/cmake/FindUBSan.cmake
        /{PROJECT}/Externals/cubeb/cmake/sanitizers-cmake/cmake/sanitize-helpers.cmake
        /{PROJECT}/Externals/curl/CMakeLists.txt
        /{PROJECT}/Externals/curl/lib/CMakeLists.txt
        /{PROJECT}/Externals/enet/CMakeLists.txt
        /{PROJECT}/Externals/fmt/CMakeLists.txt
        /{PROJECT}/Externals/fmt/support/cmake/JoinPaths.cmake
        /{PROJECT}/Externals/fmt/support/cmake/cxx14.cmake
        /{PROJECT}/Externals/glslang/CMakeLists.txt
        /{PROJECT}/Externals/gtest/CMakeLists.txt
        /{PROJECT}/Externals/gtest/cmake/internal_utils.cmake
        /{PROJECT}/Externals/imgui/CMakeLists.txt
        /{PROJECT}/Externals/libiconv-1.14/CMakeLists.txt
        /{PROJECT}/Externals/liblzma/CMakeLists.txt
        /{PROJECT}/Externals/libpng/CMakeLists.txt
        /{PROJECT}/Externals/mbedtls/CMakeLists.txt
        /{PROJECT}/Externals/mbedtls/library/CMakeLists.txt
        /{PROJECT}/Externals/minizip/CMakeLists.txt
        /{PROJECT}/Externals/pugixml/CMakeLists.txt
        /{PROJECT}/Externals/rangeset/CMakeLists.txt
        /{PROJECT}/Externals/soundtouch/CMakeLists.txt
        /{PROJECT}/Externals/xxhash/CMakeLists.txt
        /{PROJECT}/Externals/zstd/CMakeLists.txt
        /{PROJECT}/Source/Android/jni/AndroidCommon/CMakeLists.txt
        /{PROJECT}/Source/Android/jni/CMakeLists.txt
        /{PROJECT}/Source/CMakeLists.txt
        /{PROJECT}/Source/Core/AudioCommon/CMakeLists.txt
        /{PROJECT}/Source/Core/CMakeLists.txt
        /{PROJECT}/Source/Core/Common/CMakeLists.txt
        /{PROJECT}/Source/Core/Core/CMakeLists.txt
        /{PROJECT}/Source/Core/DiscIO/CMakeLists.txt
        /{PROJECT}/Source/Core/InputCommon/CMakeLists.txt
        /{PROJECT}/Source/Core/UICommon/CMakeLists.txt
        /{PROJECT}/Source/Core/VideoBackends/CMakeLists.txt
        /{PROJECT}/Source/Core/VideoBackends/Null/CMakeLists.txt
        /{PROJECT}/Source/Core/VideoBackends/OGL/CMakeLists.txt
        /{PROJECT}/Source/Core/VideoBackends/Software/CMakeLists.txt
        /{PROJECT}/Source/Core/VideoBackends/Vulkan/CMakeLists.txt
        /{PROJECT}/Source/Core/VideoCommon/CMakeLists.txt
        /{PROJECT}/Source/UnitTests/CMakeLists.txt
        /{PROJECT}/Source/UnitTests/Common/CMakeLists.txt
        /{PROJECT}/Source/UnitTests/Core/CMakeLists.txt
        /{PROJECT}/Source/UnitTests/VideoCommon/CMakeLists.txt
        """
          .trimIndent()
      )
  }

  @Test
  fun runtimeFilesExtractModelData() {
    val replyFolder = prepareReplyFolder("runtimefiles")
    val sources = mutableListOf<CmakeFileApiSourceFile>()
    val config = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) { sources.add(it) }
    val content =
      config.libraries!!
        .values
        .flatMap { it.runtimeFiles ?: listOf() }
        .map { it.toString().replace("\\", "/") }
        .distinct()
        .sorted()
        .joinToString("\n")

    assertThat(content)
      .isEqualTo(
        """
        /{PROJECT}/build/intermediates/cmake/debug/obj/x86_64/libfoo.so
        """
          .trimIndent()
      )
  }

  @Test
  fun `translate dolphin to configuration metadata files`() {
    val replyFolder = prepareReplyFolder("dolphin")
    val additionalFiles = temporaryFolder.newFile("additional_files.txt")
    val compileCommandsJson = replyFolder.resolve("compile_commands.json")
    val compileCommandsJsonBin = temporaryFolder.newFile("compile_commands.json.bin")
    compileCommandsJsonBin.delete()
    val androidGradleBuildJsonFile = temporaryFolder.newFile("android_gradle_build.json")
    parseCmakeFileApiReply(
      replyFolder = replyFolder,
      additionalFiles = additionalFiles,
      androidGradleBuildJsonFile = androidGradleBuildJsonFile,
      compileCommandsJsonFile = compileCommandsJson,
      compileCommandsJsonBinFile = compileCommandsJsonBin,
      createNinjaCommand = ::createNinjaCommand,
    )
    streamCompileCommands(compileCommandsJsonBin) {}
  }

  @Test
  fun `reproduce case where the working folder is a single dot`() {
    val replyFolder = prepareReplyFolder("repro-dot-working-folder")
    val additionalFiles = temporaryFolder.newFile("additional_files.txt")
    val compileCommandsJson = replyFolder.resolve("compile_commands.json")
    val compileCommandsJsonBin = temporaryFolder.newFile("compile_commands.json.bin")
    compileCommandsJsonBin.delete()
    val androidGradleBuildJsonFile = temporaryFolder.newFile("android_gradle_build.json")
    parseCmakeFileApiReply(
      replyFolder = replyFolder,
      additionalFiles = additionalFiles,
      androidGradleBuildJsonFile = androidGradleBuildJsonFile,
      compileCommandsJsonFile = compileCommandsJson,
      compileCommandsJsonBinFile = compileCommandsJsonBin,
      createNinjaCommand = ::createNinjaCommand,
    )
    streamCompileCommands(compileCommandsJsonBin) {}
  }

  @Test
  fun `reproduce case where there is an absolute path to a source file`() {
    val replyFolder = prepareReplyFolder("repro-app-glue")
    val additionalFiles = temporaryFolder.newFile("additional_files.txt")
    val compileCommandsJson = replyFolder.resolve("compile_commands.json")
    val compileCommandsJsonBin = temporaryFolder.newFile("compile_commands.json.bin")
    compileCommandsJsonBin.delete()
    val androidGradleBuildJsonFile = temporaryFolder.newFile("android_gradle_build.json")
    parseCmakeFileApiReply(
      replyFolder = replyFolder,
      additionalFiles = additionalFiles,
      androidGradleBuildJsonFile = androidGradleBuildJsonFile,
      compileCommandsJsonFile = compileCommandsJson,
      compileCommandsJsonBinFile = compileCommandsJsonBin,
      createNinjaCommand = ::createNinjaCommand,
    )
    streamCompileCommands(compileCommandsJsonBin) {}
  }

  @Test
  fun `bug 187134648 OBJECT-library`() {
    val replyFolder = prepareReplyFolder("multiple-object-library")
    val result = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) {}
    assertThat(result.libraries!!.getValue("object_dependency::@6890427a1f51a3e7e1df").output).isNull()
  }

  @Test
  fun `bug 198756433 multiple object file in same target`() {
    val replyFolder = prepareReplyFolder("multiple-object-library-2")
    val result = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) {}
    assertThat(result.libraries!!.getValue("AB::@6890427a1f51a3e7e1df").output).isNull()
  }

  @Test
  fun `bug 200065980 runtime file was not 'so'`() {
    val replyFolder = prepareReplyFolder("b200065980")
    val result = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) {}
    assertThat(result.libraries!!.getValue("alpha::@6890427a1f51a3e7e1df").runtimeFiles!!.size).isEqualTo(1)
  }

  @Test
  fun `bug 257976107 sysroot not matching on Windows`() {
    val replyFolder = prepareReplyFolder("b257976107")
    val result = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) {}
    assertThat(result.libraries!!.getValue("myapplication::@6890427a1f51a3e7e1df").runtimeFiles!!.size).isEqualTo(0)
  }

  @Test
  fun `bug 491158080 IMPORTED shared and static libraries are read from abstractTargets`() {
    // Reply from CMake 4.4 (codemodel 2.11) for SHARED, STATIC and EXECUTABLE IMPORTED targets.
    val replyFolder = prepareReplyFolder("imported-libraries")
    val librariesById = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) {}.libraries!!
    assertThat(librariesById.keys).containsExactly("CPP_module::@6890427a1f51a3e7e1df", "CPP_module_static::@6890427a1f51a3e7e1df")
    val libraries = librariesById.values
    assertThat(libraries.map { it.artifactName }).containsExactly("CPP_module", "CPP_module_static")
    assertThat(libraries.map { it.imported }).containsExactly(true, true)
    assertThat(libraries.map { it.buildCommandComponents }).containsExactly(null, null)
    assertThat(libraries.map { it.abi }).containsExactly("x86_64", "x86_64")
    assertThat(libraries.map { it.buildType }).containsExactly("debug", "debug")
    assertThat(libraries.map { it.output!!.path.replace("\\", "/") })
      .containsExactly(
        "/{PROJECT}/CPP_module/src/main/jniLibs/x86_64/libCPP_module.so",
        "/{PROJECT}/CPP_module/src/main/jniLibs/x86_64/libCPP_module_static.a",
      )
  }

  @Test
  fun `bug 491158080 runtime files of an IMPORTED library are its shared library dependencies`() {
    // Reply from CMake 4.4 for an IMPORTED library that links to a target like the ones from pkg_check_modules(... IMPORTED_TARGET ...),
    // to two IMPORTED libraries that link to each other, and to a static library. Static libraries, "log", libraries of the NDK and the
    // library itself aren't runtime files, and libdep_of_dep.so is reached twice.
    val replyFolder = prepareReplyFolder("imported-library-dependencies")
    val libraries = readCmakeFileApiReply(replyFolder, ::createNinjaCommand) {}.libraries!!.values.associateBy { it.artifactName }
    fun runtimeFiles(name: String) = libraries.getValue(name).runtimeFiles!!.map { it.path.replace("\\", "/") }
    val prebuilt = "/{PROJECT}/CPP_module/src/main/jniLibs/x86_64"
    assertThat(runtimeFiles("CPP_module"))
      .containsExactly("$prebuilt/libdep.so", "$prebuilt/libdep_of_dep.so", "$prebuilt/libtarget_dep.so")
    assertThat(runtimeFiles("target_dep")).containsExactly("$prebuilt/libdep_of_dep.so")
    assertThat(runtimeFiles("dep_of_dep")).containsExactly("$prebuilt/libtarget_dep.so")
    // pkgconfig_deps (INTERFACE_LIBRARY) is not emitted into libraries, but static_target_dep (STATIC_LIBRARY) is
    assertThat(libraries.keys).containsExactly("CPP_module", "target_dep", "dep_of_dep", "static_target_dep")
  }

  @Test
  fun `bug 491158080 abstractTargets handles EXTRA_OUTPUT concrete shared deps non-imported targets and Windows sysroot slashes`() {
    val replyFolder = temporaryFolder.newFolder("reply")
    val srcReply = prepareReplyFolder("imported-libraries")
    srcReply.listFiles()!!.forEach { file -> file.copyTo(replyFolder.resolve(file.name)) }

    // Overwrite cache with Windows backslash NDK/sysroot paths to verify slash-normalized exclusion.
    val cacheFile = replyFolder.listFiles()!!.single { it.name.startsWith("cache-v2-") }
    cacheFile.writeText(
      cacheFile
        .readText()
        .replace(
          "\"name\" : \"CMAKE_ANDROID_NDK\",\n\t\t\t\"properties\" :",
          "\"name\" : \"CMAKE_SYSROOT\",\n\t\t\t\"type\" : \"PATH\",\n\t\t\t\"value\" : \"C:\\\\Sdk\\\\ndk\\\\sysroot\"\n\t\t},\n\t\t{\n\t\t\t\"name\" : \"CMAKE_ANDROID_NDK\",\n\t\t\t\"properties\" :",
        )
    )

    // Add a concrete SHARED_LIBRARY target + non-imported abstract SHARED_LIBRARY + multi-artifact imported target.
    replyFolder
      .resolve("target-built_shared-Debug.json")
      .writeText(
        """
        {
          "artifacts": [{ "path": "libbuilt_shared.so" }],
          "id": "built_shared::@1",
          "name": "built_shared",
          "type": "SHARED_LIBRARY"
        }
        """
          .trimIndent()
      )
    replyFolder
      .resolve("target-not_imported_shared-Debug.json")
      .writeText(
        """
        {
          "artifacts": [{ "path": "libnot_imported.so" }],
          "id": "not_imported::@1",
          "imported": false,
          "name": "not_imported",
          "type": "SHARED_LIBRARY"
        }
        """
          .trimIndent()
      )
    replyFolder
      .resolve("target-multi_out-Debug.json")
      .writeText(
        """
        {
          "artifacts": [{ "path": "libmulti1.so" }, { "path": "libmulti2.so" }],
          "imported": true,
          "interfaceLinkLibraries": [
            { "id": "built_shared::@1" },
            { "fragment": "c:/Sdk/ndk/sysroot/usr/lib/x86_64-linux-android/libandroid.so" },
            { "fragment": "-Wl,--no-undefined" },
            { "fragment": "custom\\libextra.so" }
          ],
          "type": "SHARED_LIBRARY"
        }
        """
          .trimIndent()
      )

    replyFolder.resolve("target-empty-Debug.json").writeText("")
    val codemodelFile = replyFolder.listFiles()!!.single { it.name.startsWith("codemodel-v2-") }
    codemodelFile.writeText(
      """
      {
        "configurations": [
          {
            "name": "Debug",
            "targets": [
              { "id": "built_shared::@1", "jsonFile": "target-built_shared-Debug.json", "name": "built_shared" }
            ],
            "abstractTargets": [
              { "id": "not_imported::@1", "jsonFile": "target-not_imported_shared-Debug.json", "name": "not_imported" },
              { "id": "multi_out::@1", "jsonFile": "target-multi_out-Debug.json", "name": "multi_out" },
              { "id": "empty_json::@1", "jsonFile": "target-empty-Debug.json", "name": "empty_json" },
              { "id": "missing_json::@1", "jsonFile": "target-does-not-exist.json", "name": "missing_json" }
            ]
          }
        ],
        "kind": "codemodel",
        "version": { "major": 2, "minor": 11 }
      }
      """
        .trimIndent()
    )

    val recordedErrors = mutableListOf<String>()
    val recordingLogger =
      object : ThreadLoggingEnvironment() {
        override fun log(message: LoggingMessage) {
          if (message.level == LoggingMessage.LoggingLevel.ERROR) {
            recordedErrors.add(message.text())
          }
        }
      }

    val result = recordingLogger.use { readCmakeFileApiReply(replyFolder, ::createNinjaCommand) {} }

    assertThat(recordedErrors).hasSize(1)
    assertThat(recordedErrors.single()).contains("Target multi_out::@1 produces multiple outputs libmulti1.so, libmulti2.so")

    val byName = result.libraries!!.values.associateBy { it.artifactName }
    assertThat(byName.keys).containsExactly("built_shared", "multi_out")
    assertThat(byName.getValue("built_shared").imported).isNull()
    val multiOut = byName.getValue("multi_out")
    assertThat(multiOut.imported).isTrue()
    assertThat(multiOut.output!!.path.replace("\\", "/")).endsWith("/libmulti1.so")
    val multiOutRuntimes = multiOut.runtimeFiles!!.map { it.path.replace("\\", "/") }
    assertThat(multiOutRuntimes.any { it.endsWith("/libbuilt_shared.so") }).isTrue()
    assertThat(multiOutRuntimes.any { it.endsWith("/custom/libextra.so") }).isTrue()
    assertThat(multiOutRuntimes.none { it.contains("libandroid.so") }).isTrue()
  }
}
