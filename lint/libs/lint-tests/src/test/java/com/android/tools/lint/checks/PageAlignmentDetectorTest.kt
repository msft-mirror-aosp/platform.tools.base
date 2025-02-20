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
import com.android.tools.lint.detector.api.Detector

class PageAlignmentDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return PageAlignmentDetector()
  }

  fun testDocumentationExample() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            dependencies {
                implementation("org.tensorflow:tensorflow-lite:2.16.1")
            }
            """,
          )
          .indented(),
        jniLibArm64v8a,
        jniLibx86_64,
        jniLibArmeAbiv7a,
        jniLibX86,
      )
      .run()
      .expect(
        """
        build.gradle:2: Warning: The native library arm64-v8a/libtensorflowlite_jni.so (from org.tensorflow:tensorflow-lite:2.16.1) is not 16 KB aligned [Aligned16KB]
            implementation("org.tensorflow:tensorflow-lite:2.16.1")
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testAlignmentOk() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            dependencies {
                implementation("org.tensorflow:tensorflow-lite:2.16.1")
            }
            """,
          )
          .indented(),
        jniLibArmeAbiv7a,
        jniLibX86,
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
                implementation(libs.tensorflow.lite)
            }
            """,
          )
          .indented(),
        gradleToml(
          """
          [versions]
          agp = "8.9.0-alpha07"
          tensorflowLite = "2.16.1"

          [libraries]
          tensorflow-lite = { module = "org.tensorflow:tensorflow-lite", version.ref = "tensorflowLite" }
          """
        ),
        jniLibArm64v8a,
        jniLibx86_64,
        jniLibArmeAbiv7a,
        jniLibX86,
      )
      .run()
      .expect(
        """
        ../gradle/libs.versions.toml:7: Warning: The native library arm64-v8a/libtensorflowlite_jni.so (from org.tensorflow:tensorflow-lite:2.16.1) is not 16 KB aligned [Aligned16KB]
                  tensorflow-lite = { module = "org.tensorflow:tensorflow-lite", version.ref = "tensorflowLite" }
                                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
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
            jniLibArm64v8a,
            jniLibx86_64,
            jniLibArmeAbiv7a,
            jniLibX86,
          )
          .withDependencyGraph(
            """
            +--- my.indirect.dependency:myname:1.2.3
            |    \--- org.tensorflow:tensorflow-lite:2.16.1
            +--- commons-logging:commons-logging:1.2
            """
              .trimIndent()
          )
      )
      .run()
      .expect(
        """
        build/intermediates/exploded-aar/org.tensorflow/tensorflow-lite/2.16.1/jni/arm64-v8a/libtensorflowlite_jni.so: Warning: The native library arm64-v8a/libtensorflowlite_jni.so (from org.tensorflow:tensorflow-lite:2.16.1) is not 16 KB aligned [Aligned16KB]
        0 errors, 1 warnings
        """
      )
  }

  fun test395836302() {
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
            jniLibArm64v8a,
            jniLibx86_64,
            jniLibArmeAbiv7a,
            jniLibX86,
            // Make a second unique library so we can check multiple transitive
            // libraries not found directly referenced in the build file
            base64gzip(
              jniLibArm64v8a.targetRelativePath.replace("/tensorflow-lite/", "/tensorflow-lite2/"),
              jniLibArm64v8aEncoded,
            ),
          )
          .withDependencyGraph(
            """
            +--- my.indirect.dependency:myname:1.2.3
            |    +--- org.tensorflow:tensorflow-lite:2.16.1
            |    \--- org.tensorflow:tensorflow-lite2:2.16.1
            +--- commons-logging:commons-logging:1.2
            """
              .trimIndent()
          )
      )
      .run()
      .expect(
        """
        build/intermediates/exploded-aar/org.tensorflow/tensorflow-lite2/2.16.1/jni/arm64-v8a/libtensorflowlite_jni.so: Warning: The native library arm64-v8a/libtensorflowlite_jni.so (from org.tensorflow:tensorflow-lite2:2.16.1) is not 16 KB aligned [Aligned16KB]
        build/intermediates/exploded-aar/org.tensorflow/tensorflow-lite/2.16.1/jni/arm64-v8a/libtensorflowlite_jni.so: Warning: The native library arm64-v8a/libtensorflowlite_jni.so (from org.tensorflow:tensorflow-lite:2.16.1) is not 16 KB aligned [Aligned16KB]
        0 errors, 2 warnings
        """
      )
  }

  fun testKnownAndroidX() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            dependencies {
                implementation("com.google.android.games:memory-advice:0.23") // ERROR
            }
            """,
          )
          .indented(),
        base64gzip(
          "build/intermediates/exploded-aar/com.google.android.games/memory-advice/0.23/jni/x86_64/libtry-alloc-lib.so",
          """
          H4sIAAAAAAAA/6t39XFjYmRkgAFmBjsGBI+BwQFKv/B1RRKzYOACkjIM0gxs
          QD4Lkjp02oAJleaAioPsYGXADRSWuKDQDAIIfSA7GTZAxdHoigQGFBpdX4UA
          xB8VCqj0Amuo+zJQ9TFB9a3ggKhDpz9AAwtGw/wX9LQkhYUIdzIUINwHAgFQ
          fRwPmSHmodE1FxlQaBaovkCgPjYG0gELFBdA4wednsGASjOh6eOAOAuDVmBA
          pWHuBABUNm+wcAIAAA==
          """,
        ),
      )
      .run()
      .expect(
        """
        build.gradle:2: Warning: The native library x86_64/libtry-alloc-lib.so (from com.google.android.games:memory-advice:0.23) is not 16 KB aligned [Aligned16KB]
            implementation("com.google.android.games:memory-advice:0.23") // ERROR
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
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
                implementation("org.tensorflow:tensorflow-lite:2.16.1")
            }
            """,
          )
          .indented(),
        jniLibArm64v8a,
        jniLibx86_64,
        jniLibArmeAbiv7a,
        jniLibX86,
      )
      .incremental("build.gradle")
      .run()
      .expect(
        """
        build.gradle:2: Warning: The native library arm64-v8a/libtensorflowlite_jni.so (from org.tensorflow:tensorflow-lite:2.16.1) is not 16 KB aligned [Aligned16KB]
            implementation("org.tensorflow:tensorflow-lite:2.16.1")
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
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
                implementation(libs.tensorflow.lite)
            }
            """,
          )
          .indented(),
        gradleToml(
          """
          [versions]
          agp = "8.9.0-alpha07"
          tensorflowLite = "2.16.1"

          [libraries]
          tensorflow-lite = { module = "org.tensorflow:tensorflow-lite", version.ref = "tensorflowLite" }
          """
        ),
        jniLibArm64v8a,
        jniLibx86_64,
        jniLibArmeAbiv7a,
        jniLibX86,
      )
      .incremental("../gradle/libs.versions.toml")
      .run()
      .expect(
        """
        ../gradle/libs.versions.toml:7: Warning: The native library arm64-v8a/libtensorflowlite_jni.so (from org.tensorflow:tensorflow-lite:2.16.1) is not 16 KB aligned [Aligned16KB]
                  tensorflow-lite = { module = "org.tensorflow:tensorflow-lite", version.ref = "tensorflowLite" }
                                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  // truncated to just the first 568 bytes (base64 and gzipped) --
  // that's all the alignment utility will look at
  private val jniLibArm64v8aEncoded =
    """
      H4sIAAAAAAAA/6t39XFjYmRkgAFmhu0MCB4DgwOUPvHAGEnMgoETSMowSDOw
      AfksSOrQ6R+MqDQHVBzEZWXADSbYGaPQDAIIfWxgC6DiaHRFMgMKja6vYjFU
      3WZUusEaou4FOyOKPiaovoLJEHXo9Aeo8g9o/gt6WpLCQoQ7GQoQ7gOBAKg+
      i5nMYD46/eYUAwrNAtUXCNTHxkA6YIFiCyYIH53ewYBKw+wDAE9kGSg4AgAA
      """

  private val jniLibArm64v8a =
    base64gzip(
      "build/intermediates/exploded-aar/org.tensorflow/tensorflow-lite/2.16.1/jni/arm64-v8a/libtensorflowlite_jni.so",
      jniLibArm64v8aEncoded,
    )

  private val jniLibx86_64 =
    base64gzip(
      "build/intermediates/exploded-aar/org.tensorflow/tensorflow-lite/2.16.1/jni/x86_64/libtensorflowlite_jni.so",
      // truncated to just the first 624 bytes (base64 and gzipped) --
      // that's all the alignment utility will look at
      """
      H4sIAAAAAAAA/6t39XFjYmRkgAFmBjsGBI+BwQFKv/B1RRKzYOACkjIM0gxs
      QD4Lkjp02oAJleaAioPsYGXADRSWuKDQDAIIfSA7GTZAxdHoigQGFBpdX4UA
      xB8VCqj0Amuo+zJQ9TFB9a3ggKhDpz9AAwtGw/wX9LQkhYUIdzIUINwHAgFQ
      fRwPmSHmodE1FxlQaBaovkCgPjYG0gELFBdA4wednsGASjOh6eOAOAuDVmBA
      pWHuBABUNm+wcAIAAA==
      """,
    )

  private val jniLibArmeAbiv7a =
    base64gzip(
      "build/intermediates/exploded-aar/org.tensorflow/tensorflow-lite/2.16.1/jni/armeabi-v7a/libtensorflowlite_jni.so",
      // Just the first 6 bytes -- that's all the alignment utility will look at
      """
      H4sIAAAAAAAA/6t39XFjZAQArt5sEAYAAAA=
      """,
    )

  private val jniLibX86 =
    base64gzip(
      "build/intermediates/exploded-aar/org.tensorflow/tensorflow-lite/2.16.1/jni/x86/libtensorflowlite_jni.so",
      // Just the first 6 bytes -- that's all the alignment utility will look at
      """
      H4sIAAAAAAAA/6t39XFjZAQArt5sEAYAAAA=
      """,
    )
}
