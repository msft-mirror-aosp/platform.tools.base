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
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification.ModifiedProjectTest
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.output.AarSubject
import com.android.build.gradle.integration.common.output.AbstractZipSubject
import com.android.build.gradle.integration.common.output.Zip
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.build.gradle.integration.common.truth.TruthHelper
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.utils.FileUtils
import com.google.common.base.Charsets
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.*
import java.util.function.Consumer
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
        checkAar(libProject2, "filelib2.txt", "library2:abcd")
        checkTestApk(libProject2, "filelib2.txt", "library2:abcd")
        checkTestApk(libProject2, "filelib2test.txt", "library2Test:abcd")

        checkAar(libProject, "filelib.txt", "library:abcd")
        // aar does not contain dependency's assets
        checkAar(libProject, "filelib2.txt", null)
        // test apk contains both test-ony assets, lib assets, and dependency assets.
        checkTestApk(libProject, "filelib.txt", "library:abcd")
        checkTestApk(libProject, "filelib2.txt", "library2:abcd")
        checkTestApk(libProject, "filelibtest.txt", "libraryTest:abcd")
        // but not the assets of the dependency's own test
        checkTestApk(libProject, "filelib2test.txt", null)

        // app contain own assets + all dependencies' assets.
        checkApk(appProject, "file.txt", "app:abcd")
        checkApk(appProject, "subdir/file.txt", "app:defg")
        checkApk(appProject, "filelib.txt", "library:abcd")
        checkApk(appProject, "filelib2.txt", "library2:abcd")
        // This should be null because of the default AaptOptions.ignoreAssetsPattern
        checkApk(appProject, "_anotherdir/file.txt", null)
        checkTestApk(appProject, "filetest.txt", "appTest:abcd")
        // app test does not contain dependencies' own test assets.
        checkTestApk(appProject, "filelibtest.txt", null)
        checkTestApk(appProject, "filelib2test.txt", null)
    }

    // ---- APP DEFAULT ---
    @Test
    fun testAppProjectWithNewAssetFile() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject, ModifiedProjectTest {
            it.addFile("src/main/assets/newfile.txt", "newfile content")
            execute("app:assembleDebug")
            checkApk(appProject, "newfile.txt", "newfile content")
        })
    }

    @Test
    fun testAppProjectWithRemovedAssetFile() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject, ModifiedProjectTest {
            it.removeFile("src/main/assets/file.txt")
            execute("app:assembleDebug")
            checkApk(appProject, "file.txt", null)
        })
    }

    @Test
    fun testAppProjectWithModifiedAssetFile() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject, ModifiedProjectTest {
            it.replaceFile("src/main/assets/file.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "file.txt", "new content")
        })
    }

    @Test
    fun testAppProjectWithNewDebugAssetFileOverridingMain() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject, ModifiedProjectTest {
            it.addFile("src/debug/assets/file.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "file.txt", "new content")
        })

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        checkApk(appProject, "file.txt", "app:abcd")
    }

    @Test
    fun testAppProjectWithNewAssetFileOverridingDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject, ModifiedProjectTest {
            it.addFile("src/main/assets/filelib.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "filelib.txt", "new content")
        })

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        checkApk(appProject, "filelib.txt", "library:abcd")
    }

    @Test
    fun testAppProjectWithNewAssetFileInDebugSourceSet() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject, ModifiedProjectTest {
            it.addFile("src/debug/assets/file.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "file.txt", "new content")
        })

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        checkApk(appProject, "file.txt", "app:abcd")
    }

    @Test
    fun testAppProjectWithModifiedAssetInDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.replaceFile("src/main/assets/filelib.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "filelib.txt", "new content")
        })
    }

    @Test
    fun testAppProjectWithAddedAssetInDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.addFile("src/main/assets/new_lib_file.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "new_lib_file.txt", "new content")
        })
    }

    @Test
    fun testAppProjectWithRemovedAssetInDependency() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.removeFile("src/main/assets/filelib.txt")
            execute("app:assembleDebug")
            checkApk(appProject, "filelib.txt", null)
        })
    }

    @Test
    fun testAppProjectWithAddedAssetThatOverrideAaptOptions() {
        TemporaryProjectModification.doTest(
            appProject,
            ModifiedProjectTest {
                it.replaceInFile(
                    appProject.buildFile.getPath(),
                    "aaptOptions \\{\\}",
                    ("aaptOptions \\{ ignoreAssetsPattern = "
                            + " \"!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~\""
                            + " \\}")
                )
                // Override AaptOptions and check that the file has been included.
                execute("app:assembleDebug")
                checkApk(appProject, "_anotherdir/file.txt", "app:hijk")

                // Another run with more files and they all should be included too as part of
                // incremental build.
                it.addFile("src/main/assets/_file.txt", "app:1234")
                it.addFile("src/main/assets/_anotherdir/_file.txt", "app:5678")
                it.addFile("src/main/assets/_onemoredir/file.txt", "app:9012")
                execute("app:assembleDebug")
                checkApk(appProject, "_anotherdir/file.txt", "app:hijk")
                checkApk(appProject, "_file.txt", "app:1234")
                checkApk(appProject, "_anotherdir/_file.txt", "app:5678")
                checkApk(appProject, "_onemoredir/file.txt", "app:9012")
            })
    }

    @Test
    fun testAppProjectWithAddedAndRemovedAsset() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(
            appProject,
            ModifiedProjectTest {
                it.addFile("src/main/assets/newFile.txt", "foo")
                execute("app:assembleDebug")
                checkApk(appProject, "newFile.txt", "foo")
            })

        // Asset file has been removed. Check it's removed from the APK after another inc build.
        execute("app:assembleDebug")
        checkApk(appProject, "newFile.txt", null)
    }

    // ---- APP TEST ---
    @Test
    fun testAppProjectTestWithNewAssetFile() {
        execute("app:assembleAT")

        TemporaryProjectModification.doTest(appProject, ModifiedProjectTest {
            it.addFile("src/androidTest/assets/newfile.txt", "new file content")
            execute("app:assembleAT")
            checkTestApk(appProject, "newfile.txt", "new file content")
        })
    }

    @Test
    fun testAppProjectTestWithRemovedAssetFile() {
        execute("app:assembleAT")

        TemporaryProjectModification.doTest(appProject, ModifiedProjectTest {
            it.removeFile("src/androidTest/assets/filetest.txt")
            execute("app:assembleAT")
            checkTestApk(appProject, "filetest.txt", null)
        })
    }

    @Test
    fun testAppProjectTestWithModifiedAssetFile() {
        execute("app:assembleAT")

        TemporaryProjectModification.doTest(appProject, ModifiedProjectTest {
            it.replaceFile("src/androidTest/assets/filetest.txt", "new content")
            execute("app:assembleAT")
            checkTestApk(appProject, "filetest.txt", "new content")
        })
    }

    // ---- LIB DEFAULT ---
    @Test
    fun testLibProjectWithNewAssetFile() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.addFile("src/main/assets/newfile.txt", "newfile content")
            execute("library:assembleDebug")
            checkAar(libProject, "newfile.txt", "newfile content")
        })
    }

    @Test
    fun testLibProjectWithRemovedAssetFile() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.removeFile("src/main/assets/filelib.txt")
            execute("library:assembleDebug")
            checkAar(libProject, "filelib.txt", null)
        })
    }

    @Test
    fun testLibProjectWithModifiedAssetFile() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.replaceFile("src/main/assets/filelib.txt", "new content")
            execute("library:assembleDebug")
            checkAar(libProject, "filelib.txt", "new content")
        })
    }

    @Test
    fun testLibProjectWithNewAssetFileInDebugSourceSet() {
        execute("library:assembleDebug")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.addFile("src/debug/assets/filelib.txt", "new content")
            execute("library:assembleDebug")
            checkAar(libProject, "filelib.txt", "new content")
        })

        // file's been removed, checking in the other direction.
        execute("library:assembleDebug")
        checkAar(libProject, "filelib.txt", "library:abcd")
    }

    @Test
    fun testLibProjectWithIgnoredAssets() {
        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            // first test for non-incremental
            it.addFile("src/main/assets/ignored", "ignored")
            it.addFile("src/main/assets/kept", "kept")
            it.appendToFile(
                libProject.buildFile.getPath(),
                "android.aaptOptions.ignoreAssetsPattern = 'ignored'"
            )
            execute("library:assembleDebug")
            checkAar(libProject, "ignored", null)
            checkAar(libProject, "kept", "kept")
            // then test for incremental
            it.addFile("src/main/assets/dir/ignored", "ignored")
            execute("library:assembleDebug")
            checkAar(libProject, "ignored", null)
            checkAar(libProject, "dir/ignored", null)
            checkAar(libProject, "kept", "kept")
        })
    }

    // ---- LIB TEST ---
    @Test
    fun testLibProjectTestWithNewAssetFile() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.addFile("src/androidTest/assets/newfile.txt", "new file content")
            execute("library:assembleAT")
            checkTestApk(libProject, "newfile.txt", "new file content")
        })
    }

    @Test
    fun testLibProjectTestWithRemovedAssetFile() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.removeFile("src/androidTest/assets/filelibtest.txt")
            execute("library:assembleAT")
            checkTestApk(libProject, "filelibtest.txt", null)
        })
    }

    @Test
    fun testLibProjectTestWithModifiedAssetFile() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.replaceFile("src/androidTest/assets/filelibtest.txt", "new content")
            execute("library:assembleAT")
            checkTestApk(libProject, "filelibtest.txt", "new content")
        })
    }

    @Test
    fun testLibProjectTestWithNewAssetFileOverridingTestedLib() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.addFile("src/androidTest/assets/filelib.txt", "new content")
            execute("library:assembleAT")
            checkTestApk(libProject, "filelib.txt", "new content")
        })

        // file's been removed, checking in the other direction.
        execute("library:assembleAT")
        checkTestApk(libProject, "filelib.txt", "library:abcd")
    }

    @Test
    fun testLibProjectTestWithNewAssetFileOverridingDependency() {
        execute("library:assembleAT")

        TemporaryProjectModification.doTest(libProject, ModifiedProjectTest {
            it.addFile("src/androidTest/assets/filelib2.txt", "new content")
            execute("library:assembleAT")
            checkTestApk(libProject, "filelib2.txt", "new content")
        })

        // file's been removed, checking in the other direction.
        execute("library:assembleAT")
        checkTestApk(libProject, "filelib2.txt", "library2:abcd")
    }

    // ---- TEST DEFAULT ---
    @Test
    fun testTestProjectWithNewAssetFile() {
        execute("test:assembleDebug")

        TemporaryProjectModification.doTest(testProject, ModifiedProjectTest {
            it.addFile("src/main/assets/newfile.txt", "newfile content")
            execute("test:assembleDebug")
            checkApk(testProject, "newfile.txt", "newfile content")
        })
    }

    @Test
    fun testTestProjectWithRemovedAssetFile() {
        execute("test:assembleDebug")

        TemporaryProjectModification.doTest(testProject, ModifiedProjectTest {
            it.removeFile("src/main/assets/file.txt")
            execute("test:assembleDebug")
            checkApk(testProject, "file.txt", null)
        })
    }

    @Test
    fun testTestProjectWithModifiedAssetFile() {
        execute("test:assembleDebug")

        TemporaryProjectModification.doTest(testProject, ModifiedProjectTest {
            it.replaceFile("src/main/assets/file.txt", "new content")
            execute("test:assembleDebug")
            checkApk(testProject, "file.txt", "new content")
        })
    }

    // -----------------------
    @Test
    fun testPackageAssetsWithUnderscoreRegression() {
        execute("app:assembleDebug")

        TemporaryProjectModification.doTest(appProject, ModifiedProjectTest {
            it.addFile("src/main/assets/_newfile.txt", "newfile content")
            execute("app:assembleDebug")
            checkApk(appProject, "_newfile.txt", "newfile content")
        })
    }

    @Test
    fun testIgnoreAssets() {
        val projectFile = appProject.buildFile
        TestFileUtils.appendToFile(
            projectFile,
            "android { aaptOptions { ignoreAssets = '*a:b*' } } "
        )

        val aaData = byteArrayOf('e'.code.toByte())
        val abData = byteArrayOf('f'.code.toByte())
        val baData = byteArrayOf('g'.code.toByte())
        val bbData = byteArrayOf('h'.code.toByte())

        val aaAsset = FileUtils.join(appProject.projectDir, "src", "main", "assets", "aa")
        val abAsset = FileUtils.join(appProject.projectDir, "src", "main", "assets", "ab")
        val baAsset = FileUtils.join(appProject.projectDir, "src", "main", "assets", "ba")
        val bbAsset = FileUtils.join(appProject.projectDir, "src", "main", "assets", "bb")

        FileUtils.mkdirs(aaAsset.getParentFile())
        FileUtils.mkdirs(abAsset.getParentFile())
        FileUtils.mkdirs(baAsset.getParentFile())
        FileUtils.mkdirs(bbAsset.getParentFile())

        Files.write(aaAsset.toPath(), aaData)
        Files.write(abAsset.toPath(), abData)
        Files.write(baAsset.toPath(), baData)
        Files.write(bbAsset.toPath(), bbData)

        execute("app:assembleDebug")

        TruthHelper.assertThat(appProject.getApk(GradleTestProject.ApkType.DEBUG))
            .doesNotContain("assets/aa")
        TruthHelper.assertThat(appProject.getApk(GradleTestProject.ApkType.DEBUG))
            .containsFileWithContent("assets/ab", abData)
        TruthHelper.assertThat(appProject.getApk(GradleTestProject.ApkType.DEBUG))
            .doesNotContain("assets/ba")
        TruthHelper.assertThat(appProject.getApk(GradleTestProject.ApkType.DEBUG))
            .doesNotContain("assets/bb")
    }

    /** Regression test for b/352352252  */
    @Test
    fun testAddStaticSourceDirectory() {
        TestFileUtils.appendToFile(
            appProject.buildFile,
            ("androidComponents {\n"
                    + "  onVariants(selector().all()) { variant ->\n"
                    + "    variant.sources.assets?.addStaticSourceDirectory('src/static/assets')\n"
                    + "  }\n"
                    + "}")
        )
        execute("assembleDebug")
        checkApk(appProject, "static.txt", "app:static")
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
        check(
            TruthHelper.assertThat(project.getApk(GradleTestProject.ApkType.DEBUG)),
            filename,
            content
        )
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
        project: GradleTestProject, filename: String, content: String?
    ) {
        check(TruthHelper.assertThat(project.testApk), filename, content)
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
        project.assertAar(
            AarSelector.DEBUG,
            {
                check(this, filename, content)
            })
    }

    private fun check(
        subject: AbstractZipSubject<AarSubject, Zip>,
        filename: String,
        content: String?
    ) {
        if (content != null) {
            subject.textFile("assets/" + filename).isEqualTo(content)
        } else {
            subject.doesNotContain("assets/" + filename)
        }
    }

    private fun check(subject: ApkSubject, filename: String, content: String?) {
        if (content != null) {
            subject.containsFileWithContent("assets/" + filename, content)
        } else {
            subject.doesNotContain("assets/" + filename)
        }
    }
}
