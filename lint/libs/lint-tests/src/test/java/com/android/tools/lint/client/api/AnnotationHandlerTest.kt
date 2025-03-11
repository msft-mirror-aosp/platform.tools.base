/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tools.lint.client.api

import com.android.testutils.TestUtils.getSdk
import com.android.tools.lint.LintCliFlags.ERRNO_SUCCESS
import com.android.tools.lint.MainTest
import com.android.tools.lint.checks.infrastructure.ProjectDescription
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.bytecode
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.klib
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.checks.infrastructure.dos2unix
import com.android.tools.lint.detector.api.AnnotationInfo
import com.android.tools.lint.detector.api.AnnotationOrigin
import com.android.tools.lint.detector.api.AnnotationUsageInfo
import com.android.tools.lint.detector.api.AnnotationUsageType
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LocationType
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.useFirUast
import com.google.common.io.Files
import java.io.File
import org.intellij.lang.annotations.Language
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.junit.Assume
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnnotationHandlerTest {
  private fun lint(includesDefinition: Boolean = false) =
    TestLintTask.lint()
      .sdkHome(getSdk().toFile())
      .issues(
        if (includesDefinition) MyAnnotationDetectorDefinitionToo.TEST_ISSUE
        else MyAnnotationDetector.TEST_ISSUE
      )

  private val javaAnnotation: TestFile =
    java(
        """
        package pkg.java;
        public @interface MyJavaAnnotation {
        }
        """
      )
      .indented()

  private val kotlinAnnotation: TestFile =
    kotlin(
        """
        package pkg.kotlin
        annotation class MyKotlinAnnotation
        """
      )
      .indented()

  private val experimentalKotlinAnnotation: TestFile =
    kotlin(
        """
        package pkg.kotlin
        @MyKotlinAnnotation
        annotation class ExperimentalKotlinAnnotation
        """
      )
      .indented()

  @Test
  fun testReferenceKotlinAnnotation() {
    lint()
      .files(
        java(
            """
                    package test.pkg;
                    import pkg.java.MyJavaAnnotation;
                    import pkg.kotlin.MyKotlinAnnotation;

                    public class JavaUsage {
                        public void test() {
                            new JavaApi().method1();
                            new JavaApi().method2();
                            new KotlinApi().method1();
                            new KotlinApi().method2();
                        }
                    }
                    """
          )
          .indented(),
        kotlin(
            """
                    package test.pkg
                    import pkg.java.MyJavaAnnotation
                    import pkg.kotlin.MyKotlinAnnotation

                    class KotlinUsage {
                        fun test() {
                            JavaApi().method1()
                            JavaApi().method2()
                            KotlinApi().method1()
                            KotlinApi().method2()
                        }

                        @Suppress("_AnnotationIssue")
                        fun suppressedId1() {
                            JavaApi().method1()
                        }

                        fun suppressedId2() {
                            //noinspection _AnnotationIssue
                            KotlinApi().method1()
                        }

                        @Suppress("Correctness:Test Category")
                        fun suppressedCategory1() {
                            JavaApi().method1()
                        }

                        fun suppressedCategory2() {
                            //noinspection Correctness
                            KotlinApi().method1()
                        }

                        @Suppress("Correctness")
                        fun suppressedCategory3() {
                            JavaApi().method1()
                        }

                        fun suppressedCategory4() {
                            //noinspection Correctness:Test Category
                            KotlinApi().method1()
                        }
                    }
                    """
          )
          .indented(),
        java(
            """
                    package test.pkg;
                    import pkg.java.MyJavaAnnotation;
                    import pkg.kotlin.MyKotlinAnnotation;

                    public class JavaApi {
                        @MyJavaAnnotation
                        public void method1() {
                        }

                        @MyKotlinAnnotation
                        public void method2() {
                        }
                    }
                    """
          )
          .indented(),
        kotlin(
            """
                    package test.pkg
                    import pkg.java.MyJavaAnnotation
                    import pkg.kotlin.MyKotlinAnnotation

                    class KotlinApi {
                        @MyJavaAnnotation
                        fun method1() {
                        }

                        @MyKotlinAnnotation
                        fun method2() {
                        }
                    }
                    """
          )
          .indented(),
        javaAnnotation,
        kotlinAnnotation,
      )
      .run()
      .expect(
        """
            src/test/pkg/JavaUsage.java:7: Error: METHOD_CALL usage associated with @MyJavaAnnotation on METHOD [_AnnotationIssue]
                    new JavaApi().method1();
                    ~~~~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/JavaUsage.java:8: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                    new JavaApi().method2();
                    ~~~~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/JavaUsage.java:9: Error: METHOD_CALL usage associated with @MyJavaAnnotation on METHOD [_AnnotationIssue]
                    new KotlinApi().method1();
                    ~~~~~~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/JavaUsage.java:10: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                    new KotlinApi().method2();
                    ~~~~~~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/KotlinUsage.kt:7: Error: METHOD_CALL usage associated with @MyJavaAnnotation on METHOD [_AnnotationIssue]
                    JavaApi().method1()
                    ~~~~~~~~~~~~~~~~~~~
            src/test/pkg/KotlinUsage.kt:8: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                    JavaApi().method2()
                    ~~~~~~~~~~~~~~~~~~~
            src/test/pkg/KotlinUsage.kt:9: Error: METHOD_CALL usage associated with @MyJavaAnnotation on METHOD [_AnnotationIssue]
                    KotlinApi().method1()
                    ~~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/KotlinUsage.kt:10: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                    KotlinApi().method2()
                    ~~~~~~~~~~~~~~~~~~~~~
            8 errors, 0 warnings
            """
      )
  }

  @Test
  fun testFieldReferences() {
    lint()
      .files(
        java(
            """
                package test.api;
                import pkg.java.MyJavaAnnotation;
                import pkg.kotlin.MyKotlinAnnotation;

                @MyJavaAnnotation
                @MyKotlinAnnotation
                public class Api {
                    public Api next = null;
                    public Object field = null;
                }
                """
          )
          .indented(),
        java(
            """
                package test.usage;
                import test.api.Api;
                public class Usage {
                    private void use(Object o) { }
                    public void test(Api api) {
                        use(api.field);      // ERROR 1A and 1B
                        use(api.next.field); // ERROR 2A, 2B on next, 3A, 3B on field
                    }
                }
                """
          )
          .indented(),
        kotlin(
            """
                package test.usage
                import test.api.Api
                private fun use(o: Any?) { }
                fun test(api: Api) {
                    use(api.field)       // ERROR 4A and 4B
                    use(api.next.field)  // ERROR 5A, 5B on next, 6A, 6B on field
                }
                """
          )
          .indented(),
        javaAnnotation,
        kotlinAnnotation,
      )
      .run()
      .expect(
        """
            src/test/api/Api.java:8: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                public Api next = null;
                       ~~~
            src/test/api/Api.java:8: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                public Api next = null;
                       ~~~
            src/test/usage/Usage.java:5: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                public void test(Api api) {
                                 ~~~
            src/test/usage/Usage.java:5: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                public void test(Api api) {
                                 ~~~
            src/test/usage/Usage.java:6: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                    use(api.field);      // ERROR 1A and 1B
                            ~~~~~
            src/test/usage/Usage.java:6: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                    use(api.field);      // ERROR 1A and 1B
                            ~~~~~
            src/test/usage/Usage.java:7: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                    use(api.next.field); // ERROR 2A, 2B on next, 3A, 3B on field
                            ~~~~
            src/test/usage/Usage.java:7: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                    use(api.next.field); // ERROR 2A, 2B on next, 3A, 3B on field
                                 ~~~~~
            src/test/usage/Usage.java:7: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                    use(api.next.field); // ERROR 2A, 2B on next, 3A, 3B on field
                            ~~~~
            src/test/usage/Usage.java:7: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                    use(api.next.field); // ERROR 2A, 2B on next, 3A, 3B on field
                                 ~~~~~
            src/test/usage/test.kt:4: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
            fun test(api: Api) {
                          ~~~
            src/test/usage/test.kt:4: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
            fun test(api: Api) {
                          ~~~
            src/test/usage/test.kt:5: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                use(api.field)       // ERROR 4A and 4B
                        ~~~~~
            src/test/usage/test.kt:5: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                use(api.field)       // ERROR 4A and 4B
                        ~~~~~
            src/test/usage/test.kt:6: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                use(api.next.field)  // ERROR 5A, 5B on next, 6A, 6B on field
                        ~~~~
            src/test/usage/test.kt:6: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                use(api.next.field)  // ERROR 5A, 5B on next, 6A, 6B on field
                             ~~~~~
            src/test/usage/test.kt:6: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                use(api.next.field)  // ERROR 5A, 5B on next, 6A, 6B on field
                        ~~~~
            src/test/usage/test.kt:6: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                use(api.next.field)  // ERROR 5A, 5B on next, 6A, 6B on field
                             ~~~~~
            18 errors, 0 warnings
            """
      )
  }

  @Test
  fun testDeclarationTypes() {
    // Regression test for b/228961124
    lint()
      .files(
        java(
            """
                package test.api;
                import pkg.java.MyJavaAnnotation;
                import pkg.kotlin.MyKotlinAnnotation;

                @MyJavaAnnotation
                @MyKotlinAnnotation
                public class Api { }
                """
          )
          .indented(),
        kotlin(
            """
                package test.usage.kt
                import test.api.Api
                abstract class C {
                  abstract val api: Api
                  abstract val list: List<Api>
                  fun get(): Api = Api()
                  fun f() = get() // Implicit type reference
                  val x = get() // Implicit type reference
                  abstract fun doSomething(api: Api): Api
                  fun doSomethingLists(list: List<Api>): List<Api> {
                    val x: Api? = null
                    val y = get() // Implicit type reference
                  }
                }
                """
          )
          .indented(),
        java(
            """
                package test.usage;
                import test.api.Api;
                public class C {
                    Api api;
                    List<Api> api;
                    private Api use(Api api) { }
                    public List<Api> useList(List<Api> list) {
                        Api x = null;
                    }
                }
                """
          )
          .indented(),
        javaAnnotation,
        kotlinAnnotation,
      )
      .skipTestModes(TestMode.TYPE_ALIAS)
      .run()
      .expect(
        """
        src/test/usage/C.java:4: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
            Api api;
            ~~~
        src/test/usage/C.java:4: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
            Api api;
            ~~~
        src/test/usage/C.java:5: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
            List<Api> api;
                 ~~~
        src/test/usage/C.java:5: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
            List<Api> api;
                 ~~~
        src/test/usage/C.java:6: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
            private Api use(Api api) { }
                    ~~~
        src/test/usage/C.java:6: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
            private Api use(Api api) { }
                            ~~~
        src/test/usage/C.java:6: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
            private Api use(Api api) { }
                    ~~~
        src/test/usage/C.java:6: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
            private Api use(Api api) { }
                            ~~~
        src/test/usage/C.java:7: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
            public List<Api> useList(List<Api> list) {
                        ~~~
        src/test/usage/C.java:7: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
            public List<Api> useList(List<Api> list) {
                                          ~~~
        src/test/usage/C.java:7: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
            public List<Api> useList(List<Api> list) {
                        ~~~
        src/test/usage/C.java:7: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
            public List<Api> useList(List<Api> list) {
                                          ~~~
        src/test/usage/C.java:8: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                Api x = null;
                ~~~
        src/test/usage/C.java:8: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                Api x = null;
                ~~~
        src/test/usage/kt/C.kt:4: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
          abstract val api: Api
                            ~~~
        src/test/usage/kt/C.kt:4: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
          abstract val api: Api
                            ~~~
        src/test/usage/kt/C.kt:5: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
          abstract val list: List<Api>
                                  ~~~
        src/test/usage/kt/C.kt:5: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
          abstract val list: List<Api>
                                  ~~~
        src/test/usage/kt/C.kt:6: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
          fun get(): Api = Api()
                     ~~~
        src/test/usage/kt/C.kt:6: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
          fun get(): Api = Api()
                     ~~~
        src/test/usage/kt/C.kt:6: Error: METHOD_CALL usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
          fun get(): Api = Api()
                           ~~~~~
        src/test/usage/kt/C.kt:6: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
          fun get(): Api = Api()
                           ~~~~~
        src/test/usage/kt/C.kt:8: Error: CLASS_REFERENCE_AS_IMPLICIT_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
          val x = get() // Implicit type reference
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/usage/kt/C.kt:8: Error: CLASS_REFERENCE_AS_IMPLICIT_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
          val x = get() // Implicit type reference
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/usage/kt/C.kt:9: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
          abstract fun doSomething(api: Api): Api
                                        ~~~
        src/test/usage/kt/C.kt:9: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
          abstract fun doSomething(api: Api): Api
                                              ~~~
        src/test/usage/kt/C.kt:9: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
          abstract fun doSomething(api: Api): Api
                                        ~~~
        src/test/usage/kt/C.kt:9: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
          abstract fun doSomething(api: Api): Api
                                              ~~~
        src/test/usage/kt/C.kt:10: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
          fun doSomethingLists(list: List<Api>): List<Api> {
                                          ~~~
        src/test/usage/kt/C.kt:10: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
          fun doSomethingLists(list: List<Api>): List<Api> {
                                                      ~~~
        src/test/usage/kt/C.kt:10: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
          fun doSomethingLists(list: List<Api>): List<Api> {
                                          ~~~
        src/test/usage/kt/C.kt:10: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
          fun doSomethingLists(list: List<Api>): List<Api> {
                                                      ~~~
        src/test/usage/kt/C.kt:11: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
            val x: Api? = null
                   ~~~~
        src/test/usage/kt/C.kt:11: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
            val x: Api? = null
                   ~~~~
        src/test/usage/kt/C.kt:12: Error: CLASS_REFERENCE_AS_IMPLICIT_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
            val y = get() // Implicit type reference
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/usage/kt/C.kt:12: Error: CLASS_REFERENCE_AS_IMPLICIT_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
            val y = get() // Implicit type reference
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        36 errors, 0 warnings
        """
      )
  }

  @Test
  fun testFileLevelAnnotations() {
    lint()
      .files(
        kotlin(
            """
                package pkg.kotlin
                @Target(AnnotationTarget.FILE)
                annotation class MyKotlinAnnotation
                """
          )
          .indented(),
        java(
            """
                    package test.pkg;

                    public class JavaUsage {
                        public void test() {
                            new KotlinApi().method();
                        }
                    }
                    """
          )
          .indented(),
        kotlin(
            """
                    package test.pkg

                    class KotlinUsage {
                        fun test() {
                            KotlinApi().method()
                            method2()
                        }
                    }
                    """
          )
          .indented(),
        kotlin(
            """
                    @file:MyKotlinAnnotation
                    package test.pkg
                    import pkg.kotlin.MyKotlinAnnotation
                    class KotlinApi {
                        fun method() {
                        }
                    }
                    fun method2() { }
                    """
          )
          .indented(),
      )
      .run()
      .expect(
        """
            src/test/pkg/JavaUsage.java:5: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on FILE [_AnnotationIssue]
                    new KotlinApi().method();
                    ~~~~~~~~~~~~~~~
            src/test/pkg/JavaUsage.java:5: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on FILE [_AnnotationIssue]
                    new KotlinApi().method();
                    ~~~~~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/KotlinUsage.kt:5: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on FILE [_AnnotationIssue]
                    KotlinApi().method()
                    ~~~~~~~~~~~
            src/test/pkg/KotlinUsage.kt:5: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on FILE [_AnnotationIssue]
                    KotlinApi().method()
                    ~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/KotlinUsage.kt:6: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                    method2()
                    ~~~~~~~~~
            5 errors, 0 warnings
            """
      )
  }

  @Test
  fun testOuterClassReferences() {
    lint()
      .files(
        java(
            """
                package test.api;
                import pkg.java.MyJavaAnnotation;
                import pkg.kotlin.MyKotlinAnnotation;

                @MyJavaAnnotation
                @MyKotlinAnnotation
                public class Api {
                    public static class InnerApi {
                        public static String method() { return ""; }
                        public static final String CONSTANT = "";
                    }
                }
                """
          )
          .indented(),
        java(
            """
                package test.usage;
                import test.api.Api;
                import test.api.Api.InnerApi;
                import static test.api.Api.InnerApi.CONSTANT;
                import static test.api.Api.InnerApi.method;

                public class JavaUsage {
                    private void use(Object o) { }
                    public void test(InnerApi innerApi) {
                        use(InnerApi.CONSTANT); // ERROR 1A and 1B
                        use(CONSTANT);          // ERROR 2A and 2B
                        use(innerApi.method()); // ERROR 3A and 3B
                        use(method());          // ERROR 4A and 4B
                    }
                }
                """
          )
          .indented(),
        kotlin(
            """
                package test.usage

                import test.api.Api.InnerApi
                import test.api.Api.InnerApi.CONSTANT
                import test.api.Api.InnerApi.method

                class KotlinUsage {
                    private fun use(o: Any) {}
                    fun test(innerApi: InnerApi?) {
                        use(InnerApi.CONSTANT)     // ERROR 5A and 5B
                        use(CONSTANT)              // ERROR 6A and 6B
                        use(InnerApi.method())     // ERROR 7A and 7B
                        use(method())              // ERROR 8A and 8B
                    }
                }
                """
          )
          .indented(),
        javaAnnotation,
        kotlinAnnotation,
      )
      .run()
      .expect(
        """
            src/test/usage/JavaUsage.java:9: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                public void test(InnerApi innerApi) {
                                 ~~~~~~~~
            src/test/usage/JavaUsage.java:9: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                public void test(InnerApi innerApi) {
                                 ~~~~~~~~
            src/test/usage/JavaUsage.java:10: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(InnerApi.CONSTANT); // ERROR 1A and 1B
                                 ~~~~~~~~
            src/test/usage/JavaUsage.java:10: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(InnerApi.CONSTANT); // ERROR 1A and 1B
                                 ~~~~~~~~
            src/test/usage/JavaUsage.java:11: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(CONSTANT);          // ERROR 2A and 2B
                        ~~~~~~~~
            src/test/usage/JavaUsage.java:11: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(CONSTANT);          // ERROR 2A and 2B
                        ~~~~~~~~
            src/test/usage/JavaUsage.java:12: Error: METHOD_CALL usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(innerApi.method()); // ERROR 3A and 3B
                        ~~~~~~~~~~~~~~~~~
            src/test/usage/JavaUsage.java:12: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(innerApi.method()); // ERROR 3A and 3B
                        ~~~~~~~~~~~~~~~~~
            src/test/usage/JavaUsage.java:13: Error: METHOD_CALL usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(method());          // ERROR 4A and 4B
                        ~~~~~~~~
            src/test/usage/JavaUsage.java:13: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(method());          // ERROR 4A and 4B
                        ~~~~~~~~
            src/test/usage/KotlinUsage.kt:9: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                fun test(innerApi: InnerApi?) {
                                   ~~~~~~~~~
            src/test/usage/KotlinUsage.kt:9: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                fun test(innerApi: InnerApi?) {
                                   ~~~~~~~~~
            src/test/usage/KotlinUsage.kt:10: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(InnerApi.CONSTANT)     // ERROR 5A and 5B
                                 ~~~~~~~~
            src/test/usage/KotlinUsage.kt:10: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(InnerApi.CONSTANT)     // ERROR 5A and 5B
                                 ~~~~~~~~
            src/test/usage/KotlinUsage.kt:11: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(CONSTANT)              // ERROR 6A and 6B
                        ~~~~~~~~
            src/test/usage/KotlinUsage.kt:11: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(CONSTANT)              // ERROR 6A and 6B
                        ~~~~~~~~
            src/test/usage/KotlinUsage.kt:12: Error: METHOD_CALL usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(InnerApi.method())     // ERROR 7A and 7B
                        ~~~~~~~~~~~~~~~~~
            src/test/usage/KotlinUsage.kt:12: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(InnerApi.method())     // ERROR 7A and 7B
                        ~~~~~~~~~~~~~~~~~
            src/test/usage/KotlinUsage.kt:13: Error: METHOD_CALL usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(method())              // ERROR 8A and 8B
                        ~~~~~~~~
            src/test/usage/KotlinUsage.kt:13: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(method())              // ERROR 8A and 8B
                        ~~~~~~~~
            20 errors, 0 warnings
            """
      )
  }

  @Test
  fun testClassReference() {
    lint()
      .files(
        java(
            """
                package test.api;
                import pkg.java.MyJavaAnnotation;

                @MyJavaAnnotation
                public class Api {
                    public static class InnerApi {
                    }
                }
                """
          )
          .indented(),
        java(
            """
                package test.usage;
                import test.api.Api.InnerApi;

                public class JavaUsage {
                    private void use(Object o) { }
                    public void test() {
                        use(InnerApi.class); // ERROR1
                    }
                }
                """
          )
          .indented(),
        kotlin(
            """
                package test.usage
                import test.api.Api.InnerApi

                private fun use(o: Any) {}
                fun test() {
                    use(InnerApi::class.java)  // ERROR2
                }
                """
          )
          .indented(),
        javaAnnotation,
      )
      .run()
      .expect(
        """
            src/test/usage/JavaUsage.java:7: Error: CLASS_REFERENCE usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    use(InnerApi.class); // ERROR1
                        ~~~~~~~~~~~~~~
            src/test/usage/test.kt:6: Error: CLASS_REFERENCE usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                use(InnerApi::class.java)  // ERROR2
                    ~~~~~~~~~~~~~~~
            2 errors, 0 warnings
            """
      )
  }

  @Test
  fun testTypeReferences() {
    lint()
      .files(
        java(
            """
                package test.api;
                import pkg.java.MyJavaAnnotation;

                @MyJavaAnnotation
                public class Api { }
                """
          )
          .indented(),
        java(
            """
                package test.usage;
                import test.api.Api;

                public class JavaUsage {
                    private void use(Api api) { }
                    public void test(Object o) {
                        use((Api) o); // ERROR
                    }
                }
                """
          )
          .indented(),
        kotlin(
            """
                package test.usage
                import test.api.Api

                private fun use(api: Api) {}
                fun test(o: Object) {
                    use(o as Api)  // ERROR2
                }
                """
          )
          .indented(),
        javaAnnotation,
      )
      .run()
      .expect(
        """
            src/test/usage/JavaUsage.java:5: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                private void use(Api api) { }
                                 ~~~
            src/test/usage/JavaUsage.java:7: Error: CLASS_REFERENCE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                    use((Api) o); // ERROR
                         ~~~
            src/test/usage/test.kt:4: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
            private fun use(api: Api) {}
                                 ~~~
            src/test/usage/test.kt:6: Error: CLASS_REFERENCE usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
                use(o as Api)  // ERROR2
                         ~~~
            4 errors, 0 warnings
        """
      )
  }

  @Test
  fun testFieldAssignment() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg
            import pkg.kotlin.MyKotlinAnnotation

            class MyClass {
              @MyKotlinAnnotation
              var property = 1 // ERROR1 - ASSIGNMENT_RHS

              fun f() {
                var x = property // ERROR2 - ASSIGNMENT_LHS, ERROR3 - FIELD_REFERENCE
                x = property // ERROR4 - ASSIGNMENT_LHS, ERROR5 - FIELD_REFERENCE
                property = 2 // ERROR6 - ASSIGNMENT_RHS, ERROR7 - FIELD_REFERENCE
              }

              @MyKotlinAnnotation
              lateinit var manager: Manager
                private set

              fun g() {
                Manager().use {
                  manager = it // ERROR7 - ASSIGNMENT_RHS, ERROR8 - FIELD_REFERENCE
                }
              }
            }
            """
          )
          .indented(),
        java(
            """
            package test.pkg.java;
            import pkg.java.MyJavaAnnotation;

            class MyClass {
              @MyJavaAnnotation
              int field = 1; // ERROR1 - ASSIGNMENT_RHS

              public void f() {
                int x = field; // ERROR2 - ASSIGNMENT_LHS, ERROR3 - FIELD_REFERENCE
                x = field; // ERROR4 - ASSIGNMENT_LHS, ERROR5 - FIELD_REFERENCE
                field = 2; // ERROR6 - ASSIGNMENT_RHS, ERROR7 - FIELD_REFERENCE
              }
            }
            """
          )
          .indented(),
        kotlin(
            "src/test/test/pkg/Manager.kt",
            """
            package test.pkg
            import java.io.Closeable
            class Manager : Closeable
          """,
          )
          .indented(),
        kotlinAnnotation,
        javaAnnotation,
      )
      .run()
      .expect(
        """
        src/test/pkg/java/MyClass.java:6: Error: ASSIGNMENT_RHS usage associated with @MyJavaAnnotation on VARIABLE [_AnnotationIssue]
          int field = 1; // ERROR1 - ASSIGNMENT_RHS
                      ~
        src/test/pkg/java/MyClass.java:9: Error: ASSIGNMENT_LHS usage associated with @MyJavaAnnotation on FIELD [_AnnotationIssue]
            int x = field; // ERROR2 - ASSIGNMENT_LHS, ERROR3 - FIELD_REFERENCE
            ~~~~~~~~~~~~~~
        src/test/pkg/java/MyClass.java:9: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on FIELD [_AnnotationIssue]
            int x = field; // ERROR2 - ASSIGNMENT_LHS, ERROR3 - FIELD_REFERENCE
                    ~~~~~
        src/test/pkg/java/MyClass.java:10: Error: ASSIGNMENT_LHS usage associated with @MyJavaAnnotation on FIELD [_AnnotationIssue]
            x = field; // ERROR4 - ASSIGNMENT_LHS, ERROR5 - FIELD_REFERENCE
            ~
        src/test/pkg/java/MyClass.java:10: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on FIELD [_AnnotationIssue]
            x = field; // ERROR4 - ASSIGNMENT_LHS, ERROR5 - FIELD_REFERENCE
                ~~~~~
        src/test/pkg/java/MyClass.java:11: Error: ASSIGNMENT_RHS usage associated with @MyJavaAnnotation on FIELD [_AnnotationIssue]
            field = 2; // ERROR6 - ASSIGNMENT_RHS, ERROR7 - FIELD_REFERENCE
                    ~
        src/test/pkg/java/MyClass.java:11: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on FIELD [_AnnotationIssue]
            field = 2; // ERROR6 - ASSIGNMENT_RHS, ERROR7 - FIELD_REFERENCE
            ~~~~~
        src/test/pkg/MyClass.kt:6: Error: ASSIGNMENT_RHS usage associated with @MyKotlinAnnotation on VARIABLE [_AnnotationIssue]
          var property = 1 // ERROR1 - ASSIGNMENT_RHS
                         ~
        src/test/pkg/MyClass.kt:9: Error: ASSIGNMENT_LHS usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
            var x = property // ERROR2 - ASSIGNMENT_LHS, ERROR3 - FIELD_REFERENCE
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/MyClass.kt:9: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
            var x = property // ERROR2 - ASSIGNMENT_LHS, ERROR3 - FIELD_REFERENCE
                    ~~~~~~~~
        src/test/pkg/MyClass.kt:10: Error: ASSIGNMENT_LHS usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
            x = property // ERROR4 - ASSIGNMENT_LHS, ERROR5 - FIELD_REFERENCE
            ~
        src/test/pkg/MyClass.kt:10: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
            x = property // ERROR4 - ASSIGNMENT_LHS, ERROR5 - FIELD_REFERENCE
                ~~~~~~~~
        src/test/pkg/MyClass.kt:11: Error: ASSIGNMENT_RHS usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
            property = 2 // ERROR6 - ASSIGNMENT_RHS, ERROR7 - FIELD_REFERENCE
                       ~
        src/test/pkg/MyClass.kt:11: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
            property = 2 // ERROR6 - ASSIGNMENT_RHS, ERROR7 - FIELD_REFERENCE
            ~~~~~~~~
        src/test/pkg/MyClass.kt:20: Error: ASSIGNMENT_RHS usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
              manager = it // ERROR7 - ASSIGNMENT_RHS, ERROR8 - FIELD_REFERENCE
                        ~~
        src/test/pkg/MyClass.kt:20: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
              manager = it // ERROR7 - ASSIGNMENT_RHS, ERROR8 - FIELD_REFERENCE
              ~~~~~~~
        16 errors, 0 warnings
        """
      )
  }

  @Test
  fun testLocalVariables() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg
            import pkg.kotlin.MyKotlinAnnotation

            class MyClass {
              fun use(o: Any) {}

              fun f() {
                @MyKotlinAnnotation
                var x = 1 // ERROR1 - ASSIGNMENT_RHS
                x = 2 // ERROR2 - ASSIGNMENT_RHS, ERROR3 - VARIABLE_REFERENCE
                val y = x // ERROR4 - ASSIGNMENT_LHS, ERROR5 - VARIABLE_REFERENCE
                use(x) // ERROR6 - VARIABLE_REFERENCE
              }
            }
            """
          )
          .indented(),
        java(
            """
            package test.pkg.java;
            import pkg.java.MyJavaAnnotation;

            class MyClass {
              void use(Object o) {}

              void f() {
                @MyJavaAnnotation
                int x = 1; // ERROR1 - ASSIGNMENT_RHS
                x = 2; // ERROR2 - ASSIGNMENT_RHS, ERROR3 - VARIABLE_REFERENCE
                int y = x; // ERROR4 - ASSIGNMENT_LHS, ERROR5 - VARIABLE_REFERENCE
                use(x); // ERROR6 - VARIABLE_REFERENCE
              }
            }
            """
          )
          .indented(),
        kotlinAnnotation,
        javaAnnotation,
      )
      .run()
      .expect(
        """
        src/test/pkg/java/MyClass.java:9: Error: ASSIGNMENT_RHS usage associated with @MyJavaAnnotation on VARIABLE [_AnnotationIssue]
            int x = 1; // ERROR1 - ASSIGNMENT_RHS
                    ~
        src/test/pkg/java/MyClass.java:10: Error: VARIABLE_REFERENCE usage associated with @MyJavaAnnotation on VARIABLE [_AnnotationIssue]
            x = 2; // ERROR2 - ASSIGNMENT_RHS, ERROR3 - VARIABLE_REFERENCE
            ~
        src/test/pkg/java/MyClass.java:11: Error: ASSIGNMENT_LHS usage associated with @MyJavaAnnotation on VARIABLE [_AnnotationIssue]
            int y = x; // ERROR4 - ASSIGNMENT_LHS, ERROR5 - VARIABLE_REFERENCE
            ~~~~~~~~~~
        src/test/pkg/java/MyClass.java:11: Error: VARIABLE_REFERENCE usage associated with @MyJavaAnnotation on VARIABLE [_AnnotationIssue]
            int y = x; // ERROR4 - ASSIGNMENT_LHS, ERROR5 - VARIABLE_REFERENCE
                    ~
        src/test/pkg/java/MyClass.java:12: Error: VARIABLE_REFERENCE usage associated with @MyJavaAnnotation on VARIABLE [_AnnotationIssue]
            use(x); // ERROR6 - VARIABLE_REFERENCE
                ~
        src/test/pkg/MyClass.kt:9: Error: ASSIGNMENT_RHS usage associated with @MyKotlinAnnotation on VARIABLE [_AnnotationIssue]
            var x = 1 // ERROR1 - ASSIGNMENT_RHS
                    ~
        src/test/pkg/MyClass.kt:10: Error: VARIABLE_REFERENCE usage associated with @MyKotlinAnnotation on VARIABLE [_AnnotationIssue]
            x = 2 // ERROR2 - ASSIGNMENT_RHS, ERROR3 - VARIABLE_REFERENCE
            ~
        src/test/pkg/MyClass.kt:11: Error: ASSIGNMENT_LHS usage associated with @MyKotlinAnnotation on VARIABLE [_AnnotationIssue]
            val y = x // ERROR4 - ASSIGNMENT_LHS, ERROR5 - VARIABLE_REFERENCE
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/MyClass.kt:11: Error: VARIABLE_REFERENCE usage associated with @MyKotlinAnnotation on VARIABLE [_AnnotationIssue]
            val y = x // ERROR4 - ASSIGNMENT_LHS, ERROR5 - VARIABLE_REFERENCE
                    ~
        src/test/pkg/MyClass.kt:12: Error: VARIABLE_REFERENCE usage associated with @MyKotlinAnnotation on VARIABLE [_AnnotationIssue]
            use(x) // ERROR6 - VARIABLE_REFERENCE
                ~
        10 errors, 0 warnings
        """
      )
  }

  @Test
  fun testKotlinObjects() {
    // Regression test for b/282811891
    lint()
      .files(
        kotlin(
            """
                package test.obj
                import pkg.kotlin.MyKotlinAnnotation

                @MyKotlinAnnotation
                object Obj

                class C {
                  @MyKotlinAnnotation
                  companion object
                }
                """
          )
          .indented(),
        kotlin(
            """
                package test.usage
                import test.obj.Obj

                private fun use(o: Any) {}
                fun test() {
                    use(Obj)  // ERROR
                }
                """
          )
          .indented(),
        kotlinAnnotation,
      )
      .run()
      .expect(
        """
            src/test/usage/test.kt:6: Error: FIELD_REFERENCE usage associated with @MyKotlinAnnotation on CLASS [_AnnotationIssue]
                use(Obj)  // ERROR
                    ~~~
            1 errors, 0 warnings
            """
      )
  }

  @Test
  fun testObjectLiteral() {
    lint()
      .files(
        java(
            """
                package test.api;
                import pkg.java.MyJavaAnnotation;

                @MyJavaAnnotation
                public class Api {
                    public static class InnerApi {
                    }
                }
                """
          )
          .indented(),
        java(
            """
                package test.usage;
                import test.api.Api.InnerApi;
                public class JavaUsage {
                    public void test() {
                        new InnerApi(); // ERROR1
                        new InnerApi() { }; // ERROR2
                    }
                }
                """
          )
          .indented(),
        kotlin(
            """
                package test.usage
                import test.api.Api.InnerApi
                fun test() {
                    InnerApi() // ERROR3
                    object : InnerApi() { } // ERROR4
                }
                """
          )
          .indented(),
        javaAnnotation,
      )
      .run()
      .expect(
        """
            src/test/usage/JavaUsage.java:5: Error: METHOD_CALL usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    new InnerApi(); // ERROR1
                    ~~~~~~~~~~~~~~
            src/test/usage/JavaUsage.java:6: Error: METHOD_CALL usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                    new InnerApi() { }; // ERROR2
                    ~~~~~~~~~~~~~~~~~~
            src/test/usage/test.kt:4: Error: METHOD_CALL usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                InnerApi() // ERROR3
                ~~~~~~~~~~
            src/test/usage/test.kt:5: Error: METHOD_CALL usage associated with @MyJavaAnnotation on OUTER_CLASS [_AnnotationIssue]
                object : InnerApi() { } // ERROR4
                         ~~~~~~~~~~
            4 errors, 0 warnings
            """
      )
  }

  @Test
  fun testFieldPackageReference() {
    lint()
      .files(
        java(
            """
                package test.api;
                public class Api {
                    public Object field = null;
                }
                """
          )
          .indented(),
        bytecode(
          "libs/packageinfoclass.jar",
          java(
              "src/test/api/package-info.java",
              """
                    @MyJavaAnnotation
                    package test.api;
                    import pkg.java.MyJavaAnnotation;
                    """,
            )
            .indented(),
          0x1373820f,
          """
                test/api/package-info.class:
                H4sIAAAAAAAAADv1b9c+BgYGcwZOdgZ2dgYORgau4PzSouRUt8ycVEYGwYLE
                5OzE9FTdzLy0fL2sxLJERgbpoNK8kszcVM+8sszizKScVMe8vPySxJLM/Lxi
                oKxPQXa6Pkilvm+lF5BCyFozMoiWpBaX6CcWZOojG8zIIADWkJOYl67vn5SV
                mlwixsDAyMDEAAFMDMxgkoWBFUizAWXYGBgAA/vgtboAAAA=
                """,
        ),
        java(
            """
                package test.usage;
                import test.api.Api;
                public class Usage {
                    private void use(Object o) { }
                    public void test(Api api) {
                        use(api.field);
                    }
                }
                """
          )
          .indented(),
        javaAnnotation,
      )
      .allowDuplicates()
      .run()
      .expect(
        """
            src/test/usage/Usage.java:5: Error: CLASS_REFERENCE_AS_DECLARATION_TYPE usage associated with @MyJavaAnnotation on PACKAGE [_AnnotationIssue]
                public void test(Api api) {
                                 ~~~
            src/test/usage/Usage.java:6: Error: FIELD_REFERENCE usage associated with @MyJavaAnnotation on PACKAGE [_AnnotationIssue]
                    use(api.field);
                            ~~~~~
            libs/packageinfoclass.jar!/test/api/package-info.class: Error: Incident reported on package annotation [_AnnotationIssue]
            3 errors, 0 warnings
            """
      )
  }

  @Test
  fun testOverride() {
    lint()
      .files(
        java(
            """
                package test.api;
                import pkg.java.MyJavaAnnotation;
                import pkg.kotlin.MyKotlinAnnotation;

                public interface StableInterface {
                    @MyJavaAnnotation
                    @MyKotlinAnnotation
                    void experimentalMethod();
                }
                """
          )
          .indented(),
        java(
            """
                package test.api;
                class ConcreteStableInterface implements StableInterface {
                    @Override
                    public void experimentalMethod() {} // ERROR 1A and 1B
                }
                """
          )
          .indented(),
        kotlin(
            """
                package test.api
                class ConcreteStableInterface2 : StableInterface {
                    override fun experimentalMethod() {} // ERROR 2A and 2B
                }
                """
          )
          .indented(),
        kotlin(
            """
                package test.pkg
                import pkg.kotlin.MyKotlinAnnotation

                interface I {
                    fun m() {}
                }

                // Make sure outer annotations are inherited into the C override of m
                @MyKotlinAnnotation
                open class A {
                    open class B : I {
                        override fun m() {}
                    }
                }

                open class C : A.B() {
                    override fun m() {}
                }
                """
          )
          .indented(),
        javaAnnotation,
        kotlinAnnotation,
      )
      .run()
      .expect(
        """
            src/test/api/ConcreteStableInterface.java:4: Error: METHOD_OVERRIDE usage associated with @MyJavaAnnotation on METHOD [_AnnotationIssue]
                public void experimentalMethod() {} // ERROR 1A and 1B
                            ~~~~~~~~~~~~~~~~~~
            src/test/api/ConcreteStableInterface.java:4: Error: METHOD_OVERRIDE usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                public void experimentalMethod() {} // ERROR 1A and 1B
                            ~~~~~~~~~~~~~~~~~~
            src/test/api/ConcreteStableInterface2.kt:3: Error: METHOD_OVERRIDE usage associated with @MyJavaAnnotation on METHOD [_AnnotationIssue]
                override fun experimentalMethod() {} // ERROR 2A and 2B
                             ~~~~~~~~~~~~~~~~~~
            src/test/api/ConcreteStableInterface2.kt:3: Error: METHOD_OVERRIDE usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                override fun experimentalMethod() {} // ERROR 2A and 2B
                             ~~~~~~~~~~~~~~~~~~
            src/test/pkg/I.kt:16: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
            open class C : A.B() {
                           ~~~~~
            src/test/pkg/I.kt:17: Error: METHOD_OVERRIDE usage associated with @MyKotlinAnnotation on OUTER_CLASS [_AnnotationIssue]
                override fun m() {}
                             ~
            6 errors, 0 warnings
            """
      )
  }

  @Test
  fun test195014464() {
    @Suppress("MemberVisibilityCanBePrivate")
    lint()
      .files(
        kotlin(
            """
                package test.usage
                import pkg.kotlin.MyKotlinAnnotation

                class FooBar {
                    infix fun infixFun(@MyKotlinAnnotation foo: Int) {  }
                    operator fun plus(@MyKotlinAnnotation foo: Int) {  }
                    infix fun String.extensionInfixFun(@MyKotlinAnnotation foo: Int) {  }
                    infix fun @receiver:MyKotlinAnnotation String.extensionInfixFun2(foo: Int) {  }
                    operator fun plusAssign(@MyKotlinAnnotation foo: Int) {  }

                    fun testBinary() {
                        val bar = ""
                        this infixFun 0 // visit 0
                        this + 0 // visit 0
                        bar extensionInfixFun 0 // visit 0
                        bar extensionInfixFun2 0 // visit bar
                        this += 0 // visit 0
                    }
                }
                """
          )
          .indented(),
        javaAnnotation,
        kotlinAnnotation,
      )
      .run()
      .expect(
        """
            src/test/usage/FooBar.kt:13: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                    this infixFun 0 // visit 0
                                  ~
            src/test/usage/FooBar.kt:14: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                    this + 0 // visit 0
                           ~
            src/test/usage/FooBar.kt:15: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                    bar extensionInfixFun 0 // visit 0
                                          ~
            src/test/usage/FooBar.kt:16: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                    bar extensionInfixFun2 0 // visit bar
                    ~~~
            src/test/usage/FooBar.kt:17: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                    this += 0 // visit 0
                            ~
            5 errors, 0 warnings
            """
      )
  }

  @Test
  fun testBinaryOperators() {
    lint()
      .files(
        kotlin(
            """
                package test.pkg
                import pkg.kotlin.MyKotlinAnnotation

                class Resource {
                    operator fun contains(@MyKotlinAnnotation id: Int): Boolean = false
                    operator fun times(@MyKotlinAnnotation id: Int): Int = 0
                    operator fun rangeTo(@MyKotlinAnnotation id: Int): Int = 0
                }
                class Resource2

                operator fun Resource2.contains(@MyKotlinAnnotation id: Int): Boolean = false
                operator fun Resource2.rangeTo(@MyKotlinAnnotation id: Int): Int = 0
                operator fun @receiver:MyKotlinAnnotation Resource2.times(id: Int): Int = 0

                fun testBinary(resource: Resource, resource2: Resource2, color: Int) {
                    // Here we should only be visiting the "color" argument, except for in the
                    // last multiplication where we've annotated the receiver instead
                    println(color in resource) // visit color
                    println(color !in resource) // visit color
                    println(resource * color) // visit color
                    println(resource..color) // visit color

                    println(color in resource2) // visit color
                    println(resource2..color) // visit color
                    println(resource2 * color) // visit *resource*
                }
                """
          )
          .indented(),
        javaAnnotation,
        kotlinAnnotation,
      )
      .run()
      .expect(
        """
            src/test/pkg/Resource.kt:18: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                println(color in resource) // visit color
                        ~~~~~
            src/test/pkg/Resource.kt:19: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                println(color !in resource) // visit color
                        ~~~~~
            src/test/pkg/Resource.kt:20: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                println(resource * color) // visit color
                                   ~~~~~
            src/test/pkg/Resource.kt:21: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                println(resource..color) // visit color
                                  ~~~~~
            src/test/pkg/Resource.kt:23: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                println(color in resource2) // visit color
                        ~~~~~
            src/test/pkg/Resource.kt:24: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                println(resource2..color) // visit color
                                   ~~~~~
            src/test/pkg/Resource.kt:25: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                println(resource2 * color) // visit *resource*
                        ~~~~~~~~~
            7 errors, 0 warnings
            """
      )
  }

  @Test
  fun testArrayAccess() {
    lint()
      .files(
        kotlin(
            """
                package test.pkg
                import pkg.kotlin.MyKotlinAnnotation

                class Resource {
                    operator fun get(@MyKotlinAnnotation key: Int): String = ""
                    operator fun set(@MyKotlinAnnotation key: Int, value: String) {}
                }
                class Resource2 {
                    operator fun get(@MyKotlinAnnotation key: Int): String = ""
                    operator fun set(key: Int, @MyKotlinAnnotation value: String) {}
                }
                class Resource3
                operator fun Resource3.get(@MyKotlinAnnotation id: Int): String = ""
                operator fun Resource3.set(@MyKotlinAnnotation id: Int, value: String) {}
                class Resource4
                operator fun Resource4.get(id0: Int, @MyKotlinAnnotation id: Int): String = ""
                operator fun Resource4.set(id0: Int, @MyKotlinAnnotation id: Int, value: String) {}

                fun testArray(resource: Resource, resource2: Resource2, resource3: Resource3, resource4: Resource4) {
                    val x = resource[5] // visit 5
                    resource[5] = x // visit 5
                    val y = resource2[5] // visit 5
                    resource2[5] = y // visit y
                    val z = resource3[5] // visit 5
                    resource3[5] = z // visit 5
                    val w = resource4[0, 5] // visit 5
                    resource4[0, 5] = w // visit 5
                }
                """
          )
          .indented(),
        javaAnnotation,
        kotlinAnnotation,
      )
      .run()
      .expect(
        """
            src/test/pkg/Resource.kt:20: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                val x = resource[5] // visit 5
                                 ~
            src/test/pkg/Resource.kt:21: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                resource[5] = x // visit 5
                         ~
            src/test/pkg/Resource.kt:22: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                val y = resource2[5] // visit 5
                                  ~
            src/test/pkg/Resource.kt:23: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                resource2[5] = y // visit y
                               ~
            src/test/pkg/Resource.kt:24: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                val z = resource3[5] // visit 5
                                  ~
            src/test/pkg/Resource.kt:25: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                resource3[5] = z // visit 5
                          ~
            src/test/pkg/Resource.kt:26: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                val w = resource4[0, 5] // visit 5
                                     ~
            src/test/pkg/Resource.kt:27: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                resource4[0, 5] = w // visit 5
                             ~
            8 errors, 0 warnings
            """
      )
  }

  @Test
  fun testResolveExtensionArrayAccessFunction() {
    lint()
      .files(
        bytecode(
          "libs/library.jar",
          kotlin(
            """
                    package test.pkg1
                    import pkg.kotlin.MyKotlinAnnotation
                    class Resource
                    operator fun Resource.get(@MyKotlinAnnotation id: Int): String = ""
                    operator fun Resource.set(@MyKotlinAnnotation id: Int, value: String) {}
                """
          ),
          0x96bee228,
          """
                META-INF/main.kotlin_module:
                H4sIAAAAAAAAAGNgYGBmYGBgBGJWKM3AJcTFUZJaXKJXkJ0uxBYCZHmXcIlz
                ccLEDIW4glKL80uLklO9S5QYtBgA5F2hGUUAAAA=
                """,
          """
                test/pkg1/Resource.class:
                H4sIAAAAAAAAAGVRwU4CMRScdmHRFWVBVFDjWT24QLxpTNTEhGTVBA0XTgUa
                LCxdQwvxyLf4B55MPBji0Y8yvl31ZA+TNzOvr/PSz6+3dwDH2GUoWWls8Dga
                1IOWNPF00pM5MAZ/KGYiiIQeBLfdoezZHBwG91RpZc8YnP2Ddh5ZuB4yyDFk
                7IMyDOXw/7gThmI4im2kdHAtregLK0jj45lDIVgCWQY2IulJJaxGVb/OsLeY
                ex6vcI/7VC3mlcW8wWvsIvvx7HKfJ10NRhOw8vfU0chSlMu4LxkKodLyZjru
                ysm96EaklMK4J6K2mKiE/4reXXrzSiWk2ppqq8ayrYwi91zr2AqrYm1QB6dN
                k0NZk8UJt4gFKacVDl+x9EIFR4XQTcUMqoT5nwYsw0v97RQ3sZP+AcUnL9+B
                08RqE2uEKCTgN1FEqQNmsI4y+QaewYaB+w30FRU7wAEAAA==
                """,
          """
                test/pkg1/ResourceKt.class:
                H4sIAAAAAAAAAG1Sz08TQRT+Zpe22/JrqUUoKCBUoRXZQryhJobEuKGgAcMF
                L9N2Uqbd7prdaaM3Tv49ejMeDPHoH2V8s1uKhc7hve+9+eZ9897Mn78/fwF4
                jl2GghKRcj51WrvOiYiCXtgQhyoDxmC3eZ87Hvdbzrt6WzQoazKYLaEYylu1
                u+f23XLt5sypCqXf2mfYqAVhy2kLVQ+59COH+36guJIB4eNAHfc8j1irNarl
                dALlSd85+nIYg9dDKjHSL9SFjF5ZyDKsDIjtfteRvhKhzz3H9bVkJBtRBpMM
                840L0egMFN7zkHcFERk2t2q3O9u/e+/y2RSmMZPDFGYZYGGOIVvSNyjFEyiM
                GwCDIZsMzKU5RZpVGT+ncXIMqT73esJCcagU18iPG+pc7XpUQvEmV1xrd/sm
                PSvTJkW36GhgUP6z1KhKqEkv/vHqcjF3dZkz7JmcsWgk0EqcjpYWbDJGlVWM
                qrFnWcw2KZp4+/ursbRqpwinR3f20nZG8zVDa+wxTF73utOhDiYOgqZgmK1J
                Xxz3unURfuB1T+jWggb3zngodTxILp/0fCW7wvX7MpKUuvkFEcP6YPcs2Ru+
                6wipdLvEeFruNL7jG6lli6OF/yNiFwYmoJeBIlJIU1Sm6CXFBvlsJZ/7ATuf
                /xZTKmTToA9L1KeE7yck3EMhLpLFPOUYtge8DPlnOq9pjL4asJOEyR5IdAGL
                MGPRQ6qmn3M6Ed3OL5H9PiI8RVYLryVELA+EpwfCGhXxgE5o2UlzKJsIz5pD
                4cQbcGK7hSr5A8o+pNZWzmG6WHWxRhaPXKxjw0UJj8/BIjzB5jmsCKkIcxEK
                EeZjsBDb4j8xfuP2ggQAAA==
                """,
        ),
        kotlin(
            """
                package test.pkg
                import test.pkg1.Resource
                import test.pkg1.get
                import test.pkg1.set
                fun testArray(resource: Resource) {
                    val x = resource[5] // visit 5
                    resource[5] = x // visit 5
                }
                """
          )
          .indented(),
        kotlinAnnotation,
      )
      .run()
      .expect(
        """
            src/test/pkg/test.kt:6: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                val x = resource[5] // visit 5
                                 ~
            src/test/pkg/test.kt:7: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PARAMETER [_AnnotationIssue]
                resource[5] = x // visit 5
                         ~
            2 errors, 0 warnings
            """
      )
  }

  @Test
  fun testImplicitConstructor() {
    // Regression test for
    // 234779271: com.android.tools.lint.client.api.AnnotationHandler doesn't visit implicit
    // constructor delegations
    lint()
      .files(
        java(
            """
                package test.pkg;
                import pkg.kotlin.MyKotlinAnnotation;

                @SuppressWarnings({"InnerClassMayBeStatic", "unused"})
                public class Java {
                    class Parent {
                        @MyKotlinAnnotation
                        Parent() {
                        }

                        Parent(int i) {
                            this(); // (1) Invoked constructor is marked @MyKotlinAnnotation
                        }
                    }

                    class ChildDefaultConstructor extends Parent { // (2) Implicitly delegated constructor is marked @MyKotlinAnnotation
                    }

                    class ChildExplicitConstructor extends Parent {
                        ChildExplicitConstructor() { // (3) Implicitly invoked super constructor is marked @MyKotlinAnnotation, (4) Overrides annotated method
                        }

                        ChildExplicitConstructor(int a) {
                            super(); // (5) Invoked constructor is marked @MyKotlinAnnotation
                        }
                    }

                    class IndirectChildDefaultConstructor extends ChildDefaultConstructor { // (6) Implicitly invoked constructor is marked @MyKotlinAnnotation
                    }

                    class IndirectChildDefaultConstructor2 extends ChildDefaultConstructor {
                        IndirectChildDefaultConstructor2(int a) {
                            super(); // (7) Annotations on indirect implicit super constructor
                        }
                    }
                }
                """
          )
          .indented(),
        kotlin(
            """
                package test.pkg
                import pkg.kotlin.MyKotlinAnnotation

                class Kotlin {
                    internal open inner class Parent @MyKotlinAnnotation constructor() {
                        constructor(i: Int) : this()  { // (8) Invoked constructor is marked @MyKotlinAnnotation
                        }
                    }

                    internal open inner class ChildDefaultConstructor : Parent() { // (9), (10) override and call of annotated constructor
                    }

                    internal inner class ChildExplicitConstructor : Parent {
                        constructor() { // (11), (12) Extending annotated constructor, and implicitly invoking it
                        }

                        constructor(a: Int) : super() { // (13) Invoked constructor is marked @MyKotlinAnnotation
                        }
                    }

                    internal inner class IndirectChildDefaultConstructor : ChildDefaultConstructor() { // (14) Implicitly invoked constructor is marked @MyKotlinAnnotation
                    }

                    internal inner class IndirectChildDefaultConstructor2(a: Int) : ChildDefaultConstructor() // (15) Annotations on indirect implicit super constructor
                }
                """
          )
          .indented(),
        kotlinAnnotation,
      )
      // We don't get a METHOD_OVERRIDE on line 14 of Kotlin.kt here since we're
      // messing with the parameter signatures.
      .skipTestModes(TestMode.JVM_OVERLOADS)
      .run()
      .expect(
        """
            src/test/pkg/Java.java:12: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                        this(); // (1) Invoked constructor is marked @MyKotlinAnnotation
                        ~~~~~~
            src/test/pkg/Java.java:16: Error: IMPLICIT_CONSTRUCTOR usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                class ChildDefaultConstructor extends Parent { // (2) Implicitly delegated constructor is marked @MyKotlinAnnotation
                ^
            src/test/pkg/Java.java:20: Error: IMPLICIT_CONSTRUCTOR_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                    ChildExplicitConstructor() { // (3) Implicitly invoked super constructor is marked @MyKotlinAnnotation, (4) Overrides annotated method
                    ~~~~~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/Java.java:20: Error: METHOD_OVERRIDE usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                    ChildExplicitConstructor() { // (3) Implicitly invoked super constructor is marked @MyKotlinAnnotation, (4) Overrides annotated method
                    ~~~~~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/Java.java:24: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                        super(); // (5) Invoked constructor is marked @MyKotlinAnnotation
                        ~~~~~~~
            src/test/pkg/Java.java:28: Error: IMPLICIT_CONSTRUCTOR usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                class IndirectChildDefaultConstructor extends ChildDefaultConstructor { // (6) Implicitly invoked constructor is marked @MyKotlinAnnotation
                ^
            src/test/pkg/Java.java:33: Error: IMPLICIT_CONSTRUCTOR usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                        super(); // (7) Annotations on indirect implicit super constructor
                        ~~~~~~~
            src/test/pkg/Kotlin.kt:6: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                    constructor(i: Int) : this()  { // (8) Invoked constructor is marked @MyKotlinAnnotation
                                          ~~~~~~
            src/test/pkg/Kotlin.kt:10: Error: IMPLICIT_CONSTRUCTOR usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                internal open inner class ChildDefaultConstructor : Parent() { // (9), (10) override and call of annotated constructor
                ^
            src/test/pkg/Kotlin.kt:10: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                internal open inner class ChildDefaultConstructor : Parent() { // (9), (10) override and call of annotated constructor
                                                                    ~~~~~~~~
            src/test/pkg/Kotlin.kt:14: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                    constructor() { // (11), (12) Extending annotated constructor, and implicitly invoking it
                                 ^
            src/test/pkg/Kotlin.kt:14: Error: METHOD_OVERRIDE usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                    constructor() { // (11), (12) Extending annotated constructor, and implicitly invoking it
                    ~~~~~~~~~~~
            src/test/pkg/Kotlin.kt:17: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                    constructor(a: Int) : super() { // (13) Invoked constructor is marked @MyKotlinAnnotation
                                          ~~~~~~~
            src/test/pkg/Kotlin.kt:21: Error: IMPLICIT_CONSTRUCTOR usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                internal inner class IndirectChildDefaultConstructor : ChildDefaultConstructor() { // (14) Implicitly invoked constructor is marked @MyKotlinAnnotation
                ^
            src/test/pkg/Kotlin.kt:24: Error: IMPLICIT_CONSTRUCTOR usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
                internal inner class IndirectChildDefaultConstructor2(a: Int) : ChildDefaultConstructor() // (15) Annotations on indirect implicit super constructor
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            15 errors, 0 warnings
            """
      )
  }

  @Test
  fun testPropertyAnnotationWithoutUseSite() {
    // Regression test for 199932515
    lint()
      .files(
        kotlin(
            """
                @file:Suppress("_AnnotationIssue")
                package test.pkg
                import pkg.kotlin.ExperimentalKotlinAnnotation

                class AnnotatedKotlinMembers {
                  @ExperimentalKotlinAnnotation
                  var field: Int = -1

                  @set:ExperimentalKotlinAnnotation
                  var fieldWithSetMarker: Int = -1
                }
          """
          )
          .indented(),
        java(
            """
                package test.pkg;

                class Test {
                  void unsafePropertyUsage() {
                    new AnnotatedKotlinMembers().setField(-1);
                    int value = new AnnotatedKotlinMembers().getField();
                    new AnnotatedKotlinMembers().setFieldWithSetMarker(-1);
                    int value2 = new AnnotatedKotlinMembers().getFieldWithSetMarker();
                  }
                }
          """
          )
          .indented(),
        kotlinAnnotation,
        experimentalKotlinAnnotation,
      )
      .run()
      .expect(
        """
          src/test/pkg/Test.java:5: Error: METHOD_CALL_PARAMETER usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
              new AnnotatedKotlinMembers().setField(-1);
                                                    ~~
          src/test/pkg/Test.java:5: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
              new AnnotatedKotlinMembers().setField(-1);
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          src/test/pkg/Test.java:6: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on PROPERTY_DEFAULT [_AnnotationIssue]
              int value = new AnnotatedKotlinMembers().getField();
                          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          src/test/pkg/Test.java:7: Error: METHOD_CALL usage associated with @MyKotlinAnnotation on METHOD [_AnnotationIssue]
              new AnnotatedKotlinMembers().setFieldWithSetMarker(-1);
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          4 errors, 0 warnings
        """
      )
  }

  @Test
  fun testMetaAnnotationOnValueParameter() {
    // Test from b/313699428
    lint(includesDefinition = true)
      .files(
        java(
            """
            package test.pkg;
            import pkg.java.MyJavaAnnotation;

            @MyJavaAnnotation
            public @interface ExperimentalJavaAnnotation {}
          """
          )
          .indented(),
        java(
            """
            package test.pkg;

            @ExperimentalJavaAnnotation
            @Retention(RetentionPolicy.CLASS)
            public @interface AnnotatedJavaAnnotation {}
          """
          )
          .indented(),
        java(
            """
            import test.pkg.AnnotatedJavaAnnotation;
            import test.pkg.ExperimentalJavaAnnotation;

            class Test {
                void unsafeExperimentalAnnotationStep1(@ExperimentalJavaAnnotation int foo) {}
                void unsafeExperimentalAnnotationStep2(@AnnotatedJavaAnnotation int foo) {}
            }
          """
          )
          .indented(),
        javaAnnotation,
      )
      .run()
      .expect(
        """
          src/test/pkg/ExperimentalJavaAnnotation.java:4: Error: DEFINITION usage associated with @MyJavaAnnotation on SELF [_AnnotationIssue]
          @MyJavaAnnotation
          ~~~~~~~~~~~~~~~~~
          src/Test.java:5: Error: DEFINITION usage associated with @MyJavaAnnotation on PARAMETER [_AnnotationIssue]
              void unsafeExperimentalAnnotationStep1(@ExperimentalJavaAnnotation int foo) {}
                                                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          src/Test.java:6: Error: DEFINITION usage associated with @MyJavaAnnotation on PARAMETER [_AnnotationIssue]
              void unsafeExperimentalAnnotationStep2(@AnnotatedJavaAnnotation int foo) {}
                                                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          3 errors, 0 warnings
        """
      )
  }

  @Test
  fun testAnnotationOnConstructor() {
    // Test from b/340894674
    // and https://youtrack.jetbrains.com/issue/IDEA-353636
    lint()
      .files(
        java(
            """
            package test.pkg;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            import pkg.java.MyJavaAnnotation;

            @MyJavaAnnotation
            @Target({ElementType.TYPE, ElementType.CONSTRUCTOR})
            public @interface ExperimentalJavaAnnotationOnConstructor {}
          """
          )
          .indented(),
        java(
            """
            package test.pkg;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            import pkg.java.MyJavaAnnotation;

            @MyJavaAnnotation
            @Target(ElementType.METHOD)
            public @interface ExperimentalJavaAnnotationOnMethod {}
          """
          )
          .indented(),
        java(
            """
            package pkg1;
            import test.pkg.ExperimentalJavaAnnotationOnConstructor;
            import test.pkg.ExperimentalJavaAnnotationOnMethod;

            @ExperimentalJavaAnnotationOnConstructor
            public class JavaClass {
                @ExperimentalJavaAnnotationOnConstructor
                @ExperimentalJavaAnnotationOnMethod
                public JavaClass(String x) {}

                @ExperimentalJavaAnnotationOnConstructor
                public JavaClass(int x) {
                    this(String.valueOf(x));
                }

                @ExperimentalJavaAnnotationOnMethod
                public JavaClass(String x, String y) {
                    this(x + y);
                }

                @ExperimentalJavaAnnotationOnConstructor
                @ExperimentalJavaAnnotationOnMethod
                static void staticFunctionWithBoth() {}

                @ExperimentalJavaAnnotationOnConstructor
                static void staticFunctionWitConstructor() {}

                @ExperimentalJavaAnnotationOnMethod
                static void staticFunctionWitMethod() {}
            }
          """
          )
          .indented(),
        kotlin(
            """
            package pkg2
            import test.pkg.ExperimentalJavaAnnotationOnConstructor
            import test.pkg.ExperimentalJavaAnnotationOnMethod

            @ExperimentalJavaAnnotationOnConstructor
            class KotlinClass(val x: String) {
                @ExperimentalJavaAnnotationOnConstructor
                @ExperimentalJavaAnnotationOnMethod
                constructor(val x: Int) : this(x.toString())

                @ExperimentalJavaAnnotationOnConstructor
                constructor(val x: String, val y: String) : this(x + y)

                @ExperimentalJavaAnnotationOnMethod
                constructor(val x: String, val y: Int) : this(x + y.toString())

                @ExperimentalJavaAnnotationOnConstructor
                @ExperimentalJavaAnnotationOnMethod
                fun both() {}

                @ExperimentalJavaAnnotationOnConstructor
                fun onConstructor() {}

                @ExperimentalJavaAnnotationOnMethod
                fun onMethod() {}
            }
          """
          )
          .indented(),
        javaAnnotation,
      )
      .run()
      .expect(
        """
          src/pkg1/JavaClass.java:13: Error: METHOD_CALL usage associated with @MyJavaAnnotation on METHOD [_AnnotationIssue]
                  this(String.valueOf(x));
                  ~~~~~~~~~~~~~~~~~~~~~~~
          src/pkg1/JavaClass.java:18: Error: METHOD_CALL usage associated with @MyJavaAnnotation on METHOD [_AnnotationIssue]
                  this(x + y);
                  ~~~~~~~~~~~
          src/pkg2/KotlinClass.kt:9: Error: METHOD_CALL usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
              constructor(val x: Int) : this(x.toString())
                                        ~~~~~~~~~~~~~~~~~~
          src/pkg2/KotlinClass.kt:12: Error: METHOD_CALL usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
              constructor(val x: String, val y: String) : this(x + y)
                                                          ~~~~~~~~~~~
          src/pkg2/KotlinClass.kt:15: Error: METHOD_CALL usage associated with @MyJavaAnnotation on CLASS [_AnnotationIssue]
              constructor(val x: String, val y: Int) : this(x + y.toString())
                                                       ~~~~~~~~~~~~~~~~~~~~~~
          5 errors, 0 warnings
        """
      )
  }

  @Test
  fun testKtFileFromKlibMetadata() {
    // b/397414922: AnnotationHandler and DefaultJavaEvaluator attempt to
    // read annotations and package name on [KtFile] from klib metadata (.knm)
    // when a call is resolved to declarations in klib.
    // Such (text) read requires a decompilation, while no decompiler plugin
    // is registered in CLI, resulting in a runtime exception.

    // Klib resolution only works for K2 UAST.
    Assume.assumeTrue(useFirUast())

    val shared =
      project(
          klib(
            "libs/common.klib",
            "" +
              "H4sIAAAAAAAA/62YeTzU7dfHRxhbGOtYIlvZ94SSxljCkAnZ584yYxtmxJA1" +
              "ycTYIpEte2gs2d20kCX7UnYVCqGSpdDC1KN+r+e+Z57iqdfr950/vvN9zZz3" +
              "db7XOee6zvWBw6hpGAH/uYQB5Bc9gA2ARDk7+Hrg5M+aMwPoyH+E72rGTmbm" +
              "jfLB+no7oXz+BAAiA+AcvF1QuD8y5/uFuYcbxtf/nP/RI38CEt0T5IZx8vBF" +
              "opB/QhTek4hxwLn5of57PDQWt/PwJzw2Mt6OKRrpgHP4tT0ngJ6e/id78K/s" +
              "PbFIXw/UT5TZmQqD/oG+voHq6hkapXUlUx/v91RwGB09crEazrXzB67fDs4/" +
              "Q3k5OKEdXFDnnLCecq4oDw/snzgv93tEhXM/7nJojOdPcIGrJSwtEE6dj8sx" +
              "KJAHnJn9SQ6f6RXReiADENZpmt6Y6Mx8tfxlRsJIp5QY7NGckXNvM8vM01H4" +
              "BcuzsuZHoSll5dFAx2mNHJNG6fQDztuJg9qvMn0EqxqGMWzhtkC31EOu2jdO" +
              "yciI4BxQi6HFBNlH/DWGfZ5aMjORDZm3BtNVPCVjghZFZVC4i10LRqzIh6pK" +
              "OiG5QRWshc2KvFx0oG8/5rccxCqeu+Ns7X9jNhT3mA3ja02nWxVA7T51s2pj" +
              "N9KFcm+LmhUV47rxOtZQcRp2x7joEXPjd4kRufwc8Q/b8hU+skm198dm8jQU" +
              "CvA0bl10mT7Y1W4C0fTzWfcx42R5lSmZ0p+XPUqsmHiec7OhONUmifWsei3H" +
              "3ESbUFMtV0Tk+6lSaKgpn5nwYMWV6naLlG/+lxyXuKWT5l+pSN/g2Z8pbaQX" +
              "ZJecwf65FpHbXY26OJvFXNwtYKFr2VkTCQ3gg0s9ltl0X5gz2mqD9nwxcvVd" +
              "33xi4lTe2sQm1wh5UEJ/P5U549rjPCbOcDn+WhWWNxph68ExL+l9w736WJ8b" +
              "jV87flclIfodDF00jy9Cj0/XrjMwP0GcZHsdEb9P4MwzIH2/6yhwmVrW1B+N" +
              "p9Hq3nw8nKBRuLouLPFMi846+3pwopii6SWRiMYvj7xiplgj1pMefInxo19i" +
              "/R6x0VK5XF0qAKCOaq+IkS+bng4YN2eUD+7nmPRVMrYogACPSY26UNvoeqSX" +
              "l9d59jEvFRd1fTmVC5Kk4HsSy6yCpw9YFHfrBq+cvhNOPK/EzQyDBiWnk94F" +
              "N2nKrcoEvNTZWKAba8gN12cyMC/n5jB5qCIxcHlUKoT45asC5HArISL05WLg" +
              "uGQ55/63RoWalTWyHh1s6av3zoB65z5qgMN+vJTQW46BiB2Pyvcscyayl3Lz" +
              "/pNy5qS0xAV4oXx20vTnKXGyt7d3tLeHU824CjkDHf2EnKmf2JTu8/RgN7KR" +
              "2SfE3smWCCC+utQ120LPDI9mgq5BJCTpkwG5g2I8EPMoGg7A97eRE+Bm09qB" +
              "xezpkwClT27eOignDwfvnXUfi/nuHPIn51AJA2iOM6CO6cZbz8YM0mP5o3Jd" +
              "QzDaCUXD6yN4FFoXRiQSTrLM6AZKFLisyY+Pg25Hga8EdhXQi/xFktdgfBCw" +
              "ee5xOEF5qw4vxiDc69I4vdKrIYznhMbiOxEFoSb1rINYngNBFTnBvSONS6+1" +
              "k2CHACoQGS9Zr57SDjQcrm0Q7aAd2p8Pdrc6fCaxQ12PuU1BRVLc4XqtsRWK" +
              "GbQFuj7fOMSJMObr8S3X2VjhN8fampxeviFNp52t9wGDbYJNWtYIsPPcb7cw" +
              "pPErcJOfBDLpjrxijs+4m62vA84SHyy/Xjrj/tfqM8aRTP3U0/dbL+QOrb/m" +
              "YaTzxbGBeWmsiAEvGe1mpYsJvcHBbDc+Rz8N8LLUG1xqZ1V7XQUrEnuVeEMy" +
              "CnIp00auVLvTID2R8PnT6bu3V4PFo7sz+vzD89/QfJscFN8eB77BJwJzh0RN" +
              "LNce7B+yrM+6NnwM3DlXOFHD/9hxQ2rj61UrkqKZqnKLu+3hwWodzUwloo7m" +
              "xLNC1JgD875kHf8Cv82RkOy3jW0HlUpbaFUIXcqYW9cXOuqOD9Vlp71bLjVO" +
              "0w4CbA0bLxuPB2v02NkqywVF+WODLsNKF2nHxtPccUd0aDkmjh4JOO3ieJpr" +
              "WkJ14eWSn25g/CmnhVnJBQkfzUxfucQPBQHO2Q/lTnZN4T/ozJT33j0ZdODD" +
              "VDPnt/w8dt3Gqqnt0O+JJnC5w5l5HwDQQf0Hye/s5vEj+Z13S34voKOlnB5Q" +
              "WFpOlpT4fF+IjxOMQ5vajMmJyYeaQ1RZGwYjzW3bkWKHb94MDmEFEVMQIm2t" +
              "D2/fnks6lnlefZZbT/IwUC+B7ajdU3wcvb6sVP8AdVozk5IoVnDWrF0+Ns5I" +
              "SPtHmRxYkhxA7wybtGeZ8FB6j0Q5+roYYJyxv6yQ5AQtWCsEdAWsS/uEMVpQ" +
              "JTCajvZEYa4pOinHtewBQd/a15hb9Z5iGje0c2UNe8ygLhFfJ/Syp1djqtEj" +
              "AlSlQ19ZuW2P0BtNkEXK9wUfxledAIrrjinGD/XG8FB/gIW9eL/OICJ3wvxJ" +
              "U/wB4bedUko6Np2MVdZdpJ5TfjhPt/3I/Oa6TeMFxBx84cBcdd+hyNMkQ88T" +
              "Deyrfk/DEOkGfBXosYZ7Sl/CIxuVeTTj5e49sP341dm8u6G5+xxQTLjavbId" +
              "37Edx8QSxaUXDA3jlc+2M12zKZU8WdikfcJC36pWCZ3xbeK8RPua+VL3nOOd" +
              "NQRtbqJRS5p1Zn+PpBF3GaM5MT1uqqhy2uxg6YSlfAGH/0O+wfutS0FGuh+y" +
              "b3ZsE6Pf3dWSUqjFhqGdN+W/x2D+0jMl2Z2dZGXfXjHgooyBIxbp9iOFHH8K" +
              "QK05Qt9cizPLZ3Lktb1fZu/omb83lvnmTcpO3orS8bQQseImEa5cO5RGSHhy" +
              "UGty0uiI6KFAG1AVxGjx0Ln8yKloAl6kInJRJW8sp6h/ecpvYeaB5Jnupd6b" +
              "aZ/7P4b4zCu1H2lPjaxUyZTZ+PuqTdyIqmTIoVpFd/FSC1RtSawWS+3bWJTx" +
              "h7WKM2FvidcDUjnW3rtm2QfewGrEfo6ylwtcudRe7XuDfo3H/zPh1MBQsyrf" +
              "TL5wviCaeH3ESpAuPONKHidTJBN81ZbWW9a5QyjG5NPiqqtEgM7Hx/G0rQx6" +
              "H2RpPkMVGKpBRq00Sgpfj8RqKTA1z7FdzvU552iPf/wiRjHhkBUoMhiD4W5Y" +
              "JA4dU4UfsymKq+le6b/MRnu/q2ge8lcr03memXmL84ImPWbPPUCI8WjZKnXF" +
              "0g4lmUR+Dv1j89bE1qTATTYasVxTE4uCKs6hbdlHMbebPvIgv7TpC+gK9hdY" +
              "SBxfyxneJm1Hzn07Myvkh3T2Vh3tEO7tO3zmSkHrI6ukivKHhlNGXUO2DHLK" +
              "m8h2aejfyByS22LmUdc+3kD1fAJ//iteEQwvZ0u4Wg5oublfDeflk+LaOp86" +
              "2pmf3o+04s67oj5YXGg+s+XBOB6tR6LZzn0etc34iWFmla1+4jA31bkWZX8q" +
              "iURLhPNQWjRvXWrrSO09vuR99MfaoP05F8FwT15IQ5mexZ2UFb5Nl8IZZJDq" +
              "CVJl6zqbPh11umYfioYfKaAaT+ATL8u8gyo2DS7g+3pgVbK3lSMH5whc1ew0" +
              "UPqWykN3625y7fMl9pGmlRQJSf0kVUnV+11+b99ctajhuVASS5KLRcCHK7Ex" +
              "7owYy1vmjgYHousDIGpVvpUXpehczLUFAVMRh/QvFg8+6Y6pP3ihUw+zyfSK" +
              "12i9eD3ZxctiwAKDyVju69Wh7VZ73FF2SqxvSAtx1uApvt38akplKMOX1Vjo" +
              "qn/L5JiQelHKG/3ZeLHxYv0Dpu447FaWThzLooOu5FC+hKzIC0Odtf1NxLua" +
              "1I2iK9OXyvSkiXriVMYJqvXqEndeHNIQ36pXFwu8Nau86BsEvPrM4HLPJuTB" +
              "m+iQzRSWr8cbLoIb0qvtmmLs3jV1e8KH79xdDv3Rkqs/rag4ubOg2zPsVZK8" +
              "lCXp4+ayc2L09d6lrfE0nYTtlGV22oOgJruxt4iPU9et+8z2GzLMmJ0Y1v4G" +
              "CTWUP7EBuqvFJC1NEOF+o+1bduhRJAKPa46rkOlJUDCoZgW3FWzYfgF5DDek" +
              "TLx/2cE7v92f9mGrT4ueDQ9nzH3sImjMEt76qkIdh7t5QWc9NyPdAFSnqDh7" +
              "ig6qYCT2rHDUEPGZuRf+oMAJFgY9Pw0jdXNhFT2KD1t4lp5BjkaxUzfiyp17" +
              "5O8/59KTKMRH0Ei3TNfL9EFvWeOoTkFm4Y/uX5wiLfSfeCmD9hbrDESYE58l" +
              "59kdBHvdWtRX7vH5HNwQudaUzSiDfGZ5g647A/bcZDl9JXvllnBeF2numrlk" +
              "RdDJaxdHVdVUoxumG3sc9L/2zn3t1bS726YPG29jo1eRuovh3b6OKwEePhZh" +
              "FvV1ZGwsDRrl6k7ns7i6aGBLzD8+qXrZOhWsF8+Yb7Hf8Pjr4eW55a0Mo1cn" +
              "L8yIuN50t/UdFFk0XyOept2oQN+/7A7ks3MzK3ZNLYZWAytSB8QapwQuVsRZ" +
              "ty0cV2o6tjgIyEwLyzyP9UyokBi5vFW9euTkGCI1ZLsyeQR+Au7NYjHlEee3" +
              "ueHxvLk3D6jidjbhVoUqDs73WiplTKGk4BrEXLeFRVCd1WklorBdcymoT0av" +
              "xG04VgCWwOE7MwhQ+zS30Q++Pn2nsh6EVvi0trkJHZiCYXmVO4q6NzAvLgUz" +
              "NYPiFM4Fqmhub091nvUzDPblKMu9oXawm4kIlntaz9t4gTS1LHAza77H3AT9" +
              "CG0sq2AmVWP80QgzZV9E7Sdd46IKHhvb6nBWQNNHJGQf1/YM7cps+p64+1nX" +
              "+R129hE/mr0Sl/v/JC7O2w3j8uus9TU1xg5BQCpu7DaaLMkAhvNEtTAadq8X" +
              "STY2z67jrryrRxfl2VlYWD2Svjlpj8UHzqWsWh5/W2pbwl6VdSo9cyLgw1Rg" +
              "lup7jZWDEU082aJXQRyRHYQiwhjhNWDTI1atdgP9oEwH8ZZpK8b+tBnBmd9B" +
              "08/6oDKSC6zh1LPf6nyfAQ80S35C3tOd6D2kX5uWZlj3tZCt5ba3tGs6WIw5" +
              "TJk+7C8q0TfAUyECjzOOBZqdKijym1MQbLMk/E0QDz/rsRJliz1iFbC0un+t" +
              "c+P++Sfz8YiVto04xOQaJP2JfXtb5kZDCgF81XaAVJOiwGFccrY813zwk8XV" +
              "XNNXenCUhT8+vfdyQ2gRGtKCPYJYVb+ilicpKxH1mrUefLH/BsMAvSgD58Nj" +
              "MUvvvl1YfGFYrJijWydYtEWNlZ5Im/pcV/LRk7v8Pe3zaVxEzLsa9u7gvyDR" +
              "R7lBawROfKRil08WhB1wT/pL1eg9/nzWGMEafxferFC11uaFlZKqMijJ7Eje" +
              "5Fh7Z5nrS9PtqRRffldEjKnw2bA+vSPFpSjqlM38WHACEqY3rtIkEpsoBKye" +
              "DJlBqH8TNX40hkJXCDRGVr4N/ATE/ljOXFjBDM07HYbMjx6Vah8jYDeBkQFA" +
              "ef0rN9L+Jy/2MmenMFclMyeXHX8DBKIA+ZOB/pUffwPDR4H5+xcYchnyN4Ci" +
              "lPNDtReQTI78DbIwBdl0T/I/suQfc/P25P4jT/4Gl42C+56MSyZTknE4Af+u" +
              "ROR6IpiCo7TvF5z/lSv/OD6Zv6L9SpHcxU9yXU6Ogrzye2QKZXKXQcilJMpB" +
              "SNS/NYjibwxCLu1Q1lY5LeAXGtVvTDUTBQYPBFCqQrs4Qq7KcFIQGikJZOrQ" +
              "Lijyc7cAZfXQAf4/UWcXJvlpmNK9ewyAXc7vu6DID3U8FKgARsAeh+ldcOQN" +
              "KRdlmjABdjsX7sIi7xF4KVgJrIC9GtpdeOS7CzcFz4AdsGuf8Q+MFvj9zrXz" +
              "id/5do7z+9P/ANzW3e4EGwAA",
            0x4f666997,
            kotlin(
              "com/hello/Hello.kt",
              """
              package com.hello

              interface Hello {
                val hello : String
                fun greet(name : String) : String
              }
            """,
            ),
            kotlin(
              "com/hello/Person.kt",
              """
              package com.hello

              data class Person(val name : String, val age : Int) : Hello {
                override val hello get() = "hi"
                override fun greet(name : String) = hello + " " + name
             }
            """,
            ),
          )
        )
        .name("shared")

    val common =
      project(
          kotlin(
              "src/test/pkg/testCommon.kt",
              """
            package test.pkg
            import com.hello.Person

            fun test() {
              Person("Foo", 10).greet("Bar")
            }
          """,
            )
            .indented()
        )
        .name("commonMain")

    val root = temp.newFolder().canonicalFile.absoluteFile
    @Language("XML")
    val descriptor =
      """
        <project>
          <sdk dir='${getSdk()}'/>
          <root dir="$root" />

          <module name="shared" android="false" library="false" >
            <klib file="shared/libs/common.klib" kind="regular" />
          </module>

          <module name="commonMain" android="false" library="false" >
            <src file="commonMain/src/test/pkg/testCommon.kt" />
            <klib file="shared/libs/common.klib" kind="regular" />
          </module>
        </project>
      """
        .trimIndent()
    val task =
      lint().issues(MyAnnotationDetector.TEST_ISSUE).projects(shared, common).allowMissingSdk()
    val projects = task.createProjects(root)
    Files.asCharSink(File(root, "project.xml"), Charsets.UTF_8).write(descriptor)

    MainTest.checkDriver(
      "No issues found.",
      "",
      ERRNO_SUCCESS,
      arrayOf("--XuseK2Uast", "--project", File(root, "project.xml").path),
      { it.replace(root.canonicalPath, "ROOT").replace(root.path, "ROOT").dos2unix() },
      { _, _, _, _ -> },
    )
  }

  // Simple detector which just flags annotation references
  @SuppressWarnings("ALL")
  abstract class MyAnnotationDetectorBase : Detector(), Detector.UastScanner {
    override fun applicableAnnotations(): List<String> {
      return listOf("pkg.java.MyJavaAnnotation", "pkg.kotlin.MyKotlinAnnotation")
    }

    abstract val testIssue: Issue

    override fun visitAnnotationUsage(
      context: JavaContext,
      element: UElement,
      annotationInfo: AnnotationInfo,
      usageInfo: AnnotationUsageInfo,
    ) {
      if (annotationInfo.origin == AnnotationOrigin.PACKAGE) {
        val annotation = annotationInfo.annotation
        // Regression test for https://issuetracker.google.com/191286558: Make sure we can report
        // incidents on annotations from package info files without throwing an exception
        context.report(
          testIssue,
          context.getLocation(annotation),
          "Incident reported on package annotation",
        )
      }

      val name = annotationInfo.qualifiedName.substringAfterLast('.')
      val message =
        "`${usageInfo.type.name}` usage associated with `@$name` on ${annotationInfo.origin}"
      val locationType = if (element is UMethod) LocationType.NAME else LocationType.ALL
      val location = context.getLocation(element, locationType)
      context.report(testIssue, element, location, message)
    }
  }

  class MyAnnotationDetector : MyAnnotationDetectorBase() {
    override fun isApplicableAnnotationUsage(type: AnnotationUsageType): Boolean {
      return type != AnnotationUsageType.DEFINITION
    }

    override val testIssue: Issue = TEST_ISSUE

    companion object {
      @JvmField val TEST_CATEGORY = Category.create(Category.CORRECTNESS, "Test Category", 0)

      @Suppress("SpellCheckingInspection")
      @JvmField
      val TEST_ISSUE =
        Issue.create(
          id = "_AnnotationIssue",
          briefDescription = "Blahblah",
          explanation = "Blahdiblah",
          category = TEST_CATEGORY,
          priority = 10,
          severity = Severity.ERROR,
          implementation = Implementation(MyAnnotationDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
  }

  class MyAnnotationDetectorDefinitionToo : MyAnnotationDetectorBase() {
    override fun isApplicableAnnotationUsage(type: AnnotationUsageType): Boolean {
      return true
    }

    override val testIssue: Issue = TEST_ISSUE

    companion object {
      @JvmField val TEST_CATEGORY = Category.create(Category.CORRECTNESS, "Test Category", 0)

      @Suppress("SpellCheckingInspection")
      @JvmField
      val TEST_ISSUE =
        Issue.create(
          id = "_AnnotationIssue",
          briefDescription = "Blahblah",
          explanation = "Blahdiblah",
          category = TEST_CATEGORY,
          priority = 10,
          severity = Severity.ERROR,
          implementation =
            Implementation(MyAnnotationDetectorDefinitionToo::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
  }

  companion object {
    @ClassRule @JvmField var temp = TemporaryFolder()

    fun project(vararg files: TestFile): ProjectDescription = ProjectDescription(*files)
  }
}
