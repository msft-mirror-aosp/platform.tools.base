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
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.integration.common.fixture.DESUGAR_DEPENDENCY_VERSION
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.output.ApkSubject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThatApk
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.generateAarWithContent
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Properties
import org.gradle.api.JavaVersion
import org.junit.Rule
import org.junit.Test

/**
 * Integration test verifying Custom Test Suites with TEST_APK source type:
 * 1. Source delegation: compiles test suite sources into the test APK, excluding main app sources.
 * 2. Manifest creation config: generates instrumented test manifest targeting tested app with proper attributes (handleProfiling,
 *    functionalTest, testLabel) without merging main app activities.
 * 3. Dependency isolation: does not package the main app's runtime dependencies into the test APK.
 * 4. Native libraries packaging: the test APK manifest declares the android:extractNativeLibs value matching jniLibs.useLegacyPackaging.
 */
class TestSuiteApkIntegrationTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("com.test:toy-junit-engine:1.0")
          .addClasses(ToyJunitEngineForTesting::class.java, ToyTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", ToyJunitEngineForTesting::class.java.name)
        library(
          MavenRepoGenerator.Library(
            "com.example:suite-aar:1.0",
            "aar",
            generateAarWithContent(
              packageName = "com.example.suiteaar",
              resources =
                mapOf(
                  "values/strings.xml" to """<resources><string name="suite_aar_str">from suite aar</string></resources>""".toByteArray()
                ),
              extraFiles = mapOf("R.txt" to "int string suite_aar_str 0x7f040001".toByteArray()),
            ),
          )
        )
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication(":app") {
          android {
            namespace = "com.example.test"
            testOptions.customSuites.create("customTest") {
              it.useJunitEngine.apply {
                includeEngines.add("[engine:toy-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:toy-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
                inputs.add(AgpTestSuiteInputParameters.TEST_APKS)
              }
              // Configures this test suite to generate a standalone Test APK
              // targeting the debug variant of the main application.
              it.testApk {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          // Main app depends on :lib to test dependency isolation in the Test APK.
          dependencies { implementation(project(":lib")) }
          files {
            // Main application manifest defining an activity.
            // This activity must NOT be merged into the test APK's manifest.
            update("src/main/AndroidManifest.xml")
              .replaceWith(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application>
                        <activity android:name=".MainActivity" android:exported="true" />
                    </application>
                </manifest>
                """
                  .trimIndent()
              )
            // Main application classes: verify these are NOT compiled into the test APK.
            add(
              "src/main/java/com/example/test/MainActivity.java",
              """
              package com.example.test;
              public class MainActivity {}
              """
                .trimIndent(),
            )
            add(
              "src/main/java/com/example/test/MainAppClass.java",
              """
              package com.example.test;
              public class MainAppClass {}
              """
                .trimIndent(),
            )
            // Test suite source: must be compiled into the test APK via source delegation.
            add(
              "src/customTest/java/com/example/test/SuiteTestClass.java",
              """
              package com.example.test;
              public class SuiteTestClass {}
              """
                .trimIndent(),
            )
          }
        }
        androidLibrary(":lib") {
          android {
            namespace = "com.example.lib"
          }
          files {
            // Library dependency class: should only exist in the main app runtime classpath,
            // and must NOT be packaged into the test APK.
            add(
              "src/main/java/com/example/lib/LibClass.java",
              """
              package com.example.lib;
              public class LibClass {}
              """
                .trimIndent(),
            )
          }
        }
      }

  @Test
  fun testTestApkSourcesManifestAndDependencyIsolation() {
    // Packaging the test APK triggers compilation, manifest processing, and packaging
    // for the custom test suite.
    val result = rule.build.executor.run(":app:packageDebugCustomTest")

    // Verify expected tasks executed
    assertThat(result.didWorkTasks).contains(":app:compileDebugCustomTestJavaWithJavac")
    assertThat(result.didWorkTasks).contains(":app:processDebugCustomTestManifest")
    assertThat(result.didWorkTasks).contains(":app:packageDebugCustomTest")

    val testApk = customTestApk()

    // 1. Verify Class and Dependency Assertions via TruthHelper:
    assertThatApk(testApk).apply {
      // Source Delegation: Test suite's sources (SuiteTestClass) are compiled into the test APK,
      // but the main application's sources (MainAppClass, MainActivity) are excluded.
      containsClass("Lcom/example/test/SuiteTestClass;")
      doesNotContainClass("Lcom/example/test/MainAppClass;")
      doesNotContainClass("Lcom/example/test/MainActivity;")

      // Dependency Isolation: The main app's runtime dependencies (:lib -> LibClass)
      // are not bundled into the test APK classes (preventing dex collisions and duplicate classes).
      doesNotContainClass("Lcom/example/lib/LibClass;")
    }

    // 2. Verify Manifest Creation Config via ApkSubject:
    ApkSubject.assertThat(testApk) {
      // Test manifest targets the tested application, includes instrumented test attributes,
      // and does not merge main app activities.
      manifest().contains("android:targetPackage=\"com.example.test\"")
      manifest().contains("android:handleProfiling=false")
      manifest().contains("android:functionalTest=false")
      manifest().contains("android:label=\"Tests for com.example.test\"")
      manifest().doesNotContain("MainActivity")
    }
  }

  @Test
  fun testTestApkManifestMatchesDefaultJniLibsPackaging() {
    // From minSdk 23, native libraries are stored uncompressed by default, which the packager
    // only accepts when the test APK manifest sets android:extractNativeLibs="false".
    rule.build.androidApplication(":app").reconfigure { android { defaultConfig { minSdk = 24 } } }

    rule.build.executor.run(":app:packageDebugCustomTest")

    ApkSubject.assertThat(customTestApk()) { manifest().contains("android:extractNativeLibs=false") }
  }

  @Test
  fun testTestApkManifestMatchesLegacyJniLibsPackaging() {
    rule.build.androidApplication(":app").reconfigure {
      android {
        defaultConfig { minSdk = 24 }
        packaging { jniLibs { useLegacyPackaging = true } }
      }
    }

    rule.build.executor.run(":app:packageDebugCustomTest")

    ApkSubject.assertThat(customTestApk()) { manifest().contains("android:extractNativeLibs=true") }
  }

  @Test
  fun testTestApkContainsCoverageAgentWhenSuiteCollectsOnDeviceCoverage() {
    val build = rule.build {
      gradleProperties { add(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true) }
      androidApplication(":app") { android { testOptions.customSuites.named("customTest") { it.codeCoverage = true } } }
    }

    build.executor.run(":app:packageDebugCustomTest")

    // The engine attaches the agent from the test APK, so the attacher and the native agent must be packaged into it.
    assertThatApk(customTestApk()).apply {
      containsClass("Lcom/android/tools/coverage/CoverageAgentAttacher;")
      contains("lib/x86_64/coverage_agent.so")
      contains("lib/arm64-v8a/coverage_agent.so")
    }
  }

  @Test
  fun testTestApkDoesNotContainCoverageAgentWhenSuiteDoesNotCollectCoverage() {
    val build = rule.build { gradleProperties { add(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true) } }

    build.executor.run(":app:packageDebugCustomTest")

    assertThatApk(customTestApk()).apply {
      doesNotContainClass("Lcom/android/tools/coverage/CoverageAgentAttacher;")
      doesNotContain("lib/x86_64/coverage_agent.so")
    }
  }

  @Test
  fun testJavaSourceReferencesSuiteKotlinClass() {
    val build = rule.build {
      androidApplication(":app") {
        files {
          add(
            "src/customTest/kotlin/com/example/test/SuiteKotlinHelper.kt",
            """
            package com.example.test

            class SuiteKotlinHelper {
              fun value(): String = "ok"
            }
            """
              .trimIndent(),
          )
          add(
            "src/customTest/java/com/example/test/SuiteJavaCaller.java",
            """
            package com.example.test;

            public class SuiteJavaCaller {
              public String callKotlin() {
                return new SuiteKotlinHelper().value();
              }
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    build.executor.run(":app:packageDebugCustomTest")

    assertThatApk(customTestApk()).apply {
      containsClass("Lcom/example/test/SuiteKotlinHelper;")
      containsClass("Lcom/example/test/SuiteJavaCaller;")
    }
  }

  @Test
  fun testRunnerSynchronizedBetweenMergedManifestAndEngineInputs() {
    val build = rule.build {
      androidApplication(":app") {
        android {
          defaultConfig { testInstrumentationRunner = "com.example.DefaultRunner" }
          testOptions.customSuites.named("customTest") {
            it.useJunitEngine.inputs.clear()
            it.useJunitEngine.addInputProperty("android-test.instrumentation-runner-class", "com.example.SuiteRunner")
          }
        }
      }
    }

    build.executor.run(":app:packageDebugCustomTest", ":app:testDebugCustomTestT1TestSuite")

    ApkSubject.assertThat(customTestApk()) { manifest().contains("android:name=\"com.example.SuiteRunner\"") }
    val inputsFile =
      File(build.androidApplication(":app").buildDir.toFile(), "intermediates/debug/testDebugCustomTestT1TestSuite/junit_inputs.txt")
    val inputs = Properties().also { props -> inputsFile.reader(Charsets.UTF_8).use { props.load(it) } }
    assertThat(inputs.getProperty("android-test.instrumentation-runner-class")).isEqualTo("com.example.SuiteRunner")
  }

  @Test
  fun testOfflineJacocoTransformsAppWhenDeviceSuiteEnablesCoverage() {
    val build = rule.build {
      androidApplication(":app") { android { testOptions.customSuites.named("customTest") { it.codeCoverage = true } } }
    }

    val result = build.executor.run(":app:assembleDebug", ":app:packageDebugCustomTest")
    assertThat(result.didWorkTasks).contains(":app:jacocoDebug")
  }

  @Test
  fun testTestApkDesugarLibDexAppVsLibrary() {
    val build = rule.build {
      androidApplication(":app") {
        android {
          defaultConfig { minSdk = 21 }
          compileOptions {
            isCoreLibraryDesugaringEnabled = true
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
          }
        }
        dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:$DESUGAR_DEPENDENCY_VERSION") }
        files {
          update("src/customTest/java/com/example/test/SuiteTestClass.java")
            .replaceWith(
              """
              package com.example.test;
              import java.time.LocalDate;
              public class SuiteTestClass {
                public String date() {
                  return LocalDate.of(2026, 1, 1).toString();
                }
              }
              """
                .trimIndent()
            )
        }
      }
      androidLibrary(":lib") {
        android {
          defaultConfig { minSdk = 21 }
          compileOptions {
            isCoreLibraryDesugaringEnabled = true
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
          }
          testOptions.customSuites.create("customTest") {
            it.useJunitEngine.apply {
              includeEngines.add("[engine:toy-junit-engine-for-tests]")
              enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
              enginesDependencies.add("org.junit.platform:junit-platform-launcher")
              enginesDependencies.add("com.test:toy-junit-engine:1.0")
              enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
              inputs.add(AgpTestSuiteInputParameters.TEST_APKS)
            }
            it.testApk {}
            it.targetVariants.add("debug")
            it.targets.apply { create("t1") {} }
          }
        }
        dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:$DESUGAR_DEPENDENCY_VERSION") }
        files {
          add(
            "src/customTest/java/com/example/lib/LibDesugarTest.java",
            """
            package com.example.lib;
            import java.time.LocalDate;
            public class LibDesugarTest {
              public String date() {
                return LocalDate.of(2026, 1, 1).toString();
              }
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    val appResult = build.executor.run(":app:packageDebugCustomTest")
    assertThat(appResult.didWorkTasks).doesNotContain(":app:l8DexDesugarLibDebugCustomTest")
    assertThatApk(customTestApk()).doesNotContainClass("Lj$/time/LocalDate;")

    val libResult = build.executor.run(":lib:packageDebugCustomTest")
    assertThat(libResult.didWorkTasks).contains(":lib:l8DexDesugarLibDebugCustomTest")
    val libBuildDir = build.androidLibrary(":lib").buildDir.toFile()
    val libTestApk =
      checkNotNull(
        libBuildDir.walkTopDown().firstOrNull {
          it.name.endsWith(".apk") && it.name.contains("customTest", ignoreCase = true) && it.path.contains("debug")
        }
      ) {
        "No debug customTest APK found under $libBuildDir"
      }
    assertThatApk(libTestApk).containsClass("Lj$/time/LocalDate;")
  }

  @Test
  fun testTestApkGeneratesTestNamespaceRAndCompilesRReferences() {
    val build = rule.build {
      gradleProperties { add(BooleanOption.NON_TRANSITIVE_R_CLASS, true) }
      androidApplication(":app") {
        android {
          testOptions.customSuites.named("customTest") {
            it.testApk { dependencies.implementation.add("com.example:suite-aar:1.0") }
          }
        }
        files {
          add("src/main/res/values/strings.xml", """<resources><string name="app_str">from app</string></resources>""")
          add(
            "src/customTest/java/com/example/test/SuiteRJavaConsumer.java",
            """
            package com.example.test;

            public class SuiteRJavaConsumer {
              public Class<?> suiteR() {
                return com.example.test.test.R.class;
              }
              public int ids() {
                return com.example.test.R.string.app_str + com.example.suiteaar.R.string.suite_aar_str;
              }
            }
            """
              .trimIndent(),
          )
          add(
            "src/customTest/kotlin/com/example/test/SuiteRKotlinConsumer.kt",
            """
            package com.example.test

            class SuiteRKotlinConsumer {
              fun suiteR(): Class<*> = com.example.test.test.R::class.java
              fun ids(): Int = com.example.test.R.string.app_str + com.example.suiteaar.R.string.suite_aar_str
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    build.executor.run(":app:packageDebugCustomTest")

    assertThatApk(customTestApk()).apply {
      containsClass("Lcom/example/test/test/R;")
      containsClass("Lcom/example/test/SuiteRJavaConsumer;")
      containsClass("Lcom/example/test/SuiteRKotlinConsumer;")
      doesNotContainClass("Lcom/example/test/R;")
    }
  }

  @Test
  fun testTestApkHonorsCustomTestNamespaceAndTransitiveR() {
    val build = rule.build {
      gradleProperties { add(BooleanOption.NON_TRANSITIVE_R_CLASS, false) }
      androidApplication(":app") {
        android {
          testNamespace = "com.example.custom.testns"
          testOptions.customSuites.named("customTest") {
            it.testApk { dependencies.implementation.add("com.example:suite-aar:1.0") }
          }
        }
        files {
          add(
            "src/customTest/kotlin/com/example/test/SuiteCustomNsConsumer.kt",
            """
            package com.example.test

            class SuiteCustomNsConsumer {
              fun suiteAarStringId(): Int = com.example.custom.testns.R.string.suite_aar_str
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    build.executor.run(":app:packageDebugCustomTest")

    assertThatApk(customTestApk()).apply {
      containsClass("Lcom/example/custom/testns/R;")
      containsClass("Lcom/example/custom/testns/R\$string;")
      containsClass("Lcom/example/test/SuiteCustomNsConsumer;")
      doesNotContainClass("Lcom/example/test/R;")
    }
  }

  @Test
  fun testTestApkGeneratesSuiteLayoutResAndCompilesAppAndTestR() {
    val build = rule.build {
      androidApplication(":app") {
        files {
          add("src/main/res/layout/main_layout.xml", layoutWithTextView("main_text"))
          add("src/customTest/res/layout/test_layout_1.xml", layoutWithTextView("test_layout_1_textview"))
          add(
            "src/customTest/java/com/example/test/SuiteResJavaConsumer.java",
            """
            package com.example.test;

            public class SuiteResJavaConsumer {
              public int[] ids() {
                return new int[] {
                  com.example.test.R.id.main_text,
                  com.example.test.test.R.layout.test_layout_1,
                  com.example.test.test.R.id.test_layout_1_textview,
                };
              }
            }
            """
              .trimIndent(),
          )
          add(
            "src/customTest/kotlin/com/example/test/SuiteResKotlinConsumer.kt",
            """
            package com.example.test

            class SuiteResKotlinConsumer {
              fun ids(): IntArray = intArrayOf(
                com.example.test.R.id.main_text,
                com.example.test.test.R.layout.test_layout_1,
                com.example.test.test.R.id.test_layout_1_textview,
              )
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    build.executor.run(":app:packageDebugCustomTest")

    assertThatApk(customTestApk()).apply {
      contains("resources.arsc")
      contains("res/layout/test_layout_1.xml")
      containsClass("Lcom/example/test/SuiteResJavaConsumer;")
      containsClass("Lcom/example/test/SuiteResKotlinConsumer;")
      hasClass("Lcom/example/test/test/R\$layout;").that().hasField("test_layout_1")
      hasClass("Lcom/example/test/test/R\$id;").that().hasField("test_layout_1_textview")
    }
  }

  @Test
  fun testTestApkCompilesAndPackagesSuiteLayoutResWhenCompiledAsLibrary() {
    val build = rule.build {
      androidLibrary(":lib") {
        android {
          testOptions.customSuites.create("customTest") {
            it.useJunitEngine.apply {
              includeEngines.add("[engine:toy-junit-engine-for-tests]")
              enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
              enginesDependencies.add("org.junit.platform:junit-platform-launcher")
              enginesDependencies.add("com.test:toy-junit-engine:1.0")
              enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
              inputs.add(AgpTestSuiteInputParameters.TEST_APKS)
            }
            it.testApk {}
            it.targetVariants.add("debug")
            it.targets.apply { create("t1") {} }
          }
        }
        files {
          add("src/main/res/layout/lib_layout.xml", layoutWithTextView("lib_text"))
          add("src/customTest/res/layout/test_layout_1.xml", layoutWithTextView("test_layout_1_textview"))
          add(
            "src/customTest/java/com/example/lib/SuiteLibResJavaConsumer.java",
            """
            package com.example.lib;

            public class SuiteLibResJavaConsumer {
              public int[] ids() {
                return new int[] {
                  com.example.lib.R.id.lib_text,
                  com.example.lib.test.R.layout.test_layout_1,
                  com.example.lib.test.R.id.test_layout_1_textview,
                };
              }
            }
            """
              .trimIndent(),
          )
          add(
            "src/customTest/kotlin/com/example/lib/SuiteLibResKotlinConsumer.kt",
            """
            package com.example.lib

            class SuiteLibResKotlinConsumer {
              fun ids(): IntArray = intArrayOf(
                com.example.lib.R.id.lib_text,
                com.example.lib.test.R.layout.test_layout_1,
                com.example.lib.test.R.id.test_layout_1_textview,
              )
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    build.executor.run(":lib:packageDebugCustomTest")

    assertThatApk(customTestApk(build.androidLibrary(":lib").buildDir.toFile())).apply {
      contains("resources.arsc")
      contains("res/layout/test_layout_1.xml")
      containsClass("Lcom/example/lib/SuiteLibResJavaConsumer;")
      containsClass("Lcom/example/lib/SuiteLibResKotlinConsumer;")
      hasClass("Lcom/example/lib/test/R\$layout;").that().hasField("test_layout_1")
      hasClass("Lcom/example/lib/test/R\$id;").that().hasField("test_layout_1_textview")
    }
  }

  @Test
  fun testTestApkCustomTestNamespaceWithSuiteLayoutRes() {
    val build = rule.build {
      androidApplication(":app") {
        android { testNamespace = "com.example.custom.testns" }
        files {
          add("src/main/res/layout/main_layout.xml", layoutWithTextView("main_text"))
          add("src/customTest/res/layout/test_layout_1.xml", layoutWithTextView("test_layout_1_textview"))
          add(
            "src/customTest/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application>
                <activity android:name=".TestActivity" />
              </application>
            </manifest>
            """
              .trimIndent(),
          )
          add(
            "src/customTest/kotlin/com/example/test/SuiteCustomNsResConsumer.kt",
            """
            package com.example.test

            class SuiteCustomNsResConsumer {
              fun ids(): IntArray = intArrayOf(
                com.example.test.R.id.main_text,
                com.example.custom.testns.R.layout.test_layout_1,
                com.example.custom.testns.R.id.test_layout_1_textview,
              )
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    build.executor.run(":app:packageDebugCustomTest")

    val testApk = customTestApk()
    assertThatApk(testApk).apply {
      contains("resources.arsc")
      contains("res/layout/test_layout_1.xml")
      containsClass("Lcom/example/test/SuiteCustomNsResConsumer;")
      hasClass("Lcom/example/custom/testns/R\$layout;").that().hasField("test_layout_1")
      hasClass("Lcom/example/custom/testns/R\$id;").that().hasField("test_layout_1_textview")
      doesNotContainClass("Lcom/example/test/R;")
    }
    ApkSubject.assertThat(testApk) {
      manifest().contains("android:name=\"com.example.custom.testns.TestActivity\"")
      manifest().doesNotContain("com.example.test.test.TestActivity")
    }
  }

  @Test
  fun testTestApkIncrementalAddModifyRemoveSuiteLayoutRes() {
    val build = rule.build {
      androidApplication(":app") {
        files {
          add("src/customTest/res/layout/test_layout_1.xml", layoutWithTextView("test_layout_1_textview"))
          add(
            "src/customTest/kotlin/com/example/test/SuiteIncrementalResConsumer.kt",
            """
            package com.example.test

            class SuiteIncrementalResConsumer {
              fun layout(): Int = com.example.test.test.R.layout.test_layout_1
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    build.executor.run(":app:packageDebugCustomTest")
    assertThatApk(customTestApk()).apply {
      contains("res/layout/test_layout_1.xml")
      doesNotContain("res/layout/test_layout_2.xml")
      hasClass("Lcom/example/test/test/R\$layout;").that().apply {
        hasField("test_layout_1")
        doesNotHaveField("test_layout_2")
      }
      hasClass("Lcom/example/test/test/R\$id;").that().apply {
        hasField("test_layout_1_textview")
        doesNotHaveField("test_layout_2_textview")
      }
    }

    val appFiles = build.androidApplication(":app").files
    appFiles.add("src/customTest/res/layout/test_layout_2.xml", layoutWithTextView("test_layout_2_textview"))
    build.executor.run(":app:packageDebugCustomTest")
    assertThatApk(customTestApk()).apply {
      contains("res/layout/test_layout_1.xml")
      contains("res/layout/test_layout_2.xml")
      hasClass("Lcom/example/test/test/R\$layout;").that().apply {
        hasField("test_layout_1")
        hasField("test_layout_2")
      }
      hasClass("Lcom/example/test/test/R\$id;").that().apply {
        hasField("test_layout_1_textview")
        hasField("test_layout_2_textview")
      }
    }

    appFiles.update("src/customTest/res/layout/test_layout_2.xml").searchAndReplace("test_layout_2_textview", "test_layout_3_textview")
    build.executor.run(":app:packageDebugCustomTest")
    assertThatApk(customTestApk()).apply {
      hasClass("Lcom/example/test/test/R\$id;").that().apply {
        hasField("test_layout_1_textview")
        doesNotHaveField("test_layout_2_textview")
        hasField("test_layout_3_textview")
      }
    }

    appFiles.remove("src/customTest/res/layout/test_layout_2.xml")
    build.executor.run(":app:packageDebugCustomTest")
    assertThatApk(customTestApk()).apply {
      contains("res/layout/test_layout_1.xml")
      doesNotContain("res/layout/test_layout_2.xml")
      hasClass("Lcom/example/test/test/R\$layout;").that().apply {
        hasField("test_layout_1")
        doesNotHaveField("test_layout_2")
      }
      hasClass("Lcom/example/test/test/R\$id;").that().apply {
        hasField("test_layout_1_textview")
        doesNotHaveField("test_layout_2_textview")
        doesNotHaveField("test_layout_3_textview")
      }
    }
  }

  private fun layoutWithTextView(textViewId: String): String =
    """
    <?xml version="1.0" encoding="utf-8"?>
    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">
      <TextView
          android:id="@+id/$textViewId"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content"
          android:text="Suite layout" />
    </LinearLayout>
    """
      .trimIndent()

  private fun customTestApk(buildDir: File = rule.build.androidApplication(":app").buildDir.toFile()): File {
    val testApk = buildDir.walkTopDown().firstOrNull { it.name.endsWith(".apk") && it.name.contains("customTest", ignoreCase = true) }
    return checkNotNull(testApk) { "No customTest APK found under $buildDir" }
  }
}
