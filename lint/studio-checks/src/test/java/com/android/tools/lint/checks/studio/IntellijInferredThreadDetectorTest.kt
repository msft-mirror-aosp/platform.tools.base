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
package com.android.tools.lint.checks.studio

import com.android.tools.lint.checks.infrastructure.TestFiles.jar
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.kt
import com.android.tools.lint.checks.infrastructure.TestFiles.xml
import com.android.tools.lint.checks.infrastructure.TestLintTask
import org.junit.Ignore
import org.junit.Test

class IntellijInferredThreadDetectorTest {

  @Ignore("b/379742474")
  @Test
  fun testConditionNarrowingCurrentThread_379742474() {
    studioLint()
        .setUp()
        .files(
            kt(
                    """
                    package test.pkg
                    import com.android.annotations.concurrency.AnyThread
                    import com.android.annotations.concurrency.Slow
                    import com.android.annotations.concurrency.UiThread
                    import com.android.annotations.concurrency.WorkerThread

                    @UiThread @WorkerThread
                    fun doHeavyWorkFromAnywhere() {
                      val application = ApplicationManager.getApplication() // TODO import, or placeholder
                      if (application.isDispatchThread)
                        execOnPooledThread(::doHeavyWork)
                      else
                        doHeavyWork()
                    }

                    @UiThread fun execOnPooledThread(task: @WorkerThread () -> Unit) { }
                    @WorkerThread fun doHeavyWork() { }
                """
                )
                .indented(),
            *annotationDefinitions,
        )
        .run()
        .expectClean()
  }

  /* Old tests from [IntellijThreadDetectorTest] */

  @Test
  fun testIncompatibleMethods() {
    studioLint()
        .setUp()
        .files(
            java(
                    """
                    package test.pkg;
                    import com.android.annotations.concurrency.AnyThread;
                    import com.android.annotations.concurrency.Slow;
                    import com.android.annotations.concurrency.UiThread;
                    import com.android.annotations.concurrency.WorkerThread;

                    public class Test {
                        @Slow
                        public void slowMethod() {
                            uiThread(); // WARN
                            UiThreadClass.method(); // WARN
                        }

                        @UiThread
                        public void uiThread() {
                            slowMethod(); // WARN
                            workerThread(); // WARN
                        }

                        @Slow
                        public void okSlow() {
                            slowMethod(); // OK
                            anyThread(); // OK
                            workerThread(); // OK
                        }

                        @UiThread
                        public void okUiThread() {
                            uiThread(); // OK
                            anyThread(); // OK
                            UiThreadClass.method(); // OK
                        }

                        @AnyThread
                        public void anyThread() {
                            uiThread(); // WARN
                            slowMethod(); // WARN
                            workerThread(); // WARN
                        }

                        @WorkerThread
                        public void workerThread() {
                            uiThread(); // WARN
                        }

                        @WorkerThread
                        public void okWorkerThread() {
                            workerThread(); // OK
                            slowMethod(); // OK
                            anyThread(); // OK
                        }
                    }
                """
                )
                .indented(),
            java(
                    """
                    package test.pkg;
                    import com.android.annotations.concurrency.UiThread;

                    public class UiThreadClass {
                        @UiThread
                        public static void method() {
                        }
                    }
                """
                )
                .indented(),
            *annotationDefinitions,
        )
        .run()
        .expect(
            """
          src/test/pkg/Test.java:10: Error: Call must be from @UiThread, but context is allowing @{Slow,WorkerThread} [WrongThread]
        uiThread(); // WARN
        ~~~~~~~~~~
src/test/pkg/Test.java:11: Error: Call must be from @UiThread, but context is allowing @{Slow,WorkerThread} [WrongThread]
        UiThreadClass.method(); // WARN
                      ~~~~~~~~
src/test/pkg/Test.java:16: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
        slowMethod(); // WARN
        ~~~~~~~~~~~~
src/test/pkg/Test.java:17: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
        workerThread(); // WARN
        ~~~~~~~~~~~~~~
src/test/pkg/Test.java:36: Error: Call must be from @UiThread, but context is allowing @AnyThread [WrongThread]
        uiThread(); // WARN
        ~~~~~~~~~~
src/test/pkg/Test.java:37: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @AnyThread [WrongThread]
        slowMethod(); // WARN
        ~~~~~~~~~~~~
src/test/pkg/Test.java:38: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @AnyThread [WrongThread]
        workerThread(); // WARN
        ~~~~~~~~~~~~~~
src/test/pkg/Test.java:43: Error: Call must be from @UiThread, but context is allowing @{Slow,WorkerThread} [WrongThread]
        uiThread(); // WARN
        ~~~~~~~~~~
8 errors, 0 warnings
                """
        )
  }

  @Test
  fun testImplicit() {
    studioLint()
        .setUp()
        .files(
            java(
                    """
                    package test.pkg;
                    import com.intellij.openapi.application.Application;
                    import com.android.annotations.concurrency.Slow;
                    import com.android.annotations.concurrency.UiThread;
                    import com.android.annotations.concurrency.WorkerThread;
                    import com.intellij.openapi.actionSystem.AnAction;
                    import com.intellij.openapi.actionSystem.AnActionEvent;

                    @SuppressWarnings("Convert2Lambda")
                    public class Test {
                        @Slow
                        public boolean slowMethod() { }

                        private void fastMethod() { }

                        @UiThread
                        private void uiMethod() { }

                        @WorkerThread
                        private void workerMethod() { }

                        public void test() {
                            new AnAction() {
                                @UiThread
                                public void update(@NotNull AnActionEvent e) {
                                    fastMethod(); // OK
                                    slowMethod(); // WARN1
                                    uiMethod(); // OK
                                    workerMethod(); // WARN2
                                }
                            };
                            new Application().invokeLater(() -> {
                                fastMethod(); // OK
                                slowMethod(); // WARN3
                                uiMethod(); // OK
                                workerMethod(); // WARN4
                            });
                            new Application().runOnPooledThread(() -> {
                                slowMethod(); // OK
                                uiMethod(); // WARN5
                                workerMethod(); // OK
                            });
                            new Application().invokeLater(new Runnable() { // WARN6 current
                                @Override
                                public void run() {
                                    fastMethod(); // OK
                                    slowMethod(); // WARN6 ideally, but above instead
                                    uiMethod(); // OK
                                    workerMethod(); // WARN7
                                }
                            });
                            new Application().runOnPooledThread(new Runnable() { // WARN8 current
                                @Override
                                public void run() {
                                    slowMethod(); // OK
                                    uiMethod(); // WARN8 ideally, but above instead
                                    workerMethod(); // OK
                                }
                            });
                            new Application().externallyAnnotated(new Runnable() { // WARN9, WARN10 current
                                @Override
                                public void run() {
                                    slowMethod(); // WARN9 ideally
                                    slowMethod(); // WARN10 ideally
                                    uiMethod(); // OK
                                    workerMethod(); // WARN11
                                }
                            });
                        }

                        @Slow
                        public void test2() {
                            fastMethod(); // OK
                            slowMethod(); // OK
                            uiMethod(); // WARN12

                            new Application().invokeLater(() -> {
                                fastMethod(); // OK
                                slowMethod(); // WARN13
                                uiMethod(); // OK
                                workerMethod(); // WARN14
                            });

                            new Application().invokeLater(this::fastMethod); // OK
                            new Application().invokeLater(this::uiMethod); // OK
                            new Application().invokeLater(this::slowMethod); // WARN15

                            new Application().runOnPooledThread(this::uiMethod); // WARN16
                            new Application().runOnPooledThread(this::workerMethod); // OK
                            new Application().runOnPooledThread(this::slowMethod); // OK
                            new Application().runOnPooledThread(this::fastMethod); // OK

                            new Application().runWriteAction(() -> { // WARN17
                                fastMethod(); // OK
                                slowMethod(); // WARN18
                                workerMethod(); // WARN19
                                uiMethod(); // OK
                            });
                        }

                        @UiThread
                        public void test3() {
                            fastMethod(); // OK
                            slowMethod(); // WARN20
                            uiMethod(); // OK
                            workerMethod(); // WARN21

                            new Application().runWriteAction(() -> {
                                fastMethod(); // OK
                                slowMethod(); // WARN22
                                workerMethod(); // WARN23
                                uiMethod(); // OK
                            });

                            new Application().runWriteAction(this::fastMethod); // OK
                            new Application().runWriteAction(this::uiMethod); // OK
                            new Application().runWriteAction(this::slowMethod); // WARN24
                            new Application().runWriteAction(this::workerMethod); // WARN25
                        }
                    }
                """
                )
                .indented(),
            java(
                    """
                    // Stub until test infrastructure passes the right class path for non-Android
                    // modules.
                    package com.intellij.openapi.application;
                    import com.android.annotations.concurrency.UiThread;
                    import com.android.annotations.concurrency.WorkerThread;
                    import org.jetbrains.annotations.NotNull;

                    @SuppressWarnings("ALL")
                    public class Application {
                        public void runOnPooledThread(@NotNull @WorkerThread Runnable run) { run.run(); }

                        public void externallyAnnotated(@NotNull Runnable run) { run.run(); }

                        @UiThread
                        public void runWriteAction(@NotNull @UiThread Runnable run) { run.run(); }
                    }
                """
                )
                .indented(),
            java(
                    """
                    package org.jetbrains.annotations;

                    @Documented
                    @Retention(RetentionPolicy.CLASS)
                    @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
                    public @interface NotNull {}
                """
                )
                .indented(),
            java(
                    """
                    package com.intellij.openapi.actionSystem;

                    public class AnActionEvent {}
                """
                )
                .indented(),
            java(
                """
                    package com.intellij.openapi.actionSystem;

                    public class AnAction {
                        @UiThread
                        public void update(@NotNull AnActionEvent e) {
                        }
                    }
                """
            ),
            jar(
                "annotations.zip",
                xml(
                        "com/intellij/openapi/application/annotations.xml",
                        """
                        <root>
                            <item name='com.intellij.openapi.application.Application void externallyAnnotated(java.lang.Runnable) 0'>
                                <annotation name='com.android.annotations.concurrency.UiThread' />
                            </item>
                        </root>
                        """,
                    )
                    .indented(),
            ),
            *annotationDefinitions,
        )
        .run()
        .expect(
            """
        src/test/pkg/Test.java:35: Warning: Statement must run from @UiThread, incompatible with earlier code that must run from @{Slow,WorkerThread} [UnsatisfiableThreadConstraint]
            uiMethod(); // OK
            ~~~~~~~~~~
src/test/pkg/Test.java:48: Warning: Statement must run from @UiThread, incompatible with earlier code that must run from @{Slow,WorkerThread} [UnsatisfiableThreadConstraint]
                uiMethod(); // OK
                ~~~~~~~~~~
src/test/pkg/Test.java:52: Warning: Call results in an unsatisfiable thread requirement [UnsatisfiableThreadConstraint]
        new Application().runOnPooledThread(new Runnable() { // WARN8 current
                          ^
src/test/pkg/Test.java:56: Warning: Statement must run from @UiThread, incompatible with earlier code that must run from @{Slow,WorkerThread} [UnsatisfiableThreadConstraint]
                uiMethod(); // WARN8 ideally, but above instead
                ~~~~~~~~~~
src/test/pkg/Test.java:60: Warning: Call results in an unsatisfiable thread requirement [UnsatisfiableThreadConstraint]
        new Application().externallyAnnotated(new Runnable() { // WARN9, WARN10 current
                          ^
src/test/pkg/Test.java:65: Warning: Statement must run from @UiThread, incompatible with earlier code that must run from @{Slow,WorkerThread} [UnsatisfiableThreadConstraint]
                uiMethod(); // OK
                ~~~~~~~~~~
src/test/pkg/Test.java:80: Warning: Statement must run from @UiThread, incompatible with earlier code that must run from @{Slow,WorkerThread} [UnsatisfiableThreadConstraint]
            uiMethod(); // OK
            ~~~~~~~~~~
src/test/pkg/Test.java:27: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
                slowMethod(); // WARN1
                ~~~~~~~~~~~~
src/test/pkg/Test.java:29: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
                workerMethod(); // WARN2
                ~~~~~~~~~~~~~~
src/test/pkg/Test.java:40: Error: Call must be from @UiThread, but a super method is allowing @{Slow,WorkerThread} [WrongThread]
            uiMethod(); // WARN5
            ~~~~~~~~~~
src/test/pkg/Test.java:52: Error: Call has an unsatisfiable thread requirement, but a super method is allowing @{Slow,WorkerThread} [WrongThread]
        new Application().runOnPooledThread(new Runnable() { // WARN8 current
                                            ^
src/test/pkg/Test.java:60: Error: Call has an unsatisfiable thread requirement, but a super method is allowing @UiThread [WrongThread]
        new Application().externallyAnnotated(new Runnable() { // WARN9, WARN10 current
                                              ^
src/test/pkg/Test.java:75: Error: Call must be from @UiThread, but context is allowing @{Slow,WorkerThread} [WrongThread]
        uiMethod(); // WARN12
        ~~~~~~~~~~
src/test/pkg/Test.java:88: Error: Call has an unsatisfiable thread requirement, but context is allowing @{Slow,WorkerThread} [WrongThread]
        new Application().runOnPooledThread(this::uiMethod); // WARN16
                          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
src/test/pkg/Test.java:88: Error: Call must be from @UiThread, but a super method is allowing @{Slow,WorkerThread} [WrongThread]
        new Application().runOnPooledThread(this::uiMethod); // WARN16
                                            ~~~~~~~~~~~~~~
src/test/pkg/Test.java:93: Error: Call must be from @UiThread, but context is allowing @{Slow,WorkerThread} [WrongThread]
        new Application().runWriteAction(() -> { // WARN17
                          ^
src/test/pkg/Test.java:95: Error: Call must be from @{Slow,WorkerThread}, but a super method is allowing @UiThread [WrongThread]
            slowMethod(); // WARN18
            ~~~~~~~~~~~~
src/test/pkg/Test.java:96: Error: Call must be from @{Slow,WorkerThread}, but a super method is allowing @UiThread [WrongThread]
            workerMethod(); // WARN19
            ~~~~~~~~~~~~~~
src/test/pkg/Test.java:104: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
        slowMethod(); // WARN20
        ~~~~~~~~~~~~
src/test/pkg/Test.java:106: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
        workerMethod(); // WARN21
        ~~~~~~~~~~~~~~
src/test/pkg/Test.java:110: Error: Call must be from @{Slow,WorkerThread}, but a super method is allowing @UiThread [WrongThread]
            slowMethod(); // WARN22
            ~~~~~~~~~~~~
src/test/pkg/Test.java:111: Error: Call must be from @{Slow,WorkerThread}, but a super method is allowing @UiThread [WrongThread]
            workerMethod(); // WARN23
            ~~~~~~~~~~~~~~
src/test/pkg/Test.java:117: Error: Argument must allow calling run() from @UiThread, but that call is requiring @{Slow,WorkerThread} [WrongThread]
        new Application().runWriteAction(this::slowMethod); // WARN24
                                         ~~~~~~~~~~~~~~~~
src/test/pkg/Test.java:117: Error: Call must be from @{Slow,WorkerThread}, but a super method is allowing @UiThread [WrongThread]
        new Application().runWriteAction(this::slowMethod); // WARN24
                                         ~~~~~~~~~~~~~~~~
src/test/pkg/Test.java:118: Error: Argument must allow calling run() from @UiThread, but that call is requiring @{Slow,WorkerThread} [WrongThread]
        new Application().runWriteAction(this::workerMethod); // WARN25
                                         ~~~~~~~~~~~~~~~~~~
src/test/pkg/Test.java:118: Error: Call must be from @{Slow,WorkerThread}, but a super method is allowing @UiThread [WrongThread]
        new Application().runWriteAction(this::workerMethod); // WARN25
                                         ~~~~~~~~~~~~~~~~~~
19 errors, 7 warnings
                """
        )
  }

  private fun TestLintTask.setUp() =
      issues(
          IntellijInferredThreadDetector.THREAD,
          IntellijInferredThreadDetector.UNSATISFIABLE_CONSTRAINT,
      )

  private val annotationDefinitions =
      arrayOf(
          java(
                  """
                    package com.android.annotations.concurrency;

                    import java.lang.annotation.Documented;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;

                    @Documented
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.METHOD)
                    public @interface Slow {}
                """
              )
              .indented(),
          java(
                  """
                    package com.android.annotations.concurrency;

                    import java.lang.annotation.Documented;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;

                    @Documented
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.METHOD)
                    public @interface UiThread {}
                """
              )
              .indented(),
          java(
                  """
                    package com.android.annotations.concurrency;

                    import java.lang.annotation.Documented;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;

                    @Documented
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
                    public @interface AnyThread {}
                """
              )
              .indented(),
          java(
                  """
                    package com.android.annotations.concurrency;

                    import java.lang.annotation.Documented;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.RetentionPolicy;
                    import java.lang.annotation.Target;

                    @Documented
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
                    public @interface WorkerThread {}
                """
              )
              .indented(),
      )

  @Test
  fun testBaseAssumptions() {
    studioLint()
        .setUp()
        .files(
            java(
                    """
                    package test.pkg;
                    import com.android.annotations.concurrency.AnyThread;
                    import com.android.annotations.concurrency.Slow;
                    import com.android.annotations.concurrency.UiThread;
                    import com.android.annotations.concurrency.WorkerThread;
                    import com.intellij.openapi.application.Application;
                    import java.util.stream.Stream;

                    public class Test {
                        @Slow static void slow() { }
                        static void f(Application app) {
                            app.invokeLater(() -> slow());
                        }

                        @UiThread
                        static void g(Stream<Test> s) {
                            s.forEach((x) -> slow());
                        }
                    }
                """
                )
                .indented(),
            java(
                    """
            package com.intellij.openapi.application;

            public class Application {
                public void invokeLater(Runnable run) { }
            }
          """
                )
                .indented(),
            java(
                    """
                    package java.util.function;

                    public interface Consumer<T> {
                        void accept(T t);
                    }
                    """
                        .trimIndent()
                )
                .indented(),
            java(
                    """
                    package java.util.stream;
                    import java.util.function.Consumer;

                    public interface Stream<T> {
                        void forEach(Consumer<? super T> action);
                    }
                    """
                        .trimIndent()
                )
                .indented(),
            *annotationDefinitions,
        )
        .run()
        .expect(
            """
            src/test/pkg/Test.java:12: Error: Argument at x₀ must allow calling run() from @UiThread, but that call is requiring @{Slow,WorkerThread}. [WrongThread]
                    app.invokeLater(() -> slow());
                        ~~~~~~~~~~~~~~~~~~~~~~~~~
            src/test/pkg/Test.java:17: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
                    s.forEach((x) -> slow());
                      ~~~~~~~~~~~~~~~~~~~~~~
            2 errors
            """
                .trimIndent()
        )
  }
}
