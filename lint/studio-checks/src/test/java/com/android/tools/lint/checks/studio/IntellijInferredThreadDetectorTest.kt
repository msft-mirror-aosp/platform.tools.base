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
                            new Application().invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    fastMethod(); // OK
                                    slowMethod(); // WARN6
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
                        public void invokeLater(@NotNull Runnable run) { run.run(); }

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
        src/test/pkg/Test.java:56: Warning: Statement must run from @UiThread, incompatible with earlier code that must run from @{Slow,WorkerThread} [UnsatisfiableThreadConstraint]
                uiMethod(); // WARN8 ideally, but above instead
                ~~~~~~~~~~
src/test/pkg/Test.java:65: Warning: Statement must run from @UiThread, incompatible with earlier code that must run from @{Slow,WorkerThread} [UnsatisfiableThreadConstraint]
                uiMethod(); // OK
                ~~~~~~~~~~
src/test/pkg/Test.java:27: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
                slowMethod(); // WARN1
                ~~~~~~~~~~~~
src/test/pkg/Test.java:29: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
                workerMethod(); // WARN2
                ~~~~~~~~~~~~~~
src/test/pkg/Test.java:34: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLater is expected to run from @UiThread [WrongThread]
            slowMethod(); // WARN3
            ~~~~~~~~~~~~
src/test/pkg/Test.java:36: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLater is expected to run from @UiThread [WrongThread]
            workerMethod(); // WARN4
            ~~~~~~~~~~~~~~
src/test/pkg/Test.java:40: Error: Call must be from @UiThread, but a super method is allowing @{Slow,WorkerThread} [WrongThread]
            uiMethod(); // WARN5
            ~~~~~~~~~~
src/test/pkg/Test.java:47: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLater is expected to run from @UiThread [WrongThread]
                slowMethod(); // WARN6
                ~~~~~~~~~~~~
src/test/pkg/Test.java:49: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLater is expected to run from @UiThread [WrongThread]
                workerMethod(); // WARN7
                ~~~~~~~~~~~~~~
src/test/pkg/Test.java:52: Error: Call has an unsatisfiable thread requirement, but a super method is allowing @{Slow,WorkerThread} [WrongThread]
        new Application().runOnPooledThread(new Runnable() { // WARN8 current
                                            ^
src/test/pkg/Test.java:60: Error: Call has an unsatisfiable thread requirement, but a super method is allowing @UiThread [WrongThread]
        new Application().externallyAnnotated(new Runnable() { // WARN9, WARN10 current
                                              ^
src/test/pkg/Test.java:75: Error: Call must be from @UiThread, but context is allowing @{Slow,WorkerThread} [WrongThread]
        uiMethod(); // WARN12
        ~~~~~~~~~~
src/test/pkg/Test.java:79: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLater is expected to run from @UiThread [WrongThread]
            slowMethod(); // WARN13
            ~~~~~~~~~~~~
src/test/pkg/Test.java:81: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLater is expected to run from @UiThread [WrongThread]
            workerMethod(); // WARN14
            ~~~~~~~~~~~~~~
src/test/pkg/Test.java:86: Error: Argument at x₀ must allow calling run() from @UiThread, but that call is requiring @{Slow,WorkerThread}. [WrongThread]
        new Application().invokeLater(this::slowMethod); // WARN15
                          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
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
src/test/pkg/Test.java:117: Error: Call must be from @{Slow,WorkerThread}, but a super method is allowing @UiThread [WrongThread]
        new Application().runWriteAction(this::slowMethod); // WARN24
                                         ~~~~~~~~~~~~~~~~
src/test/pkg/Test.java:118: Error: Call must be from @{Slow,WorkerThread}, but a super method is allowing @UiThread [WrongThread]
        new Application().runWriteAction(this::workerMethod); // WARN25
                                         ~~~~~~~~~~~~~~~~~~
23 errors, 2 warnings
                """
      )
  }

  private fun TestLintTask.setUp() = issues(IntellijInferredThreadDetector.THREAD, IntellijInferredThreadDetector.UNSATISFIABLE_CONSTRAINT)

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
  fun testEdtAndReadActionAssumptions() {
    studioLint()
      .setUp()
      .files(
        kt(
            """
                    package test.pkg
                    import com.android.annotations.concurrency.Slow
                    import com.android.annotations.concurrency.UiThread
                    import com.intellij.openapi.application.Application
                    import com.intellij.openapi.application.ReadAction
                    import com.intellij.openapi.application.WriteAction
                    import com.intellij.openapi.project.DumbService
                    import com.intellij.util.ui.UIUtil
                    import javax.swing.SwingUtilities

                    @Slow fun slow() { }

                    @UiThread fun ui() { }

                    @Slow
                    fun fromBackground(app: Application, dumb: DumbService) {
                        app.invokeAndWait { ui() } // OK: safe from any thread, and the runnable runs on the EDT
                        app.invokeAndWait { slow() } // ERROR
                        SwingUtilities.invokeLater { slow() } // ERROR
                        UIUtil.invokeLaterIfNeeded { slow() } // ERROR
                        dumb.smartInvokeLater { slow() } // ERROR
                        WriteAction.runAndWait<RuntimeException> { slow() } // ERROR
                        app.runReadAction { slow() } // OK: runs synchronously on this (background) thread
                    }

                    @UiThread
                    fun fromUi(app: Application) {
                        app.runReadAction { slow() } // ERROR: runs synchronously on the UI thread
                        val n = app.runReadAction<Int> { slow(); 42 } // ERROR: likewise for the Computable overloading
                        ReadAction.run<RuntimeException> { slow() } // ERROR
                    }
                """
          )
          .indented(),
        java(
            """
                    // Stub until test infrastructure passes the right class path for non-Android
                    // modules.
                    package com.intellij.openapi.application;
                    import com.intellij.openapi.util.Computable;

                    @SuppressWarnings("ALL")
                    public class Application {
                        public void invokeAndWait(Runnable runnable) { }

                        public void runReadAction(Runnable action) { }

                        public <T> T runReadAction(Computable<T> computation) { return null; }
                    }
                """
          )
          .indented(),
        java(
            """
                    package com.intellij.openapi.application;
                    import com.intellij.util.ThrowableRunnable;

                    @SuppressWarnings("ALL")
                    public final class ReadAction {
                        public static <E extends Throwable> void run(ThrowableRunnable<E> action) throws E { }
                    }
                """
          )
          .indented(),
        java(
            """
                    package com.intellij.openapi.application;
                    import com.intellij.util.ThrowableRunnable;

                    @SuppressWarnings("ALL")
                    public final class WriteAction {
                        public static <E extends Throwable> void runAndWait(ThrowableRunnable<E> action) throws E { }
                    }
                """
          )
          .indented(),
        java(
            """
                    package com.intellij.openapi.util;

                    public interface Computable<T> {
                        T compute();
                    }
                """
          )
          .indented(),
        java(
            """
                    package com.intellij.util;

                    public interface ThrowableRunnable<T extends Throwable> {
                        void run() throws T;
                    }
                """
          )
          .indented(),
        java(
            """
                    package com.intellij.openapi.project;

                    @SuppressWarnings("ALL")
                    public abstract class DumbService {
                        public void smartInvokeLater(Runnable runnable) { }
                    }
                """
          )
          .indented(),
        java(
            """
                    package com.intellij.util.ui;

                    @SuppressWarnings("ALL")
                    public final class UIUtil {
                        public static void invokeLaterIfNeeded(Runnable runnable) { }
                    }
                """
          )
          .indented(),
        *annotationDefinitions,
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:18: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeAndWait is expected to run from @UiThread [WrongThread]
            app.invokeAndWait { slow() } // ERROR
                                ~~~~~~
        src/test/pkg/test.kt:19: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLater is expected to run from @UiThread [WrongThread]
            SwingUtilities.invokeLater { slow() } // ERROR
                                         ~~~~~~
        src/test/pkg/test.kt:20: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLaterIfNeeded is expected to run from @UiThread [WrongThread]
            UIUtil.invokeLaterIfNeeded { slow() } // ERROR
                                         ~~~~~~
        src/test/pkg/test.kt:21: Error: Call must be from @{Slow,WorkerThread}, but the work passed to smartInvokeLater is expected to run from @UiThread [WrongThread]
            dumb.smartInvokeLater { slow() } // ERROR
                                    ~~~~~~
        src/test/pkg/test.kt:22: Error: Call must be from @{Slow,WorkerThread}, but the work passed to runAndWait is expected to run from @UiThread [WrongThread]
            WriteAction.runAndWait<RuntimeException> { slow() } // ERROR
                                                       ~~~~~~
        src/test/pkg/test.kt:28: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
            app.runReadAction { slow() } // ERROR: runs synchronously on the UI thread
                ~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:29: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
            val n = app.runReadAction<Int> { slow(); 42 } // ERROR: likewise for the Computable overloading
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:30: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
            ReadAction.run<RuntimeException> { slow() } // ERROR
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        8 errors
        """
          .trimIndent()
      )
  }

  @Test
  fun testConcurrencyAssumptions() {
    studioLint()
      .setUp()
      .files(
        kt(
            """
                    package test.pkg
                    import com.android.annotations.concurrency.Slow
                    import com.android.annotations.concurrency.UiThread
                    import java.util.concurrent.CompletableFuture
                    import kotlin.concurrent.thread

                    @Slow fun slow() { }

                    @UiThread fun ui() { }

                    @UiThread
                    fun schedule() {
                        thread { slow() } // OK: the block runs on a fresh background thread
                        thread { ui() } // ERROR
                        CompletableFuture.runAsync { slow() } // OK
                        CompletableFuture.runAsync { ui() } // ERROR
                    }
                """
          )
          .indented(),
        *annotationDefinitions,
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:14: Error: Call must be from @UiThread, but the work passed to thread is expected to run from @{Slow,WorkerThread} [WrongThread]
            thread { ui() } // ERROR
                     ~~~~
        src/test/pkg/test.kt:16: Error: Call must be from @UiThread, but the work passed to runAsync is expected to run from @{Slow,WorkerThread} [WrongThread]
            CompletableFuture.runAsync { ui() } // ERROR
                                         ~~~~
        2 errors
        """
          .trimIndent()
      )
  }

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
        src/test/pkg/Test.java:12: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLater is expected to run from @UiThread [WrongThread]
                app.invokeLater(() -> slow());
                                      ~~~~~~
        src/test/pkg/Test.java:17: Error: Call must be from @{Slow,WorkerThread}, but context is allowing @UiThread [WrongThread]
                s.forEach((x) -> slow());
                  ~~~~~~~~~~~~~~~~~~~~~~
        2 errors
        """
          .trimIndent()
      )
  }

  @Test
  fun `test assumed higher-order function`() {
    studioLint()
      .setUp()
      .files(
        java(
            """
                    package test.pkg;
                    import com.android.annotations.concurrency.Slow;
                    import com.android.annotations.concurrency.UiThread;
                    import com.intellij.openapi.application.Application;

                    public class Test {
                        @Slow static void slow() { }
                        @UiThread static void ui() { }

                        static void mixedBody(Application app) {
                            app.invokeLater(() -> {
                                slow(); // WARN: pinpointed against the lambda's @UiThread requirement
                                ui(); // OK
                            });
                        }

                        static void satisfiableBody(Application app) {
                            app.invokeLater(() -> ui()); // OK
                        }

                        static void nonLambdaArguments(Application app) {
                            app.invokeLater(Test::slow); // WARN: whole-argument report
                            app.invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    slow(); // WARN: pinpointed like in a lambda body
                                }
                            });
                        }

                        static void pooled(Application app) {
                            app.executeOnPooledThread(() -> ui()); // WARN: pinpointed
                            app.executeOnPooledThread(() -> slow()); // OK
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
                public void executeOnPooledThread(Runnable run) { }
            }
          """
          )
          .indented(),
        *annotationDefinitions,
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.java:12: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLater is expected to run from @UiThread [WrongThread]
                    slow(); // WARN: pinpointed against the lambda's @UiThread requirement
                    ~~~~~~
        src/test/pkg/Test.java:22: Error: Argument at x₀ must allow calling run() from @UiThread, but that call is requiring @{Slow,WorkerThread}. [WrongThread]
                app.invokeLater(Test::slow); // WARN: whole-argument report
                    ~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/Test.java:26: Error: Call must be from @{Slow,WorkerThread}, but the work passed to invokeLater is expected to run from @UiThread [WrongThread]
                        slow(); // WARN: pinpointed like in a lambda body
                        ~~~~~~
        src/test/pkg/Test.java:32: Error: Call must be from @UiThread, but the work passed to executeOnPooledThread is expected to run from @{Slow,WorkerThread} [WrongThread]
                app.executeOnPooledThread(() -> ui()); // WARN: pinpointed
                                                ~~~~
        4 errors
        """
          .trimIndent()
      )
  }

  @Test
  fun `test assumed higher-order function matches legacy baseline`() {
    // Before the pinpointed reports above, these were reported on the whole call with an `Argument …`
    // message; a baseline recorded back then still silences them.
    studioLint()
      .setUp()
      .files(
        java(
            """
                    package test.pkg;
                    import com.android.annotations.concurrency.Slow;
                    import com.android.annotations.concurrency.UiThread;
                    import com.intellij.openapi.application.Application;

                    public class Test {
                        @Slow static void slow() { }
                        @UiThread static void ui() { }

                        static void lambda(Application app) {
                            app.invokeLater(() -> slow()); // WARN: baselined on the whole call
                        }

                        static void objectLiteral(Application app) {
                            app.invokeLater(new Runnable() { // WARN: baselined on the whole call
                                @Override
                                public void run() {
                                    slow();
                                }
                            });
                        }

                        static void pooled(Application app) {
                            app.executeOnPooledThread(() -> ui()); // WARN: baselined on the whole call
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
                public void executeOnPooledThread(Runnable run) { }
            }
          """
          )
          .indented(),
        *annotationDefinitions,
      )
      .baseline(
        xml(
          "lint-baseline.xml",
          """
          <issues format="5">
              <issue
                  id="WrongThread"
                  message="Argument at `x₀` must allow calling `run()` from @UiThread, but that call is requiring @{Slow,WorkerThread}."
                  errorLine1="        app.invokeLater(() -> slow()); // WARN: baselined on the whole call"
                  errorLine2="            ~~~~~~~~~~~~~~~~~~~~~~~~~">
                  <location
                      file="src/test/pkg/Test.java"
                      line="11"/>
              </issue>
              <issue
                  id="WrongThread"
                  message="Argument at `x₀` must allow calling `run()` from @UiThread, but that call is requiring @{Slow,WorkerThread}."
                  errorLine1="        app.invokeLater(new Runnable() { // WARN: baselined on the whole call"
                  errorLine2="            ^">
                  <location
                      file="src/test/pkg/Test.java"
                      line="15"/>
              </issue>
              <issue
                  id="WrongThread"
                  message="Argument at `x₀` must allow calling `run()` from @{Slow,WorkerThread}, but that call is requiring @UiThread."
                  errorLine1="        app.executeOnPooledThread(() -> ui()); // WARN: baselined on the whole call"
                  errorLine2="            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~">
                  <location
                      file="src/test/pkg/Test.java"
                      line="24"/>
              </issue>
          </issues>
          """,
        )
      )
      .run()
      .expectClean()
  }
}
