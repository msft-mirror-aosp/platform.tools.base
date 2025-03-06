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
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.output.AarSubject
import com.android.build.gradle.integration.common.truth.AbstractAndroidSubject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.truth.TruthHelper
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.testutils.truth.PathSubject
import com.android.utils.FileUtils
import com.google.common.base.Charsets
import com.google.common.io.Files
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

/** test for packaging of java resources.  */
class JavaResPackagingTest {
    @get:Rule
    val project = GradleTestProject.builder()
        .fromTestProject("projectWithModules")
        .create()

    private lateinit var appProject: GradleTestProject
    private lateinit var libProject: GradleTestProject
    private lateinit var libProject2: GradleTestProject
    private lateinit var testProject: GradleTestProject
    private lateinit var jarProject: GradleTestProject

    @Before
    @Throws(Exception::class)
    fun setUp() {
        appProject = project.getSubproject("app")
        libProject = project.getSubproject("library")
        libProject2 = project.getSubproject("library2")
        testProject = project.getSubproject("test")
        jarProject = project.getSubproject("jar")

        // Rewrite settings.gradle to remove un-needed modules. We include library3 so that
        // testAppProjectTestWithRemovedResFile() also serves as a regression test for
        // https://issuetracker.google.com/128858509
        project.setIncludedProjects("app", "library", "library2", "library3", "test", "jar")

        // setup dependencies.
        TestFileUtils.appendToFile(
            appProject.buildFile,
            ("android {\n"
                    + "    publishNonDefault = true\n"
                    + "}\n"
                    + "\n"
                    + "dependencies {\n"
                    + "    api project(':library')\n"
                    + "    api project(':library3')\n"
                    + "    api project(':jar')\n"
                    + "}\n")
        )

        TestFileUtils.appendToFile(
            libProject.buildFile,
            ("dependencies {\n"
                    + "    api project(':library2')\n"
                    + "    api files('libs/local.jar')\n"
                    + "}\n")
        )

        TestFileUtils.appendToFile(testProject.buildFile, "android { targetProjectPath ':app' }\n")

        // put some default files in the 4 projects, to check non incremental packaging as well,
        // and to provide files to change to test incremental support.
        val appDir = appProject.projectDir
        createOriginalResFile(appDir, "main", "app.txt", "app:abcd")
        createOriginalResFile(appDir, "androidTest", "apptest.txt", "appTest:abcd")
        // add some .kotlin_module files to ensure they're excluded by default.
        createOriginalResFile(appDir, "main", "META-INF", "foo.kotlin_module", "app:abcd")
        createOriginalResFile(
            appDir, "androidTest", "META-INF", "foo.kotlin_module", "appTest:abcd"
        )

        val testDir = testProject.projectDir
        createOriginalResFile(testDir, "main", "test.txt", "test:abcd")

        val libDir = libProject.projectDir
        createOriginalResFile(libDir, "main", "library.txt", "library:abcd")
        createOriginalResFile(libDir, "androidTest", "librarytest.txt", "libraryTest:abcd")
        // add some .kotlin_module files to ensure they're included for the AAR but excluded for the
        // android test APK.
        createOriginalResFile(libDir, "main", "META-INF", "foo.kotlin_module", "library:abcd")
        createOriginalResFile(
            libDir, "androidTest", "META-INF", "foo.kotlin_module", "libraryTest:abcd"
        )

        val lib2Dir = libProject2.projectDir
        createOriginalResFile(lib2Dir, "main", "library2.txt", "library2:abcd")
        createOriginalResFile(lib2Dir, "androidTest", "library2test.txt", "library2Test:abcd")

        val jarDir = jarProject.projectDir
        val resFolder = FileUtils.join(jarDir, "src", "main", "resources", "com", "foo")
        FileUtils.mkdirs(resFolder)
        Files.asCharSink(File(resFolder, "jar.txt"), Charsets.UTF_8).write("jar:abcd")
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun execute(vararg tasks: String): GradleBuildResult {
        return project.executor().run(*tasks)
    }

    @Test
    @Throws(Exception::class)
    fun testNonIncrementalPackaging() {
        project.executor().run("clean", "assembleDebug", "assembleAndroidTest")

        // check the files are there. Start from the bottom of the dependency graph
        checkAar(libProject2, "library2.txt", "library2:abcd")
        checkTestApk(libProject2, "library2.txt", "library2:abcd")
        checkTestApk(libProject2, "library2test.txt", "library2Test:abcd")

        checkAar(libProject, "library.txt", "library:abcd")
        checkAar(libProject, "localjar.txt", "localjar:abcd")
        // aar does not contain dependency's assets
        checkAar(libProject, "library2.txt", null)
        // test apk contains both test-only assets, lib assets, and dependency assets.
        checkTestApk(libProject, "library.txt", "library:abcd")
        checkTestApk(libProject, "library2.txt", "library2:abcd")
        checkTestApk(libProject, "localjar.txt", "localjar:abcd")
        checkTestApk(libProject, "librarytest.txt", "libraryTest:abcd")
        // but not the assets of the dependency's own test
        checkTestApk(libProject, "library2test.txt", null)

        // app contain own assets + all dependencies' assets.
        checkApk(appProject, "app.txt", "app:abcd")
        checkApk(appProject, "library.txt", "library:abcd")
        checkApk(appProject, "library2.txt", "library2:abcd")
        checkApk(appProject, "jar.txt", "jar:abcd")
        checkApk(appProject, "localjar.txt", "localjar:abcd")
        // app test contains test-ony assets (not app, dependency, or dependency test assets).
        checkTestApk(appProject, "apptest.txt", "appTest:abcd")
        checkTestApk(appProject, "app.txt", null)
        checkTestApk(appProject, "library.txt", null)
        checkTestApk(appProject, "library2.txt", null)
        checkTestApk(appProject, "localjar.txt", null)
        checkTestApk(appProject, "librarytest.txt", null)
        checkTestApk(appProject, "library2test.txt", null)

        // All APKs should exclude .kotlin_module files, but the AAR should include it.
        checkApk(appProject, "META-INF", "foo.kotlin_module", null)
        checkTestApk(appProject, "META-INF", "foo.kotlin_module", null)
        checkTestApk(libProject, "META-INF", "foo.kotlin_module", null)
        checkAar(libProject, "META-INF", "foo.kotlin_module", "library:abcd")
    }

    // ---- APP DEFAULT ---
    @Test
    @Throws(Exception::class)
    fun testAppProjectWithNewResFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            appProject
        ) { project: TemporaryProjectModification ->
            project.addFile("src/main/resources/com/foo/newapp.txt", "newfile content")
            execute("app:assembleDebug")
            checkApk(appProject, "newapp.txt", "newfile content")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectWithRemovedResFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            appProject
        ) { project ->
            project.removeFile("src/main/resources/com/foo/app.txt")
            execute("app:assembleDebug")
            checkApk(appProject, "app.txt", null)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectWithRenamedResFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            appProject
        ) { project ->
            project.removeFile("src/main/resources/com/foo/app.txt")
            project.addFile("src/main/resources/com/foo/moved_app.txt", "app:abcd")
            execute("app:assembleDebug")

            checkApk(appProject, "app.txt", null)
            checkApk(appProject, "moved_app.txt", "app:abcd")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectWithMovedResFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            appProject
        ) { project ->
            project.removeFile("src/main/resources/com/foo/app.txt")
            project.addFile("src/main/resources/com/bar/app.txt", "app:abcd")
            execute("app:assembleDebug")

            checkApk(appProject, "app.txt", null)
            checkApk(appProject, "com/bar", "app.txt", "app:abcd")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectWithModifiedResFile() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            appProject
        ) { project ->
            project.replaceFile("src/main/resources/com/foo/app.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "app.txt", "new content")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectWithNewDebugResFileOverridingMain() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            appProject
        ) { project ->
            project.addFile("src/debug/resources/com/foo/app.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "app.txt", "new content")
        }

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        checkApk(appProject, "app.txt", "app:abcd")
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectWithNewResFileOverridingDependency() {
        val resourcePath = "src/main/resources/com/foo/library.txt"

        execute("app:clean", "app:assembleDebug")
        checkApk(appProject, "library.txt", "library:abcd")

        TemporaryProjectModification.doTest(
            appProject
        ) { project ->
            project.addFile(resourcePath, "new content")
            PathSubject.assertThat(appProject.file(resourcePath)).exists()
            val result = execute("app:assembleDebug")
            result.stdout.use { stdout ->
                ScannerSubject.assertThat(stdout)
                    .contains(
                        "More than one file was found with OS independent path"
                                + " 'com/foo/library.txt'."
                    )
            }
            checkApk(appProject, "library.txt", "new content")
        }

        // Trying to figure out why the test is flaky?
        PathSubject.assertThat(appProject.file(resourcePath)).doesNotExist()

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        checkApk(appProject, "library.txt", "library:abcd")
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectWithNewResFileInDebugSourceSet() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            appProject
        ) { project ->
            project.addFile("src/debug/resources/com/foo/app.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "app.txt", "new content")
        }

        // file's been removed, checking in the other direction.
        execute("app:assembleDebug")
        checkApk(appProject, "app.txt", "app:abcd")
    }

    /**
     * Check for correct behavior when the order of pre-merged java resource jar files changes. This
     * must be supported in order to use @Classpath annotations on the MergeJavaResourceTask inputs.
     */
    @Test
    @Throws(Exception::class)
    fun testAppProjectWithReorderedDeps() {
        execute("app:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            appProject
        ) { project ->
            // change order of dependencies in app from (library, library3, jar) to
            // (library3, jar, library).
            project.replaceInFile("build.gradle", ":library3", ":tempLibrary3")
            project.replaceInFile("build.gradle", ":library", ":tempLibrary")
            project.replaceInFile("build.gradle", ":jar", ":tempJar")
            project.replaceInFile("build.gradle", ":tempLibrary3", ":jar")
            project.replaceInFile("build.gradle", ":tempLibrary", ":library3")
            project.replaceInFile("build.gradle", ":tempJar", ":library")
            execute("app:assembleDebug")

            checkApk(appProject, "library.txt", "library:abcd")
            checkApk(appProject, "library2.txt", "library2:abcd")
            checkApk(appProject, "jar.txt", "jar:abcd")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectWithModifiedResInDependency() {
        execute("app:clean", "library:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.replaceFile("src/main/resources/com/foo/library.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "library.txt", "new content")
        }
    }

    /**
     * Check for correct behavior when a java res source file get removed.
     *
     * Also, with app's dependency on library3, this serves as a regression test for
     * https://issuetracker.google.com/128858509
     */
    @Test
    @Throws(Exception::class)
    fun testAppProjectWithAddedResInDependency() {
        execute("app:clean", "library:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.addFile("src/main/resources/com/foo/newlibrary.txt", "new content")
            execute("app:assembleDebug")
            checkApk(appProject, "newlibrary.txt", "new content")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectWithRemovedResInDependency() {
        execute("app:clean", "library:clean", "app:assembleDebug")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.removeFile("src/main/resources/com/foo/library.txt")
            project.replaceInFile("build.gradle", "api files(.*)", "")
            execute("app:assembleDebug")

            checkApk(appProject, "library.txt", null)
            checkApk(appProject, "localjar.txt", null)
        }
    }

    // ---- APP TEST ---
    @Test
    @Throws(Exception::class)
    fun testAppProjectTestWithNewResFile() {
        execute("app:clean", "app:assembleAT")

        TemporaryProjectModification.doTest(
            appProject
         ) { project ->
            project.addFile("src/androidTest/resources/com/foo/newapp.txt", "new file content")
            execute("app:assembleAT")
            checkTestApk(appProject, "newapp.txt", "new file content")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectTestWithRemovedResFile() {
        execute("app:clean", "app:assembleAT")

        TemporaryProjectModification.doTest(
            appProject
        ) { project ->
            project.removeFile("src/androidTest/resources/com/foo/apptest.txt")
            execute("app:assembleAT")
            checkTestApk(appProject, "apptest.txt", null)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAppProjectTestWithModifiedResFile() {
        execute("app:clean", "app:assembleAT")

        TemporaryProjectModification.doTest(
            appProject
        ) { project ->
            project.replaceFile("src/androidTest/resources/com/foo/apptest.txt", "new content")
            execute("app:assembleAT")
            checkTestApk(appProject, "apptest.txt", "new content")
        }
    }

    // ---- LIB DEFAULT ---
    @Test
    @Throws(Exception::class)
    fun testLibProjectWithNewResFile() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.addFile("src/main/resources/com/foo/newlibrary.txt", "newfile content")
            execute("library:assembleDebug")
            checkAar(libProject, "newlibrary.txt", "newfile content")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testLibProjectWithRemovedFile() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.removeFile("src/main/resources/com/foo/library.txt")
            project.replaceInFile("build.gradle", "api files(.*)", "")
            execute("library:assembleDebug")

            checkAar(libProject, "library.txt", null)
            checkAar(libProject, "localjar.txt", null)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testLibProjectWithModifiedResFile() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.replaceFile("src/main/resources/com/foo/library.txt", "new content")
            execute("library:assembleDebug")
            checkAar(libProject, "library.txt", "new content")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testLibProjectWithNewResFileInDebugSourceSet() {
        execute("library:clean", "library:assembleDebug")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.addFile("src/debug/resources/com/foo/library.txt", "new content")
            execute("library:assembleDebug")
            checkAar(libProject, "library.txt", "new content")
        }

        // file's been removed, checking in the other direction.
        execute("library:assembleDebug")
        checkAar(libProject, "library.txt", "library:abcd")
    }

    // ---- LIB TEST ---
    @Test
    @Throws(Exception::class)
    fun testLibProjectTestWithNewResFile() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.addFile("src/androidTest/resources/com/foo/newlibrary.txt", "new file content")
            execute("library:assembleAT")
            checkTestApk(libProject, "newlibrary.txt", "new file content")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testLibProjectTestWithRemovedResFile() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.removeFile("src/androidTest/resources/com/foo/librarytest.txt")
            project.replaceInFile("build.gradle", "api files(.*)", "")
            execute("library:assembleAT")

            checkTestApk(libProject, "librarytest.txt", null)
            checkTestApk(libProject, "localjar.txt", null)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testLibProjectTestWithModifiedResFile() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.replaceFile("src/androidTest/resources/com/foo/librarytest.txt", "new content")
            execute("library:assembleAT")
            checkTestApk(libProject, "librarytest.txt", "new content")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testLibProjectTestWithNewResFileOverridingTestedLib() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.addFile("src/androidTest/resources/com/foo/library.txt", "new content")
            val result = execute("library:assembleAT")
            result.stdout.use { stdout ->
                ScannerSubject.assertThat(stdout).contains(
                    "More than one file was found with OS independent path 'com/foo/library.txt'."
                )
            }
            checkTestApk(libProject, "library.txt", "new content")
        }

        // file's been removed, checking in the other direction.
        execute("library:assembleAT")
        checkTestApk(libProject, "library.txt", "library:abcd")
    }

    @Test
    @Throws(Exception::class)
    fun testLibProjectTestWithNewResFileOverridingDependency() {
        execute("library:clean", "library:assembleAT")

        TemporaryProjectModification.doTest(
            libProject
        ) { project ->
            project.addFile(
                "src/androidTest/resources/com/foo/library2.txt", "new content"
            )
            val result = execute("library:assembleAT")
            result.stdout.use { stdout ->
                ScannerSubject.assertThat(stdout).contains(
                    "More than one file was found with OS independent path 'com/foo/library2.txt'."
                )
            }
            checkTestApk(libProject, "library2.txt", "new content")
        }

        // file's been removed, checking in the other direction.
        execute("library:assembleAT")
        checkTestApk(libProject, "library2.txt", "library2:abcd")
    }

    // ---- TEST DEFAULT ---
    @Test
    @Throws(Exception::class)
    fun testTestProjectWithNewResFile() {
        project.executor().run("test:clean", "test:assembleDebug")

        TemporaryProjectModification.doTest(
            testProject
        ) { project ->
            project.addFile("src/main/resources/com/foo/newtest.txt", "newfile content")
            this.project.executor().run("test:assembleDebug")
            checkApk(testProject, "newtest.txt", "newfile content")
        }
    }

    @Test
    @Throws(Exception::class)
    fun testTestProjectWithRemovedResFile() {
        project.executor().run("test:clean", "test:assembleDebug")

        TemporaryProjectModification.doTest(
            testProject
        ) { project ->
            project.removeFile("src/main/resources/com/foo/test.txt")
            this.project.executor().run("test:assembleDebug")
            checkApk(testProject, "test.txt", null)
        }
    }

    @Test
    @Throws(Exception::class)
    fun testTestProjectWithModifiedResFile() {
        project.executor().run("test:clean", "test:assembleDebug")

        TemporaryProjectModification.doTest(
            testProject
        ) { project ->
            project.replaceFile("src/main/resources/com/foo/test.txt", "new content")
            this.project.executor().run("test:assembleDebug")
            checkApk(testProject, "test.txt", "new content")
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
     * @param filename the filename
     * @param content the content
     */
    @Throws(Exception::class)
    private fun checkTestApk(
        project: GradleTestProject, filename: String, content: String?
    ) {
        checkTestApk(project, "com/foo", filename, content)
    }

    /**
     * check a test apk has (or not) the given res file name.
     *
     *
     * If the content is non-null the file is expected to be there with the same content. If the
     * content is null the file is not expected to be there.
     *
     * @param project the project
     * @param parentDirRelativePath the relative path of the file's parent directory
     * @param filename the filename
     * @param content the content
     */
    @Throws(Exception::class)
    private fun checkTestApk(
        project: GradleTestProject,
        parentDirRelativePath: String,
        filename: String,
        content: String?
    ) {
        check(TruthHelper.assertThat(project.testApk), parentDirRelativePath, filename, content)
    }

    companion object {
        @Throws(Exception::class)
        private fun createOriginalResFile(
            projectFolder: File,
            dimension: String,
            filename: String,
            content: String
        ) {
            createOriginalResFile(projectFolder, dimension, "com/foo", filename, content)
        }

        @Throws(Exception::class)
        private fun createOriginalResFile(
            projectFolder: File,
            dimension: String,
            parentDirRelativePath: String,
            filename: String,
            content: String
        ) {
            val resourcesFolder = FileUtils.join(projectFolder, "src", dimension, "resources")
            val parentFolder = File(resourcesFolder, parentDirRelativePath)
            FileUtils.mkdirs(parentFolder)
            Files.asCharSink(File(parentFolder, filename), Charsets.UTF_8).write(content)
        }

        // --------------------------------
        /**
         * check an apk has (or not) the given res file name.
         *
         *
         * If the content is non-null the file is expected to be there with the same content. If the
         * content is null the file is not expected to be there.
         *
         * @param project the project
         * @param filename the filename
         * @param content the content
         */
        @Throws(Exception::class)
        private fun checkApk(
            project: GradleTestProject, filename: String, content: String?
        ) {
            checkApk(project, "com/foo", filename, content)
        }

        /**
         * check an apk has (or not) the given res file name.
         *
         *
         * If the content is non-null the file is expected to be there with the same content. If the
         * content is null the file is not expected to be there.
         *
         * @param project the project
         * @param parentDirRelativePath the relative path of the file's parent directory
         * @param filename the filename
         * @param content the content
         */
        @Throws(Exception::class)
        private fun checkApk(
            project: GradleTestProject,
            parentDirRelativePath: String,
            filename: String,
            content: String?
        ) {
            check(TruthHelper.assertThat(project.getApk("debug")), parentDirRelativePath, filename, content)
        }

        /**
         * check an aar has (or not) the given res file name.
         *
         *
         * If the content is non-null the file is expected to be there with the same content. If the
         * content is null the file is not expected to be there.
         *
         * @param project the project
         * @param filename the filename
         * @param content the content
         */
        @Throws(Exception::class)
        private fun checkAar(
            project: GradleTestProject, filename: String, content: String?
        ) {
            checkAar(project, "com/foo", filename, content)
        }

        /**
         * check an aar has (or not) the given res file name.
         *
         *
         * If the content is non-null the file is expected to be there with the same content. If the
         * content is null the file is not expected to be there.
         *
         * @param project the project
         * @param parentDirRelativePath the relative path of the file's parent directory
         * @param filename the filename
         * @param content the content
         */
        @Throws(Exception::class)
        private fun checkAar(
            project: GradleTestProject,
            parentDirRelativePath: String,
            filename: String,
            content: String?
        ) {
            project.testAar("debug") { it: AarSubject ->
                check(it, parentDirRelativePath, filename, content)
            }
        }

        /**
         * check an AbstractAndroidSubject has (or not) the given res file name.
         *
         *
         * If the content is non-null the file is expected to be there with the same content. If the
         * content is null the file is not expected to be there.
         *
         * @param subject the AbstractAndroidSubject
         * @param parentDirRelativePath the relative path of the file's parent directory
         * @param filename the filename
         * @param content the content
         */
        private fun check(
            subject: AbstractAndroidSubject<*, *>,
            parentDirRelativePath: String,
            filename: String,
            content: String?
        ) {
            if (content != null) {
                subject.containsJavaResourceWithContent(
                    "$parentDirRelativePath/$filename", content
                )
            } else {
                subject.doesNotContainJavaResource("$parentDirRelativePath/$filename")
            }
        }

        /**
         * check an AbstractAndroidSubject has (or not) the given res file name.
         *
         *
         * If the content is non-null the file is expected to be there with the same content. If the
         * content is null the file is not expected to be there.
         *
         * @param subject the AbstractAndroidSubject
         * @param parentDirRelativePath the relative path of the file's parent directory
         * @param filename the filename
         * @param content the content
         */
        private fun check(
            subject: AarSubject,
            parentDirRelativePath: String,
            filename: String,
            content: String?
        ) {
            if (content != null) {
                subject.allJars()
                    .resourceAsText("$parentDirRelativePath/$filename")
                    .isEqualTo(content)
            } else {
                subject.allJars().doesNotContainResource("$parentDirRelativePath/$filename")
            }
        }
    }
}
