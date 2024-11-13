/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tools.lint.checks.infrastructure

import com.android.testutils.TestUtils
import com.android.tools.lint.LintCliFlags
import com.android.tools.lint.MainTest
import com.android.tools.lint.checks.AbstractCheckTest.assertTrue
import com.android.tools.lint.checks.AbstractCheckTest.bytecode
import com.android.tools.lint.checks.AbstractCheckTest.source
import com.android.tools.lint.checks.infrastructure.TestFiles.gradle
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFiles.xml
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.client.api.JarFileIssueRegistryTest.Companion.lintApiStubs
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScanner
import com.intellij.psi.PsiMethod
import java.io.File
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UImportStatement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.evaluateString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Attr

/**
 * Checks that some lint checks cannot be suppressed with the normal suppression annotations or
 * mechanisms.
 */
class SuppressLintTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun checkErrorFlagged() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
                    fun forbidden() {
                        forbidden()
                    }"""
          )
          .indented(),
        java(
            """
                    import forbidden;
                    class Test {
                    }
                    """
          )
          .indented(),
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
                src/Test.java:1: Warning: Some error message here [_SecureIssue]
                import forbidden;
                ~~~~~~~~~~~~~~~~~
                src/test.kt:2: Warning: Some error message here [_SecureIssue]
                    forbidden()
                    ~~~~~~~~~~~
                0 errors, 2 warnings
                """
      )
  }

  @Test
  fun checkOkSuppress() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
                    import foo.bar.MyOwnAnnotation
                    @MyOwnAnnotation
                    fun forbidden() {
                        forbidden()
                    }"""
          )
          .indented(),
        java(
            """
                    import foo.bar.MyOwnAnnotation;
                    import forbidden;
                    @MyOwnAnnotation
                    class Test {
                        @SuppressWarnings("InfiniteRecursion")
                        public void forbidden() {
                            forbidden();
                        }
                    }
                    """
          )
          .indented(),
        java(
          """
                    package foo.bar;
                    @SuppressWarnings("ClassNameDiffersFromFileName")
                    public @interface MyOwnAnnotation {
                    }
                    """
        ),
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expectClean()
  }

  @Test
  fun checkForbiddenSuppressWithComment() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
            fun forbidden() {
                //noinspection AndroidLint_SecureIssue
                forbidden()
            }
            """
          )
          .indented()
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
        src/test.kt:3: Error: Issue _SecureIssue is not allowed to be suppressed (but can be with @foo.bar.MyOwnAnnotation) [LintError]
            forbidden()
            ~~~~~~~~~~~
        src/test.kt:3: Warning: Some error message here [_SecureIssue]
            forbidden()
            ~~~~~~~~~~~
        1 errors, 1 warnings
        """
      )
  }

  @Test
  fun checkForbiddenRequiresExactMatch() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
            @Suppress("all")
            fun forbidden() {
                forbidden()
            }
            """
          )
          .indented()
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
        src/test.kt:3: Warning: Some error message here [_SecureIssue]
            forbidden()
            ~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  @Test
  fun checkForbiddenSuppressWithAnnotation() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
                    import android.annotation.SuppressLint
                    @SuppressLint("_SecureIssue")
                    fun forbidden() {
                        forbidden()
                    }"""
          )
          .indented(),
        java(
            """
                    import android.annotation.SuppressLint;
                    import forbidden;
                    @SuppressLint("_SecureIssue")
                    class Test {
                    }
                    """
          )
          .indented(),
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
                src/Test.java:3: Error: Issue _SecureIssue is not allowed to be suppressed (but can be with @foo.bar.MyOwnAnnotation) [LintError]
                @SuppressLint("_SecureIssue")
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                src/test.kt:2: Error: Issue _SecureIssue is not allowed to be suppressed (but can be with @foo.bar.MyOwnAnnotation) [LintError]
                @SuppressLint("_SecureIssue")
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                src/Test.java:2: Warning: Some error message here [_SecureIssue]
                import forbidden;
                ~~~~~~~~~~~~~~~~~
                src/test.kt:4: Warning: Some error message here [_SecureIssue]
                    forbidden()
                    ~~~~~~~~~~~
                2 errors, 2 warnings
                """
      )
  }

  @Test
  fun checkForbiddenSuppressWithXmlIgnore() {
    lint()
      .allowCompilationErrors()
      .files(
        xml(
            "res/layout/main.xml",
            """
                    <LinearLayout
                      android:layout_width="match_parent"
                      android:layout_height="match_parent"
                      xmlns:tools="http://schemas.android.com/tools"
                      xmlns:android="http://schemas.android.com/apk/res/android">

                      <androidx.compose.ui.platform.ComposeView
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:forbidden="true"
                        tools:ignore="_SecureIssue"/>
                    </LinearLayout>
                    """,
          )
          .indented()
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
                res/layout/main.xml:7: Error: Issue _SecureIssue is not allowed to be suppressed [LintError]
                  <androidx.compose.ui.platform.ComposeView
                  ^
                res/layout/main.xml:10: Warning: Some error message here [_SecureIssue]
                    android:forbidden="true"
                    ~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 1 warnings
                """
      )
  }

  @Test
  fun checkForbiddenSuppressWithXmlComment() {
    lint()
      .allowCompilationErrors()
      .files(
        xml(
            "res/layout/main.xml",
            """
                    <LinearLayout
                      android:layout_width="match_parent"
                      android:layout_height="match_parent"
                      xmlns:android="http://schemas.android.com/apk/res/android">

                      <!--suppress _SecureIssue -->
                      <androidx.compose.ui.platform.ComposeView android:forbidden="true"/>
                    </LinearLayout>
                    """,
          )
          .indented()
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
                res/layout/main.xml:7: Error: Issue _SecureIssue is not allowed to be suppressed [LintError]
                  <androidx.compose.ui.platform.ComposeView android:forbidden="true"/>
                  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                res/layout/main.xml:7: Warning: Some error message here [_SecureIssue]
                  <androidx.compose.ui.platform.ComposeView android:forbidden="true"/>
                                                            ~~~~~~~~~~~~~~~~~~~~~~~~
                1 errors, 1 warnings
                """
      )
  }

  @Test
  fun checkForbiddenSuppressWithLintXml() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
                    fun forbidden() {
                        forbidden()
                    }"""
          )
          .indented(),
        xml(
            "lint.xml",
            """
                    <lint>
                        <issue id="all" severity="ignore" />
                        <issue id="_SecureIssue" severity="ignore" />
                    </lint>
                """,
          )
          .indented(),
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
                src/test.kt:2: Warning: Some error message here [_SecureIssue]
                    forbidden()
                    ~~~~~~~~~~~
                0 errors, 1 warnings
                """
      )
  }

  @Test
  fun check258705120() {
    // Verify that if we have an issue where the suppression annotation is set to the same value
    // as the issue id, we allow using that.
    lint()
      .allowCompilationErrors()
      .files(
        xml(
            "res/values/strings.xml",
            """
            <resources xmlns:tools="http://schemas.android.com/tools">
                <!-- Make sure detector flags this attribute when not suppressed -->
                <string name="test1" forbidden="_SecureIssue3">Test</string> <!-- ERROR 1 -->
                <!-- Make sure suppression using something other than the specific issue id does not work: -->
                <string name="test2" tools:ignore="all" forbidden="_SecureIssue3">Test</string> <!-- ERROR 2 -->
                <!-- Valid suppression: -->
                <string name="test3" tools:ignore="_SecureIssue3" forbidden="_SecureIssue3">Test</string> <!-- OK 1 -->
            </resources>
            """,
          )
          .indented(),
        kotlin(
            "src/suppressions.kt",
            """
            fun notSuppressed() {
                // No suppressions -- make sure the detector is actually flagging these calls
                println("_SecureIssue3") // ERROR 3
            }

            @Suppress("all")
            fun notSuppressedWithAll() {
                // Make sure that @Suppress("all") doesn't have any effect on these
                println("_SecureIssue3") // ERROR 4
            }

            @Suppress("Security")
            fun notSuppressedWithCategory() {
                // Make sure that @Suppress("Security") -- the issue category -- doesn't have any effect on these
                println("_SecureIssue3") // ERROR 5
            }

            @Suppress("_SecureIssue3") // OK 2
            fun suppressed() {
                println("_SecureIssue3") // OK 3
            }

            @Suppress("_SecureIssue3") // OK 4
            fun suppressedWithComment() {
                // noinspection _SecureIssue3
                println("_SecureIssue3") // OK 5
            }
            """,
          )
          .indented(),
        kotlin(
            """
            fun baselineSuppressed() {
                println("_SecureIssue3") // OK 6
            }
            """
          )
          .indented(),
      )
      .baseline(
        xml(
          "baseline.xml",
          """
          <issues format="5" by="lint 8.7.2">
              <issue
                  id="_SecureIssue3"
                  severity="Warning"
                  message="Some error message here"
                  category="Security"
                  priority="10"
                  summary="Some important security issue"
                  explanation="Blahdiblah"
                  errorLine1="    println(&quot;_SecureIssue3&quot;) // OK 6"
                  errorLine2="    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~">
                  <location
                      file="src/test.kt"
                      line="2"
                      column="5"/>
              </issue>
          </issues>
          """,
        )
      )
      .issues(MySecurityDetector.TEST_ISSUE_SUPPRESS_ANNOTATION_SAME_AS_ID)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
        res/values/strings.xml:3: Warning: Some error message here [_SecureIssue3]
            <string name="test1" forbidden="_SecureIssue3">Test</string> <!-- ERROR 1 -->
                                 ~~~~~~~~~~~~~~~~~~~~~~~~~
        res/values/strings.xml:5: Warning: Some error message here [_SecureIssue3]
            <string name="test2" tools:ignore="all" forbidden="_SecureIssue3">Test</string> <!-- ERROR 2 -->
                                                    ~~~~~~~~~~~~~~~~~~~~~~~~~
        src/suppressions.kt:3: Warning: Some error message here [_SecureIssue3]
            println("_SecureIssue3") // ERROR 3
            ~~~~~~~~~~~~~~~~~~~~~~~~
        src/suppressions.kt:9: Warning: Some error message here [_SecureIssue3]
            println("_SecureIssue3") // ERROR 4
            ~~~~~~~~~~~~~~~~~~~~~~~~
        src/suppressions.kt:15: Warning: Some error message here [_SecureIssue3]
            println("_SecureIssue3") // ERROR 5
            ~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 5 warnings
        """
      )
  }

  @Test
  fun checkIgnoredSuppressWithLintXmlAll() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
                    fun forbidden() {
                        forbidden()
                    }"""
          )
          .indented(),
        xml(
            "lint.xml",
            """
                    <lint>
                        <!-- Not specifically targeting the forbidden issue with "all"
                         so we skip it for this issue but don't complain about it -->
                        <issue id="all" severity="ignore" />
                    </lint>
                """,
          )
          .indented(),
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
                src/test.kt:2: Warning: Some error message here [_SecureIssue]
                    forbidden()
                    ~~~~~~~~~~~
                0 errors, 1 warnings
                """
      )
  }

  @Test
  fun checkIgnoredBaseline() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
                    fun forbidden() {
                        forbidden()
                    }"""
          )
          .indented()
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .baseline(
        xml(
            "baseline.xml",
            """
                    <issues format="5" by="lint 3.3.0">
                        <issue
                            id="_SecureIssue"
                            severity="Warning"
                            message="Some error message here"
                            category="Security"
                            priority="10"
                            summary="Some important security issue"
                            explanation="Blahdiblah"
                            errorLine1="    forbidden()"
                            errorLine2="    ~~~~~~~~~~~">
                            <location
                                file="src/test.kt"
                                line="2"
                                column="5"/>
                        </issue>
                    </issues>
                    """,
          )
          .indented()
      )
      .skipTestModes(TestMode.PARTIAL)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
                baseline.xml: Error: Issue _SecureIssue is not allowed to be suppressed (but can be with @foo.bar.MyOwnAnnotation) [LintError]
                src/test.kt:2: Warning: Some error message here [_SecureIssue]
                    forbidden()
                    ~~~~~~~~~~~
                1 errors, 1 warnings
                """
      )
  }

  @Test
  fun checkNeverSuppressible() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
                    fun forbidden() {
                        forbidden()
                    }"""
          )
          .indented()
      )
      .issues(MySecurityDetector.TEST_ISSUE_NEVER_SUPPRESSIBLE)
      .baseline(
        xml(
            "baseline.xml",
            """
                    <issues format="5" by="lint 3.3.0">
                        <issue
                            id="_SecureIssue2"
                            severity="Warning"
                            message="Some error message here"
                            category="Security"
                            priority="10"
                            summary="Some important security issue"
                            explanation="Blahdiblah"
                            errorLine1="    forbidden()"
                            errorLine2="    ~~~~~~~~~~~">
                            <location
                                file="src/test.kt"
                                line="2"
                                column="5"/>
                        </issue>
                    </issues>
                """,
          )
          .indented()
      )
      .skipTestModes(TestMode.PARTIAL)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
                baseline.xml: Error: Issue _SecureIssue2 is not allowed to be suppressed [LintError]
                src/test.kt:2: Warning: Some error message here [_SecureIssue2]
                    forbidden()
                    ~~~~~~~~~~~
                1 errors, 1 warnings
                """
      )
  }

  @Test
  fun checkAllowBaselineSuppressViaFlag() {
    // This test is identical to the one above (checkNeverSuppressible)
    // except that it turns on the --XallowBaselineSuppress flag (via the
    // clientFactory method below)
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
                    fun forbidden() {
                        forbidden()
                    }"""
          )
          .indented()
      )
      .issues(MySecurityDetector.TEST_ISSUE_NEVER_SUPPRESSIBLE)
      .baseline(
        xml(
            "baseline.xml",
            """
                    <issues format="5" by="lint 3.3.0">
                        <issue
                            id="_SecureIssue2"
                            severity="Warning"
                            message="Some error message here"
                            category="Security"
                            priority="10"
                            summary="Some important security issue"
                            explanation="Blahdiblah"
                            errorLine1="    forbidden()"
                            errorLine2="    ~~~~~~~~~~~">
                            <location
                                file="src/test.kt"
                                line="2"
                                column="5"/>
                        </issue>
                    </issues>
                """,
          )
          .indented()
      )
      // Sets the --XallowBaselineSuppress flag:
      .clientFactory { TestLintClient().apply { flags.allowBaselineSuppress = true } }
      .skipTestModes(TestMode.PARTIAL)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expectClean()
  }

  @Test
  fun checkForbiddenSuppressWithLintOptions() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
                    fun forbidden() {
                        forbidden()
                    }"""
          )
          .indented(),
        gradle(
            """
                    apply plugin: 'com.android.application'

                    android {
                        lintOptions {
                            disable '_SecureIssue'
                        }
                    }
                    """
          )
          .indented(),
      )
      .issues(MySecurityDetector.TEST_ISSUE)
      .skipTestModes(TestMode.PARTIAL)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expect(
        """
                src/main/kotlin/test.kt:2: Warning: Some error message here [_SecureIssue]
                    forbidden()
                    ~~~~~~~~~~~
                src/main/kotlin/test.kt:2: Warning: Some error message here [_SecureIssue2]
                    forbidden()
                    ~~~~~~~~~~~
                0 errors, 2 warnings
                """
      )
  }

  @Test
  fun testSuppressProperty() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
                    class Test(
                        @Suppress("_PropertyIssue") var forbidden0: String = ""
                    ) {
                        @Suppress("_PropertyIssue") var forbidden1: String = ""
                        @Suppress("_PropertyIssue") val forbidden2: String
                          get() = ""
                        @Suppress("_PropertyIssue") var forbidden3 : String
                            get() = field
                            set(value) { field = value }
                        @Suppress("MyId", "_PropertyIssue") val forbidden4: String
                          get() = ""
                    }
                    """
          )
          .indented()
      )
      .issues(MyPropertyDetector.PROPERTY_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expectClean()
  }

  @Test
  fun testSuppressTopLevelFunctions() {
    lint()
      .allowCompilationErrors()
      .files(
        kotlin(
            """
            // Some comment
            //noinspection _PropertyIssue
            val forbidden1 = ""
            """
          )
          .indented()
      )
      .issues(MyPropertyDetector.PROPERTY_ISSUE)
      .sdkHome(TestUtils.getSdk().toFile())
      .run()
      .expectClean()
  }

  @Test
  fun testSuppressWithCommandLineFlags() {
    // Tests what happens when the loaded lint rule was compiled with
    // a newer version of the lint apis than the current host, but the
    // API accesses all seem to be okay
    val root = temporaryFolder.newFolder()

    val lintJarProject =
      lint()
        .files(
          *lintApiStubs,
          bytecode(
            "lint.jar",
            source(
              "META-INF/services/com.android.tools.lint.client.api.IssueRegistry",
              "test.pkg.MyIssueRegistry",
            ),
            0x70522285,
          ),
          bytecode(
            "lint.jar",
            kotlin(
                """
                package test.pkg

                import com.android.tools.lint.client.api.*
                import com.android.tools.lint.detector.api.*
                import com.intellij.psi.*
                import org.jetbrains.uast.*

                class MyIssueRegistry : IssueRegistry() {
                  override val issues: List<Issue> = listOf(ISSUE)
                  override val api: Int = 15
                  override val vendor: Vendor = Vendor(
                    vendorName = "AOSP",
                    contact = "AOSP"
                  )
                }

                class MyDetector : Detector(), SourceCodeScanner {
                  override fun getApplicableMethodNames(): List<String> = listOf("trigger")
                  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
                    context.report(ISSUE, node, context.getLocation(node), "Found trigger")
                  }
                }

                private val ISSUE =
                  Issue.create(
                    id = "_TestIssueId",
                    briefDescription = "Desc",
                    explanation = "Desc",
                    suppressAnnotations = listOf("test.pkg.MySuppress"),
                    implementation = Implementation(
                      MyDetector::class.java,
                      Scope.JAVA_FILE_SCOPE
                    ),
                  )
                """
              )
              .indented(),
            0xd29865d8,
            """
            META-INF/main.kotlin_module:
            H4sIAAAAAAAA/2NgYGBmYGBgBGJOBijgMuZSTM7P1UvMSynKz0zRK8nPzynW
            y8nMK9FLzslMBVKJBZlCfM5gdnxxSWlSsXcJlywXR0lqcYleQXa6kKBvpWdx
            cWlqUGp6ZnFJUaV3iRKDFgMARSnsbWwAAAA=
            """,
            """
            test/pkg/MyDetector.class:
            H4sIAAAAAAAA/51VbU8bRxB+9mxsY97M0VBe0tQtkBgwnKFJ09aElhAoJual
            OKBKfDrOG7NwvrNu14hI/cBv6S9ov7SoHyrUj/1NVdXZs4spRpGp5J2ZG8/M
            zjwzu/vn37/9DuAp9hmGFJfKqp1WrK13r7jijvKDOBhD1vGrlu2VA1+ULeX7
            rrRc4Smr3DSy7JqwWh4RhmedeJT8euDwVb/MS47teZxcuxhiS8ITapkhkpk+
            6EUciSSi6GaIqmMhGR4U70gzzzBS4WqlVnOFYx+5fIurY7+8bVc5uZiZ6eKJ
            fWZbdSVcqyikIvvHbcqlxrdrexWrpALhVfLLZDhR9IOKdcLVUWALT1JZnq9s
            JXySt321XXddsoqTQ6XCgwQGGdKnvqJ6Lcd3XcovNF1tya9VHENUqUub7rxl
            mMzc2Hnn6ITM8m0Z9+IBhpP4AB8yDJwJKVSjxlXbdRlUptgJ5JsUc9X3FD9X
            +Vtl1W1CdV9HWzuvBVxKyjQfBqUo3HXFiVWTwtqVorFvfvqAqnYawRIYZ3jU
            rPrkrOETeLZrFTyNpBSOjOMj6p5zzJ3TJmy7dkANIkOGJ3dA0N4OPRAfI53E
            I3xCA+HR6CQwQUhWw5QSmGIYuzEeBSnrfI9XCL/gnUb9CcOg7ThU3SSNS6FU
            2l+brDHM0Sx0Al8YjzoxjZkkMphlGL8LxDWXV7lH280xWPdsSxwWQw8lV/Sd
            cMgYtjJ3tqq5S76z1P8NR9kvYDGJHD5j6Fv36145fT26zxj6A17zAzVZ5m/t
            uqsY/vofk9U5lu+t7H6FtY9LZ/6kWRfn+cIdZ5DG7Tm+0GB9ybBwbxzoZHdy
            yBgevu+c0cgWmyeLNHbZVjbpjOpZhC5upkm3JmBgp6Q/F/orR1J5geGHq4tc
            0hgxWitBK5Wm1Z+8uiBmhGzkFhu5ulg0coyW8bLrjx9jRiqy2Z+KjiXMqGnk
            YrmujeHNdCo+ZuQSi7FUN/Ek8R7ivcT7iPdvDOscFhnmOwLuxlX+vCOHtueD
            PAkE89axnz+lIY5qM7o3qdl8u1494sEb/UqQtR4f98AOhP5uKrtLouLZqh6Q
            PL5X95So8oKn71z6e6V1/VN/b/97faX9xyzZyHVd6OijTZ+Dtnh0Mg166+g+
            ow5GiNPjR3SLvizdXuJdM78i+TMJBraJxkLlIHaI9jYM0EOSBqIP/RREO+ea
            zjHTvMTIT7e8zRvesab3bmgzgO+aVqkw4ijGSKsjnlNiUeLZWfPhJT7NmpNE
            58zHRGcvkc1eYX5Wk1/w1PycpVLsEl+1sk4SN+klm6UnTe8904jV3FtL48iH
            +WQxgSWy1tIUXlA9eyQPRa5TalEDpZAW8Yb496RdJjC+PkSkgG8KWCngJVZJ
            xKsC1rB+CCbxLTYOMSDRK1GQ2JRISPRJvA5/oyEdl8hLTEgsSUxJvPgH8aZe
            0jEJAAA=
            """,
            """
            test/pkg/MyIssueRegistry.class:
            H4sIAAAAAAAA/6VUS1MbRxD+ZvVeBCwKToTwA8eyLQmbFcR5CuNgsJMtC3Ch
            RJUUp0UaK4NWu9TOSBXnxD33/ICcc0iqQtmVVKUoH/OjUuldyQZLViW2Dzs9
            3dPd83X3t/P3P3/8BeAWLIas4lKZh+2WufXYkrLLd3lLSOU/ToAxmA2vY9pu
            0/dE01Se50jTEa4yG47gJOxDYQ4FRRjiq8IVao0hUijW04ghriOKBENu3F0P
            VAIphhm70eBS5ltcWbXa1/fyhww3C8XqGBBNrnhDef4pjEoaE0jr0DHJsND2
            FPlRCY5DfsJzpblxug/unCawDiHYecSQL1QP7J5tOrbbMnf2D8itUuybuko4
            ZpX8KP8MMjoMvEORIrhSMhijXufwbgoa3qMeEDgGZqUxh1xgm2co/HdX69xt
            en4CFxii6zu1h0lcYvjhLMSa8oXbqryZxaoOmnPQ65h0Nfdd2zE3+SO766gN
            6o7yu0Frt2y/zf1Kf4yXdVzE+1R4LwTHUBo3mZFCqCV5XA3Kv0YFqe8EtS1X
            HUeHCkMq4MCgv5nCyBwY7owYV1+DJ2uU4UrV81vmAVf7vi2IG7bresru82Tb
            U9tdxyGvOAFZD0ZIZLb6uOqD8m+Mp+ZoAxhuvyXemecz2+LKbtrKJpvW6UXo
            T2bBkgoWENfaZP9eBFqZds1lhh9Pjgq6ltX6X5I+I0kyQnJhYIs8P8ueHK1o
            ZXY39uznuGZou7NGJKeVo988+2mTLEn95CgXTcaM+G7OSOSSmWhGK6fKSTqO
            nh7rxgTFpSlu8uWDKWM6gLTCsPw/WjdMCipu8TWaRswZItZSW9EIa6Ll2qrr
            c4b53a6rRIdbbk9Ise/w9VMSEFE3vCY5TVeFy7e7nX3uf2WTD+Wteg3bqdu+
            CPSBUa95Xb/B74tAmRskro+kxTL9BVEaTgSZ4J0g7S5pGlawQTJORd4kmQne
            i1DSj0O2s2dRkrFQ2yRNIkU7oFh6gmTpKaaeYvYY2ZIxfYzzpT9x8dvMAmOZ
            BSPOnuDKMa7/Fl53j9ZrFAokKWEKU/RqztLzOYc05klewCQukSzQ+X3ySvcv
            Ib0Y0qyERSohAGAGpCMZK/2O7K8vksdDY+pMcGwQ3K/3xks1MSyFiYYTnv9l
            KKH+ioQM5bCtw8HXh9FMvCL4LAoNX4TrOr4kuUfWD8jv1h4iFj608JGFj/EJ
            bfGphc9QIQeJVdzegyFRlFiTiEvMSdyRWJIwJc6F+88lShKLEnmJqxJlIsG/
            iAGvHoQHAAA=
            """,
            """
            test/pkg/MyIssueRegistryKt.class:
            H4sIAAAAAAAA/51U3U4bVxD+Zk1Ye0vAmLQBQ8ifm9hAWCAh/TFNSgy02yxQ
            ZVPUlgt0WJ9YC+tda/csqi8qod72LfoELb1o1EoVymWfpk9QZdaxAkq5MNjS
            mTmz38yZ+eac+ee/P/8G8AAWoahkrMzWfsNcb1txnMhnsuHFKmo/VTqIkN8T
            B8L0RdAwN3f3pMvWDGFYuK6M41JDKstxvlkttQj3yhXbDZumCOpR6NVNFYZ+
            bPpeoMy6VOwZRqZoeWbnlCrhUseTMH0OrwHoyOagIUfILrkM89QjQqZc2SJU
            eo6j4zIhVwubLRF4YUBY7D2H0ls3zmYI+RwGMUwYSXmcZR5n19tO0mpFTE8W
            I4Qb+6HiUKYb+j6HYr/YrJ3oKc3vE/p9Jn3zBaFUtt9lvFp5Y0qU55s24/jg
            qxg18AHGCAs9Zd5s+bIpAyXSQ3WMdxPuNn6li9VxrUcaHTdsMY3XCUNfLW8t
            76xZ9uqOU9v8mhs6cird1SBpOjLN+CZu5XADt7nWpW7b7p6uteaLOK6e4VrZ
            GsCHuGNgAncJAzvPOe9OJ6x6FhVC34qM3SymCVdOnE8Y1nGPcP8C7dVhEgbd
            SAolS3X5QiS+Ivq5fKGrcqpQR0Ve0OjNcv7eXjRKjatshFG7avUEd+SBjDzV
            rn5/6rwnDJYiOKuJ9lmt4bPOuOvnmQbzWDAwh/uE23YYNcw9qXYj4fETE0EQ
            vmEkNjdCtZH4Ps+cYbv7GtelEnWhBNu05kGGpyGlSy5dQKD9VNH44w9eqs2x
            Vp8nTB4f9hvHh4Y2qt0azB8fFrU5+vbVT32vfunX2J6iFgiFd0bp7L4ijD9L
            AuU1pRUceLG368vlkwz5EtfCuuTHZHuB3EiauzJ6LhjD990KAhl1HodknOGE
            SeTKNS/9NtYNufW/gEyMhr5OKcV0ZLKs8a6f5WJaWH4Mlzq7Fd5NsEx/fUcw
            fu34rHaxqZ7FexjoIn/kgZfSsXiEwtPClZco2n9h4rvC5BFKf6C8PlWYKswU
            ZmaIMvyn6WPMDuv/0ks8+B3Gb29DP8RlXsc4wWswMIk8z4URllfZNsoJFzHO
            SU2wZRLX2XaHcVMsM1hjP4OjzPHQHWLLF2kxeIIvWc6z/SGX9dE2MhY+tvCJ
            hU9RtbCEzyw8wuNtUIzPsbwNLYYeI/saqnpWgggHAAA=
            """,
          ),
        )
        .testModes(TestMode.DEFAULT)
        .createProjects(root)

    val lintJar = File(lintJarProject.single(), "lint.jar")
    assertTrue(lintJar.exists())

    val project =
      lint()
        .files(
          kotlin(
              """
            package test.pkg
            fun test() {
                trigger() // ERROR
            }
            fun trigger() { }
            """
            )
            .indented(),
          // Defeat the "No .class files were found in project" warning
          source("bin/classes/Empty.txt", ""),
        )
        .createProjects(temporaryFolder.newFolder())
        .single()

    val sharedArgs =
      arrayOf(
        "-q",
        "--exitcode",
        "--sdk-home",
        TestUtils.getSdk().toString(),
        "--text",
        "stdout",
        "--lint-rule-jars",
        lintJar.path,
        project.path,
      )

    // First, make sure we actually pick up the custom lint check and flag it
    MainTest.checkDriver(
      """
      src/test/pkg/test.kt:3: Warning: Found trigger [_TestIssueId]
          trigger() // ERROR
          ~~~~~~~~~
      0 errors, 1 warnings
      """
        .trimIndent(),
      "",
      LintCliFlags.ERRNO_SUCCESS,
      sharedArgs,
      null,
      null,
    )

    // Then, verify that --disable both fails to disable *and* generates a warning that disable is
    // not allowed for
    // this issue type
    MainTest.checkDriver(
      """
      app: Error: Issue _TestIssueId is not allowed to be suppressed (but can be with @test.pkg.MySuppress) [LintError]
      src/test/pkg/test.kt:3: Warning: Found trigger [_TestIssueId]
          trigger() // ERROR
          ~~~~~~~~~
      1 errors, 1 warnings
      """
        .trimIndent(),
      "",
      LintCliFlags.ERRNO_ERRORS,
      arrayOf("--disable", "_TestIssueId") + sharedArgs,
      null,
      null,
    )

    // Finally, use the protected disable flag to disable the issue without
    // a warning

    MainTest.checkDriver(
      "No issues found.",
      "",
      LintCliFlags.ERRNO_SUCCESS,
      arrayOf("--Xdisable", "_TestIssueId") + sharedArgs,
      null,
      null,
    )
  }

  // Sample detector which just flags calls to a method called "forbidden"
  @SuppressWarnings("ALL")
  class MySecurityDetector : Detector(), SourceCodeScanner, XmlScanner {
    override fun getApplicableUastTypes() = listOf(UImportStatement::class.java)

    override fun getApplicableMethodNames(): List<String> {
      return listOf("forbidden", "println")
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
      val message = "Some error message here"

      val location = context.getLocation(node)
      if (
        node.valueArguments.firstOrNull()?.evaluateString() ==
          TEST_ISSUE_SUPPRESS_ANNOTATION_SAME_AS_ID.id
      ) {
        context.report(TEST_ISSUE_SUPPRESS_ANNOTATION_SAME_AS_ID, node, location, message)
        return
      }
      context.report(TEST_ISSUE, node, location, message)
      context.report(TEST_ISSUE_NEVER_SUPPRESSIBLE, node, location, message)
    }

    override fun createUastHandler(context: JavaContext): UElementHandler = Handler(context)

    private class Handler(private val context: JavaContext) : UElementHandler() {
      override fun visitImportStatement(node: UImportStatement) {
        node.importReference?.let { importReference ->
          if (importReference.asSourceString().contains("forbidden")) {
            val message = "Some error message here"
            val location = context.getLocation(node)
            context.report(TEST_ISSUE, node, location, message)
          }
        }
      }
    }

    override fun getApplicableAttributes(): Collection<String> = listOf("forbidden")

    override fun visitAttribute(context: XmlContext, attribute: Attr) {
      val message = "Some error message here"
      val location = context.getLocation(attribute)
      if (attribute.value == TEST_ISSUE_SUPPRESS_ANNOTATION_SAME_AS_ID.id) {
        context.report(TEST_ISSUE_SUPPRESS_ANNOTATION_SAME_AS_ID, attribute, location, message)
        return
      }
      context.report(TEST_ISSUE, attribute, location, message)
      context.report(TEST_ISSUE_NEVER_SUPPRESSIBLE, attribute, location, message)
    }

    companion object {
      @Suppress("SpellCheckingInspection")
      @JvmField
      val TEST_ISSUE =
        Issue.create(
          id = "_SecureIssue",
          briefDescription = "Some important security issue",
          explanation = "Blahdiblah",
          category = Category.SECURITY,
          priority = 10,
          severity = Severity.WARNING,
          suppressAnnotations = listOf("foo.bar.MyOwnAnnotation"),
          implementation =
            Implementation(MySecurityDetector::class.java, Scope.JAVA_AND_RESOURCE_FILES),
        )

      @Suppress("SpellCheckingInspection")
      @JvmField
      val TEST_ISSUE_NEVER_SUPPRESSIBLE =
        Issue.create(
          id = "_SecureIssue2",
          briefDescription = "Some important security issue",
          explanation = "Blahdiblah",
          category = Category.SECURITY,
          priority = 10,
          severity = Severity.WARNING,
          suppressAnnotations = emptyList(),
          implementation =
            Implementation(MySecurityDetector::class.java, Scope.JAVA_AND_RESOURCE_FILES),
        )

      @Suppress("SpellCheckingInspection")
      @JvmField
      val TEST_ISSUE_SUPPRESS_ANNOTATION_SAME_AS_ID =
        Issue.create(
          id = "_SecureIssue3",
          briefDescription = "Some important security issue",
          explanation = "Blahdiblah",
          category = Category.SECURITY,
          priority = 10,
          severity = Severity.WARNING,
          suppressAnnotations = listOf("_SecureIssue3"),
          implementation =
            Implementation(MySecurityDetector::class.java, Scope.JAVA_AND_RESOURCE_FILES),
        )
    }
  }

  // Sample detector which just flags the property accessors named forbidden
  @SuppressWarnings("ALL")
  class MyPropertyDetector : Detector(), SourceCodeScanner, XmlScanner {
    override fun getApplicableUastTypes() =
      listOf(UImportStatement::class.java, UMethod::class.java, UField::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = Handler(context)

    private class Handler(private val context: JavaContext) : UElementHandler() {
      override fun visitImportStatement(node: UImportStatement) {}

      override fun visitField(node: UField) {
        val name = node.name
        if (name == "forbidden") {
          val message = "Some error message here"
          val location = context.getLocation(node)
          context.report(PROPERTY_ISSUE, node, location, message)
        }
      }

      override fun visitMethod(node: UMethod) {
        val name = node.name
        if (name.startsWith("getForbidden") || name.startsWith("setForbidden")) {
          val message = "Some error message here"
          val location = context.getLocation(node)
          context.report(PROPERTY_ISSUE, node, location, message)
        }
      }
    }

    companion object {
      @Suppress("SpellCheckingInspection")
      @JvmField
      val PROPERTY_ISSUE =
        Issue.create(
          id = "_PropertyIssue",
          briefDescription = "Some issue",
          explanation = "Blahdiblah",
          category = Category.CORRECTNESS,
          priority = 10,
          severity = Severity.WARNING,
          implementation = Implementation(MyPropertyDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
  }
}
