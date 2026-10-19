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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.DESUGAR_DEPENDENCY_VERSION
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test

/**
 * Reproduction test for circular dependency between L8 and R8 tasks.
 *
 * The circular dependency occurs when an instrumented test variant has minification enabled and library desugaring is active, combined with
 * external dependencies between the main and test variants.
 *
 * The cycle is:
 * ```text
 * :app:l8DexDesugarLibRelease
 * \--- :app:l8DexDesugarLibReleaseAndroidTest
 *      \--- :app:minifyReleaseAndroidTestWithR8
 *           \--- :app:l8DexDesugarLibRelease (*)
 * ```
 */
class L8CircularDependencyReproTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication {
      android { namespace = "com.example.app" }
      files {
        update("build.gradle")
          .replaceWith(
            """
                    apply plugin: 'com.android.application'
                    android {
                        namespace = "com.example.app"
                        compileSdk = $DEFAULT_COMPILE_SDK_VERSION
                        testBuildType = "release"
                        compileOptions {
                            sourceCompatibility = JavaVersion.VERSION_1_8
                            targetCompatibility = JavaVersion.VERSION_1_8
                            coreLibraryDesugaringEnabled = true
                        }
                        defaultConfig {
                            minSdk = 24
                            multiDexEnabled = true
                        }
                        buildTypes {
                            release {
                                minifyEnabled = true
                            }
                        }
                    }
                    dependencies {
                        coreLibraryDesugaring "com.android.tools:desugar_jdk_libs:$DESUGAR_DEPENDENCY_VERSION"
                    }
                    afterEvaluate {
                        if (tasks.findByName("l8DexDesugarLibReleaseAndroidTest") != null) {
                            tasks.named("l8DexDesugarLibRelease").configure {
                                // Simulate a third-party plugin forcing the App L8 task to depend on the Test L8 task's outputs.
                                // For example, com.slack.keeper.KeeperPlugin does this to share desugar keep rules from test to app.
                                dependsOn("l8DexDesugarLibReleaseAndroidTest")
                            }
                        }
                    }
                """
              .trimIndent()
          )
        add("src/main/java/com/example/helloworld/HelloWorld.java", "package com.example.helloworld; public class HelloWorld {}")
      }
    }
  }

  @Test
  fun testCircularDependency() {
    // The build is expected to pass as the circular dependency is resolved by breaking
    // the task dependency through APK_MAPPING_FILE.
    rule.build.executor.run(":app:assembleReleaseAndroidTest")
  }
}
