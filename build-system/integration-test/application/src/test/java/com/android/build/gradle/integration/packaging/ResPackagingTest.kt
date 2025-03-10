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

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.builder
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.output.AbstractAndroidArchiveSubject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.utils.FileUtils
import com.google.common.base.Charsets
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

/**
 * test for packaging of android asset files.
 *
 * This only uses raw files. This is not about running aapt tests, this is only about
 * everything around it, so raw files are easier to test in isolation.
 */
class ResPackagingTest {
    // Add a timeout so there's a trace dump on Windows when the test hangs (b/178233111).
    @get:Rule
    val timeout = Timeout.builder()
        .withTimeout(180, TimeUnit.SECONDS)
        .withLookingForStuckThread(true)
        .build()

    @get:Rule
    val project = builder()
        .fromTestProject("projectWithModules")
        .create()

    private val appProject by lazy { project.getSubproject("app") }
    private val libProject by lazy { project.getSubproject("library") }
    private val libProject2 by lazy { project.getSubproject("library2") }
    private val testProject by lazy { project.getSubproject("test") }

    private fun execute(vararg tasks: String) {
        project.executor().run(*tasks)
    }

    @Before
    fun setUp() {

        // rewrite settings.gradle to remove un-needed modules
        project.setIncludedProjects("app", "library", "library2", "test")

        // setup dependencies.
        TestFileUtils.appendToFile(
            appProject.buildFile,
            """android {
                        publishNonDefault = true
                    }

                    dependencies {
                        api project(':library')
                    }
                    """
        )

        libProject.buildFile.appendText(
            """dependencies {
                        api project(':library2')
                    }
                """)

        testProject.buildFile.appendText(
            """android {
                targetProjectPath ':app'
                targetVariant 'debug'
            }
            """)

        // put some default files in the 4 projects, to check non incremental packaging as well,
        // and to provide files to change to test incremental support.
        val appDir = appProject.projectDir
        createOriginalResFile(appDir, "main", "file.txt", "app:abcd")
        createOriginalResFile(appDir, "androidTest", "filetest.txt", "appTest:abcd")

        val testDir = testProject.projectDir
        createOriginalResFile(testDir, "main", "file.txt", "test:abcd")

        val libDir = libProject.projectDir
        createOriginalResFile(libDir, "main", "filelib.txt", "library:abcd")
        createOriginalResFile(libDir, "androidTest", "filelibtest.txt", "libraryTest:abcd")

        val lib2Dir = libProject2!!.projectDir
        createOriginalResFile(lib2Dir, "main", "filelib2.txt", "library2:abcd")
        createOriginalResFile(lib2Dir, "androidTest", "filelib2test.txt", "library2Test:abcd")
    }

    @Test
    fun testNonIncrementalPackaging() {
        execute("assembleDebug", "assembleAndroidTest")

        // chek the files are there. Start from the bottom of the dependency graph
        libProject2.checkAarResources("filelib2.txt".withContent("library2:abcd"))
        libProject2.checkTestApkResources(
            "filelib2.txt".withContent("library2:abcd"),
            "filelib2test.txt".withContent("library2Test:abcd")
        )

        // aar does not contain dependency's assets
        libProject.checkAarResources("filelib.txt".withContent("library:abcd"))

        // test apk contains both test-ony assets, lib assets, and dependency assets.
        // but not the assets of the dependency's own test
        libProject.checkTestApkResources(
            "filelib.txt".withContent("library:abcd"),
            "filelib2.txt".withContent("library2:abcd"),
            "filelibtest.txt".withContent("libraryTest:abcd")
        )

        // app contain own assets + all dependencies' assets.
        appProject.checkApkResources(
            "file.txt".withContent("app:abcd"),
            "filelib.txt".withContent("library:abcd"),
            "filelib2.txt".withContent("library2:abcd")
        )
        // app test does not contain dependencies' own test assets.
        appProject.checkTestApkResources("filetest.txt".withContent("appTest:abcd"))
    }

    // ---- APP DEFAULT ---
    @Test
    fun testAppProjectWithNewResFile() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) { project: TemporaryProjectModification ->
            project.addFile("src/main/res/raw/newfile.txt", "newfile content")
            execute("app:assembleDebug")
            appProject.checkApkResources(
                "newfile.txt".withContent("newfile content"),
                "filelib2.txt",
                "filelib.txt",
                "file.txt"
            )
        }
    }

    @Test
    fun testAppProjectWithRemovedResFile() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) { project: TemporaryProjectModification ->
            project.removeFile("src/main/res/raw/file.txt")
            execute("app:assembleDebug")
            appProject.checkApkResources("filelib2.txt", "filelib.txt")
        }
    }

    @Test
    fun testAppProjectWithModifiedResFile() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) { project: TemporaryProjectModification ->
            project.replaceFile("src/main/res/raw/file.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApkResources(
                "file.txt".withContent("new content"),
                "filelib2.txt",
                "filelib.txt"
            )
        }
    }

    @Test
    fun testAppProjectWithNewDebugResFileOverridingMain() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) { project: TemporaryProjectModification ->
            project.addFile("src/debug/res/raw/file.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApkResources(
                "file.txt".withContent("new content"),
                "filelib2.txt",
                "filelib.txt"
            )
        }

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        appProject.checkApkResources(
            "file.txt".withContent("app:abcd"),
            "filelib2.txt",
            "filelib.txt"
        )
    }

    @Test
    fun testAppProjectWithnewResFileOverridingDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) { project: TemporaryProjectModification ->
            project.addFile("src/main/res/raw/filelib.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApkResources(
                "filelib.txt".withContent("new content"),
                "filelib2.txt",
                "file.txt"
            )
        }

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        appProject.checkApkResources(
            "filelib.txt".withContent("library:abcd"),
            "filelib2.txt",
            "file.txt"
        )
    }

    @Test
    fun testAppProjectWithnewResFileInDebugSourceSet() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) { project: TemporaryProjectModification ->
            project.addFile("src/debug/res/raw/file.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApkResources(
                "file.txt".withContent("new content"),
                "filelib2.txt",
                "filelib.txt"
            )
        }

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        appProject.checkApkResources(
            "file.txt".withContent("app:abcd"),
            "filelib2.txt",
            "filelib.txt"
        )
    }

    @Test
    fun testAppProjectWithModifiedResInDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.replaceFile("src/main/res/raw/filelib.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApkResources(
                "filelib.txt".withContent("new content"),
                "filelib2.txt",
                "file.txt"
            )
        }
    }

    @Test
    fun testAppProjectWithAddedResInDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.addFile("src/main/res/raw/new_lib_file.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApkResources(
                "new_lib_file.txt".withContent("new content"),
                "filelib2.txt",
                "filelib.txt",
                "file.txt"
            )
        }
    }

    @Test
    fun testAppProjectWithRemovedResInDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.removeFile("src/main/res/raw/filelib.txt")
            execute("app:assembleDebug")
            appProject.checkApkResources("filelib2.txt", "file.txt")
        }
    }

    @Test
    fun testAppResourcesAreFilteredByMinSdkFull() {
        testAppResourcesAreFilteredByMinSdk(false)
    }

    @Test
    fun testAppResourcesAreFilteredByMinSdkIncremental() {
        // Note: this test is very similar to the previous one but, instead of trying all 3
        // versions independently, we start with min SDK 26, then change to <26 and set
        // min SDK to 27. The outputs should be the same as in the previous test.
        testAppResourcesAreFilteredByMinSdk(true)
    }

    private fun testAppResourcesAreFilteredByMinSdk(incremental: Boolean) {
        // Here are which files go into where:
        //  (none)  v26     v27
        //  f1
        //  f2      f2
        //  f3      f3      f3
        //          f4      f4
        //                  f5
        //
        // If we build with minSdkVersion < 26, we should get everything exactly as shown.
        //
        // If we build with minSdkVersion = 26 we should end up with:
        // (none)   v26     v27
        //  f1
        //          f2
        //          f3      f3
        //          f4      f4
        //                  f5
        //
        // If we build with minSdkVersion = 27 we should end up with:
        // (none)   v26     v27
        //  f1
        //          f2
        //                  f3
        //                  f4
        //                  f5
        val raw = appProject.file("src/main/res/raw").also { it.toPath().createDirectories() }
        val raw26 = appProject.file("src/main/res/raw-v26").also { it.toPath().createDirectories() }
        val raw27 = appProject.file("src/main/res/raw-v27").also { it.toPath().createDirectories() }

        val f1NoneC = "f1NoneC"
        val f2NoneC = "f2NoneC"
        val f2v26C = "f2v26C"
        val f3NoneC = "f3NoneC"
        val f3v26C = "f3v26C"
        val f3v27C = "f3v27C"
        val f4v26C = "f4v26C"
        val f4v27C = "f4v27C"
        val f5v27C = "f5v27C"

        File(raw, "f1").writeText(f1NoneC)
        File(raw, "f2").writeText(f2NoneC)
        File(raw26, "f2").writeText(f2v26C)
        File(raw, "f3").writeText(f3NoneC)
        File(raw26, "f3").writeText(f3v26C)
        File(raw27, "f3").writeText(f3v27C)
        File(raw26, "f4").writeText(f4v26C)
        File(raw27, "f4").writeText(f4v27C)
        File(raw27, "f5").writeText(f5v27C)

        val appGradleFile = appProject.file("build.gradle")
        val appGradleFileContents =
            com.google.common.io.Files.asCharSource(appGradleFile, StandardCharsets.UTF_8).read()

        // Set min SDK version 26 and generate the APK.
        var newBuild =
            appGradleFileContents.replace("minSdkVersion .*".toRegex(), "minSdkVersion 26 // Updated")
        assertThat(newBuild).isNotEqualTo(appGradleFileContents)
        com.google.common.io.Files.asCharSink(appGradleFile, Charset.defaultCharset()).write(newBuild)
        execute("clean", ":app:assembleDebug")

        appProject.assertApk(ApkSelector.DEBUG) {
            checkResources(
                resFolder = "raw",
                "f1".withContent("f1NoneC"),
                "file.txt",
                "filelib.txt",
                "filelib2.txt"
            )
            checkResources(
                resFolder = "raw-v26",
                "f2".withContent(f2v26C),
                "f3".withContent(f3v26C),
                "f4".withContent(f4v26C)
            )
            checkResources(
                resFolder = "raw-v27",
                "f3".withContent(f3v27C),
                "f4".withContent(f4v27C),
                "f5".withContent(f5v27C)
            )
        }

        // Set lower min SDK version and generate the APK. Incremental update!
        newBuild = appGradleFileContents.replace("minSdkVersion".toRegex(), "minSdkVersion 25 //")
        assertThat(newBuild).isNotEqualTo(appGradleFileContents)
        com.google.common.io.Files.asCharSink(appGradleFile, StandardCharsets.UTF_8).write(newBuild)
        if (incremental) {
            execute(":app:assembleDebug")
        } else {
            execute("clean", ":app:assembleDebug")
        }

        appProject.assertApk(ApkSelector.DEBUG) {
            checkResources(
                resFolder = "raw",
                "f1".withContent("f1NoneC"),
                "f2".withContent("f2NoneC"),
                "f3".withContent("f3NoneC"),
                "file.txt",
                "filelib.txt",
                "filelib2.txt"
            )
            checkResources(
                resFolder = "raw-v26",
                "f2".withContent(f2v26C),
                "f3".withContent(f3v26C),
                "f4".withContent(f4v26C)
            )
            checkResources(
                resFolder = "raw-v27",
                "f3".withContent(f3v27C),
                "f4".withContent(f4v27C),
                "f5".withContent(f5v27C)
            )
        }

        // Set min SDK version 27 and generate the APK. Incremental update!
        newBuild = appGradleFileContents.replace("minSdkVersion".toRegex(), "minSdkVersion 27 //")
        assertThat(newBuild).isNotEqualTo(appGradleFileContents)
        com.google.common.io.Files.asCharSink(appGradleFile, StandardCharsets.UTF_8).write(newBuild)
        if (incremental) {
            execute(":app:assembleDebug")
        } else {
            execute("clean", ":app:assembleDebug")
        }

        appProject.assertApk(ApkSelector.DEBUG) {
            checkResources(
                resFolder = "raw",
                "f1".withContent("f1NoneC"),
                "file.txt",
                "filelib.txt",
                "filelib2.txt"
            )
            checkResources(
                resFolder = "raw-v26",
                "f2".withContent(f2v26C),
            )
            checkResources(
                resFolder = "raw-v27",
                "f3".withContent(f3v27C),
                "f4".withContent(f4v27C),
                "f5".withContent(f5v27C)
            )
        }
    }

    // ---- APP TEST ---
    @Test
    fun testAppProjectTestWithNewResFile() {
        execute("app:assembleAT")

        TemporaryProjectModification.doTest(appProject) { project: TemporaryProjectModification ->
            project.addFile("src/androidTest/res/raw/newfile.txt", "new file content")
            execute("app:assembleAT")
            appProject.checkTestApkResources("newfile.txt".withContent("new file content"), "filetest.txt")
        }
    }

    @Test
    fun testAppProjectTestWithRemovedResFile() {
        execute("app:assembleAT")

        TemporaryProjectModification.doTest(appProject) { project: TemporaryProjectModification ->
            project.removeFile("src/androidTest/res/raw/filetest.txt")
            execute("app:assembleAT")
            appProject.checkTestApkResources()
        }
    }

    @Test
    fun testAppProjectTestWithModifiedResFile() {
        execute("app:assembleAT")

        TemporaryProjectModification.doTest(appProject) { project: TemporaryProjectModification ->
            project.replaceFile("src/androidTest/res/raw/filetest.txt", "new content")
            execute("app:assembleAT")
            appProject.checkTestApkResources("filetest.txt".withContent("new content"))
        }
    }

    @Test
    fun testAppProjectWithMultipleFlavors() {
        appProject.buildFile.appendText(
            """
                android {
                    flavorDimensions = ["color"]
                    productFlavors {
                        red {
                            dimension = "color"
                        }
                        blue {
                            dimension = "color"
                        }
                    }
                }
            """.trimIndent()
        )

        File(appProject.projectDir, "src/red/res/raw").mkdirs()
        File(appProject.projectDir, "src/red/res/raw/red.txt").writeText("Red Text")
        File(appProject.projectDir, "src/blue/res/raw").mkdirs()
        File(appProject.projectDir, "src/blue/res/raw/blue.txt").writeText("Blue Text")

        execute("app:assembleDebug")

        appProject.assertApk(ApkSelector.DEBUG.withFlavor("red")) {
            checkRawResources(
                "red.txt".withContent("Red Text"),
                "filelib2.txt",
                "filelib.txt",
                "file.txt"
            )
        }
        appProject.assertApk(ApkSelector.DEBUG.withFlavor("blue")) {
            checkRawResources(
                "blue.txt".withContent("Blue Text"),
                "filelib2.txt",
                "filelib.txt",
                "file.txt"
            )
        }
    }

    // ---- LIB DEFAULT ---
    @Test
    fun testLibProjectWithNewResFile() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.addFile("src/main/res/raw/newfile.txt", "newfile content")
            execute("library:assembleDebug")
            libProject.checkAarResources("newfile.txt".withContent("newfile content"), "filelib.txt")
        }
    }

    @Test
    fun testLibProjectWithRemovedResFile() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.removeFile("src/main/res/raw/filelib.txt")
            execute("library:assembleDebug")
            libProject.checkAarResources()
        }
    }

    @Test
    fun testLibProjectWithModifiedResFile() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.replaceFile("src/main/res/raw/filelib.txt", "new content")
            execute("library:assembleDebug")
            libProject.checkAarResources("filelib.txt".withContent("new content"))
        }
    }

    @Test
    fun testLibProjectWithnewResFileInDebugSourceSet() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.addFile("src/debug/res/raw/filelib.txt", "new content")
            execute("library:assembleDebug")
            libProject.checkAarResources("filelib.txt".withContent("new content"))
        }

        // file's been removed, checking in the other direction.
        execute("library:assembleDebug")
        libProject.checkAarResources("filelib.txt".withContent("library:abcd"))
    }

    // ---- LIB TEST ---
    @Test
    fun testLibProjectTestWithNewResFile() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.addFile("src/androidTest/res/raw/newfile.txt", "new file content")
            execute("library:assembleAT")
            libProject.checkTestApkResources(
                "newfile.txt".withContent("new file content"),
                "filelibtest.txt",
                "filelib2.txt",
                "filelib.txt"
            )
        }
    }

    @Test
    fun testLibProjectTestWithRemovedResFile() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.removeFile("src/androidTest/res/raw/filelibtest.txt")
            execute("library:assembleAT")
            libProject.checkTestApkResources("filelib2.txt", "filelib.txt")
        }
    }

    @Test
    fun testLibProjectTestWithModifiedResFile() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.replaceFile("src/androidTest/res/raw/filelibtest.txt", "new content")
            execute("library:assembleAT")
            libProject.checkTestApkResources(
                "filelibtest.txt".withContent("new content"),
                "filelib2.txt",
                "filelib.txt"
            )
        }
    }

    @Test
    fun testLibProjectTestWithnewResFileOverridingTestedLib() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.addFile("src/androidTest/res/raw/filelib.txt", "new content")
            execute("library:assembleAT")
            libProject.checkTestApkResources(
                "filelib.txt".withContent("new content"),
                "filelibtest.txt",
                "filelib2.txt"
            )
        }

        // files been removed, checking in the other direction.
        execute("library:assembleAT")
        libProject.checkTestApkResources(
            "filelib.txt".withContent("library:abcd"),
            "filelibtest.txt",
            "filelib2.txt"
        )
    }

    @Test
    fun testLibProjectTestWithnewResFileOverridingDependency() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject) { project: TemporaryProjectModification ->
            project.addFile("src/androidTest/res/raw/filelib2.txt", "new content")
            execute("library:assembleAT")
            libProject.checkTestApkResources(
                "filelib2.txt".withContent("new content"),
                "filelibtest.txt",
                "filelib.txt"
            )
        }

        // file's been removed, checking in the other direction.
        execute("library:assembleAT")
        libProject.checkTestApkResources("filelib2.txt".withContent("library2:abcd"), "filelibtest.txt", "filelib.txt")
    }

    // ---- TEST DEFAULT ---
    @Test
    fun testTestProjectWithNewResFile() {
        execute("test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) { project: TemporaryProjectModification ->
            project.addFile("src/main/res/raw/newfile.txt", "newfile content")
            execute("test:assembleDebug")
            testProject.checkApkResources("newfile.txt".withContent("newfile content"), "file.txt")
        }
    }

    @Test
    fun testTestProjectWithRemovedResFile() {
        execute("test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) { project: TemporaryProjectModification ->
            project.removeFile("src/main/res/raw/file.txt")
            execute("test:assembleDebug")
            testProject.checkApkResources()
        }
    }

    @Test
    fun testTestProjectWithModifiedResFile() {
        execute("test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) { project: TemporaryProjectModification ->
            project.replaceFile("src/main/res/raw/file.txt", "new content")
            execute("test:assembleDebug")
            testProject.checkApkResources("file.txt".withContent("new content"))
        }
    }

    companion object {
        private fun createOriginalResFile(
            projectFolder: File,
            dimension: String,
            filename: String,
            content: String
        ) {
            val assetFolder = FileUtils.join(projectFolder, "src", dimension, "res", "raw")
            FileUtils.mkdirs(assetFolder)
            com.google.common.io.Files.asCharSink(File(assetFolder, filename), Charsets.UTF_8).write(content)
        }

        // -----------------------
        /**
         * check an apk has (or not) the given res file name.
         *
         *
         * If the content is non-null the file is expected to be there with the same content. If the
         * content is null the file is not expected to be there.
         *
         * @param project the project
         * @param itemList a list of items that must be present in the android archive. The list
         * can either contain [String] to just validate presence, or [StringWithContent] to validate
         * presence and content.
         */
        private fun GradleTestProject.checkApkResources(
            vararg itemList: Any
        ) {
            assertApk(ApkSelector.DEBUG) {
                checkRawResources(*itemList)
            }
        }

        /**
         * check a test apk has (or not) the given res file name.
         *
         *
         * If the content is non-null the file is expected to be there with the same content. If the
         * content is null the file is not expected to be there.
         *
         * @param project the project
         * @param itemList a list of items that must be present in the android archive. The list
         * can either contain [String] to just validate presence, or [StringWithContent] to validate
         * presence and content.
         */
        private fun GradleTestProject.checkTestApkResources(
            vararg itemList: Any
        ) {
            assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
                checkRawResources(*itemList)
            }
        }

        /**
         * check an aat has (or not) the given res file name.
         *
         *
         * If the content is non-null the file is expected to be there with the same content. If the
         * content is null the file is not expected to be there.
         *
         * @param project the project
         * @param itemList a list of items that must be present in the android archive. The list
         * can either contain [String] to just validate presence, or [StringWithContent] to validate
         * presence and content.
         */
        private fun GradleTestProject.checkAarResources(vararg itemList: Any) {
            assertAar(AarSelector.DEBUG) {
                checkRawResources(*itemList)
            }
        }

        private fun AbstractAndroidArchiveSubject<*,*>.checkRawResources(vararg itemList: Any) {
            checkResources(resFolder = "raw", itemList = itemList)
        }

        private fun AbstractAndroidArchiveSubject<*,*>.checkResources(
            resFolder: String,
            vararg itemList: Any) {
            androidResources().folder(resFolder).apply {
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
                        resourceAsText(item.name).isEqualTo(item.content)
                    }
                }
            }
        }
    }
}
