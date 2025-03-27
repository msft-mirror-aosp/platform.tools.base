/*
 * Copyright (C) 2015 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.builder
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GeneratesAar
import com.android.build.gradle.integration.common.fixture.project.GeneratesApk
import com.android.build.gradle.integration.common.output.AbstractAndroidArchiveSubject
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.dsl.ModulePropertyKey
import com.android.build.gradle.options.StringOption
import com.android.bundle.Config
import com.android.bundle.Config.UncompressNativeLibraries
import com.android.testutils.TestUtils
import com.android.testutils.apk.Apk
import com.android.utils.FileUtils
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** test for packaging of asset files.  */
class NativeSoPackagingTest {
    @get:Rule
    val project: GradleTestProject = builder()
        .fromTestProject("projectWithModules")
        .create()

    private lateinit var appProject: GradleTestProject
    private lateinit var libProject: GradleTestProject
    private lateinit var libProject2: GradleTestProject
    private lateinit var testProject: GradleTestProject
    private lateinit var jarProject: GradleTestProject
    private lateinit var jarProject2: GradleTestProject

    private fun execute(vararg tasks: String): GradleBuildResult {
        // TODO: Remove once we understand the cause of flakiness.
        TestUtils.waitForFileSystemTick()
        return project.executor().run(*tasks)
    }

    @Before
    fun setUp() {
        appProject = project.getSubproject("app")
        libProject = project.getSubproject("library")
        libProject2 = project.getSubproject("library2")
        testProject = project.getSubproject("test")
        jarProject = project.getSubproject("jar")
        jarProject2 = project.getSubproject("jar2")

        // rewrite settings.gradle to remove un-needed modules
        project.setIncludedProjects("app", "library", "library2", "test", "jar", "jar2")

        // setup dependencies.
        TestFileUtils.appendToFile(
            appProject.buildFile,
            """
                android {
                    publishNonDefault = true
                }

                dependencies {
                    api project(':library')
                    api project(':jar')
                    api project(':jar2')
                }
            """.trimIndent()
        )

        TestFileUtils.appendToFile(
            libProject.buildFile, """
                dependencies {
                   api project(':library2')
                }
            """.trimIndent()
        )

        TestFileUtils.appendToFile(
            testProject.buildFile,
            """
                android {
                    targetProjectPath ':app'
                    targetVariant 'debug'
                }
            """.trimIndent()
        )

        // put some default files in the 4 projects, to check non incremental packaging as well,
        // and to provide files to change to test incremental support.
        val appDir = appProject.projectDir
        createOriginalSoFile(appDir, "main", "libapp.so", "app:abcd")
        createOriginalSoFile(appDir, "androidTest", "libapptest.so", "appTest:abcd")

        val testDir = testProject.projectDir
        createOriginalSoFile(testDir, "main", "libtest.so", "test:abcd")

        val libDir = libProject.projectDir
        createOriginalSoFile(libDir, "main", "liblibrary.so", "library:abcd")
        createOriginalSoFile(libDir, "androidTest", "liblibrarytest.so", "libraryTest:abcd")

        val lib2Dir = libProject2.projectDir
        createOriginalSoFile(lib2Dir, "main", "liblibrary2.so", "library2:abcd")
        createOriginalSoFile(lib2Dir, "androidTest", "liblibrary2test.so", "library2Test:abcd")

        val jarDir = jarProject.projectDir
        val resFolder = FileUtils.join(jarDir, "src", "main", "resources", "lib", "x86")
        FileUtils.mkdirs(resFolder)
        File(resFolder, "libjar.so").writeBytes("jar:abcd".toByteArray())

        val jar2Dir = jarProject2.projectDir
        val res2Folder = FileUtils.join(jar2Dir, "src", "main", "resources", "lib", "x86")
        FileUtils.mkdirs(res2Folder)
        File(res2Folder, "libjar2.so").writeBytes("jar2:abcd".toByteArray())
    }

    private fun createOriginalSoFile(
        projectFolder: File,
        dimension: String,
        filename: String,
        content: String
    ) {
        val assetFolder = FileUtils.join(projectFolder, "src", dimension, "jniLibs", "x86")
        FileUtils.mkdirs(assetFolder)
        File(assetFolder, filename).writeBytes(content.toByteArray())
    }

    @Test
    fun testAlignment() {
        execute("app:assembleDebug", "app:assembleAT", "library:assembleAT")

        appProject.assertApk(ApkSelector.DEBUG) {
            validateZipAlignment()
        }

        appProject.assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
            validateZipAlignment()
        }

        libProject.assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
            validateZipAlignment()
        }
    }

    @Test
    fun testNonIncrementalPackaging() {
        project.executor().run("clean", "assembleDebug", "assembleAndroidTest")

        // check the files are there. Start from the bottom of the dependency graph
        libProject2.checkAarJniLibs("liblibrary2.so".withContent("library2:abcd"))
        libProject2.checkTestApkJniLibs(
            "liblibrary2.so".withContent("library2:abcd"),
            "liblibrary2test.so".withContent("library2Test:abcd")
        )

        // aar does not contain dependency's assets
        libProject.checkAarJniLibs("liblibrary.so".withContent("library:abcd"))
        // test apk contains both test-ony assets, lib assets, and dependency assets.
        // but not the assets of the dependency's own test
        libProject.checkTestApkJniLibs(
            "liblibrary.so".withContent("library:abcd"),
            "liblibrary2.so".withContent("library2:abcd"),
            "liblibrarytest.so".withContent("libraryTest:abcd")
        )

        // app contain own assets + all dependencies' assets.
        appProject.checkApkJniLibs(
            "libapp.so".withContent("app:abcd"),
            "liblibrary.so".withContent("library:abcd"),
            "liblibrary2.so".withContent("library2:abcd"),
            "libjar.so".withContent("jar:abcd"),
            "libjar2.so".withContent("jar2:abcd")
        )

        // app test does not contain dependencies' own test assets.
        appProject.checkTestApkJniLibs("libapptest.so".withContent("appTest:abcd"))
    }

    // ---- APP DEFAULT ---
    @Test
    fun testAppProjectWithNewJniFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addBinaryFile("src/main/jniLibs/x86/libnewapp.so", "newfile content")
            execute("app:assembleDebug")
            appProject.checkApkJniLibs(
                "libnewapp.so".withContent("newfile content"),
                "liblibrary2.so", "liblibrary.so", "libjar2.so", "libjar.so", "libapp.so"
            )
        }
    }

    @Test
    fun testAppProjectWithRemovedJniFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.removeFile("src/main/jniLibs/x86/libapp.so")
            execute("app:assembleDebug")
            appProject.checkApkJniLibs(
                "liblibrary2.so", "liblibrary.so", "libjar2.so", "libjar.so"
            )
        }
    }

    @Test
    fun testAppProjectWithRenamedJniFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.removeFile("src/main/jniLibs/x86/libapp.so")
            it.addBinaryFile("src/main/jniLibs/x86/moved_libapp.so", "app:abcd")
            execute("app:assembleDebug")

            appProject.checkApkJniLibs(
                "moved_libapp.so".withContent("app:abcd"),
                "liblibrary2.so", "liblibrary.so", "libjar2.so", "libjar.so"
            )
        }
    }

    @Test
    fun testAppProjectWithJniFileWithChangedAbi() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.removeFile("src/main/jniLibs/x86/libapp.so")
            it.addBinaryFile("src/main/jniLibs/x86_64/libapp.so", "app:abcd")
            execute("app:assembleDebug")

            appProject.checkApkJniLibsForAbi("x86_64", "libapp.so".withContent("app:abcd"))
        }
    }

    @Test
    fun testAppProjectWithModifiedJniFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.replaceBinaryFile("src/main/jniLibs/x86/libapp.so", "new content")
            execute("app:assembleDebug")
            appProject.checkApkJniLibs(
                "libapp.so".withContent("new content"),
                "liblibrary2.so", "liblibrary.so", "libjar2.so", "libjar.so"
            )
        }
    }

    @Test
    fun testAppProjectWithNewJniFileOverridingDependency() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addBinaryFile("src/main/jniLibs/x86/liblibrary.so", "new content")
            val result = execute("app:assembleDebug")
            result.stdout.use { stdout ->
                assertThat(stdout)
                    .contains("2 files found for path 'lib/x86/liblibrary.so'.")
            }
            appProject.checkApkJniLibs(
                "liblibrary.so".withContent("new content"),
                "liblibrary2.so", "libjar2.so", "libjar.so", "libapp.so"
            )

            // now remove it to test it works in the other direction
            it.removeFile("src/main/jniLibs/x86/liblibrary.so")
            execute("app:assembleDebug")
            appProject.checkApkJniLibs(
                "liblibrary.so".withContent("library:abcd"),
                "liblibrary2.so", "libjar2.so", "libjar.so", "libapp.so"
            )
        }
    }

    @Test
    fun testAppProjectWithNewJniFileInDebugSourceSet() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addBinaryFile("src/debug/jniLibs/x86/libapp.so", "new content")
            execute("app:assembleDebug")

            appProject.checkApkJniLibs(
                "libapp.so".withContent("new content"),
                "liblibrary2.so", "liblibrary.so", "libjar2.so", "libjar.so"
            )

            // now remove it to test it works in the other direction
            it.removeFile("src/debug/jniLibs/x86/libapp.so")
            execute("app:assembleDebug")
            appProject.checkApkJniLibs(
                "libapp.so".withContent("app:abcd"),
                "liblibrary2.so", "liblibrary.so", "libjar2.so", "libjar.so"
            )
        }
    }

    /**
     * Check for correct behavior when the order of pre-merged so files changes. This must be
     * supported in order to use @Classpath annotations on the MergeNativeLibsTask inputs.
     */
    @Test
    fun testAppProjectWithReorderedDeps() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            // change order of dependencies in app from (jar, jar2) to (jar2, jar).
            it.replaceInFile("build.gradle", ":jar2", ":tempJar2")
            it.replaceInFile("build.gradle", ":jar", ":tempJar")
            it.replaceInFile("build.gradle", ":tempJar2", ":jar")
            it.replaceInFile("build.gradle", ":tempJar", ":jar2")
            execute("app:assembleDebug")

            appProject.checkApkJniLibs(
                "liblibrary.so".withContent("library:abcd"),
                "liblibrary2.so".withContent("library2:abcd"),
                "libjar.so".withContent("jar:abcd"),
                "libjar2.so".withContent("jar2:abcd"),
                "libapp.so"
            )
        }
    }

    @Test
    fun testAppProjectWithModifiedJniLibInDependency() {
        execute("app:clean", "library:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.replaceBinaryFile("src/main/jniLibs/x86/liblibrary.so", "new content")
            execute("app:assembleDebug")
            appProject.checkApkJniLibs(
                "liblibrary.so".withContent("new content"),
                "liblibrary2.so", "libjar2.so", "libjar.so", "libapp.so"
            )
        }
    }

    @Test
    fun testAppProjectWithAddedAssetInDependency() {
        execute("app:clean", "library:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.addBinaryFile("src/main/jniLibs/x86/libnewlibrary.so", "new content")
            execute("app:assembleDebug")
            appProject.checkApkJniLibs(
                "libnewlibrary.so".withContent("new content"),
                "liblibrary2.so", "liblibrary.so", "libjar2.so", "libjar.so", "libapp.so"
            )
        }
    }

    @Test
    fun testAppProjectWithRemovedAssetInDependency() {
        execute("app:clean", "library:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.removeFile("src/main/jniLibs/x86/liblibrary.so")
            execute("app:assembleDebug")
            appProject.checkApkJniLibs(
                "liblibrary2.so", "libjar2.so", "libjar.so", "libapp.so"
            )
        }
    }

    // ---- APP TEST ---
    @Test
    fun testAppProjectTestWithNewJniFile() {
        execute("app:clean", "app:assembleAT")

        TemporaryProjectModification.doTest(appProject) {
            it.addBinaryFile("src/androidTest/jniLibs/x86/libnewapp.so", "new file content")
            execute("app:assembleAT")
            appProject.checkTestApkJniLibs(
                "libnewapp.so".withContent("new file content"),
                "libapptest.so"
            )
        }
    }

    @Test
    fun testAppProjectTestWithRemovedJniFile() {
        execute("app:clean", "app:assembleAT")

        TemporaryProjectModification.doTest(appProject) {
            it.removeFile("src/androidTest/jniLibs/x86/libapptest.so")
            execute("app:assembleAT")
            appProject.checkTestApkJniLibs()
        }
    }

    @Test
    fun testAppProjectTestWithModifiedJniFile() {
        execute("app:clean", "app:assembleAT")

        TemporaryProjectModification.doTest(appProject) {
            it.replaceBinaryFile("src/androidTest/jniLibs/x86/libapptest.so", "new content")
            execute("app:assembleAT")
            appProject.checkTestApkJniLibs("libapptest.so".withContent("new content"))
        }
    }

    // ---- LIB DEFAULT ---
    @Test
    fun testLibProjectWithNewJniFile() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.addBinaryFile("src/main/jniLibs/x86/libnewlibrary.so", "newfile content")
            execute("library:assembleDebug")
            libProject.checkAarJniLibs(
                "libnewlibrary.so".withContent("newfile content"),
                "liblibrary.so"
            )
        }
    }

    @Test
    fun testLibProjectWithRemovedJniFile() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.removeFile("src/main/jniLibs/x86/liblibrary.so")
            execute("library:assembleDebug")
            libProject.checkAarJniLibs()
        }
    }

    @Test
    fun testLibProjectWithModifiedJniFile() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.replaceBinaryFile("src/main/jniLibs/x86/liblibrary.so", "new content")
            execute("library:assembleDebug")
            libProject.checkAarJniLibs("liblibrary.so".withContent("new content"))
        }
    }

    @Test
    fun testLibProjectWithNewJniFileInDebugSourceSet() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.addBinaryFile("src/debug/jniLibs/x86/liblibrary.so", "new content")
            execute("library:assembleDebug")

            libProject.checkAarJniLibs("liblibrary.so".withContent("new content"))
        }

        // file is removed, test it works in the other direction
        execute("library:assembleDebug")
        libProject.checkAarJniLibs("liblibrary.so".withContent("library:abcd"))
    }

    // ---- LIB TEST ---
    @Test
    fun testLibProjectTestWithNewJniFile() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.addBinaryFile("src/androidTest/jniLibs/x86/libnewlibrary.so", "new file content")
            execute("library:assembleAT")
            libProject.checkTestApkJniLibs(
                "libnewlibrary.so".withContent("new file content"),
                "liblibrarytest.so", "liblibrary2.so", "liblibrary.so"
            )
        }
    }

    @Test
    fun testLibProjectTestWithRemovedJniFile() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.removeFile("src/androidTest/jniLibs/x86/liblibrarytest.so")
            execute("library:assembleAT")
            libProject.checkTestApkJniLibs("liblibrary2.so", "liblibrary.so")
        }
    }

    @Test
    fun testLibProjectTestWithModifiedJniFile() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.replaceBinaryFile("src/androidTest/jniLibs/x86/liblibrarytest.so", "new content")
            execute("library:assembleAT")
            libProject.checkTestApkJniLibs(
                "liblibrarytest.so".withContent("new content"),
                "liblibrary2.so",
                "liblibrary.so"
            )
        }
    }

    @Test
    fun testLibProjectTestWithNewJniFileOverridingTestedLib() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.addBinaryFile("src/androidTest/jniLibs/x86/liblibrary.so", "new content")
            val result = execute("library:assembleAT")
            result.stdout.use { stdout ->
                assertThat(stdout)
                    .contains("2 files found for path 'lib/x86/liblibrary.so'.")
            }
            libProject.checkTestApkJniLibs(
                "liblibrary.so".withContent("new content"),
                "liblibrarytest.so",
                "liblibrary2.so"
            )
        }

        // file is removed, test it works in the other direction
        execute("library:assembleAT")
        libProject.checkTestApkJniLibs(
            "liblibrary.so".withContent("library:abcd"),
            "liblibrarytest.so",
            "liblibrary2.so"
        )
    }

    @Test
    fun testLibProjectTestWithNewJniFileOverridingDepenency() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.addBinaryFile("src/androidTest/jniLibs/x86/liblibrary2.so", "new content")
            val result = execute("library:assembleAT")
            result.stdout.use { stdout ->
                assertThat(stdout)
                    .contains("2 files found for path 'lib/x86/liblibrary2.so'.")
            }
            libProject.checkTestApkJniLibs(
                "liblibrary2.so".withContent("new content"),
                "liblibrarytest.so",
                "liblibrary.so"
            )
        }

        // file is removed, test it works in the other direction
        execute("library:assembleAT")
        libProject.checkTestApkJniLibs(
            "liblibrary2.so".withContent("library2:abcd"),
            "liblibrarytest.so",
            "liblibrary.so"
        )
    }

    // ---- TEST DEFAULT ---
    @Test
    fun testTestProjectWithNewJniFile() {
        project.executor().run("test:clean", "test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) {
            it.addBinaryFile("src/main/jniLibs/x86/libnewtest.so", "newfile content")
            this.project.executor().run("test:assembleDebug")
            testProject.checkApkJniLibs(
                "libnewtest.so".withContent("newfile content"),
                "libtest.so"
            )
        }
    }

    @Test
    fun testTestProjectWithRemovedJniFile() {
        project.executor().run("test:clean", "test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) {
            it.removeFile("src/main/jniLibs/x86/libtest.so")
            this.project.executor().run("test:assembleDebug")
            testProject.checkApkJniLibs()
        }
    }

    @Test
    fun testTestProjectWithModifiedJniFile() {
        project.executor().run("test:clean", "test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) {
            it.replaceBinaryFile("src/main/jniLibs/x86/libtest.so", "new content")
            this.project.executor().run("test:assembleDebug")
            testProject.checkApkJniLibs("libtest.so".withContent("new content"))
        }
    }

    // ---- SO ALIGNMENT ----
    private fun checkBundleAlignment(
        expectedPageAlignment: UncompressNativeLibraries.PageAlignment?, pageSize: String?
    ) {
        val apkSelectConfig = project.file("apkSelectConfig.json")
        apkSelectConfig
            .writeText(
                "{\"sdk_version\":34,\"sdk_runtime\":{\"supported\":\"true\"},\"screen_density\":420,\"supported_abis\":[\"x86_64\",\"x86\",\"arm64-v8a\"],\"supported_locales\":[\"en\"]}",
                StandardCharsets.UTF_8
            )
        project.executor()
            .with(StringOption.IDE_APK_SELECT_CONFIG, apkSelectConfig.getAbsolutePath())
            .run(":app:bundleDebug", ":app:extractApksFromBundleForDebug")
        appProject.getBundle(GradleTestProject.ApkType.DEBUG).use { appBundle ->
            BufferedInputStream(
                java.nio.file.Files.newInputStream(
                    appBundle.getEntry("BundleConfig.pb")
                )
            ).use { bundleConfigStream ->
                val config = Config.BundleConfig.parseFrom(bundleConfigStream)
                Truth.assertThat<UncompressNativeLibraries.PageAlignment?>(
                    config.getOptimizations().getUncompressNativeLibraries().getAlignment()
                )
                    .named("bundleConfig optimizations.uncompress_native_libraries.alignment")
                    .isEqualTo(expectedPageAlignment)
            }
        }
        val extractedApks =
            appProject.getIntermediateFile(
                "extracted_apks", "debug", "extractApksFromBundleForDebug"
            )

        Apk(extractedApks.listFiles().filterNotNull().first {
            it.name.startsWith("base-master")
        }).use {
            PackagingTests.checkZipAlignWithPageAlignedSoFiles(it, pageSize)
        }
    }

    @Test
    fun testSharedObjectFilesAlignment4k() {
        TestFileUtils.searchAndReplace(
            appProject.file("src/main/AndroidManifest.xml"),
            "<application ",
            "<application android:extractNativeLibs=\"false\" "
        )
        val flag =
            ModulePropertyKey.OptionalString.NATIVE_LIBRARY_PAGE_SIZE
        TestFileUtils.appendToFile(
            appProject.buildFile,
            """
                android {
                experimentalProperties["${flag.key}"]="4k"
            }
            """.trimIndent()
        )
        execute("app:assembleDebug")
        appProject.checkApkJniLibs(
            "libapp.so".withContent("app:abcd"),
            "liblibrary2.so", "liblibrary.so", "libjar2.so", "libjar.so"
        )
        PackagingTests.checkZipAlignWithPageAlignedSoFiles(appProject.getApk("debug"), "4")
        checkBundleAlignment(UncompressNativeLibraries.PageAlignment.PAGE_ALIGNMENT_4K, "4")
    }

    @Test
    fun testSharedObjectFilesAlignment16k() {
        TestFileUtils.searchAndReplace(
            appProject.file("src/main/AndroidManifest.xml"),
            "<application ",
            "<application android:extractNativeLibs=\"false\" "
        )
        // The default page size is 16k
        execute("app:assembleDebug")

        appProject.checkApkJniLibs(
            "libapp.so".withContent("app:abcd"),
            "liblibrary2.so", "liblibrary.so", "libjar2.so", "libjar.so"
        )
        PackagingTests.checkZipAlignWithPageAlignedSoFiles(appProject.getApk("debug"), "16")
        checkBundleAlignment(
            UncompressNativeLibraries.PageAlignment.PAGE_ALIGNMENT_16K, "16"
        )
    }

    @Test
    fun testSharedObjectFilesAlignment64k() {
        TestFileUtils.searchAndReplace(
            appProject.file("src/main/AndroidManifest.xml"),
            "<application ",
            "<application android:extractNativeLibs=\"false\" "
        )
        val flag =
            ModulePropertyKey.OptionalString.NATIVE_LIBRARY_PAGE_SIZE
        TestFileUtils.appendToFile(
            appProject.buildFile,
            """
                android {
                    experimentalProperties["${flag.key}"]="64k"
                }
            """.trimIndent()
        )
        execute("app:assembleDebug")

        appProject.checkApkJniLibs(
            "libapp.so".withContent("app:abcd"),
            "liblibrary2.so", "liblibrary.so", "libjar2.so", "libjar.so"
        )
        PackagingTests.checkZipAlignWithPageAlignedSoFiles(appProject.getApk("debug"), "64")
        checkBundleAlignment(
            UncompressNativeLibraries.PageAlignment.PAGE_ALIGNMENT_64K, "64"
        )
    }

    @Test
    fun testSharedObjectFilesInvalidAlignment() {
        TestFileUtils.searchAndReplace(
            appProject.file("src/main/AndroidManifest.xml"),
            "<application ",
            "<application android:extractNativeLibs=\"false\" "
        )
        val flag =
            ModulePropertyKey.OptionalString.NATIVE_LIBRARY_PAGE_SIZE
        TestFileUtils.appendToFile(
            appProject.buildFile,
            """
                android {
                    experimentalProperties["${flag.key}"]="0k"
                }
            """.trimIndent()
        )
        TestUtils.waitForFileSystemTick()
        val result = project.executor().expectFailure().run("app:assembleDebug")
        assertThat(result.stderr)
            .contains(
                "Invalid value for ${flag.key}. Supported values are \"4k\", \"16k\", and \"64k\"."
            )
        val result2 = project.executor().expectFailure().run("app:bundleDebug")
        assertThat(result2.stderr)
            .contains(
                "Invalid value for ${flag.key}. Supported values are \"4k\", \"16k\", and \"64k\"."
            )
    }

    private fun TemporaryProjectModification.addBinaryFile(path: String, content: String) {
        addFile(path, content.toByteArray())
    }

    private fun TemporaryProjectModification.replaceBinaryFile(path: String, content: String) {
        modifyFileWithBytes(path) {
            content.toByteArray()
        }
    }
}

/**
 * Checks the DEBUG apk has the specific list of x86 jni libraries. The list must be exhaustive.
 *
 * @param itemList a list of items that must be present in the android archive. The list
 * can either contain [String] to just validate presence, or [StringWithContent] to validate
 * presence and content.
 */
internal fun GeneratesApk.checkApkJniLibs(
    vararg itemList: Any
) {
    checkApkJniLibsForAbi("x86", *itemList)
}

/**
 * Checks the DEBUG apk has the specific list of jni libraries, for a given abi.
 * The list must be exhaustive.
 *
 * @param abi the abi to check
 * @param itemList a list of items that must be present in the android archive. The list
 * can either contain [String] to just validate presence, or [StringWithContent] to validate
 * presence and content.
 */
internal fun GeneratesApk.checkApkJniLibsForAbi(
    abi: String,
    vararg itemList: Any
) {
    assertApk(ApkSelector.DEBUG) {
        checkJniContent(abi, *itemList)
    }
}

/**
 * Checks the DEBUG test apk has the specific list of x86 jni libraries. The list must be
 * exhaustive.
 *
 * @param itemList a list of items that must be present in the android archive. The list
 * can either contain [String] to just validate presence, or [StringWithContent] to validate
 * presence and content.
 */
internal fun GeneratesApk.checkTestApkJniLibs(
    vararg itemList: Any
) {
    assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
        checkJniContent("x86", *itemList)
    }
}

/**
 * Checks the DEBUG aar has the specific list of x86 jni libraries. The list must be exhaustive.
 *
 * @param itemList a list of items that must be present in the android archive. The list
 * can either contain [String] to just validate presence, or [StringWithContent] to validate
 * presence and content.
 */
internal fun GeneratesAar.checkAarJniLibs(
    vararg itemList: Any
) {
    this.assertAar(AarSelector.DEBUG) {
        checkJniContent("x86", *itemList)
    }
}

/**
 * Checks the android archive has the specific list of jni libraries, for a given abi.
 * The list must be exhaustive.
 *
 * @param this@checkAar the project
 * @param abi the abi to check
 * @param itemList a list of items that must be present in the android archive. The list
 * can either contain [String] to just validate presence, or [StringWithContent] to validate
 * presence and content.
 */
internal fun AbstractAndroidArchiveSubject<*, *>.checkJniContent(
    abi: String,
    vararg itemList: Any,
) {
    jniLibs().abi(abi) {
        if (itemList.isEmpty()) {
            isEmpty()
        } else {
            val itemsWithContent = itemList.mapNotNull { it as? StringWithContent }
            val itemNames = itemList.map {
                when (it) {
                    is StringWithContent -> it.name
                    is String -> it
                    else -> throw RuntimeException("Unexpected type in itemList: ${it.javaClass}")
                }
            }

            // check the list
            containsExactly(itemNames)
            for (item in itemsWithContent) {
                bytesOf(item.name).isEqualTo(item.content.toByteArray())
            }
        }
    }
}
