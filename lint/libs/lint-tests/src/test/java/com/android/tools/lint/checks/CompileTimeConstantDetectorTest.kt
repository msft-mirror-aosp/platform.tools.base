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

package com.android.tools.lint.checks

import com.android.tools.lint.detector.api.Detector

class CompileTimeConstantDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector = CompileTimeConstantDetector()

  private val ctcAnnotationStub =
    java(
        """
        package com.google.errorprone.annotations;
        import java.lang.annotation.ElementType;
        import java.lang.annotation.Target;

        @Target({ElementType.PARAMETER, ElementType.FIELD})
        public @interface CompileTimeConstant {}
        """
      )
      .indented()

  fun testDocumentationExample() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import com.google.errorprone.annotations.CompileTimeConstant;

            public class Test {
                public void check(@CompileTimeConstant String x) {}

                public void test() {
                    check("constant"); // OK
                    String nonConstant = "non-" + System.currentTimeMillis();
                    check(nonConstant); // ERROR
                }
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:11: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
                check(nonConstant); // ERROR
                      ~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testSimpleKotlin() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import com.google.errorprone.annotations.CompileTimeConstant

            fun check(@CompileTimeConstant x: String) {}

            fun test(nonConst: String) {
                check("constant") // OK
                check(nonConst) // ERROR
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:9: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
            check(nonConst) // ERROR
                  ~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testNonFinalField() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import com.google.errorprone.annotations.CompileTimeConstant;

            public class Test {
                @CompileTimeConstant private String shouldBeFinal = "shouldBeFinal";
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:6: Error: @CompileTimeConstant found on non-final field. Fields annotated with @CompileTimeConstant must be declared final. [CompileTimeConstant]
            @CompileTimeConstant private String shouldBeFinal = "shouldBeFinal";
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testInterfaceInheritanceConflict() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import com.google.errorprone.annotations.CompileTimeConstant;

            public class Test {
                interface Itf {
                    void foo(String x);
                }

                static class HelperBase {
                    public void foo(@CompileTimeConstant String x) {}
                }

                static class Wrong extends HelperBase implements Itf {}
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:14: Error: Inherited method foo with @CompileTimeConstant parameter can be called
        unsafely through implemented interface test.pkg.Test.Itf.
        Explicitly override the method or annotate the conflicting interface. [CompileTimeConstant]
            static class Wrong extends HelperBase implements Itf {}
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testGuavaImmutableList() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.CompileTimeConstant;

            public class Test {
                public void ctcList(@CompileTimeConstant ImmutableList<String> args) {}

                public void test(String s, @CompileTimeConstant String constStr) {
                    ctcList(ImmutableList.of("foo", "bar", constStr)); // OK
                    ctcList(ImmutableList.of("foo", s, "bar")); // ERROR
                }
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
        java(
            """
            package com.google.common.collect;
            public class ImmutableList<E> {
                public static <E> ImmutableList<E> of(E... elements) { return null; }
            }
            """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:11: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
                ctcList(ImmutableList.of("foo", s, "bar")); // ERROR
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testKotlinJvmField() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import com.google.errorprone.annotations.CompileTimeConstant

            class KtTest(@CompileTimeConstant @JvmField val jvmFieldProp: String = "default") {
                @CompileTimeConstant @JvmField val validVal = "constant"
                @field:CompileTimeConstant @JvmField var invalidVar = "mutable" // ERROR
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/KtTest.kt:7: Error: @CompileTimeConstant found on non-final field. Fields annotated with @CompileTimeConstant must be declared val. [CompileTimeConstant]
            @field:CompileTimeConstant @JvmField var invalidVar = "mutable" // ERROR
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testStringConcatenation() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import com.google.errorprone.annotations.CompileTimeConstant;

            public class Test {
                static final String JAVA_CONST = "Hello";

                public void check(@CompileTimeConstant String s) {}

                public void test(String x) {
                    check(JAVA_CONST + " World!"); // OK
                    check(1 + " World!"); // OK
                    check("World! " + 1); // OK
                    check('a' + "b"); // OK
                    check(x + " World!"); // ERROR
                    check("World! " + x); // ERROR
                }
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:15: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
                check(x + " World!"); // ERROR
                      ~~~~~~~~~~~~~
        src/test/pkg/Test.java:16: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
                check("World! " + x); // ERROR
                      ~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  fun testConstructorAndVarargs() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import com.google.errorprone.annotations.CompileTimeConstant;

            public class Test {
                public static void onlyCtc(@CompileTimeConstant String s) {}
                public static void ctcVarargs(String s, @CompileTimeConstant String... args) {}

                Test(String s, @CompileTimeConstant String p) {
                    onlyCtc(p); // OK
                }

                Test(String s) {
                    onlyCtc(s); // ERROR
                }

                public void testVarargs(String s) {
                    ctcVarargs(s, "foo", "bar"); // OK
                    ctcVarargs(s, "foo", s); // ERROR
                }
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:14: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
                onlyCtc(s); // ERROR
                        ~
        src/test/pkg/Test.java:19: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
                ctcVarargs(s, "foo", s); // ERROR
                                     ~
        2 errors, 0 warnings
        """
      )
  }

  fun testKotlinPropertySetterSyntax() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import com.google.errorprone.annotations.CompileTimeConstant;

            public class JavaClass {
                public String getCtc() { return null; }
                public void setCtc(@CompileTimeConstant String x) {}
            }
            """
          )
          .indented(),
        kotlin(
            """
            package test.pkg

            fun testPropertySyntax(javaObj: JavaClass, nonConst: String) {
                javaObj.ctc = "constant" // OK
                javaObj.ctc = nonConst // ERROR
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:5: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
            javaObj.ctc = nonConst // ERROR
                          ~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testVariableAssignmentAndIncDec() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import com.google.errorprone.annotations.CompileTimeConstant;

            public class Test {
                public void test(String s) {
                    @CompileTimeConstant String ctcVar = "constant"; // OK
                    ctcVar = s; // ERROR
                }
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:8: Error: Non-compile-time constant expression assigned to variable annotated with @CompileTimeConstant [CompileTimeConstant]
                ctcVar = s; // ERROR
                ~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testMethodOverride() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import com.google.errorprone.annotations.CompileTimeConstant;

            public class Test {
                static class Base {
                    public void foo(String s) {}
                }
                static class Sub extends Base {
                    @Override
                    public void foo(@CompileTimeConstant String s) {} // ERROR
                }
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:10: Error: Method with @CompileTimeConstant parameter cannot override method without it [CompileTimeConstant]
                @Override
                ^
        1 errors, 0 warnings
        """
      )
  }

  fun testKotlinCollections() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import com.google.errorprone.annotations.CompileTimeConstant

            fun ctcList(@CompileTimeConstant args: List<String>) {}
            fun ctcSet(@CompileTimeConstant args: Set<String>) {}

            fun test(s: String, @CompileTimeConstant constStr: String) {
                ctcList(listOf("foo", "bar", constStr)) // OK
                ctcList(listOf("foo", s, "bar")) // ERROR
                ctcSet(setOf("foo", "bar", constStr)) // OK
                ctcSet(setOf("foo", s, "bar")) // ERROR
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:10: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
            ctcList(listOf("foo", s, "bar")) // ERROR
                    ~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:12: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
            ctcSet(setOf("foo", s, "bar")) // ERROR
                   ~~~~~~~~~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  fun testJavaCollections() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import java.util.List;
            import java.util.Set;
            import com.google.errorprone.annotations.CompileTimeConstant;

            public class Test {
                public void ctcList(@CompileTimeConstant List<String> args) {}
                public void ctcSet(@CompileTimeConstant Set<String> args) {}

                public void test(String s, @CompileTimeConstant String constStr) {
                    ctcList(List.of("foo", "bar", constStr)); // OK
                    ctcList(List.of("foo", s, "bar")); // ERROR
                    ctcSet(Set.of("foo", "bar", constStr)); // OK
                    ctcSet(Set.of("foo", s, "bar")); // ERROR
                }
            }
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:13: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
                ctcList(List.of("foo", s, "bar")); // ERROR
                        ~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/Test.java:15: Error: Non-compile-time constant expression passed to parameter with @CompileTimeConstant annotation [CompileTimeConstant]
                ctcSet(Set.of("foo", s, "bar")); // ERROR
                       ~~~~~~~~~~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  fun testKotlinConstructorProperty() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import com.google.errorprone.annotations.CompileTimeConstant

            class KtTest(
                @CompileTimeConstant val validParamProp: String,
                @field:CompileTimeConstant private val fieldProp: String,
            )
            """
          )
          .indented(),
        ctcAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/KtTest.kt:7: Error: Annotating backing field of constructor property with @field:CompileTimeConstant
        is unsafe and unnecessary. Annotate as @CompileTimeConstant only. [CompileTimeConstant]
            @field:CompileTimeConstant private val fieldProp: String,
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }
}
