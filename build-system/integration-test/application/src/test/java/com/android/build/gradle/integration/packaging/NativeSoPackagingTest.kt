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
import com.android.build.gradle.integration.common.output.AbstractZipSubject
import com.android.build.gradle.integration.common.truth.AbstractAndroidSubject
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.integration.common.truth.TruthHelper
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.dsl.ModulePropertyKey
import com.android.build.gradle.options.StringOption
import com.android.bundle.Config
import com.android.bundle.Config.UncompressNativeLibraries
import com.android.testutils.TestUtils
import com.android.testutils.apk.Apk
import com.android.utils.FileUtils
import com.google.common.base.Charsets
import com.google.common.io.Files
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
        Files.asCharSink(File(resFolder, "libjar.so"), Charsets.UTF_8).write("jar:abcd")

        val jar2Dir = jarProject2.projectDir
        val res2Folder = FileUtils.join(jar2Dir, "src", "main", "resources", "lib", "x86")
        FileUtils.mkdirs(res2Folder)
        Files.asCharSink(File(res2Folder, "libjar2.so"), Charsets.UTF_8).write("jar2:abcd")
    }

    private fun createOriginalSoFile(
        projectFolder: File,
        dimension: String,
        filename: String,
        content: String
    ) {
        val assetFolder = FileUtils.join(projectFolder, "src", dimension, "jniLibs", "x86")
        FileUtils.mkdirs(assetFolder)
        Files.asCharSink(File(assetFolder, filename), Charsets.UTF_8).write(content)
    }

    @Test
    fun testNonIncrementalPackaging() {
        project.executor().run("clean", "assembleDebug", "assembleAndroidTest")

        // check the files are there. Start from the bottom of the dependency graph
        checkAar(libProject2, "liblibrary2.so", "library2:abcd")
        checkTestApk(libProject2, "liblibrary2.so", "library2:abcd")
        checkTestApk(libProject2, "liblibrary2test.so", "library2Test:abcd")

        checkAar(libProject, "liblibrary.so", "library:abcd")
        // aar does not contain dependency's assets
        checkAar(libProject, "liblibrary2.so", null)
        // test apk contains both test-ony assets, lib assets, and dependency assets.
        checkTestApk(libProject, "liblibrary.so", "library:abcd")
        checkTestApk(libProject, "liblibrary2.so", "library2:abcd")
        checkTestApk(libProject, "liblibrarytest.so", "libraryTest:abcd")
        // but not the assets of the dependency's own test
        checkTestApk(libProject, "liblibrary2test.so", null)

        // app contain own assets + all dependencies' assets.
        checkApk(appProject, "libapp.so", "app:abcd")
        checkApk(appProject, "liblibrary.so", "library:abcd")
        checkApk(appProject, "liblibrary2.so", "library2:abcd")
        checkApk(appProject, "libjar.so", "jar:abcd")
        checkApk(appProject, "libjar2.so", "jar2:abcd")
        checkTestApk(appProject, "libapptest.so", "appTest:abcd")
        // app test does not contain dependencies' own test assets.
        checkTestApk(appProject, "liblibrarytest.so", null)
        checkTestApk(appProject, "liblibrary2test.so", null)
    }

    // ---- APP DEFAULT ---
    @Test
    fun testAppProjectWithNewAssetFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addFile("src/main/jniLibs/x86/libnewapp.so", "newfile content")
            execute("app:assembleDebug")
            checkApk(appProject, "libnewapp.so", "newfile content")
        }
    }

    @Test
    fun testAppProjectWithRemovedAssetFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.removeFile("src/main/jniLibs/x86/libapp.so")
            execute("app:assembleDebug")
            checkApk(appProject, "libapp.so", null)
        }
    }

    @Test
    fun testAppProjectWithRenamedAssetFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.removeFile("src/main/jniLibs/x86/libapp.so")
            it.addFile("src/main/jniLibs/x86/moved_libapp.so", "app:abcd")
            execute("app:assembleDebug")

            checkApk(appProject, "libapp.so", null)
            checkApk(appProject, "moved_libapp.so", "app:abcd")
        }
    }

    @Test
    fun testAppProjectWithAssetFileWithChangedAbi() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.removeFile("src/main/jniLibs/x86/libapp.so")
            it.addFile("src/main/jniLibs/x86_64/libapp.so", "app:abcd")
            execute("app:assembleDebug")

            checkApk(appProject, "libapp.so", null)
            checkApk(appProject, "x86_64", "libapp.so", "app:abcd")
        }
    }

    @Test
    fun testAppProjectWithModifiedAssetFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.replaceFile("src/main/jniLibs/x86/libapp.so", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "libapp.so", "new content")
        }
    }

    @Test
    fun testAppProjectWithNewAssetFileOverridingDependency() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addFile("src/main/jniLibs/x86/liblibrary.so", "new content")
            val result = execute("app:assembleDebug")
            result.stdout.use { stdout ->
                assertThat(stdout)
                    .contains("2 files found for path 'lib/x86/liblibrary.so'.")
            }
            checkApk(appProject, "liblibrary.so", "new content")

            // now remove it to test it works in the other direction
            it.removeFile("src/main/jniLibs/x86/liblibrary.so")
            execute("app:assembleDebug")
            checkApk(appProject, "liblibrary.so", "library:abcd")
        }
    }

    @Test
    fun testAppProjectWithNewAssetFileInDebugSourceSet() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addFile("src/debug/jniLibs/x86/libapp.so", "new content")
            execute("app:assembleDebug")

            checkApk(appProject, "libapp.so", "new content")

            // now remove it to test it works in the other direction
            it.removeFile("src/debug/jniLibs/x86/libapp.so")
            execute("app:assembleDebug")
            checkApk(appProject, "libapp.so", "app:abcd")
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

            checkApk(appProject, "liblibrary.so", "library:abcd")
            checkApk(appProject, "liblibrary2.so", "library2:abcd")
            checkApk(appProject, "libjar.so", "jar:abcd")
            checkApk(appProject, "libjar2.so", "jar2:abcd")
        }
    }

    @Test
    fun testAppProjectWithModifiedAssetInDependency() {
        execute("app:clean", "library:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.replaceFile("src/main/jniLibs/x86/liblibrary.so", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "liblibrary.so", "new content")
        }
    }

    @Test
    fun testAppProjectWithAddedAssetInDependency() {
        execute("app:clean", "library:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/main/jniLibs/x86/libnewlibrary.so", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "libnewlibrary.so", "new content")
        }
    }

    @Test
    fun testAppProjectWithRemovedAssetInDependency() {
        execute("app:clean", "library:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.removeFile("src/main/jniLibs/x86/liblibrary.so")
            execute("app:assembleDebug")
            checkApk(appProject, "liblibrary.so", null)
        }
    }

    // ---- APP TEST ---
    @Test
    fun testAppProjectTestWithNewAssetFile() {
        execute("app:clean", "app:assembleAT")

        TemporaryProjectModification.doTest(appProject) {
            it.addFile("src/androidTest/jniLibs/x86/libnewapp.so", "new file content")
            execute("app:assembleAT")
            checkTestApk(appProject, "libnewapp.so", "new file content")
        }
    }

    @Test
    fun testAppProjectTestWithRemovedAssetFile() {
        execute("app:clean", "app:assembleAT")

        TemporaryProjectModification.doTest(appProject) {
            it.removeFile("src/androidTest/jniLibs/x86/libapptest.so")
            execute("app:assembleAT")
            checkTestApk(appProject, "libapptest.so", null)
        }
    }

    @Test
    fun testAppProjectTestWithModifiedAssetFile() {
        execute("app:clean", "app:assembleAT")

        TemporaryProjectModification.doTest(appProject) {
            it.replaceFile("src/androidTest/jniLibs/x86/libapptest.so", "new content")
            execute("app:assembleAT")
            checkTestApk(appProject, "libapptest.so", "new content")
        }
    }

    // ---- LIB DEFAULT ---
    @Test
    fun testLibProjectWithNewAssetFile() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/main/jniLibs/x86/libnewlibrary.so", "newfile content")
            execute("library:assembleDebug")
            checkAar(libProject, "libnewlibrary.so", "newfile content")
        }
    }

    @Test
    fun testLibProjectWithRemovedAssetFile() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.removeFile("src/main/jniLibs/x86/liblibrary.so")
            execute("library:assembleDebug")
            checkAar(libProject, "liblibrary.so", null)
        }
    }

    @Test
    fun testLibProjectWithModifiedAssetFile() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.replaceFile("src/main/jniLibs/x86/liblibrary.so", "new content")
            execute("library:assembleDebug")
            checkAar(libProject, "liblibrary.so", "new content")
        }
    }

    @Test
    fun testLibProjectWithNewAssetFileInDebugSourceSet() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/debug/jniLibs/x86/liblibrary.so", "new content")
            execute("library:assembleDebug")

            checkAar(libProject, "liblibrary.so", "new content")

            // now remove it to test it works in the other direction
            it.removeFile("src/debug/jniLibs/x86/liblibrary.so")
            execute("library:assembleDebug")
            checkAar(libProject, "liblibrary.so", "library:abcd")
        }
    }

    // ---- LIB TEST ---
    @Test
    fun testLibProjectTestWithNewAssetFile() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/androidTest/jniLibs/x86/libnewlibrary.so", "new file content")
            execute("library:assembleAT")
            checkTestApk(libProject, "libnewlibrary.so", "new file content")
        }
    }

    @Test
    fun testLibProjectTestWithRemovedAssetFile() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.removeFile("src/androidTest/jniLibs/x86/liblibrarytest.so")
            execute("library:assembleAT")
            checkTestApk(libProject, "liblibrarytest.so", null)
        }
    }

    @Test
    fun testLibProjectTestWithModifiedAssetFile() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.replaceFile("src/androidTest/jniLibs/x86/liblibrarytest.so", "new content")
            execute("library:assembleAT")
            checkTestApk(libProject, "liblibrarytest.so", "new content")
        }
    }

    @Test
    fun testLibProjectTestWithNewAssetFileOverridingTestedLib() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/androidTest/jniLibs/x86/liblibrary.so", "new content")
            val result = execute("library:assembleAT")
            result.stdout.use { stdout ->
                assertThat(stdout)
                    .contains("2 files found for path 'lib/x86/liblibrary.so'.")
            }
            checkTestApk(libProject, "liblibrary.so", "new content")

            // now remove it to test it works in the other direction
            it.removeFile("src/androidTest/jniLibs/x86/liblibrary.so")
            execute("library:assembleAT")
            checkTestApk(libProject, "liblibrary.so", "library:abcd")
        }
    }

    @Test
    fun testLibProjectTestWithNewAssetFileOverridingDepenency() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/androidTest/jniLibs/x86/liblibrary2.so", "new content")
            val result = execute("library:assembleAT")
            result.stdout.use { stdout ->
                assertThat(stdout)
                    .contains("2 files found for path 'lib/x86/liblibrary2.so'.")
            }
            checkTestApk(libProject, "liblibrary2.so", "new content")

            // now remove it to test it works in the other direction
            it.removeFile("src/androidTest/jniLibs/x86/liblibrary2.so")
            execute("library:assembleAT")
            checkTestApk(libProject, "liblibrary2.so", "library2:abcd")
        }
    }

    // ---- TEST DEFAULT ---
    @Test
    fun testTestProjectWithNewAssetFile() {
        project.executor().run("test:clean", "test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) {
            it.addFile("src/main/jniLibs/x86/libnewtest.so", "newfile content")
            this.project.executor().run("test:assembleDebug")
            checkApk(testProject, "libnewtest.so", "newfile content")
        }
    }

    @Test
    fun testTestProjectWithRemovedAssetFile() {
        project.executor().run("test:clean", "test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) {
            it.removeFile("src/main/jniLibs/x86/libtest.so")
            this.project.executor().run("test:assembleDebug")
            checkApk(testProject, "libtest.so", null)
        }
    }

    @Test
    fun testTestProjectWithModifiedAssetFile() {
        project.executor().run("test:clean", "test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) {
            it.replaceFile("src/main/jniLibs/x86/libtest.so", "new content")
            this.project.executor().run("test:assembleDebug")
            checkApk(testProject, "libtest.so", "new content")
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

        Apk(extractedApks.listFiles().filterNotNull<File>().first<File> {
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
        checkApk(appProject, "libapp.so", "app:abcd")
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

        checkApk(appProject, "libapp.so", "app:abcd")
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

        checkApk(appProject, "libapp.so", "app:abcd")
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

    /**
     * check an apk has (or not) the given asset file name.
     *
     *
     * If the content is non-null the file is expected to be there with the same content. If the
     * content is null the file is not expected to be there.
     *
     * @param project the project
     * @param filename the filename
     * @param content the content
     */
    private fun checkApk(
        project: GradleTestProject, filename: String, content: String?
    ) {
        checkApk(project, "x86", filename, content)
    }

    /**
     * check an apk has (or not) the given asset file name.
     *
     *
     * If the content is non-null the file is expected to be there with the same content. If the
     * content is null the file is not expected to be there.
     *
     * @param project the project
     * @param abi the abi
     * @param filename the filename
     * @param content the content
     */
    private fun checkApk(
        project: GradleTestProject,
        abi: String,
        filename: String,
        content: String?
    ) {
        val apk = project.getApk("debug")
        check(TruthHelper.assertThatApk(apk), "lib", abi, filename, content)
        PackagingTests.checkZipAlign(apk)
    }

    /**
     * check a test apk has (or not) the given asset file name.
     *
     *
     * If the content is non-null the file is expected to be there with the same content. If the
     * content is null the file is not expected to be there.
     *
     * @param project the project
     * @param filename the filename
     * @param content the content
     */
    private fun checkTestApk(
        project: GradleTestProject,
        filename: String,
        content: String?
    ) {
        check(TruthHelper.assertThat(project.testApk), "lib", "x86", filename, content)
    }

    /**
     * check an aat has (or not) the given asset file name.
     *
     *
     * If the content is non-null the file is expected to be there with the same content. If the
     * content is null the file is not expected to be there.
     *
     * @param project the project
     * @param filename the filename
     * @param content the content
     */
    private fun checkAar(
        project: GradleTestProject,
        filename: String,
        content: String?
    ) {
        project.assertAar(AarSelector.DEBUG) {
            check(this, "jni", "x86", filename, content)
        }
    }

    private fun check(
        subject: AbstractAndroidSubject<*, *>,
        folderName: String,
        abi: String,
        filename: String,
        content: String?
    ) {
        if (content != null) {
            subject.containsFileWithContent("$folderName/$abi/$filename", content)
        } else {
            subject.doesNotContain("$folderName/$abi/$filename")
        }
    }

    private fun check(
        subject: AbstractZipSubject<*, *>,
        folderName: String,
        abi: String,
        filename: String,
        content: String?
    ) {
        if (content != null) {
            subject.textFile("$folderName/$abi/$filename").isEqualTo(content)
        } else {
            subject.doesNotContain("$folderName/$abi/$filename")
        }
    }
}
