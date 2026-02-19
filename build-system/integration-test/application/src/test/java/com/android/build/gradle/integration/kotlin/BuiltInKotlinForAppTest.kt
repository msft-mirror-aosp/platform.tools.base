/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.kotlin

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.testutils.truth.PathSubject
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BuiltInKotlinForAppTest(private val useLatestKgpVersion: Boolean) {

  companion object {

    @Parameterized.Parameters(name = "useLatestKgpVersion_{0}") @JvmStatic fun parameters() = listOf(false, true)
  }

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication { HelloWorldAndroid.setupKotlin(files) }
      useLatestKgpVersion = this@BuiltInKotlinForAppTest.useLatestKgpVersion
    }

  @Test
  fun testKotlinClassesInApk() {
    val build =
      rule.build {
        androidApplication {
          files {
            add(
              "src/main/java/com/foo/application/AppFoo.kt",
              // language=kotlin
              """
              package com.foo.application
              class AppFoo
              """
                .trimIndent(),
            )
            add(
              "src/main/kotlin/com/foo/application/KotlinAppFoo.kt",
              // language=kotlin
              """
              package com.foo.application
              class KotlinAppFoo
              """
                .trimIndent(),
            )
          }
        }
      }

    build.executor.run(":app:assembleDebug")
    build.androidApplication().assertApk(ApkSelector.DEBUG) {
      classes()
        .containsExactly(
          "com/foo/application/AppFoo",
          "com/foo/application/KotlinAppFoo",
          "pkg/name/app/HelloWorld",
          "pkg/name/app/R\$",
          "kotlin/",
          "org/intellij/",
          "org/jetbrains/",
        )
    }
  }

  @Test
  fun testKotlinClassesInTestApk() {
    val build =
      rule.build {
        androidApplication {
          files {
            add(
              "src/androidTest/java/AppFooTest.kt",
              // language=kotlin
              """
              package com.foo.application
              class AppFooTest
              """
                .trimIndent(),
            )
            add(
              "src/androidTest/kotlin/KotlinAppFooTest.kt",
              // language=kotlin
              """
              package com.foo.application
              class KotlinAppFooTest
              """
                .trimIndent(),
            )
          }
        }
      }

    build.executor.run(":app:assembleDebugAndroidTest")
    build.androidApplication().assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
      classes().containsExactly("com/foo/application/AppFooTest", "com/foo/application/KotlinAppFooTest", "pkg/name/app/test/R")
    }
  }

  @Test
  fun testUnitTests() {
    val build =
      rule.build {
        androidApplication {
          dependencies { testImplementation("junit:junit:4.12") }

          files {
            add(
              "src/test/kotlin/AppFooTest.kt",
              // language=kotlin
              """
              package com.foo.application.test

              class AppFooTest {
                @org.junit.Test
                fun testSample() {}
              }
              """
                .trimIndent(),
            )
          }
        }
      }

    build.executor.run(":app:testDebug")
    val app = build.androidApplication()
    val testResults = app.buildDir.resolve("test-results/testDebugUnitTest/TEST-com.foo.application.test.AppFooTest.xml")
    PathSubject.assertThat(testResults).exists()
  }

  @Test
  fun testInternalModifierAccessibleFromTests() {
    val build =
      rule.build {
        androidApplication {
          files {
            add(
              "src/main/java/com/foo/application/AppFoo.kt",
              // language=kotlin
              """
              package com.foo.application
              class AppFoo {
                internal fun bar() {}
              }
              """
                .trimIndent(),
            )
            add(
              "src/test/com/foo/application/AppFooTest.kt",
              // language=kotlin
              """
              package com.foo.application
              class AppFooTest {
                init { AppFoo().bar() }
              }
              """
                .trimIndent(),
            )
          }
        }
      }

    build.executor.run(":app:assembleDebugUnitTest")
  }

  @Test
  fun testAppCompilesAgainstKotlinClassesFromDependency() {
    val build =
      rule.build {
        androidLibrary {
          applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

          HelloWorldAndroid.setupKotlin(files)

          files {
            add(
              "src/main/java/com/foo/library/LibFoo.kt",
              // language=kotlin
              """
              package com.foo.library
              open class LibFoo
              """
                .trimIndent(),
            )
          }
        }
        androidApplication {
          files {
            add(
              "src/main/kotlin/com/foo/application/AppFoo.kt",
              // language=kotlin
              """
              package com.foo.application
              class AppFoo: com.foo.library.LibFoo()
              """
                .trimIndent(),
            )
          }
          dependencies { api(project(":lib")) }
        }
      }

    build.executor.run(":app:assembleDebug")
    build.androidApplication().assertApk(ApkSelector.DEBUG) {
      classes()
        .containsExactly(
          "com/foo/application/AppFoo",
          "com/foo/library/LibFoo",
          "pkg/name/app/HelloWorld",
          "pkg/name/app/R\$",
          "pkg/name/lib/HelloWorld",
          "pkg/name/lib/R\$",
          "kotlin/",
          "org/intellij/",
          "org/jetbrains/",
        )
    }
  }

  @Test
  fun testKotlinAndJavaCrossReferences() {
    val build =
      rule.build {
        androidApplication {
          files {
            add(
              "src/main/java/com/foo/application/AppJavaFoo.java",
              // language=java
              """
              package com.foo.application;
              public class AppJavaFoo {
                String prop = new AppKotlinBar().getAppJavaFooClassName();
              }
              """
                .trimIndent(),
            )
            add(
              "src/main/java/com/foo/application/AppKotlinFoo.kt",
              // language=kotlin
              """
              package com.foo.application
              class AppKotlinFoo: AppJavaFoo()
              """
                .trimIndent(),
            )
            add(
              "src/main/java/com/foo/application/AppKotlinBar.kt",
              // language=kotlin
              """
              package com.foo.application
              class AppKotlinBar {
                val appJavaFooClassName = AppJavaFoo::class.java.name
              }
              """
                .trimIndent(),
            )
          }
        }
      }

    build.executor.run(":app:assembleDebug")
    build.androidApplication().assertApk(ApkSelector.DEBUG) {
      classes()
        .containsExactly(
          "com/foo/application/AppJavaFoo",
          "com/foo/application/AppKotlinFoo",
          "com/foo/application/AppKotlinBar",
          "pkg/name/app/HelloWorld",
          "pkg/name/app/R\$",
          "kotlin/",
          "org/intellij/",
          "org/jetbrains/",
        )
    }
  }

  @Test
  fun testExplicitApiModeStrictForMain() {
    val build =
      rule.build {
        androidApplication {
          files {
            add(
              "src/main/kotlin/com/foo/application/KotlinAppFoo.kt",
              // language=kotlin
              """
              package com.foo.application

              // This will cause the build to fail because it's missing an explicit
              // visibility modifier.
              fun publicFunction() {}
              """
                .trimIndent(),
            )
          }
          kotlin { explicitApi() }
        }
      }

    build.executor.expectFailure().run(":app:compileDebugKotlin").assertErrorContains("Visibility must be specified in explicit API mode")
  }

  @Test
  fun testExplicitApiModeDisabledOnUnitTest() {
    val build =
      rule.build {
        androidApplication {
          dependencies { testImplementation("junit:junit:4.12") }

          files {
            add(
              "src/test/kotlin/AppFooTest.kt",
              // language=kotlin
              """
              package com.foo.application.test

              class AppFooTest {
                @org.junit.Test
                fun testSample() {}
              }
              """
                .trimIndent(),
            )
          }
          kotlin { explicitApi() }
        }
      }
    build.executor.expectFailure().run(":app:compileDebugUnitTestKotlin")
  }

  @Test
  fun testExplicitApiModeDisabledOnAndroidTest() {
    val build =
      rule.build {
        androidApplication {
          dependencies { testImplementation("junit:junit:4.12") }

          files {
            add(
              "src/androidTest/java/AppFooTest.kt",
              // language=kotlin
              """
              package com.foo.application
              class AppFooTest
              """
                .trimIndent(),
            )
          }
          kotlin { explicitApi() }
        }
      }
    build.executor.expectFailure().run(":app:compileDebugAndroidHostTestKotlin")
  }

  /** Regression test for b/338596003 */
  @Test
  fun testKotlinAttributeSetup() {
    val build =
      rule.build {
        androidApplication {
          android {
            defaultConfig { minSdk = 21 }
            dependencies { implementation("androidx.compose.ui:ui-tooling-preview:1.6.5") }
          }
        }
      }

    // Test that kotlin compilation completes successfully
    build.executor.run(":app:compileDebugKotlin")
  }

  @Test
  fun `test inconsistent JVM targets between Java and Kotlin compile tasks`() { // b/408242956
    val build = rule.build { androidApplication { kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } } } }
    build.executor
      .expectFailure()
      .run(":app:compileDebugJavaWithJavac")
      .assertErrorContains("Inconsistent JVM targets between Java and Kotlin compile tasks: 11 and 17.")
  }

  @Test
  fun testKotlinCompilerOptionsDsl() {
    val build =
      rule.build {
        androidApplication {
          // Add some kotlin code so that `compileDebugKotlin` task isn't skipped.
          files.add(
            "src/main/kotlin/KotlinAppFoo.kt",
            // language=kotlin
            """
            package com.foo.application
            class KotlinAppFoo
            """
              .trimIndent(),
          )
          // Set some values in the built-in Kotlin DSL and check that the values flow to the task
          kotlin {
            compilerOptions {
              moduleName.set("foo")
              languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
            }
          }

          pluginCallbacks += KotlinTaskCallback::class.java
        }
      }

    val result = build.executor.run(":app:compileDebugKotlin")
    assertThat(result.didWorkTasks).contains(":app:compileDebugKotlin")
  }

  class KotlinTaskCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      project.afterEvaluate {
        project.tasks.named("compileDebugKotlin") {
          it.doLast { task ->
            task as KotlinCompile
            val moduleName = task.compilerOptions.moduleName.get()
            if (moduleName != "foo") {
              throw RuntimeException("Unexpected module name: $moduleName")
            }
            val languageVersion = task.compilerOptions.languageVersion.get()
            if (languageVersion != org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9) {
              throw RuntimeException("Unexpected app language version: $languageVersion")
            }
          }
        }
      }
    }
  }

  @Test
  fun testKotlinSourceSets() {
    val build =
      rule.build {
        androidApplication {
          // Add some custom source directories.
          files {
            add(
              "src/fooMain/kotlin/FooMain.kt",
              // language=kotlin
              """
              package com.foo.application
              class FooMain {}
              """
                .trimIndent(),
            )
            add(
              "src/fooDebug/kotlin/FooDebug.kt",
              // language=kotlin
              """
              package com.foo.application
              class FooDebug {}
              """
                .trimIndent(),
            )
            add(
              "src/fooAndroidTest/kotlin/FooAndroidTest.kt",
              // language=kotlin
              """
              package com.foo.application
              class FooAndroidTest {}
              """
                .trimIndent(),
            )
          }
          // Add the custom source directories to the source sets.
          android {
            sourceSets.named("main") { it.kotlin.directories += "src/fooMain/kotlin" }
            sourceSets.named("debug") { it.kotlin.directories += "src/fooDebug/kotlin" }
            sourceSets.named("androidTest") { it.kotlin.directories += "src/fooAndroidTest/kotlin" }
          }
        }
      }

    // Run Kotlin compilation tasks and check that the expected class files are created.
    build.executor.run(":app:compileDebugKotlin", ":app:compileDebugAndroidTestKotlin")

    val kotlincOutputDir = build.androidApplication().resolve(InternalArtifactType.BUILT_IN_KOTLINC)
    PathSubject.assertThat(kotlincOutputDir).exists()

    val fooMainClassFile = kotlincOutputDir.resolve("debug/compileDebugKotlin/classes/com/foo/application/FooMain.class")
    PathSubject.assertThat(fooMainClassFile).exists()

    val fooDebugClassFile = kotlincOutputDir.resolve("debug/compileDebugKotlin/classes/com/foo/application/FooDebug.class")
    PathSubject.assertThat(fooDebugClassFile).exists()

    val fooAndroidTestClassFile =
      kotlincOutputDir.resolve("debugAndroidTest/compileDebugAndroidTestKotlin/classes/com/foo/application/FooAndroidTest.class")
    PathSubject.assertThat(fooAndroidTestClassFile).exists()
  }
}
