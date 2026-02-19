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
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GeneratesAar
import com.android.build.gradle.integration.common.fixture.project.GeneratesApk
import com.android.build.gradle.integration.common.output.AbstractAndroidArchiveSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.utils.FileUtils
import com.google.common.base.Charsets
import com.google.common.io.Files
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** test for packaging of java resources. */
class JavaResPackagingTest {
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

  @Test
  fun testNonIncrementalPackaging() {
    project.executor().run("clean", "assembleDebug", "assembleAndroidTest")

    // check the files are there. Start from the bottom of the dependency graph
    libProject2.checkAar("library2.txt".withContent("library2:abcd"))
    libProject2.checkTestApk("library2.txt".withContent("library2:abcd"), "library2test.txt".withContent("library2Test:abcd"))

    // aar does not contain dependency's assets
    libProject.checkAar("library.txt".withContent("library:abcd"), "localjar.txt".withContent("localjar:abcd"))

    // test apk contains both test-only assets, lib assets, and dependency assets.
    // but not the assets of the dependency's own test
    libProject.checkTestApk(
      "library.txt".withContent("library:abcd"),
      "library2.txt".withContent("library2:abcd"),
      "localjar.txt".withContent("localjar:abcd"),
      "librarytest.txt".withContent("libraryTest:abcd"),
    )

    // app contain own assets + all dependencies' assets.
    appProject.checkApk(
      "app.txt".withContent("app:abcd"),
      "library.txt".withContent("library:abcd"),
      "library2.txt".withContent("library2:abcd"),
      "jar.txt".withContent("jar:abcd"),
      "localjar.txt".withContent("localjar:abcd"),
    )

    // app test contains test-ony assets (not app, dependency, or dependency test assets).
    appProject.checkTestApk("apptest.txt".withContent("appTest:abcd"))

    // All APKs should exclude .kotlin_module files, but the AAR should include it.
    appProject.assertApk(ApkSelector.DEBUG) {
      javaResources()
        .folder("META-INF")
        .containsExactly("MANIFEST.MF", "CERT.RSA", "CERT.SF", "com/android/build/gradle/app-metadata.properties")
    }
    appProject.assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
      javaResources().folder("META-INF").containsExactly("MANIFEST.MF", "CERT.RSA", "CERT.SF")
    }
    libProject.checkAarMetaInf("foo.kotlin_module".withContent("library:abcd"))
    libProject.assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
      javaResources().folder("META-INF").containsExactly("MANIFEST.MF", "CERT.RSA", "CERT.SF")
    }
  }

  // ---- TEST DEFAULT ---
  @Test
  fun testTestProjectWithNewResFile() {
    project.executor().run("test:clean", "test:assembleDebug")

    TemporaryProjectModification.doTest(testProject) { project ->
      project.addFile("src/main/resources/com/foo/newtest.txt", "newfile content")
      this.project.executor().run("test:assembleDebug")
      testProject.checkApk("newtest.txt".withContent("newfile content"), "test.txt")
    }
  }

  @Test
  fun testTestProjectWithRemovedResFile() {
    project.executor().run("test:clean", "test:assembleDebug")

    TemporaryProjectModification.doTest(testProject) { project ->
      project.removeFile("src/main/resources/com/foo/test.txt")
      this.project.executor().run("test:assembleDebug")
      testProject.checkApk()
    }
  }

  @Test
  fun testTestProjectWithModifiedResFile() {
    project.executor().run("test:clean", "test:assembleDebug")

    TemporaryProjectModification.doTest(testProject) { project ->
      project.replaceFile("src/main/resources/com/foo/test.txt", "new content")
      this.project.executor().run("test:assembleDebug")
      testProject.checkApk("test.txt".withContent("new content"))
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
   * check an aar has (or not) the given res file name.
   *
   * If the content is non-null the file is expected to be there with the same content. If the content is null the file is not expected to
   * be there.
   *
   * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
   *   presence, or [StringWithContent] to validate presence and content.
   */
  private fun GeneratesAar.checkAar(vararg itemList: Any) {
    assertAar(AarSelector.DEBUG) { checkJavaRes("com/foo", *itemList) }
  }

  /**
   * check an aar has (or not) the given res file name.
   *
   * If the content is non-null the file is expected to be there with the same content. If the content is null the file is not expected to
   * be there.
   *
   * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
   *   presence, or [StringWithContent] to validate presence and content.
   */
  private fun GeneratesAar.checkAarMetaInf(vararg itemList: Any) {
    assertAar(AarSelector.DEBUG) { checkJavaRes("META-INF", *itemList) }
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
