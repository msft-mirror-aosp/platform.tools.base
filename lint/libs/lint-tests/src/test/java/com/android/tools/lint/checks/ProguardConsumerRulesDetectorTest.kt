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

import com.android.tools.lint.checks.infrastructure.TestFiles.gradleToml
import com.android.tools.lint.checks.infrastructure.TestFiles.proguard
import com.android.tools.lint.detector.api.Detector

class ProguardConsumerRulesDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return ProguardConsumerRulesDetector()
  }

  fun testDocumentationExample() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            dependencies {
                implementation("com.example.badkeep:badkeep:1.0.1")
            }
            """,
          )
          .indented(),
        dontOptimize,
      )
      .run()
      .expect(
        """
        build.gradle:2: Warning: The consumer keep rules at 1.0.1/proguard.pro (from com.example.badkeep:badkeep:1.0.1) contains a global option which should not be specified in library consumer rules: -dontoptimize [GlobalOptionInConsumerRules]
            implementation("com.example.badkeep:badkeep:1.0.1")
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testRepackageClasses() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            dependencies {
                implementation("com.example.badkeep:badkeep:1.0.1")
            }
            """,
          )
          .indented(),
        repackageclasses,
      )
      .run()
      .expect(
        """
        build.gradle:2: Warning: The consumer keep rules at 1.0.1/proguard.pro (from com.example.badkeep:badkeep:1.0.1) contains a global option which should not be specified in library consumer rules without an argument: -repackageclasses [GlobalOptionInConsumerRules]
            implementation("com.example.badkeep:badkeep:1.0.1")
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testBenignKeepRules() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            dependencies {
                implementation("com.example.badkeep:badkeep:1.0.1")
            }
            """,
          )
          .indented(),
        benign,
      )
      .run()
      .expectClean()
  }

  fun testNoKeepRules() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            dependencies {
                implementation("com.example.badkeep:badkeep:1.0.1")
            }
            """,
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testVersionCatalog() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            dependencies {
                implementation(libs.badkeep)
            }
            """,
          )
          .indented(),
        gradleToml(
          """
          [versions]
          agp = "8.9.0-alpha07"
          badKeepVersion = "1.0.1"

          [libraries]
          badkeep = { module = "com.example.badkeep:badkeep", version.ref = "badKeepVersion" }
          """
        ),
        dontOptimize,
      )
      .run()
      .expect(
        """
        ../gradle/libs.versions.toml:7: Warning: The consumer keep rules at 1.0.1/proguard.pro (from com.example.badkeep:badkeep:1.0.1) contains a global option which should not be specified in library consumer rules: -dontoptimize [GlobalOptionInConsumerRules]
                  badkeep = { module = "com.example.badkeep:badkeep", version.ref = "badKeepVersion" }
                             ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
  }

  fun testTransitiveDependency() {
    lint()
      .projects(
        project(
            gradle(
                "build.gradle",
                """
                dependencies {
                    implementation 'my.indirect.dependency:myname:1.2.3'
                }
                """,
              )
              .indented(),
            dontOptimize,
          )
          .withDependencyGraph(
            """
            +--- my.indirect.dependency:myname:1.2.3
            |    \--- com.example.badkeep:badkeep:1.0.1
            +--- commons-logging:commons-logging:1.2
            """
              .trimIndent()
          )
      )
      .run()
      .expect(
        """
        build/intermediates/exploded-aar/com.example.badkeep/badkeep/1.0.1/proguard.pro: Warning: The consumer keep rules at 1.0.1/proguard.pro (from com.example.badkeep:badkeep:1.0.1) contains a global option which should not be specified in library consumer rules: -dontoptimize [GlobalOptionInConsumerRules]
        0 errors, 1 warnings
        """
      )
  }

  fun testGradleInEditor() {
    // Test what happens when the lint check is running while editing a Gradle file
    // (see the below `.incremental` call).
    // (Same test scenario as testDocumentationExample.)
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            dependencies {
                implementation("com.example.badkeep:badkeep:1.0.1")
            }
            """,
          )
          .indented(),
        dontOptimize,
      )
      .incremental("build.gradle")
      .run()
      .expect(
        """
        build.gradle:2: Warning: The consumer keep rules at 1.0.1/proguard.pro (from com.example.badkeep:badkeep:1.0.1) contains a global option which should not be specified in library consumer rules: -dontoptimize [GlobalOptionInConsumerRules]
            implementation("com.example.badkeep:badkeep:1.0.1")
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testVersionCatalogInEditor() {
    // Like testVersionCatalog but with lint running in incremental mode
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            dependencies {
                implementation(libs.badkeep)
            }
            """,
          )
          .indented(),
        gradleToml(
          """
          [versions]
          agp = "8.9.0-alpha07"
          badKeepVersion = "1.0.1"

          [libraries]
          badkeep = { module = "com.example.badkeep:badkeep", version.ref = "badKeepVersion" }
          """
        ),
        dontOptimize,
      )
      .incremental("../gradle/libs.versions.toml")
      .run()
      .expect(
        """
        ../gradle/libs.versions.toml:7: Warning: The consumer keep rules at 1.0.1/proguard.pro (from com.example.badkeep:badkeep:1.0.1) contains a global option which should not be specified in library consumer rules: -dontoptimize [GlobalOptionInConsumerRules]
                  badkeep = { module = "com.example.badkeep:badkeep", version.ref = "badKeepVersion" }
                             ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  private val dontOptimize =
    proguard("build/intermediates/exploded-aar/com.example.badkeep/badkeep/1.0.1/proguard.pro", proguard = "-dontoptimize")
  private val repackageclasses =
    proguard("build/intermediates/exploded-aar/com.example.badkeep/badkeep/1.0.1/proguard.pro", proguard = "-repackageclasses")
  private val benign =
    proguard(
      // "com.example.badkeep:badkeep:1.0.1"
      "build/intermediates/exploded-aar/com.example.badkeep/badkeep/1.0.1/proguard.pro",
      proguard = "# -dontoptimize",
    )
}
