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
package com.android.tools.lint

import com.android.testutils.TestUtils
import com.android.tools.lint.LintCliFlags.ERRNO_SUCCESS
import com.android.tools.lint.checks.AbstractCheckTest.SUPPORT_ANNOTATIONS_JAR
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.dos2unix
import com.google.common.io.Files
import java.io.File
import kotlin.text.Charsets
import org.intellij.lang.annotations.Language
import org.junit.After
import org.junit.Assume
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@Suppress("LintDocExample")
class UastEnvironmentSourceSetTest {

  @Test
  fun testSelectiveInput() {
    // Regression test for b/347624107
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          SUPPORT_ANNOTATIONS_JAR,
          java(
              "src/Foo.java",
              """
                  import androidx.annotation.RequiresApi;
                  public class Foo {
                    @RequiresApi(24)
                    public static String foo(String x) {
                      return x;
                    }
                  }
                  """,
            )
            .indented(),
          kotlin(
              "src/Foo.kt",
              """
                  public open class Foo {
                    companion object {
                      @JvmStatic
                      public fun foo(x: String): String {
                        return x
                      }
                    }
                  }
                  """,
            )
            .indented(),
          java(
              "src/Bar.java",
              """
                  import static Foo.foo;
                  public class Bar {
                    public String bar(String x) {
                      return foo(x);
                    }
                  }
                  """,
            )
            .indented(),
        )
        .allowClassNameClashes(true)
        .createProjects(root)

    @Language("XML")
    val descriptor =
      """
        <project>
          <sdk dir='${TestUtils.getSdk()}'/>
          <module name="app" android="true" library="false" compute_source_roots="false">
            <classpath jar="libs/support-annotations.jar" />
            <src file="src/Foo.java" />
            <!-- Intentionally miss Foo.kt to see if Foo.foo in Bar.java refers to Foo.java, not Foo.kt -->
            <!-- src file="src/Foo.kt" /-->
            <src file="src/Bar.java" />
          </module>
        </project>
      """

    Files.asCharSink(File(root, "project.xml"), Charsets.UTF_8).write(descriptor)
    MainTest.checkDriver(
      "src/Bar.java:4: Error: Call requires API level 24 (current min is 1): foo [NewApi]\n" +
        "    return foo(x);\n" +
        "           ~~~\n" +
        "1 error",
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "NewApi", "--project", File(root, "project.xml").path),
      { it.dos2unix() },
      null,
    )
  }

  @Test
  fun testSelectiveInput_duplicated() {
    // Regression test for b/347624107
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          SUPPORT_ANNOTATIONS_JAR,
          java(
              "src/Foo.java",
              """
                  import androidx.annotation.RequiresApi;
                  public class Foo {
                    @RequiresApi(24)
                    public static String foo(String x) {
                      return x;
                    }
                  }
                  """,
            )
            .indented(),
          kotlin(
              "src/Foo.kt",
              """
                  public open class Foo {
                    companion object {
                      @JvmStatic
                      public fun foo(x: String): String {
                        return x
                      }
                    }
                  }
                  """,
            )
            .indented(),
          java(
              "src/Bar.java",
              """
                  import static Foo.foo;
                  public class Bar {
                    public String bar(String x) {
                      return foo(x);
                    }
                  }
                  """,
            )
            .indented(),
        )
        .allowClassNameClashes(true)
        .createProjects(root)

    @Language("XML")
    val descriptor =
      """
        <project>
          <sdk dir='${TestUtils.getSdk()}'/>
          <module name="app" android="true" library="false" compute_source_roots="false">
            <classpath jar="libs/support-annotations.jar" />
            <src file="src/Foo.java" />
            <!-- Intentionally miss Foo.kt to see if Foo.foo in Bar.java refers to Foo.java, not Foo.kt -->
            <!-- src file="src/Foo.kt" /-->
            <src file="src/Bar.java" />
            <src file="src/Bar.java" />
          </module>
        </project>
      """

    Files.asCharSink(File(root, "project.xml"), Charsets.UTF_8).write(descriptor)
    MainTest.checkDriver(
      """
        src/Bar.java:4: Error: Call requires API level 24 (current min is 1): foo [NewApi]
            return foo(x);
                   ~~~
        src/Bar.java:4: Error: Call requires API level 24 (current min is 1): foo [NewApi]
            return foo(x);
                   ~~~
        2 errors
      """
        .trimIndent(),
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "NewApi", "--project", File(root, "project.xml").path),
      { it.dos2unix() },
      null,
    )
  }

  @Test
  fun testSelectiveInput_overlapped() {
    Assume.assumeTrue(useFirUast())
    // Regression test for b/347624107
    val root = temp.newFolder().canonicalFile.absoluteFile
    val projects =
      lint()
        .files(
          SUPPORT_ANNOTATIONS_JAR,
          java(
              "src/Foo.java",
              """
                  import androidx.annotation.RequiresApi;
                  public class Foo {
                    @RequiresApi(24)
                    public static String foo(String x) {
                      return x;
                    }
                  }
                  """,
            )
            .indented(),
          kotlin(
              "src/Foo.kt",
              """
                  public open class Foo {
                    companion object {
                      @JvmStatic
                      public fun foo(x: String): String {
                        return x
                      }
                    }
                  }
                  """,
            )
            .indented(),
          java(
              "src/Bar.java",
              """
                  import static Foo.foo;
                  public class Bar {
                    public String bar(String x) {
                      return foo(x);
                    }
                  }
                  """,
            )
            .indented(),
        )
        .allowClassNameClashes(true)
        .createProjects(root)

    @Language("XML")
    val descriptor =
      """
        <project>
          <sdk dir='${TestUtils.getSdk()}'/>
          <module name="app" android="true" library="false" compute_source_roots="false">
            <classpath jar="libs/support-annotations.jar" />
            <src file="src/Foo.java" />
            <!-- Intentionally miss Foo.kt to see if Foo.foo in Bar.java refers to Foo.java, not Foo.kt -->
            <!-- src file="src/Foo.kt" /-->
            <src file="src/Bar.java" />
            <src file="src/" />
          </module>
        </project>
      """

    Files.asCharSink(File(root, "project.xml"), Charsets.UTF_8).write(descriptor)
    MainTest.checkDriver(
      "No issues found.", // `foo` not resolved due to conflict, so error not detected
      "",

      // Expected exit code
      ERRNO_SUCCESS,

      // Args
      arrayOf("--check", "NewApi", "--project", File(root, "project.xml").path),
      { it.dos2unix() },
      null,
    )
  }

  @After
  fun tearDown() {
    UastEnvironment.disposeApplicationEnvironment()
  }

  companion object {
    @ClassRule @JvmField var temp = TemporaryFolder()
  }
}
