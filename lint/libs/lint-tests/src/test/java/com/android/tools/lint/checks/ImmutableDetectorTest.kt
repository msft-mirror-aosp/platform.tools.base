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

import com.android.tools.lint.checks.infrastructure.TestFiles.LibraryReferenceTestFile
import com.android.tools.lint.detector.api.Detector
import com.intellij.openapi.application.PathManager

class ImmutableDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector = ImmutableDetector()

  private val protoJar by lazy { PathManager.getJarForClass(com.google.protobuf.MessageLite::class.java)?.toFile() }

  private val immutableAnnotationStub =
    java(
        """
      package com.google.errorprone.annotations;
      import java.lang.annotation.ElementType;
      import java.lang.annotation.Inherited;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Target({ElementType.TYPE, ElementType.TYPE_USE})
      @Retention(RetentionPolicy.RUNTIME)
      @Inherited
      public @interface Immutable {
          String[] containerOf() default {};
      }
      """
      )
      .indented()

  private val immutableTypeParameterAnnotationStub =
    java(
        """
      package com.google.errorprone.annotations;
      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Target({ElementType.TYPE_PARAMETER})
      @Retention(RetentionPolicy.RUNTIME)
      public @interface ImmutableTypeParameter {}
      """
      )
      .indented()

  private val lazyInitAnnotationStub =
    java(
        """
      package com.google.errorprone.annotations.concurrent;
      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Target({ElementType.FIELD})
      @Retention(RetentionPolicy.RUNTIME)
      public @interface LazyInit {}
      """
      )
      .indented()

  private val immutableListStub =
    java(
        """
      package com.google.common.collect;
      import com.google.errorprone.annotations.Immutable;
      import java.util.Collection;
      import java.util.List;

      @Immutable(containerOf = {"E"})
      public abstract class ImmutableList<E> implements List<E> {
          public static <E> ImmutableList<E> of() { return null; }
          public static <E> ImmutableList<E> of(E e1) { return null; }
      }
      """
      )
      .indented()

  private val immutableSetStub =
    java(
        """
      package com.google.common.collect;
      import com.google.errorprone.annotations.Immutable;
      import java.util.Collection;
      import java.util.Set;

      @Immutable(containerOf = {"E"})
      public abstract class ImmutableSet<E> implements Set<E> {
          public static <E> ImmutableSet<E> of() { return null; }
          public static <E> ImmutableSet<E> of(E e1) { return null; }
      }
      """
      )
      .indented()

  private val immutableMapStub =
    java(
        """
      package com.google.common.collect;
      import com.google.errorprone.annotations.Immutable;
      import java.util.Map;

      @Immutable(containerOf = {"K", "V"})
      public abstract class ImmutableMap<K, V> implements Map<K, V> {
          public static <K, V> ImmutableMap<K, V> of() { return null; }
          public static <K, V> ImmutableMap<K, V> of(K k1, V v1) { return null; }
      }
      """
      )
      .indented()

  private val immutableCollectionStub =
    java(
        """
      package com.google.common.collect;
      import com.google.errorprone.annotations.Immutable;
      import java.util.Collection;

      @Immutable(containerOf = {"E"})
      public abstract class ImmutableCollection<E> implements Collection<E> {}
      """
      )
      .indented()

  private val canIgnoreReturnValueStub =
    java(
        """
      package com.google.errorprone.annotations;
      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Target({ElementType.METHOD, ElementType.TYPE})
      @Retention(RetentionPolicy.RUNTIME)
      public @interface CanIgnoreReturnValue {}
      """
      )
      .indented()

  private val genericWithImmutableParamStub =
    java(
        """
      package test.pkg;
      import com.google.errorprone.annotations.Immutable;
      import com.google.errorprone.annotations.ImmutableTypeParameter;

      @Immutable
      class GenericWithImmutableParam<@ImmutableTypeParameter T> {}
      """
      )
      .indented()

  fun testDocumentationExample() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              int a = 42;
              final int[] xs = new int[1];
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.a is not final [Immutable]
                      int a = 42;
                      ~~~~~~~~~~~
        src/test/pkg/Test.java:8: Error: Test is annotated as immutable, but field Test.xs is an array which is mutable [Immutable]
                      final int[] xs = new int[1];
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testBasicFields() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final int a = 42;
              final String b = null;
              final java.lang.String c = null;
              final com.google.common.collect.ImmutableList<String> d = null;
              final ImmutableList<Integer> e = null;
              final Deprecated dep = null;
              final Class<?> clazz = Class.class;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expectClean()
  }

  fun testInterfacesMutableByDefault() {
    lint()
      .files(
        java(
            """
package test.pkg;
interface I {}
          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              private final I i = new I() {};
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.i is of type I which is mutable [Immutable]
                      private final I i = new I() {};
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testAnnotationsAreImmutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            @interface Test {}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testCustomAnnotationsMightBeMutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            @interface Test {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.lang.annotation.Annotation;

            @Immutable
            final class MyTest implements Test {
              public Object[] xs = {};

              public Class<? extends Annotation> annotationType() {
                return null;
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/MyTest.java:8: Error: MyTest is annotated as immutable, but field MyTest.xs is an array which is mutable [Immutable]
                      public Object[] xs = {};
                      ~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/MyTest.java:8: Error: MyTest is annotated as immutable, but field MyTest.xs is not final [Immutable]
                      public Object[] xs = {};
                      ~~~~~~~~~~~~~~~~~~~~~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testCustomAnnotationsSubtype() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            @interface Test {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import java.lang.annotation.Annotation;

            final class MyTest implements Test {
              public Object[] xs = {};

              public Class<? extends Annotation> annotationType() {
                return null;
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/MyTest.java:5: Error: Class extends @Immutable type test.pkg.Test, but is not annotated as immutable [Immutable]
                    final class MyTest implements Test {
                    ^
        1 error
        """
          .trimIndent()
      )
  }

  fun testAnnotationsDefaultToImmutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              private final Override override = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testEnumsDefaultToImmutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            enum ElementKind { CONSTRUCTOR, METHOD }

            @Immutable
            class Test {
              private final ElementKind ek = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testEnumsMayBeImmutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            enum Kind {
              A,
              B,
              C;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              private final Kind k = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testMutableArray() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final int[] xs = {42};
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.xs is an array which is mutable [Immutable]
                      final int[] xs = {42};
                      ~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testAnnotatedImmutableInterfaces() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            interface Test {}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableInterfaceField() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            interface MyInterface {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final MyInterface i = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testDeeplyImmutableArguments() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final ImmutableList<ImmutableList<ImmutableList<String>>> l = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expectClean()
  }

  fun testMutableNonFinalField() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              int a = 42;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.a is not final [Immutable]
                      int a = 42;
                      ~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testIgnoreStaticFields() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              static int a = 42;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testMutableField() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.Map;

            @Immutable
            class Test {
              final Map<String, String> a = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:8: Error: Test is annotated as immutable, but field Test.a is of type Map which is mutable [Immutable]
                      final Map<String, String> a = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testDeeplyMutableTypeArguments() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;
            import java.util.Map;

            @Immutable
            class Test {
              final ImmutableList<ImmutableList<ImmutableList<Map<String, String>>>> l = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:9: Error: Test is annotated as immutable, but field Test.l is a container for the mutable type(s) Map<String, String> [Immutable]
                      final ImmutableList<ImmutableList<ImmutableList<Map<String, String>>>> l = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testExtendsImmutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            public class Super {
              public final int x = 42;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test extends Super {}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testExtendsMutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            public class Super {
              public int x = 42;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test extends Super {}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:6: Error: Test is annotated as immutable, but field Super.x is not final [Immutable]
                    class Test extends Super {}
                          ~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testExtendsImmutableAnnotated_substBounds() {
    lint()
      .files(
        java(
            """
package test.pkg;

            public class SuperMost<B> {
              public final B x = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = {"A"})
            public class Super<A, B> extends SuperMost<A> {
              public final int x = 42;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testExtendsImmutableAnnotated_mutableBounds() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = {"A"})
            public class SuperMost<A> {
              public final A x = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.List;

            @Immutable
            public class SubClass extends SuperMost<List<String>> {}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/SubClass.java:7: Error: Class is annotated as immutable, but the super type SuperMost<List<String>> is a container for the mutable type(s) List<String> [Immutable]
                    public class SubClass extends SuperMost<List<String>> {}
                                 ~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testTypeParameterWithImmutableBound() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            class Test<T extends ImmutableList<String>> {
              final T t = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeArgumentInstantiation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public class Holder<T> {
              public final T t = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final Holder<String> h = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testMutableTypeArgumentInstantiation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            public class Holder<T> {
              public final T t = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final Holder<Object> h = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.h is of type Holder which is mutable [Immutable]
                      final Holder<Object> h = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testInstantiationWithMutableType() {
    lint()
      .files(
        java(
            """
package test.pkg;

            public class Holder<T> {
              public final T t = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final Holder<Object> h = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.h is of type Holder which is mutable [Immutable]
                      final Holder<Object> h = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testTransitiveSuperSubstitutionImmutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "N")
            public class SuperMostType<N> {
              public final N f = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "M")
            public class MiddleClass<M> extends SuperMostType<M> {
              // Empty
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test extends MiddleClass<String> {
              final MiddleClass<String> f = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testContainerOf_mutableInstantiation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "V")
            class X<V> {
              private final V t = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test<T> {
              private final X<T> t = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.t is a container for the mutable type(s) T [Immutable]
                      private final X<T> t = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testMissingContainerOf() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test<T> {
              private final T t = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.t is of generic type T which is not guaranteed to be immutable [Immutable]
                      private final T t = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testTransitiveSuperSubstitutionMutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "N")
            public class SuperMostType<N> {
              public final N f = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "M")
            public class MiddleClass<M> extends SuperMostType<M> {
              // Empty
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.List;

            @Immutable
            class Test extends MiddleClass<List> {}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Class is annotated as immutable, but the super type MiddleClass<List> is a container for the mutable type(s) List [Immutable]
                    class Test extends MiddleClass<List> {}
                          ~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testImmutableInstantiation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public class X<T> {
              final ImmutableList<T> xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final X<String> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expectClean()
  }

  fun testMutableInstantiation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;

            public class X<T> {
              final ImmutableList<T> xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final X<Object> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.x is of type X which is mutable [Immutable]
                      final X<Object> x = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testImmutableInstantiation_superBound() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;

            public class X<T> {
              final ImmutableList<? super T> xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final X<String> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.x is of type X which is mutable [Immutable]
                      final X<String> x = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testMutableInstantiation_superBound() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;

            public class X<T> {
              final ImmutableList<? super T> xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final X<String> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.x is of type X which is mutable [Immutable]
                      final X<String> x = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testImmutableInstantiation_extendsBound() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public class X<T> {
              final ImmutableList<? extends T> xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final X<String> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expectClean()
  }

  fun testMutableInstantiation_wildcard() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public class X<T> {
              final ImmutableList<?> xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final X<String> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/X.java:8: Error: X is annotated as immutable, but field X.xs is a container for the mutable type(s) ? [Immutable]
                      final ImmutableList<?> xs = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testMutableInstantiation_extendsBound() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public class X<T> {
              final ImmutableList<? extends T> xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final X<Object> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.x is a container for the mutable type(s) Object [Immutable]
                      final X<Object> x = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testImmutableInstantiation_inferredImmutableType() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public class X<T> {
              final T xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public class Y<T> {
              final X<? extends T> xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final Y<String> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testMutableInstantiation_inferredImmutableType() {
    lint()
      .files(
        java(
            """
package test.pkg;

            public class X<T> {
              final T xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            public class Y<T> {
              final X<? extends T> xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final Y<Object> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.x is of type Y which is mutable [Immutable]
                      final Y<Object> x = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testMutableWildInstantiation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public class X<T> {
              final ImmutableList<T> xs = null;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final X<?> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.x is a container for the mutable type(s) ? [Immutable]
                      final X<?> x = null;
                      ~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testMutableWildcardInstantiation_immutableTypeParameter() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import com.google.errorprone.annotations.ImmutableTypeParameter;

            @Immutable
            class A<@ImmutableTypeParameter T> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class B {
              private final A<?> a;

              public B(A<?> a) {
                this.a = a;
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testPositiveAnonymous() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Super {}

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            class Test {
              {
                new Super() {
                  int x = 0;

                  {
                    x++;
                  }
                };
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:7: Error: The anonymous class's supertype is annotated as immutable, but field x is not final [Immutable]
              int x = 0;
              ~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testPositiveAnonymousInterface() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            interface Super {}

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            class Test {
              {
                new Super() {
                  int x = 0;

                  {
                    x++;
                  }
                };
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:7: Error: The anonymous class's supertype is annotated as immutable, but field x is not final [Immutable]
              int x = 0;
              ~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testNegativeParametricAnonymous() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            class Super<T> {
              private final T t = null;
            }

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            class Test {
              static <T> Super<T> get() {
                return new Super<T>() {};
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInterface_containerOf_immutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public interface MyList<T> {
              T get(int i);
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            public class Test {
              private final MyList<Integer> l = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInterface_containerOf_mutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public interface MyList<T> {
              T get(int i);
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            public class Test<X> {
              private final MyList<X> l = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.l is a container for the mutable type(s) X [Immutable]
                      private final MyList<X> l = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testImplementsInterface_containerOf() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            public interface MyList<T> {
              T get(int i);
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            public class Test<X> implements MyList<X> {
              public X get(int i) {
                return null;
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:6: Error: Class is annotated as immutable, but the super type MyList<X> is a container for the mutable type(s) X [Immutable]
                    public class Test<X> implements MyList<X> {
                                 ~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testPositive() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Super {}

          """
          )
          .indented(),
        java(
            """

package threadsafety;

class Test extends Super {
  public int x = 0;
}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:5: Error: Test is annotated as immutable, but field Test.x is not final [Immutable]
          public int x = 0;
          ~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testPositiveContainerOf() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = {"T"})
            class Super<T> {}

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            class Test extends Super<Integer> {
              public int x = 0;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:5: Error: Test is annotated as immutable, but field Test.x is not final [Immutable]
          public int x = 0;
          ~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testPositiveImplicitContainerOf() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = {"T"})
            class Super<T> {}

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            class Test<U> extends Super<U> {
              public final Test<Object> x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:4: Error: Class is annotated as immutable, but the super type Super<U> is a container for the mutable type(s) U [Immutable]
        class Test<U> extends Super<U> {
              ~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testNegative() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Super {}

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test extends Super {}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testTransitive() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            interface I {}

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            class Test implements J {
              public int x = 0;
            }

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            interface J extends I {}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/J.java:4: Error: Class extends @Immutable type threadsafety.I, but is not annotated as immutable [Immutable]
        interface J extends I {}
        ~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testNegativeAnonymousMutableBound() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            class Super<T> {
              private final T t = null;
            }

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            class Test {
              {
                new Super<Object>() {};
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableAnonymousTypeScope() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "X")
            class Super<X> {
              private final X t = null;
            }

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            class Test<T> {
              {
                new Super<T>() {};
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableClassSuperTypeScope() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "Y")
            class Super<Y> {
              @Immutable(containerOf = "X")
              class Inner1<X> {
                private final X x = null;
                private final Y y = null;
              }
            }

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "U")
            class Test<U> extends Super<U> {
              @Immutable
              class Inner2 extends Inner1<U> {}
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableClassTypeScope() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "X")
            class Super<X> {
              private final X t = null;
            }

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            class Test<T> {
              @Immutable
              class Inner extends Super<T> {}
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testNegativeAnonymousBound() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            class Super<T> {
              private final T t = null;
            }

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            class Test {
              {
                new Super<String>() {};
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testNegativeAnonymous() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Super {}

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            class Test {
              {
                new Super() {};
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testPositiveEnumConstant() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            interface Super {
              int f();
            }

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            enum Test implements Super {
              INSTANCE {
                public int x = 0;

                public int f() {
                  return x++;
                }
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:9: Error: The anonymous class's supertype is annotated as immutable, but field x is not final [Immutable]
            public int x = 0;
            ~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testNegativeEnumConstant() {
    lint()
      .files(
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            interface Super {
              void f();
            }

          """
          )
          .indented(),
        java(
            """

            package threadsafety;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            enum Test implements Super {
              INSTANCE {
                public void f() {}
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableProto() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final com.google.errorprone.testdata.proto.Test.User x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testSuppressOnField() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              @SuppressWarnings("Immutable")
              final int[] xs = {1};
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testSuppressOnOneField() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              @SuppressWarnings("Immutable")
              final int[] xs = {1};

              final int[] ys = {1};
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:10: Error: Test is annotated as immutable, but field Test.ys is an array which is mutable [Immutable]
                      final int[] ys = {1};
                      ~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testTwoFieldsInSource() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final int[] xs = {1};
              final int[] ys = {1};
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:7: Error: Test is annotated as immutable, but field Test.xs is an array which is mutable [Immutable]
                      final int[] xs = {1};
                      ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/Test.java:8: Error: Test is annotated as immutable, but field Test.ys is an array which is mutable [Immutable]
                      final int[] ys = {1};
                      ~~~~~~~~~~~~~~~~~~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testMutableEnclosing() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            public class Test {
              int x = 0;

              @Immutable
              public class Inner {
                public int count() {
                  return x++;
                }
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:11: Error: The wider-scope variable 'x' is captured by a class definition with immutable type(s) Inner and is not final [Immutable]
                          return x++;
                                 ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testRawClass() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test {
              final Class clazz = Test.class;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testLazyInit() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import com.google.errorprone.annotations.concurrent.LazyInit;

            @Immutable
            class Test {
              @LazyInit int a = 42;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        lazyInitAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testLazyInitMutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import com.google.errorprone.annotations.concurrent.LazyInit;
            import java.util.List;

            @Immutable
            class Test {
              @LazyInit List<Integer> a = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        lazyInitAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:9: Error: Test is annotated as immutable, but field Test.a is of type List which is mutable [Immutable]
                      @LazyInit List<Integer> a = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testImmutableTypeParameterInstantiation_immutableGenericFromContext_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import com.google.errorprone.annotations.ImmutableTypeParameter;

            @Immutable
            class A<@ImmutableTypeParameter T> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.ImmutableTypeParameter;

            class Test<@ImmutableTypeParameter T> {
              A<T> n() {
                return new A<>();
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeParameterInstantiation_staticMethod_genericParamAnnotated_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import com.google.errorprone.annotations.ImmutableTypeParameter;

            @Immutable
            class A<@ImmutableTypeParameter T> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.ImmutableTypeParameter;

            class Test {
              static <@ImmutableTypeParameter T> A<T> l() {
                return new A<>();
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeParameterInstantiation_genericParamAnnotated_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import com.google.errorprone.annotations.ImmutableTypeParameter;

            @Immutable
            class A<@ImmutableTypeParameter T> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.ImmutableTypeParameter;

            class Test {
              <@ImmutableTypeParameter T> A<T> k() {
                return new A<>();
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeParameterInstantiation_genericParamExtendsImmutable_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class MyImmutableType {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import com.google.errorprone.annotations.ImmutableTypeParameter;

            @Immutable
            class A<@ImmutableTypeParameter T> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              <T extends MyImmutableType> A<T> h() {
                return new A<>();
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeParameterInstantiation_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import com.google.errorprone.annotations.ImmutableTypeParameter;

            @Immutable
            class A<@ImmutableTypeParameter T> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              A<String> f() {
                return new A<>();
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeParameterUsage() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.ImmutableTypeParameter;

            class T {
              static <@ImmutableTypeParameter T> void f() {}
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeParameterUsage_interface() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import com.google.errorprone.annotations.ImmutableTypeParameter;

            @Immutable
            interface T<@ImmutableTypeParameter T> {}

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeParameterMutableClass() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.ImmutableTypeParameter;

            class A<@ImmutableTypeParameter T> {}

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testContainerOf_extendsThreadSafeContainerOf() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = {"V"})
            class X<V> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = {"Y"})
            class Test<Y> extends X<Y> {
              private final Y t = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testContainerOf_extendsThreadSafe_nonContainer() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = {"V"})
            class X<U, V> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = {"Y"})
            class Test<Y> extends X<Object, Y> {
              private final Y t = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testContainerOf_field() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            interface X<Y> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "V")
            class Test<V> {
              private final X<V> t = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testAnnotatedClassType() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import static java.lang.annotation.ElementType.TYPE_USE;

            import java.lang.annotation.Target;

            @Target(TYPE_USE)
            @interface A {}

            class Test {
              Object o = new @A Object();
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableUpperBound() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class MyImmutableType {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class Test<T extends MyImmutableType, U extends T> {
              final T t = null;
              final U u = null;
              final ImmutableList<? extends U> v = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableRecursiveUpperBound() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            abstract class Recursive<T extends Recursive<T>> {
              final T x = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableRecursiveUpperBound_notImmutable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.List;

            @Immutable
            abstract class Recursive<T extends Recursive<T>> {
              final T x = null;
              final List<T> y = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Recursive.java:9: Error: Recursive is annotated as immutable, but field Recursive.y is of type List which is mutable [Immutable]
                      final List<T> y = null;
                      ~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testImmutableTypeParameter_twoInstantiations() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import com.google.errorprone.annotations.ImmutableTypeParameter;

            @Immutable
            class Test<@ImmutableTypeParameter T> {
              <@ImmutableTypeParameter T> T f(T t) {
                return t;
              }

              <@ImmutableTypeParameter T> void g(T a, T b) {}

              @Immutable
              interface I {}

              void test(I i) {
                g(f(i), f(i));
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testMutable_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class MutableClass {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              private GenericWithImmutableParam<MutableClass> field;
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testContainerOf_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = "T")
            class ImmutableContainer<T> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              private GenericWithImmutableParam<ImmutableContainer<Object>> field;
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testNestedImmutableTypeParameter_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class Test<T> {
              private GenericWithImmutableParam<T> field;
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testLocalVariable_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class MutableClass {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              public void method() {
                GenericWithImmutableParam<MutableClass> value = null;
              }
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testParameter_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class MutableClass {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              public void method(GenericWithImmutableParam<MutableClass> value) {}
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testReturnValue_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class MutableClass {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              public GenericWithImmutableParam<MutableClass> method() {
                return null;
              }
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testGenericStaticMethodParam_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class Test {
              public static <T> void method(GenericWithImmutableParam<T> value) {}
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testGenericStaticMethodReturnValue_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class Test {
              public static <T> GenericWithImmutableParam<T> method() {
                return null;
              }
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testMethodParameter_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class Test<T> {
              public void method(GenericWithImmutableParam<T> value) {}
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testMethodReturnValue_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class Test<T> {
              public GenericWithImmutableParam<T> method() {
                return null;
              }
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testConstructorParam_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class Test<T> {
              public Test(GenericWithImmutableParam<T> param) {}
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testTypecast_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class MutableClass {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              public void method() {
                Object obj = (GenericWithImmutableParam<MutableClass>) null;
              }
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testTypeParameterExtendsMutable_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class MutableClass {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              public void method() {
                GenericWithImmutableParam<? extends MutableClass> value = null;
              }
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testTypeParameterExtendsImmutable_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class ImmutableClass {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              public void method() {
                GenericWithImmutableParam<? extends ImmutableClass> value = null;
              }
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testTypeParameterSuper_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class ImmutableClass {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Test {
              public void method() {
                GenericWithImmutableParam<? super ImmutableClass> value = null;
              }
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testExtendsImmutable_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class ImmutableClass {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class ChildGenericWithImmutableParam<T extends ImmutableClass>
                extends GenericWithImmutableParam<T> {}

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testContainerOfAsImmutableTypeParameter_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = {"T"})
            class Container<T> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            class Clazz<T> {
              private GenericWithImmutableParam<Container<T>> container;
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testContainerOfAsImmutableTypeParameterInSameClass_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable(containerOf = {"T"})
            class Container<T> {
              GenericWithImmutableParam<T> method() {
                return null;
              }
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeParameter_recursiveUpperBound() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            abstract class B<T extends B<T>> {}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeParameter_recursiveUpperBoundUsage() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            interface B<T extends B<T>> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            class A implements B<A> {
              final B<A> value = null;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testWildcard_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class Test {
              private final GenericWithImmutableParam<?> value = null;
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableTypeParameter_anonymousInstantiation_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class Clazz {
              private static final GenericWithImmutableParam<String> value =
                  new GenericWithImmutableParam<String>() {};
            }

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testNonGeneric_inheritanceClass_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            class ChildGenericWithImmutableParam extends GenericWithImmutableParam<String> {}

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testNonGeneric_inheritanceInterface_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.ImmutableTypeParameter;

            interface GenericWithImmutableParamIface<@ImmutableTypeParameter T> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

class ChildGenericWithImmutableParam implements GenericWithImmutableParamIface<String> {}

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInheritanceClass_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.ImmutableTypeParameter;

            class ChildGenericWithImmutableParam<@ImmutableTypeParameter T>
                extends GenericWithImmutableParam<T> {}

          """
          )
          .indented(),
        genericWithImmutableParamStub,
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInheritanceInterface_noViolation() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.ImmutableTypeParameter;

            interface GenericWithImmutableParamIface<@ImmutableTypeParameter T> {}

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.ImmutableTypeParameter;

            class ChildGenericWithImmutableParam<@ImmutableTypeParameter T>
                implements GenericWithImmutableParamIface<T> {}

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testLambda_cannotCloseAroundMutableField() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            class Test {
              @Immutable
              interface ImmutableFunction<A, B> {
                A apply(B b);
              }

              private int a = 0;

              void test(ImmutableFunction<Integer, Integer> f) {
                test(x -> ++a);
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:14: Error: 'a' is captured by a lambda expression that is passed as immutable type(s) ImmutableFunction but is not final [Immutable]
                        test(x -> ++a);
                                    ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testLambda_cannotCloseAroundMutableFieldQualifiedWithThis() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            class Test {
              @Immutable
              interface ImmutableFunction<A, B> {
                A apply(B b);
              }

              private int b = 1;

              void test(ImmutableFunction<Integer, Integer> f) {
                test(x -> this.b);
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:14: Error: 'this' is captured by a lambda expression that is passed as immutable type(s) ImmutableFunction but is of type Test which is mutable [Immutable]
                        test(x -> this.b);
                                  ~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testNotImmutableAnnotatedLambda_noFinding() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Function;

            class Test {
              void test(Function<Integer, Integer> f) {
                List<Integer> xs = new ArrayList<>();
                test(x -> xs.get(x));
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testLambda_canHaveMutableVariablesWithin() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.ArrayList;
            import java.util.List;

            class Test {
              @Immutable
              interface ImmutableFunction<A, B> {
                A apply(B b);
              }

              void test(ImmutableFunction<Integer, Integer> f) {
                test(
                    x -> {
                      List<Integer> xs = new ArrayList<>();
                      return xs.get(x);
                    });
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testLambda_canAccessStaticField() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            class Test {
              @Immutable
              interface ImmutableFunction<A, B> {
                A apply(B b);
              }

              static class A {
                public static int FOO = 1;
              }

              void test(ImmutableFunction<Integer, Integer> f) {
                test(x -> A.FOO);
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testLambda_cannotCallMethodOnMutableClass() {
    lint()
      .files(
        java(
            """
package test.pkg;

import com.google.errorprone.annotations.Immutable;

abstract class Test {
  @Immutable
  interface ImmutableFunction<A, B> {
    A apply(B b);
  }

  abstract int mutable(int a);

  void test(ImmutableFunction<Integer, Integer> f) {
    test(x -> mutable(x));
    test(x -> this.mutable(x));
  }
}

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:14: Error: Implicit 'this' is captured by a lambda expression that is passed as immutable type(s) ImmutableFunction but is of type Test which is mutable [Immutable]
            test(x -> mutable(x));
                      ~~~~~~~~~~
        src/test/pkg/Test.java:15: Error: 'this' is captured by a lambda expression that is passed as immutable type(s) ImmutableFunction but is of type Test which is mutable [Immutable]
            test(x -> this.mutable(x));
                      ~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testLambda_canCallMethodOnImmutableClass() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            abstract class Test {
              @Immutable
              interface ImmutableFunction<A, B> {
                A apply(B b);
              }

              abstract int mutable(int a);

              void test(ImmutableFunction<Integer, Integer> f) {
                test(x -> mutable(x));
                test(x -> this.mutable(x));
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testChecksEffectiveTypeOfReceiver() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.function.Function;

            @Immutable
            abstract class Test {
              @Immutable
              interface ImmutableFunction<A, B> extends Function<A, B> {
                default <C> ImmutableFunction<A, C> andThen(ImmutableFunction<B, C> fn) {
                  return x -> fn.apply(apply(x));
                }
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testChecksEffectiveTypeOfReceiver_whenNotDirectOuterClass() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.function.Function;

            @Immutable
            abstract class Test implements Function<String, String> {
              @Immutable
              interface ImmutableFunction {
                String apply(String a);
              }

              class A {
                ImmutableFunction asImmutable() {
                  return x -> apply(x);
                }
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testMethodReference_onImmutableType() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableMap;
            import com.google.errorprone.annotations.Immutable;

            abstract class Test {
              @Immutable
              interface ImmutableFunction {
                String apply(String b);
              }

              void test(ImmutableFunction f) {
                ImmutableMap<String, String> map = ImmutableMap.of();
                test(map::get);
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableMapStub,
      )
      .run()
      .expectClean()
  }

  fun testMethodReference_toUnboundMethodReference() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.Set;

            abstract class Test {
              @Immutable
              interface ImmutableBiConsumer {
                void accept(Set<String> xs, String x);
              }

              void test(ImmutableBiConsumer c) {
                test(Set::add);
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testMethodReference_toConstructor() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.ArrayList;

            abstract class Test {
              @Immutable
              interface ImmutableProvider {
                Object get();
              }

              void test(ImmutableProvider f) {
                test(ArrayList::new);
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testChainedGettersAreAcceptable() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.ArrayList;
            import java.util.List;

            class Test {
              final Test t = null;
              final List<String> xs = new ArrayList<>();

              final List<String> getXs() {
                return xs;
              }

              @Immutable
              interface ImmutableFunction {
                String apply(String b);
              }

              void test(ImmutableFunction f) {
                test(
                    x -> {
                      Test t = new Test();
                      return t.xs.get(0) + t.getXs().get(0) + t.t.t.xs.get(0);
                    });
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testAnonymousClass_hasMutableFieldSuppressed_noWarningAtUsageSite() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;
            import java.util.ArrayList;
            import java.util.List;

            class Test {
              @Immutable
              interface ImmutableFunction<A, B> {
                A apply(B b);
              }

              void test(ImmutableFunction<Integer, Integer> f) {
                test(
                    new ImmutableFunction<>() {
                      @Override
                      public Integer apply(Integer x) {
                        return xs.get(x);
                      }

                      @SuppressWarnings("Immutable")
                      List<Integer> xs = new ArrayList<>();
                    });
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testAnonymousClass_canCallSuperMethodOnNonImmutableSuperClass() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            class Test {
              interface Function<A, B> {
                default void foo() {}
              }

              @Immutable
              interface ImmutableFunction<A, B> extends Function<A, B> {
                A apply(B b);
              }

              void test(ImmutableFunction<Integer, Integer> f) {
                test(
                    new ImmutableFunction<>() {
                      @Override
                      public Integer apply(Integer x) {
                        foo();
                        return 0;
                      }
                    });
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testSwitchExpressionsResultingInGenericTypes_doesNotThrow() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            enum Kind {
              A,
              B;
            }

          """
          )
          .indented(),
        java(
            """
package test.pkg;

            import java.util.Optional;
            import java.util.function.Supplier;

            class Test {
              Supplier<Optional<String>> test(Kind kind) {
                return switch (kind) {
                  case A -> Optional::empty;
                  case B -> () -> Optional.of("");
                };
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testSwitchExpressionsMethodReference_doesNotThrow() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import java.util.function.Supplier;

            class Test {
              Supplier<Double> test(String mode) {
                return switch (mode) {
                  case "random" -> Math::random;
                  default -> throw new IllegalArgumentException();
                };
              }
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testImmutableRecord() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.common.collect.ImmutableList;
            import com.google.errorprone.annotations.Immutable;

            @Immutable
            record R(ImmutableList<String> xs) {}

          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableListStub,
      )
      .run()
      .expectClean()
  }

  fun testSubtyping() {
    lint()
      .files(
        java(
            """
package test.pkg;

            import com.google.errorprone.annotations.Immutable;

            @Immutable
            interface ImmutableInterface {}

            @Immutable
            abstract class ImmutableAbstractClass {}

            class B implements ImmutableInterface {}

            class D extends ImmutableAbstractClass {}

            class F implements ImmutableInterface {
              Object unsafe;
            }

            class H extends ImmutableAbstractClass {
              Object unsafe;
            }

          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/ImmutableInterface.java:11: Error: Class extends @Immutable type test.pkg.ImmutableInterface, but is not annotated as immutable [Immutable]
                    class B implements ImmutableInterface {}
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/ImmutableInterface.java:15: Error: Class extends @Immutable type test.pkg.ImmutableInterface, but is not annotated as immutable [Immutable]
                    class F implements ImmutableInterface {
                    ^
        src/test/pkg/ImmutableInterface.java:20: Error: H is annotated as immutable, but field H.unsafe is not final [Immutable]
                      Object unsafe;
                      ~~~~~~~~~~~~~~
        src/test/pkg/ImmutableInterface.java:20: Error: H is annotated as immutable, but field H.unsafe is of type Object which is mutable [Immutable]
                      Object unsafe;
                      ~~~~~~~~~~~~~~
        4 errors
        """
          .trimIndent()
      )
  }

  fun testKotlinLazyDelegatedProperty() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg

          import com.google.errorprone.annotations.Immutable

          class MutableClass {
            var x = 0
          }

          @Immutable
          class Test {
            val safe: String by lazy { "safe" }
            val mutable: MutableClass by lazy { MutableClass() }
          }
          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/MutableClass.kt:12: Error: Test is annotated as immutable, but field Test.mutable is of type MutableClass which is mutable [Immutable]
          val mutable: MutableClass by lazy { MutableClass() }
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/MutableClass.kt:12: Error: Test is annotated as immutable, but the delegate for field Test.mutable is a container for the mutable type(s) MutableClass [Immutable]
          val mutable: MutableClass by lazy { MutableClass() }
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  fun testKotlinKClassProperty() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg

          import com.google.errorprone.annotations.Immutable
          import kotlin.reflect.KClass

          @Immutable
          class Test {
            val clazz: KClass<Any> = Any::class
          }
          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testKotlinCompanionObjectStaticProperty() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg

          import com.google.errorprone.annotations.Immutable

          class MutableClass {
            var x = 0
          }

          @Immutable
          class Test {
            val safe: String = "safe"

            companion object {
              @JvmStatic
              val s = MutableClass()
            }
          }
          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testKotlinAnonymousObject() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg

          import com.google.errorprone.annotations.Immutable

          @Immutable
          interface Super

          class MutableClass {
            var x = 0
          }

          class Test {
            val anon = object : Super {
              val testVal = MutableClass()
            }
          }
          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/Super.kt:14: Error: The anonymous class's supertype is annotated as immutable, but field testVal is of type MutableClass which is mutable [Immutable]
            val testVal = MutableClass()
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testImmutableTypeParameterCallSite() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg

          import com.google.errorprone.annotations.Immutable
          import com.google.errorprone.annotations.ImmutableTypeParameter

          class MutableClass {
            var x = 0
          }

          @Immutable
          class Holder<@ImmutableTypeParameter T>(val t: T)

          class Usage {
            fun run() {
              val allowed = Holder("safe")
              val notAllowed = Holder(MutableClass())
            }
          }
          """
          )
          .indented(),
        immutableAnnotationStub,
        immutableTypeParameterAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/MutableClass.kt:16: Error: Type parameter T is annotated with ImmutableTypeParameter but contains the mutable type(s) MutableClass [Immutable]
            val notAllowed = Holder(MutableClass())
                             ~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testRealProtobufMessageAndEnum() {
    val protoJar =
      com.intellij.openapi.application.PathManager.getJarForClass(com.google.protobuf.MessageLite::class.java)?.toFile()
        ?: error("protobuf jar not found")
    lint()
      .files(
        java(
            """
          package test.pkg;

          import com.google.errorprone.annotations.Immutable;
          import com.google.protobuf.Message;
          import com.google.protobuf.MessageLite;
          import com.google.protobuf.ProtocolMessageEnum;

          @Immutable
          class Test {
            private final Message message = null;
            private final MessageLite messageLite = null;
            private final ProtocolMessageEnum protoEnum = null;
          }
          """
          )
          .indented(),
        immutableAnnotationStub,
        LibraryReferenceTestFile("libs/protobuf.jar", protoJar),
      )
      .run()
      .expectClean()
  }

  fun testKotlinProtobufMessageAndEnum() {
    val protoJar =
      com.intellij.openapi.application.PathManager.getJarForClass(com.google.protobuf.MessageLite::class.java)?.toFile()
        ?: error("protobuf jar not found")
    lint()
      .files(
        kotlin(
            """
          package test.pkg

          import com.google.errorprone.annotations.Immutable
          import com.google.protobuf.Message
          import com.google.protobuf.MessageLite
          import com.google.protobuf.ProtocolMessageEnum

          @Immutable
          class Test(
            val message: Message,
            val messageLite: MessageLite,
            val protoEnum: ProtocolMessageEnum,
          )
          """
          )
          .indented(),
        immutableAnnotationStub,
        LibraryReferenceTestFile("libs/protobuf.jar", protoJar),
      )
      .run()
      .expectClean()
  }

  fun testSubclassInheritsContainerOf() {
    lint()
      .files(
        java(
            """
          package test.pkg;

          import com.google.errorprone.annotations.Immutable;

          @Immutable(containerOf = {"S"})
          abstract class SuperContainer<S> {
            public abstract S getValue();
          }

          class SubContainer<S> extends SuperContainer<S> {
            private final S value;

            SubContainer(S value) {
              this.value = value;
            }

            @Override
            public S getValue() {
              return value;
            }
          }
          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testParameterizedArray() {
    lint()
      .files(
        java(
            """
          package test.pkg;

          import com.google.errorprone.annotations.Immutable;

          @Immutable
          class ParameterizedArray<T> {
            final T[] arrayField = null;
          }
          """
          )
          .indented(),
        immutableAnnotationStub,
      )
      .run()
      .expect(
        """
        src/test/pkg/ParameterizedArray.java:7: Error: ParameterizedArray is annotated as immutable, but field ParameterizedArray.arrayField is an array which is mutable [Immutable]
          final T[] arrayField = null;
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }
}
