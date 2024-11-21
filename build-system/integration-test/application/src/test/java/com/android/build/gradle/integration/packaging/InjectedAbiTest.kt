/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.integration.packaging

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.integration.common.fixture.project.AndroidProject
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector.Companion.DEBUG
import com.android.build.gradle.integration.common.fixture.project.GradleProject
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.build.gradle.integration.common.truth.GradleTaskSubject.assertThat
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.internal.core.Abi
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import com.android.testutils.truth.PathSubject
import com.android.testutils.truth.ZipFileSubject.assertThat
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.fail

/** Test APK is packaged correctly when injected ABI exists or changes */
class InjectedAbiTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication(":app") {
            files {
                HelloWorldAndroid.setupJava(this)
                createOriginalSoFile("x86", "libapp.so", "app:abcd")
                createOriginalSoFile("arm64-v8a", "libapp.so", "app:abcd")
                createOriginalSoFile("armeabi-v7a", "libapp.so", "app:abcd")
                createOriginalSoFile( "x86_64", "libapp.so", "app:abcd")
            }
        }
    }

    private val x86Selection = DEBUG.withFilter("x86")
    private val armV7aSelection = DEBUG.withFilter("armeabi-v7a")
    private val x86_64Selection = DEBUG.withFilter("x86_64")
    private val armV8Selection = DEBUG.withFilter("arm64-v8a")

    @Test
    fun testInjectedAbiChange_WithSplits() {
        val build = rule.build {
            enableSplits(listOf("x86", "armeabi-v7a", "x86_64", "arm64-v8a"))
        }
        val project = build.androidApplication(":app")

        // Run the first build with a target ABI, check that only the APK for that ABI is generated
        // and that APK only contains native libraries for target ABI
        var result = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86")
            .run("assembleDebug")
        assertThat(result.getTask(":app:packageDebug")).didWork()

        project.assertDoesNotExist(DEBUG.fromIntermediates())
        project.assertCorrectApk(x86Selection.fromIntermediates())
        project.assertDoesNotExist(armV7aSelection.fromIntermediates())
        project.assertDoesNotExist(x86_64Selection.fromIntermediates())
        project.assertDoesNotExist(armV8Selection.fromIntermediates())

        project.assertApk(x86Selection.fromIntermediates()) {
            contains("lib/" + Abi.X86.tag + "/libapp.so")
            doesNotContain("lib/" + Abi.ARM64_V8A.tag + "/libapp.so")
            doesNotContain("lib/" + Abi.X86_64.tag + "/libapp.so")
            doesNotContain("lib/" + Abi.ARMEABI_V7A.tag + "/libapp.so")
        }

        // Run the second build with another target ABI, check that another APK for that ABI is
        // generated (and generated correctly--regression test for
        // https://issuetracker.google.com/issues/38481325)
        result = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "armeabi-v7a")
            .run("assembleDebug")
        assertThat(result.getTask(":app:packageDebug")).didWork()

        project.assertDoesNotExist(DEBUG.fromIntermediates())
        project.assertDoesNotExist(x86Selection.fromIntermediates())
        project.assertCorrectApk(armV7aSelection.fromIntermediates())
        project.assertDoesNotExist(x86_64Selection.fromIntermediates())
        project.assertDoesNotExist(armV8Selection.fromIntermediates())

        val armeabiV7aLastModifiedTime = project.withApk(armV7aSelection.fromIntermediates()) {
            java.nio.file.Files.getLastModifiedTime(file)
        }

        // Run the third build without any target ABI, check that the APKs for all ABIs are
        // generated (or regenerated)
        result = build.executor.run("assembleDebug")
        assertThat(result.getTask(":app:packageDebug")).didWork()

        project.assertDoesNotExist(DEBUG)
        project.assertCorrectApk(x86Selection)
        project.assertCorrectApk(armV7aSelection)
        project.assertCorrectApk(x86_64Selection)
        project.assertCorrectApk(armV8Selection)

        project.withApk(armV7aSelection) {
            PathSubject.assertThat(file).isNewerThan(armeabiV7aLastModifiedTime)
        }

        val x86LastModifiedTime = project.withApk(x86Selection) {
            java.nio.file.Files.getLastModifiedTime(file)
        }

        // Run the fourth build with a target ABI, check that the APK for that ABI is re-generated
        result = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86")
            .run("assembleDebug")
        assertThat(result.getTask(":app:packageDebug")).didWork()

        project.assertDoesNotExist(DEBUG.fromIntermediates())
        project.assertCorrectApk(x86Selection.fromIntermediates())
        project.assertDoesNotExist(armV7aSelection.fromIntermediates())
        project.assertDoesNotExist(x86_64Selection.fromIntermediates())
        project.assertDoesNotExist(armV8Selection.fromIntermediates())

        project.withApk(x86Selection.fromIntermediates()) {
            PathSubject.assertThat(file).isNewerThan(x86LastModifiedTime)
        }
    }

    @Test
    fun testInjectedAbiChange_WithoutSplits() {
        val build = rule.build
        val project = build.androidApplication(":app")

        // Run the first build with a target ABI, check that no split APKs are generated
        // and main APK only contains native libraries for target ABI
        var result = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86")
            .run("assembleDebug")
        assertThat(result.getTask(":app:packageDebug")).didWork()

        project.assertCorrectApk(DEBUG.fromIntermediates())
        project.assertDoesNotExist(x86Selection.fromIntermediates())
        project.assertDoesNotExist(armV7aSelection.fromIntermediates())

        project.assertApk(DEBUG.fromIntermediates()) {
            contains("lib/" + Abi.X86.tag + "/libapp.so")
            doesNotContain("lib/" + Abi.ARM64_V8A.tag + "/libapp.so")
            doesNotContain("lib/" + Abi.X86_64.tag + "/libapp.so")
            doesNotContain("lib/" + Abi.ARMEABI_V7A.tag + "/libapp.so")
       }

        val apkLastModifiedTime = project.withApk(DEBUG.fromIntermediates()) {
            java.nio.file.Files.getLastModifiedTime(file)
        }

        // Run the second build with another target ABI, again check that no split APKs are
        // generated (and the main APK is re-generated)
        result = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "armeabi-v7a")
            .run("assembleDebug")
        assertThat(result.getTask(":app:packageDebug")).didWork()

        project.assertCorrectApk(DEBUG.fromIntermediates())
        project.assertDoesNotExist(x86Selection.fromIntermediates())
        project.assertDoesNotExist(armV7aSelection.fromIntermediates())

        project.withApk(DEBUG.fromIntermediates()) {
            PathSubject.assertThat(file).isNewerThan(apkLastModifiedTime)
       }
    }

    @Test
    fun testMissingSoFiles_WithoutSplits() {
        val build = rule.build
        val project = build.androidApplication(":app")

        // Build first with all .so files present. Inject x86_64 first, followed by x86
        val result1 = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86_64,x86")
            .run("assembleDebug")

        // we expect x86_64 .so files in the APK (and no x86 .so files) since there are x86_64 .so
        // files available, and we also don't expect a warning about missing .so files in this case.
        project.assertCorrectApk(DEBUG.fromIntermediates()) {
            contains("lib/x86_64/libapp.so")
            doesNotContain("lib/x86/libapp.so")
        }
        result1.stdout.use { scanner ->
            ScannerSubject.assertThat(scanner).doesNotContain("There are no .so files available")
        }

        // remove x86_64 .so files
        project.removeSoFiles(listOf("x86_64"))
        val jniLibsDir = project.location.resolve("src/main/jniLibs")
        PathSubject.assertThat(jniLibsDir).exists()
        assertThat(jniLibsDir.listDirectoryEntries().map { it.name }).doesNotContain("x86_64")

        // Build again with the same command.
        val result2 = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86_64,x86")
            .run("assembleDebug")

        // we expect no .so files in the APK since there are no x86_64 .so files available, and we
        // also expect a warning about the missing .so files.
        project.assertCorrectApk(DEBUG.fromIntermediates()) {
            doesNotContain("lib/x86_64/libapp.so")
            doesNotContain("lib/x86/libapp.so")
        }
        result2.stdout.use { scanner ->
            ScannerSubject.assertThat(scanner).contains(
                "There are no .so files available to package in the APK for x86_64."
            )
        }

        // Add explicit abiFilters and remove x86 .so files
        project.reconfigure(buildFileOnly = true) {
            android {
                defaultConfig {
                    ndk {
                        abiFilters += listOf("x86_64", "x86", "arm64-v8a")
                    }
                }
            }
        }

        project.removeSoFiles(listOf("x86"))
        PathSubject.assertThat(jniLibsDir).exists()
        assertThat(jniLibsDir.listDirectoryEntries().map { it.name }).doesNotContain("x86")

        // Build again with the same command.
        val result3 = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86_64,x86")
            .run("assembleDebug")

        // we expect only arm64-v8a .so files in the APK, and we also expect a warning about the
        // missing x86 and x86_64 .so files.
        project.assertCorrectApk(DEBUG.fromIntermediates()) {
            contains("lib/arm64-v8a/libapp.so")
            doesNotContain("lib/x86_64/libapp.so")
            doesNotContain("lib/x86/libapp.so")
        }
        result3.stdout.use { scanner ->
            ScannerSubject.assertThat(scanner).contains(
                "There are no .so files available to package in the APK for x86, x86_64."
            )
        }
    }

    @Test
    fun testMissingSoFiles_WithSplits() {
        val build = rule.build {
            enableSplits(listOf("x86", "armeabi-v7a", "x86_64", "arm64-v8a"))
        }
        val project = build.androidApplication(":app")

        // Build first with all .so files present. Inject x86_64 first, followed by x86
        val result1 = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86_64,x86")
            .run("assembleDebug")

        // we expect the x86_64 APK to be created with the x86_64 .so files, and we also don't
        // expect a warning about missing .so files in this case.
        project.assertCorrectApk(x86_64Selection.fromIntermediates()) {
            contains("lib/x86_64/libapp.so")
            doesNotContain("lib/x86/libapp.so")
        }
        project.assertDoesNotExist(DEBUG.fromIntermediates())
        project.assertDoesNotExist(x86Selection.fromIntermediates())
        result1.stdout.use { scanner ->
            ScannerSubject.assertThat(scanner).doesNotContain("There are no .so files available")
        }

        // remove x86_64 .so files
        project.removeSoFiles(listOf("x86_64"))
        val jniLibsDir = project.location.resolve("src/main/jniLibs")
        PathSubject.assertThat(jniLibsDir).exists()
        assertThat(jniLibsDir.listDirectoryEntries().map { it.name }).doesNotContain("x86_64")

        // Build again with the same command.
        val result2 = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86_64,x86")
            .run("assembleDebug")

        // we expect the x86_64 APK to be created, but we don't expect any .so files in the APK
        // since there were no "source" x86_64 .so files. We expect a warning about the missing .so
        // files.
        val apk2 = project.assertCorrectApk(x86_64Selection.fromIntermediates()) {
            doesNotContain("lib/x86_64/libapp.so")
            doesNotContain("lib/x86/libapp.so")
        }
        project.assertDoesNotExist(DEBUG.fromIntermediates())
        project.assertDoesNotExist(x86Selection.fromIntermediates())
        result2.stdout.use { scanner ->
            ScannerSubject.assertThat(scanner).contains(
                "There are no .so files available to package in the APK for x86_64."
            )
        }
    }

    @Test
    fun testPackagingTargetAbiCanBeDisabled() {
        val build = rule.build
        val project = build.androidProject(":app")

        // Run the build with target ABI but set BUILD_ONLY_TARGET_ABI to false,
        // check that APK contains native libraries for multiple ABIs
        build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86")
            .with(BooleanOption.BUILD_ONLY_TARGET_ABI, false)
            .run("clean", "assembleDebug")

        project.assertApk(DEBUG.fromIntermediates()) {
            contains("lib/" + Abi.X86.tag + "/libapp.so")
            contains("lib/" + Abi.ARM64_V8A.tag + "/libapp.so")
        }
    }

    private fun AndroidProject<ApplicationExtension>.assertCorrectApk(
        apkSelector: ApkSelector,
        action: (ApkSubject.() -> Unit)? = null
    ) {
        assertApk(apkSelector) {
            exists()
            contains("META-INF/MANIFEST.MF")
            contains("res/layout/main.xml")
            contains("AndroidManifest.xml")
            contains("classes.dex")
            contains("resources.arsc")
            action?.invoke(this)
        }
    }

    private fun AndroidProject<ApplicationExtension>.assertDoesNotExist(apkSelector: ApkSelector) {
        if (this.hasApk(apkSelector)) {
            fail("APK ($apkSelector) exist for ${this.location}")
        }
    }

    private fun GradleProjectFiles.createOriginalSoFile(
        abi: String,
        filename: String,
        content: String
    ) {
        add("src/main/jniLibs/$abi/$filename", content)
    }

    private fun GradleProject.removeSoFiles(abis: List<String>) {
        abis.forEach {
            val folder = location.resolve("src/main/jniLibs/$it")
            FileUtils.deleteRecursivelyIfExists(folder.toFile())
        }
    }

    private fun GradleBuildDefinition.enableSplits(abis: List<String>) {
        androidApplication(":app") {
            android {
                splits {
                    abi {
                        isEnable = true
                        reset()
                        include(*abis.toTypedArray<String>())
                        isUniversalApk = true
                    }
                }
            }
        }
    }
}
