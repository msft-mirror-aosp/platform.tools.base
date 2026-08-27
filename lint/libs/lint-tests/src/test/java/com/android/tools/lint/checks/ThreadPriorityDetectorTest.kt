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

import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector

class ThreadPriorityDetectorTest : AbstractCheckTest() {

  override fun getDetector(): Detector = ThreadPriorityDetector()

  // Stub for android.os.Process to make tests independent of SDK stubs
  private val processStub =
    java(
        """
    // HIDE-FROM-DOCUMENTATION
    package android.os;
    public class Process {
        public static final int THREAD_PRIORITY_DEFAULT = 0;
        public static final int THREAD_PRIORITY_LOWEST = 19;
        public static final int THREAD_PRIORITY_BACKGROUND = 10;
        public static final int THREAD_PRIORITY_FOREGROUND = -2;
        public static final int THREAD_PRIORITY_URGENT_DISPLAY = -8;

        public static final void setThreadPriority(int priority) {}
        public static final void setThreadPriority(int tid, int priority) {}
    }
    """
      )
      .indented()

  // Stub for android.os.HandlerThread
  private val handlerThreadStub =
    java(
        """
    // HIDE-FROM-DOCUMENTATION
    package android.os;
    public class HandlerThread extends Thread {
        public HandlerThread(String name) {}
        public HandlerThread(String name, int priority) {}
    }
    """
      )
      .indented()

  fun testDocumentationExample() {
    lint()
      .files(
        processStub,
        java(
            """
          package test.pkg;
          import android.os.Process;
          public class TestClass {
              public void test() {
                  // Bad: passing Process constants to Thread.setPriority
                  Thread.currentThread().setPriority(Process.THREAD_PRIORITY_BACKGROUND); // ERROR
                  Thread.currentThread().setPriority(Process.THREAD_PRIORITY_DEFAULT);    // ERROR

                  // Good: passing Thread constants
                  Thread.currentThread().setPriority(Thread.MAX_PRIORITY);  // OK
                  Thread.currentThread().setPriority(Thread.NORM_PRIORITY); // OK
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:6: Error: Passing android.os.Process priority constants to Thread.setPriority() is invalid [ThreadPriorityConfusion]
                Thread.currentThread().setPriority(Process.THREAD_PRIORITY_BACKGROUND); // ERROR
                                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:7: Error: Passing android.os.Process priority constants to Thread.setPriority() is invalid [ThreadPriorityConfusion]
                Thread.currentThread().setPriority(Process.THREAD_PRIORITY_DEFAULT);    // ERROR
                                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  fun testThreadSetPriorityOutOfBoundsLiterals() {
    lint()
      .files(
        java(
            """
          package test.pkg;
          public class TestClass {
              public void test() {
                  Thread.currentThread().setPriority(0);  // ERROR: too low
                  Thread.currentThread().setPriority(11); // ERROR: too high
                  Thread.currentThread().setPriority(-1); // ERROR: negative

                  Thread.currentThread().setPriority(1);  // OK
                  Thread.currentThread().setPriority(5);  // OK
                  Thread.currentThread().setPriority(10); // OK
              }
          }
          """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:4: Error: Thread priority must be between 1 (Thread.MIN_PRIORITY) and 10 (Thread.MAX_PRIORITY); was 0 [ThreadPriorityConfusion]
                Thread.currentThread().setPriority(0);  // ERROR: too low
                                                   ~
        src/test/pkg/TestClass.java:5: Error: Thread priority must be between 1 (Thread.MIN_PRIORITY) and 10 (Thread.MAX_PRIORITY); was 11 [ThreadPriorityConfusion]
                Thread.currentThread().setPriority(11); // ERROR: too high
                                                   ~~
        src/test/pkg/TestClass.java:6: Error: Thread priority must be between 1 (Thread.MIN_PRIORITY) and 10 (Thread.MAX_PRIORITY); was -1 [ThreadPriorityConfusion]
                Thread.currentThread().setPriority(-1); // ERROR: negative
                                                   ~~
        3 errors, 0 warnings
        """
      )
  }

  fun testProcessSetThreadPriorityConfusedConstants() {
    lint()
      .files(
        processStub,
        java(
            """
          package test.pkg;
          import android.os.Process;
          public class TestClass {
              public void test() {
                  // Bad: passing Thread constants to Process.setThreadPriority
                  Process.setThreadPriority(Thread.MAX_PRIORITY); // ERROR
                  Process.setThreadPriority(123, Thread.MIN_PRIORITY); // ERROR

                  // Good: passing Process constants
                  Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); // OK
                  Process.setThreadPriority(123, Process.THREAD_PRIORITY_URGENT_DISPLAY); // OK
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:6: Error: Passing java.lang.Thread priority constants to Process.setThreadPriority() is invalid [ThreadPriorityConfusion]
                Process.setThreadPriority(Thread.MAX_PRIORITY); // ERROR
                                          ~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:7: Error: Passing java.lang.Thread priority constants to Process.setThreadPriority() is invalid [ThreadPriorityConfusion]
                Process.setThreadPriority(123, Thread.MIN_PRIORITY); // ERROR
                                               ~~~~~~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  fun testProcessSetThreadPriorityOutOfBoundsLiterals() {
    lint()
      .files(
        processStub,
        java(
            """
          package test.pkg;
          import android.os.Process;
          public class TestClass {
              public void test() {
                  Process.setThreadPriority(-21); // ERROR: too low
                  Process.setThreadPriority(20);  // ERROR: too high
                  Process.setThreadPriority(123, -25); // ERROR: too low

                  Process.setThreadPriority(-20); // OK
                  Process.setThreadPriority(0);   // OK
                  Process.setThreadPriority(19);  // OK
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:5: Error: Process thread priority must be between -20 (highest) and 19 (lowest); was -21 [ThreadPriorityConfusion]
                Process.setThreadPriority(-21); // ERROR: too low
                                          ~~~
        src/test/pkg/TestClass.java:6: Error: Process thread priority must be between -20 (highest) and 19 (lowest); was 20 [ThreadPriorityConfusion]
                Process.setThreadPriority(20);  // ERROR: too high
                                          ~~
        src/test/pkg/TestClass.java:7: Error: Process thread priority must be between -20 (highest) and 19 (lowest); was -25 [ThreadPriorityConfusion]
                Process.setThreadPriority(123, -25); // ERROR: too low
                                               ~~~
        3 errors, 0 warnings
        """
      )
  }

  fun testKotlinThreadPriorityConfusion() {
    lint()
      .files(
        processStub,
        kotlin(
            """
          package test.pkg
          import android.os.Process
          class TestKotlin {
              fun test() {
                  Thread.currentThread().priority = Process.THREAD_PRIORITY_BACKGROUND // ERROR
                  Process.setThreadPriority(Thread.MAX_PRIORITY) // ERROR

                  Thread.currentThread().priority = Thread.MAX_PRIORITY // OK
                  Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) // OK
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestKotlin.kt:5: Error: Passing android.os.Process priority constants to Thread.setPriority() is invalid [ThreadPriorityConfusion]
                Thread.currentThread().priority = Process.THREAD_PRIORITY_BACKGROUND // ERROR
                                                  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestKotlin.kt:6: Error: Passing java.lang.Thread priority constants to Process.setThreadPriority() is invalid [ThreadPriorityConfusion]
                Process.setThreadPriority(Thread.MAX_PRIORITY) // ERROR
                                          ~~~~~~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  fun testKotlinLocalVariableConfusion() {
    lint()
      .files(
        processStub,
        kotlin(
            """
          package test.pkg
          import android.os.Process
          class TestKotlin {
              fun test() {
                  val p = Process.THREAD_PRIORITY_BACKGROUND
                  Thread.currentThread().priority = p // ERROR

                  val t = Thread.MAX_PRIORITY
                  Process.setThreadPriority(t) // ERROR
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestKotlin.kt:6: Error: Passing android.os.Process priority constants to Thread.setPriority() is invalid [ThreadPriorityConfusion]
                Thread.currentThread().priority = p // ERROR
                                                  ~
        src/test/pkg/TestKotlin.kt:9: Error: Passing java.lang.Thread priority constants to Process.setThreadPriority() is invalid [ThreadPriorityConfusion]
                Process.setThreadPriority(t) // ERROR
                                          ~
        2 errors, 0 warnings
        """
      )
  }

  fun testKotlinNamedArguments() {
    lint()
      .files(
        processStub,
        kotlin(
            """
          package test.pkg
          import android.os.Process
          class TestKotlin {
              fun test() {
                  // Named arguments with reordering
                  Process.setThreadPriority(priority = Thread.MAX_PRIORITY, tid = 123) // ERROR on priority

                  // Named arguments without reordering
                  Process.setThreadPriority(tid = 123, priority = Thread.MAX_PRIORITY) // ERROR on priority

                  // Good
                  Process.setThreadPriority(priority = Process.THREAD_PRIORITY_BACKGROUND, tid = 123) // OK
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestKotlin.kt:6: Error: Passing java.lang.Thread priority constants to Process.setThreadPriority() is invalid [ThreadPriorityConfusion]
                Process.setThreadPriority(priority = Thread.MAX_PRIORITY, tid = 123) // ERROR on priority
                                                     ~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestKotlin.kt:9: Error: Passing java.lang.Thread priority constants to Process.setThreadPriority() is invalid [ThreadPriorityConfusion]
                Process.setThreadPriority(tid = 123, priority = Thread.MAX_PRIORITY) // ERROR on priority
                                                                ~~~~~~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  fun testTypeCastingHandling() {
    lint()
      .files(
        processStub,
        java(
            """
          package test.pkg;
          import android.os.Process;
          public class TestClass {
              public void test() {
                  // Bad: passing casted Process constant to Thread.setPriority
                  Thread.currentThread().setPriority((int) Process.THREAD_PRIORITY_BACKGROUND); // ERROR

                  // Bad: passing casted Thread constant to Process.setThreadPriority
                  Process.setThreadPriority((int) Thread.MAX_PRIORITY); // ERROR
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:6: Error: Passing android.os.Process priority constants to Thread.setPriority() is invalid [ThreadPriorityConfusion]
                Thread.currentThread().setPriority((int) Process.THREAD_PRIORITY_BACKGROUND); // ERROR
                                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:9: Error: Passing java.lang.Thread priority constants to Process.setThreadPriority() is invalid [ThreadPriorityConfusion]
                Process.setThreadPriority((int) Thread.MAX_PRIORITY); // ERROR
                                          ~~~~~~~~~~~~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  fun testHandlerThreadConstructor() {
    lint()
      .files(
        processStub,
        handlerThreadStub,
        java(
            """
          package test.pkg;
          import android.os.Process;
          import android.os.HandlerThread;
          public class TestClass {
              public void test() {
                  // Bad: passing Thread priority to HandlerThread constructor
                  HandlerThread thread1 = new HandlerThread("name", Thread.MAX_PRIORITY); // ERROR

                  // Good: passing Process priority
                  HandlerThread thread2 = new HandlerThread("name", Process.THREAD_PRIORITY_BACKGROUND); // OK
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:7: Error: Passing java.lang.Thread priority constants to HandlerThread constructor is invalid [ThreadPriorityConfusion]
                HandlerThread thread1 = new HandlerThread("name", Thread.MAX_PRIORITY); // ERROR
                                                                  ~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testKotlinHandlerThreadConstructor() {
    lint()
      .files(
        processStub,
        handlerThreadStub,
        kotlin(
            """
          package test.pkg
          import android.os.Process
          import android.os.HandlerThread
          class TestKotlin {
              fun test() {
                  // Bad: named argument with Thread priority
                  val thread1 = HandlerThread(priority = Thread.MAX_PRIORITY, name = "name") // ERROR

                  // Good
                  val thread2 = HandlerThread(name = "name", priority = Process.THREAD_PRIORITY_BACKGROUND) // OK
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestKotlin.kt:7: Error: Passing java.lang.Thread priority constants to HandlerThread constructor is invalid [ThreadPriorityConfusion]
                val thread1 = HandlerThread(priority = Thread.MAX_PRIORITY, name = "name") // ERROR
                                                       ~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testJavaLocalVariableReassignment() {
    lint()
      .files(
        processStub,
        java(
            """
          package test.pkg;
          import android.os.Process;
          public class TestClass {
              public void test() {
                  int p = Process.THREAD_PRIORITY_BACKGROUND;
                  p = Thread.MAX_PRIORITY; // reassigned to Thread priority
                  Process.setThreadPriority(p); // ERROR: flagged because last assignment was Thread.MAX_PRIORITY
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:7: Error: Passing java.lang.Thread priority constants to Process.setThreadPriority() is invalid [ThreadPriorityConfusion]
                Process.setThreadPriority(p); // ERROR: flagged because last assignment was Thread.MAX_PRIORITY
                                          ~
        1 errors, 0 warnings
        """
      )
  }

  fun testHandlerThreadPriorityFieldAccessJava() {
    lint()
      .files(
        processStub,
        handlerThreadStub,
        java(
            """
          package test.pkg;
          import android.os.Process;
          import android.os.HandlerThread;
          public class TestClass {
              public static void createAndStartNewLooperExecutor(String name, int priority) {}

              public void test() {
                  // Bad: referencing HandlerThread.MIN_PRIORITY / NORM_PRIORITY / MAX_PRIORITY
                  createAndStartNewLooperExecutor("NoOpViewCapture", HandlerThread.MIN_PRIORITY); // ERROR
                  HandlerThread t1 = new HandlerThread("name", HandlerThread.MIN_PRIORITY); // ERROR
                  Process.setThreadPriority(HandlerThread.MIN_PRIORITY); // ERROR
                  Thread.currentThread().setPriority(HandlerThread.MIN_PRIORITY); // ERROR
                  int p = HandlerThread.MAX_PRIORITY; // ERROR
                  int p2 = HandlerThread.NORM_PRIORITY; // ERROR

                  // Good: using Process priorities for HandlerThread/Process and Thread priorities for Thread
                  createAndStartNewLooperExecutor("NoOpViewCapture", Process.THREAD_PRIORITY_LOWEST); // OK
                  HandlerThread t2 = new HandlerThread("name", Process.THREAD_PRIORITY_BACKGROUND); // OK
                  Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); // OK
                  Thread.currentThread().setPriority(Thread.MIN_PRIORITY); // OK
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:9: Error: Do not use HandlerThread.MIN_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MIN_PRIORITY (1) from java.lang.Thread [ThreadPriorityConfusion]
                createAndStartNewLooperExecutor("NoOpViewCapture", HandlerThread.MIN_PRIORITY); // ERROR
                                                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:10: Error: Do not use HandlerThread.MIN_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MIN_PRIORITY (1) from java.lang.Thread [ThreadPriorityConfusion]
                HandlerThread t1 = new HandlerThread("name", HandlerThread.MIN_PRIORITY); // ERROR
                                                             ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:11: Error: Do not use HandlerThread.MIN_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MIN_PRIORITY (1) from java.lang.Thread [ThreadPriorityConfusion]
                Process.setThreadPriority(HandlerThread.MIN_PRIORITY); // ERROR
                                          ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:12: Error: Do not use HandlerThread.MIN_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MIN_PRIORITY (1) from java.lang.Thread [ThreadPriorityConfusion]
                Thread.currentThread().setPriority(HandlerThread.MIN_PRIORITY); // ERROR
                                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:13: Error: Do not use HandlerThread.MAX_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MAX_PRIORITY (10) from java.lang.Thread [ThreadPriorityConfusion]
                int p = HandlerThread.MAX_PRIORITY; // ERROR
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:14: Error: Do not use HandlerThread.NORM_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits NORM_PRIORITY (5) from java.lang.Thread [ThreadPriorityConfusion]
                int p2 = HandlerThread.NORM_PRIORITY; // ERROR
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        6 errors, 0 warnings
        """
      )
  }

  fun testHandlerThreadPriorityFieldAccessKotlin() {
    lint()
      .files(
        processStub,
        handlerThreadStub,
        kotlin(
            """
          package test.pkg
          import android.os.Process
          import android.os.HandlerThread

          class TestKotlin {
              fun createAndStartNewLooperExecutor(name: String, priority: Int) {}

              fun test() {
                  // Bad: referencing HandlerThread.MIN_PRIORITY in helper method (SystemUI pattern)
                  createAndStartNewLooperExecutor("NoOpViewCapture", HandlerThread.MIN_PRIORITY) // ERROR
                  val thread1 = HandlerThread("name", HandlerThread.MIN_PRIORITY) // ERROR
                  Thread.currentThread().priority = HandlerThread.MIN_PRIORITY // ERROR
                  Process.setThreadPriority(HandlerThread.MAX_PRIORITY) // ERROR
                  val p = HandlerThread.NORM_PRIORITY // ERROR

                  // Good
                  createAndStartNewLooperExecutor("NoOpViewCapture", Process.THREAD_PRIORITY_LOWEST) // OK
                  val thread2 = HandlerThread("name", Process.THREAD_PRIORITY_BACKGROUND) // OK
                  Thread.currentThread().priority = Thread.MIN_PRIORITY // OK
                  Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) // OK
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestKotlin.kt:10: Error: Do not use HandlerThread.MIN_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MIN_PRIORITY (1) from java.lang.Thread [ThreadPriorityConfusion]
                createAndStartNewLooperExecutor("NoOpViewCapture", HandlerThread.MIN_PRIORITY) // ERROR
                                                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestKotlin.kt:11: Error: Do not use HandlerThread.MIN_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MIN_PRIORITY (1) from java.lang.Thread [ThreadPriorityConfusion]
                val thread1 = HandlerThread("name", HandlerThread.MIN_PRIORITY) // ERROR
                                                    ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestKotlin.kt:12: Error: Do not use HandlerThread.MIN_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MIN_PRIORITY (1) from java.lang.Thread [ThreadPriorityConfusion]
                Thread.currentThread().priority = HandlerThread.MIN_PRIORITY // ERROR
                                                  ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestKotlin.kt:13: Error: Do not use HandlerThread.MAX_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MAX_PRIORITY (10) from java.lang.Thread [ThreadPriorityConfusion]
                Process.setThreadPriority(HandlerThread.MAX_PRIORITY) // ERROR
                                          ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestKotlin.kt:14: Error: Do not use HandlerThread.NORM_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits NORM_PRIORITY (5) from java.lang.Thread [ThreadPriorityConfusion]
                val p = HandlerThread.NORM_PRIORITY // ERROR
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        5 errors, 0 warnings
        """
      )
  }

  fun testHandlerThreadSubclass() {
    lint()
      .files(
        processStub,
        handlerThreadStub,
        java(
            """
          package test.pkg;
          import android.os.HandlerThread;
          import android.os.Process;
          public class TestClass {
              public static class BluetoothScanThread extends HandlerThread {
                  public BluetoothScanThread() {
                      super("BluetoothScanThread", HandlerThread.MIN_PRIORITY); // ERROR
                  }
              }

              public static class CustomThread extends HandlerThread {
                  public CustomThread() {
                      super("CustomThread", Thread.MIN_PRIORITY); // ERROR
                  }

                  public void test() {
                      int p = CustomThread.MIN_PRIORITY; // ERROR
                  }
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:7: Error: Do not use HandlerThread.MIN_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MIN_PRIORITY (1) from java.lang.Thread [ThreadPriorityConfusion]
                    super("BluetoothScanThread", HandlerThread.MIN_PRIORITY); // ERROR
                                                 ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:13: Error: Passing java.lang.Thread priority constants to HandlerThread constructor is invalid [ThreadPriorityConfusion]
                    super("CustomThread", Thread.MIN_PRIORITY); // ERROR
                                          ~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:17: Error: Do not use CustomThread.MIN_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MIN_PRIORITY (1) from java.lang.Thread [ThreadPriorityConfusion]
                    int p = CustomThread.MIN_PRIORITY; // ERROR
                            ~~~~~~~~~~~~~~~~~~~~~~~~~
        3 errors, 0 warnings
        """
      )
  }

  fun testHandlerThreadSubclassMethodCalls() {
    lint()
      .files(
        processStub,
        handlerThreadStub,
        java(
            """
          package test.pkg;
          import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
          import android.os.HandlerThread;
          import android.os.Process;

          public class TestClass {
              public static class CustomThread extends HandlerThread {
                  public CustomThread() {
                      super("CustomThread");
                  }

                  public void test() {
                      setPriority(THREAD_PRIORITY_BACKGROUND); // ERROR
                      int p = Process.THREAD_PRIORITY_BACKGROUND;
                      setPriority(p); // ERROR
                      int q = 50;
                      setPriority(q); // ERROR
                      int r = Thread.MAX_PRIORITY;
                      Process.setThreadPriority(r); // ERROR
                  }
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:13: Error: Passing android.os.Process priority constants to Thread.setPriority() is invalid [ThreadPriorityConfusion]
                    setPriority(THREAD_PRIORITY_BACKGROUND); // ERROR
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/TestClass.java:15: Error: Passing android.os.Process priority constants to Thread.setPriority() is invalid [ThreadPriorityConfusion]
                    setPriority(p); // ERROR
                                ~
        src/test/pkg/TestClass.java:17: Error: Thread priority must be between 1 (Thread.MIN_PRIORITY) and 10 (Thread.MAX_PRIORITY); was 50 [ThreadPriorityConfusion]
                    setPriority(q); // ERROR
                                ~
        src/test/pkg/TestClass.java:19: Error: Passing java.lang.Thread priority constants to Process.setThreadPriority() is invalid [ThreadPriorityConfusion]
                    Process.setThreadPriority(r); // ERROR
                                              ~
        4 errors, 0 warnings
        """
      )
  }

  fun testHandlerThreadStaticImport() {
    lint()
      .skipTestModes(TestMode.FULLY_QUALIFIED)
      .files(
        handlerThreadStub,
        java(
            """
          package test.pkg;
          import static android.os.HandlerThread.MIN_PRIORITY;
          public class TestClass {
              public void test() {
                  int p = MIN_PRIORITY; // ERROR
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/test/pkg/TestClass.java:5: Error: Do not use HandlerThread.MIN_PRIORITY; HandlerThread uses android.os.Process thread priorities (such as Process.THREAD_PRIORITY_DEFAULT), but inherits MIN_PRIORITY (1) from java.lang.Thread [ThreadPriorityConfusion]
                int p = MIN_PRIORITY; // ERROR
                        ~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }
}
