/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.tools.lint.checks

import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.TextFormat

class WrongGradleMethodDetectorTest : AbstractCheckTest() {

  override fun lint(): TestLintTask {
    return super.lint().textFormat(TextFormat.RAW)
  }

  override fun getDetector(): Detector {
    return WrongGradleMethodDetector()
  }

  fun testDocumentationExample() {
    lint()
      .files(
        kts(
            """
            plugins {
                id("com.google.firebase.appdistribution")
            }
            android {
                flavorDimensions += "version"
                buildTypes {
                    getByName("debug") {
                    }
                    getByName("release") {
                    }
                }
                productFlavors {
                    create("demo") {
                        dimension = "version"
                        firebaseAppDistribution {
                            releaseNotes = "Release notes for demo version"
                            testers = "demo@example.com"
                        }
                    }
                }
            }
           """
          )
          .indented()
      )
      .run()
      .expect(
        """
        build.gradle.kts:15: Error: This does not resolve to the right method; you need to explicitly add `import com.google.firebase.appdistribution.gradle.firebaseAppDistribution` to this file! [WrongGradleMethod]
                    firebaseAppDistribution {
                    ~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
      )
      .expectFixDiffs(
        """
        Fix for build.gradle.kts line 15: Import com.google.firebase.appdistribution.gradle.firebaseAppDistribution:
        @@ -0,0 +1 @@
        +import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
        """
      )
  }

  fun testDependenciesWrongPlace() {
    lint()
      .files(
        kts(
            """
            android {
                flavorDimensions += "version"
                buildTypes {
                    getByName("debug") {
                        dependencies {
                          implementation("foo.bar:baz:1.0.0") // ERROR 1
                        }
                    }
                    getByName("release") {
                    }
                }
                productFlavors {
                    create("demo") {
                        dimension = "version"
                        dependencies {
                          implementation("foo.bar:baz:1.0.0") // ERROR 2
                        }
                    }
                }
            }
            dependencies {
              implementation("foo.bar:baz:1.0.0") // OK
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        build.gradle.kts:5: Error: Suspicious receiver type; this does not apply to build types. This will apply to a receiver of type `Project`, found in one of the enclosing lambdas. Make sure it's declared in the right place in the file. If you wanted a build type specific dependency, use `debugImplementation` rather than `implementation` in the top level `dependencies` block. [WrongGradleMethod]
                    dependencies {
                    ~~~~~~~~~~~~
        build.gradle.kts:15: Error: Suspicious receiver type; this does not apply to product flavors. This will apply to a receiver of type `Project`, found in one of the enclosing lambdas. Make sure it's declared in the right place in the file. If you wanted a product flavor specific dependency, use `demoImplementation` rather than `implementation` in the top level `dependencies` block. [WrongGradleMethod]
                    dependencies {
                    ~~~~~~~~~~~~
        2 errors
        """
      )
  }

  fun testDependenciesGroovy() {
    lint()
      .files(
        gradle(
            """
            android {
                buildTypes {
                    debug {
                        dependencies {
                          implementation("foo.bar:baz:1.0.0") // ERROR 1
                        }
                    }
                }
            }
            dependencies {
                implementation("foo.bar:baz:1.0.0") // OK 3
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        build.gradle:4: Error: Suspicious receiver type; this does not apply to build types. This will apply to a receiver of type `Project`, found in one of the enclosing lambdas. Make sure it's declared in the right place in the file. If you wanted a build type specific dependency, use `debugImplementation` rather than `implementation` in the top level `dependencies` block. [WrongGradleMethod]
                    dependencies {
                    ^
        1 error
        """
      )
  }

  fun testNegative() {
    lint()
      .files(
        kts(
            """
            import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
            plugins {
                id("com.google.firebase.appdistribution")
            }
            android {
                flavorDimensions += "version"
                buildTypes {
                    getByName("debug") {
                    }
                    getByName("release") {
                        // noinspection WrongGradleMethod
                        dependencies {
                          implementation("foo.bar:baz:1.0.0") // OK -- suppressed
                        }
                    }
                }
                productFlavors {
                    create("demo") {
                        dimension = "version"
                        firebaseAppDistribution {
                            releaseNotes = "Release notes for demo version"
                            testers = "demo@example.com"
                        }
                    }
                    getByName("debug") {
                        ndk { // OK
                            abiFilters.add("x86")
                        }
                    }
                    create("lollipop") {
                        minSdkVersion(21) // OK
                        minSdk = 21
                    }
                }
            }
           """
          )
          .indented(),
        // Stub to make import work
        kotlin(
            """
            package com.google.firebase.appdistribution.gradle
            @Suppress("UnusedReceiverParameter")
            fun com.android.build.api.dsl.ProductFlavor.firebaseAppDistribution(action: com.google.firebase.appdistribution.gradle.AppDistributionExtension.() -> kotlin.Unit): kotlin.Unit { /* compiled code */ }
            """
          )
          .indented(),
      )
      .run()
      .expectClean()
  }
}
