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

class SynchronizeOnValueBasedClassDetectorTest : AbstractCheckTest() {
  override fun getDetector() = SynchronizeOnValueBasedClassDetector()

  fun testDocumentationExample() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import java.time.LocalDate

            @JvmInline
            value class UserId(val id: String)

            @JvmInline
            value class IntId(val id: Int)

            class MyService {
              private val validLock = Any()
              private val boolLock = true
              private val intLock: Any = 42
              private val anyInline: Any = IntId(42)

              fun doWork() {}

              fun process(date: LocalDate, userId: UserId, intId: IntId) {
                synchronized(validLock) {
                  // OK
                }
                synchronized(doWork()) {
                  // OK
                }
                synchronized(boolLock) {
                  // Warn
                }
                synchronized(intLock) {
                  // Warn
                }
                synchronized(date) {
                  // Warn
                }
                synchronized(userId) {
                  // Warn
                }
                synchronized(intId) {
                  // Warn
                }
                synchronized(anyInline) {
                  // Warn
                }
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/UserId.kt:26: Warning: Synchronizing on an instance of value-based class java.lang.Boolean is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized(boolLock) {
                         ~~~~~~~~
        src/com/example/app/UserId.kt:29: Warning: Synchronizing on an instance of value-based class java.lang.Integer is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized(intLock) {
                         ~~~~~~~
        src/com/example/app/UserId.kt:32: Warning: Synchronizing on an instance of value-based class java.time.LocalDate is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized(date) {
                         ~~~~
        src/com/example/app/UserId.kt:35: Warning: Synchronizing on an instance of value-based class com.example.app.UserId is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized(userId) {
                         ~~~~~~
        src/com/example/app/UserId.kt:38: Warning: Synchronizing on an instance of value-based class com.example.app.IntId is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized(intId) {
                         ~~~~~
        src/com/example/app/UserId.kt:41: Warning: Synchronizing on an instance of value-based class com.example.app.IntId is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized(anyInline) {
                         ~~~~~~~~~
        0 errors, 6 warnings
        """
      )
  }

  fun testJavaSynchronized() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            @JvmInline
            value class IntId(val id: Int)
            """
          )
          .indented(),
        java(
            """
            package com.example.app;

            import java.time.Duration;
            import java.util.Optional;

            public class JavaService {
              private final Object validLock = new Object();
              private final Boolean boolField = Boolean.TRUE;

              public void execute(Optional<String> opt, Duration duration, IntId boxedId) {
                synchronized (boxedId) {
                  // Warn
                }
                synchronized (validLock) {
                  // OK
                }
                synchronized (boolField) {
                  // Warn
                }
                Object localBoxed = Integer.valueOf(100);
                synchronized (localBoxed) {
                  // Warn
                }
                synchronized (opt) {
                  // Warn
                }
                synchronized (duration) {
                  // Warn
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
        src/com/example/app/JavaService.java:11: Warning: Synchronizing on an instance of value-based class com.example.app.IntId is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized (boxedId) {
                          ~~~~~~~
        src/com/example/app/JavaService.java:17: Warning: Synchronizing on an instance of value-based class java.lang.Boolean is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized (boolField) {
                          ~~~~~~~~~
        src/com/example/app/JavaService.java:21: Warning: Synchronizing on an instance of value-based class java.lang.Integer is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized (localBoxed) {
                          ~~~~~~~~~~
        src/com/example/app/JavaService.java:24: Warning: Synchronizing on an instance of value-based class java.util.Optional is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized (opt) {
                          ~~~
        src/com/example/app/JavaService.java:27: Warning: Synchronizing on an instance of value-based class java.time.Duration is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized (duration) {
                          ~~~~~~~~
        0 errors, 5 warnings
        """
      )
  }

  fun testTargetSdk38Error() {
    lint()
      .files(
        manifest().targetSdk(38),
        kotlin(
            """
            package com.example.app

            class Target38Service {
              fun test() {
                val lock = 123L
                synchronized(lock) {
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
        src/com/example/app/Target38Service.kt:6: Error: Synchronizing on an instance of value-based class java.lang.Long is unsafe and can throw an exception when targeting API 38+ [SynchronizeOnValueBasedClass]
            synchronized(lock) {
                         ~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testSuppressionAliases() {
    lint()
      .files(
        java(
            """
            package com.example.app;

            public class SuppressExample {
              @SuppressWarnings("synchronization")
              public void suppressedByJavacOrIj(Integer lock) {
                synchronized (lock) {
                }
              }

              @SuppressWarnings("ValueClassIdentity")
              public void suppressedByErrorProne(Boolean lock) {
                synchronized (lock) {
                }
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }
}
