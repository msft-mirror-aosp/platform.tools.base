/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.build.gradle.integration.testing.suites

import com.android.Version
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.apk.Zip
import com.android.testutils.generateAarWithContent
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.readLines
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

/** A library that only the host jar sources of the `androidResSuite` test suite of the application depend on. */
private const val SUITE_ONLY_LIBRARY = "com.example.suiteonly:suiteonly:1.0"

/** A library that the application depends on in version 1.0, and that the `androidResSuite` test suite depends on in version 2.0. */
private const val SHARED_LIBRARY = "com.example.shared:shared"

/** Returns a layout showing the given string resource in the given color resource. */
private fun textViewLayout(string: String, color: String): ByteArray =
  """
  <TextView xmlns:android="http://schemas.android.com/apk/res/android"
      android:layout_width="wrap_content"
      android:layout_height="wrap_content"
      android:text="@string/$string"
      android:textColor="@color/$color" />
  """
    .trimIndent()
    .toByteArray()

/**
 * The AAR of [SUITE_ONLY_LIBRARY]. Its layout, precompiled, references its own values, so linking it fails if these values are not merged.
 */
private val suiteOnlyAar: ByteArray =
  generateAarWithContent(
    packageName = "com.example.suiteonly",
    resources =
      mapOf(
        "values/values.xml" to
          """
          <resources>
              <string name="suite_only_string">Suite only</string>
              <color name="suite_only_color">#FF0000</color>
              <declare-styleable name="SuiteOnlyView">
                  <attr name="suiteOnlyAttr" format="string" />
              </declare-styleable>
          </resources>
          """
            .trimIndent()
            .toByteArray(),
        "layout/suite_only_layout.xml" to textViewLayout("suite_only_string", "suite_only_color"),
      ),
    extraFiles =
      mapOf(
        "R.txt" to
          """
          int attr suiteOnlyAttr 0x7f010001
          int color suite_only_color 0x7f020001
          int layout suite_only_layout 0x7f030001
          int string suite_only_string 0x7f040001
          int[] styleable SuiteOnlyView { 0x7f010001 }
          int styleable SuiteOnlyView_suiteOnlyAttr 0
          """
            .trimIndent()
            .toByteArray(),
        "assets/suite_only_asset.txt" to "Suite only".toByteArray(),
      ),
  )

/** The AAR of version 1.0 of [SHARED_LIBRARY]. */
private val sharedAarV1: ByteArray =
  generateAarWithContent(
    packageName = "com.example.shared",
    resources = mapOf("values/values.xml" to """<resources><string name="shared_string">Shared 1.0</string></resources>""".toByteArray()),
    extraFiles = mapOf("R.txt" to "int string shared_string 0x7f040001".toByteArray()),
  )

/**
 * The AAR of version 2.0 of [SHARED_LIBRARY]. Its layout, precompiled, references a value that version 1.0 does not have, so linking it
 * fails if the values of version 1.0 are merged instead.
 */
private val sharedAarV2: ByteArray =
  generateAarWithContent(
    packageName = "com.example.shared",
    resources =
      mapOf(
        "values/values.xml" to
          """
          <resources>
              <string name="shared_string">Shared 2.0</string>
              <color name="shared_color">#00FF00</color>
          </resources>
          """
            .trimIndent()
            .toByteArray(),
        "layout/shared_layout.xml" to textViewLayout("shared_string", "shared_color"),
      ),
    extraFiles =
      mapOf(
        "R.txt" to
          """
          int color shared_color 0x7f020001
          int layout shared_layout 0x7f030001
          int string shared_string 0x7f040001
          """
            .trimIndent()
            .toByteArray(),
        "assets/shared_asset.txt" to "Shared 2.0".toByteArray(),
      ),
  )

/**
 * Integration test verifying that Android Resources are accessible, successfully compiled, and packaged into apk-for-local-test.ap_ within
 * HostJar Test Suites for both application and library projects.
 */
class HostJarTestSuiteAndroidResourcesTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("com.google.guava:guava:31.1-jre")
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("org.junit.platform:junit-platform-commons:1.13.3")
        jar("org.opentest4j:opentest4j:1.3.0")
        jar("org.apiguardian:apiguardian-api:1.1.2")
        jar("org.jetbrains.kotlin:kotlin-stdlib:1.8.20")
        jar("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
          .addClasses(VerifyingJunitEngine::class.java, VerifyingTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", VerifyingJunitEngine::class.java.name)
        library(MavenRepoGenerator.Library(SUITE_ONLY_LIBRARY, "aar", suiteOnlyAar))
        library(MavenRepoGenerator.Library("$SHARED_LIBRARY:1.0", "aar", sharedAarV1))
        library(MavenRepoGenerator.Library("$SHARED_LIBRARY:2.0", "aar", sharedAarV2))
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication(":app") {
          android {
            namespace = "com.example.test"

            // Android Resources Suite
            testOptions.customSuites.create("androidResSuite") {
              it.useJunitEngine.apply {
                inputs.add(com.android.build.api.dsl.AgpTestSuiteInputParameters.RESOURCES_AP_ARCHIVE)
                includeEngines.add("verifying-junit-engine")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-commons:1.13.3")
                enginesDependencies.add("org.opentest4j:opentest4j:1.3.0")
                enginesDependencies.add("org.apiguardian:apiguardian-api:1.1.2")
                enginesDependencies.add("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
                addInputProperty("com.android.junit.engine.expected.classes", "some.androidres.TestClass")
              }
              it.hostJar { enableAndroidResources = true }
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          files {
            add("src/main/assets/app_asset.txt", "App asset")
            add(
              "src/main/res/values/strings.xml",
              """
              <resources>
                  <string name="app_name">My Application</string>
              </resources>
              """
                .trimIndent(),
            )
            add(
              "src/androidResSuite/java/some/androidres/TestClass.java",
              """
              package some.androidres;
              public class TestClass {
                public void testResourceAccess() {
                   int stringRes = com.example.test.R.string.app_name;
                }
              }
              """
                .trimIndent(),
            )
          }
        }

        androidLibrary(":lib") {
          android {
            namespace = "com.example.lib"

            // Android Resources Suite for Library
            testOptions.customSuites.create("libAndroidResSuite") {
              it.useJunitEngine.apply {
                inputs.add(com.android.build.api.dsl.AgpTestSuiteInputParameters.RESOURCES_AP_ARCHIVE)
                includeEngines.add("verifying-junit-engine")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-commons:1.13.3")
                enginesDependencies.add("org.opentest4j:opentest4j:1.3.0")
                enginesDependencies.add("org.apiguardian:apiguardian-api:1.1.2")
                enginesDependencies.add("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
                addInputProperty("com.android.junit.engine.expected.classes", "some.lib.androidres.LibTestClass")
              }
              it.hostJar { enableAndroidResources = true }
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          files {
            add("src/main/assets/lib_asset.txt", "Lib asset")
            add(
              "src/main/res/values/strings.xml",
              """
              <resources>
                  <string name="lib_name">My Library</string>
              </resources>
              """
                .trimIndent(),
            )
            add(
              "src/libAndroidResSuite/java/some/lib/androidres/LibTestClass.java",
              """
              package some.lib.androidres;
              public class LibTestClass {
                public void testLibResourceAccess() {
                   int stringRes = com.example.lib.R.string.lib_name;
                }
              }
              """
                .trimIndent(),
            )
          }
        }
      }

  @Test
  fun testAndroidResourcesCompilationAndPackaging() {
    rule.build.executor.run(":app:testDebugAndroidResSuiteT1TestSuite")

    val intermediatesDir = rule.build.androidApplication(":app").intermediatesDir

    val apkForLocalTest =
      intermediatesDir.resolve("apk_for_local_test").toFile().walkTopDown().firstOrNull { it.name == "apk-for-local-test.ap_" }

    assertThat(apkForLocalTest).isNotNull()

    Zip(apkForLocalTest!!).use { zip ->
      assertThat(zip.entries.map { it.toString() }).containsAtLeast("/resources.arsc", "/assets/app_asset.txt")
    }
  }

  @Test
  fun testLibraryAndroidResourcesCompilationAndPackaging() {
    rule.build.executor.run(":lib:testDebugLibAndroidResSuiteT1TestSuite")

    val intermediatesDir = rule.build.androidLibrary(":lib").intermediatesDir

    val apkForLocalTest =
      intermediatesDir.resolve("apk_for_local_test").toFile().walkTopDown().firstOrNull { it.name == "apk-for-local-test.ap_" }

    assertThat(apkForLocalTest).isNotNull()

    Zip(apkForLocalTest!!).use { zip ->
      assertThat(zip.entries.map { it.toString() }).containsAtLeast("/resources.arsc", "/assets/lib_asset.txt")
    }
  }

  @Test
  fun testAssembleWithAndroidResources() {
    val result = rule.build.executor.run(":app:assembleDebugAndroidResSuite")

    assertThat(result.didWorkTasks).contains(":app:packageDebugForAndroidResSuite")
    assertThat(result.didWorkTasks).contains(":app:generateDebugAndroidResSuiteConfig")
    assertThat(result.didWorkTasks).contains(":app:compileDebugAndroidResSuiteJavaWithJavac")
    assertThat(result.getTask(":app:assembleDebugAndroidResSuite")).isNotNull()

    val intermediatesDir = rule.build.androidApplication(":app").intermediatesDir
    val apkForLocalTest =
      intermediatesDir.resolve("apk_for_local_test").toFile().walkTopDown().firstOrNull { it.name == "apk-for-local-test.ap_" }
    assertThat(apkForLocalTest).isNotNull()
  }

  @Test
  fun testResourcesOfTestSuiteDependencies() {
    val build = rule.build {
      androidApplication(":app") {
        dependencies { implementation("$SHARED_LIBRARY:1.0") }
        android {
          testOptions.customSuites.named("androidResSuite") {
            it.useJunitEngine.addInputProperty(
              "com.android.junit.engine.expected.classes",
              "some.androidres.TestClass,com.example.test.R\$string,com.example.suiteonly.R\$styleable,com.example.shared.R\$color",
            )
            it.hostJar {
              dependencies.apply {
                implementation.add(SUITE_ONLY_LIBRARY)
                implementation.add("$SHARED_LIBRARY:2.0")
              }
            }
          }
        }
        files {
          add(
            "src/debug/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <meta-data android:name="com.example.suite.MANIFEST_MARKER" android:value="from_app_debug_manifest" />
                </application>
            </manifest>
            """
              .trimIndent(),
          )
          add(
            "src/androidResSuite/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools">
                <application>
                    <meta-data
                        android:name="com.example.suite.MANIFEST_MARKER"
                        android:value="from_app_suite_manifest"
                        tools:replace="android:value" />
                </application>
            </manifest>
            """
              .trimIndent(),
          )
          update("src/androidResSuite/java/some/androidres/TestClass.java")
            .searchAndReplace(
              "int stringRes = com.example.test.R.string.app_name;",
              """
              int stringRes = com.example.test.R.string.app_name;
              int suiteOnlyStringRes = com.example.suiteonly.R.string.suite_only_string;
              int sharedColorRes = com.example.shared.R.color.shared_color;
              """
                .trimIndent(),
            )
        }
      }
      androidLibrary(":lib") {
        dependencies { implementation("$SHARED_LIBRARY:1.0") }
        android {
          testOptions.targetSdk = 33
          testOptions.customSuites.named("libAndroidResSuite") {
            it.useJunitEngine.addInputProperty(
              "com.android.junit.engine.expected.classes",
              "some.lib.androidres.LibTestClass,com.example.lib.R\$string,com.example.suiteonly.R\$styleable,com.example.shared.R\$color",
            )
            it.hostJar {
              dependencies.apply {
                implementation.add(SUITE_ONLY_LIBRARY)
                implementation.add("$SHARED_LIBRARY:2.0")
              }
            }
          }
        }
        files {
          add(
            "src/debug/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <meta-data android:name="com.example.lib.suite.MANIFEST_MARKER" android:value="from_lib_debug_manifest" />
                </application>
            </manifest>
            """
              .trimIndent(),
          )
          add(
            "src/libAndroidResSuite/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools">
                <application>
                    <meta-data
                        android:name="com.example.lib.suite.MANIFEST_MARKER"
                        android:value="from_lib_suite_manifest"
                        tools:replace="android:value" />
                </application>
            </manifest>
            """
              .trimIndent(),
          )
          update("src/libAndroidResSuite/java/some/lib/androidres/LibTestClass.java")
            .searchAndReplace(
              "int stringRes = com.example.lib.R.string.lib_name;",
              """
              int stringRes = com.example.lib.R.string.lib_name;
              int suiteOnlyStringRes = com.example.suiteonly.R.string.suite_only_string;
              int sharedColorRes = com.example.shared.R.color.shared_color;
              """
                .trimIndent(),
            )
        }
      }
    }

    // Linking the resources of the test suite fails unless the values of the dependencies that it resolves are merged, as the precompiled
    // layouts of these dependencies reference them.
    build.executor.run(":app:testDebugAndroidResSuiteT1TestSuite", ":lib:testDebugLibAndroidResSuiteT1TestSuite")

    val intermediatesDir = build.androidApplication(":app").intermediatesDir

    val appSuiteManifest =
      intermediatesDir.resolve("packaged_manifests/debugAndroidResSuite/processDebugAndroidResSuiteManifest/AndroidManifest.xml")
    assertWithMessage("$appSuiteManifest exists").that(appSuiteManifest.exists()).isTrue()
    val appSuiteManifestText = appSuiteManifest.toFile().readText()
    assertThat(appSuiteManifestText).contains("from_app_suite_manifest")
    assertThat(appSuiteManifestText).doesNotContain("from_app_debug_manifest")

    // The resources passed to the test engine hold the resources of the dependencies that only the test suite has, or has in a newer
    // version.
    val apkForLocalTest =
      intermediatesDir.resolve("apk_for_local_test").toFile().walkTopDown().single {
        it.name == "apk-for-local-test.ap_" && it.parentFile.name == "packageDebugForAndroidResSuite"
      }
    Zip(apkForLocalTest).use { zip ->
      assertThat(zip.entries.map { it.toString() })
        .containsAtLeast(
          "/res/layout/suite_only_layout.xml",
          "/res/layout/shared_layout.xml",
          "/assets/app_asset.txt",
          "/assets/suite_only_asset.txt",
          "/assets/shared_asset.txt",
        )
    }

    val appResourceIds = readResourceIds(intermediatesDir.resolve("runtime_symbol_list/debug/processDebugResources/R.txt"))
    val testSuiteResourceIds =
      readResourceIds(intermediatesDir.resolve("runtime_symbol_list/debugAndroidResSuite/processDebugAndroidResSuiteResources/R.txt"))

    // The resources of the dependencies that only the test suite has, or has in a newer version, are only linked for the test suite.
    val testSuiteOnlyResources =
      listOf(
        "attr suiteOnlyAttr",
        "color suite_only_color",
        "layout suite_only_layout",
        "string suite_only_string",
        "styleable SuiteOnlyView",
        "color shared_color",
        "layout shared_layout",
      )
    assertThat(testSuiteResourceIds.keys).containsAtLeastElementsIn(testSuiteOnlyResources)
    assertThat(appResourceIds.keys).containsNoneIn(testSuiteOnlyResources)

    // The ids of the resources of the tested application are kept, as its classes may have inlined them, even though the resource types
    // that only the test suite has come first.
    for (resource in listOf("string app_name", "string shared_string")) {
      assertWithMessage(resource).that(appResourceIds[resource]).isNotNull()
      assertWithMessage(resource).that(testSuiteResourceIds[resource]).isEqualTo(appResourceIds[resource])
    }

    val testSuiteRClassJar =
      intermediatesDir.resolve("compile_and_runtime_r_class_jar/debugAndroidResSuite/processDebugAndroidResSuiteResources/R.jar")
    assertWithMessage("$testSuiteRClassJar exists").that(testSuiteRClassJar.exists()).isTrue()
    Zip(testSuiteRClassJar).use { zip ->
      for (rClass in listOf("com/example/suiteonly/R\$styleable.class", "com/example/shared/R\$color.class")) {
        assertWithMessage(rClass).that(zip.getEntry(rClass)).isNotNull()
      }
      assertThat(zip.getEntry("com/example/test/R\$styleable.class")).isNull()
    }

    val libIntermediatesDir = build.androidLibrary(":lib").intermediatesDir
    val libSuiteManifest =
      libIntermediatesDir.resolve("packaged_manifests/debugLibAndroidResSuite/processDebugLibAndroidResSuiteManifest/AndroidManifest.xml")
    assertWithMessage("$libSuiteManifest exists").that(libSuiteManifest.exists()).isTrue()
    val libSuiteManifestText = libSuiteManifest.toFile().readText()
    assertThat(libSuiteManifestText).contains("from_lib_suite_manifest")
    assertThat(libSuiteManifestText).doesNotContain("from_lib_debug_manifest")
    assertThat(libSuiteManifestText).contains("android:targetSdkVersion=\"33\"")

    val libApkForLocalTest =
      libIntermediatesDir.resolve("apk_for_local_test").toFile().walkTopDown().single {
        it.name == "apk-for-local-test.ap_" && it.parentFile.name == "packageDebugLibAndroidResSuiteForUnitTest"
      }
    Zip(libApkForLocalTest).use { zip ->
      assertThat(zip.entries.map { it.toString() })
        .containsAtLeast(
          "/res/layout/suite_only_layout.xml",
          "/res/layout/shared_layout.xml",
          "/assets/lib_asset.txt",
          "/assets/suite_only_asset.txt",
          "/assets/shared_asset.txt",
        )
    }

    val libTestSuiteResourceIds =
      readResourceIds(
        libIntermediatesDir.resolve("runtime_symbol_list/debugLibAndroidResSuite/processDebugLibAndroidResSuiteResources/R.txt")
      )
    assertThat(libTestSuiteResourceIds.keys)
      .containsAtLeastElementsIn(testSuiteOnlyResources + listOf("string lib_name", "string shared_string"))

    val libTestSuiteRClassJar =
      libIntermediatesDir.resolve("compile_and_runtime_r_class_jar/debugLibAndroidResSuite/processDebugLibAndroidResSuiteResources/R.jar")
    assertWithMessage("$libTestSuiteRClassJar exists").that(libTestSuiteRClassJar.exists()).isTrue()
    Zip(libTestSuiteRClassJar).use { zip ->
      for (rClass in
        listOf("com/example/lib/R\$string.class", "com/example/suiteonly/R\$styleable.class", "com/example/shared/R\$color.class")) {
        assertWithMessage(rClass).that(zip.getEntry(rClass)).isNotNull()
      }
      assertThat(zip.getEntry("com/example/lib/R\$styleable.class")).isNull()
    }
  }

  @Test
  fun testCompileAgainstSuiteLinkedRClass() {
    val build = rule.build {
      gradleProperties { add(BooleanOption.NON_TRANSITIVE_R_CLASS, false) }
      androidApplication(":app") {
        dependencies { implementation("$SHARED_LIBRARY:1.0") }
        android {
          testOptions.customSuites.named("androidResSuite") {
            it.useJunitEngine.addInputProperty("com.android.junit.engine.invoked.methods", "some.androidres.TestClass#checkLinkedRClass")
            it.hostJar {
              dependencies.apply {
                implementation.add(SUITE_ONLY_LIBRARY)
                implementation.add("$SHARED_LIBRARY:2.0")
              }
            }
          }
        }
        files {
          update("src/androidResSuite/java/some/androidres/TestClass.java")
            .searchAndReplace(
              "int stringRes = com.example.test.R.string.app_name;",
              """
              int stringRes = com.example.test.R.string.app_name;
              }
              public static void checkLinkedRClass() {
                if (com.example.test.R.string.app_name == 0) throw new IllegalStateException("app_name is 0");
                if (com.example.test.R.string.suite_only_string == 0) throw new IllegalStateException("suite_only_string is 0");
                if (com.example.test.R.color.shared_color == 0) throw new IllegalStateException("shared_color is 0");
              """
                .trimIndent(),
            )
        }
      }
      androidLibrary(":lib") {
        dependencies { implementation("$SHARED_LIBRARY:1.0") }
        android {
          testOptions.customSuites.named("libAndroidResSuite") {
            it.useJunitEngine.addInputProperty(
              "com.android.junit.engine.invoked.methods",
              "some.lib.androidres.LibTestClass#checkLinkedRClass",
            )
            it.hostJar {
              dependencies.apply {
                implementation.add(SUITE_ONLY_LIBRARY)
                implementation.add("$SHARED_LIBRARY:2.0")
              }
            }
          }
        }
        files {
          update("src/libAndroidResSuite/java/some/lib/androidres/LibTestClass.java")
            .searchAndReplace(
              "int stringRes = com.example.lib.R.string.lib_name;",
              """
              int stringRes = com.example.lib.R.string.lib_name;
              }
              public static void checkLinkedRClass() {
                if (com.example.lib.R.string.lib_name == 0) throw new IllegalStateException("lib_name is 0");
                if (com.example.lib.R.string.suite_only_string == 0) throw new IllegalStateException("suite_only_string is 0");
                if (com.example.lib.R.color.shared_color == 0) throw new IllegalStateException("shared_color is 0");
              """
                .trimIndent(),
            )
        }
      }
    }

    build.executor.run(":app:testDebugAndroidResSuiteT1TestSuite", ":lib:testDebugLibAndroidResSuiteT1TestSuite")
  }

  @Test
  fun testTestedLibraryMergedManifestTransformPropagatesToSuite() {
    val build = rule.build {
      androidLibrary(":lib") {
        pluginCallbacks += TransformLibraryMergedManifestCallback::class.java
        files {
          add(
            "src/main/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <meta-data android:name="com.example.lib.suite.MANIFEST_MARKER" android:value="raw_lib_manifest" />
                </application>
            </manifest>
            """
              .trimIndent(),
          )
        }
      }
    }

    build.executor.run(":lib:processDebugLibAndroidResSuiteManifest")

    val libSuiteManifest =
      build
        .androidLibrary(":lib")
        .intermediatesDir
        .resolve("packaged_manifests/debugLibAndroidResSuite/processDebugLibAndroidResSuiteManifest/AndroidManifest.xml")
    val libSuiteManifestText = libSuiteManifest.toFile().readText()
    assertThat(libSuiteManifestText).contains("transformed_lib_manifest")
    assertThat(libSuiteManifestText).doesNotContain("raw_lib_manifest")
  }

  @Test
  fun testSuiteManifestAddedAndRemovedReusesConfigurationCache() {
    val build = rule.build {
      androidLibrary(":lib") {
        files {
          add(
            "src/debug/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <meta-data android:name="com.example.lib.suite.MANIFEST_MARKER" android:value="from_lib_debug_manifest" />
                </application>
            </manifest>
            """
              .trimIndent(),
          )
        }
      }
    }

    val lib = build.androidLibrary(":lib")
    val libSuiteManifest =
      lib.intermediatesDir.resolve("packaged_manifests/debugLibAndroidResSuite/processDebugLibAndroidResSuiteManifest/AndroidManifest.xml")

    build.executor.run(":lib:processDebugLibAndroidResSuiteManifest")
    assertThat(libSuiteManifest.toFile().readText()).contains("from_lib_debug_manifest")

    lib.files.add(
      "src/libAndroidResSuite/AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">
          <application>
              <meta-data
                  android:name="com.example.lib.suite.MANIFEST_MARKER"
                  android:value="from_lib_suite_manifest"
                  tools:replace="android:value" />
          </application>
      </manifest>
      """
        .trimIndent(),
    )

    val resultAfterAdd = build.executor.run(":lib:processDebugLibAndroidResSuiteManifest")
    resultAfterAdd.assertConfigurationCacheHit()
    val manifestWithSuiteOverlay = libSuiteManifest.toFile().readText()
    assertThat(manifestWithSuiteOverlay).contains("from_lib_suite_manifest")
    assertThat(manifestWithSuiteOverlay).doesNotContain("from_lib_debug_manifest")

    lib.files.remove("src/libAndroidResSuite/AndroidManifest.xml")

    val resultAfterRemove = build.executor.run(":lib:processDebugLibAndroidResSuiteManifest")
    resultAfterRemove.assertConfigurationCacheHit()
    val manifestAfterRemoval = libSuiteManifest.toFile().readText()
    assertThat(manifestAfterRemoval).contains("from_lib_debug_manifest")
    assertThat(manifestAfterRemoval).doesNotContain("from_lib_suite_manifest")
  }

  @Test
  fun testDynamicFeatureAndroidResourcesSuite() {
    val build = rule.build {
      androidApplication(":app") { android { dynamicFeatures.add(":feature") } }
      androidFeature(":feature") {
        dependencies { implementation(project(":app")) }
        android {
          namespace = "com.example.feature"
          testOptions.customSuites.create("featureResSuite") {
            it.useVerifyingJunitEngine()
            it.useJunitEngine.inputs.add(com.android.build.api.dsl.AgpTestSuiteInputParameters.RESOURCES_AP_ARCHIVE)
            it.useJunitEngine.addInputProperty("com.android.junit.engine.expected.classes", "some.feature.FeatureSuiteTest")
            it.useJunitEngine.addInputProperty(
              "com.android.junit.engine.expected.resources",
              "com/android/tools/test_config.properties",
            )
            it.hostJar { enableAndroidResources = true }
            it.targetVariants.add("debug")
            it.targets.apply { create("t1") {} }
          }
        }
        files {
          add("src/main/assets/feature_asset.txt", "Feature asset")
          add(
            "src/featureResSuite/AndroidManifest.xml",
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <meta-data android:name="com.example.feature.SUITE_MARKER" android:value="from_feature_suite_manifest" />
                </application>
            </manifest>
            """
              .trimIndent(),
          )
          add("src/featureResSuite/java/some/feature/FeatureSuiteTest.java", "package some.feature; public class FeatureSuiteTest {}")
        }
      }
    }

    val result = build.executor.run(":feature:assembleDebugFeatureResSuite", ":feature:testDebugFeatureResSuiteT1TestSuite")
    assertThat(result.didWorkTasks).contains(":feature:generateDebugFeatureResSuiteConfig")
    assertThat(result.didWorkTasks).contains(":feature:packageDebugForFeatureResSuite")

    val featureSuiteManifest =
      build
        .androidFeature(":feature")
        .intermediatesDir
        .resolve("packaged_manifests/debugFeatureResSuite/processDebugFeatureResSuiteManifest/AndroidManifest.xml")
    assertThat(featureSuiteManifest.toFile().readText()).contains("from_feature_suite_manifest")
  }

  @Test
  fun testNoCompressInSuiteResourceApk() {
    // Large enough for the compressor to keep the deflated data.
    val assetContent = "Compressible host-jar test suite asset content. ".repeat(64)
    val build = rule.build {
      androidApplication(":app") {
        android {
          androidResources { noCompress += "nocompress" }
          dynamicFeatures.add(":feature")
        }
        files {
          add("src/main/assets/stored_asset.nocompress", assetContent)
          add("src/main/assets/deflated_asset.txt", assetContent)
        }
      }
      androidLibrary(":lib") {
        android { androidResources { noCompress += "nocompress" } }
        files {
          add("src/main/assets/stored_asset.nocompress", assetContent)
          add("src/main/assets/deflated_asset.txt", assetContent)
        }
      }
      androidFeature(":feature") {
        dependencies { implementation(project(":app")) }
        android {
          namespace = "com.example.feature"
          androidResources { noCompress += "nocompress" }
          testOptions.customSuites.create("featureResSuite") {
            it.useVerifyingJunitEngine()
            it.hostJar { enableAndroidResources = true }
            it.targetVariants.add("debug")
            it.targets.apply { create("t1") {} }
          }
        }
        files {
          add("src/main/assets/stored_asset.nocompress", assetContent)
          add("src/main/assets/deflated_asset.txt", assetContent)
        }
      }
    }

    build.executor.run(
      ":app:packageDebugForAndroidResSuite",
      ":lib:packageDebugLibAndroidResSuiteForUnitTest",
      ":feature:packageDebugForFeatureResSuite",
    )

    val apkForLocalTests =
      mapOf(
        build.androidApplication(":app").intermediatesDir to "packageDebugForAndroidResSuite",
        build.androidLibrary(":lib").intermediatesDir to "packageDebugLibAndroidResSuiteForUnitTest",
        build.androidFeature(":feature").intermediatesDir to "packageDebugForFeatureResSuite",
      )
    for ((intermediatesDir, taskName) in apkForLocalTests) {
      val apkForLocalTest =
        intermediatesDir.resolve("apk_for_local_test").toFile().walkTopDown().single {
          it.name == "apk-for-local-test.ap_" && it.parentFile.name == taskName
        }
      ZipFile(apkForLocalTest).use { zip ->
        assertWithMessage("$taskName stored_asset.nocompress")
          .that(zip.getEntry("assets/stored_asset.nocompress").method)
          .isEqualTo(ZipEntry.STORED)
        assertWithMessage("$taskName deflated_asset.txt")
          .that(zip.getEntry("assets/deflated_asset.txt").method)
          .isEqualTo(ZipEntry.DEFLATED)
      }
    }
  }

  /** Returns the ids of the resources listed in the given R.txt file, by resource type and name, e.g. `string app_name`. */
  private fun readResourceIds(symbolList: Path): Map<String, String> {
    assertWithMessage("$symbolList exists").that(symbolList.exists()).isTrue()
    return symbolList
      .readLines()
      .filter { it.isNotBlank() }
      .associate { line ->
        // e.g. `int string app_name 0x7f010000`
        val (_, type, name, value) = line.split(" ", limit = 4)
        "$type $name" to value
      }
  }
}

abstract class ReplaceLibraryManifestMarkerTask : DefaultTask() {
  @get:InputFile abstract val mergedManifest: RegularFileProperty
  @get:OutputFile abstract val updatedManifest: RegularFileProperty

  @TaskAction
  fun taskAction() {
    val content = mergedManifest.get().asFile.readText().replace("raw_lib_manifest", "transformed_lib_manifest")
    updatedManifest.get().asFile.writeText(content)
  }
}

class TransformLibraryMergedManifestCallback : LibraryComponentCallback {
  override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
      val updater = project.tasks.register("${variant.name}ManifestMarkerUpdater", ReplaceLibraryManifestMarkerTask::class.java)
      variant.artifacts
        .use(updater)
        .wiredWithFiles(ReplaceLibraryManifestMarkerTask::mergedManifest, ReplaceLibraryManifestMarkerTask::updatedManifest)
        .toTransform(SingleArtifact.MERGED_MANIFEST)
    }
  }
}
