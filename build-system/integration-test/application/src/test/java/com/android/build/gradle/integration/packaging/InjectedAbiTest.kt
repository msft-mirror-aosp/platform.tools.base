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

import com.android.build.api.component.impl.ENABLE_LEGACY_API
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.AndroidApplicationProject
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector.Companion.DEBUG
import com.android.build.gradle.integration.common.fixture.project.ApkSelector.Companion.RELEASE
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.output.ApkSubject
import com.android.build.gradle.integration.common.output.ZipSubject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.internal.core.Abi
import com.android.build.gradle.internal.dsl.ModulePropertyKey.BooleanWithDefault
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import com.android.testutils.truth.PathSubject
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Test APK is packaged correctly when injected ABI exists or changes */
class InjectedAbiTest {

    @get:Rule
    val rule = GradleRule.from {
        androidJavaApplication {
            files {
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
        val project = build.androidApplication()

        // Run the first build with a target ABI, check that only the APK for that ABI is generated
        // and that APK only contains native libraries for target ABI
        build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86")
            .with(BooleanOption.ENABLE_LEGACY_API, true)
            .run("assembleDebug")
            .apply {
                assertTask(":app:packageDebug").didWork()
            }

        project.assertDoesNotExist(DEBUG.fromIntermediates())
        project.assertCorrectApk(x86Selection.fromIntermediates())
        project.assertDoesNotExist(armV7aSelection.fromIntermediates())
        project.assertDoesNotExist(x86_64Selection.fromIntermediates())
        project.assertDoesNotExist(armV8Selection.fromIntermediates())

        project.assertApk(x86Selection.fromIntermediates()) {
            jniLibs().containsExactly("${Abi.X86.tag}/libapp.so")
        }

        // Run the second build with another target ABI, check that another APK for that ABI is
        // generated (and generated correctly--regression test for
        // https://issuetracker.google.com/issues/38481325)
        build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "armeabi-v7a")
            .with(BooleanOption.ENABLE_LEGACY_API, true)
            .run("assembleDebug")
            .apply {
                assertTask(":app:packageDebug").didWork()
            }

        project.assertDoesNotExist(DEBUG.fromIntermediates())
        project.assertDoesNotExist(x86Selection.fromIntermediates())
        project.assertCorrectApk(armV7aSelection.fromIntermediates())
        project.assertDoesNotExist(x86_64Selection.fromIntermediates())
        project.assertDoesNotExist(armV8Selection.fromIntermediates())

        val armeabiV7aLastModifiedTime = java.nio.file.Files.getLastModifiedTime(
            project.getApkLocationForCopy(armV7aSelection.fromIntermediates())
        )

        // Run the third build without any target ABI, check that the APKs for all ABIs are
        // generated (or regenerated)
        build.executor.run("assembleDebug").apply {
            assertTask(":app:packageDebug").didWork()
        }

        project.assertDoesNotExist(DEBUG)
        project.assertCorrectApk(x86Selection)
        project.assertCorrectApk(armV7aSelection)
        project.assertCorrectApk(x86_64Selection)
        project.assertCorrectApk(armV8Selection)

        PathSubject.assertThat(project.getApkLocationForCopy(armV7aSelection))
            .isNewerThan(armeabiV7aLastModifiedTime)

        val x86LastModifiedTime = java.nio.file.Files.getLastModifiedTime(
            project.getApkLocationForCopy(x86Selection)
        )

        // Run the fourth build with a target ABI, check that the APK for that ABI is re-generated
        build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86")
            .with(BooleanOption.ENABLE_LEGACY_API, true)
            .run("assembleDebug")
            .apply {
                assertTask(":app:packageDebug").didWork()
            }

        project.assertDoesNotExist(DEBUG.fromIntermediates())
        project.assertCorrectApk(x86Selection.fromIntermediates())
        project.assertDoesNotExist(armV7aSelection.fromIntermediates())
        project.assertDoesNotExist(x86_64Selection.fromIntermediates())
        project.assertDoesNotExist(armV8Selection.fromIntermediates())

        PathSubject.assertThat(project.getApkLocationForCopy(x86Selection.fromIntermediates()))
            .isNewerThan(x86LastModifiedTime)
    }

    @Test
    fun testInjectedAbiChange_WithoutSplits() {
        val build = rule.build
        val project = build.androidApplication()

        // Run the first build with a target ABI, check that no split APKs are generated
        // and main APK only contains native libraries for target ABI
        var result = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86")
            .with(BooleanOption.ENABLE_LEGACY_API, true)
            .run("assembleDebug")
            .apply {
                assertTask(":app:packageDebug").didWork()
            }

        project.assertCorrectApk(DEBUG.fromIntermediates())
        project.assertDoesNotExist(x86Selection.fromIntermediates())
        project.assertDoesNotExist(armV7aSelection.fromIntermediates())

        project.assertApk(DEBUG.fromIntermediates()) {
            jniLibs().containsExactly("${Abi.X86.tag}/libapp.so")
       }

        val apkLastModifiedTime = java.nio.file.Files.getLastModifiedTime(
            project.getApkLocationForCopy(DEBUG.fromIntermediates())
        )

        // Run the second build with another target ABI, again check that no split APKs are
        // generated (and the main APK is re-generated)
        build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "armeabi-v7a")
            .with(BooleanOption.ENABLE_LEGACY_API, true)
            .run("assembleDebug")
            .apply {
                assertTask(":app:packageDebug").didWork()
            }

        project.assertCorrectApk(DEBUG.fromIntermediates())
        project.assertDoesNotExist(x86Selection.fromIntermediates())
        project.assertDoesNotExist(armV7aSelection.fromIntermediates())

        PathSubject.assertThat(project.getApkLocationForCopy(DEBUG.fromIntermediates()))
            .isNewerThan(apkLastModifiedTime)
    }

    class BuildAllAbisCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                onVariants(selector().withBuildType("release")) {
                    it.experimentalProperties.put(
                        BooleanWithDefault.BUILD_ALL_ABIS_IGNORING_IDE_OPTIMIZATIONS.key,
                        true
                    )
                }
            }
        }
    }

    @Test
    fun testBuildAllAbisFlag() {
        val build = rule.build {
            androidApplication {
                pluginCallbacks += BuildAllAbisCallback::class.java
            }
        }
        val project = build.androidApplication()

        build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86")
            .with(BooleanOption.ENABLE_LEGACY_API, true)
            .run("assemble")

        project.assertApk(RELEASE.fromIntermediates()) {
            jniLibs().containsExactly(
                "${Abi.X86.tag}/libapp.so",
                "${Abi.ARM64_V8A.tag}/libapp.so",
                "${Abi.X86_64.tag}/libapp.so",
                "${Abi.ARMEABI_V7A.tag}/libapp.so"
            )
       }

        // Validate the debug variant did not build all ABIs
        project.assertApk(DEBUG.fromIntermediates()) {
            jniLibs().containsExactly("${Abi.X86.tag}/libapp.so")
       }
    }

    @Test
    fun testMissingSoFiles_WithoutSplits() {
        val build = rule.build
        val project = build.androidApplication()

        // Build first with all .so files present. Inject x86_64 first, followed by x86
        val executor = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86_64,x86")
            .with(BooleanOption.ENABLE_LEGACY_API, true)
        val result1 = executor.run("assembleDebug")

        // we expect x86_64 .so files in the APK (and no x86 .so files) since there are x86_64 .so
        // files available, and we also don't expect a warning about missing .so files in this case.
        project.assertCorrectApk(DEBUG.fromIntermediates()) {
            jniLibs().containsExactly("${Abi.X86_64.tag}/libapp.so")
        }
        result1.stdout.use { scanner ->
            ScannerSubject.assertThat(scanner).doesNotContain("There are no .so files available")
        }

        // remove x86_64 .so files
        project.removeSoFiles(listOf("x86_64"))
        val jniLibsDir = project.resolve("src/main/jniLibs")
        PathSubject.assertThat(jniLibsDir).exists()
        assertThat(jniLibsDir.listDirectoryEntries().map { it.name }).doesNotContain("x86_64")

        // Build again with the same command.
        val result2 = executor.run("assembleDebug")

        // we expect no .so files in the APK since there are no x86_64 .so files available, and we
        // also expect a warning about the missing .so files.
        project.assertCorrectApk(DEBUG.fromIntermediates()) {
            jniLibs().isEmpty()
        }
        result2.stdout.use { scanner ->
            ScannerSubject.assertThat(scanner).contains(
                "There are no .so files available to package in the APK for x86_64."
            )
        }

        // Add explicit abiFilters and remove x86 .so files
        project.reconfigure {
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
        val result3 = executor.run("assembleDebug")

        // we expect only arm64-v8a .so files in the APK, and we also expect a warning about the
        // missing x86 and x86_64 .so files.
        project.assertCorrectApk(DEBUG.fromIntermediates()) {
            jniLibs().containsExactly("arm64-v8a/")
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
        val project = build.androidApplication()

        // Build first with all .so files present. Inject x86_64 first, followed by x86
        val executor = build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86_64,x86")
            .with(BooleanOption.ENABLE_LEGACY_API, true)
        val result1 = executor.run("assembleDebug")

        // we expect the x86_64 APK to be created with the x86_64 .so files, and we also don't
        // expect a warning about missing .so files in this case.
        project.assertCorrectApk(x86_64Selection.fromIntermediates()) {
            jniLibs().containsExactly("${Abi.X86_64.tag}/libapp.so")
        }
        project.assertDoesNotExist(DEBUG.fromIntermediates())
        project.assertDoesNotExist(x86Selection.fromIntermediates())
        result1.stdout.use { scanner ->
            ScannerSubject.assertThat(scanner).doesNotContain("There are no .so files available")
        }

        // remove x86_64 .so files
        project.removeSoFiles(listOf("x86_64"))
        val jniLibsDir = project.resolve("src/main/jniLibs")
        PathSubject.assertThat(jniLibsDir).exists()
        assertThat(jniLibsDir.listDirectoryEntries().map { it.name }).doesNotContain("x86_64")

        // Build again with the same command.
        val result2 = executor.run("assembleDebug")

        // we expect the x86_64 APK to be created, but we don't expect any .so files in the APK
        // since there were no "source" x86_64 .so files. We expect a warning about the missing .so
        // files.
        val apk2 = project.assertCorrectApk(x86_64Selection.fromIntermediates()) {
            jniLibs().isEmpty()
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
        val project = build.androidApplication()

        // Run the build with target ABI but set BUILD_ONLY_TARGET_ABI to false,
        // check that APK contains native libraries for multiple ABIs
        build.executor
            .with(StringOption.IDE_BUILD_TARGET_ABI, "x86")
            .with(BooleanOption.BUILD_ONLY_TARGET_ABI, false)
            .run("clean", "assembleDebug")

        project.assertApk(DEBUG.fromIntermediates()) {
            jniLibs().containsExactly(
                "x86_64/libapp.so",
                "x86/libapp.so",
                "arm64-v8a/libapp.so",
                "armeabi-v7a/libapp.so"
            )
        }
    }

    private fun AndroidApplicationProject.assertCorrectApk(
        apkSelector: ApkSelector,
        action: (ApkSubject.() -> Unit)? = null
    ) {
        ZipSubject.assertThat(getApkLocationForCopy(apkSelector)) {
            entries().containsAtLeast(
                "META-INF/MANIFEST.MF",
                "res/layout/main.xml",
                "AndroidManifest.xml",
                "classes.dex",
                "resources.arsc"
            )
        }
        assertApk(apkSelector) {
            action?.invoke(this)
        }
    }

    private fun AndroidApplicationProject.assertDoesNotExist(apkSelector: ApkSelector) {
        assertApk(apkSelector) {
            doesNotExist()
        }
    }

    private fun GradleProjectFiles.createOriginalSoFile(
        abi: String,
        filename: String,
        content: String
    ) {
        add("src/main/jniLibs/$abi/$filename", content)
    }

    private fun AndroidApplicationProject.removeSoFiles(abis: List<String>) {
        abis.forEach {
            val folder = resolve("src/main/jniLibs/$it")
            FileUtils.deleteRecursivelyIfExists(folder.toFile())
        }
    }

    private fun GradleBuildDefinition.enableSplits(abis: List<String>) {
        androidApplication {
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
