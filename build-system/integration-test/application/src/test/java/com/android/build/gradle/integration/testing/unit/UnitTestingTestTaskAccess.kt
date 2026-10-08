/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.testing.unit

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

class UnitTestingTestTaskAccess {
  @JvmField @Rule val project = GradleTestProject.builder().fromTestProject("unitTesting").create()

  @Test
  fun testTestTaskAccess() {
    project.buildFile.appendText(
      """
      androidComponents {
          onVariants(selector().all()) { variant ->
              variant.unitTest?.configureTestTask { testTask ->
                  testTask.addTestListener([
                      beforeTest: { descriptor -> println("Running test: " + descriptor) }
                  ] as org.gradle.api.tasks.testing.TestListener)
              }
          }
      }
      """
        .trimIndent()
    )
    var result = project.executor().run("testDebug")
    Truth.assertThat(result.stdout.findAll("Running test: *").count()).isGreaterThan(10)
  }

  // Regression test for b/559685582
  @Test
  fun testUnitTestJavaResourcesProvidedAsStaticSourceDirectories() {
    project.file("src/test/extra-res/my_extra_res.txt").apply {
      parentFile.mkdirs()
      writeText("hello")
    }
    project.file("src/main/extra-main-res/main_extra_res.txt").apply {
      parentFile.mkdirs()
      writeText("hello_main")
    }
    project.buildFile.appendText(
      """
      androidComponents {
          onVariants(selector().all()) { variant ->
              variant.sources.resources.addStaticSourceDirectory("src/main/extra-main-res")
              variant.unitTest?.sources?.resources?.addStaticSourceDirectory("src/test/extra-res")
          }
      }
      """
        .trimIndent()
    )
    project
      .file("src/test/java/com/android/tests/ExtraResTest.java")
      .writeText(
        """
        package com.android.tests;
        import org.junit.Test;
        import static org.junit.Assert.*;
        import java.io.File;
        import java.net.URL;
        import java.util.Enumeration;
        import java.util.ArrayList;
        import java.util.List;

        public class ExtraResTest {
            @Test
            public void testGetResourcesRoot() throws Exception {
                Enumeration<URL> urls = getClass().getClassLoader().getResources("");
                List<File> allFiles = new ArrayList<>();
                while (urls.hasMoreElements()) {
                    URL u = urls.nextElement();
                    if ("file".equals(u.getProtocol())) {
                        File f = new File(u.getPath());
                        collectFiles(f, allFiles);
                    }
                }
                assertTrue(
                    "Expected my_extra_res.txt in getResources(\"\") file roots, found: " + allFiles,
                    allFiles.stream().anyMatch(f -> f.getName().equals("my_extra_res.txt"))
                );
                assertNotNull(
                    "Expected main_extra_res.txt from main variant resources",
                    getClass().getClassLoader().getResource("main_extra_res.txt")
                );
            }

            private void collectFiles(File curr, List<File> out) {
                if (curr.isFile()) {
                    out.add(curr);
                } else if (curr.isDirectory()) {
                    File[] children = curr.listFiles();
                    if (children != null) {
                        for (File c : children) collectFiles(c, out);
                    }
                }
            }
        }
        """
          .trimIndent()
      )
    val result = project.executor().run("testDebugUnitTest")
    Truth.assertThat(result.didWorkTasks).contains(":compressDebugUnitTestJavaRes")
    Truth.assertThat(result.didWorkTasks).contains(":extractDebugUnitTestJavaRes")
    Truth.assertThat(result.didWorkTasks).contains(":compressDebugJavaRes")
    Truth.assertThat(result.tasks).doesNotContain(":processDebugUnitTestJavaRes")
    Truth.assertThat(result.tasks).doesNotContain(":processDebugJavaRes")
  }
}
