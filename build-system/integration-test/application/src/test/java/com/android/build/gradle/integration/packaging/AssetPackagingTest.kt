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
import com.android.build.gradle.integration.common.fixture.project.GeneratesAar
import com.android.build.gradle.integration.common.fixture.project.GeneratesApk
import com.android.build.gradle.integration.common.output.AbstractAndroidArchiveSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.utils.FileUtils
import com.google.common.base.Charsets
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.*
import java.util.zip.GZIPOutputStream

/**
 * test for packaging of asset files.
 */
class AssetPackagingTest {
    @get:Rule
    val project: GradleTestProject = builder()
        .fromTestProject("projectWithModules")
        .withDependencyChecker(false)
        .create()

    private lateinit var appProject: GradleTestProject
    private lateinit var libProject: GradleTestProject
    private lateinit var libProject2: GradleTestProject
    private lateinit var testProject: GradleTestProject

    @Before
    fun setUp() {
        appProject = project.getSubproject("app")
        libProject = project.getSubproject("library")
        libProject2 = project.getSubproject("library2")
        testProject = project.getSubproject("test")

        // rewrite settings.gradle to remove un-needed modules
        project.setIncludedProjects("app", "library", "library2", "test")

        // setup dependencies.
        TestFileUtils.appendToFile(
            appProject.buildFile,
            """
                android {
                    publishNonDefault = true
                    aaptOptions {}
                }

                dependencies {
                    api project(':library')
                }
                """.trimIndent()
        )

        TestFileUtils.appendToFile(
            libProject.buildFile,
            """
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
        createOriginalAsset(createAssetFile(appDir, "main", "file.txt"), "app:abcd")
        createOriginalAsset(createAssetFile(appDir, "main", "subdir", "file.txt"), "app:defg")
        createOriginalAsset(createAssetFile(appDir, "main", "_anotherdir", "file.txt"), "app:hijk")
        createOriginalAsset(createAssetFile(appDir, "androidTest", "filetest.txt"), "appTest:abcd")
        createOriginalAsset(createAssetFile(appDir, "static", "static.txt"), "app:static")

        val testDir = testProject.projectDir
        createOriginalAsset(createAssetFile(testDir, "main", "file.txt"), "test:abcd")

        val libDir = libProject.projectDir
        createOriginalAsset(createAssetFile(libDir, "main", "filelib.txt"), "library:abcd")
        createOriginalAsset(
            createAssetFile(libDir, "androidTest", "filelibtest.txt"), "libraryTest:abcd"
        )

        val lib2Dir = libProject2.projectDir
        // Include a gzipped asset, which should be extracted.
        createOriginalGzippedAsset(
            createAssetFile(lib2Dir, "main", "filelib2.txt.gz"),
            "library2:abcd".toByteArray(Charsets.UTF_8)
        )
        createOriginalAsset(
            createAssetFile(lib2Dir, "androidTest", "filelib2test.txt"), "library2Test:abcd"
        )
    }

    private fun execute(vararg tasks: String) {
        project.executor().run(*tasks)
    }

    private fun createOriginalAsset(assetFile: File, content: String) {
        createOriginalAsset(assetFile, content.toByteArray(Charsets.UTF_8))
    }

    private fun createOriginalGzippedAsset(assetFile: File, content: ByteArray) {
        val byteArrayOutputStream = ByteArrayOutputStream()
        GZIPOutputStream(byteArrayOutputStream).use { out ->
            out.write(content)
        }
        createOriginalAsset(assetFile, byteArrayOutputStream.toByteArray())
    }

    private fun createOriginalAsset(assetFile: File, content: ByteArray) {
        val assetFolder = assetFile.getParentFile().toPath()
        Files.createDirectories(assetFolder)
        Files.write(assetFile.toPath(), content)
    }

    private fun createAssetFile(
        projectDirectory: File, dimension: String, vararg path: String
    ): File {
        val assetBase = FileUtils.join(projectDirectory, "src", dimension, "assets")
        return FileUtils.join(assetBase, Arrays.asList<String?>(*path))
    }

    @Test
    fun testNonIncrementalPackaging() {
        execute("assembleDebug", "assembleAndroidTest")

        // check the files are there. Start from the bottom of the dependency graph
        libProject2.checkAar("filelib2.txt".withContent("library2:abcd"))
        libProject2.checkTestApk(
            "filelib2.txt".withContent("library2:abcd"),
            "filelib2test.txt".withContent("library2Test:abcd")
        )

        // aar does not contain dependency's assets
        libProject.checkAar("filelib.txt".withContent("library:abcd"))
        // test apk contains both test-ony assets, lib assets, and dependency assets.
        // but not the assets of the dependency's own test
        libProject.checkTestApk(
            "filelib.txt".withContent("library:abcd"),
            "filelib2.txt".withContent("library2:abcd"),
            "filelibtest.txt".withContent("libraryTest:abcd")
        )

        // app contain own assets + all dependencies' assets.
        // but not _anotherdir/file.txt because of the default AaptOptions.ignoreAssetsPattern
        appProject.checkApk(
            "file.txt".withContent("app:abcd"),
            "subdir/file.txt".withContent("app:defg"),
            "filelib.txt".withContent("library:abcd"),
            "filelib2.txt".withContent("library2:abcd")
        )

        // app test does not contain dependencies' own test assets.
        appProject.checkTestApk("filetest.txt".withContent("appTest:abcd"))
    }

    // ---- APP DEFAULT ---
    @Test
    fun testAppProjectWithNewAssetFile() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addFile("src/main/assets/newfile.txt", "newfile content")
            execute("app:assembleDebug")
            appProject.checkApk(
                "newfile.txt".withContent("newfile content"),
                "subdir/file.txt", "filelib2.txt", "filelib.txt", "file.txt"
            )
        }
    }

    @Test
    fun testAppProjectWithRemovedAssetFile() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.removeFile("src/main/assets/file.txt")
            execute("app:assembleDebug")
            appProject.checkApk("subdir/file.txt", "filelib2.txt", "filelib.txt")
        }
    }

    @Test
    fun testAppProjectWithModifiedAssetFile() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.replaceFile("src/main/assets/file.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApk(
                "file.txt".withContent("new content"),
                "subdir/file.txt", "filelib2.txt", "filelib.txt"
            )
        }
    }

    @Test
    fun testAppProjectWithNewDebugAssetFileOverridingMain() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addFile("src/debug/assets/file.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApk(
                "file.txt".withContent("new content"),
                "subdir/file.txt", "filelib2.txt", "filelib.txt"
            )
        }

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        appProject.checkApk(
            "file.txt".withContent("app:abcd"),
            "subdir/file.txt", "filelib2.txt", "filelib.txt")
    }

    @Test
    fun testAppProjectWithNewAssetFileOverridingDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addFile("src/main/assets/filelib.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApk(
                "filelib.txt".withContent("new content"),
                "subdir/file.txt", "filelib2.txt", "file.txt"
            )
        }

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        appProject.checkApk(
            "filelib.txt".withContent("library:abcd"),
            "subdir/file.txt", "filelib2.txt", "file.txt"
        )
    }

    @Test
    fun testAppProjectWithNewAssetFileInDebugSourceSet() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addFile("src/debug/assets/file.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApk(
                "file.txt".withContent("new content"),
                "subdir/file.txt", "filelib2.txt", "filelib.txt"
            )
        }

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        appProject.checkApk(
            "file.txt".withContent("app:abcd"),
            "subdir/file.txt", "filelib2.txt", "filelib.txt"
        )
    }

    @Test
    fun testAppProjectWithModifiedAssetInDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.replaceFile("src/main/assets/filelib.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApk(
                "filelib.txt".withContent("new content"),
                "subdir/file.txt", "filelib2.txt", "file.txt"
            )
        }
    }

    @Test
    fun testAppProjectWithAddedAssetInDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/main/assets/new_lib_file.txt", "new content")
            execute("app:assembleDebug")
            appProject.checkApk(
                "new_lib_file.txt".withContent("new content"),
                "subdir/file.txt", "filelib2.txt", "filelib.txt", "file.txt"
            )
        }
    }

    @Test
    fun testAppProjectWithRemovedAssetInDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.removeFile("src/main/assets/filelib.txt")
            execute("app:assembleDebug")
            appProject.checkApk("subdir/file.txt", "filelib2.txt", "file.txt")
        }
    }

    @Test
    fun testAppProjectWithAddedAssetThatOverrideAaptOptions() {
        TemporaryProjectModification.doTest(
            appProject
        ) {
            it.replaceInFile(
                appProject.buildFile.getPath(),
                "aaptOptions \\{\\}",
                ("aaptOptions \\{ ignoreAssetsPattern = "
                        + " \"!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~\""
                        + " \\}")
            )
            // Override AaptOptions and check that the file has been included.
            execute("app:assembleDebug")
            appProject.checkApk(
                "_anotherdir/file.txt".withContent("app:hijk"),
                "subdir/file.txt", "filelib2.txt", "filelib.txt", "file.txt"
            )

            // Another run with more files and they all should be included too as part of
            // incremental build.
            it.addFile("src/main/assets/_file.txt", "app:1234")
            it.addFile("src/main/assets/_anotherdir/_file.txt", "app:5678")
            it.addFile("src/main/assets/_onemoredir/file.txt", "app:9012")
            execute("app:assembleDebug")
            appProject.checkApk(
                "_anotherdir/file.txt".withContent("app:hijk"),
                "_file.txt".withContent("app:1234"),
                "_anotherdir/_file.txt".withContent("app:5678"),
                "_onemoredir/file.txt".withContent("app:9012"),
                "subdir/file.txt", "filelib2.txt", "filelib.txt", "file.txt"
            )
        }
    }

    @Test
    fun testAppProjectWithAddedAndRemovedAsset() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(
            appProject
        ) {
            it.addFile("src/main/assets/newFile.txt", "foo")
            execute("app:assembleDebug")
            appProject.checkApk(
                "newFile.txt".withContent("foo"),
                "subdir/file.txt", "filelib2.txt", "filelib.txt", "file.txt"
            )
        }

        // Asset file has been removed. Check it's removed from the APK after another inc build.
        execute("app:assembleDebug")
        appProject.checkApk("subdir/file.txt", "filelib2.txt", "filelib.txt", "file.txt")
    }

    // ---- APP TEST ---
    @Test
    fun testAppProjectTestWithNewAssetFile() {
        execute("app:assembleAT")

        TemporaryProjectModification.doTest(appProject) {
            it.addFile("src/androidTest/assets/newfile.txt", "new file content")
            execute("app:assembleAT")
            appProject.checkTestApk(
                "newfile.txt".withContent("new file content"),
                "filetest.txt"
            )
        }
    }

    @Test
    fun testAppProjectTestWithRemovedAssetFile() {
        execute("app:assembleAT")

        TemporaryProjectModification.doTest(appProject) {
            it.removeFile("src/androidTest/assets/filetest.txt")
            execute("app:assembleAT")
            appProject.checkTestApk()
        }
    }

    @Test
    fun testAppProjectTestWithModifiedAssetFile() {
        execute("app:assembleAT")

        TemporaryProjectModification.doTest(appProject) {
            it.replaceFile("src/androidTest/assets/filetest.txt", "new content")
            execute("app:assembleAT")
            appProject.checkTestApk("filetest.txt".withContent("new content"))
        }
    }

    // ---- LIB DEFAULT ---
    @Test
    fun testLibProjectWithNewAssetFile() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/main/assets/newfile.txt", "newfile content")
            execute("library:assembleDebug")
            libProject.checkAar("newfile.txt".withContent("newfile content"), "filelib.txt")
        }
    }

    @Test
    fun testLibProjectWithRemovedAssetFile() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.removeFile("src/main/assets/filelib.txt")
            execute("library:assembleDebug")
            libProject.checkAar()
        }
    }

    @Test
    fun testLibProjectWithModifiedAssetFile() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.replaceFile("src/main/assets/filelib.txt", "new content")
            execute("library:assembleDebug")
            libProject.checkAar("filelib.txt".withContent("new content"))
        }
    }

    @Test
    fun testLibProjectWithNewAssetFileInDebugSourceSet() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/debug/assets/filelib.txt", "new content")
            execute("library:assembleDebug")
            libProject.checkAar("filelib.txt".withContent("new content"))
        }

        // file's been removed, checking in the other direction.
        execute("library:assembleDebug")
        libProject.checkAar("filelib.txt".withContent("library:abcd"))
    }

    @Test
    fun testLibProjectWithIgnoredAssets() {
        TemporaryProjectModification.doTest(libProject) {
            // first test for non-incremental
            it.addFile("src/main/assets/ignored", "ignored")
            it.addFile("src/main/assets/kept", "kept")
            it.appendToFile(
                libProject.buildFile.getPath(),
                "android.aaptOptions.ignoreAssetsPattern = 'ignored'"
            )
            execute("library:assembleDebug")
            libProject.checkAar("kept".withContent("kept"), "filelib.txt")
            // then test for incremental
            it.addFile("src/main/assets/dir/ignored", "ignored")
            execute("library:assembleDebug")
            libProject.checkAar("kept".withContent("kept"), "filelib.txt")
        }
    }

    // ---- LIB TEST ---
    @Test
    fun testLibProjectTestWithNewAssetFile() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/androidTest/assets/newfile.txt", "new file content")
            execute("library:assembleAT")
            libProject.checkTestApk(
                "newfile.txt".withContent("new file content"),
                "filelibtest.txt", "filelib2.txt", "filelib.txt"
            )
        }
    }

    @Test
    fun testLibProjectTestWithRemovedAssetFile() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.removeFile("src/androidTest/assets/filelibtest.txt")
            execute("library:assembleAT")
            libProject.checkTestApk("filelib.txt", "filelib2.txt")
        }
    }

    @Test
    fun testLibProjectTestWithModifiedAssetFile() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.replaceFile("src/androidTest/assets/filelibtest.txt", "new content")
            execute("library:assembleAT")
            libProject.checkTestApk(
                "filelibtest.txt".withContent("new content"),
                "filelib2.txt",
                "filelib.txt"
            )
        }
    }

    @Test
    fun testLibProjectTestWithNewAssetFileOverridingTestedLib() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/androidTest/assets/filelib.txt", "new content")
            execute("library:assembleAT")
            libProject.checkTestApk(
                "filelib.txt".withContent("new content"),
                "filelibtest.txt",
                "filelib2.txt"
            )
        }

        // file's been removed, checking in the other direction.
        execute("library:assembleAT")
        libProject.checkTestApk(
            "filelib.txt".withContent("library:abcd"),
            "filelibtest.txt",
            "filelib2.txt"
        )
    }

    @Test
    fun testLibProjectTestWithNewAssetFileOverridingDependency() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject) {
            it.addFile("src/androidTest/assets/filelib2.txt", "new content")
            execute("library:assembleAT")
            libProject.checkTestApk(
                "filelib2.txt".withContent("new content"),
                "filelibtest.txt",
                "filelib.txt"
            )
        }

        // file's been removed, checking in the other direction.
        execute("library:assembleAT")
        libProject.checkTestApk(
            "filelib2.txt".withContent("library2:abcd"),
            "filelibtest.txt",
            "filelib.txt"
        )
    }

    // ---- TEST DEFAULT ---
    @Test
    fun testTestProjectWithNewAssetFile() {
        execute("test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) {
            it.addFile("src/main/assets/newfile.txt", "newfile content")
            execute("test:assembleDebug")
            testProject.checkApk("newfile.txt".withContent("newfile content"), "file.txt")
        }
    }

    @Test
    fun testTestProjectWithRemovedAssetFile() {
        execute("test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) {
            it.removeFile("src/main/assets/file.txt")
            execute("test:assembleDebug")
            testProject.checkApk()
        }
    }

    @Test
    fun testTestProjectWithModifiedAssetFile() {
        execute("test:assembleDebug")

        TemporaryProjectModification.doTest(testProject) {
            it.replaceFile("src/main/assets/file.txt", "new content")
            execute("test:assembleDebug")
            testProject.checkApk("file.txt".withContent("new content"))
        }
    }

    // -----------------------
    @Test
    fun testPackageAssetsWithUnderscoreRegression() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject) {
            it.addFile("src/main/assets/_newfile.txt", "newfile content")
            execute("app:assembleDebug")
            appProject.checkApk(
                "_newfile.txt".withContent("newfile content"),
                "subdir/file.txt", "filelib2.txt", "filelib.txt", "file.txt"
            )
        }
    }

    @Test
    fun testIgnoreAssets() {
        val projectFile = appProject.buildFile
        TestFileUtils.appendToFile(
            projectFile,
            "android { aaptOptions { ignoreAssets = '*a:b*' } } "
        )

        val aaData = "e"
        val abData = "f"
        val baData = "g"
        val bbData = "h"

        val aaAsset = FileUtils.join(appProject.projectDir, "src", "main", "assets", "aa")
        val abAsset = FileUtils.join(appProject.projectDir, "src", "main", "assets", "ab")
        val baAsset = FileUtils.join(appProject.projectDir, "src", "main", "assets", "ba")
        val bbAsset = FileUtils.join(appProject.projectDir, "src", "main", "assets", "bb")

        FileUtils.mkdirs(aaAsset.getParentFile())

        aaAsset.writeText(aaData)
        abAsset.writeText(abData)
        baAsset.writeText(baData)
        bbAsset.writeText(bbData)

        execute("app:assembleDebug")

        appProject.checkApk(
            "ab".withContent(abData),
            "subdir/file.txt", "filelib2.txt", "filelib.txt", "file.txt", "_anotherdir/file.txt"
        )
    }

    /** Regression test for b/352352252  */
    @Test
    fun testAddStaticSourceDirectory() {
        TestFileUtils.appendToFile(
            appProject.buildFile,
            """
                androidComponents {
                  onVariants(selector().all()) { variant ->
                    variant.sources.assets?.addStaticSourceDirectory('src/static/assets')
                  }
                }
            """.trimIndent()
        )
        execute("assembleDebug")
        appProject.checkApk(
            "static.txt".withContent("app:static"),
            "subdir/file.txt", "filelib2.txt", "filelib.txt", "file.txt"
        )
    }

    /**
     * Checks the DEBUG apk has the specific list of assets. The list must be exhaustive.
     *
     * @param itemList a list of items that must be present in the android archive. The list
     * can either contain [String] to just validate presence, or [StringWithContent] to validate
     * presence and content.
     */
    private fun GeneratesApk.checkApk(
        vararg itemList: Any
    ) {
        assertApk(ApkSelector.DEBUG) {
            checkAssets(*itemList)
        }
    }

    /**
     * Checks the DEBUG test apk has the specific list of assets. The list must be exhaustive.
     *
     * @param itemList a list of items that must be present in the android archive. The list
     * can either contain [String] to just validate presence, or [StringWithContent] to validate
     * presence and content.
     */
    private fun GeneratesApk.checkTestApk(
        vararg itemList: Any
    ) {
        assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
            checkAssets(*itemList)
        }
    }

    /**
     * Checks the DEBUG aar has the specific list of assets. The list must be exhaustive.
     *
     * @param itemList a list of items that must be present in the android archive. The list
     * can either contain [String] to just validate presence, or [StringWithContent] to validate
     * presence and content.
     */
    private fun GeneratesAar.checkAar(
        vararg itemList: Any
    ) {
        assertAar(AarSelector.DEBUG) {
            checkAssets(*itemList)
        }
    }

    /**
     * Checks the android archive has the specific list of assets. The list must be exhaustive.
     *
     * @param itemList a list of items that must be present in the android archive. The list
     * can either contain [String] to just validate presence, or [StringWithContent] to validate
     * presence and content.
     */
    private fun AbstractAndroidArchiveSubject<*,*>.checkAssets(vararg itemList: Any) {
        if (itemList.isEmpty()) {
            assets().isEmpty()
        } else {
            assets {
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
