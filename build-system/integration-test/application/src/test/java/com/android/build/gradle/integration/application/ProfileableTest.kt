/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.integration.common.utils.SigningHelper
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests verifying that builds using the profileable option are configured correctly. For example, including the profileable tag in
 * AndroidManifest, disable debuggable features and doesn't use release signing configs etc.
 */
class ProfileableTest {

  @get:Rule
  val project =
    GradleTestProject.builder()
      .fromTestApp(MultiModuleTestProject.builder().subproject(":app", MinimalSubProject.app("com.profilabletest.app")).build())
      .create()

  @get:Rule val temporaryDirectory = TemporaryFolder()

  @Test
  fun `test dsl setting the release build type to be profileable`() {
    val app = project.getSubproject(":app")
    app.buildFile.appendText("android.buildTypes.release.profileable = true")
    project.executor().with(BooleanOption.ENABLE_DEFAULT_DEBUG_SIGNING_CONFIG, true).run("assembleRelease")
    val apkSigned = project.getSubproject("app").getApk(GradleTestProject.ApkType.RELEASE_SIGNED)
    val verificationResult = SigningHelper.assertApkSignaturesVerify(apkSigned, 30)
    assertThat(verificationResult.signerCertificates.first().subjectX500Principal.name).isEqualTo("C=US,O=Android,CN=Android Debug")

    project.getSubproject("app").checkProjectContainsProfileableInManifest(ApkSelector.RELEASE_SIGNED)

    // Test no signing config configured, if the automatic signing config assignment is disabled.
    project.executor().with(BooleanOption.ENABLE_DEFAULT_DEBUG_SIGNING_CONFIG, false).run("clean", "assembleRelease")

    project.getSubproject("app").assertApk(ApkSelector.RELEASE) { doesNotHaveApkSigningBlock() }
  }

  @Test
  fun `test variant api when the release build type to be profileable`() {
    val app = project.getSubproject(":app")
    app.buildFile.appendText(
      """

       androidComponents {
            beforeVariants(selector().withBuildType("release"), { builder ->
                 builder.setProfileable(true)
            })
      }
      """
        .trimIndent()
    )
    project.executor().with(BooleanOption.ENABLE_DEFAULT_DEBUG_SIGNING_CONFIG, true).run("assembleRelease")
    val apkSigned = project.getSubproject("app").getApk(GradleTestProject.ApkType.RELEASE_SIGNED)
    val verificationResult = SigningHelper.assertApkSignaturesVerify(apkSigned, 30)
    assertThat(verificationResult.signerCertificates.first().subjectX500Principal.name).isEqualTo("C=US,O=Android,CN=Android Debug")

    project.getSubproject("app").checkProjectContainsProfileableInManifest(ApkSelector.RELEASE_SIGNED)

    // Test no signing config configured, if the automatic signing config assignment is disabled.
    project.executor().with(BooleanOption.ENABLE_DEFAULT_DEBUG_SIGNING_CONFIG, false).run("clean", "assembleRelease")

    project.getSubproject("app").assertApk(ApkSelector.RELEASE) { doesNotHaveApkSigningBlock() }
  }

  @Test
  fun `test variant api the release build type to be profileable on Api 29`() {
    val app = project.getSubproject(":app")
    app.buildFile.apply {
      appendText("android.compileSdk = 29\n")
      appendText("android.compileOptions.sourceCompatibility = JavaVersion.VERSION_1_8\n")
      appendText("android.compileOptions.targetCompatibility = JavaVersion.VERSION_1_8\n")
      appendText(
        """
         androidComponents {
              beforeVariants(selector().withBuildType("release"), { builder ->
                   builder.setProfileable(true)
              })
        }
        """
          .trimIndent()
      )
    }

    project.executor().with(BooleanOption.ENABLE_DEFAULT_DEBUG_SIGNING_CONFIG, true).run("assembleRelease")

    project.getSubproject("app").assertApk(ApkSelector.RELEASE_SIGNED) {
      // should not contain 'android:enabled'
      manifestAsNodes()
        .node("manifest")
        .node("application")
        .node("profileable")
        .containsExactlyAttributes("http://schemas.android.com/apk/res/android:shell")
    }
  }

  @Test
  fun `test dsl setting the release build type to be profileable on Api 29`() {
    val app = project.getSubproject(":app")
    app.buildFile.apply {
      appendText("android.buildTypes.release.profileable = true\n")
      appendText("android.compileSdk = 29\n")
      appendText("android.compileOptions.sourceCompatibility = JavaVersion.VERSION_1_8\n")
      appendText("android.compileOptions.targetCompatibility = JavaVersion.VERSION_1_8\n")
    }

    project.executor().with(BooleanOption.ENABLE_DEFAULT_DEBUG_SIGNING_CONFIG, true).run("assembleRelease")
    project.getSubproject("app").assertApk(ApkSelector.RELEASE_SIGNED) {
      // should not contain 'android:enabled'
      manifestAsNodes()
        .node("manifest")
        .node("application")
        .node("profileable")
        .containsExactlyAttributes("http://schemas.android.com/apk/res/android:shell")
    }
  }

  @Test
  fun `test dsl when profileable and debuggable enabled`() {
    val app = project.getSubproject(":app")
    app.buildFile.appendText("android.buildTypes.debug.debuggable = true\n")
    app.buildFile.appendText("android.buildTypes.debug.profileable = true\n")
    val result = project.executor().run("assembleDebug")

    // Ensure profileable is not applied (debuggable dsl option overrides profileable).
    app.assertApk(ApkSelector.DEBUG) { manifestAsNodes().node("manifest").node("application").hasNoNodes() }

    result.stdout.use { out ->
      ScannerSubject.assertThat(out)
        .contains(
          ":app build type 'debug' can only have debuggable or profileable enabled.\n" +
            "Only one of these options can be used at a time.\n" +
            "Recommended action: Only set one of debuggable=true and profileable=true.\n"
        )
    }
  }

  @Test
  fun `test variant api when profileable and debuggable enabled`() {
    val app = project.getSubproject(":app")
    app.buildFile.appendText("android.buildTypes.debug.debuggable true\n")
    app.buildFile.appendText(
      """
       androidComponents {
            beforeVariants(selector().withBuildType("debug"), { builder ->
                 builder.setProfileable(true)
            })
      }
      """
        .trimIndent()
    )
    val result = project.executor().run("assembleDebug")
    // Ensure profileable is not applied (debuggable dsl option overrides profileable).
    app.assertApk(ApkSelector.DEBUG) { manifestAsNodes().node("manifest").node("application").hasNoNodes() }

    result.stdout.use { out ->
      ScannerSubject.assertThat(out)
        .contains(
          "Variant 'debug' can only have debuggable or profileable enabled.\n" +
            "Only one of these options can be used at a time.\n" +
            "Recommended action: Only set one of profileable=true via variant API\n" +
            "or debuggable=true via DSL"
        )
    }
  }

  @Test
  fun `test injecting the debug build type to be profileable`() {
    val app = project.getSubproject(":app")
    val result = project.executor().with(StringOption.PROFILING_MODE, "profileable").run("assembleDebug")
    assertThat(result.tasks.filter { it.lowercase(Locale.US).contains("lint") }).named("Lint tasks").isEmpty()
    app.checkProjectContainsProfileableInManifest(ApkSelector.DEBUG)
  }

  @Test
  fun `test injecting the release build type to be profileable`() {
    val app = project.getSubproject(":app")
    val result =
      project
        .executor()
        .with(StringOption.PROFILING_MODE, "profileable")
        .with(BooleanOption.ENABLE_DEFAULT_DEBUG_SIGNING_CONFIG, true)
        .run("assembleRelease")
    assertThat(result.tasks.filter { it.lowercase(Locale.US).contains("lint") }).named("Lint tasks").isEmpty()
    app.checkProjectContainsProfileableInManifest(ApkSelector.RELEASE_SIGNED)
  }

  @Test
  fun `build with compileSdk less than 29 fails`() {
    val app = project.getSubproject(":app")
    val lowCompileSdkVersion = 28
    TestFileUtils.searchAndReplace(
      app.buildFile.absoluteFile,
      "android.compileSdkVersion $DEFAULT_COMPILE_SDK_VERSION",
      "android.compileSdkVersion $lowCompileSdkVersion",
    )
    app.buildFile.appendText("android.buildTypes.debug.debuggable false\n")

    val result = project.executor().with(StringOption.PROFILING_MODE, "profileable").expectFailure().run("assembleDebug")

    result.stderr.use { out ->
      ScannerSubject.assertThat(out).contains("'profileable' is enabled in variant 'debug' with compile SDK less than API 29.")
      ScannerSubject.assertThat(out)
        .contains("Recommended action: If possible, upgrade compileSdk from $lowCompileSdkVersion to at least API 29.")
    }
  }

  @Test
  fun `build with compileSdk less than 29 fails with variant api`() {
    val app = project.getSubproject(":app")
    app.buildFile.appendText("android.buildTypes.debug.debuggable false\n")
    app.buildFile.appendText(
      """
       androidComponents {
            beforeVariants(selector().withBuildType("debug"), { builder ->
                 builder.setProfileable(true)
            })
      }
      """
        .trimIndent()
    )
    val lowCompileSdkVersion = 28
    TestFileUtils.searchAndReplace(
      app.buildFile.absoluteFile,
      "android.compileSdkVersion $DEFAULT_COMPILE_SDK_VERSION",
      "android.compileSdkVersion $lowCompileSdkVersion",
    )

    val result = project.executor().expectFailure().run("assembleDebug")

    result.stderr.use { out ->
      ScannerSubject.assertThat(out).contains("'profileable' is enabled in variant 'debug' with compile SDK less than API 29.")
      ScannerSubject.assertThat(out)
        .contains("Recommended action: If possible, upgrade compileSdk from $lowCompileSdkVersion to at least API 29.")
    }
  }

  @Test
  fun `test fail on reading profileable from builder`() {
    val app = project.getSubproject(":app")
    app.buildFile.apply {
      appendText("android.compileSdk = 29\n")
      appendText(
        """
         androidComponents {
              beforeVariants(selector().withBuildType("release"), { builder ->
                   boolean prof = builder.profileable
              })
        }
        """
          .trimIndent()
      )
    }

    val result = project.executor().expectFailure().with(BooleanOption.ENABLE_DEFAULT_DEBUG_SIGNING_CONFIG, true).run("assembleRelease")
    result.stderr.use { out ->
      ScannerSubject.assertThat(out)
        .contains("You cannot access 'profileable' on ApplicationVariantBuilder in the [AndroidComponentsExtension.beforeVariants]")
    }
  }

  private fun GradleTestProject.checkProjectContainsProfileableInManifest(apkSelector: ApkSelector) {
    assertApk(apkSelector) {
      manifestAsNodes()
        .node("manifest")
        .node("application")
        .node("profileable")
        .containsExactlyAttributesAndValues(
          "http://schemas.android.com/apk/res/android:enabled=true",
          "http://schemas.android.com/apk/res/android:shell=true",
        )
    }
  }
}
