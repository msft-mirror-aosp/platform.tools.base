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
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.localRepositories
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.generateAarWithContent
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlin.io.path.readText
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/** A library whose manifest declares [TEST_MANIFEST_ACTIVITY], for example `androidx.compose.ui:ui-test-manifest`. */
private const val TEST_MANIFEST_LIBRARY = "com.example.testmanifest:testmanifest:1.0"
private const val TEST_MANIFEST_ACTIVITY = "com.example.testmanifest.TestManifestActivity"

private val testManifestAar: ByteArray =
  generateAarWithContent(
    packageName = "com.example.testmanifest",
    manifest =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.testmanifest">
          <application>
              <activity android:name="$TEST_MANIFEST_ACTIVITY" android:exported="false" />
          </application>
      </manifest>
      """
        .trimIndent(),
  )

/**
 * Runs Robolectric tests in library and application host-jar test suites (`hostJar { enableAndroidResources = true }`) and in
 * `testDebugUnitTest`, verifying the test config, merged manifest overlay, manifest placeholders, resources, assets, and target SDK.
 */
class HostJarTestSuiteRobolectricTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("org.junit.platform:junit-platform-commons:1.13.3")
        jar("org.opentest4j:opentest4j:1.3.0")
        jar("org.apiguardian:apiguardian-api:1.1.2")
        jar("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
          .addClasses(VerifyingJunitEngine::class.java, VerifyingTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", VerifyingJunitEngine::class.java.name)
        library(MavenRepoGenerator.Library(TEST_MANIFEST_LIBRARY, "aar", testManifestAar))
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidLibrary(":lib") {
          android {
            namespace = "com.example.lib"
            // A library's own target SDK is its minSdk, the tests use testOptions.targetSdk.
            testOptions.targetSdk = ROBOLECTRIC_SDK
          }
          addRobolectricTests(packageName = "some.lib", rPackage = "com.example.lib", markerValue = "from_lib_suite_manifest")
        }
        androidApplication(":app") {
          pluginCallbacks += AddSuiteMarkerPlaceholderCallback::class.java
          android {
            namespace = "com.example.app"
            defaultConfig.targetSdk = ROBOLECTRIC_SDK
          }
          // The suite's manifest takes the value from the tested variant's manifest placeholders.
          addRobolectricTests(packageName = "some.app", rPackage = "com.example.app", markerValue = "\${suiteMarker}")
        }
      }

  @Test
  fun robolectricTestsRunInHostJarSuitesAndUnitTests() {
    val build = rule.build
    build.executor.run(
      ":lib:testDebugUnitTest",
      ":lib:testDebugRoboJvmTestSuite",
      ":app:testDebugUnitTest",
      ":app:testDebugRoboJvmTestSuite",
    )

    // RunRobolectric verifies the suite runs; check the unit test XML reports too.
    val unitTestResults =
      listOf(
        build.androidLibrary(":lib").buildDir.resolve("test-results/testDebugUnitTest/TEST-some.lib.RobolectricUnitTest.xml"),
        build.androidApplication().buildDir.resolve("test-results/testDebugUnitTest/TEST-some.app.RobolectricUnitTest.xml"),
      )
    for (results in unitTestResults) {
      assertThat(Files.isRegularFile(results)).named("$results exists").isTrue()
      assertThat(results.readText()).contains("tests=\"1\" skipped=\"0\" failures=\"0\" errors=\"0\"")
    }
  }

  /**
   * Adds a `robo` host-jar suite and a `test` unit test running Robolectric against the module's string resource, asset, and target SDK,
   * with the suite test also verifying its manifest overlay and suite-only dependency manifest.
   */
  private fun AndroidProjectDefinition<out CommonExtension>.addRobolectricTests(
    packageName: String,
    rPackage: String,
    markerValue: String,
  ) {
    android {
      testOptions.unitTests.isIncludeAndroidResources = true
      testOptions.customSuites.create("robo") {
        it.useVerifyingJunitEngine()
        it.useJunitEngine.addInputProperty("com.android.junit.engine.invoked.methods", "$packageName.RunRobolectric#run")
        it.hostJar {
          enableAndroidResources = true
          dependencies.apply {
            implementation.add("org.robolectric:robolectric:$ROBOLECTRIC_VERSION")
            implementation.add("junit:junit:4.13.2")
            // A dependency of the suite only, whose manifest declares an activity.
            implementation.add(TEST_MANIFEST_LIBRARY)
          }
        }
        it.targetVariants.add("debug")
        it.targets.apply { create("jvm") {} }
      }
    }
    dependencies {
      testImplementation("org.robolectric:robolectric:$ROBOLECTRIC_VERSION")
      testImplementation("junit:junit:4.13.2")
      // Provides a current ASM version on the unit test classpath so Robolectric 4.8.2 can instrument classes compiled for the test JDK
      // (the suite gets gradle-api through useVerifyingJunitEngine).
      testImplementation("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
    }
    val packagePath = packageName.replace('.', '/')
    // Maps both the target SDK and Robolectric's max supported SDK (used to parse the application manifest) to the prebuilt SDK jar in the
    // offline test repository.
    val robolectricDeps =
      """
      org.robolectric\:android-all-instrumented\:$ROBOLECTRIC_SDK_VERSION=${robolectricSdkJar()}
      org.robolectric\:android-all-instrumented\:$ROBOLECTRIC_MAX_SDK_VERSION=${robolectricSdkJar()}
      """
        .trimIndent()
    files {
      add("src/main/res/values/strings.xml", "<resources><string name=\"label\">Label</string></resources>")
      add("src/main/assets/asset.txt", "Asset")
      add(
        "src/robo/AndroidManifest.xml",
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application>
                <meta-data android:name="com.example.SUITE_MARKER" android:value="$markerValue" />
            </application>
        </manifest>
        """
          .trimIndent(),
      )
      add("src/robo/resources/robolectric-deps.properties", robolectricDeps)
      add("src/test/resources/robolectric-deps.properties", robolectricDeps)
      add(
        "src/robo/java/$packagePath/RobolectricTest.java",
        robolectricTestSource(packageName, "RobolectricTest", rPackage, suiteManifestMarker = expectedMarker(markerValue)),
      )
      add(
        "src/test/java/$packagePath/RobolectricUnitTest.java",
        robolectricTestSource(packageName, "RobolectricUnitTest", rPackage, suiteManifestMarker = null),
      )
      // Runs the JUnit 4 Robolectric test via JUnitCore from VerifyingJunitEngine.
      add(
        "src/robo/java/$packagePath/RunRobolectric.java",
        """
        package $packageName;

        import org.junit.runner.JUnitCore;
        import org.junit.runner.Result;
        import org.junit.runner.notification.Failure;

        public class RunRobolectric {
            public static void run() {
                Result result = JUnitCore.runClasses(RobolectricTest.class);
                StringBuilder failures = new StringBuilder();
                for (Failure failure : result.getFailures()) {
                    failures.append(failure.getTrace()).append('\n');
                }
                if (result.getRunCount() != 1 || !result.wasSuccessful()) {
                    throw new AssertionError(result.getRunCount() + " run, " + result.getFailureCount() + " failed:\n" + failures);
                }
            }
        }
        """
          .trimIndent(),
      )
    }
  }

  /**
   * A Robolectric test that checks the SDK, a string and an asset. With [suiteManifestMarker], it also checks the meta-data value of the
   * suite's manifest overlay and the activity that the manifest of [TEST_MANIFEST_LIBRARY] declares.
   */
  private fun robolectricTestSource(packageName: String, className: String, rPackage: String, suiteManifestMarker: String?): String {
    val suiteManifestChecks =
      suiteManifestMarker?.let {
        """
        PackageManager packageManager = application.getPackageManager();
        assertEquals("$it", packageManager.getApplicationInfo(application.getPackageName(), PackageManager.GET_META_DATA).metaData.getString("com.example.SUITE_MARKER"));
        packageManager.getActivityInfo(new ComponentName(application, "$TEST_MANIFEST_ACTIVITY"), 0);
        """
          .trimIndent()
          // Indents subsequent lines to align with the method body in the template below.
          .replace("\n", "\n              ")
      } ?: ""
    return """
      package $packageName;

      import static org.junit.Assert.assertEquals;

      import android.app.Application;
      import android.content.ComponentName;
      import android.content.pm.PackageManager;
      import android.os.Build;
      import java.io.BufferedReader;
      import java.io.InputStreamReader;
      import org.junit.Test;
      import org.junit.runner.RunWith;
      import org.robolectric.RobolectricTestRunner;
      import org.robolectric.RuntimeEnvironment;

      @RunWith(RobolectricTestRunner.class)
      public class $className {
          @Test
          public void seesTheAndroidResources() throws Exception {
              Application application = RuntimeEnvironment.getApplication();
              assertEquals($ROBOLECTRIC_SDK, Build.VERSION.SDK_INT);
              assertEquals("Label", application.getString($rPackage.R.string.label));
              try (BufferedReader reader = new BufferedReader(new InputStreamReader(application.getAssets().open("asset.txt")))) {
                  assertEquals("Asset", reader.readLine());
              }
              $suiteManifestChecks
          }
      }
      """
      .trimIndent()
  }

  private fun expectedMarker(markerValue: String) = if (markerValue == "\${suiteMarker}") SUITE_MARKER_PLACEHOLDER_VALUE else markerValue

  private fun robolectricSdkJar(): String {
    val relativePath =
      "org/robolectric/android-all-instrumented/$ROBOLECTRIC_SDK_VERSION/android-all-instrumented-$ROBOLECTRIC_SDK_VERSION.jar"
    val jar =
      localRepositories.map { it.resolve(relativePath) }.firstOrNull { Files.exists(it) }
        ?: throw AssertionError("Failed to find the Robolectric SDK jar $relativePath in the test repositories.")
    return jar.toAbsolutePath().toString().replace('\\', '/')
  }

  companion object {
    private const val ROBOLECTRIC_VERSION = "4.8.2"
    private const val ROBOLECTRIC_SDK = 28
    private const val ROBOLECTRIC_SDK_VERSION = "9-robolectric-4913185-2-i4"
    private const val ROBOLECTRIC_MAX_SDK_VERSION = "12.1-robolectric-8229987-i4"
    const val SUITE_MARKER_PLACEHOLDER_VALUE = "from_app_placeholder"
  }
}

class AddSuiteMarkerPlaceholderCallback : ApplicationComponentCallback {
  override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
    androidComponents.onVariants { variant ->
      variant.manifestPlaceholders.put("suiteMarker", HostJarTestSuiteRobolectricTest.SUITE_MARKER_PLACEHOLDER_VALUE)
    }
  }
}
