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

import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class GuardedByDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector = GuardedByDetector()

  override fun getIssues(): List<Issue> = listOf(GuardedByDetector.ISSUE)

  override fun lint(): TestLintTask = super.lint().skipTestModes(TestMode.PARENTHESIZED, TestMode.WHITESPACE, TestMode.JVM_OVERLOADS)

  private val guardedByAnnotationStub =
    java(
        """
      package com.google.errorprone.annotations.concurrent;
      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
      @Retention(RetentionPolicy.CLASS)
      public @interface GuardedBy {
          String value();
      }
      """
      )
      .indented()

  private val altGuardedByAnnotationStub =
    java(
        """
      package com.android.annotations.concurrency;
      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;

      @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
      @Retention(RetentionPolicy.CLASS)
      public @interface GuardedBy {
          String value();
      }
      """
      )
      .indented()

  private val mutexStub =
    kotlin(
        """
      package kotlinx.coroutines.sync

      class Mutex
      """
      )
      .indented()

  private val libStub =
    kotlin(
        """
      package com.google.android.tools.lint.checks.inputs.libs

      import com.google.errorprone.annotations.concurrent.GuardedBy

      inline fun Int.unsafeLambdaFun(noinline unsafeLambda: (Int) -> Unit) {
        unsafeLambda(this)
      }

      inline fun mixedLambdaFun(
        safeLambda: () -> Unit,
        noinline noInlineLambda: () -> Unit,
        crossinline crossInlineLambda: () -> Unit,
      ) {
        safeLambda()
      }

      private class InternalLockClass

      @GuardedBy("InternalLockClass.class") fun guardedByInternalClass() {}
      """
      )
      .indented()

  private val javaInputStub =
    java(
        """
      package com.google.android.tools.lint.checks.guardedby;

      import com.google.errorprone.annotations.concurrent.GuardedBy;

      public class JavaInput {
        @GuardedBy("this")
        int guardedJavaInt = 0;

        @GuardedBy("this")
        int getGuardedJavaProp() {
          return 0;
        }

        @SuppressWarnings("UnusedVariable")
        private final Object lock = new Object();

        @GuardedBy("lock")
        public int guardedByPrivateFieldInt = 0;
      }
      """
      )
      .indented()

  fun testDocumentationExample() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.ArrayList;
        import java.util.List;

        class Names {
          @GuardedBy("this")
          List<String> names = new ArrayList<>();

          public void addName(String name) {
            synchronized (this) {
              names.add(name);
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testLocked() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.concurrent.locks.Lock;

        class Test {
          final Lock lock = null;

          @GuardedBy("lock")
          int x;

          void m() {
            lock.lock();
            x++;
            try {
              x++;
            } catch (Exception e) {
              x--;
            } finally {
              lock.unlock();
            }
            x++;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:14: Error: Locks must be properly released in the case of an exception being thrown. Try using withLock() or a try/finally expression [GuardedBy]
            x++;
            ~
        src/threadsafety/Test.java:22: Error: Access to x should be guarded by Test.lock [GuardedBy]
            x++;
            ~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testStaticLocked() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          @GuardedBy("Test.class")
          static int x;

          static synchronized void m() {
            x++;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testWrongLock() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.concurrent.locks.Lock;

        class Test {
          final Lock lock1 = null;
          final Lock lock2 = null;

          @GuardedBy("lock1")
          int x;

          void m() {
            lock2.lock();
            try {
              x++;
            } finally {
              lock2.unlock();
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:16: Error: Access to x should be guarded by Test.lock1 [GuardedBy]
              x++;
              ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testReentrantNestedLock() {
    // Regression repro: the inner try/finally's unconditional
    // lockSet.removeAll(finallyUnlockedLocks) drops the lock acquired by the
    // OUTER lock()/try/finally, even though locksAreReentrant = true.
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.concurrent.locks.Lock;

        class Test {
          final Lock lock = null;

          @GuardedBy("lock")
          int x;

          void m() {
            lock.lock();
            try {
              lock.lock();
              try {
                x++;
              } finally {
                lock.unlock();
              }
              x++;
            } finally {
              lock.unlock();
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      // BUG: this should be clean. `lock` is still held reentrantly at line 21
      // because of the outer lock()/try/finally, but the inner try/finally's
      // unconditional lockSet.removeAll(finallyUnlockedLocks) already dropped it.
      .expect(
        """
        src/threadsafety/Test.java:21: Error: Access to x should be guarded by Test.lock [GuardedBy]
              x++;
              ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testUnlockOutsideFinally() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.concurrent.locks.Lock;

        class Test {
          final Lock lock = null;

          @GuardedBy("lock")
          int x;

          void m() {
            lock.lock();
            x++;
            lock.unlock();
            x++;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      // TODO: unguarded access on line 16 should ideally be reported as well (it is when removing line 14)
      .expect(
        """
        src/threadsafety/Test.java:14: Error: Locks must be properly released in the case of an exception being thrown. Try using withLock() or a try/finally expression [GuardedBy]
            x++;
            ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testAnonymousClassAccess() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          @GuardedBy("this")
          boolean b = false;

          private void n() {
            b = true;
            new Object() {
              void m() {
                b = true;
              }
            };
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .allowDuplicates() // needed to allow (correctly) reporting the two errors seen below
      .run()
      .expect(
        """
        src/threadsafety/Test.java:10: Error: Access to b should be guarded by Test.this [GuardedBy]
            b = true;
            ~
        src/threadsafety/Test.java:13: Error: Access to b should be guarded by Test.this [GuardedBy]
                b = true;
                ~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testGuardedStaticFieldAccess_1() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          public static final Object lock = new Object();

          @GuardedBy("lock")
          public static int x;

          void m() {
            synchronized (Test.lock) {
              Test.x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testGuardedStaticFieldAccess_2() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          public static final Object lock = new Object();

          @GuardedBy("lock")
          public static int x;

          void m() {
            synchronized (lock) {
              Test.x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testGuardedStaticFieldAccess_3() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          public static final Object lock = new Object();

          @GuardedBy("lock")
          public static int x;

          void m() {
            synchronized (Test.lock) {
              x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testGuardedStaticFieldAccess_enclosingClass() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          @GuardedBy("Test.class")
          public static int x;

          static synchronized void n() {
            Test.x++;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testBadStaticFieldAccess() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          public static final Object lock = new Object();

          @GuardedBy("lock")
          public static int x;

          void m() {
            Test.x++;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:12: Error: Access to x should be guarded by Test.lock [GuardedBy]
            Test.x++;
            ~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testBadGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          @GuardedBy("foo")
          int y;
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:6: Error: Could not resolve lock identifier foo [GuardedBy]
          @GuardedBy("foo")
          ^
        1 error
        """
          .trimIndent()
      )
  }

  fun testUnheldInstanceGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          final Object mu = new Object();

          @GuardedBy("mu")
          int y;
        }

        class Main {
          void m(Test t) {
            t.y++;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:14: Error: Access to y should be guarded by Test.mu [GuardedBy]
            t.y++;
            ~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testUnheldItselfGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Itself {
          @GuardedBy("itself")
          int x;

          void incrementX() {
            x++;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Itself.java:10: Error: Access to x should be guarded by Itself.x [GuardedBy]
            x++;
            ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testI541() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.List;

        class Itself {
          @GuardedBy("itself")
          List<String> xs;

          void f() {
            this.xs.add("");
            synchronized (this.xs) {
              this.xs.add("");
            }
            synchronized (this.xs) {
              xs.add("");
            }
            synchronized (xs) {
              this.xs.add("");
            }
            synchronized (xs) {
              xs.add("");
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Itself.java:11: Error: Access to xs should be guarded by Itself.xs [GuardedBy]
            this.xs.add("");
            ~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testMethodQualifiedWithThis() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Itself {
          @GuardedBy("this")
          void f() {}

          void g() {
            this.f();
            synchronized (this) {
              f();
            }
            synchronized (this) {
              this.f();
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Itself.java:10: Error: Access to f should be guarded by Itself.this [GuardedBy]
            this.f();
            ~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testCtor() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          @GuardedBy("this")
          int x;

          public Test() {
            this.x = 42;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testBadGuardMethodAccess() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          @GuardedBy("this")
          void x() {}

          void m() {
            x();
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:10: Error: Access to x should be guarded by Test.this [GuardedBy]
            x();
            ~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testTransitiveGuardMethodAccess() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          @GuardedBy("this")
          void x() {}

          @GuardedBy("this")
          void m() {
            x();
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInnerClass_enclosingClassLock() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          final Object mu = new Object();

          @GuardedBy("mu")
          boolean b = false;

          private final class Baz {
            public void m() {
              synchronized (mu) {
                n();
              }
            }

            @GuardedBy("Test.this.mu")
            private void n() {
              b = true;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInnerClass_thisLock() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          @GuardedBy("this")
          boolean b = false;

          private final class Baz {
            private synchronized void n() {
              b = true;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:11: Error: Access to b should be guarded by Test.this [GuardedBy]
              b = true;
              ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testAnonymousClass() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          @GuardedBy("this")
          boolean b = false;

          private synchronized void n() {
            b = true;
            new Object() {
              void m() {
                b = true;
              }
            };
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:13: Error: Access to b should be guarded by Test.this [GuardedBy]
                b = true;
                ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testInheritedLock() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class A {
          final Object lock = new Object();
        }

        class B extends A {
          @GuardedBy("lock")
          boolean b = false;

          void m() {
            synchronized (lock) {
              b = true;
            }
            ;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testEnclosingSuperAccess() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class A {
          final Object lock = new Object();

          @GuardedBy("lock")
          boolean flag = false;
        }

        class B extends A {
          void m() {
            new Object() {
              @GuardedBy("lock")
              void n() {
                flag = true;
              }
            };
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testSuperAccess_this() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class A {
          final Object lock = new Object();

          @GuardedBy("this")
          boolean flag = false;
        }

        class B extends A {
          synchronized void m() {
            flag = true;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testSuperAccess_lock() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class A {
          final Object lock = new Object();

          @GuardedBy("lock")
          boolean flag = false;
        }

        class B extends A {
          void m() {
            synchronized (lock) {
              flag = true;
            }
            synchronized (this.lock) {
              flag = true;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testSuperAccess_staticLock() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class A {
          static final Object lock = new Object();

          @GuardedBy("lock")
          static boolean flag = false;
        }

        class B extends A {
          void m() {
            synchronized (A.lock) {
              flag = true;
            }
            synchronized (B.lock) {
              flag = true;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testOtherClass_bad_staticLock() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class A {
          static final Object lock = new Object();

          @GuardedBy("lock")
          static boolean flag = false;
        }

        class B {
          static final Object lock = new Object();

          @GuardedBy("lock")
          static boolean flag = false;

          void m() {
            synchronized (B.lock) {
              A.flag = true;
            }
            synchronized (A.lock) {
              B.flag = true;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/A.java:20: Error: Access to flag should be guarded by A.lock [GuardedBy]
              A.flag = true;
              ~~~~~~
        src/threadsafety/A.java:23: Error: Access to flag should be guarded by B.lock [GuardedBy]
              B.flag = true;
              ~~~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testOtherClass_bad_staticLock_alsoSub() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class A {
          static final Object lock = new Object();

          @GuardedBy("lock")
          static boolean flag = false;
        }

        class B extends A {
          static final Object lock = new Object();

          @GuardedBy("lock")
          static boolean flag = false;

          void m() {
            synchronized (B.lock) {
              A.flag = true;
            }
            synchronized (A.lock) {
              B.flag = true;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/A.java:20: Error: Access to flag should be guarded by A.lock [GuardedBy]
              A.flag = true;
              ~~~~~~
        src/threadsafety/A.java:23: Error: Access to flag should be guarded by B.lock [GuardedBy]
              B.flag = true;
              ~~~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testOtherClass_staticLock() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class A {
          static final Object lock = new Object();

          @GuardedBy("lock")
          static boolean flag = false;
        }

        class B {
          void m() {
            synchronized (A.lock) {
              A.flag = true;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInstanceAccess_instanceGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class InstanceAccess_InstanceGuard {
          class A {
            final Object lock = new Object();

            @GuardedBy("lock")
            int x;
          }

          class B extends A {
            void m() {
              synchronized (this.lock) {
                this.x++;
              }
              this.x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/InstanceAccess_InstanceGuard.java:18: Error: Access to x should be guarded by A.lock [GuardedBy]
              this.x++;
              ~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testInstanceAccess_lexicalGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class InstanceAccess_LexicalGuard {
          class Outer {
            final Object lock = new Object();

            class Inner {
              @GuardedBy("lock")
              int x;

              void m() {
                synchronized (Outer.this.lock) {
                  this.x++;
                }
                this.x++;
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/InstanceAccess_LexicalGuard.java:17: Error: Access to x should be guarded by Outer.lock [GuardedBy]
                this.x++;
                ~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testLexicalAccess_instanceGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class LexicalAccess_InstanceGuard {
          class Outer {
            final Object lock = new Object();

            @GuardedBy("lock")
            int x;

            class Inner {
              void m() {
                synchronized (Outer.this.lock) {
                  Outer.this.x++;
                }
                Outer.this.x++;
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/LexicalAccess_InstanceGuard.java:17: Error: Access to x should be guarded by Outer.lock [GuardedBy]
                Outer.this.x++;
                ~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testLexicalAccess_lexicalGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class LexicalAccess_LexicalGuard {
          class Outer {
            final Object lock = new Object();

            class Inner {
              @GuardedBy("lock")
              int x;

              class InnerMost {
                void m() {
                  synchronized (Outer.this.lock) {
                    Inner.this.x++;
                  }
                  Inner.this.x++;
                }
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/LexicalAccess_LexicalGuard.java:18: Error: Access to x should be guarded by Outer.lock [GuardedBy]
                  Inner.this.x++;
                  ~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testInstanceAccess_thisGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class InstanceAccess_ThisGuard {
          class A {
            @GuardedBy("this")
            int x;
          }

          class B extends A {
            void m() {
              synchronized (this) {
                this.x++;
              }
              this.x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/InstanceAccess_ThisGuard.java:16: Error: Access to x should be guarded by A.this [GuardedBy]
              this.x++;
              ~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testInstanceAccess_namedThisGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class InstanceAccess_NamedThisGuard {
          class Outer {
            class Inner {
              @GuardedBy("Outer.this")
              int x;

              void m() {
                synchronized (Outer.this) {
                  x++;
                }
                x++;
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/InstanceAccess_NamedThisGuard.java:15: Error: Access to x should be guarded by Outer.this [GuardedBy]
                x++;
                ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testLexicalAccess_thisGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class LexicalAccess_ThisGuard {
          class Outer {
            @GuardedBy("this")
            int x;

            class Inner {
              void m() {
                synchronized (Outer.this) {
                  Outer.this.x++;
                }
                Outer.this.x++;
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/LexicalAccess_ThisGuard.java:15: Error: Access to x should be guarded by Outer.this [GuardedBy]
                Outer.this.x++;
                ~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testLexicalAccess_namedThisGuard() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class LexicalAccess_NamedThisGuard {
          class Outer {
            class Inner {
              @GuardedBy("Outer.this")
              int x;

              class InnerMost {
                void m() {
                  synchronized (Outer.this) {
                    Inner.this.x++;
                  }
                  Inner.this.x++;
                }
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/LexicalAccess_NamedThisGuard.java:16: Error: Access to x should be guarded by Outer.this [GuardedBy]
                  Inner.this.x++;
                  ~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testComplexLockExpression() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        class ComplexLockExpression {
          final Object[] xs = {};
          final int[] ys = {};

          void m(int i) {
            synchronized (xs[i]) {
              ys[i]++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testWrongInnerClassInstance() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class WrongInnerClassInstance {
          final Object lock = new Object();

          class Inner {
            @GuardedBy("lock")
            int x = 0;

            void m(Inner i) {
              synchronized (WrongInnerClassInstance.this.lock) {
                i.x++;
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/WrongInnerClassInstance.java:14: Error: Access to x should be guarded by WrongInnerClassInstance.lock [GuardedBy]
                i.x++;
                ~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testTryWithResources() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.concurrent.locks.Lock;

        class Test {
          Lock lock;

          @GuardedBy("lock")
          int x;

          static class LockCloser implements AutoCloseable {
            Lock lock;

            LockCloser(Lock lock) {
              this.lock = lock;
              this.lock.lock();
            }

            @Override
            public void close() throws Exception {
              lock.unlock();
            }
          }

          void m() throws Exception {
            try (LockCloser _ = new LockCloser(lock)) {
              x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:9: Error: The lock lock should be final, but is not [GuardedBy]
          @GuardedBy("lock")
          ^
        src/threadsafety/Test.java:27: Error: Variables being locked on should be final, but lock is not [GuardedBy]
            try (LockCloser _ = new LockCloser(lock)) {
            ^
        2 errors
        """
          .trimIndent()
      )
  }

  fun testTryWithResources_resourceVariables() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.concurrent.locks.Lock;

        class Test {
          Lock lock;

          @GuardedBy("lock")
          int x;

          void m(AutoCloseable c) throws Exception {
            try (AutoCloseable unused = c) {
              x++;
            } catch (Exception e) {
              x++;
              throw e;
            } finally {
              x++;
            }
          }

          void n(AutoCloseable c) throws Exception {
            lock.lock();
            try (AutoCloseable unused = c) {
            } catch (Exception e) {
              x++;
            } finally {
              lock.unlock();
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:9: Error: The lock lock should be final, but is not [GuardedBy]
          @GuardedBy("lock")
          ^
        src/threadsafety/Test.java:14: Error: Access to x should be guarded by Test.lock [GuardedBy]
              x++;
              ~
        src/threadsafety/Test.java:16: Error: Access to x should be guarded by Test.lock [GuardedBy]
              x++;
              ~
        src/threadsafety/Test.java:19: Error: Access to x should be guarded by Test.lock [GuardedBy]
              x++;
              ~
        4 errors
        """
          .trimIndent()
      )
  }

  fun testLexicalScopingExampleOne() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Transaction {
          @GuardedBy("this")
          int x;

          interface Handler {
            void apply();
          }

          public void handle() {
            runHandler(
                new Handler() {
                  public void apply() {
                    x++;
                  }
                });
          }

          private synchronized void runHandler(Handler handler) {
            handler.apply();
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Transaction.java:17: Error: Access to x should be guarded by Transaction.this [GuardedBy]
                    x++;
                    ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testLexicalScopingExampleTwo() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Transaction {
          @GuardedBy("this")
          int x;

          interface Handler {
            void apply();
          }

          public void handle() {
            runHandler(
                new Handler() {
                  @GuardedBy("Transaction.this")
                  public void apply() {
                    x++;
                  }
                });
          }

          private synchronized void runHandler(Handler handler) {
            // This isn't safe...
            handler.apply();
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testAliasing() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.ArrayList;
        import java.util.List;

        class Names {
          @GuardedBy("this")
          List<String> names = new ArrayList<>();

          public void addName(String name) {
            List<String> copyOfNames;
            synchronized (this) {
              copyOfNames = names; // OK: access of 'names' guarded by 'this'
            }
            copyOfNames.add(name); // should be an error: this access is not thread-safe!
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testSemaphore() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.concurrent.Semaphore;

        class Test {
          final Semaphore semaphore = null;

          @GuardedBy("semaphore")
          int x;

          void m() throws InterruptedException {
            semaphore.acquire();
            x++;
            try {
              x++;
            } finally {
              semaphore.release();
            }
            x++;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:14: Error: Locks must be properly released in the case of an exception being thrown. Try using withLock() or a try/finally expression [GuardedBy]
            x++;
            ~
        src/threadsafety/Test.java:20: Error: Access to x should be guarded by Test.semaphore [GuardedBy]
            x++;
            ~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testSynchronizedOnLockMethod_negative() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        // do not remove, regression test for a bug when RWL is on the classpath

        class Test {
          Object lock() {
            return null;
          }

          @GuardedBy("lock()")
          int x;

          void m() {
            synchronized (lock()) {
              x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testSuppressLocalVariable() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.concurrent.locks.Lock;

        class Test {
          final Lock lock = null;

          @GuardedBy("lock")
          int x;

          void m() {
            @SuppressWarnings("GuardedBy")
            int z = x++;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testEnclosingBlockScope() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          public final Object mu = new Object();

          @GuardedBy("mu")
          int x = 1;

          {
            new Object() {
              void f() {
                synchronized (mu) {
                  x++;
                }
              }
            };
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInstanceInitializersAreUnchecked() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          public final Object mu1 = new Object();
          public final Object mu2 = new Object();

          @GuardedBy("mu1")
          int x = 1;

          {
            synchronized (mu2) {
              x++;
            }
            synchronized (mu1) {
              x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testClassInitializersAreUnchecked() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          public static final Object mu1 = new Object();
          public static final Object mu2 = new Object();

          @GuardedBy("mu1")
          static int x = 1;

          static {
            synchronized (mu2) {
              x++;
            }
            synchronized (mu1) {
              x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testStaticFieldInitializersAreUnchecked() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          public static final Object mu = new Object();

          @GuardedBy("mu")
          static int x0 = 1;

          static int x1 = x0++;
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInstanceFieldInitializersAreUnchecked() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          public final Object mu = new Object();

          @GuardedBy("mu")
          int x0 = 1;

          int x1 = x0++;
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testInnerClassMethod() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          public final Object mu = new Object();

          class Inner {
            @GuardedBy("mu")
            int x;

            @GuardedBy("Test.this")
            int y;
          }

          void f(Inner i) {
            synchronized (mu) {
              i.x++;
            }
          }

          synchronized void g(Inner i) {
            i.y++;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:18: Error: Access to x should be guarded by Test.mu [GuardedBy]
              i.x++;
              ~~~
        src/threadsafety/Test.java:23: Error: Access to y should be guarded by Test.this [GuardedBy]
            i.y++;
            ~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testRegression_b27686620() {
    lint()
      .files(
        java(
            """
        class A extends One {
          void g() {}
        }
        """
          )
          .indented(),
        java(
            """
        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class One {
          @GuardedBy("One.class")
          static int x = 1;

          static void f() {
            synchronized (One.class) {
              x++;
            }
          }
        }

        class Two {
          @GuardedBy("Two.class")
          static int x = 1;

          static void f() {
            synchronized (Two.class) {
              x++;
            }
          }
        }
        """
          )
          .indented(),
        java(
            """
        class B extends Two {
          void g() {}
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testQualifiedMethod() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          @GuardedBy("this")
          void f() {}

          void main() {
            new Test().f();
            Test t = new Test();
            t.f();
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:10: Error: Access to f should be guarded by Test.this [GuardedBy]
            new Test().f();
            ~~~~~~~~~~~~~~
        src/threadsafety/Test.java:12: Error: Access to f should be guarded by Test.this [GuardedBy]
            t.f();
            ~~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testQualifiedMethodWrongThis_causesFinding_whenMatchOnErrorsFlagNotSet() {
    lint()
      .files(
        java(
            """
        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class MemoryAllocatedInfoJava {
          private static final class AllocationStats {
            @GuardedBy("MemoryAllocatedInfoJava.this")
            void addAllocation(long size) {}
          }

          public void addStackTrace(long size) {
            synchronized (this) {
              AllocationStats stat = new AllocationStats();
              stat.addAllocation(size);
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/MemoryAllocatedInfoJava.java:6: Error: Could not resolve lock identifier MemoryAllocatedInfoJava.this [GuardedBy]
            void addAllocation(long size) {}
                 ~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testNoSuchMethod() {
    lint()
      .files(
        java(
            """
        public class Foo {}
        """
          )
          .indented(),
        java(
            """
        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          Foo foo;

          @GuardedBy("foo.get()")
          Object o = null;
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/Test.java:6: Error: Could not resolve lock identifier foo.get() [GuardedBy]
          @GuardedBy("foo.get()")
          ^
        1 error
        """
          .trimIndent()
      )
  }

  fun testLambda() {
    lint()
      .files(
        java(
            """
        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          @GuardedBy("this")
          int x;

          synchronized void f() {
            Runnable r =
                () -> {
                  x++;
                };
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/Test.java:10: Error: Access to x should be guarded by Test.this [GuardedBy]
                  x++;
                  ~
        1 error
        """
          .trimIndent()
      )
  }

  fun testStaticMemberClass_enclosingInstanceLock() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          final Object mu = new Object();

          private static final class Baz {
            @GuardedBy("mu")
            int x;
          }

          public void m(Baz b) {
            synchronized (mu) {
              b.x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:9: Error: Could not resolve lock identifier mu [GuardedBy]
            @GuardedBy("mu")
            ^
        1 error
        """
          .trimIndent()
      )
  }

  fun testStaticMemberClass_staticOuterClassLock() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Test {
          static final Object mu = new Object();

          private static final class Baz {
            @GuardedBy("mu")
            int x;
          }

          public void m(Baz b) {
            synchronized (mu) {
              b.x++;
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testNewClassBase() {
    lint()
      .files(
        java(
            """
        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class Foo {
          private final Object mu = new Object();

          @GuardedBy("mu")
          int x;
        }
        """
          )
          .indented(),
        java(
            """
        public class Bar {
          void bar(Foo f) {
            f.x = 10;
          }

          void bar() {
            new Foo().x = 11;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/Bar.java:3: Error: Access to x should be guarded by Foo.mu [GuardedBy]
            f.x = 10;
            ~~~
        src/Bar.java:7: Error: Access to x should be guarded by Foo.mu [GuardedBy]
            new Foo().x = 11;
            ~~~~~~~~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testImmediateLambdas() {
    lint()
      .files(
        java(
            """
        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.ArrayList;
        import java.util.List;
        import java.util.Optional;

        class Test {
          @GuardedBy("this")
          private final List<String> xs = new ArrayList<>();

          @GuardedBy("ys")
          private final List<String> ys = new ArrayList<>();

          public synchronized void add(Optional<String> x) {
            x.ifPresent(y -> xs.add(y));
            x.ifPresent(xs::add);
            x.ifPresent(y -> ys.add(y));
            x.ifPresent(ys::add);
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/Test.java:16: Error: Access to ys should be guarded by Test.ys [GuardedBy]
            x.ifPresent(y -> ys.add(y));
                             ~~
        src/Test.java:17: Error: Access to ys should be guarded by Test.ys [GuardedBy]
            x.ifPresent(ys::add);
                        ~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testMethodReferences_shouldBeFlagged() {
    lint()
      .files(
        java(
            """
        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.ArrayList;
        import java.util.List;
        import java.util.function.Predicate;

        class Test {
          @GuardedBy("this")
          private final List<String> xs = new ArrayList<>();

          private final List<Predicate<String>> preds = new ArrayList<>();

          public synchronized void test() {
            preds.add(xs::contains);
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/Test.java:13: Error: Access to xs should be guarded by Test.this [GuardedBy]
            preds.add(xs::contains);
                      ~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testMethodReference_referencedMethodIsFlagged() {
    lint()
      .files(
        java(
            """
        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.ArrayList;
        import java.util.List;
        import java.util.Optional;
        import java.util.function.Predicate;

        class Test {
          private final List<Predicate<String>> preds = new ArrayList<>();

          public synchronized void test() {
            Optional.of("foo").ifPresent(this::frobnicate);
            preds.add(this::frobnicate);
          }

          @GuardedBy("this")
          public boolean frobnicate(String x) {
            return true;
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/Test.java:12: Error: This member should be guarded by a lock; saving a reference to it is unsafe [GuardedBy]
            preds.add(this::frobnicate);
                      ~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testLambdaMethodInvokedImmediately_shouldNotBeFlagged() {
    lint()
      .files(
        java(
            """
        import com.google.errorprone.annotations.concurrent.GuardedBy;
        import java.util.List;

        class Test {
          @GuardedBy("this")
          private final Object o = new Object();

          public synchronized void test(List<?> xs) {
            xs.forEach(x -> o.toString());
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testBindingVariable() {
    lint()
      .files(
        java(
            """
        import com.google.errorprone.annotations.concurrent.GuardedBy;

        interface I {
          class Impl implements I {
            @GuardedBy("this")
            private int number = 42;
          }

          public static void t(I other) {
            if (other instanceof Impl otherImpl) {
              synchronized (otherImpl) {
                int a = otherImpl.number;
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testParseError() {
    lint()
      .files(
        java(
            """
        package example;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        public class IllegalStartOfExpression {
          @GuardedBy("itself (synchronized blocks)")
          int field;
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/example/IllegalStartOfExpression.java:6: Error: Could not resolve lock identifier itself (synchronized blocks) [GuardedBy]
          @GuardedBy("itself (synchronized blocks)")
          ^
        1 error
        """
          .trimIndent()
      )
  }

  fun testKotlin_lambda() {
    lint()
      .files(
        kotlin(
            """
        package threadsafety

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class Test {
          @GuardedBy("this")
          private var x: Int = 0

          fun m(nonInlined: (() -> Unit) -> Unit) {
            val r: () -> Unit = {
              x++
            }
            synchronized(this) {
              nonInlined {
                x++
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.kt:11: Error: Access to x should be guarded by Test.this [GuardedBy]
              x++
              ~
        src/threadsafety/Test.kt:15: Error: Access to x should be guarded by Test.this [GuardedBy]
                x++
                ~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_immediateLambdas() {
    lint()
      .files(
        kotlin(
            """
        package threadsafety

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.Optional

        class Test {
          val x: Optional<Int> = Optional.empty()
          @GuardedBy("ys")
          private val ys: MutableList<Int> = mutableListOf()

          fun m() {
            x.ifPresent { y -> ys.add(y) }
            x.ifPresent(ys::add)
          }

          fun m2() {
            synchronized(ys) {
              x.ifPresent { y -> ys.add(y) }
              x.ifPresent(ys::add)
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.kt:12: Error: Access to ys should be guarded by Test.ys [GuardedBy]
            x.ifPresent { y -> ys.add(y) }
                               ~~
        src/threadsafety/Test.kt:13: Error: Access to ys should be guarded by Test.ys [GuardedBy]
            x.ifPresent(ys::add)
                        ~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_methodReferences_shouldBeFlagged() {
    lint()
      .files(
        kotlin(
            """
        package threadsafety

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class Test {
          @GuardedBy("this")
          private val xs: MutableList<Int> = mutableListOf()

          fun m(preds: MutableList<(Int) -> Boolean>) {
            preds.add(xs::contains)
          }

          fun m2(preds: MutableList<(Int) -> Boolean>) {
            synchronized(this) {
              preds.add(xs::contains)
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.kt:10: Error: Access to xs should be guarded by Test.this [GuardedBy]
            preds.add(xs::contains)
                      ~~
        src/threadsafety/Test.kt:15: Error: Access to xs should be guarded by Test.this [GuardedBy]
              preds.add(xs::contains)
                        ~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_methodReference_referencedMethodIsFlagged() {
    lint()
      .files(
        kotlin(
            """
        package threadsafety

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class Test {
          @GuardedBy("this")
          fun guarded() {}

          fun m(rs: MutableList<() -> Unit>) {
            rs.add(this::guarded)
          }

          fun m2(rs: MutableList<() -> Unit>) {
            synchronized(this) {
              rs.add(this::guarded)
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.kt:10: Error: This member should be guarded by a lock; saving a reference to it is unsafe [GuardedBy]
            rs.add(this::guarded)
                   ~~~~~~~~~~~~~
        src/threadsafety/Test.kt:15: Error: This member should be guarded by a lock; saving a reference to it is unsafe [GuardedBy]
              rs.add(this::guarded)
                     ~~~~~~~~~~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_lambdaMethodInvokedImmediately_shouldNotBeFlagged() {
    lint()
      .files(
        kotlin(
            """
        package threadsafety

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.Optional

        class Test {
          @GuardedBy("this")
          fun guarded() {}

          @GuardedBy("this")
          fun guardedConsumer(x: String) {}

          fun m(opt: Optional<String>) {
            synchronized(this) {
              opt.ifPresent { guarded() }
              opt.ifPresent(this::guardedConsumer)
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testKotlin_inlineLambda() {
    lint()
      .files(
        kotlin(
            """
        package threadsafety

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class Test {
          @GuardedBy("this")
          private var x: Int = 0

          fun m() {
            synchronized(this) {
              x.let { it + 1 }
              apply { x = 42 }
              run { x++ }
              also { x = 10 }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testEffectivelyFinalLocalVariable() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          @GuardedBy("this")
          void guarded() {}

          static void testSynchronized() {
            Test lock = new Test();
            synchronized (lock) {
              lock.guarded();
            }
          }

          static void testSynchronizedUninitialized() {
            Test lock;
            lock = new Test();
            synchronized (lock) {
              lock.guarded();
            }
          }

          static void testParameter(Test lock) {
            synchronized (lock) {
              lock.guarded();
            }
          }

          static void testInLambda(List<String> list) {
            Test lock = new Test();
            list.forEach(
                x -> {
                  synchronized (lock) {
                    lock.guarded();
                  }
                });
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testNotEffectivelyFinalLocalVariable_reassigned() {
    lint()
      .files(
        java(
            """
        package threadsafety;

        import com.google.errorprone.annotations.concurrent.GuardedBy;

        class Test {
          @GuardedBy("this")
          void guarded() {}

          static void testSynchronizedReassigned() {
            Test lock = new Test();
            lock = new Test();
            synchronized (lock) {
              lock.guarded();
            }
          }

          static void testSynchronizedUninitializedReassigned() {
            Test lock;
            lock = new Test();
            lock = new Test();
            synchronized (lock) {
              lock.guarded();
            }
          }

          static void testSynchronizedInLoop() {
            Test lock = null;
            for (int i = 0; i < 2; i++) {
              lock = new Test();
            }
            synchronized (lock) {
              lock.guarded();
            }
          }

          static void testParameterReassigned(Test lock) {
            lock = new Test();
            synchronized (lock) {
              lock.guarded();
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/threadsafety/Test.java:12: Error: Variables being locked on should be final, but lock is not [GuardedBy]
            synchronized (lock) {
            ^
        src/threadsafety/Test.java:21: Error: Variables being locked on should be final, but lock is not [GuardedBy]
            synchronized (lock) {
            ^
        src/threadsafety/Test.java:31: Error: Variables being locked on should be final, but lock is not [GuardedBy]
            synchronized (lock) {
            ^
        src/threadsafety/Test.java:38: Error: Variables being locked on should be final, but lock is not [GuardedBy]
            synchronized (lock) {
            ^
        4 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_guardedField() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import kotlinx.coroutines.sync.Mutex
        import java.util.concurrent.locks.Lock

        class GuardedFieldTest() {
          @GuardedBy("this") private var _lockedInt: Int = 0
          @GuardedBy("this") private var _lockedString: String = ""
          private val mutex = Mutex()
          @GuardedBy("mutex") private var _mutexGuardedInt: Int = 0

          fun testNoImplicitLock() {
            _lockedInt = 1
            _lockedInt == 1
            _lockedInt.inc()
            _lockedString.length
          }

          fun testQualifiedExpression() {
            this._lockedInt = 1
            this._lockedInt == 1
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
        mutexStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/GuardedFieldTest.kt:11: Error: To guard on Mutexes, '@GuardedByMutex' should be used instead. [GuardedBy]
          @GuardedBy("mutex") private var _mutexGuardedInt: Int = 0
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedFieldTest.kt:14: Error: Access to _lockedInt should be guarded by GuardedFieldTest.this [GuardedBy]
            _lockedInt = 1
            ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedFieldTest.kt:15: Error: Access to _lockedInt should be guarded by GuardedFieldTest.this [GuardedBy]
            _lockedInt == 1
            ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedFieldTest.kt:16: Error: Access to _lockedInt should be guarded by GuardedFieldTest.this [GuardedBy]
            _lockedInt.inc()
            ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedFieldTest.kt:17: Error: Access to _lockedString should be guarded by GuardedFieldTest.this [GuardedBy]
            _lockedString.length
            ~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedFieldTest.kt:21: Error: Access to _lockedInt should be guarded by GuardedFieldTest.this [GuardedBy]
            this._lockedInt = 1
            ~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedFieldTest.kt:22: Error: Access to _lockedInt should be guarded by GuardedFieldTest.this [GuardedBy]
            this._lockedInt == 1
            ~~~~~~~~~~~~~~~
        7 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_guardedConstructorProperty() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.Lock

        class GuardedConstructorPropertyTest(@set:GuardedBy("this") var constructorLockedSetter: Int)
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/GuardedConstructorPropertyTest.kt:6: Error: @GuardedBy is not allowed to be specifically on getters or setters of constructor defined properties, but is on at least one of constructorLockedSetter's accessors [GuardedBy]
        class GuardedConstructorPropertyTest(@set:GuardedBy("this") var constructorLockedSetter: Int)
                                             ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testKotlin_guardedProperty() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import com.android.annotations.concurrency.GuardedBy as AltGuardedBy
        import java.util.concurrent.locks.Lock

        open class GuardedPropertyTest(@GuardedBy("this") private var constructorInt: Int) {
          @GuardedBy("this") private var lockedInt: Int = 0

          @get:GuardedBy("this")
          var lockedGetter: Int
            get() = 0
            set(value) {}

          @set:GuardedBy("this")
          var lockedSetter: Int
            get() = 0
            set(value) {}

          @GuardedBy("this")
          var lockedIntWithGetter: Int = 0
            get() = field
            set(value) {
              field = value
            }

          @GuardedBy("this")
          // `getLockedIntWithImplicitAccessors` should be guarded by `GuardedPropertyTest.this`
          var lockedIntWithImplicitAccessors: Int = 0

          @GuardedBy("this")
          // accessor
          // `getInternalLockedIntWithImplicitAccessors` should be guarded by `GuardedPropertyTest.this`
          // `setInternalLockedIntWithImplicitAccessors` should be guarded by `GuardedPropertyTest.this`
          internal var internalLockedIntWithImplicitAccessors: Int = 0

          public fun getInternalLockedIntWithImplicitAccessors(): Int = 0

          @get:GuardedBy("this") @set:GuardedBy("this") internal var internalLockedAccessors: Int = 0

          @GuardedBy("this") fun guardedFun() {}

          @GuardedBy("nonsense") private var malformedGuardedVar: Int = 0

          @AltGuardedBy("this") private var altGuardedVar: Int = 0

          @field:GuardedBy("this") @get:GuardedBy("this") val publicInt = 0

          init {
            lockedInt = 1
            lockedInt == 1
          }

          @GuardedBy("nonsense") fun testMalformedLockArgument() {}

          fun testNoImplicitLockProperty() {
            lockedInt = 1
            lockedInt == 1
            lockedInt.let {}

            altGuardedVar = 1
            altGuardedVar == 1

            internalLockedAccessors = 1
            internalLockedAccessors == 1
          }

          fun testNoImplicitLockFun() {
            guardedFun()
            synchronized(this) { guardedFun() }
          }

          fun testNoImplicitLockConstructorProperty() {
            constructorInt = 1
            constructorInt == 1
            synchronized(this) { constructorInt = 1 }
          }

          fun testNoImplicitLockGetter() {
            lockedGetter == 1
          }

          fun testNoImplicitLockSetter() {
            lockedSetter = 1
            lockedSetter == 1
          }

          fun testQualifiedRefs() {
            this.lockedInt = 1
            this.lockedInt == 1
            this.guardedFun()
          }

          fun testFunctionRef() {
            val f = this::guardedFun
          }

          @Synchronized
          fun testImplicitLock() {
            lockedInt = 1
            lockedInt == 1
            lockedInt.inc()

            altGuardedVar = 1
            altGuardedVar == 1
            altGuardedVar.inc()
          }

          inner class Inner {
            @Synchronized
            fun m() {
              lockedInt = 1
              lockedInt == 1
            }

            fun testSyncBlock() {
              synchronized(this@GuardedPropertyTest) {
                lockedInt = 1
                lockedInt == 1
              }
            }

            fun testSyncWrongThis() {
              synchronized(this@Inner) {
                lockedInt = 1
                lockedInt == 1
              }
            }
          }

          @Synchronized
          fun testAnonymousClass() {
            object {
              @Synchronized
              fun m() {
                lockedInt = 1
                lockedInt == 1
              }
            }
          }

          @GuardedBy("this")
          fun testTransitiveLock() {
            lockedInt = 1
            lockedInt == 1
          }

          @Synchronized
          fun testLockInLambda() {
            var r = {
              synchronized(this) {
                lockedInt = 1
                lockedInt == 1
              }
              lockedInt = 1
              lockedInt
            }
          }

          fun testSyncBlock() {
            synchronized(this) {
              lockedInt = 1
              lockedInt == 1
            }
          }
        }

        class ThisGuardedMethodClass() {
          @GuardedBy("this") fun guardedFun() {}
        }

        class TestFieldAsThisGuard() {
          private val field: ThisGuardedMethodClass = ThisGuardedMethodClass()

          fun testFieldAsThisGuard() {
            field.guardedFun()

            synchronized(this.field) { field.guardedFun() }
          }

          fun testLambdaWithReceiver() {
            val l: TestFieldAsThisGuard.() -> Unit = label@{
              synchronized(this.field) { field.guardedFun() }
              synchronized(field) { this.field.guardedFun() }
              synchronized(this@label.field) { field.guardedFun() }
              synchronized(this@label.field) { this@label.field.guardedFun() }
              synchronized(this@TestFieldAsThisGuard.field) { this@TestFieldAsThisGuard.field.guardedFun() }
              synchronized(this@TestFieldAsThisGuard.field) {
                field.guardedFun()
              }
              synchronized(field) {
                this@TestFieldAsThisGuard.field.guardedFun()
              }
            }
          }
        }

        open class LambdaReceiverBoundTestSuper {
          val lock = Any()

          @GuardedBy("lock") fun guardedFun() {}
        }

        class LambdaReceiverBoundTestSub : LambdaReceiverBoundTestSuper() {
          fun testSuperLambdaReceiver() {
            val lambda: LambdaReceiverBoundTestSub.() -> Unit = label@{
              synchronized(this@LambdaReceiverBoundTestSub.lock) {
                guardedFun()

                this.guardedFun()

                this@LambdaReceiverBoundTestSub.guardedFun()
              }

              synchronized(lock) {
                this@LambdaReceiverBoundTestSub.guardedFun()

                guardedFun()

                this.guardedFun()
              }
            }
          }
        }

        fun testNonFinalLock() {
          var test = GuardedPropertyTest(0)
          synchronized(test) { test.publicInt == 1 }
        }

        fun testThisLockOutsideClass(test: GuardedPropertyTest) {
          test.publicInt
          synchronized(test) { test.publicInt == 1 }
        }

        fun testFunctionLockOutsideClass(test: GuardedPropertyTest) {
          test.guardedFun()
          synchronized(test) { test.guardedFun() }
        }

        fun testThisLockOutsideClass() {
          val test = GuardedPropertyTest(0)
          val otherTest = GuardedPropertyTest(0)
          test.publicInt == 0

          synchronized(test) {
            test.publicInt == 0
            otherTest.publicInt == 0
          }
        }

        class GuardedPropertyTestContainer {
          val test = GuardedPropertyTest(1)
        }

        fun testThisLockFromProperty() {
          val testInstance = GuardedPropertyTestContainer()
          testInstance.test.publicInt == 1
          synchronized(testInstance.test) { testInstance.test.publicInt == 1 }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
        altGuardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:22: Error: Access to lockedIntWithGetter should be guarded by GuardedPropertyTest.this [GuardedBy]
            get() = field
                    ~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:24: Error: Access to lockedIntWithGetter should be guarded by GuardedPropertyTest.this [GuardedBy]
              field = value
              ~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:29: Error: Access to lockedIntWithImplicitAccessors in implicit accessor getLockedIntWithImplicitAccessors should be guarded by GuardedPropertyTest.this [GuardedBy]
          var lockedIntWithImplicitAccessors: Int = 0
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:29: Error: Access to lockedIntWithImplicitAccessors in implicit accessor setLockedIntWithImplicitAccessors should be guarded by GuardedPropertyTest.this [GuardedBy]
          var lockedIntWithImplicitAccessors: Int = 0
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:35: Error: Access to internalLockedIntWithImplicitAccessors in implicit accessor getInternalLockedIntWithImplicitAccessors should be guarded by GuardedPropertyTest.this [GuardedBy]
          internal var internalLockedIntWithImplicitAccessors: Int = 0
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:35: Error: Access to internalLockedIntWithImplicitAccessors in implicit accessor setInternalLockedIntWithImplicitAccessors should be guarded by GuardedPropertyTest.this [GuardedBy]
          internal var internalLockedIntWithImplicitAccessors: Int = 0
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:43: Error: Could not resolve lock identifier nonsense [GuardedBy]
          @GuardedBy("nonsense") private var malformedGuardedVar: Int = 0
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:54: Error: Could not resolve lock identifier nonsense [GuardedBy]
          @GuardedBy("nonsense") fun testMalformedLockArgument() {}
                                     ~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:57: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
            lockedInt = 1
            ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:58: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
            lockedInt == 1
            ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:59: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
            lockedInt.let {}
            ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:61: Error: Access to altGuardedVar should be guarded by GuardedPropertyTest.this [GuardedBy]
            altGuardedVar = 1
            ~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:62: Error: Access to altGuardedVar should be guarded by GuardedPropertyTest.this [GuardedBy]
            altGuardedVar == 1
            ~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:64: Error: Access to internalLockedAccessors should be guarded by GuardedPropertyTest.this [GuardedBy]
            internalLockedAccessors = 1
            ~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:65: Error: Access to internalLockedAccessors should be guarded by GuardedPropertyTest.this [GuardedBy]
            internalLockedAccessors == 1
            ~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:69: Error: Access to guardedFun should be guarded by GuardedPropertyTest.this [GuardedBy]
            guardedFun()
            ~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:74: Error: Access to constructorInt should be guarded by GuardedPropertyTest.this [GuardedBy]
            constructorInt = 1
            ~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:75: Error: Access to constructorInt should be guarded by GuardedPropertyTest.this [GuardedBy]
            constructorInt == 1
            ~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:80: Error: Access to lockedGetter should be guarded by GuardedPropertyTest.this [GuardedBy]
            lockedGetter == 1
            ~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:84: Error: Access to lockedSetter should be guarded by GuardedPropertyTest.this [GuardedBy]
            lockedSetter = 1
            ~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:89: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
            this.lockedInt = 1
            ~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:90: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
            this.lockedInt == 1
            ~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:91: Error: Access to guardedFun should be guarded by GuardedPropertyTest.this [GuardedBy]
            this.guardedFun()
            ~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:95: Error: This member should be guarded by a lock; saving a reference to it is unsafe [GuardedBy]
            val f = this::guardedFun
                    ~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:112: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
              lockedInt = 1
              ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:113: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
              lockedInt == 1
              ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:125: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
                lockedInt = 1
                ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:126: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
                lockedInt == 1
                ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:136: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
                lockedInt = 1
                ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:137: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
                lockedInt == 1
                ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:155: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
              lockedInt = 1
              ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:156: Error: Access to lockedInt should be guarded by GuardedPropertyTest.this [GuardedBy]
              lockedInt
              ~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:176: Error: Access to guardedFun should be guarded by ThisGuardedMethodClass.this [GuardedBy]
            field.guardedFun()
            ~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:189: Error: Access to guardedFun should be guarded by ThisGuardedMethodClass.this [GuardedBy]
                field.guardedFun()
                ~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:192: Error: Access to guardedFun should be guarded by ThisGuardedMethodClass.this [GuardedBy]
                this@TestFieldAsThisGuard.field.guardedFun()
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:208: Error: Access to guardedFun should be guarded by LambdaReceiverBoundTestSuper.lock [GuardedBy]
                guardedFun()
                ~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:210: Error: Access to guardedFun should be guarded by LambdaReceiverBoundTestSuper.lock [GuardedBy]
                this.guardedFun()
                ~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:216: Error: Access to guardedFun should be guarded by LambdaReceiverBoundTestSuper.lock [GuardedBy]
                this@LambdaReceiverBoundTestSub.guardedFun()
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:228: Error: Variables being locked on should be final, but test is not [GuardedBy]
          synchronized(test) { test.publicInt == 1 }
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:232: Error: Access to publicInt should be guarded by GuardedPropertyTest.this [GuardedBy]
          test.publicInt
          ~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:237: Error: Access to guardedFun should be guarded by GuardedPropertyTest.this [GuardedBy]
          test.guardedFun()
          ~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:244: Error: Access to publicInt should be guarded by GuardedPropertyTest.this [GuardedBy]
          test.publicInt == 0
          ~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:248: Error: Access to publicInt should be guarded by GuardedPropertyTest.this [GuardedBy]
            otherTest.publicInt == 0
            ~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedPropertyTest.kt:258: Error: Access to publicInt should be guarded by GuardedPropertyTest.this [GuardedBy]
          testInstance.test.publicInt == 1
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        44 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_fieldAsThisGuard() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class ThisGuardedMethodClass() {
          @GuardedBy("this") fun guardedFun() {}
        }

        class TestFieldAsThisGuard() {
          private val field: ThisGuardedMethodClass = ThisGuardedMethodClass()

          fun testFieldAsThisGuard() {
            field.guardedFun()

            synchronized(this.field) { field.guardedFun() }
          }

          fun testLambdaWithReceiver() {
            val l: TestFieldAsThisGuard.() -> Unit = label@{
              synchronized(this.field) { field.guardedFun() }
              synchronized(field) { this.field.guardedFun() }
              synchronized(this@label.field) { field.guardedFun() }
              synchronized(this@label.field) { this@label.field.guardedFun() }
              synchronized(this@TestFieldAsThisGuard.field) { this@TestFieldAsThisGuard.field.guardedFun() }
              synchronized(this@TestFieldAsThisGuard.field) {
                field.guardedFun()
              }
              synchronized(field) {
                this@TestFieldAsThisGuard.field.guardedFun()
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/ThisGuardedMethodClass.kt:13: Error: Access to guardedFun should be guarded by ThisGuardedMethodClass.this [GuardedBy]
            field.guardedFun()
            ~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ThisGuardedMethodClass.kt:26: Error: Access to guardedFun should be guarded by ThisGuardedMethodClass.this [GuardedBy]
                field.guardedFun()
                ~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ThisGuardedMethodClass.kt:29: Error: Access to guardedFun should be guarded by ThisGuardedMethodClass.this [GuardedBy]
                this@TestFieldAsThisGuard.field.guardedFun()
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        3 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_lambdaReceiverBound() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy

        open class LambdaReceiverBoundTestSuper {
          val lock = Any()

          @GuardedBy("lock") fun guardedFun() {}
        }

        class LambdaReceiverBoundTestSub : LambdaReceiverBoundTestSuper() {
          fun testSuperLambdaReceiver() {
            val lambda: LambdaReceiverBoundTestSub.() -> Unit = label@{
              synchronized(this@LambdaReceiverBoundTestSub.lock) {
                guardedFun()

                this.guardedFun()

                this@LambdaReceiverBoundTestSub.guardedFun()
              }

              synchronized(lock) {
                this@LambdaReceiverBoundTestSub.guardedFun()

                guardedFun()

                this.guardedFun()
              }
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/LambdaReceiverBoundTestSuper.kt:15: Error: Access to guardedFun should be guarded by LambdaReceiverBoundTestSuper.lock [GuardedBy]
                guardedFun()
                ~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/LambdaReceiverBoundTestSuper.kt:17: Error: Access to guardedFun should be guarded by LambdaReceiverBoundTestSuper.lock [GuardedBy]
                this.guardedFun()
                ~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/LambdaReceiverBoundTestSuper.kt:23: Error: Access to guardedFun should be guarded by LambdaReceiverBoundTestSuper.lock [GuardedBy]
                this@LambdaReceiverBoundTestSub.guardedFun()
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        3 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_guardedByField() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.Lock

        open class GuardedByFieldTestSuper {
          val superLock = Object()
        }

        class GuardedByFieldTest() : GuardedByFieldTestSuper() {
          val lock = Object()
          var nonFinalLock = Object()

          @GuardedBy("lock") @get:GuardedBy("lock") @set:GuardedBy("lock") var guardedInt: Int = 0

          @GuardedBy("superLock") private var superGuardedInt: Int = 0

          @GuardedBy("nonFinalLock") private var notProperlyGuardedInt = 0

          fun testNoLock() {
            guardedInt = 1
            guardedInt == 1
          }

          fun testNoSuperLock() {
            superGuardedInt = 1
            superGuardedInt == 1
          }

          fun testSyncBlock() {
            synchronized(lock) {
              guardedInt = 1
              guardedInt == 1
              this.guardedInt = 1
              this.guardedInt == 1
            }

            synchronized(this.lock) {
              guardedInt = 1
              guardedInt == 1
            }

            synchronized(superLock) {
              superGuardedInt = 1
              superGuardedInt == 1
            }
          }

          fun testOtherInstance(other: GuardedByFieldTest) {
            synchronized(lock) {
              other.guardedInt == 1
              other.guardedInt.inc()

              guardedInt == 1
              guardedInt.inc()
            }

            synchronized(other.lock) {
              guardedInt == 1

              other.guardedInt == 1
              other.guardedInt.inc()
            }
          }

          inner class Inner {
            @GuardedBy("lock") @set:GuardedBy("lock") @get:GuardedBy("lock") var innerGuardedInt: Int = 0

            fun testNoLock() {
              innerGuardedInt = 1
              innerGuardedInt == 1
            }

            fun testLock() {
              synchronized(lock) {
                innerGuardedInt = 1
                innerGuardedInt == 1
              }
            }
          }

          inner class InnerShadowed {
            val superLock = Object()

            @GuardedBy("superLock") private var shadowedGuardedInt: Int = 0

            fun testShadowedLock() {
              synchronized(superLock) {
                shadowedGuardedInt = 1
                shadowedGuardedInt == 1
              }
            }

            fun testWrongShadowedLock() {
              shadowedGuardedInt = 1
              shadowedGuardedInt == 1
            }
          }

          inner class InnerGuardedByFieldTest : GuardedByFieldTestSuper() {
            @GuardedBy("superLock") private var shadowedGuardedInt: Int = 0

            fun testShadowedLock() {
              synchronized(superLock) {
                shadowedGuardedInt = 1
                shadowedGuardedInt == 1
              }
            }

            fun testWrongShadowedLock() {
              shadowedGuardedInt = 1
              shadowedGuardedInt == 1
            }
          }

          class Nested(@GuardedBy("blah") private var constructorInt: Int) {
            @GuardedBy("lock") private var nestedGuardedInt: Int = 0
          }
        }

        class TestFieldGuardedLock() {
          val testField = GuardedByFieldTest()

          fun testExtraQualifiedLock() {
            synchronized(this.testField.lock) { testField.guardedInt == 1 }
          }
        }

        fun testFieldGuardedLock() {
          val testClass = GuardedByFieldTest()
          testClass.guardedInt = 1
          synchronized(testClass.lock) { testClass.guardedInt = 1 }
        }

        fun testNonFinalLockWithField() {
          var test = GuardedByFieldTest()
          synchronized(test.lock) { test.guardedInt = 1 }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:18: Error: The lock nonFinalLock should be final, but is not [GuardedBy]
          @GuardedBy("nonFinalLock") private var notProperlyGuardedInt = 0
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:21: Error: Access to guardedInt should be guarded by GuardedByFieldTest.lock [GuardedBy]
            guardedInt = 1
            ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:22: Error: Access to guardedInt should be guarded by GuardedByFieldTest.lock [GuardedBy]
            guardedInt == 1
            ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:26: Error: Access to superGuardedInt should be guarded by GuardedByFieldTestSuper.superLock [GuardedBy]
            superGuardedInt = 1
            ~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:27: Error: Access to superGuardedInt should be guarded by GuardedByFieldTestSuper.superLock [GuardedBy]
            superGuardedInt == 1
            ~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:51: Error: Access to guardedInt should be guarded by GuardedByFieldTest.lock [GuardedBy]
              other.guardedInt == 1
              ~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:52: Error: Access to guardedInt should be guarded by GuardedByFieldTest.lock [GuardedBy]
              other.guardedInt.inc()
              ~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:59: Error: Access to guardedInt should be guarded by GuardedByFieldTest.lock [GuardedBy]
              guardedInt == 1
              ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:70: Error: Access to innerGuardedInt should be guarded by GuardedByFieldTest.lock [GuardedBy]
              innerGuardedInt = 1
              ~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:71: Error: Access to innerGuardedInt should be guarded by GuardedByFieldTest.lock [GuardedBy]
              innerGuardedInt == 1
              ~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:95: Error: Access to shadowedGuardedInt should be guarded by InnerShadowed.superLock [GuardedBy]
              shadowedGuardedInt = 1
              ~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:96: Error: Access to shadowedGuardedInt should be guarded by InnerShadowed.superLock [GuardedBy]
              shadowedGuardedInt == 1
              ~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:111: Error: Access to shadowedGuardedInt should be guarded by GuardedByFieldTestSuper.superLock [GuardedBy]
              shadowedGuardedInt = 1
              ~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:112: Error: Access to shadowedGuardedInt should be guarded by GuardedByFieldTestSuper.superLock [GuardedBy]
              shadowedGuardedInt == 1
              ~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:116: Error: Could not resolve lock identifier blah [GuardedBy]
          class Nested(@GuardedBy("blah") private var constructorInt: Int) {
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:117: Error: Could not resolve lock identifier lock [GuardedBy]
            @GuardedBy("lock") private var nestedGuardedInt: Int = 0
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:131: Error: Access to guardedInt should be guarded by GuardedByFieldTest.lock [GuardedBy]
          testClass.guardedInt = 1
          ~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByFieldTestSuper.kt:137: Error: Variables being locked on should be final, but test.lock is not [GuardedBy]
          synchronized(test.lock) { test.guardedInt = 1 }
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        18 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_guardedByNonFinalConstructorProperty() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class GuardedByNonFinalConstructorProperty(var lock: Any) {
          @GuardedBy("lock") private var int = 0
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/GuardedByNonFinalConstructorProperty.kt:6: Error: The lock lock should be final, but is not [GuardedBy]
          @GuardedBy("lock") private var int = 0
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testKotlin_subclassJavaClass() {
    lint()
      .allowDuplicates()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.Lock

        open class GuardedByCheckerJavaSubclass() : JavaInput() {
          fun testNoImplicitLock() {
            guardedJavaInt = 1
            guardedJavaInt == 1
            guardedJavaProp == 1
          }

          @Synchronized
          fun testSyncedJavaLock() {
            guardedJavaInt = 1
            guardedJavaInt == 1
            guardedJavaProp == 1
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
        javaInputStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/GuardedByCheckerJavaSubclass.kt:8: Error: Access to guardedJavaInt should be guarded by JavaInput.this [GuardedBy]
            guardedJavaInt = 1
            ~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByCheckerJavaSubclass.kt:9: Error: Access to guardedJavaInt should be guarded by JavaInput.this [GuardedBy]
            guardedJavaInt == 1
            ~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/GuardedByCheckerJavaSubclass.kt:10: Error: Access to getGuardedJavaProp should be guarded by JavaInput.this [GuardedBy]
            guardedJavaProp == 1
            ~~~~~~~~~~~~~~~
        3 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_thisQualified() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class ThisQualifiedTestClass {
          @GuardedBy("this") private var state = ""

          fun updateState(state: String) {
            this.state = state
            synchronized(this) { this.state = state }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/ThisQualifiedTestClass.kt:9: Error: Access to state should be guarded by ThisQualifiedTestClass.this [GuardedBy]
            this.state = state
            ~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testKotlin_readWriteLockUnsupported() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.ReadWriteLock
        import java.util.concurrent.locks.ReentrantReadWriteLock
        import java.util.concurrent.locks.Lock

        class ReentrantLockUnsupportedTest(
          val lock: ReadWriteLock,
          val reentrantLock: ReentrantReadWriteLock,
        ) {
          @GuardedBy("lock") private var guardedInt = 0

          @GuardedBy("reentrantLock") private var reentrantGuardedInt = 0

          fun testReadWriteLockIgnored() {
            guardedInt = 1
          }

          fun testReentrantLockIgnored() {
            reentrantGuardedInt = 1
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/ReentrantLockUnsupportedTest.kt:12: Error: Checking GuardedBy locks of type java.util.concurrent.locks.ReadWriteLock is not currently supported [GuardedBy]
          @GuardedBy("lock") private var guardedInt = 0
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ReentrantLockUnsupportedTest.kt:14: Error: Checking GuardedBy locks of type java.util.concurrent.locks.ReadWriteLock is not currently supported [GuardedBy]
          @GuardedBy("reentrantLock") private var reentrantGuardedInt = 0
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_synchronizedGetterAndSetter() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class SynchronizedGetterAndSetter {
          @GuardedBy("this") @get:Synchronized @set:Synchronized var syncedInt = 0

          @GuardedBy("this") @get:Synchronized val finalSyncedInt = 0
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testKotlin_unguardedDefaultAccessor() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class UnguardedDefaultAccessor {
          @GuardedBy("this")
          @get:GuardedBy("this")
          // `setUnguardedSetterVar` should be guarded by `UnguardedDefaultAccessor.this`
          var unguardedSetterVar = 0
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/UnguardedDefaultAccessor.kt:9: Error: Access to unguardedSetterVar in implicit accessor setUnguardedSetterVar should be guarded by UnguardedDefaultAccessor.this [GuardedBy]
          var unguardedSetterVar = 0
              ~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testKotlin_complexGetterWithOtherFieldRef() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class ComplexGetterWithOtherFieldRef {
          @field:GuardedBy("this") @get:Synchronized @set:Synchronized var fieldWithSyncedAccessors = 0

          @GuardedBy("this")
          val lockedIntWithComplexGetter: Int = 0
            get() {
              this.fieldWithSyncedAccessors++
              return field
            }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/ComplexGetterWithOtherFieldRef.kt:12: Error: Access to lockedIntWithComplexGetter should be guarded by ComplexGetterWithOtherFieldRef.this [GuardedBy]
              return field
                     ~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testKotlin_javaUtilConcurrentReadWriteLock() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.ReadWriteLock
        import java.util.concurrent.locks.Lock

        class JavaUtilConcurrentReadWriteLockTest(val lock: ReadWriteLock) {
          @GuardedBy("lock") private var guardedInt = 0

          fun testReadWriteLockIgnored() {
            guardedInt = 1
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/JavaUtilConcurrentReadWriteLockTest.kt:8: Error: Checking GuardedBy locks of type java.util.concurrent.locks.ReadWriteLock is not currently supported [GuardedBy]
          @GuardedBy("lock") private var guardedInt = 0
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testKotlin_extensionFunction() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.Lock

        class ExtensionFunctionTest {
          @GuardedBy("this") private val thisGuardedInt = 1

          @GuardedBy("this")
          fun String.thisGuardedExtFun() {
            thisGuardedInt == 2
          }

          val lock = Object()

          @GuardedBy("lock") private val lockGuardedInt = 1

          @GuardedBy("lock")
          fun String.lockGuardedExtFun() {
            lockGuardedInt == 2
          }

          fun testThisExtFun() {
            val someObj = ""
            someObj.thisGuardedExtFun()
            synchronized(someObj) {
              someObj.thisGuardedExtFun()
            }
            synchronized(this) { someObj.thisGuardedExtFun() }
          }

          fun testLockExtFun() {
            val someObj = ""
            someObj.lockGuardedExtFun()
            synchronized(lock) { someObj.lockGuardedExtFun() }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/ExtensionFunctionTest.kt:25: Error: Access to thisGuardedExtFun should be guarded by ExtensionFunctionTest.this [GuardedBy]
            someObj.thisGuardedExtFun()
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ExtensionFunctionTest.kt:27: Error: Access to thisGuardedExtFun should be guarded by ExtensionFunctionTest.this [GuardedBy]
              someObj.thisGuardedExtFun()
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ExtensionFunctionTest.kt:34: Error: Access to lockGuardedExtFun should be guarded by ExtensionFunctionTest.lock [GuardedBy]
            someObj.lockGuardedExtFun()
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        3 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_lambdaAccess() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import com.google.android.tools.lint.checks.inputs.libs.guardedByInternalClass
        import com.google.android.tools.lint.checks.inputs.libs.mixedLambdaFun
        import com.google.android.tools.lint.checks.inputs.libs.unsafeLambdaFun

        class LambdaAccessTestClass() {
          val lock = Object()

          @GuardedBy("lock") private var guardedInt = 0

          @GuardedBy("lock") fun guardedFun(int: Int) {}

          fun testFunWithLambdaArg() {
            synchronized(lock) {
              funWithLambda {
                guardedInt = 1
              }
            }
          }

          fun testLetAllowed() {
            val someInt: Int? = 1
            synchronized(lock) { someInt?.let { guardedInt = it } }
          }

          fun testMethodRefAllowed() {
            val someInt: Int? = 1
            synchronized(lock) {
              // TODO(b/177928514): Support this case; do more than simply look for method references.
              someInt?.safeLambdaFun(::guardedFun)
            }
          }

          fun testMethodRefNotAllowed() {
            val someInt: Int? = 1
            synchronized(lock) {
              someInt?.unsafeLambdaFun(::guardedFun)
            }
          }

          fun testSafeLambdaFunAllowed() {
            val someInt: Int? = 1
            synchronized(lock) { someInt?.safeLambdaFun { guardedInt = it } }
          }

          fun testUnsafeLambdaFunNotAllowed() {
            val someInt: Int? = 1
            synchronized(lock) {
              someInt?.unsafeLambdaFun {
                guardedInt = it
              }
            }
          }

          fun testNoInlineNotAllowed() {
            synchronized(lock) {
              mixedLambdaFun(
                {},
                {
                  guardedInt = 1
                },
                {},
              )
            }
          }

          fun testCrossInlineNotAllowed() {
            synchronized(lock) {
              mixedLambdaFun(
                {},
                {},
                {
                  guardedInt = 1
                },
              )
            }
          }

          fun testInlineAllowed() {
            synchronized(lock) { mixedLambdaFun({ guardedInt = 1 }, {}, {}) }
          }

          fun testLocalNoInlineDisallowed() {
            synchronized(lock) {
              localUnsafeLambdaFun {
                guardedInt = 1
              }
            }
          }

          private inline fun Int.safeLambdaFun(safeLambda: (Int) -> Unit) {
            safeLambda(this)
          }

          private inline fun localUnsafeLambdaFun(noinline unsafeLambda: () -> Unit) {
            unsafeLambda()
          }

          private fun funWithLambda(lambda: () -> Unit) {}

          private fun randomFun() {}
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
        libStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/LambdaAccessTestClass.kt:18: Error: Access to guardedInt should be guarded by LambdaAccessTestClass.lock [GuardedBy]
                guardedInt = 1
                ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/LambdaAccessTestClass.kt:32: Error: This member should be guarded by a lock; saving a reference to it is unsafe [GuardedBy]
              someInt?.safeLambdaFun(::guardedFun)
                                     ~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/LambdaAccessTestClass.kt:39: Error: This member should be guarded by a lock; saving a reference to it is unsafe [GuardedBy]
              someInt?.unsafeLambdaFun(::guardedFun)
                                       ~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/LambdaAccessTestClass.kt:52: Error: Access to guardedInt should be guarded by LambdaAccessTestClass.lock [GuardedBy]
                guardedInt = it
                ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/LambdaAccessTestClass.kt:62: Error: Access to guardedInt should be guarded by LambdaAccessTestClass.lock [GuardedBy]
                  guardedInt = 1
                  ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/LambdaAccessTestClass.kt:75: Error: Access to guardedInt should be guarded by LambdaAccessTestClass.lock [GuardedBy]
                  guardedInt = 1
                  ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/LambdaAccessTestClass.kt:88: Error: Access to guardedInt should be guarded by LambdaAccessTestClass.lock [GuardedBy]
                guardedInt = 1
                ~~~~~~~~~~
        7 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_concurrentLock() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.Lock
        import kotlin.concurrent.withLock

        class ConcurrentLockTestClass(private val lock: Lock) {
          @GuardedBy("lock") private var guardedInt = 0

          @GuardedBy("lock") private fun guardedFun() {}

          fun testLock() {
            guardedInt = 1

            lock.lock()
            try {
              guardedInt = 1
              guardedInt == 1
            } finally {
              lock.unlock()
            }

            guardedInt = 1
          }

          fun testLockImproperUnlock() {
            lock.lock()
            try {
              // thrown. Try using withLock() or a try/finally expression
              guardedInt = 1
            } finally {}

            lock.unlock()
          }

          fun testUnlockInCatchAndFinally() {
            lock.lock()
            try {
              guardedInt = 1
            } catch (e: Exception) {
              lock.unlock()
            } finally {
              lock.unlock()
            }
          }

          fun testUnlockInCatch() {
            lock.lock()
            try {
              // thrown. Try using withLock() or a try/finally expression
              guardedInt = 1
            } catch (e: Exception) {
              lock.unlock()
            }
          }

          fun testWithLock() {
            guardedInt = 1

            lock.withLock {
              guardedInt = 1
              guardedInt == 1
            }

            guardedInt = 1
          }

          fun testThisQualifiedWithLock() {
            lock.withLock { this.guardedInt = 1 }
          }

          fun ConcurrentLockTestClass.testRedundantExtensionFunction() {
            this@ConcurrentLockTestClass.lock.lock()
            try {
              this@testRedundantExtensionFunction.guardedInt = 1

              this.guardedInt = 1

              guardedInt = 1

              this@ConcurrentLockTestClass.guardedInt = 1
            } finally {
              this@ConcurrentLockTestClass.lock.unlock()
            }

            this@testRedundantExtensionFunction.lock.lock()
            try {
              this@ConcurrentLockTestClass.guardedInt = 1

              this@testRedundantExtensionFunction.guardedInt = 1

              this.guardedInt = 1
            } finally {
              this@testRedundantExtensionFunction.lock.unlock()
            }

            this@ConcurrentLockTestClass.lock.withLock {
              this@testRedundantExtensionFunction.guardedInt = 1

              this@ConcurrentLockTestClass.guardedInt = 1
            }
          }

          fun testWithLockWithMethodRef() {
            // TODO(b/179261580): Support some known-safe uses of function references
            lock.withLock(::guardedFun)
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:13: Error: Access to guardedInt should be guarded by ConcurrentLockTestClass.lock [GuardedBy]
            guardedInt = 1
            ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:23: Error: Access to guardedInt should be guarded by ConcurrentLockTestClass.lock [GuardedBy]
            guardedInt = 1
            ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:30: Error: Locks must be properly released in the case of an exception being thrown. Try using withLock() or a try/finally expression [GuardedBy]
              guardedInt = 1
              ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:51: Error: Locks must be properly released in the case of an exception being thrown. Try using withLock() or a try/finally expression [GuardedBy]
              guardedInt = 1
              ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:58: Error: Access to guardedInt should be guarded by ConcurrentLockTestClass.lock [GuardedBy]
            guardedInt = 1
            ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:65: Error: Access to guardedInt should be guarded by ConcurrentLockTestClass.lock [GuardedBy]
            guardedInt = 1
            ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:75: Error: Access to guardedInt should be guarded by ConcurrentLockTestClass.lock [GuardedBy]
              this@testRedundantExtensionFunction.guardedInt = 1
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:77: Error: Access to guardedInt should be guarded by ConcurrentLockTestClass.lock [GuardedBy]
              this.guardedInt = 1
              ~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:79: Error: Access to guardedInt should be guarded by ConcurrentLockTestClass.lock [GuardedBy]
              guardedInt = 1
              ~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:88: Error: Access to guardedInt should be guarded by ConcurrentLockTestClass.lock [GuardedBy]
              this@ConcurrentLockTestClass.guardedInt = 1
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:98: Error: Access to guardedInt should be guarded by ConcurrentLockTestClass.lock [GuardedBy]
              this@testRedundantExtensionFunction.guardedInt = 1
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/ConcurrentLockTestClass.kt:106: Error: This member should be guarded by a lock; saving a reference to it is unsafe [GuardedBy]
            lock.withLock(::guardedFun)
                          ~~~~~~~~~~~~
        12 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_guardedByConstructorLock() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.Lock

        class GuardedByConstructorLock(val lock: Any) {
          @GuardedBy("lock") private val guardedInt = 1

          fun testGuardedInt() {
            synchronized(lock) { guardedInt == 1 }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expectClean()
  }

  fun testKotlin_staticLock() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.Lock

        class StaticLockTest {
          companion object {
            val staticLock = Object()
          }

          @GuardedBy("StaticLockTest.class") private var guardedByClassInt = 0

          @GuardedBy("StaticLockTest.staticLock") private var guardedByFieldInt = 0

          // Error Prone's GuardedBy check does not resolve guard references referring to imported classes.
          // We follow its example.

          @GuardedBy("Lock.class") private var guardedByImportedClassInt = 0

          @GuardedBy("NonExistentClass.class") private var guardedByNonExistentClassInt = 0

          fun testStaticClassGuard() {
            guardedByClassInt = 1

            synchronized(StaticLockTest::class.java) { guardedByClassInt = 1 }

            synchronized(StaticLockTest::class) {
              guardedByClassInt = 1
            }
          }

          fun testStaticFieldGuard() {
            guardedByFieldInt = 1

            synchronized(StaticLockTest.staticLock) { guardedByFieldInt = 1 }

            synchronized(staticLock) { guardedByFieldInt = 1 }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/StaticLockTest.kt:18: Error: Could not resolve lock identifier Lock.class [GuardedBy]
          @GuardedBy("Lock.class") private var guardedByImportedClassInt = 0
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/StaticLockTest.kt:20: Error: Could not resolve lock identifier NonExistentClass.class [GuardedBy]
          @GuardedBy("NonExistentClass.class") private var guardedByNonExistentClassInt = 0
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/StaticLockTest.kt:23: Error: Access to guardedByClassInt should be guarded by StaticLockTest.class [GuardedBy]
            guardedByClassInt = 1
            ~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/StaticLockTest.kt:28: Error: Access to guardedByClassInt should be guarded by StaticLockTest.class [GuardedBy]
              guardedByClassInt = 1
              ~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/StaticLockTest.kt:33: Error: Access to guardedByFieldInt should be guarded by StaticLockTest.staticLock [GuardedBy]
            guardedByFieldInt = 1
            ~~~~~~~~~~~~~~~~~
        5 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_separateCompileUnit() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.Lock
        import com.google.android.tools.lint.checks.inputs.libs.guardedByInternalClass
        import com.google.android.tools.lint.checks.inputs.libs.mixedLambdaFun
        import com.google.android.tools.lint.checks.inputs.libs.unsafeLambdaFun

        class SeparateCompileUnitTestClass() {
          fun accessFunGuardedByClassInOtherPackage() {
            // Ideally, this would complain about not being guarded by the internal class InternalLockClass.
            // However, Error Prone's GuardedBy check does not report an error in the case of a class guard
            // being located in a different package from where a guarded field or function is referenced. We
            // follow its example.
            guardedByInternalClass()
          }

          fun accessFieldGuardedByPrivateField() {
            val instance = JavaInput()
            instance.guardedByPrivateFieldInt = 1
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
        libStub,
        javaInputStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/SeparateCompileUnitTestClass.kt:20: Error: Access to guardedByPrivateFieldInt should be guarded by JavaInput.lock [GuardedBy]
            instance.guardedByPrivateFieldInt = 1
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testKotlin_qualifiedGuardExpressions() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.Lock

        class QualifiedGuardExpressions {
          val lock = Object()

          @GuardedBy("QualifiedGuardExpressions.this") private val guardedByQualifiedThis = 0
          @GuardedBy("this") private val guardedByUnqualifiedThis = 0

          fun testGuardedByQualifiedThis() {
            guardedByQualifiedThis == 1
            guardedByUnqualifiedThis == 1

            synchronized(this) {
              guardedByQualifiedThis == 1
              guardedByUnqualifiedThis == 1
            }

            synchronized(QualifiedGuardExpressions@ this) {
              guardedByQualifiedThis == 1
              guardedByUnqualifiedThis == 1
            }
          }

          @GuardedBy("this.lock") private val guardedByQualifiedField = 0

          fun testGuardedByQualifiedField() {
            guardedByQualifiedField == 1

            synchronized(lock) { guardedByQualifiedField == 1 }
          }

          @GuardedBy("QualifiedGuardExpressions.this.lock") private val guardedByExtraQualifiedField = 0

          fun testGuardedByExtraQualifiedField() {
            guardedByExtraQualifiedField == 1

            synchronized(lock) { guardedByExtraQualifiedField == 1 }
          }

          @GuardedBy("QualifiedGuardExpressions.lock") private val guardedByInstanceField = 0

          fun testGuardedByInstanceFieldWithoutThis() {
            guardedByInstanceField == 1

            synchronized(lock) { guardedByInstanceField == 1 }
          }

          inner class InnerClass {
            val lock = Object()

            @GuardedBy("QualifiedGuardExpressions.this") private val guardedByOuterClassInstance = 0

            fun testGuardedByOuterClassInstance() {
              guardedByOuterClassInstance == 1

              synchronized(this@QualifiedGuardExpressions) { guardedByOuterClassInstance == 1 }

              synchronized(this) {
                guardedByOuterClassInstance == 1
              }
            }

            @GuardedBy("QualifiedGuardExpressions.this.lock") private val guardedByOuterClassField = 0

            fun testGuardedByOuterClassField() {
              guardedByOuterClassField == 1

              synchronized(this@QualifiedGuardExpressions.lock) { guardedByOuterClassField == 1 }

              synchronized(this.lock) {
                guardedByOuterClassField == 1
              }
            }
          }

          @GuardedBy("companionLock") private val guardedByCompanionField = 0

          fun testGuardedByCompanionField() {
            guardedByCompanionField == 1

            synchronized(companionLock) { guardedByCompanionField == 1 }
          }

          companion object {
            val companionLock = Any()
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/QualifiedGuardExpressions.kt:13: Error: Access to guardedByQualifiedThis should be guarded by QualifiedGuardExpressions.this [GuardedBy]
            guardedByQualifiedThis == 1
            ~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/QualifiedGuardExpressions.kt:14: Error: Access to guardedByUnqualifiedThis should be guarded by QualifiedGuardExpressions.this [GuardedBy]
            guardedByUnqualifiedThis == 1
            ~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/QualifiedGuardExpressions.kt:30: Error: Access to guardedByQualifiedField should be guarded by QualifiedGuardExpressions.lock [GuardedBy]
            guardedByQualifiedField == 1
            ~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/QualifiedGuardExpressions.kt:38: Error: Access to guardedByExtraQualifiedField should be guarded by QualifiedGuardExpressions.lock [GuardedBy]
            guardedByExtraQualifiedField == 1
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/QualifiedGuardExpressions.kt:46: Error: Access to guardedByInstanceField should be guarded by QualifiedGuardExpressions.lock [GuardedBy]
            guardedByInstanceField == 1
            ~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/QualifiedGuardExpressions.kt:57: Error: Access to guardedByOuterClassInstance should be guarded by QualifiedGuardExpressions.this [GuardedBy]
              guardedByOuterClassInstance == 1
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/QualifiedGuardExpressions.kt:62: Error: Access to guardedByOuterClassInstance should be guarded by QualifiedGuardExpressions.this [GuardedBy]
                guardedByOuterClassInstance == 1
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/QualifiedGuardExpressions.kt:69: Error: Access to guardedByOuterClassField should be guarded by QualifiedGuardExpressions.lock [GuardedBy]
              guardedByOuterClassField == 1
              ~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/QualifiedGuardExpressions.kt:74: Error: Access to guardedByOuterClassField should be guarded by QualifiedGuardExpressions.lock [GuardedBy]
                guardedByOuterClassField == 1
                ~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/QualifiedGuardExpressions.kt:82: Error: Access to guardedByCompanionField should be guarded by QualifiedGuardExpressions.companionLock [GuardedBy]
            guardedByCompanionField == 1
            ~~~~~~~~~~~~~~~~~~~~~~~
        10 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_selfGuardedMembers() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy

        class SelfGuardedMembers {

          @GuardedBy("itself") private val selfGuardedString = ""

          @GuardedBy("itself") fun selfGuardedFun() {}

          @GuardedBy("this.itself") private val guardedByItself = 0

          private val itself = ""

          fun testSelfGuardedInt() {
            selfGuardedString == ""

            synchronized(selfGuardedString) { selfGuardedString == "" }
          }

          fun testSelfGuardedFun() {
            selfGuardedFun()

            synchronized(selfGuardedFun()) { selfGuardedFun() }
          }

          fun testFieldNamedItself() {
            synchronized(itself) {
              guardedByItself == 0

              selfGuardedFun()
            }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/SelfGuardedMembers.kt:16: Error: Access to selfGuardedString should be guarded by SelfGuardedMembers.selfGuardedString [GuardedBy]
            selfGuardedString == ""
            ~~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/SelfGuardedMembers.kt:22: Error: Access to selfGuardedFun should be guarded by SelfGuardedMembers.selfGuardedFun() [GuardedBy]
            selfGuardedFun()
            ~~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/SelfGuardedMembers.kt:31: Error: Access to selfGuardedFun should be guarded by SelfGuardedMembers.selfGuardedFun() [GuardedBy]
              selfGuardedFun()
              ~~~~~~~~~~~~~~~~
        3 errors
        """
          .trimIndent()
      )
  }

  fun testKotlin_testObject() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy

        object TestObject {
          val lock = Any()

          @GuardedBy("lock") private var guardedInt = 0

          fun testGuardedIntInObject() {
            guardedInt == 1

            synchronized(lock) { guardedInt == 1 }
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/TestObject.kt:11: Error: Access to guardedInt should be guarded by TestObject.lock [GuardedBy]
            guardedInt == 1
            ~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun testKotlin_methodLocks() {
    lint()
      .files(
        kotlin(
            """
        package com.google.android.tools.lint.checks.guardedby

        import com.google.errorprone.annotations.concurrent.GuardedBy
        import java.util.concurrent.locks.Lock

        class MethodLocks {
          fun lockMethod(): Any = lock

          @GuardedBy("lockMethod()") private var guardedByFunInt = 0

          @GuardedBy("MethodLocks.staticLockMethod()") private var guardedByStaticFunInt = 0

          fun testGuardedByFunInt() {
            guardedByFunInt == 1

            synchronized(lockMethod()) { guardedByFunInt == 1 }

            synchronized(this.lockMethod()) { guardedByFunInt == 1 }
          }

          fun testGuardedByStaticFunInt() {
            guardedByStaticFunInt == 1

            synchronized(staticLockMethod()) { guardedByStaticFunInt == 1 }

            synchronized(MethodLocks.staticLockMethod()) { guardedByStaticFunInt == 1 }
          }

          companion object {
            val lock = Any()

            fun staticLockMethod(): Any = lock
          }
        }
        """
          )
          .indented(),
        guardedByAnnotationStub,
      )
      .run()
      .expect(
        """
        src/com/google/android/tools/lint/checks/guardedby/MethodLocks.kt:14: Error: Access to guardedByFunInt should be guarded by MethodLocks.lockMethod() [GuardedBy]
            guardedByFunInt == 1
            ~~~~~~~~~~~~~~~
        src/com/google/android/tools/lint/checks/guardedby/MethodLocks.kt:22: Error: Access to guardedByStaticFunInt should be guarded by MethodLocks.staticLockMethod() [GuardedBy]
            guardedByStaticFunInt == 1
            ~~~~~~~~~~~~~~~~~~~~~
        2 errors
        """
          .trimIndent()
      )
  }
}
