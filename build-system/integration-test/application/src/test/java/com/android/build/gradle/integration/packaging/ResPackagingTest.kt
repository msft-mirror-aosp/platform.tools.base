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

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GeneratesAar
import com.android.build.gradle.integration.common.fixture.project.GeneratesApk
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.output.AbstractAndroidArchiveSubject
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * test for packaging of android asset files.
 *
 * This only uses raw files. This is not about running aapt tests, this is only about everything around it, so raw files are easier to test
 * in isolation.
 */
class ResPackagingTest {
  // Add a timeout so there's a trace dump on Windows when the test hangs (b/178233111).
  @get:Rule val timeout = Timeout.builder().withTimeout(180, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication(":app") {
        dependencies { api(project(":library")) }
        files {
          // put some default files in the 4 projects, to check non incremental packaging as well,
          // and to provide files to change to test incremental support.
          createOriginalResFile("main", "file.txt", "app:abcd")
          createOriginalResFile("androidTest", "filetest.txt", "appTest:abcd")
        }
      }
      androidLibrary(":library") {
        dependencies { api(project(":library2")) }
        files {
          createOriginalResFile("main", "filelib.txt", "library:abcd")
          createOriginalResFile("androidTest", "filelibtest.txt", "libraryTest:abcd")
        }
      }
      androidLibrary(":library2") {
        files {
          createOriginalResFile("main", "filelib2.txt", "library2:abcd")
          createOriginalResFile("androidTest", "filelib2test.txt", "library2Test:abcd")
        }
      }
      androidTest(":test") {
        android { targetProjectPath = ":app" }
        files.createOriginalResFile("main", "file.txt", "test:abcd")
      }
    }

  private fun execute(vararg tasks: String): GradleBuild {
    val build = rule.build

    build.executor.run(*tasks)

    return build
  }

  @Test
  fun testNonIncrementalPackaging() {
    val build = execute("assembleDebug", "assembleAndroidTest")

    // chek the files are there. Start from the bottom of the dependency graph
    val libProject2 = build.androidLibrary(":library2")
    libProject2.checkAarResources("filelib2.txt".withContent("library2:abcd"))
    libProject2.checkTestApkResources("filelib2.txt".withContent("library2:abcd"), "filelib2test.txt".withContent("library2Test:abcd"))

    // aar does not contain dependency's assets
    val libProject = build.androidLibrary(":library")
    libProject.checkAarResources("filelib.txt".withContent("library:abcd"))

    // test apk contains both test-ony assets, lib assets, and dependency assets.
    // but not the assets of the dependency's own test
    libProject.checkTestApkResources(
      "filelib.txt".withContent("library:abcd"),
      "filelib2.txt".withContent("library2:abcd"),
      "filelibtest.txt".withContent("libraryTest:abcd"),
    )

    // app contain own assets + all dependencies' assets.
    val appProject = build.androidApplication(":app")
    appProject.checkApkResources(
      "file.txt".withContent("app:abcd"),
      "filelib.txt".withContent("library:abcd"),
      "filelib2.txt".withContent("library2:abcd"),
    )
    // app test does not contain dependencies' own test assets.
    appProject.checkTestApkResources("filetest.txt".withContent("appTest:abcd"))
  }

  // ---- APP DEFAULT ---
  @Test
  fun testAppProjectWithNewResFile() {
    val build = execute("app:assembleDebug")

    val appProject = build.androidApplication(":app")
    appProject.files.add("src/main/res/raw/newfile.txt", "newfile content")

    build.executor.run(":app:assemble")

    appProject.checkApkResources("newfile.txt".withContent("newfile content"), "filelib2.txt", "filelib.txt", "file.txt")
  }

  @Test
  fun testAppProjectWithRemovedResFile() {
    val build = execute("app:assembleDebug")

    val appProject = build.androidApplication(":app")
    appProject.files.remove("src/main/res/raw/file.txt")

    build.executor.run("app:assembleDebug")

    appProject.checkApkResources("filelib2.txt", "filelib.txt")
  }

  @Test
  fun testAppProjectWithModifiedResFile() {
    val build = execute("app:assembleDebug")

    val appProject = build.androidApplication(":app")
    appProject.files.update("src/main/res/raw/file.txt").replaceWith("new content")

    build.executor.run("app:assembleDebug")

    appProject.checkApkResources("file.txt".withContent("new content"), "filelib2.txt", "filelib.txt")
  }

  @Test
  fun testAppProjectWithNewDebugResFileOverridingMain() {
    val build = execute("app:assembleDebug")

    build.withReversibleModifications { build ->
      val appProject = build.androidApplication(":app")
      appProject.files.add("src/debug/res/raw/file.txt", "new content")

      build.executor.run("app:assembleDebug")

      appProject.checkApkResources("file.txt".withContent("new content"), "filelib2.txt", "filelib.txt")
    }

    // file's been removed, checking in the other direction.
    build.executor.run("app:assembleDebug")

    build.androidApplication(":app").checkApkResources("file.txt".withContent("app:abcd"), "filelib2.txt", "filelib.txt")
  }

  @Test
  fun testAppProjectWithNewResFileOverridingDependency() {
    val build = execute("app:assembleDebug")

    build.withReversibleModifications { build ->
      val appProject = build.androidApplication(":app")
      appProject.files.add("src/main/res/raw/filelib.txt", "new content")

      build.executor.run("app:assembleDebug")

      appProject.checkApkResources("filelib.txt".withContent("new content"), "filelib2.txt", "file.txt")
    }

    // file's been removed, checking in the other direction.
    build.executor.run("app:assembleDebug")
    build.androidApplication(":app").checkApkResources("filelib.txt".withContent("library:abcd"), "filelib2.txt", "file.txt")
  }

  @Test
  fun testAppProjectWithNewResFileInDebugSourceSet() {
    val build = execute("app:assembleDebug")

    build.withReversibleModifications { build ->
      val appProject = build.androidApplication(":app")
      appProject.files.add("src/debug/res/raw/file.txt", "new content")

      build.executor.run("app:assembleDebug")

      appProject.checkApkResources("file.txt".withContent("new content"), "filelib2.txt", "filelib.txt")
    }

    // file's been removed, checking in the other direction.
    build.executor.run("app:assembleDebug")
    build.androidApplication(":app").checkApkResources("file.txt".withContent("app:abcd"), "filelib2.txt", "filelib.txt")
  }

  @Test
  fun testAppProjectWithModifiedResInDependency() {
    val build = execute("app:assembleDebug")

    val appProject = build.androidApplication(":app")

    appProject.files.update("src/main/res/raw/filelib.txt").replaceWith("new content")
    build.executor.run("app:assembleDebug")
    appProject.checkApkResources("filelib.txt".withContent("new content"), "filelib2.txt", "file.txt")
  }

  @Test
  fun testAppProjectWithAddedResInDependency() {
    val build = execute("app:assembleDebug")

    val libProject = build.androidLibrary(":library")
    libProject.files.add("src/main/res/raw/new_lib_file.txt", "new content")

    build.executor.run("app:assembleDebug")

    build
      .androidApplication(":app")
      .checkApkResources("new_lib_file.txt".withContent("new content"), "filelib2.txt", "filelib.txt", "file.txt")
  }

  @Test
  fun testAppProjectWithRemovedResInDependency() {
    val build = execute("app:assembleDebug")

    val libProject = build.androidLibrary(":library")
    libProject.files.remove("src/main/res/raw/filelib.txt")

    build.executor.run("app:assembleDebug")

    build.androidApplication(":app").checkApkResources("filelib2.txt", "file.txt")
  }

  @Test
  fun testAppResourcesAreFilteredByMinSdkFull() {
    rule.build.testAppResourcesAreFilteredByMinSdk(false)
  }

  @Test
  fun testAppResourcesAreFilteredByMinSdkIncremental() {
    // Note: this test is very similar to the previous one but, instead of trying all 3
    // versions independently, we start with min SDK 26, then change to <26 and set
    // min SDK to 27. The outputs should be the same as in the previous test.
    rule.build.testAppResourcesAreFilteredByMinSdk(true)
  }

  private fun GradleBuild.testAppResourcesAreFilteredByMinSdk(incremental: Boolean) {
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
    val appProject = androidApplication(":app")

    val f1NoneC = "f1NoneC"
    val f2NoneC = "f2NoneC"
    val f2v26C = "f2v26C"
    val f3NoneC = "f3NoneC"
    val f3v26C = "f3v26C"
    val f3v27C = "f3v27C"
    val f4v26C = "f4v26C"
    val f4v27C = "f4v27C"
    val f5v27C = "f5v27C"

    appProject.files.apply {
      add("src/main/res/raw/f1", f1NoneC)

      add("src/main/res/raw/f2", f2NoneC)
      add("src/main/res/raw-v26/f2", f2v26C)

      add("src/main/res/raw/f3", f3NoneC)
      add("src/main/res/raw-v26/f3", f3v26C)
      add("src/main/res/raw-v27/f3", f3v27C)

      add("src/main/res/raw-v26/f4", f4v26C)
      add("src/main/res/raw-v27/f4", f4v27C)

      add("src/main/res/raw-v27/f5", f5v27C)
    }

    // Set min SDK version 26 and generate the APK.
    appProject.reconfigure { android { defaultConfig { minSdk = 26 } } }

    executor.run("clean", ":app:assembleDebug")

    appProject.assertApk(ApkSelector.DEBUG) {
      checkResources(resFolder = "raw", "f1".withContent("f1NoneC"), "file.txt", "filelib.txt", "filelib2.txt")
      checkResources(resFolder = "raw-v26", "f2".withContent(f2v26C), "f3".withContent(f3v26C), "f4".withContent(f4v26C))
      checkResources(resFolder = "raw-v27", "f3".withContent(f3v27C), "f4".withContent(f4v27C), "f5".withContent(f5v27C))
    }

    // Set lower min SDK version and generate the APK. Incremental update!
    appProject.reconfigure { android { defaultConfig { minSdk = 25 } } }

    if (incremental) {
      executor.run(":app:assembleDebug")
    } else {
      executor.run("clean", ":app:assembleDebug")
    }

    appProject.assertApk(ApkSelector.DEBUG) {
      checkResources(
        resFolder = "raw",
        "f1".withContent("f1NoneC"),
        "f2".withContent("f2NoneC"),
        "f3".withContent("f3NoneC"),
        "file.txt",
        "filelib.txt",
        "filelib2.txt",
      )
      checkResources(resFolder = "raw-v26", "f2".withContent(f2v26C), "f3".withContent(f3v26C), "f4".withContent(f4v26C))
      checkResources(resFolder = "raw-v27", "f3".withContent(f3v27C), "f4".withContent(f4v27C), "f5".withContent(f5v27C))
    }

    // Set min SDK version 27 and generate the APK. Incremental update!
    appProject.reconfigure { android { defaultConfig { minSdk = 27 } } }

    if (incremental) {
      executor.run(":app:assembleDebug")
    } else {
      executor.run("clean", ":app:assembleDebug")
    }

    appProject.assertApk(ApkSelector.DEBUG) {
      checkResources(resFolder = "raw", "f1".withContent("f1NoneC"), "file.txt", "filelib.txt", "filelib2.txt")
      checkResources(resFolder = "raw-v26", "f2".withContent(f2v26C))
      checkResources(resFolder = "raw-v27", "f3".withContent(f3v27C), "f4".withContent(f4v27C), "f5".withContent(f5v27C))
    }
  }

  // ---- APP TEST ---
  @Test
  fun testAppProjectTestWithNewResFile() {
    val build = execute("app:assembleAT")

    val appProject = build.androidApplication(":app")
    appProject.files.add("src/androidTest/res/raw/newfile.txt", "new file content")

    build.executor.run("app:assembleAT")

    appProject.checkTestApkResources("newfile.txt".withContent("new file content"), "filetest.txt")
  }

  @Test
  fun testAppProjectTestWithRemovedResFile() {
    val build = execute("app:assembleAT")

    val appProject = build.androidApplication(":app")
    appProject.files.remove("src/androidTest/res/raw/filetest.txt")

    build.executor.run("app:assembleAT")

    appProject.checkTestApkResources()
  }

  @Test
  fun testAppProjectTestWithModifiedResFile() {
    val build = execute("app:assembleAT")

    val appProject = build.androidApplication(":app")
    appProject.files.update("src/androidTest/res/raw/filetest.txt").replaceWith("new content")

    build.executor.run("app:assembleAT")

    appProject.checkTestApkResources("filetest.txt".withContent("new content"))
  }

  @Test
  fun testAppProjectWithMultipleFlavors() {
    val build =
      rule.build {
        androidApplication(":app") {
          android {
            flavorDimensions += "color"
            productFlavors {
              create("red") { it.dimension = "color" }
              create("blue") { it.dimension = "color" }
            }
          }
          files {
            add("src/red/res/raw/red.txt", "Red Text")
            add("src/blue/res/raw/blue.txt", "Blue Text")
          }
        }
      }

    build.executor.run("app:assembleDebug")

    val appProject = build.androidApplication(":app")

    appProject.assertApk(ApkSelector.DEBUG.withFlavor("red")) {
      checkRawResources("red.txt".withContent("Red Text"), "filelib2.txt", "filelib.txt", "file.txt")
    }
    appProject.assertApk(ApkSelector.DEBUG.withFlavor("blue")) {
      checkRawResources("blue.txt".withContent("Blue Text"), "filelib2.txt", "filelib.txt", "file.txt")
    }
  }

  // ---- LIB DEFAULT ---
  @Test
  fun testLibProjectWithNewResFile() {
    val build = execute("library:assembleDebug")

    val libProject = build.androidLibrary(":library")
    libProject.files.add("src/main/res/raw/newfile.txt", "newfile content")

    build.executor.run("library:assembleDebug")

    libProject.checkAarResources("newfile.txt".withContent("newfile content"), "filelib.txt")
  }

  @Test
  fun testLibProjectWithRemovedResFile() {
    val build = execute("library:assembleDebug")

    val libProject = build.androidLibrary(":library")
    libProject.files.remove("src/main/res/raw/filelib.txt")

    build.executor.run("library:assembleDebug")

    libProject.checkAarResources()
  }

  @Test
  fun testLibProjectWithModifiedResFile() {
    val build = execute("library:assembleDebug")

    val libProject = build.androidLibrary(":library")
    libProject.files.update("src/main/res/raw/filelib.txt").replaceWith("new content")

    build.executor.run("library:assembleDebug")

    libProject.checkAarResources("filelib.txt".withContent("new content"))
  }

  @Test
  fun testLibProjectWithNewResFileInDebugSourceSet() {
    val build = execute("library:assembleDebug")

    build.withReversibleModifications { build ->
      val libProject = build.androidLibrary(":library")
      libProject.files.add("src/debug/res/raw/filelib.txt", "new content")

      build.executor.run("library:assembleDebug")

      libProject.checkAarResources("filelib.txt".withContent("new content"))
    }

    // file's been removed, checking in the other direction.
    build.executor.run("library:assembleDebug")
    build.androidLibrary(":library").checkAarResources("filelib.txt".withContent("library:abcd"))
  }

  // ---- LIB TEST ---
  @Test
  fun testLibProjectTestWithNewResFile() {
    val build = execute("library:assembleAT")

    val libProject = build.androidLibrary(":library")
    libProject.files.add("src/androidTest/res/raw/newfile.txt", "new file content")

    build.executor.run("library:assembleAT")

    libProject.checkTestApkResources("newfile.txt".withContent("new file content"), "filelibtest.txt", "filelib2.txt", "filelib.txt")
  }

  @Test
  fun testLibProjectTestWithRemovedResFile() {
    val build = execute("library:assembleAT")

    val libProject = build.androidLibrary(":library")
    libProject.files.remove("src/androidTest/res/raw/filelibtest.txt")

    build.executor.run("library:assembleAT")

    libProject.checkTestApkResources("filelib2.txt", "filelib.txt")
  }

  @Test
  fun testLibProjectTestWithModifiedResFile() {
    val build = execute("library:assembleAT")

    val libProject = build.androidLibrary(":library")
    libProject.files.update("src/androidTest/res/raw/filelibtest.txt").replaceWith("new content")

    build.executor.run("library:assembleAT")

    libProject.checkTestApkResources("filelibtest.txt".withContent("new content"), "filelib2.txt", "filelib.txt")
  }

  @Test
  fun testLibProjectTestWithNewResFileOverridingTestedLib() {
    val build = execute("library:assembleAT")

    build.withReversibleModifications { build ->
      val libProject = build.androidLibrary(":library")
      libProject.files.add("src/androidTest/res/raw/filelib.txt", "new content")

      build.executor.run("library:assembleAT")

      libProject.checkTestApkResources("filelib.txt".withContent("new content"), "filelibtest.txt", "filelib2.txt")
    }

    // files been removed, checking in the other direction.
    build.executor.run("library:assembleAT")
    build.androidLibrary(":library").checkTestApkResources("filelib.txt".withContent("library:abcd"), "filelibtest.txt", "filelib2.txt")
  }

  @Test
  fun testLibProjectTestWithNewResFileOverridingDependency() {
    val build = execute("library:assembleAT")

    build.withReversibleModifications { build ->
      val libProject = build.androidLibrary(":library")
      libProject.files.add("src/androidTest/res/raw/filelib2.txt", "new content")

      build.executor.run("library:assembleAT")

      libProject.checkTestApkResources("filelib2.txt".withContent("new content"), "filelibtest.txt", "filelib.txt")
    }

    // file's been removed, checking in the other direction.
    build.executor.run("library:assembleAT")
    build.androidLibrary(":library").checkTestApkResources("filelib2.txt".withContent("library2:abcd"), "filelibtest.txt", "filelib.txt")
  }

  // ---- TEST DEFAULT ---
  @Test
  fun testTestProjectWithNewResFile() {
    val build = execute("test:assembleDebug")

    val testProject = build.androidTest(":test")
    testProject.files.add("src/main/res/raw/newfile.txt", "newfile content")

    build.executor.run("test:assembleDebug")

    testProject.checkApkResources("newfile.txt".withContent("newfile content"), "file.txt")
  }

  @Test
  fun testTestProjectWithRemovedResFile() {
    val build = execute("test:assembleDebug")

    val testProject = build.androidTest(":test")
    testProject.files.remove("src/main/res/raw/file.txt")

    build.executor.run("test:assembleDebug")

    testProject.checkApkResources()
  }

  @Test
  fun testTestProjectWithModifiedResFile() {
    val build = execute("test:assembleDebug")

    val testProject = build.androidTest(":test")
    testProject.files.update("src/main/res/raw/file.txt").replaceWith("new content")
    build.executor.run("test:assembleDebug")
    testProject.checkApkResources("file.txt".withContent("new content"))
  }

  companion object {
    private fun AndroidProjectFiles.createOriginalResFile(dimension: String, filename: String, content: String) {
      add("src/$dimension/res/raw/$filename", content)
    }

    // -----------------------
    /**
     * check an apk has (or not) the given res file name.
     *
     * If the content is non-null the file is expected to be there with the same content. If the content is null the file is not expected to
     * be there.
     *
     * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
     *   presence, or [StringWithContent] to validate presence and content.
     */
    private fun GeneratesApk.checkApkResources(vararg itemList: Any) {
      assertApk(ApkSelector.DEBUG) { checkRawResources(*itemList) }
    }

    /**
     * check a test apk has (or not) the given res file name.
     *
     * If the content is non-null the file is expected to be there with the same content. If the content is null the file is not expected to
     * be there.
     *
     * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
     *   presence, or [StringWithContent] to validate presence and content.
     */
    private fun GeneratesApk.checkTestApkResources(vararg itemList: Any) {
      assertApk(ApkSelector.ANDROIDTEST_DEBUG) { checkRawResources(*itemList) }
    }

    /**
     * check an aat has (or not) the given res file name.
     *
     * If the content is non-null the file is expected to be there with the same content. If the content is null the file is not expected to
     * be there.
     *
     * @param itemList a list of items that must be present in the android archive. The list can either contain [String] to just validate
     *   presence, or [StringWithContent] to validate presence and content.
     */
    private fun GeneratesAar.checkAarResources(vararg itemList: Any) {
      assertAar(AarSelector.DEBUG) { checkRawResources(*itemList) }
    }

    private fun AbstractAndroidArchiveSubject<*, *>.checkRawResources(vararg itemList: Any) {
      checkResources(resFolder = "raw", itemList = itemList)
    }

    private fun AbstractAndroidArchiveSubject<*, *>.checkResources(resFolder: String, vararg itemList: Any) {
      androidResources().folder(resFolder).apply {
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
}
