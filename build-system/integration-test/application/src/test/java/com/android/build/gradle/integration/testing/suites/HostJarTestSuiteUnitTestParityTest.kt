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
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

/** A Java resource of the verifying engine's jar. */
private const val ENGINE_RESOURCE = "verifying_engine_resource.txt"

/**
 * Checks that a host-jar test suite resolves the tested module and its dependencies on its compile and runtime classpaths: the suite's and
 * tested module's Java resources, the test fixtures of the module, the dependencies that AGP adds to the tested variant after suite
 * creation (kotlin-stdlib), the classes of a plain JVM library that the tested library depends on, runtime stub R classes for a library
 * without Android resources, and a library's `compileOnlyApi` dependencies at compile time.
 */
class HostJarTestSuiteUnitTestParityTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("org.junit.platform:junit-platform-commons:1.13.3")
        jar("org.opentest4j:opentest4j:1.3.0")
        jar("org.apiguardian:apiguardian-api:1.1.2")
        jar("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
        jar("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
          .addClasses(VerifyingJunitEngine::class.java, VerifyingTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", VerifyingJunitEngine::class.java.name)
          .addTextFile(ENGINE_RESOURCE, "engine")
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication(":app") {
          android {
            namespace = "com.example.app"
            testOptions.customSuites.create("unit") {
              it.useVerifyingJunitEngine()
              it.useJunitEngine.addInputProperty(
                "com.android.junit.engine.expected.classes",
                // The suite's own class and kotlin-stdlib, which AGP adds to the app after creating the suites.
                "some.app.SuiteTest,kotlin.Unit",
              )
              it.useJunitEngine.addInputProperty(
                "com.android.junit.engine.expected.resources",
                "suite_resource.txt,main_resource.txt",
              )
              // Each engine jar, which holds its own Java resources, is on the test task's classpath exactly once.
              it.useJunitEngine.addInputProperty("com.android.junit.engine.unique.resources", ENGINE_RESOURCE)
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("jvm") {} }
            }
          }
          files {
            // An application with no dependencies: only AGP brings kotlin-stdlib in.
            add("src/main/kotlin/some/app/AppClass.kt", "package some.app\nclass AppClass { fun answer() = 42 }")
            add("src/main/resources/main_resource.txt", "main")
            add("src/unit/kotlin/some/app/SuiteTest.kt", "package some.app\nclass SuiteTest { val answer = AppClass().answer() }")
            add("src/unit/resources/suite_resource.txt", "suite")
          }
        }
        // A plain JVM library, for example a model or protobuf module that Android libraries depend on.
        javaLibrary(":jvmLib") {
          files {
            add("src/main/java/some/jvmlib/JvmModel.java", "package some.jvmlib; public class JvmModel { public String id = \"id\"; }")
          }
        }
        androidLibrary(":lib") {
          android {
            namespace = "com.example.lib"
            testFixtures.enable = true
            testOptions.customSuites.create("libUnit") {
              it.useVerifyingJunitEngine()
              it.useJunitEngine.addInputProperty(
                "com.android.junit.engine.expected.classes",
                "some.lib.LibSuiteTest,some.lib.FakeRepo,some.jvmlib.JvmModel",
              )
              it.useJunitEngine.addInputProperty(
                "com.android.junit.engine.expected.resources",
                "lib_main_resource.txt",
              )
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("jvm") {} }
            }
          }
          dependencies { api(project(":jvmLib")) }
          files {
            add("src/main/java/some/lib/Repo.java", "package some.lib; public interface Repo { some.jvmlib.JvmModel get(); }")
            add("src/main/resources/lib_main_resource.txt", "lib main")
            add(
              "src/testFixtures/java/some/lib/FakeRepo.java",
              "package some.lib; public class FakeRepo implements Repo { public some.jvmlib.JvmModel get() { return new some.jvmlib.JvmModel(); } }",
            )
            // Compiles only if the suite sees the module's own test fixtures and the JVM library.
            add(
              "src/libUnit/java/some/lib/LibSuiteTest.java",
              "package some.lib; public class LibSuiteTest { Repo repo = new FakeRepo(); String id = repo.get().id; }",
            )
          }
        }
        androidLibrary(":resLib") {
          android { namespace = "com.example.reslib" }
          files { add("src/main/res/values/strings.xml", "<resources><string name=\"res_lib_name\">ResLib</string></resources>") }
        }
        // A library whose suite has no Android resources (the default).
        androidLibrary(":plainLib") {
          android {
            namespace = "com.example.plainlib"
            testOptions.customSuites.create("plainUnit") {
              it.useVerifyingJunitEngine()
              // Throws unless the R classes of the library and of its dependencies are on the classpath with real IDs.
              it.useJunitEngine.addInputProperty("com.android.junit.engine.invoked.methods", "some.plainlib.PlainSuiteTest#checkRClasses")
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("jvm") {} }
            }
          }
          dependencies {
            implementation(project(":resLib"))
          }
          files {
            add("src/main/res/values/strings.xml", "<resources><string name=\"plain_lib_name\">PlainLib</string></resources>")
            add(
              "src/plainUnit/java/some/plainlib/PlainSuiteTest.java",
              """
              package some.plainlib;
              public class PlainSuiteTest {
                  public static void checkRClasses() {
                      if (com.example.plainlib.R.string.plain_lib_name == 0) throw new IllegalStateException("library R id is 0");
                      if (com.example.reslib.R.string.res_lib_name == 0) throw new IllegalStateException("dependency R id is 0");
                  }
              }
              """
                .trimIndent(),
            )
          }
        }
        androidLibrary(":apiLib") {
          android { namespace = "com.example.apilib" }
          files {
            add(
              "src/main/java/some/apilib/CompileOnlyMarker.java",
              "package some.apilib; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS) public @interface CompileOnlyMarker {}",
            )
          }
        }
        // A library with a compileOnlyApi dependency, published on its API elements and visible when compiling its suites.
        androidLibrary(":apiUser") {
          android {
            namespace = "com.example.apiuser"
            testOptions.customSuites.create("apiUnit") {
              it.useVerifyingJunitEngine()
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("jvm") {} }
            }
          }
          dependencies { compileOnlyApi(project(":apiLib")) }
          files {
            add(
              "src/apiUnit/java/some/apiuser/ApiSuiteTest.java",
              "package some.apiuser; public class ApiSuiteTest { @some.apilib.CompileOnlyMarker public void marked() {} }",
            )
          }
        }
      }

  @Test
  fun applicationHostSuiteHasUnitTestClasspath() {
    rule.build.executor.run(":app:testDebugUnitJvmTestSuite")
  }

  @Test
  fun libraryHostSuiteSeesOwnTestFixtures() {
    rule.build.executor.run(":lib:testDebugLibUnitJvmTestSuite")
  }

  @Test
  fun libraryHostSuiteWithoutAndroidResourcesHasRuntimeRClasses() {
    rule.build.executor.run(":plainLib:testDebugPlainUnitJvmTestSuite")
  }

  @Test
  fun libraryHostSuiteCompilesAgainstCompileOnlyApiDependencies() {
    rule.build.executor.run(":apiUser:testDebugApiUnitJvmTestSuite")
  }
}
