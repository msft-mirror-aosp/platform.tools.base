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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.output.ApkSubject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThatApk
import com.android.build.gradle.options.BooleanOption
import java.io.File
import org.junit.Rule
import org.junit.Test

/** A class of version 1.0 of `com.example:shared`. */
class SharedMarkerV1

/** A class that only version 2.0 of `com.example:shared` has. */
class SharedMarkerV2

/** A class of `com.example:suite-only`, a library that only the test suites depend on. */
class SuiteOnlyMarker

/**
 * Checks dependency resolution and packaging for the test APK of a test suite:
 * - For an application, the suite resolves its runtime dependencies at the application's versions, and the test APK packages only the
 *   libraries that the suite alone depends on, with their Java resources and manifest entries.
 * - For a library, the test APK holds the library's own classes and Java resources.
 */
class TestSuiteApkDependenciesTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.example:shared:1.0").addClasses(SharedMarkerV1::class.java).addTextFile("shared-1.0.txt", "1.0")
        jar("com.example:shared:2.0")
          .addClasses(SharedMarkerV1::class.java, SharedMarkerV2::class.java)
          .addTextFile("shared-2.0.txt", "2.0")
        jar("com.example:suite-only:1.0").addClasses(SuiteOnlyMarker::class.java).addTextFile("suite-only.txt", "suite only")
        aar("com.example", "app-provider", "1.0").withManifest(providerManifest("com.example.appprovider", "AppLibProvider"))
        aar("com.example", "suite-provider", "1.0").withManifest(providerManifest("com.example.suiteprovider", "SuiteLibProvider"))
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication(":app") {
          android {
            namespace = "com.example.app"
            testOptions.customSuites.create("device") {
              // Only the test APK is built here, the suite is never run.
              it.useJunitEngine.includeEngines.add("junit-jupiter")
              it.testApk {
                // The application depends on version 2.0, which the suite resolves.
                dependencies.implementation.add("com.example:shared:1.0")
                dependencies.implementation.add("com.example:suite-only:1.0")
                dependencies.implementation.add("com.example:suite-provider:1.0")
                // The application also depends on this library, so the application APK declares its provider.
                dependencies.implementation.add("com.example:app-provider:1.0")
              }
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          dependencies {
            implementation("com.example:shared:2.0")
            implementation("com.example:app-provider:1.0")
          }
          files {
            add("src/main/java/com/example/app/AppClass.java", "package com.example.app; public class AppClass {}")
            // Compiles only with version 2.0 of com.example:shared, the application's.
            add(
              "src/device/java/com/example/app/DeviceTest.java",
              """
              package com.example.app;
              public class DeviceTest {
                  Object shared = new com.android.build.gradle.integration.testing.suites.SharedMarkerV2();
                  Object suiteOnly = new com.android.build.gradle.integration.testing.suites.SuiteOnlyMarker();
                  Object app = new AppClass();
              }
              """
                .trimIndent(),
            )
          }
        }
        androidLibrary(":lib") {
          android {
            namespace = "com.example.lib"
            testOptions.customSuites.create("libDevice") {
              it.useJunitEngine.includeEngines.add("junit-jupiter")
              it.testApk { dependencies.implementation.add("com.example:suite-only:1.0") }
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          files {
            add("src/main/java/com/example/lib/LibClass.java", "package com.example.lib; public class LibClass {}")
            add("src/main/resources/lib_resource.txt", "lib")
            add(
              "src/libDevice/java/com/example/lib/LibDeviceTest.java",
              "package com.example.lib; public class LibDeviceTest { Object lib = new LibClass(); }",
            )
          }
        }
      }

  @Test
  fun applicationSuiteApkDoesNotShipTheApplicationLibraries() {
    rule.build.executor.run(":app:packageDebugDevice")

    assertThatApk(suiteApk(":app", "debugDevice")).apply {
      containsClass("Lcom/example/app/DeviceTest;")
      // The application's own code and its libraries are in the application APK, which runs in the same process.
      doesNotContainClass("Lcom/example/app/AppClass;")
      doesNotContainClass("Lcom/android/build/gradle/integration/testing/suites/SharedMarkerV1;")
      doesNotContainClass("Lcom/android/build/gradle/integration/testing/suites/SharedMarkerV2;")
      doesNotContain("shared-1.0.txt")
      doesNotContain("shared-2.0.txt")
      // A library that only the suite has is packaged, with its Java resources.
      containsClass("Lcom/android/build/gradle/integration/testing/suites/SuiteOnlyMarker;")
      contains("suite-only.txt")
    }
    ApkSubject.assertThat(suiteApk(":app", "debugDevice")) {
      // The application APK declares the providers of its libraries, so each authority is registered once.
      manifest().doesNotContain("com.example.appprovider.AppLibProvider")
      // The provider of a library that only the suite has is merged into the test APK manifest.
      manifest().contains("com.example.suiteprovider.SuiteLibProvider")
    }
  }

  @Test
  fun librarySuiteApkHoldsTheLibrary() {
    rule.build.executor.run(":lib:packageDebugLibDevice")

    assertThatApk(suiteApk(":lib", "debugLibDevice")).apply {
      containsClass("Lcom/example/lib/LibDeviceTest;")
      containsClass("Lcom/example/lib/LibClass;")
      contains("lib_resource.txt")
      containsClass("Lcom/android/build/gradle/integration/testing/suites/SuiteOnlyMarker;")
      contains("suite-only.txt")
    }
  }

  private fun suiteApk(projectPath: String, apkDirName: String): File {
    val buildDir =
      if (projectPath == ":app") rule.build.androidApplication(projectPath).buildDir.toFile()
      else rule.build.androidLibrary(projectPath).buildDir.toFile()
    val apkDir = buildDir.resolve("outputs/apk/$apkDirName")
    return checkNotNull(apkDir.listFiles { file -> file.name.endsWith(".apk") }?.singleOrNull()) { "No single APK in $apkDir" }
  }
}

/** The manifest of an AAR with package [packageName] that declares the content provider [packageName].[providerName]. */
private fun providerManifest(packageName: String, providerName: String) =
  """
  <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">
      <application>
          <provider android:name="$packageName.$providerName" android:authorities="$packageName.provider" android:exported="false" />
      </application>
  </manifest>
  """
    .trimIndent()
