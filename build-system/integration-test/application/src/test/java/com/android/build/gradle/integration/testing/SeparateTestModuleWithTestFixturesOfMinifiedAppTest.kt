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

package com.android.build.gradle.integration.testing

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.testutils.apk.Apk
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for b/586849342: a com.android.test module testing a minified app, and depending on the test fixtures of the app and of a
 * library packaged by the app, must reference the app's and library's classes by their obfuscated names.
 *
 * The app and library classes are neither packaged in the test APK nor part of its R8 program, so they need to be passed to R8 as classpath
 * for `-applymapping` to rename the references to them.
 */
class SeparateTestModuleWithTestFixturesOfMinifiedAppTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication(":app") {
      android {
        namespace = "com.example.app"
        defaultConfig.minSdk = 24
        testFixtures.enable = true
        buildTypes {
          named("release") {
            it.isMinifyEnabled = true
            it.proguardFiles += listOf(File("keep-for-tests.pro"))
          }
        }
      }
      dependencies { implementation(project(":lib")) }
      files.add(
        "keep-for-tests.pro",
        """
        -keep,allowobfuscation class com.example.app.AppTags { *; }
        -keep,allowobfuscation class com.example.lib.LibTags { *; }
        """
          .trimIndent(),
      )
      files.add(
        "src/main/java/com/example/app/AppTags.java",
        // language=java
        """
        package com.example.app;

        public final class AppTags {
            public static String button() { return "app-button"; }
        }
        """
          .trimIndent(),
      )
      files.add(
        "src/testFixtures/java/com/example/app/AppPage.java",
        // language=java
        """
        package com.example.app;

        public final class AppPage {
            public static String button() { return AppTags.button(); }
        }
        """
          .trimIndent(),
      )
    }
    androidLibrary(":lib") {
      android {
        namespace = "com.example.lib"
        defaultConfig.minSdk = 24
        testFixtures.enable = true
      }
      files.add(
        "src/main/java/com/example/lib/LibTags.java",
        // language=java
        """
        package com.example.lib;

        public final class LibTags {
            public static String button() { return "lib-button"; }
        }
        """
          .trimIndent(),
      )
      files.add(
        "src/testFixtures/java/com/example/lib/LibPage.java",
        // language=java
        """
        package com.example.lib;

        public final class LibPage {
            public static String button() { return LibTags.button(); }
        }
        """
          .trimIndent(),
      )
    }
    androidTest(":test") {
      android {
        namespace = "com.example.e2e"
        defaultConfig.minSdk = 24
        targetProjectPath = ":app"
        buildTypes {
          create("release") {
            it.isMinifyEnabled = true
            it.proguardFiles += listOf(File("test-rules.pro"))
          }
        }
      }
      dependencies {
        implementation(project(":app", testFixtures = true))
        implementation(project(":lib", testFixtures = true))
      }
      // No -dontwarn: R8 fails on missing classes if the app's or the library's classes are not on its classpath.
      files.add("test-rules.pro", "-keep class com.example.e2e.TagsTest { *; }")
      files.add(
        "src/main/java/com/example/e2e/TagsTest.java",
        // language=java
        """
        package com.example.e2e;

        import com.example.app.AppTags;
        import com.example.lib.LibTags;

        public final class TagsTest {
            public static String appTag() { return AppTags.button(); }
            public static String libTag() { return LibTags.button(); }
        }
        """
          .trimIndent(),
      )
    }
  }

  @Test
  fun testModuleReferencesObfuscatedAppAndLibraryClasses() {
    val build = rule.build
    build.executor.run(":app:assembleRelease", ":test:assembleRelease")

    val mapping = build.androidApplication(":app").outputsDir.resolve("mapping/release/mapping.txt").toFile().readLines()
    val obfuscatedAppTags = mapping.obfuscatedDescriptorOf("com.example.app.AppTags")
    val obfuscatedLibTags = mapping.obfuscatedDescriptorOf("com.example.lib.LibTags")
    assertThat(obfuscatedAppTags).isNotEqualTo("Lcom/example/app/AppTags;")
    assertThat(obfuscatedLibTags).isNotEqualTo("Lcom/example/lib/LibTags;")

    val testApk = build.androidTest(":test").getApkLocationForCopy(ApkSelector.RELEASE_SIGNED)
    Apk(testApk).use { apk ->
      val tagsTest =
        apk.getClass("Lcom/example/e2e/TagsTest;")
          ?: error("TagsTest not found in $testApk; classes: ${apk.allDexes.flatMap { it.classes.keys }}")
      val invokedClasses =
        tagsTest.methods
          .flatMap { it.implementation?.instructions ?: emptyList() }
          .filterIsInstance<ReferenceInstruction>()
          .mapNotNull { (it.reference as? MethodReference)?.definingClass }
          .filterNot { it.startsWith("Ljava/") }
      assertThat(invokedClasses).containsExactly(obfuscatedAppTags, obfuscatedLibTags)
      // The tested app's and library's classes must be classpath for R8, not packaged in the test APK.
      assertThat(apk.allDexes.flatMap { it.classes.keys })
        .containsNoneOf(obfuscatedAppTags, obfuscatedLibTags, "Lcom/example/app/AppTags;", "Lcom/example/lib/LibTags;")
    }
  }

  private fun List<String>.obfuscatedDescriptorOf(className: String): String {
    val obfuscatedName = single { it.startsWith("$className -> ") }.substringAfter(" -> ").removeSuffix(":")
    return "L${obfuscatedName.replace('.', '/')};"
  }
}
