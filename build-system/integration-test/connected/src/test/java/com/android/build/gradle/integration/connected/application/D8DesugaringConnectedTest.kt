/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.GradleProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.EmptyGradleProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.android.utils.FileUtils
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class D8DesugaringConnectedTest(val runWithBuiltInPlatform: Boolean) {

  companion object {
    @ClassRule @JvmField val emulator = getEmulator()

    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }

  @get:Rule
  var project =
    GradleTestProject.builder()
      .fromTestApp(
        MultiModuleTestProject(ImmutableMap.of<String, GradleProject>(":app", HelloWorldApp.noBuildFile(), ":lib", EmptyGradleProject()))
      )
      .create()

  @Before
  fun setUp() {
    TestFileUtils.appendToFile(
      project.getSubproject(":app").buildFile,
      """
                apply plugin: "com.android.application"

                android {
                    namespace = "${HelloWorldApp.NAMESPACE}"
                    compileSdkVersion ${GradleTestProject.DEFAULT_COMPILE_SDK_VERSION}

                    defaultConfig {
                        applicationId "com.example.d8desugartest"
                        minSdkVersion 20
                        //noinspection ExpiredTargetSdkVersion
                        targetSdkVersion ${GradleTestProject.DEFAULT_COMPILE_SDK_VERSION}
                        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
                    }

                    flavorDimensions "whatever"

                    productFlavors {
                        multidex {
                            dimension "whatever"
                            multiDexEnabled = true
                            multiDexKeepFile = file('debug_main_dex_list.txt')
                        }
                        base {
                            dimension "whatever"
                        }
                    }
                }

                dependencies {
                    implementation project(':lib')
                    testImplementation 'junit:junit:4.12'
                    androidTestImplementation "androidx.test:runner:1.4.0-alpha06"
                    androidTestImplementation "androidx.test:rules:1.4.0-alpha06"
                    androidTestImplementation "androidx.annotation:annotation-experimental:1.3.0"
                }
                """
        .trimIndent(),
    )
    // fail fast if no response
    project.addAdbTimeout()

    TestFileUtils.appendToFile(project.getSubproject(":lib").buildFile, "apply plugin: 'java'\n")
    val interfaceWithDefault = project.getSubproject(":lib").file("src/main/java/com/example/helloworld/InterfaceWithDefault.java")
    FileUtils.mkdirs(interfaceWithDefault.parentFile)
    TestFileUtils.appendToFile(
      interfaceWithDefault,
      ("""package com.example.helloworld;

                    public interface InterfaceWithDefault {

                      static String defaultConvert(String input) {
                        return input + "-default";
                      }

                      default String convert(String input) {
                        return defaultConvert(input);
                      }
                    }
                    """),
    )
    val stringTool = project.getSubproject(":lib").file("src/main/java/com/example/helloworld/StringTool.java")
    FileUtils.mkdirs(stringTool.parentFile)
    TestFileUtils.appendToFile(
      stringTool,
      ("""package com.example.helloworld;

                    public class StringTool {
                      private InterfaceWithDefault converter;
                      public StringTool(InterfaceWithDefault converter) {
                        this.converter = converter;
                      }
                      public String convert(String input) {
                        return converter.convert(input);
                      }
                    }
                    """),
    )
    val exampleInstrumentedTest =
      project.getSubproject(":app").file("src/androidTest/java/com/example/helloworld/ExampleInstrumentedTest.java")
    FileUtils.mkdirs(exampleInstrumentedTest.parentFile)
    TestFileUtils.appendToFile(
      exampleInstrumentedTest,
      ("""package com.example.helloworld;

                    import android.content.Context;
                    import androidx.test.InstrumentationRegistry;
                    import androidx.test.runner.AndroidJUnit4;

                    import org.junit.Test;
                    import org.junit.runner.RunWith;

                    import static org.junit.Assert.*;

                    @RunWith(AndroidJUnit4.class)
                    public class ExampleInstrumentedTest {
                        @Test
                        public void useAppContext() throws Exception {
                            // Context of the app under test.
                            Context appContext = InstrumentationRegistry.getTargetContext();
                            assertEquals("toto-default", new StringTool(new InterfaceWithDefault() { }).convert("toto"));
                        }
                    }
                    """),
    )
    val debugMainDexList = project.getSubproject(":app").file("debug_main_dex_list.txt")
    TestFileUtils.appendToFile(debugMainDexList, "com/example/helloworld/InterfaceWithDefault.class")

    // run the uninstall tasks in order to (1) make sure nothing is installed at the beginning
    // of each test and (2) check the adb connection before taking the time to build anything.
    project.executor().run("uninstallAll")
  }

  @Test
  fun runAndroidTest() {
    project.executor().with(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform).run("app:connectedBaseDebugAndroidTest")
    verifyTestResultXml()
  }

  private fun verifyTestResultXml() {
    val androidTestResultsDir =
      File(project.getSubproject(":app").projectDir, "build/outputs/androidTest-results/connected/debug/flavors/base")

    val xmlFiles = androidTestResultsDir.listFiles { _, name -> name.startsWith("TEST-") && name.endsWith(".xml") }

    assertThat(xmlFiles).isNotNull()
    assertThat(xmlFiles).isNotEmpty()

    val xmlFile = xmlFiles!![0]

    val dbFactory = DocumentBuilderFactory.newInstance()
    val dBuilder = dbFactory.newDocumentBuilder()
    val doc = dBuilder.parse(xmlFile)
    doc.documentElement.normalize()

    val rootName = doc.documentElement.nodeName
    if (rootName == "testsuites" || rootName == "testsuite") {
      val testsAttr = doc.documentElement.getAttribute("tests")
      assertThat(testsAttr).isEqualTo("2")
      val failuresAttr = doc.documentElement.getAttribute("failures")
      assertThat(failuresAttr).isEqualTo("0")
    } else {
      throw AssertionError("Unexpected root element in test result XML: $rootName")
    }
  }
}
