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
import com.android.tools.lint.checks.infrastructure.TestFiles.gradle
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFiles.xml
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
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
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UImportStatement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.evaluateString
import org.junit.Test
import org.w3c.dom.Attr

/**
 * Checks that some lint checks cannot be suppressed with the normal suppression annotations or
 * mechanisms.
 */
class SuppressLintTest {

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
