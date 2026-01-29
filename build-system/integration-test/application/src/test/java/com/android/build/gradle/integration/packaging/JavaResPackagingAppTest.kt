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
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GeneratesApk
import com.android.build.gradle.integration.common.output.AbstractAndroidArchiveSubject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.testutils.truth.PathSubject
import com.android.utils.FileUtils
import com.google.common.base.Charsets
import com.google.common.io.Files
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** test for packaging of java resources. */
class JavaResPackagingAppTest {
  @get:Rule val project = GradleTestProject.builder().fromTestProject("projectWithModules").create()

  private lateinit var appProject: GradleTestProject
  private lateinit var libProject: GradleTestProject
  private lateinit var libProject2: GradleTestProject
  private lateinit var testProject: GradleTestProject
  private lateinit var jarProject: GradleTestProject

  @Before
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
      ("dependencies {\n" + "    api project(':library')\n" + "    api project(':library3')\n" + "    api project(':jar')\n" + "}\n"),
    )

    TestFileUtils.appendToFile(
      libProject.buildFile,
      ("dependencies {\n" + "    api project(':library2')\n" + "    api files('libs/local.jar')\n" + "}\n"),
    )

    TestFileUtils.appendToFile(testProject.buildFile, "android { targetProjectPath = ':app' }\n")

    // put some default files in the 4 projects, to check non incremental packaging as well,
    // and to provide files to change to test incremental support.
    val appDir = appProject.projectDir
    createOriginalResFile(appDir, "main", "app.txt", "app:abcd")
    createOriginalResFile(appDir, "androidTest", "apptest.txt", "appTest:abcd")
    // add some .kotlin_module files to ensure they're excluded by default.
    createOriginalResFile(appDir, "main", "META-INF", "foo.kotlin_module", "app:abcd")
    createOriginalResFile(appDir, "androidTest", "META-INF", "foo.kotlin_module", "appTest:abcd")

    val testDir = testProject.projectDir
    createOriginalResFile(testDir, "main", "test.txt", "test:abcd")

    val libDir = libProject.projectDir
    createOriginalResFile(libDir, "main", "library.txt", "library:abcd")
    createOriginalResFile(libDir, "androidTest", "librarytest.txt", "libraryTest:abcd")
    // add some .kotlin_module files to ensure they're included for the AAR but excluded for the
    // android test APK.
    createOriginalResFile(libDir, "main", "META-INF", "foo.kotlin_module", "library:abcd")
    createOriginalResFile(libDir, "androidTest", "META-INF", "foo.kotlin_module", "libraryTest:abcd")

    val lib2Dir = libProject2.projectDir
    createOriginalResFile(lib2Dir, "main", "library2.txt", "library2:abcd")
    createOriginalResFile(lib2Dir, "androidTest", "library2test.txt", "library2Test:abcd")

    val jarDir = jarProject.projectDir
    val resFolder = FileUtils.join(jarDir, "src", "main", "resources", "com", "foo")
    FileUtils.mkdirs(resFolder)
    Files.asCharSink(File(resFolder, "jar.txt"), Charsets.UTF_8).write("jar:abcd")
  }

  private fun execute(vararg tasks: String): GradleBuildResult {
    return project.executor().run(*tasks)
  }

  // ---- APP DEFAULT ---
  @Test
  fun testAppProjectWithNewResFile() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(appProject) { project: TemporaryProjectModification ->
      project.addFile("src/main/resources/com/foo/newapp.txt", "newfile content")
      execute("app:assembleDebug")
      appProject.checkApk("newapp.txt".withContent("newfile content"), "app.txt", "jar.txt", "library.txt", "library2.txt", "localjar.txt")
    }
  }

  @Test
  fun testAppProjectWithRemovedResFile() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(appProject) { project ->
      project.removeFile("src/main/resources/com/foo/app.txt")
      execute("app:assembleDebug")
      appProject.checkApk("jar.txt", "library.txt", "library2.txt", "localjar.txt")
    }
  }

  @Test
  fun testAppProjectWithRenamedResFile() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(appProject) { project ->
      project.removeFile("src/main/resources/com/foo/app.txt")
      project.addFile("src/main/resources/com/foo/moved_app.txt", "app:abcd")
      execute("app:assembleDebug")

      appProject.checkApk("moved_app.txt".withContent("app:abcd"), "jar.txt", "library.txt", "library2.txt", "localjar.txt")
    }
  }

  @Test
  fun testAppProjectWithMovedResFile() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(appProject) { project ->
      project.removeFile("src/main/resources/com/foo/app.txt")
      project.addFile("src/main/resources/com/bar/app.txt", "app:abcd")
      execute("app:assembleDebug")

      appProject.checkApkWithPath("com/foo", "jar.txt", "library.txt", "library2.txt", "localjar.txt")
      appProject.checkApkWithPath("com/bar", "app.txt".withContent("app:abcd"))
    }
  }

  @Test
  fun testAppProjectWithModifiedResFile() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(appProject) { project ->
      project.replaceFile("src/main/resources/com/foo/app.txt", "new content")
      execute("app:assembleDebug")
      appProject.checkApk("app.txt".withContent("new content"), "jar.txt", "library.txt", "library2.txt", "localjar.txt")
    }
  }

  @Test
  fun testAppProjectWithNewDebugResFileOverridingMain() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(appProject) { project ->
      project.addFile("src/debug/resources/com/foo/app.txt", "new content")
      execute("app:assembleDebug")
      appProject.checkApk("app.txt".withContent("new content"), "jar.txt", "library.txt", "library2.txt", "localjar.txt")
    }

    // file's been removed, checking in the other direction.
    execute("app:assembleDebug")
    appProject.checkApk("app.txt".withContent("app:abcd"), "jar.txt", "library.txt", "library2.txt", "localjar.txt")
  }

  @Test
  fun testAppProjectWithNewResFileOverridingDependency() {
    val resourcePath = "src/main/resources/com/foo/library.txt"

    execute("app:assembleDebug")
    appProject.checkApk("library.txt".withContent("library:abcd"), "app.txt", "jar.txt", "library2.txt", "localjar.txt")

    TemporaryProjectModification.doTest(appProject) { project ->
      project.addFile(resourcePath, "new content")
      PathSubject.assertThat(appProject.file(resourcePath)).exists()
      val result = execute("app:assembleDebug")
      result.stdout.use { stdout ->
        ScannerSubject.assertThat(stdout).contains("More than one file was found with OS independent path" + " 'com/foo/library.txt'.")
      }
      appProject.checkApk("library.txt".withContent("new content"), "app.txt", "jar.txt", "library2.txt", "localjar.txt")
    }

    // Trying to figure out why the test is flaky?
    PathSubject.assertThat(appProject.file(resourcePath)).doesNotExist()

    // file's been removed, checking in the other direction.
    execute("app:assembleDebug")
    appProject.checkApk("library.txt".withContent("library:abcd"), "app.txt", "jar.txt", "library2.txt", "localjar.txt")
  }

  @Test
  fun testAppProjectWithNewResFileInDebugSourceSet() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(appProject) { project ->
      project.addFile("src/debug/resources/com/foo/app.txt", "new content")
      execute("app:assembleDebug")
      appProject.checkApk("app.txt".withContent("new content"), "jar.txt", "library.txt", "library2.txt", "localjar.txt")
    }

    // file's been removed, checking in the other direction.
    execute("app:assembleDebug")
    appProject.checkApk("app.txt".withContent("app:abcd"), "jar.txt", "library.txt", "library2.txt", "localjar.txt")
  }

  /**
   * Check for correct behavior when the order of pre-merged java resource jar files changes. This must be supported in order to
   * use @Classpath annotations on the MergeJavaResourceTask inputs.
   */
  @Test
  fun testAppProjectWithReorderedDeps() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(appProject) { project ->
      // change order of dependencies in app from (library, library3, jar) to
      // (library3, jar, library).
      project.replaceInFile("build.gradle", ":library3", ":tempLibrary3")
      project.replaceInFile("build.gradle", ":library", ":tempLibrary")
      project.replaceInFile("build.gradle", ":jar", ":tempJar")
      project.replaceInFile("build.gradle", ":tempLibrary3", ":jar")
      project.replaceInFile("build.gradle", ":tempLibrary", ":library3")
      project.replaceInFile("build.gradle", ":tempJar", ":library")
      execute("app:assembleDebug")

      appProject.checkApk(
        "library.txt".withContent("library:abcd"),
        "library2.txt".withContent("library2:abcd"),
        "jar.txt".withContent("jar:abcd"),
        "app.txt",
        "localjar.txt",
      )
    }
  }

  @Test
  fun testAppProjectWithModifiedResInDependency() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(libProject) { project ->
      project.replaceFile("src/main/resources/com/foo/library.txt", "new content")
      execute("app:assembleDebug")
      appProject.checkApk("library.txt".withContent("new content"), "app.txt", "jar.txt", "library.txt", "library2.txt", "localjar.txt")
    }
  }

  /**
   * Check for correct behavior when a java res source file get removed.
   *
   * Also, with app's dependency on library3, this serves as a regression test for https://issuetracker.google.com/128858509
   */
  @Test
  fun testAppProjectWithAddedResInDependency() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(libProject) { project ->
      project.addFile("src/main/resources/com/foo/newlibrary.txt", "new content")
      execute("app:assembleDebug")
      appProject.checkApk("newlibrary.txt".withContent("new content"), "app.txt", "jar.txt", "library.txt", "library2.txt", "localjar.txt")
    }
  }

  @Test
  fun testAppProjectWithRemovedResInDependency() {
    execute("app:assembleDebug")

    TemporaryProjectModification.doTest(libProject) { project ->
      project.removeFile("src/main/resources/com/foo/library.txt")
      project.replaceInFile("build.gradle", "api files(.*)", "")
      execute("app:assembleDebug")

      appProject.checkApk("app.txt", "jar.txt", "library2.txt")
    }
  }

  // ---- APP TEST ---
  @Test
  fun testAppProjectTestWithNewResFile() {
    execute("app:assembleAT")

    TemporaryProjectModification.doTest(appProject) { project ->
      project.addFile("src/androidTest/resources/com/foo/newapp.txt", "new file content")
      execute("app:assembleAT")
      appProject.checkTestApk("newapp.txt".withContent("new file content"), "apptest.txt")
    }
  }

  @Test
  fun testAppProjectTestWithRemovedResFile() {
    execute("app:assembleAT")

    TemporaryProjectModification.doTest(appProject) { project ->
      project.removeFile("src/androidTest/resources/com/foo/apptest.txt")
      execute("app:assembleAT")
      appProject.checkTestApk()
    }
  }

  @Test
  fun testAppProjectTestWithModifiedResFile() {
    execute("app:assembleAT")

    TemporaryProjectModification.doTest(appProject) { project ->
      project.replaceFile("src/androidTest/resources/com/foo/apptest.txt", "new content")
      execute("app:assembleAT")
      appProject.checkTestApk("apptest.txt".withContent("new content"))
    }
  }

  private fun createOriginalResFile(projectFolder: File, dimension: String, filename: String, content: String) {
    createOriginalResFile(projectFolder, dimension, "com/foo", filename, content)
  }

  private fun createOriginalResFile(
    projectFolder: File,
    dimension: String,
    parentDirRelativePath: String,
    filename: String,
    content: String,
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
   * If the content is non-null the file is expected to be there with the same content. If the
   *
   * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
   *   presence, or [StringWithContent] to validate presence and content.
   */
  private fun GeneratesApk.checkApk(vararg itemList: Any) {
    checkApkWithPath("com/foo", *itemList)
  }

  /**
   * check an apk has (or not) the given res file name.
   *
   * If the content is non-null the file is expected to be there with the same content. If the
   *
   * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
   *   presence, or [StringWithContent] to validate presence and content.
   */
  private fun GeneratesApk.checkApkWithPath(folderPath: String, vararg itemList: Any) {
    assertApk(ApkSelector.DEBUG) { checkJavaRes(folderPath, *itemList) }
  }

  /**
   * check an apk has (or not) the given res file name.
   *
   * If the content is non-null the file is expected to be there with the same content. If the
   *
   * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
   *   presence, or [StringWithContent] to validate presence and content.
   */
  private fun GeneratesApk.checkTestApk(vararg itemList: Any) {
    assertApk(ApkSelector.ANDROIDTEST_DEBUG) { checkJavaRes("com/foo", *itemList) }
  }

  /**
   * Checks the android archive has the specific list of assets. The list must be exhaustive.
   *
   * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
   *   presence, or [StringWithContent] to validate presence and content.
   */
  private fun AbstractAndroidArchiveSubject<*, *>.checkJavaRes(folder: String, vararg itemList: Any) {
    javaResources().folder(folder).apply {
      if (itemList.isEmpty()) {
        isEmpty()
      } else {
        val itemsWithContent = itemList.mapNotNull { it as? StringWithContent }
        val itemNames =
          itemList.map {
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
