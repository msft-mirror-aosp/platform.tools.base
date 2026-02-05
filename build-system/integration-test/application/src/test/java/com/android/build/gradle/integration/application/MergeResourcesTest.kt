/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.AndroidApplicationProject
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.output.ApkContentSize
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.InternalArtifactType.MERGED_RES
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.IntegerOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.testutils.truth.ZipFileSubject.assertThat
import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readLines
import org.gradle.api.JavaVersion
import org.junit.Rule
import org.junit.Test

class MergeResourcesTest {

  @get:Rule
  val project =
    GradleRule.from {
      androidApplication {
        applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

        android {
          namespace = "com.example.android.multiproject.app"
          defaultConfig {
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            minSdk = 19
          }
          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
          }
        }
        kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8) } }
        files {
          add(
            "src/main/java/com/example/android/multiproject/app/MainActivity.java",
            // language=java
            """
            package com.example.android.multiproject.app;

            import android.app.Activity;
            import android.content.Intent;
            import android.os.Bundle;
            import android.view.View;

            class MainActivity extends Activity {
                @Override
                public void onCreate(Bundle savedInstanceState) {
                    super.onCreate(savedInstanceState);
                    setContentView(R.layout.main);
                }
            }
            """
              .trimIndent(),
          )
          add(
            "src/main/res/values/strings.xml",
            // language=xml
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">Composite App</string>
                <string name="button_send">Go</string>
            </resources>

            """
              .trimIndent(),
          )
          add(
            "src/main/res/layout/main.xml",
            // language=xml
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:id="@+id/main"
                android:orientation="horizontal"
                android:layout_width="fill_parent"
                android:layout_height="fill_parent"
                >
                <Button
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="@string/button_send"
                        android:id="@+id/foo" />
            </LinearLayout>
            """
              .trimIndent(),
          )
        }
      }
      androidLibrary {
        android { namespace = "com.example.android.multiproject.library" }
        files.add(
          "src/main/res/values/strings.xml",
          // language=xml
          """
          <resources>
              <string name="string_from_android_lib_1">androidLib1</string>
              <string name="string_overridden">androidLib1</string>
          </resources>
          """
            .trimIndent(),
        )
      }
    }

  @Test
  fun mergesRawWithLibraryWithOverride() {
    val build =
      project.build {
        androidApplication() {
          /*
           * Set app to depend on library.
           */
          dependencies { api(project(DEFAULT_LIB_PATH)) }
        }
      }
    val lib = build.androidLibrary()
    val app = build.androidApplication()

    build.executor.run(":app:assembleDebug")

    val rDef = lib.resolve(InternalArtifactType.LOCAL_ONLY_SYMBOL_LIST).resolve("debug/parseDebugLocalResources/R-def.txt")

    assertThat(rDef).exists()
    assertThat(rDef).doesNotContain("raw me")

    /*
     * Create raw/me.raw in library and see that it comes out in the apk.
     *
     * It should also show up in build/intermediates/merged_res/debug/raw/me.raw
     */
    val rawMeDotRawRelativePath = "src/main/res/raw/me.raw"
    lib.files.add(rawMeDotRawRelativePath, byteArrayOf(0, 1, 2))

    build.executor.run(":app:assembleDebug")

    assertThat(rDef).exists()
    assertThat(rDef).contains("raw me")

    app.assertApk(ApkSelector.DEBUG) { androidResources().resourceAsBytes("raw/me.raw").isEqualTo(byteArrayOf(0, 1, 2)) }

    val inIntermediate = app.resolve(MERGED_RES).resolve("debug/mergeDebugResources/raw_me.raw.flat").toFile()
    val inCompiledLocalResources =
      lib.resolve(InternalArtifactType.COMPILED_LOCAL_RESOURCES).resolve("debug/compileDebugLibraryResources/out/raw_me.raw.flat")

    assertThat(inIntermediate).doesNotExist()
    assertThat(inCompiledLocalResources).exists()

    /*
     * Create raw/me.raw in application and see that it comes out in the apk, overriding the
     * library's.
     *
     * The change should also show up in build/intermediates/merged_res/debug/raw/me.raw
     */
    app.files.add(rawMeDotRawRelativePath, byteArrayOf(3))

    project.build.executor.run(":app:assembleDebug")

    app.assertApk(ApkSelector.DEBUG) { androidResources().resourceAsBytes("raw/me.raw").isEqualTo(byteArrayOf(3)) }
    assertThat(inIntermediate).exists()

    /*
     * Now, modify the library's and check that nothing changed.
     */
    val apUnderscore = app.getLinkedResourcesFile()

    assertThat(apUnderscore).exists()

    lib.files.update(rawMeDotRawRelativePath).replaceWith(byteArrayOf(0, 1, 2, 4))

    build.executor.run(":app:assembleDebug")

    app.assertApk(ApkSelector.DEBUG) { androidResources().resourceAsBytes("raw/me.raw").isEqualTo(byteArrayOf(3)) }

    assertThat(inIntermediate).wasModifiedAt(inIntermediate.lastModified())
    assertThat(apUnderscore).wasModifiedAt(apUnderscore.getLastModifiedTime())
    // Sometimes fails with the APK being modified even when it shouldn't. b/37617310
    // assertThat(apk).wasModifiedAt(apkModified);
  }

  @Test
  fun removeResourceFile() {
    val build =
      project.build {
        androidApplication {
          /*
           * Add a resource file to the project and build it.
           */
          files.add("src/main/res/raw/me.raw", byteArrayOf(0, 1, 2))
        }
      }

    build.executor.run(":app:assembleDebug")

    val app = build.androidApplication()

    /*
     * Check that the file is merged and in the apk.
     */
    val inIntermediate = app.resolve(MERGED_RES).resolve("debug/mergeDebugResources/raw_me.raw.flat")

    assertThat(inIntermediate).exists()

    val apUnderscore = app.getLinkedResourcesFile()

    assertThat(apUnderscore).exists()
    assertThat(apUnderscore) { it.containsFileWithContent("res/raw/me.raw", byteArrayOf(0, 1, 2)) }

    /*
     * Remove the resource from the project and build the project incrementally.
     */
    app.files.remove("src/main/res/raw/me.raw")
    build.executor.run(":app:assembleDebug")

    /*
     * Check that the file has been removed from the intermediates and from the apk.
     */
    assertThat(inIntermediate).doesNotExist()
    assertThat(apUnderscore) { it.doesNotContain("res/raw/me.raw") }
  }

  @Test
  fun updateResourceFile() {
    val rawRelativePath = "src/main/res/raw/me.raw"

    val build =
      project.build {
        androidApplication {
          /*
           * Add a resource file to the project and build it.
           */
          files.add(rawRelativePath, byteArrayOf(0, 1, 2))
        }
      }
    val app = build.androidApplication()

    build.executor.run(":app:assembleDebug")

    /*
     * Check that the file is merged and in the apk.
     */
    val inIntermediate = app.resolve(MERGED_RES).resolve("debug/mergeDebugResources/raw_me.raw.flat")
    assertThat(inIntermediate).exists()

    val apUnderscore = app.getLinkedResourcesFile()

    assertThat(apUnderscore) {
      it.exists()
      it.containsFileWithContent("res/raw/me.raw", byteArrayOf(0, 1, 2))
    }

    /*
     * Change the resource file from the project and build the project incrementally.
     */
    app.files.update(rawRelativePath).replaceWith(byteArrayOf(1, 2, 3, 4))

    build.executor.run(":app:assembleDebug")

    /*
     * Check that the file has been updated in the intermediates directory and in the project.
     */
    assertThat(inIntermediate).exists()

    assertThat(apUnderscore) { it.containsFileWithContent("res/raw/me.raw", byteArrayOf(1, 2, 3, 4)) }
  }

  // Regression test for b/448768899
  @Test
  fun pickupNavigationXmlForIncremental() {
    val build =
      project.build {
        androidApplication {
          files.add(
            "src/main/res/navigation/nav_graph.xml",
            // language=xml
            """
            <navigation xmlns:android="http://schemas.android.com/apk/res/android">
            </navigation>
            """
              .trimIndent(),
          )
        }
      }
    val app = build.androidApplication()

    build.executor.withArgument("--build-cache").run("clean", ":app:parseDebugLocalResources")

    val rDef = app.resolve(InternalArtifactType.LOCAL_ONLY_SYMBOL_LIST).resolve("debug/parseDebugLocalResources/R-def.txt")

    assertThat(rDef).exists()

    app.files.update("src/main/res/navigation/nav_graph.xml").transform {
      // language=xml
      """
      <navigation xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:app="http://schemas.android.com/apk/res-auto"
          android:id="@+id/nav_graph"
          app:startDestination="@id/firstFragment">

          <fragment
              android:id="@+id/firstFragment"
              android:name="com.example.navigation.FirstFragment"
              android:label="First Fragment">
              <action
                  android:id="@+id/action_firstFragment_self"
                  app:destination="@id/firstFragment" />
          </fragment>
      </navigation>
      """
        .trimIndent()
    }

    build.executor.withArgument("--build-cache").run(":app:parseDebugLocalResources")

    assertThat(rDef).exists()
    assertThat(rDef).contains("action_firstFragment_self")
  }

  // Regression test for b/209574833
  @Test
  fun addResourceBetweenBuildsWithProductFlavor() {
    val build =
      project.build {
        androidApplication(":app") {
          android {
            flavorDimensions += listOf("foo")
            productFlavors { create("flavor1") { it.dimension = "foo" } }
          }
          files {
            add(
              "src/flavor1/res/values/strings.xml",
              // language=xml
              """<resources>
                                    <string name="foo_string">flavor1</string>
                                   </resources>
                                   """,
            )
          }
        }
      }
    val app = build.androidApplication()

    build.executor.run("clean", ":app:assembleDebug")
    app.files.add(
      "src/main/res/layout/additional.xml",
      // language=xml
      """<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            android:layout_width="fill_parent"
                            android:layout_height="fill_parent"
                            android:orientation="vertical" >
                        </LinearLayout>""",
    )
    build.executor.run(":app:assembleDebug")

    // Verify the resource can be moved to a different source set.
    app.files.update("src/main/res/layout/additional.xml").moveTo("src/flavor1/res/layout/additional.xml")

    build.executor.run(":app:assembleDebug")
    app.assertApk(ApkSelector.DEBUG.withFlavor("flavor1")) {
      androidResources().containsExactly("layout/main.xml", "layout/additional.xml")
    }
  }

  @Test
  fun replaceResourceFileWithDifferentExtension() {
    val build =
      project.build {
        androidApplication {
          /*
           * Add a resource file to the project and build it.
           */
          files.add("src/main/res/raw/me.raw", byteArrayOf(0, 1, 2))
        }
      }
    val app = build.androidApplication()

    build.executor.run(":app:assembleDebug")

    /*
     * Check that the file is merged and in the apk.
     */
    val inIntermediate = app.resolve(MERGED_RES).resolve("debug/mergeDebugResources/raw_me.raw.flat")
    assertThat(inIntermediate).exists()

    val apUnderscore = app.getLinkedResourcesFile()

    assertThat(apUnderscore).exists()
    assertThat(apUnderscore) { it.containsFileWithContent("res/raw/me.raw", byteArrayOf(0, 1, 2)) }

    /*
     * Change the resource file with one with a different extension and build the project
     * incrementally.
     */
    app.files.remove("src/main/res/raw/me.raw")
    app.files.add("src/main/res/raw/me.war", byteArrayOf(1, 2, 3, 4))
    build.executor.run(":app:assembleDebug")

    /*
     * Check that the file has been updated in the intermediates directory and in the project.
     */
    assertThat(inIntermediate).doesNotExist()
    assertThat(inIntermediate.parent.resolve("raw_me.war.flat")).exists()
    assertThat(apUnderscore).doesNotContain("res/raw/me.raw")
    assertThat(apUnderscore) { it.containsFileWithContent("res/raw/me.war", byteArrayOf(1, 2, 3, 4)) }
  }

  @Test
  fun injectedMinSdk() {
    val build =
      project.build {
        androidApplication {
          files {
            add(
              "src/main/res/layout-v23/main.xml",
              // language=xml
              """
              <?xml version="1.0" encoding="utf-8"?>
                                      <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                                          android:orientation="horizontal"
                                          android:layout_width="fill_parent"
                                          android:layout_height="fill_parent">
                                      </LinearLayout>
              """
                .trimIndent(),
            )
            update("src/main/java/com/example/android/multiproject/app/MainActivity.java")
              .appendMethod("public int useFoo() { return R.id.foo; }")
          }
        }
      }
    build.executor.with(IntegerOption.IDE_TARGET_DEVICE_API, 23).run(":app:assembleDebug")
  }

  @Test
  fun mergeResourceOmitsNavigationXml() {
    val build =
      project.build {
        androidApplication {
          files.add(
            "src/main/res/navigation/nav_graph.xml",
            // language=xml
            """
            <navigation xmlns:app="http://schemas.android.com/apk/res-auto">
            </navigation>
            """
              .trimIndent(),
          )
        }
      }

    val app = build.androidApplication()

    build.executor.run(":app:mergeDebugResources")

    val resourcesFolder = app.resolve(MERGED_RES).resolve("debug/mergeDebugResources")
    assertThat(resourcesFolder.resolve("navigation_nav_graph.xml.flat")).doesNotExist()
    assertThat(resourcesFolder.resolve("layout_main.xml.flat")).exists()
  }

  // Regression test for http://issuetracker.google.com/65829618
  @Test
  fun testIncrementalBuildWithShrinkResources() {
    val build =
      project.build {
        androidApplication {
          android {
            buildTypes {
              named("debug") {
                it.isMinifyEnabled = true
                it.isShrinkResources = true
              }
            }
          }
          dependencies { implementation("androidx.appcompat:appcompat:1.6.1") }
        }
      }
    val app = build.androidApplication()

    // Run a full build with shrinkResources enabled
    build.executor.run(":app:clean", ":app:assembleDebug").apply { assertTask(":app:mergeDebugResources").didWork() }
    val apkSizeWithShrinkResources = ApkContentSize.computeContent(app.getApkLocationForCopy(ApkSelector.DEBUG))

    // Run an incremental build with shrinkResources disabled, the MergeResources task should
    // not be UP-TO-DATE and the apk size should be larger
    app.reconfigure { android { android { buildTypes { named("debug") { it.isShrinkResources = false } } } } }
    build.executor.run(":app:assembleDebug").apply { assertTask(":app:mergeDebugResources").didWork() }
    val apkSizeWithoutShrinkResources = ApkContentSize.computeContent(app.getApkLocationForCopy(ApkSelector.DEBUG))
    assertThat(apkSizeWithoutShrinkResources).isGreaterThan(apkSizeWithShrinkResources)

    // Run an incremental build again with shrinkResources enabled, the MergeResources task
    // again should not be UP-TO-DATE and the apk size must be exactly the same as the first
    app.reconfigure { android { android { buildTypes { named("debug") { it.isShrinkResources = true } } } } }
    build.executor.run(":app:assembleDebug").apply { assertTask(":app:mergeDebugResources").didWork() }
    val sameApkSizeShrinkResources = ApkContentSize.computeContent(app.getApkLocationForCopy(ApkSelector.DEBUG))
    assertThat(sameApkSizeShrinkResources).isEqualTo(apkSizeWithShrinkResources)
  }

  @Test
  fun checkSmallMergeInApp() {
    val build =
      project.build {
        androidApplication { dependencies { api(project(DEFAULT_LIB_PATH)) } }
        androidLibrary {
          files {
            add("src/main/res/values/lib_values.xml", "<resources><string name=\"my_library_string\">lib string</string></resources>")
          }
        }
      }
    val app = build.androidApplication()

    build.executor.run("clean", ":app:assembleDebug")

    val incrementalMergedValues = app.intermediatesDir.resolve("incremental/debug/mergeDebugResources/merged.dir/values/values.xml")

    val smallMerge = app.resolve(InternalArtifactType.PACKAGED_RES).resolve("debug/packageDebugResources/values/values.xml").toFile()

    assertThat(incrementalMergedValues).contains("my_library_string")

    // had to use this as `executor.with()` won't work if the property is already
    // defined in the gradle.properties file.
    build.reconfigureGradleProperties { add(BooleanOption.ENABLE_APP_COMPILE_TIME_R_CLASS, true) }

    build.executor.run("clean", ":app:generateDebugRFile")

    assertThat(incrementalMergedValues).doesNotExist()
    assertThat(smallMerge).doesNotContain("my_library_string")
  }

  @Test
  fun testVectorDrawablesWithVersionQualifiersRenderCorrectly() {
    val build =
      project.build {
        androidApplication {
          android { defaultConfig { minSdk = 19 } }
          files {
            add("src/main/res/drawable/icon.xml", "<vector>a</vector>")
            add("src/main/res/drawable-v24/icon.xml", "<vector>b</vector>")
            add("src/main/res/drawable-v28/icon.xml", "<vector>c</vector>")
          }
        }
      }

    val generatedPngs = build.androidApplication().generatedDir.resolve("res/pngs/debug")

    build.executor.run(":app:processDebugResources")

    assertThat(generatedPngs.resolve("drawable-anydpi-v21/icon.xml").readLines()).containsExactlyElementsIn(listOf("<vector>a</vector>"))

    assertThat(generatedPngs.resolve("drawable-anydpi-v24/icon.xml").readLines()).containsExactlyElementsIn(listOf("<vector>b</vector>"))

    assertThat(generatedPngs.resolve("drawable-anydpi-v28/icon.xml").readLines()).containsExactlyElementsIn(listOf("<vector>c</vector>"))
  }

  // Regression test for b/206674992
  @Test
  fun testIncrementalResourceChangeOfMergedNotCompiledResource() {
    // Resource shrinker is required to generate the mergedNotCompiled resource directory.
    val build =
      project.build {
        androidApplication {
          android {
            buildTypes {
              named("release") {
                it.isMinifyEnabled = true
                it.isShrinkResources = true
              }
            }
          }
          files { add("src/main/res/layout/no_compile.xml", "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + "<merge/>") }
        }
      }

    build.executor.run(":app:mergeReleaseResources")

    build.androidApplication().files.update("src/main/res/layout/no_compile.xml").append("<!-- Comment causing incremental run. -->")

    build.executor.run(":app:mergeReleaseResources")
  }

  // Regression test b/387371071
  @Test
  fun testSameNamedStringAndIdAppearInRClass() {
    val build =
      project.build {
        androidApplication {
          android { namespace = "com.example.android.multiproject" }
          dependencies { api(project(DEFAULT_LIB_PATH)) }
          files
            .update("src/main/java/com/example/android/multiproject/app/MainActivity.java")
            .searchAndReplace("package com.example.android.multiproject.app;", "package com.example.android.multiproject;")
            .moveTo("src/main/java/com/example/android/multiproject/MainActivity.java")
        }
        androidLibrary {
          android { namespace = "com.example.android.multiproject" }
          // A string resource called `app_name` is already present in the project.
          files.add(
            "src/main/res/values/ids.xml",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
              "<resources>\n" +
              "    <item name=\"app_name\" type=\"id\" />\n" +
              "</resources>",
          )
        }
      }

    build.executor.expectFailure().run(":app:processDebugMainManifest").apply {
      assertErrorContains(
        "Namespace 'com.example.android.multiproject' is used in multiple modules and/or " +
          "libraries: AndroidManifest.xml, :lib. Please ensure that all modules and " +
          "libraries have a unique namespace."
      )
    }
  }

  private fun AndroidApplicationProject.getLinkedResourcesFile(): Path =
    resolve(InternalArtifactType.LINKED_RESOURCES_BINARY_FORMAT)
      .resolve("debug/processDebugResources/linked-resources-binary-format-debug.ap_")
}
