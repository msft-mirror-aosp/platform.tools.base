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

package com.android.build.gradle.integration.nativebuild

import com.android.SdkConstants
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.DEFAULT_NDK_SIDE_BY_SIDE_VERSION
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.internal.cxx.configure.CMakeVersion
import com.android.build.gradle.options.BooleanOption
import com.android.utils.FileUtils
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ShaderCompileTest {

    @get:Rule
    val project = GradleTestProject.builder()
        .setCmakeVersion(CMakeVersion.DEFAULT.sdkFolderName)
        .setWithCmakeDirInLocalProp(true)
        .setSideBySideNdkVersion(DEFAULT_NDK_SIDE_BY_SIDE_VERSION)
        .fromTestProject("vulkan")
        .create()

    @Test
    fun assembleGlslcDebug() {
        val content = project.file("local.properties").readText()
        val updatedContent =
            content.split("\n").filter { !it.contains("ndk.symlinkdir") }.joinToString ("\n" )
        // substitute ndk.symlinkdir with glslc.dir
        project.file("local.properties").writeText(
            updatedContent + "\nglslc.dir=${getGlslcFolder()}"
        )
        project.executor()
            .run("assembleDebug")
        project.getApk(GradleTestProject.ApkType.DEBUG).use { apk ->
            assertThat(apk).containsFile("lib/x86/libvktuts.so")
            assertThat(apk).containsFile("lib/x86_64/libvktuts.so")
            assertThat(apk).containsFile("lib/armeabi-v7a/libvktuts.so")
            assertThat(apk).containsFile("lib/arm64-v8a/libvktuts.so")
            assertThat(apk).containsFile("assets/shaders/tri.vert.spv")
            assertThat(apk).containsFile("assets/shaders/tri.frag.spv")
        }
    }

    @Test
    fun checkErrorForFutureVersion() {
        val content = project.file("local.properties").readText()
        val updatedContent =
            content.split("\n").filter { !it.contains("ndk.symlinkdir") }.joinToString ("\n" )

        // glslc.dir property is not set

        val result = project.executor()
            .with(BooleanOption.CUSTOM_SHADER_PATH_REQUIRED, true)
            .expectFailure()
            .run("assembleDebug")
        result.assertErrorContains("Property `glslc.dir` must be set for AGP to define custom shader.")
    }

    @Test
    fun assembleGlslcDebugWrongName() {
        val emptyDirectory = File(project.buildFile.parentFile, "customCompilerFolder")
        Files.createDirectory(emptyDirectory.toPath())

        project.file("local.properties")
            .appendText("glslc.dir=${FileUtils.toSystemIndependentPath(emptyDirectory.absolutePath)}")
        val result = project.executor()
            .expectFailure().run("assembleDebug")
        result.assertErrorContains("Custom `glslc.dir` location must point to existing directory with")
    }

    private fun getGlslcFolder(): String {
        var glslcRootFolder = File(project.androidNdkDir, SdkConstants.FD_SHADER_TOOLS)
        when (SdkConstants.currentPlatform()) {
            SdkConstants.PLATFORM_DARWIN -> glslcRootFolder = File(glslcRootFolder, "darwin-x86_64")
            SdkConstants.PLATFORM_WINDOWS -> {
                // try 64 bit first
                glslcRootFolder = File(glslcRootFolder, "windows-x86_64")
                if (!glslcRootFolder.isDirectory()) {
                    // try 32 bit next
                    glslcRootFolder = File(glslcRootFolder, "windows")
                }
            }

            SdkConstants.PLATFORM_LINUX -> glslcRootFolder = File(glslcRootFolder, "linux-x86_64")
        }
        return FileUtils.toSystemIndependentPath(glslcRootFolder.absolutePath)
    }
}
