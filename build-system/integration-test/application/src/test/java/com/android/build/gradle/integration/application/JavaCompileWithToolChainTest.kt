/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.OsType
import com.android.testutils.TestUtils
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.junit.Rule
import org.junit.Test

class JavaCompileWithToolChainTest {

  @get:Rule val rule = GradleRule.from { androidJavaApplication { pluginCallbacks += JavaToolchainCallback::class.java } }

  @Test
  fun basicTest() {
    rule.build {
      gradleProperties {
        add("org.gradle.java.installations.auto-detect", "false")
        add("org.gradle.java.installations.paths", jdk8LocationInGradleFile)
        add("toolchainVersion", "8")
      }
    }

    var result = rule.build.executor.withArgument("--info").run("assembleDebug")

    ScannerSubject.assertThat(result.stdout).contains("Compiling with toolchain '${jdk8LocationFromStdout}'")

    rule.build.reconfigureGradleProperties {
      add("org.gradle.java.installations.paths", latestJdkLocationInGradleFile)
      add("toolchainVersion", latestJdkVersion.toString())
    }

    result = rule.build.executor.withArgument("--info").run("assembleDebug")
    ScannerSubject.assertThat(result.stdout).contains("Compiling with toolchain '${latestJdkLocationFromStdout}'")
  }

  private fun setUpBuiltInKotlin() {
    rule.build {
      gradleProperties {
        add("org.gradle.java.installations.auto-detect", "false")
        add("org.gradle.java.installations.paths", latestJdkLocationInGradleFile)
        add("toolchainVersion", latestJdkVersion.toString())
      }
      androidApplication { kotlin { compilerOptions { allWarningsAsErrors.set(true) } } }
    }
    rule.build
      .androidApplication()
      .files
      .update("build.gradle")
      .append(
        """
            // Reading targetCompatibility early should fail
            try {
                println android.compileOptions.targetCompatibility
                throw new IllegalStateException("Exception was not thrown")
            } catch (Exception e) {
                if (e.message != "targetCompatibility is not yet finalized") {
                    throw new IllegalStateException("Exception message is not as expected: " + e.message)
                }
            }

            // Reading targetCompatibility after configuration should succeed
            afterEvaluate {
                def targetCompatibility = android.compileOptions.targetCompatibility
                if (targetCompatibility != JavaVersion.toVersion($latestJdkVersion)) {
                    throw new IllegalStateException("Unexpected targetCompatibility: " + targetCompatibility)
                }
            }
    """
          .trimIndent()
      )

    rule.build
      .androidApplication()
      .files
      .add(
        "src/main/kotlin/com/example/KotlinClass.kt",
        """
        package com.example

        class KotlinClass {
        }
        """
          .trimIndent(),
      )
  }

  @Test
  fun `test source and target compatibility versions when toolchain is configured with built-in kotlin`() {
    setUpBuiltInKotlin()
    // Compiling should not throw an error (regression test for bug 260059413)
    rule.build.executor.run("compileDebugJavaWithJavac")

    val androidProject = rule.build.modelBuilder.fetchModels(variantName = "debug").container.getProject(":app").androidProject!!
    assertThat(androidProject.javaCompileOptions).isNotNull()
    androidProject.javaCompileOptions?.let {
      assertThat(it.sourceCompatibility).isEqualTo(latestJdkVersion.toString())
      assertThat(it.targetCompatibility).isEqualTo(latestJdkVersion.toString())
    }
  }

  @Test
  fun `test source and target compatibility versions when toolchain is configured with jetbrains kotlin`() {
    rule.build {
      gradleProperties {
        add("org.gradle.java.installations.auto-detect", "false")
        add("org.gradle.java.installations.paths", latestJdkLocationInGradleFile)
        add("toolchainVersion", latestJdkVersion.toString())
        add(BooleanOption.BUILT_IN_KOTLIN, false)
        add(BooleanOption.USE_NEW_DSL, false)
      }
      androidApplication {
        applyPlugin(PluginType.KOTLIN_ANDROID)
        kotlin { compilerOptions { allWarningsAsErrors.set(true) } }
      }
    }
    rule.build
      .androidApplication()
      .files
      .update("build.gradle")
      .append(
        """
            // Reading targetCompatibility early should fail
            try {
                println android.compileOptions.targetCompatibility
                throw new IllegalStateException("Exception was not thrown")
            } catch (Exception e) {
                if (e.message != "targetCompatibility is not yet finalized") {
                    throw new IllegalStateException("Exception message is not as expected: " + e.message)
                }
            }

            // Reading targetCompatibility after configuration should succeed
            afterEvaluate {
                def targetCompatibility = android.compileOptions.targetCompatibility
                if (targetCompatibility != JavaVersion.toVersion($latestJdkVersion)) {
                    throw new IllegalStateException("Unexpected targetCompatibility: " + targetCompatibility)
                }
            }
    """
          .trimIndent()
      )

    rule.build
      .androidApplication()
      .files
      .add(
        "src/main/kotlin/com/example/KotlinClass.kt",
        """
        package com.example

        class KotlinClass {
        }
        """
          .trimIndent(),
      )

    // Compiling should not throw an error (regression test for bug 260059413)
    rule.build.executor.disableBuiltInKotlin().with(BooleanOption.USE_NEW_DSL, false).run("compileDebugJavaWithJavac")

    val androidProject =
      rule.build.modelBuilder
        .disableBuiltInKotlin()
        .with(BooleanOption.USE_NEW_DSL, false)
        .fetchModels(variantName = "debug")
        .container
        .getProject(":app")
        .androidProject!!
    assertThat(androidProject.javaCompileOptions).isNotNull()
    androidProject.javaCompileOptions?.let {
      assertThat(it.sourceCompatibility).isEqualTo(latestJdkVersion.toString())
      assertThat(it.targetCompatibility).isEqualTo(latestJdkVersion.toString())
    }
  }

  @Test
  fun testDeprecatedSourceAndTarget() {
    rule.build {
      gradleProperties {
        add("org.gradle.java.installations.auto-detect", "false")
        add("org.gradle.java.installations.paths", TestUtils.getJava21Jdk().toString().replace("\\", "/"))
        add("toolchainVersion", "21")
      }
      androidApplication {
        android {
          // Disable Kotlin to avoid JVM target 7 issue with KGP
          enableKotlin = false
          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_7
            targetCompatibility = JavaVersion.VERSION_1_7
          }
        }
      }
    }

    // Run JavaCompile 21 targeting Java 7, expect failure
    val resultWithError = rule.build.executor.expectFailure().run("compileDebugJavaWithJavac")
    resultWithError.assertErrorContains("Java compiler version 21 has removed support for compiling with source/target version 7.")

    // Run JavaCompile 21 targeting Java 8, expect a warning
    rule.build.androidApplication().reconfigure {
      android.compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
      }
    }
    val resultWithWarning = rule.build.executor.run("compileDebugJavaWithJavac")
    val warningMessage = "Java compiler version 21 has deprecated support for compiling with source/target version 8."
    resultWithWarning.assertOutputContains(warningMessage)

    // Run JavaCompile 21 targeting Java 8 again with the suppress-warning option, expect no warning
    val resultWithNoWarning =
      rule.build.executor.with(BooleanOption.JAVA_COMPILE_SUPPRESS_SOURCE_TARGET_DEPRECATION_WARNING, true).run("compileDebugJavaWithJavac")
    resultWithNoWarning.assertOutputDoesNotContain(warningMessage)
  }

  class JavaToolchainCallback : GenericCallback {
    override fun handleProject(project: Project) {
      val version = project.providers.gradleProperty("toolchainVersion").orNull
      if (version != null) {
        project.extensions.configure(JavaPluginExtension::class.java) {
          it.toolchain { it.languageVersion.set(JavaLanguageVersion.of(version.toInt())) }
        }
      }
    }
  }

  companion object {
    private val jdk8Location = TestUtils.getJava8Jdk().toString()

    private val latestJdkVersion = Runtime.version().feature()
    private val latestJdkLocation =
      when (latestJdkVersion) {
        17 -> TestUtils.getJava17Jdk()
        21 -> TestUtils.getJava21Jdk()
        else -> throw Exception("Finding the jdk path of jdk $latestJdkVersion is not supported")
      }.toString()

    val jdk8LocationInGradleFile = jdk8Location.replace("\\", "/")
    val latestJdkLocationInGradleFile = latestJdkLocation.replace("\\", "/")

    val jdk8LocationFromStdout =
      if (OsType.getHostOs() == OsType.WINDOWS) {
        jdk8Location.replace("/", "\\")
      } else {
        jdk8Location
      }

    val latestJdkLocationFromStdout =
      if (OsType.getHostOs() == OsType.WINDOWS) {
        latestJdkLocation.replace("/", "\\")
      } else {
        latestJdkLocation
      }
  }
}
