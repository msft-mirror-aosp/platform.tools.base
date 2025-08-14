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
package com.android.tools.lint.checks

import com.android.tools.lint.checks.InferredThreadDetector.Thread
import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.useFirUast

@Suppress("LintDocExample")
class InferredThreadDetectorTest : AbstractCheckTest() {
  override fun getDetector() = InferredThreadDetector()

  override fun lint(): TestLintTask {
    val task = super.lint()
    // This detector isn't intended to be run on the platform
    task.skipTestModes(PLATFORM_ANNOTATIONS_TEST_MODE)

    task.skipTestModes(TestMode.PARENTHESIZED, TestMode.FULLY_QUALIFIED, TestMode.REORDER_ARGUMENTS)

    task.skipTestModes(TestMode.JVM_OVERLOADS) // TODO

    // `TYPE_ALIAS` generates nonsense code, declaring top-level alias to local type
    task.skipTestModes(TestMode.TYPE_ALIAS)
    return task
  }

  fun testUnsignedLiteral() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread

          @UiThread fun f(): UInt = 42

          @UiThread fun g() = 45UL

          @AnyThread fun h() {
              f()
              g()
          }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/test.kt:10: Error: Call must be from @{Main,Ui}Thread, but context is allowing @AnyThread [ThreadConstraint]
              f()
              ~~~
          src/test/pkg/test.kt:11: Error: Call must be from @{Main,Ui}Thread, but context is allowing @AnyThread [ThreadConstraint]
              g()
              ~~~
          2 errors
        """
          .trimIndent()
      )
  }

  fun testInferredAny_376518592() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread

          @UiThread fun ui() { }

          fun ignoreIt(f: () -> Unit) { }

          @AnyThread fun g() = ignoreIt(::ui)
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testInferredPolymorphic_376518592() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread

          @UiThread fun ui() { }
          @WorkerThread fun worker() { }
          @AnyThread fun any() { }

          fun runIt(f: () -> Unit) = f()
          @UiThread fun runItOnUi(f: () -> Unit) = runIt(f)

          fun runUiOnUi() = runItOnUi(::ui) // OK
          fun runAnyOnUi() = runItOnUi(::any) // OK
          fun runWorkerOnUi() = runItOnUi(::worker) // ERROR

          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/test.kt:15: Error: Argument must run from @{Main,Ui}Thread, but is requiring @WorkerThread [ThreadConstraint]
          fun runWorkerOnUi() = runItOnUi(::worker) // ERROR
                                          ~~~~~~~~
          1 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testUnsatisfiableStatementSequence() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread

          @UiThread fun ui() { }
          @WorkerThread fun worker() { }
          @AnyThread fun any() { }

          fun sat() {
              ui()
              any()
          }

          fun unsat() {
              ui()
              worker() // ERROR
          }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/test.kt:17: Error: Statement must run from @WorkerThread, incompatible with earlier code that must run from @{Main,Ui}Thread [ThreadConstraint]
              worker() // ERROR
              ~~~~~~~~
          1 error
        """
          .trimIndent()
      )
  }

  fun testUnsatisfiableInstantiation() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread

          @UiThread fun ui() { }
          @WorkerThread fun worker() { }
          @AnyThread fun any() { }

          fun doBoth(fst: () -> Unit, snd: () -> Unit) { fst(); snd() }

          fun sat() = doBoth(::ui, ::any)

          fun unsat() = doBoth(::ui, ::worker) // ERROR
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/test.kt:14: Error: Call results in an unsatisfiable thread requirement [ThreadConstraint]
          fun unsat() = doBoth(::ui, ::worker) // ERROR
                        ~~~~~~~~~~~~~~~~~~~~~~
          1 error
        """
          .trimIndent()
      )
  }

  fun testSimpleEta() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread

          @UiThread fun ui() { }
          @WorkerThread fun worker() { }

          fun wrap(f: () -> Unit): () -> Unit = { f() }

          @WorkerThread fun wrapUi() = wrap(::ui) // OK
          @UiThread fun wrapWorker() = wrap(::worker) // OK

          @WorkerThread fun runWrappedUi0() = wrapUi()() // ERROR
          @WorkerThread fun runWrappedUi1() = wrapUi().invoke() // ERROR
          @WorkerThread fun runWrappedUi2() = wrap(::ui)() // ERROR
          @WorkerThread fun runWrappedUi3() = wrap(::ui).invoke() // ERROR
          @WorkerThread fun runWrappedWorker0() = wrap(::worker)()
          @WorkerThread fun runWrappedWorker1() = wrap(::worker).invoke()
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/test.kt:14: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
          @WorkerThread fun runWrappedUi0() = wrapUi()() // ERROR
                                              ~~~~~~~~~~
          src/test/pkg/test.kt:15: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
          @WorkerThread fun runWrappedUi1() = wrapUi().invoke() // ERROR
                                                       ~~~~~~~~
          src/test/pkg/test.kt:16: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
          @WorkerThread fun runWrappedUi2() = wrap(::ui)() // ERROR
                                              ~~~~~~~~~~~~
          src/test/pkg/test.kt:17: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
          @WorkerThread fun runWrappedUi3() = wrap(::ui).invoke() // ERROR
                                                         ~~~~~~~~
          4 errors
        """
          .trimIndent()
      )
  }

  fun testInheritedBasicPathSensitivity() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread

          abstract class Cat { abstract fun meow() }
          interface Dog { fun woof() }

          fun Any.speak() = when (this) {
              is Cat -> this.meow()
              is Dog -> this.woof()
              else -> { }
          }

          object Doggo: Dog { @UiThread override fun woof() { } }
          object Catto: Cat { @WorkerThread override fun meow() { } }

          @UiThread fun doggoSpeak() = Doggo.speak() // OK
          @UiThread fun cattoSpeak() = Catto.speak() // ERROR
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/Cat.kt:19: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
          @UiThread fun cattoSpeak() = Catto.speak() // ERROR
                                             ~~~~~~~
          1 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testIncompatibleInheritance_361870417() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread

          interface Intf {
              @AnyThread fun f()
          }

          class Impl: Intf {
              @WorkerThread override fun f() { } // ERROR
          }

          @UiThread
          fun updateUi(impl: Impl) {
              impl.f()           // correctly fails
              (impl as Intf).f() // would-be problematic at run time, not caught here, trusting upcasting, assuming problem caught at overriding
          }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/Intf.kt:12: Error: @WorkerThread restricts @AnyThread (from super method Intf.f(…)) [ThreadConstraint]
              @WorkerThread override fun f() { } // ERROR
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          src/test/pkg/Intf.kt:17: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
              impl.f()           // correctly fails
                   ~~~
          2 errors
        """
          .trimIndent()
      )
  }

  fun testCompatibleMultipleInheritance_361870417() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.BinderThread

          interface Intf1 {
              @WorkerThread fun f()
          }

          interface Intf2 {
              @UiThread fun f()
          }

          interface Intf3 {
              @UiThread fun f()
          }

          class GoodImpl: Intf1, Intf2 {
              @UiThread @WorkerThread override fun f() { }
          }

          class AlsoGoodImpl: Intf1, Intf2 {
              @AnyThread override fun() { }
          }

          class BadImpl: Inttf1, Intf2 {
              @WorkerThread override fun f() { }
          }

          @UiThread fun ui() { }
          class AlsoBadImpl: Intf1, Intf2 {
              override fun f() { ui() }
          }

          class RealBadImpl: Intf1, Intf2, Intf3 {
              @BinderThread override fun f() { }
          }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/Intf1.kt:29: Error: @WorkerThread restricts @{Main,Ui}Thread (from super method Intf2.f(…)) [ThreadConstraint]
              @WorkerThread override fun f() { }
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          src/test/pkg/Intf1.kt:34: Error: Call must be from @{Main,Ui}Thread, but super method Intf1.f(…) is allowing @WorkerThread [ThreadConstraint]
              override fun f() { ui() }
                                 ~~~~
          src/test/pkg/Intf1.kt:38: Error: @BinderThread restricts @WorkerThread (from super method Intf1.f(…)), and @{Main,Ui}Thread (from super method Intf2.f(…), and super method Intf3.f(…)) [ThreadConstraint]
              @BinderThread override fun f() { }
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          3 errors
        """
          .trimIndent()
      )
  }

  fun testRespectingParameterAnnotation_384511995() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread

          @UiThread fun runWorkerFromUi(@WorkerThread f: () -> Unit) = f() // ERROR

          @WorkerThread fun runWorkerFromWorker(@WorkerThread f: () -> Unit) = f() // OK

          @AnyThread fun runWorkerFromAny(@WorkerThread f: () -> Unit) = f() // ERROR

          @UiThread fun runAnyFromUi(@AnyThread f: () -> Unit) = f() // OK

          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/test.kt:7: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
          @UiThread fun runWorkerFromUi(@WorkerThread f: () -> Unit) = f() // ERROR
                                                                       ~~~
          src/test/pkg/test.kt:11: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
          @AnyThread fun runWorkerFromAny(@WorkerThread f: () -> Unit) = f() // ERROR
                                                                         ~~~
          2 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testInferredFromCallingAnnotatedParameter_384511995() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread

          fun runUiWork(@UiThread f: () -> Unit) = f()

          fun runEasyWork(@AnyThread f: Runnable) = f.run()

          @UiThread fun runUiFromUi() = runUiWork { } // OK

          @AnyThread fun runUiFromAny() = runUiWork { } // ERROR

          @WorkerThread fun runAnyFromWorker() = runEasyWork { } // OK

          @AnyThread fun runAnyFromAny() = runEasyWork { } // OK
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/test.kt:13: Error: Call must be from @{Main,Ui}Thread, but context is allowing @AnyThread [ThreadConstraint]
          @AnyThread fun runUiFromAny() = runUiWork { } // ERROR
                                          ~~~~~~~~~~~~~
          1 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testMultipleAnnotations_361843462() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread

          interface Intf {
              @AnyThread
              fun f() {}
          }

          @WorkerThread
          object Impl: Intf {
              override fun f() {} // ERROR not subsuming `Intf`'s annotation
          }

          @UiThread
          fun main(impl: Impl) = impl.f() // ERROR inconsistency with `Impl`'s annotation
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/Intf.kt:14: Error: @WorkerThread restricts @AnyThread (from super method Intf.f(…)) [ThreadConstraint]
              override fun f() {} // ERROR not subsuming `Intf`'s annotation
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          src/test/pkg/Intf.kt:18: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
          fun main(impl: Impl) = impl.f() // ERROR inconsistency with `Impl`'s annotation
                                      ~~~
          2 errors
        """
          .trimIndent()
      )
  }

  fun testMultipleAnnotationsFixed_361843462() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread

          interface Intf {
              @AnyThread
              fun f() {}
          }

          @WorkerThread
          object Impl: Intf {
              @AnyThread override fun f() {} // OK
          }

          @UiThread
          fun main(impl: Impl) = impl.f() // OK
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testEarlyReturn() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.BinderThread

          fun earlyReturnId(value: () -> Unit): () -> Unit {
              return value
              42
          }

          @UiThread fun ui() { }

          @WorkerThread fun worker() { earlyReturnId(::ui).invoke() }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/test.kt:15: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
          @WorkerThread fun worker() { earlyReturnId(::ui).invoke() }
                                                           ~~~~~~~~
          1 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testGenericId() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.BinderThread

          fun<X> id(value: X) = value

          @UiThread fun ui() { }

          @WorkerThread fun worker() {
             id(::ui).invoke()
             id(::ui)()
          }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/test.kt:13: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
             id(::ui).invoke()
                      ~~~~~~~~
          src/test/pkg/test.kt:14: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
             id(::ui)()
             ~~~~~~~~~~
          2 errors
        """
          .trimIndent()
      )
  }

  // TODO it doesn't know what `x(x)` is
  fun ignore_testOmega() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.BinderThread

          object SelfApp: (SelfApp) -> SelfApp {
              override fun invoke(x: SelfApp) = x(x)
          }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  // TODO (b/390023468)
  fun ignore_testOmega_open() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.BinderThread

          interface SelfApp: (SelfApp) -> SelfApp

          object UiOmega: SelfApp {
              @UiThread override fun invoke(x: SelfApp) = x(x)
          }

          object WorkerOmega: SelfApp {
              @WorkerThread override fun invoke(x: SelfApp) = x(x)
          }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectWarningCount(2)
  }

  fun testRecursion_Precise() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.BinderThread

          interface Tick { fun tick(): Tock }
          interface Tock { fun tock(): Tick }

          fun onTick(b: Boolean, i: Tick): Tick = if (b) i else onTock(b, i.tick()).tock()
          fun onTock(b: Boolean, o: Tock): Tock = if (b) o else onTick(b, o.tock()).tick()

          object TickImpl1: Tick { @UiThread @WorkerThread override fun tick() = TockImpl1 }
          object TockImpl1: Tock { @UiThread @BinderThread override fun tock() = TickImpl2 }
          object TickImpl2: Tick {                         override fun tick() = TockImpl2 }
          object TockImpl2: Tock {                         override fun tock() = TickImpl1 }

          @UiThread
          fun main(b: Boolean) = onTick(b, TickImpl1)
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testRecursion_Sound_1() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.BinderThread

          interface Tick { fun tick(): Tock }
          interface Tock { fun tock(): Tick }

          fun onTick(b: Boolean, i: Tick): Tick = if (b) i else onTock(b, i.tick()).tock()
          fun onTock(b: Boolean, o: Tock): Tock = if (b) o else onTick(b, o.tock()).tick()

          object TickImpl1: Tick { @UiThread @WorkerThread override fun tick() = TockImpl1 }
          object TockImpl1: Tock { @UiThread @BinderThread override fun tock() = TickImpl2 }
          object TickImpl2: Tick {                         override fun tick() = TockImpl2 }
          object TockImpl2: Tock {                         override fun tock() = TickImpl1 }

          @WorkerThread
          fun main(b: Boolean) = onTick(b, TickImpl1)
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/Tick.kt:20: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
          fun main(b: Boolean) = onTick(b, TickImpl1)
                                 ~~~~~~~~~~~~~~~~~~~~
          1 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testRecursion_Sound_2() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.MainThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.BinderThread

          interface Tick { fun tick(): Tock }
          interface Tock { fun tock(): Tick }

          fun onTick(b: Boolean, i: Tick): Tick = if (b) i else onTock(b, i.tick()).tock()
          fun onTock(b: Boolean, o: Tock): Tock = if (b) o else onTick(b, o.tock()).tick()

          object TickImpl1: Tick { @UiThread @WorkerThread override fun tick() = TockImpl1 }
          object TockImpl1: Tock { @UiThread @BinderThread override fun tock() = TickImpl2 }
          object TickImpl2: Tick {                         override fun tick() = TockImpl2 }
          object TockImpl2: Tock {                         override fun tock() = TickImpl1 }

          @BinderThread
          fun main(b: Boolean) = onTick(b, TickImpl1)
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/Tick.kt:20: Error: Call must be from @{Main,Ui}Thread, but context is allowing @BinderThread [ThreadConstraint]
          fun main(b: Boolean) = onTick(b, TickImpl1)
                                 ~~~~~~~~~~~~~~~~~~~~
          1 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testRecursion_accum() {
    lint()
      .files(
        java(
            """
          package test.pkg;

          public class Test {
              interface Thing {
                  Thing nextThing();
              }

              private boolean helper(Thing thing) {
                  if (new Random().nextBoolean()) {
                      return helper(thing.nextThing());
                  } else {
                      return false;
                  }
              }
          }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testRecursion_accum_virtual() {
    lint()
      .files(
        java(
            """
          package test.pkg;

          public class Test {
              interface Thing {
                  Thing nextThing();
              }

              boolean helper(Thing thing) {
                  if (new Random().nextBoolean()) {
                      return helper(thing.nextThing());
                  } else {
                      return false;
                  }
              }
          }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testPolymorphicRecursion() {
    lint()
      .files(
        kotlin(
            """
          package test.pkg
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread

          sealed interface LList<out T> {
            object Empty: LList<Nothing>
            class Cons<out T>(val first: T, val rest: LList<T>): LList<T>
          }

          fun<S, T> LList<S>.map(f: (S) -> T): LList<T> =
            when (this) {
              is LList.Empty -> LList.Empty
              is LList.Cons -> LList.Cons(f(first), rest.map(f))
            }

          sealed interface Nested<out T> {
            object Empty: Nested<Nothing>
            class Cons<out T>(val first: T, val rest: Nested<LList<T>>): Nested<T>
          }

          fun<S, T> Nested<S>.map(f: (S) -> T): Nested<T> =
            when (this) {
              is Nested.Empty -> Nested.Empty
              is Nested.Cons -> Nested.Cons(f(first), rest.map { it.map(f) })
            }

          @UiThread fun ui(n: Int): String = n.toString()

          @AnyThread fun f(c: LList<Int>) = c.map(::ui)
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/LList.kt:29: Error: Call must be from @{Main,Ui}Thread, but context is allowing @AnyThread [ThreadConstraint]
          @AnyThread fun f(c: LList<Int>) = c.map(::ui)
                                              ~~~~~~~~~
          1 error
        """
          .trimIndent()
      )
  }

  fun testOpenRecursiveCallback() {
    lint()
      .files(
        kotlin(
            """
          // Example from minimizing
          // androidx.compose.foundation.lazy.layout.CacheWindowLogic.scheduleNextItemIfNeeded

          interface Scope {
            fun schedule(work: (Int) -> Unit)
          }

          fun Scope.g(n: Int) {
            f()
          }

          fun Scope.f() {
            schedule { n -> g(n) }
          }

          // Self-contained example of same recursive pattern, reusing standard interface
          fun f(handle: (() -> Unit) -> Unit) {
            handle { f(handle) }
          }
          """
              .trimIndent()
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testInterpreter_bigStep() {
    lint()
      .files(
        kotlin(
            """
          import androidx.annotation.AnyThread
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread

          /******************************
           *  Syntax
           ******************************/
          sealed interface Prim: Exp, Val, HostVal {
            object Add1: Prim
            object IsInt: Prim
            object IsProc: Prim
          }
          sealed interface Exp
          class App(val fn: Exp, val arg: Exp): Exp
          class Lam(val param: String, val body: Exp): Exp
          class If0(val cnd: Exp, val thn: Exp, val els: Exp): Exp
          class Num(val unboxed: Int): Exp, Val, HostVal
          class Var(val name: String): Exp

          /******************************
           * Runtime
           ******************************/
          sealed interface Env<out T> {
            object Mt: Env<Nothing>
            class Cons<out T>(val key: String, val value: T, val rest: Env<T>): Env<T>
          }
          sealed interface Val
          class Clo(val param: String, val body: Exp, val env: Env<Val>): Val
          private operator fun<T> Env<T>.get(x: String): T = when (this) {
            is Env.Cons -> if (key == x) value else rest[x]
            is Env.Mt -> throw LookupException()
          }
          private fun<T> T.isZero(): Boolean = this is Num && unboxed == 0

          /******************************
           * Direct big-step interpreter
           ******************************/
          fun Exp.eval(): Val = ev(Env.Mt)

          @WorkerThread
          private fun Exp.ev(env: Env<Val>): Val = when (this) {
            is Lam -> Clo(param, body, env)
            is Var -> env[name]
            is App -> fn.ev(env).ap(arg.ev(env))
            is If0 -> (if (cnd.ev(env).isZero()) thn else els).ev(env)
            is Num -> this
            is Prim -> this
          }

          @AnyThread
          private fun Val.ap(x: Val): Val = when (this) {
            is Clo -> body.ev(Env.Cons(param, x, env))
            is Prim -> primAp(x)
            is Num -> throw MisApplication()
          }
          private fun Prim.primAp(x: Val): Val = when (this) {
            Prim.Add1 -> if (x is Num) Num(x.unboxed + 1) else throw MisAdd1()
            Prim.IsInt -> Num(if (x is Num) 0 else 1)
            Prim.IsProc -> Num(if (x is Clo || x is Prim) 0 else 1)
          }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/Prim.kt:52: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
            is Clo -> body.ev(Env.Cons(param, x, env))
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~
          1 error
        """
          .trimIndent()
      )
  }

  fun testInterpreter_cekMachine() {
    lint()
      .files(
        kotlin(
            """

           /******************************
            *  Syntax
            ******************************/
           sealed interface Prim: Exp, Val, HostVal {
             object Add1: Prim
             object IsInt: Prim
             object IsProc: Prim
           }
           sealed interface Exp
           class App(val fn: Exp, val arg: Exp): Exp
           class Lam(val param: String, val body: Exp): Exp
           class If0(val cnd: Exp, val thn: Exp, val els: Exp): Exp
           class Num(val unboxed: Int): Exp, Val, HostVal
           class Var(val name: String): Exp

           /******************************
            * Runtime
            ******************************/
           sealed interface Env<out T> {
             object Mt: Env<Nothing>
             class Cons<out T>(val key: String, val value: T, val rest: Env<T>): Env<T>
           }
           sealed interface Val
           class Clo(val param: String, val body: Exp, val env: Env<Val>): Val
           private operator fun<T> Env<T>.get(x: String): T = when (this) {
             is Env.Cons -> if (key == x) value else rest[x]
             is Env.Mt -> throw LookupException()
           }
           private fun<T> T.isZero(): Boolean = this is Num && unboxed == 0
           private fun Prim.primAp(x: Val): Val = when (this) {
             Prim.Add1 -> if (x is Num) Num(x.unboxed + 1) else throw MisAdd1()
             Prim.IsInt -> Num(if (x is Num) 0 else 1)
             Prim.IsProc -> Num(if (x is Clo || x is Prim) 0 else 1)
           }

           /******************************
            * CEK machine
            ******************************/
           sealed interface State
           sealed class Ongoing(val kont: K): State {
             class Ev(val control: Exp, val env: Env<Val>, kont: K): Ongoing(kont)
             class Co(val ans: Val, kont: K): Ongoing(kont)
           }
           class Ans(val ans: Val): State
           sealed interface K {
             object Mt: K
             class Fn(val arg: Exp, val env: Env<Val>, val rest: K): K
             class Ar(val fn: Val, val rest: K): K
             class If(val thn: Exp, val els: Exp, val env: Env<Val>, val rest: K): K
           }
           private fun Ongoing.step(): State = when (this) {
             is Ongoing.Ev -> when (control) {
               is Lam -> Ongoing.Co(Clo(control.param, control.body, env), kont)
               is Var -> Ongoing.Co(env[control.name], kont)
               is App -> Ongoing.Ev(control.fn, env, K.Fn(control.arg, env, kont))
               is If0 -> Ongoing.Ev(control.cnd, env, K.If(control.thn, control.els, env, kont))
               is Num -> Ongoing.Co(control, kont)
               is Prim -> Ongoing.Co(control, kont)
             }
             is Ongoing.Co -> when (kont) {
               is K.Mt -> Ans(ans)
               is K.Fn -> Ongoing.Ev(kont.arg, kont.env, K.Ar(ans, kont.rest))
               is K.Ar -> when (kont.fn) {
                 is Clo -> Ongoing.Ev(kont.fn.body, Env.Cons(kont.fn.param, ans, kont.fn.env), kont.rest)
                 is Prim -> Ongoing.Co(kont.fn.primAp(ans), kont.rest)
                 is Num -> throw MisApplication()
               }
               is K.If -> Ongoing.Ev(if (ans.isZero()) kont.thn else kont.els, kont.env, kont.rest)
             }
           }
           fun evalStep(e: Exp): Val {
             tailrec fun run(s: Ongoing): Val = when (val s1 = s.step()) {
               is Ans -> s1.ans
               is Ongoing -> run(s1)
             }
             return run(Ongoing.Ev(e, Env.Mt, K.Mt))
           }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testInterpreter_compilation() {
    lint()
      .files(
        kotlin(
            """
           import androidx.annotation.AnyThread
           import androidx.annotation.UiThread
           import androidx.annotation.WorkerThread

           /******************************
            *  Syntax
            ******************************/
           sealed interface Prim: Exp, Val, HostVal {
             object Add1: Prim
             object IsInt: Prim
             object IsProc: Prim
           }
           sealed interface Exp
           class App(val fn: Exp, val arg: Exp): Exp
           class Lam(val param: String, val body: Exp): Exp
           class If0(val cnd: Exp, val thn: Exp, val els: Exp): Exp
           class Num(val unboxed: Int): Exp, Val, HostVal
           class Var(val name: String): Exp

           /******************************
            * Runtime
            ******************************/
           sealed interface Env<out T> {
             object Mt: Env<Nothing>
             class Cons<out T>(val key: String, val value: T, val rest: Env<T>): Env<T>
           }
           sealed interface Val
           class Clo(val param: String, val body: Exp, val env: Env<Val>): Val
           private operator fun<T> Env<T>.get(x: String): T = when (this) {
             is Env.Cons -> if (key == x) value else rest[x]
             is Env.Mt -> throw LookupException()
           }
           private fun<T> T.isZero(): Boolean = this is Num && unboxed == 0
           private fun Prim.primAp(x: Val): Val = when (this) {
             Prim.Add1 -> if (x is Num) Num(x.unboxed + 1) else throw MisAdd1()
             Prim.IsInt -> Num(if (x is Num) 0 else 1)
             Prim.IsProc -> Num(if (x is Clo || x is Prim) 0 else 1)
           }

           /******************************
            * Compile then run
            ******************************/
           @WorkerThread
           fun Exp.evalComp(): HostVal = comp()(Env.Mt)

           sealed interface HostVal
           private fun interface Proc: HostVal, (HostVal) -> HostVal

           @AnyThread
           private fun Exp.comp(): (Env<HostVal>) -> HostVal = when (this) {
             is Lam -> {
               val c = body.comp()
               val x = param
               fun(env) = Proc { v -> c(Env.Cons(x, v, env)) }
             }
             is Var -> {
               val name = name
               fun(env) = env[name]
             }
             is App -> {
               val fn = fn.comp()
               val arg = arg.comp()
               fun(env): HostVal {
                 val f = fn(env)
                 val v = arg(env)
                 return when (f) {
                   is Proc -> f(v)
                   is Prim -> when (f) {
                     Prim.Add1 -> if (v is Num) Num(v.unboxed + 1) else throw MisAdd1()
                     Prim.IsInt -> Num(if (v is Num) 0 else 1)
                     Prim.IsProc -> Num(if (v is Proc) 0 else 1)
                   }
                   is Num -> throw MisApplication()
                 }
               }
             }
             is If0 -> {
               val cnd = cnd.comp()
               val thn = thn.comp()
               val els = els.comp()
               fun(env) = (if (cnd(env).isZero()) thn else els)(env)
             }
             is Num -> fun(_) = this
             is Prim -> fun(_) = this
           }
           class LookupException: Exception()
           class MisApplication: Exception()
           class MisAdd1: Exception()
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  // TODO(b/390023468)
  fun `test submethod inconsistent with assumption on unannotated supermethod eventually noticed`() {
    lint()
      .files(
        kotlin(
            """
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread

          @UiThread fun ui() { }

          interface Intf {
              fun runIt(f: () -> Unit) = f()
          }

          class Impl: Intf {
              override fun runIt(f: () -> Unit) = ui()
          }

          @WorkerThread fun run_falseNeg(impl: Intf) = impl.runIt { } // TODO

          @WorkerThread fun run_caught(impl: Impl) = impl.runIt { }

          fun main_caught() = run_falseNeg(Impl())

          fun main_falseNeg() = run_falseNeg(Impl() as Intf) // TODO
          """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/Intf.kt:16: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
          @WorkerThread fun run_caught(impl: Impl) = impl.runIt { }
                                                          ~~~~~~~~~
          src/Intf.kt:18: Error: Argument must allow calling runIt() from @WorkerThread, but that call is requiring @{Main,Ui}Thread [ThreadConstraint]
          fun main_caught() = run_falseNeg(Impl())
                                           ~~~~~~
          2 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun `test vararg`() {
    lint()
      .files(
        kotlin(
            """
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread

          class UiWork: Runnable {
              @UiThread override fun run() { }
          }

          class HeavyWork: Runnable {
              @WorkerThread override fun run() { }
          }

          fun runEach(vararg tasks: Runnable) {
              for (task in tasks) task.run()
          }

          @WorkerThread fun runEachWorker() = runEach(HeavyWork(), HeavyWork()) // ok
          @WorkerThread fun runNone() = runEach() // also ok
          @WorkerThread fun runEachUi() = runEach(UiWork(), UiWork()) // nope


          """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/UiWork.kt:18: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
          @WorkerThread fun runEachUi() = runEach(UiWork(), UiWork()) // nope
                                          ~~~~~~~~~~~~~~~~~~~~~~~~~~~
          1 error
        """
          .trimIndent()
      )
  }

  fun `test kotlin properties`() {
    if (useFirUast()) return // TODO(b/406309278)
    lint()
      .files(
        kotlin(
            """
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.AnyThread

          class Test1 {
              var inferredUiProp: Int
                get() = uiWork()
                set(v) { uiWork() }

              var cheapProp: Int
                get() = 42
                set(v) { }

              val lazyProp: Int by lazy {
                uiWork()
              }

              @get:UiThread
              @set:UiThread
              var uiProp: Int
                get() = uiWork() // ok
                set(v) { }

              @get:WorkerThread
              @set:WorkerThread
              var workerProp: Int
                get() = uiWork() // error
                set(v) { }

              @UiThread private fun uiWork(): Int = 42

              @AnyThread fun cheapPropFromAny() {
                  cheapProp
              }

              @AnyThread fun uiFromAny() {
                  inferredUiProp // error
              }
          }

          // class-level annotation ignored by properties
          @WorkerThread
          class Test2 {
              var inferredWorkerProp: Int
                get() = heavyWork()
                set(v) { heavyWork() }

              var cheapProp: Int = 42 // simple property should not inherit class annotation

              val lazyProp: Int by lazy {
                heavyWork()
              }

              @get:WorkerThread
              @set:WorkerThread
              var workerProp: Int
                 get() = heavyWork() // ok
                 set(v) { }

              @get:UiThread
              @set:UiThread
              var uiProp: Int
                 get() = heavyWork() // error
                 set(v) { }

              private fun heavyWork(): Int = 42

              @AnyThread private fun cheapPropFromAny() {
                  cheapProp // ok
              }

              @AnyThread private fun workerPropFromAny() {
                  workerProp // error
              }

              @AnyThread private fun workerPropSetFromAny() {
                  workerProp = 0 // error
              }
          }

          @AnyThread
          fun main1(): Boolean {
              val test = Test1()
              test.uiProp == 32 // error
              test.uiProp++ // error
          }

          @AnyThread
          fun main2() {
              val test = Test2()
              test.workerProp = 42
              id(Test2()).workerProp = 43
          }

          fun<X> id(x: X): X = x
          """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/Test1.kt:27: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
                get() = uiWork() // error
                        ~~~~~~~~
          src/Test1.kt:37: Error: Property call must be from @{Main,Ui}Thread, but context is allowing @AnyThread [ThreadConstraint]
                  inferredUiProp // error
                  ~~~~~~~~~~~~~~
          src/Test1.kt:63: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
                 get() = heavyWork() // error
                         ~~~~~~~~~~~
          src/Test1.kt:73: Error: Property call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  workerProp // error
                  ~~~~~~~~~~
          src/Test1.kt:77: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  workerProp = 0 // error
                  ~~~~~~~~~~~~~~
          src/Test1.kt:84: Error: Property call must be from @{Main,Ui}Thread, but context is allowing @AnyThread [ThreadConstraint]
              test.uiProp == 32 // error
              ~~~~~~~~~~~
          src/Test1.kt:85: Error: Call must be from @{Main,Ui}Thread, but context is allowing @AnyThread [ThreadConstraint]
              test.uiProp++ // error
              ~~~~~~~~~~~~~
          src/Test1.kt:91: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
              test.workerProp = 42
              ~~~~~~~~~~~~~~~~~~~~
          src/Test1.kt:92: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
              id(Test2()).workerProp = 43
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~
          9 errors
        """
          .trimIndent()
      )
  }

  fun testOverloadedOperators() {
    lint()
      .files(
        kotlin(
            """
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.AnyThread
          import androidx.annotation.BinderThread

          data object Value {
              @WorkerThread
              operator fun plus(that: Value): Value = Value

              @WorkerThread
              operator fun get(k: Int): Int = k

              @WorkerThread
              operator fun set(k: String, v: Any) { }

              @WorkerThread
              operator fun invoke(x: String): Int = 0
          }

          @WorkerThread
          operator fun Value.minus(that: Value): Value = that

          @WorkerThread
          operator fun Value.get(k: String): String = k

          @WorkerThread
          operator fun Value.set(k: Int, v: String) { }

          @WorkerThread
          operator fun Value.invoke(x1: String, x2: Int): Int = x2

          class Test {
              var value = Value

              @AnyThread
              fun f() {
                  value += value
                  value[42]
                  value["foo"] = 42
                  value("qux")
                  value -= value
                  value["bar"]
                  value[21] = "word"
                  value("qux", 0)
              }
          }
          """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/Value.kt:37: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  value += value
                  ~~~~~~~~~~~~~~
          src/Value.kt:38: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  value[42]
                  ~~~~~~~~~
          src/Value.kt:39: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  value["foo"] = 42
                  ~~~~~~~~~~~~~~~~~
          src/Value.kt:40: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  value("qux")
                  ~~~~~~~~~~~~
          src/Value.kt:41: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  value -= value
                  ~~~~~~~~~~~~~~
          src/Value.kt:42: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  value["bar"]
                  ~~~~~~~~~~~~
          src/Value.kt:43: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  value[21] = "word"
                  ~~~~~~~~~~~~~~~~~~
          src/Value.kt:44: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  value("qux", 0)
                  ~~~~~~~~~~~~~~~
          8 errors
        """
          .trimIndent()
      )
  }

  fun testLocalFunction() {
    lint()
      .files(
        kotlin(
            """
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.AnyThread

          @UiThread
          fun main() {
              fun<X> id(x: X): X = x
              @UiThread fun ui() { id(42) }
              @WorkerThread fun work() { id("foo") }
              id(::ui).invoke() // ok
              id(::ui)() //ok
              id(::work).invoke() // ERROR
              id(::work)() // ERROR
          }
          """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test.kt:12: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
              id(::work).invoke() // ERROR
                         ~~~~~~~~
          src/test.kt:13: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
              id(::work)() // ERROR
              ~~~~~~~~~~~~
          2 errors
        """
          .trimIndent()
      )
  }

  fun testOverloadedReceiver() {
    lint()
      .files(
        kotlin(
            """
          package mykotlin
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.AnyThread

          class Outer {
              @UiThread open fun f() { }

              inner class Inner {
                  @WorkerThread open fun g() {
                      this@Outer.f() // ERROR
                      f() // ERROR
                      g() // ok
                  }
              }
          }
          """
          )
          .indented(),
        java(
            """
            package myjava;
            import androidx.annotation.UiThread;
            import androidx.annotation.WorkerThread;
            import androidx.annotation.AnyThread;

            class JOuter {
                @WorkerThread public void jf() { }
                private class JInner {
                    @UiThread public void jg() {
                        JOuter.this.jf(); // ERROR
                        jf(); // ERROR
                        jg(); // ok
                    }
                }
            }
          """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/myjava/JOuter.java:10: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
                      JOuter.this.jf(); // ERROR
                                  ~~~~
          src/myjava/JOuter.java:11: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
                      jf(); // ERROR
                      ~~~~
          src/mykotlin/Outer.kt:11: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
                      this@Outer.f() // ERROR
                                 ~~~
          src/mykotlin/Outer.kt:12: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
                      f() // ERROR
                      ~~~
          4 errors
        """
          .trimIndent()
      )
  }

  fun testAnonObjectUnderStaticMethod() {
    lint()
      .files(
        kotlin(
            """
          import androidx.annotation.UiThread
          import androidx.annotation.WorkerThread
          import androidx.annotation.AnyThread

          interface Interface {
              fun f()

              companion object {
                  @JvmStatic
                  fun getInstance(param: Param): Interface =
                      object : Interface {
                          @WorkerThread override fun f() = 42
                      }
              }
          }

          fun runIntf(intf: Interface) {
              intf.f()
          }

          @UiThread
          fun main() {
              runIntf(Interface.getInstance(object : Param { }))
          }

          interface Param { }
          """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/Interface.kt:23: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
              runIntf(Interface.getInstance(object : Param { }))
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          1 error
        """
          .trimIndent()
      )
  }

  fun testTypeParamInstantiation() {
    lint()
      .files(
        java(
            """
            import androidx.annotation.UiThread;
            import androidx.annotation.WorkerThread;

            class Test {
              interface Box<T> {
                T unbox();
              }

              static<T> T indirectlyUnbox(Box<T> b) {
                  return b.unbox();
              }

              static class Worker {
                  @WorkerThread void work() { }
              }

              @UiThread static void ui(Box<Worker> b) {
                  indirectlyUnbox(b).work();
              }

              /* TODO b/437405527
              interface WorkerBox extends Box<Worker> { }
              @UiThread static void ui1(WorkerBox b) {
                  indirectlyUnbox(b).work();
              }
              */
            }
          """
              .trimIndent()
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/Test.java:18: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
                indirectlyUnbox(b).work();
                                   ~~~~~~
          1 error
        """
          .trimIndent()
      )
  }

  /* Old tests from [ThreadDetectorTest] */

  fun testThreading() {
    lint()
      .files(
        java(
          "src/test/pkg/ThreadTest.java",
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import androidx.annotation.MainThread;\n" +
            "import androidx.annotation.UiThread;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "\n" +
            "public class ThreadTest {\n" +
            "    public static AsyncTask testTask() {\n" +
            "\n" +
            "        return new AsyncTask() {\n" +
            "            final CustomView view = new CustomView();\n" +
            "\n" +
            "            @Override\n" +
            "            protected void doInBackground(Object... params) {\n" +
            "                onPreExecute(); // ERROR\n" +
            "                view.paint(); // OK, subclass more permissive\n" +
            "                publishProgress(); // OK\n" +
            "            }\n" +
            "\n" +
            "            @Override\n" +
            "            protected void onPreExecute() {\n" +
            "                publishProgress(); // ERROR\n" +
            "                onProgressUpdate(); // OK\n" +
            "            }\n" +
            "        };\n" +
            "    }\n" +
            "\n" +
            "    @UiThread\n" +
            "    public static class View {\n" +
            "        public void paint() {\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    public static class CustomView extends View {\n" +
            "        @Override public void paint() {\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    public abstract static class AsyncTask {\n" +
            "        @WorkerThread\n" +
            "        protected abstract void doInBackground(Object... params);\n" +
            "\n" +
            "        @MainThread\n" +
            "        protected void onPreExecute() {\n" +
            "        }\n" +
            "\n" +
            "        @MainThread\n" +
            "        protected void onProgressUpdate(Object... values) {\n" +
            "        }\n" +
            "\n" +
            "        @WorkerThread\n" +
            "        protected final void publishProgress(Object... values) {\n" +
            "        }\n" +
            "    }\n" +
            "}\n",
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/ThreadTest.java:15: Error: Call must be from @{Main,Ui}Thread, but super method AsyncTask.doInBackground(…) is allowing @WorkerThread [ThreadConstraint]
                          onPreExecute(); // ERROR
                          ~~~~~~~~~~~~~~
          src/test/pkg/ThreadTest.java:22: Error: Call must be from @WorkerThread, but super method AsyncTask.onPreExecute(…) is allowing @{Main,Ui}Thread [ThreadConstraint]
                          publishProgress(); // ERROR
                          ~~~~~~~~~~~~~~~~~
          2 errors
        """
          .trimIndent()
      )
  }

  fun testFieldReferencesOk() {
    lint()
      .files(
        java(
            "src/test/pkg/ThreadTest.java",
            """
                package test.pkg;

                import androidx.annotation.MainThread;
                import androidx.annotation.UiThread;
                import androidx.annotation.WorkerThread;

                @SuppressWarnings({"ClassNameDiffersFromFileName", "MethodMayBeStatic"})
                public class ThreadTest2 {
                    @UiThread
                    public static class View {
                        public int field = 42;
                        public void paint() {
                        }
                    }

                    @WorkerThread
                    protected final void useView(View view) {
                        int status = view.field; // OK
                    }
                }
                """,
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testConstructor() {
    lint()
      .files(
        LintDetectorTest.java(
          "src/test/pkg/ConstructorTest.java",
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import androidx.annotation.DrawableRes;\n" +
            "import androidx.annotation.IntRange;\n" +
            "import androidx.annotation.UiThread;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "\n" +
            "public class ConstructorTest {\n" +
            "    @UiThread\n" +
            "    ConstructorTest(@DrawableRes int iconResId, @IntRange(from = 5) int start) {\n" +
            "    }\n" +
            "\n" +
            "    public void testParameters() {\n" +
            "        new ConstructorTest(1, 3);\n" +
            "    }\n" +
            "\n" +
            "    @WorkerThread\n" +
            "    public void testMethod(int res, int range) {\n" +
            "        new ConstructorTest(res, range);\n" +
            "    }\n" +
            "}\n",
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/ConstructorTest.java:19: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
                  new ConstructorTest(res, range);
                  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
          1 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testThreadingIssue207313() {
    // Regression test for scenario in
    //  https://code.google.com/p/android/issues/detail?id=207313
    lint()
      .files(
        java(
          "src/test/pkg/BigClass.java",
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import androidx.annotation.UiThread;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "\n" +
            "@UiThread // it's here to prevent putting it on all 100 methods\n" +
            "class BigClass {\n" +
            "    void f1() { }\n" +
            "    void f2() { }\n" +
            "    //...\n" +
            "    void f100() { }\n" +
            "    @WorkerThread // this single method is not UI, it's something else\n" +
            "    void g() { }\n" +
            "    BigClass() { }\n" +
            "}\n",
        ),
        java(
          "src/test/pkg/BigClassClient.java",
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import androidx.annotation.UiThread;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "\n" +
            "@SuppressWarnings(\"unused\")\n" +
            "public class BigClassClient {\n" +
            "    @WorkerThread\n" +
            "    void worker() {\n" +
            "        BigClass o = new BigClass();\n" +
            "        o.f1();   // correct WrongThread: must be called from the UI thread currently inferred thread is worker\n" +
            "        o.f2();   // correct WrongThread: must be called from the UI thread currently inferred thread is worker\n" +
            "        o.f100(); // correct WrongThread: must be called from the UI thread currently inferred thread is worker\n" +
            "        o.g();    // unexpected WrongThread: must be called from the UI thread currently inferred thread is worker\n" +
            "    }\n" +
            "    @UiThread\n" +
            "    void ui() {\n" +
            "        BigClass o = new BigClass();\n" +
            "        o.f1();   // no problem\n" +
            "        o.f2();   // no problem\n" +
            "        o.f100(); // no problem\n" +
            "        o.g();    // correct WrongThread: must be called from the worker thread currently inferred thread is UI\n" +
            "    }\n" +
            "}\n",
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/BigClassClient.java:11: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
                  o.f1();   // correct WrongThread: must be called from the UI thread currently inferred thread is worker
                    ~~~~
          src/test/pkg/BigClassClient.java:12: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
                  o.f2();   // correct WrongThread: must be called from the UI thread currently inferred thread is worker
                    ~~~~
          src/test/pkg/BigClassClient.java:13: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
                  o.f100(); // correct WrongThread: must be called from the UI thread currently inferred thread is worker
                    ~~~~~~
          src/test/pkg/BigClassClient.java:22: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
                  o.g();    // correct WrongThread: must be called from the worker thread currently inferred thread is UI
                    ~~~
          4 errors
        """
          .trimIndent()
      )
  }

  fun testThreadingIssue207302() {
    // Regression test for
    //    https://code.google.com/p/android/issues/detail?id=207302
    lint()
      .files(
        java(
          "src/test/pkg/TestPostRunnable.java",
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "import android.view.View;\n" +
            "\n" +
            "public class TestPostRunnable {\n" +
            "    View view;\n" +
            "    @WorkerThread\n" +
            "    void f() {\n" +
            "        view.post(new Runnable() {\n" +
            "            @Override public void run() {\n" +
            "                // stuff on UI thread\n" +
            "            }\n" +
            "        });\n" +
            "    }\n" +
            "}",
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testAnyThread() {
    lint()
      .files(
        java(
          "src/test/pkg/AnyThreadTest.java",
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import androidx.annotation.AnyThread;\n" +
            "import androidx.annotation.UiThread;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "\n" +
            "@UiThread\n" +
            "class AnyThreadTest {\n" +
            "    @AnyThread\n" +
            "    static void threadSafe() {\n" +
            "        worker(); // ERROR\n" +
            "    }\n" +
            "    @WorkerThread\n" +
            "    static void worker() {\n" +
            "        threadSafe(); // OK\n" +
            "    }\n" +
            "}\n",
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/AnyThreadTest.java:11: Error: Call must be from @WorkerThread, but context is allowing @AnyThread [ThreadConstraint]
                  worker(); // ERROR
                  ~~~~~~~~
          1 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testMultipleThreads() {
    // Ensure that when multiple threading annotations are specified
    // on methods, this is handled properly: calls can satisfy any one
    // threading annotation on the target, but if multiple threads are
    // found in the context, all of them must be valid for all targets
    lint()
      .files(
        java(
          "src/test/pkg/MultiThreadTest.java",
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import androidx.annotation.BinderThread;\n" +
            "import androidx.annotation.UiThread;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "\n" +
            "class MultiThreadTest {\n" +
            "    @UiThread\n" +
            "    @WorkerThread\n" +
            "    private static void callee() {\n" +
            "    }\n" +
            "\n" +
            "    @WorkerThread\n" +
            "    private static void call1() {\n" +
            "        callee(); // OK - context is included in target\n" +
            "    }\n" +
            "\n" +
            "    @BinderThread\n" +
            "    @WorkerThread\n" +
            "    private static void call2() {\n" +
            "        callee(); // Not ok: thread could be binder thread, not supported by target\n" +
            "    }\n" +
            "\n" +
            "    // Same case as call2 but different order to make sure we don't just test the first one:\n" +
            "    @WorkerThread\n" +
            "    @BinderThread\n" +
            "    private static void call3() {\n" +
            "        callee(); // Not ok: thread could be binder thread, not supported by target\n" +
            "    }\n" +
            "}\n",
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/MultiThreadTest.java:21: Error: Call must be from @{Main,Ui}Thread|@WorkerThread, but context is allowing @WorkerThread|@BinderThread [ThreadConstraint]
                  callee(); // Not ok: thread could be binder thread, not supported by target
                  ~~~~~~~~
          src/test/pkg/MultiThreadTest.java:28: Error: Call must be from @{Main,Ui}Thread|@WorkerThread, but context is allowing @WorkerThread|@BinderThread [ThreadConstraint]
                  callee(); // Not ok: thread could be binder thread, not supported by target
                  ~~~~~~~~
          2 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testMultipleThreadNonTrivialSubsumption() {
    lint()
      .files(
        java(
          "src/test/pkg/MultiThreadTest.java",
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import androidx.annotation.BinderThread;\n" +
            "import androidx.annotation.UiThread;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "import androidx.annotation.MainThread;\n" +
            "\n" +
            "class MultiThreadTest {\n" +
            "    @UiThread\n" +
            "    @WorkerThread\n" +
            "    private static void callee() {\n" +
            "    }\n" +
            "\n" +
            "    @MainThread\n" +
            "    @WorkerThread\n" +
            "    private static void caller() {\n" +
            "        callee(); // OK - context is included in target\n" +
            "    }\n" +
            "}\n",
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testStaticMethod() {
    // Regression test for
    //  https://code.google.com/p/android/issues/detail?id=175397
    lint()
      .files(
        java(
          "src/test/pkg/StaticMethods.java",
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import android.content.Context;\n" +
            "import android.os.AsyncTask;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "import android.view.View;\n" +
            "\n" +
            "public class StaticMethods extends View {\n" +
            "    public StaticMethods(Context context) {\n" +
            "        super(context);\n" +
            "    }\n" +
            "\n" +
            "    class MyAsyncTask extends AsyncTask<Long, Void, Boolean> {\n" +
            "        @Override\n" +
            "        protected Boolean doInBackground(Long... sizes) {\n" +
            "            return workerThreadMethod();\n" +
            "        }\n" +
            "\n" +
            "        @Override\n" +
            "        protected void onPostExecute(Boolean isEnoughFree) {\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    // Static utility method which happens to be in a custom view,\n" +
            "    // but doesn't require UI thread.\n" +
            "    public static boolean workerThreadMethod() {\n" +
            "        return true;\n" +
            "    }\n" +
            "}",
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testThreadingWithinLambdas() {
    // Regression test for https://code.google.com/p/android/issues/detail?id=223101
    lint()
      .files(
        java(
            // language=java
            """package test.pkg;

                import android.app.Activity;
                import android.os.Bundle;
                import androidx.annotation.WorkerThread;
                import androidx.annotation.UiThread;

                public class LambdaThreadTest extends Activity {
                    @WorkerThread
                    static void compute() {}

                    static void doInBackground(@WorkerThread Runnable r) {}
                    static void doInUiThread(@UiThread Runnable r) {}

                    @Override protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);

                        doInBackground(new Runnable() {
                            @Override public void run() {
                                compute();
                            }
                        });
                        doInBackground(() -> compute());
                        doInBackground(LambdaThreadTest::compute);

                        doInUiThread(new Runnable() {
                            @Override public void run() {
                                compute();
                            }
                        });
                        doInUiThread(() -> compute());
                        doInUiThread(LambdaThreadTest::compute);
                    }
                }
                """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/LambdaThreadTest.java:26: Error: Call must be from @WorkerThread, but a super method is allowing @{Main,Ui}Thread [ThreadConstraint]
                                  doInUiThread(new Runnable() {
                                               ^
          src/test/pkg/LambdaThreadTest.java:31: Error: Call must be from @WorkerThread, but a super method is allowing @{Main,Ui}Thread [ThreadConstraint]
                                  doInUiThread(() -> compute());
                                                     ~~~~~~~~~
          src/test/pkg/LambdaThreadTest.java:32: Error: Call must be from @WorkerThread, but a super method is allowing @{Main,Ui}Thread [ThreadConstraint]
                                  doInUiThread(LambdaThreadTest::compute);
                                               ~~~~~~~~~~~~~~~~~~~~~~~~~
          3 errors
        """
          .trimIndent()
      )
  }

  fun testThreadsInLambdas() {
    // Regression test for b/38069472
    lint()
      .files(
        LintDetectorTest.manifest().minSdk(1),
        java(
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import androidx.annotation.MainThread;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "\n" +
            "import java.util.concurrent.Executor;\n" +
            "\n" +
            "public abstract class ApiCallInLambda<T> {\n" +
            "    Executor networkExecutor;\n" +
            "    @MainThread\n" +
            "    private void fetchFromNetwork(T data) {\n" +
            "        networkExecutor.execute(() -> {\n" +
            "            Call<T> call = createCall();\n" +
            "        });\n" +
            "    }\n" +
            "\n" +
            "    @WorkerThread\n" +
            "    protected abstract Call<T> createCall();\n" +
            "\n" +
            "    private static class Call<T> {\n" +
            "    }\n" +
            "}\n"
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testWrongThread() {

    lint()
      .files(
        LintDetectorTest.java(
          "" +
            "package test.pkg;\n" +
            "import androidx.annotation.MainThread;\n" +
            "import androidx.annotation.UiThread;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "\n" +
            "public class X {\n" +
            "    public AsyncTask testTask() {\n" +
            "\n" +
            "        return new AsyncTask() {\n" +
            "            final CustomView view = new CustomView();\n" +
            "\n" +
            "            @Override\n" +
            "            protected void doInBackground(Object... params) {\n" +
            "                onPreExecute(); // ERROR\n" +
            "                view.paint(); // ERROR\n" +
            "                publishProgress(); // OK\n" +
            "            }\n" +
            "\n" +
            "            @Override\n" +
            "            protected void onPreExecute() {\n" +
            "                publishProgress(); // ERROR\n" +
            "                onProgressUpdate(); // OK\n" +
            "            }\n" +
            "        };\n" +
            "    }\n" +
            "\n" +
            "    @UiThread\n" +
            "    public static class View {\n" +
            "        public void paint() {\n" +
            "        }\n" +
            "    }\n" +
            "    @some.pkg.UnrelatedNameEndsWithThread\n" +
            "    public static void test1(View view) {\n" +
            "        view.paint();\n" +
            "    }\n" +
            "\n" +
            "    @UiThread\n" +
            "    public static void test2(View view) {\n" +
            "        test1(view);\n" +
            "    }\n" +
            "\n" +
            "    @UiThread\n" +
            "    public static void test3(View view) {\n" +
            "        TestClass.test4();\n" +
            "    }\n" +
            "\n" +
            "    @some.pkg.UnrelatedNameEndsWithThread\n" +
            "    public static class TestClass {\n" +
            "        public static void test4() {\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    public static class CustomView extends View {\n" +
            "    }\n" +
            "\n" +
            "    public static abstract class AsyncTask {\n" +
            "        @WorkerThread\n" +
            "        protected abstract void doInBackground(Object... params);\n" +
            "\n" +
            "        @MainThread\n" +
            "        protected void onPreExecute() {\n" +
            "        }\n" +
            "\n" +
            "        @MainThread\n" +
            "        protected void onProgressUpdate(Object... values) {\n" +
            "        }\n" +
            "\n" +
            "        @WorkerThread\n" +
            "        protected final void publishProgress(Object... values) {\n" +
            "        }\n" +
            "    }\n" +
            "}\n"
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .allowCompilationErrors()
      .run()
      .expect(
        """
          src/test/pkg/X.java:14: Error: Call must be from @{Main,Ui}Thread, but super method AsyncTask.doInBackground(…) is allowing @WorkerThread [ThreadConstraint]
                          onPreExecute(); // ERROR
                          ~~~~~~~~~~~~~~
          src/test/pkg/X.java:15: Error: Call must be from @{Main,Ui}Thread, but super method AsyncTask.doInBackground(…) is allowing @WorkerThread [ThreadConstraint]
                          view.paint(); // ERROR
                               ~~~~~~~
          src/test/pkg/X.java:21: Error: Call must be from @WorkerThread, but super method AsyncTask.onPreExecute(…) is allowing @{Main,Ui}Thread [ThreadConstraint]
                          publishProgress(); // ERROR
                          ~~~~~~~~~~~~~~~~~
          3 errors
        """
          .trimIndent()
      )
  }

  /**
   * Test that the parent class annotations are not inherited by the static methods declared in a
   * child class. In the example below, android.view.View is annotated with the UiThread annotation.
   * The test checks that workerThreadMethod does not inherit that annotation.
   */
  fun testStaticWrongThread() {

    lint()
      .files(
        LintDetectorTest.java(
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import android.content.Context;\n" +
            "import android.os.AsyncTask;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "import android.view.View;\n" +
            "\n" +
            "public class X extends View {\n" +
            "    public X(Context context) {\n" +
            "        super(context);\n" +
            "    }\n" +
            "\n" +
            "    class MyAsyncTask extends AsyncTask<Long, Void, Boolean> {\n" +
            "        @Override\n" +
            "        protected Boolean doInBackground(Long... sizes) {\n" +
            "            return workedThreadMethod();\n" +
            "        }\n" +
            "\n" +
            "        @Override\n" +
            "        protected void onPostExecute(Boolean isEnoughFree) {\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    public static boolean workedThreadMethod() {\n" +
            "        return true;\n" +
            "    }\n" +
            "}"
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testAnyThread2() {
    // Tests support for the @AnyThread annotation as well as fixing bugs
    // suppressing AndroidLintWrongThread and
    // 207313: Class-level threading annotations are not overridable
    // 207302: @WorkerThread cannot call View.post

    lint()
      .files(
        LintDetectorTest.java(
          "" +
            "package test.pkg;\n" +
            "\n" +
            "import androidx.annotation.BinderThread;\n" +
            "import androidx.annotation.MainThread;\n" +
            "import androidx.annotation.UiThread;\n" +
            "import androidx.annotation.WorkerThread;\n" +
            "\n" +
            "@SuppressWarnings({\"WeakerAccess\", \"unused\"})\n" +
            "public class X {\n" +
            "    @UiThread\n" +
            "    static class AnyThreadTest {\n" +
            "        //    @AnyThread\n" +
            "        void threadSafe() {\n" +
            "            worker(); // ERROR\n" +
            "        }\n" +
            "\n" +
            "        @WorkerThread\n" +
            "        void worker() {\n" +
            "            threadSafe(); // OK\n" +
            "        }\n" +
            "\n" +
            "        // Multi thread test\n" +
            "        @UiThread\n" +
            "        @WorkerThread\n" +
            "        private void callee() {\n" +
            "        }\n" +
            "\n" +
            "        @WorkerThread\n" +
            "        private void call1() {\n" +
            "            callee(); // OK - context is included in target\n" +
            "        }\n" +
            "\n" +
            "        @BinderThread\n" +
            "        @WorkerThread\n" +
            "        private void call2() {\n" +
            "            callee(); // Not ok: thread could be binder thread, not supported by target\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    public static AsyncTask testTask() {\n" +
            "\n" +
            "        return new AsyncTask() {\n" +
            "            final CustomView view = new CustomView();\n" +
            "\n" +
            "            @Override\n" +
            "            protected void doInBackground(Object... params) {\n" +
            "                onPreExecute(); // ERROR\n" +
            "                view.paint(); // ERROR\n" +
            "                publishProgress(); // OK\n" +
            "            }\n" +
            "\n" +
            "            @Override\n" +
            "            protected void onPreExecute() {\n" +
            "                publishProgress(); // ERROR\n" +
            "                onProgressUpdate(); // OK\n" +
            "            }\n" +
            "        };\n" +
            "    }\n" +
            "\n" +
            "    @UiThread\n" +
            "    public static class View {\n" +
            "        public void paint() {\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    public static class CustomView extends View {\n" +
            "        @Override public void paint() {\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    public abstract static class AsyncTask {\n" +
            "        @WorkerThread\n" +
            "        protected abstract void doInBackground(Object... params);\n" +
            "\n" +
            "        @MainThread\n" +
            "        protected void onPreExecute() {\n" +
            "        }\n" +
            "\n" +
            "        @MainThread\n" +
            "        protected void onProgressUpdate(Object... values) {\n" +
            "        }\n" +
            "\n" +
            "        @WorkerThread\n" +
            "        protected final void publishProgress(Object... values) {\n" +
            "        }\n" +
            "    }\n" +
            "}\n"
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/X.java:14: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
                      worker(); // ERROR
                      ~~~~~~~~
          src/test/pkg/X.java:19: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
                      threadSafe(); // OK
                      ~~~~~~~~~~~~
          src/test/pkg/X.java:36: Error: Call must be from @{Main,Ui}Thread|@WorkerThread, but context is allowing @WorkerThread|@BinderThread [ThreadConstraint]
                      callee(); // Not ok: thread could be binder thread, not supported by target
                      ~~~~~~~~
          src/test/pkg/X.java:47: Error: Call must be from @{Main,Ui}Thread, but super method AsyncTask.doInBackground(…) is allowing @WorkerThread [ThreadConstraint]
                          onPreExecute(); // ERROR
                          ~~~~~~~~~~~~~~
          src/test/pkg/X.java:54: Error: Call must be from @WorkerThread, but super method AsyncTask.onPreExecute(…) is allowing @{Main,Ui}Thread [ThreadConstraint]
                          publishProgress(); // ERROR
                          ~~~~~~~~~~~~~~~~~
          5 errors
        """
          .trimIndent()
      )
  }

  fun testMismatchedAnnotationPackages() {
    // Make sure we treat the old and new package annotations
    // as synonymous
    // Regression test for 74351531.
    lint()
      .files(
        java(
            """
                    package test.pkg;

                    @SuppressWarnings("ClassNameDiffersFromFileName")
                    public class X {
                        @androidx.annotation.WorkerThread
                        static class MyWorkerThreadCode {
                            static void method() {
                                MyOtherWorkerThreadCode.method();
                            }
                        }

                        @androidx.annotation.WorkerThread
                        public static class MyOtherWorkerThreadCode {
                            static void method() { }
                        }

                        @androidx.annotation.WorkerThread
                        static class MyWorkerThreadCode2 {
                            static void method() {
                                MyOtherWorkerThreadCode2.method();
                            }
                        }

                        @androidx.annotation.WorkerThread
                        public static class MyOtherWorkerThreadCode2 {
                            static void method() { }
                        }
                    }
                """
          )
          .indented(),
        java(
          """
                    package android.support.annotation;

                    import static java.lang.annotation.ElementType.METHOD;
                    import static java.lang.annotation.ElementType.CONSTRUCTOR;
                    import static java.lang.annotation.ElementType.TYPE;
                    import static java.lang.annotation.ElementType.PARAMETER;
                    import static java.lang.annotation.RetentionPolicy.CLASS;
                    import java.lang.annotation.Documented;
                    import java.lang.annotation.Retention;
                    import java.lang.annotation.Target;
                    @SuppressWarnings("ALL")
                    @Documented
                    @Retention(CLASS)
                    @Target({METHOD,CONSTRUCTOR,TYPE,PARAMETER})
                    public @interface WorkerThread {
                    }
                """
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testAnyThread80002895() {
    // Regression test for
    // 80002895 : WrongThread doesn't support @AnyThread
    lint()
      .files(
        java(
          """
                package test.pkg;

                import android.os.Handler;
                import android.os.Looper;
                import androidx.annotation.AnyThread;
                import androidx.annotation.MainThread;
                import androidx.annotation.NonNull;
                import androidx.annotation.WorkerThread;

                import java.util.Collection;
                import java.util.LinkedList;

                @SuppressWarnings({"unused", "ClassNameDiffersFromFileName"})
                public class BackgroundSomething {
                    private final BackupListeners listeners = new BackupListeners();

                    @WorkerThread
                    private void someBackgroundThing() {
                        listeners.started();
                        // do stuff
                        listeners.finished();
                    }
                }

                @MainThread
                @SuppressWarnings("ClassNameDiffersFromFileName")
                interface BackupListener {
                    void started();

                    void finished();
                }

                @SuppressWarnings({"Convert2Lambda", "unused", "Anonymous2MethodRef", "ClassNameDiffersFromFileName"})
                @AnyThread
                class BackupListeners implements BackupListener {
                    private final Collection<BackupListener> listeners = new LinkedList<>();
                    private final Handler main = new Handler(Looper.getMainLooper());

                    public void add(@NonNull BackupListener listener) {
                        synchronized (listeners) {
                            listeners.add(listener);
                        }
                    }

                    public void remove(@NonNull BackupListener listener) {
                        synchronized (listeners) {
                            listeners.remove(listener);
                        }
                    }

                    @Override
                    public void started() {
                        synchronized (listeners) {
                            for (final BackupListener listener : listeners) {
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        listener.started();
                                    }
                                });
                            }
                        }
                    }

                    @Override
                    public void finished() {
                        synchronized (listeners) {
                            for (final BackupListener listener : listeners) {
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        listener.finished();
                                    }
                                });
                            }
                        }
                    }
                }
                """
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  /** Similar to [testAnyThread80002895], but respect explicit weaker type annotation */
  fun testAnyThread80002895_conservative() {
    lint()
      .files(
        java(
          """
                package test.pkg;

                import android.os.Handler;
                import android.os.Looper;
                import androidx.annotation.AnyThread;
                import androidx.annotation.MainThread;
                import androidx.annotation.NonNull;
                import androidx.annotation.WorkerThread;

                import java.util.Collection;
                import java.util.LinkedList;

                @SuppressWarnings({"unused", "ClassNameDiffersFromFileName"})
                public class BackgroundSomething {
                    private final BackupListener listeners = new BackupListeners();

                    @WorkerThread
                    private void someBackgroundThing() {
                        listeners.started();
                        // do stuff
                        listeners.finished();
                    }
                }

                @MainThread
                @SuppressWarnings("ClassNameDiffersFromFileName")
                interface BackupListener {
                    void started();

                    void finished();
                }

                @SuppressWarnings({"Convert2Lambda", "unused", "Anonymous2MethodRef", "ClassNameDiffersFromFileName"})
                @AnyThread
                class BackupListeners implements BackupListener {
                    private final Collection<BackupListener> listeners = new LinkedList<>();
                    private final Handler main = new Handler(Looper.getMainLooper());

                    public void add(@NonNull BackupListener listener) {
                        synchronized (listeners) {
                            listeners.add(listener);
                        }
                    }

                    public void remove(@NonNull BackupListener listener) {
                        synchronized (listeners) {
                            listeners.remove(listener);
                        }
                    }

                    @Override
                    public void started() {
                        synchronized (listeners) {
                            for (final BackupListener listener : listeners) {
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        listener.started();
                                    }
                                });
                            }
                        }
                    }

                    @Override
                    public void finished() {
                        synchronized (listeners) {
                            for (final BackupListener listener : listeners) {
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        listener.finished();
                                    }
                                });
                            }
                        }
                    }
                }
                """
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/BackgroundSomething.java:20: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
                                  listeners.started();
                                            ~~~~~~~~~
          src/test/pkg/BackgroundSomething.java:22: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
                                  listeners.finished();
                                            ~~~~~~~~~~
          2 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  /** Similar to [testAnyThread80002895], but sub-listener called outside of `Handler.post` */
  fun testAnyThread80002895_wrong() {
    lint()
      .files(
        java(
          """
                package test.pkg;

                import android.os.Handler;
                import android.os.Looper;
                import androidx.annotation.AnyThread;
                import androidx.annotation.MainThread;
                import androidx.annotation.NonNull;
                import androidx.annotation.WorkerThread;

                import java.util.Collection;
                import java.util.LinkedList;

                @SuppressWarnings({"unused", "ClassNameDiffersFromFileName"})
                public class BackgroundSomething {
                    private final BackupListeners listeners = new BackupListeners();

                    @WorkerThread
                    private void someBackgroundThing() {
                        listeners.started();
                        // do stuff
                        listeners.finished();
                    }
                }

                @MainThread
                @SuppressWarnings("ClassNameDiffersFromFileName")
                interface BackupListener {
                    void started();

                    void finished();
                }

                @SuppressWarnings({"Convert2Lambda", "unused", "Anonymous2MethodRef", "ClassNameDiffersFromFileName"})
                @AnyThread
                class BackupListeners implements BackupListener {
                    private final Collection<BackupListener> listeners = new LinkedList<>();
                    private final Handler main = new Handler(Looper.getMainLooper());

                    public void add(@NonNull BackupListener listener) {
                        synchronized (listeners) {
                            listeners.add(listener);
                        }
                    }

                    public void remove(@NonNull BackupListener listener) {
                        synchronized (listeners) {
                            listeners.remove(listener);
                        }
                    }

                    @Override
                    public void started() {
                        synchronized (listeners) {
                            for (final BackupListener listener : listeners) {
                                listener.started();
                            }
                        }
                    }

                    @Override
                    public void finished() {
                        synchronized (listeners) {
                            for (final BackupListener listener : listeners) {
                                listener.finished();
                            }
                        }
                    }
                }
                """
        ),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/BackgroundSomething.java:56: Error: Call must be from @{Main,Ui}Thread, but context is allowing @AnyThread [ThreadConstraint]
                                          listener.started();
                                                   ~~~~~~~~~
          src/test/pkg/BackgroundSomething.java:65: Error: Call must be from @{Main,Ui}Thread, but context is allowing @AnyThread [ThreadConstraint]
                                          listener.finished();
                                                   ~~~~~~~~~~
          2 errors, 0 warnings
        """
          .trimIndent()
      )
  }

  fun testAnonymousInnerClasses() {
    lint()
      .files(
        java(
            """
                package test.pkg;

                import androidx.annotation.UiThread;
                import androidx.annotation.WorkerThread;

                @SuppressWarnings({"unused", "ClassNameDiffersFromFileName", "Convert2Lambda", "Convert2MethodRef", "Anonymous2MethodRef"})
                public class ThreadNesting {
                    @WorkerThread
                    public void workerWithAnonymousClass() {
                        DispatchingFramework dispatcher = new DispatchingFramework();

                        return dispatcher.dispatch(new Runnable() {
                            @Override
                            public void run() {
                                ui();
                            }
                        });
                    }

                    @WorkerThread
                    public void workerWithLambdas() {
                        DispatchingFramework dispatcher = new DispatchingFramework();
                        return dispatcher.dispatch(() -> ui());
                    }

                    @WorkerThread
                    public void workerWithMethodReference() {
                        // Anonymous inner class
                        DispatchingFramework dispatcher = new DispatchingFramework();
                        return dispatcher.dispatch(this::ui);
                    }

                    @UiThread
                    public void ui() {
                    }

                    @SuppressWarnings("WeakerAccess")
                    public class DispatchingFramework {
                        public boolean dispatch(Runnable run) {
                            return true;
                        }
                    }
                }
                """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testAnonymousInnerClasses2() {
    lint()
      .files(
        java(
            """
                package test.pkg;

                import android.annotation.SuppressLint;
                import android.app.Activity;
                import android.os.AsyncTask;
                import android.os.Bundle;
                import android.util.Pair;
                import android.view.View;
                import android.widget.Button;

                import java.util.List;

                import androidx.annotation.Nullable;

                @SuppressWarnings("ClassNameDiffersFromFileName")
                public class ThreadNesting2 extends Activity {
                    @Override
                    protected void onCreate(@Nullable Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        Button shuffle = new Button(this);

                        shuffle.setOnClickListener(new View.OnClickListener() {
                            @SuppressLint("StaticFieldLeak")
                            @Override
                            public void onClick(View view) {
                                //noinspection unchecked
                                new AsyncTask<List<String>, Void, Pair<List<String>, Object>>() {

                                    @Override
                                    protected Pair<List<String>, Object> doInBackground(
                                            List<String>... lists) {
                                        return new Pair(null, null);
                                    }
                                }.execute();
                            }
                        });
                    }
                }
                """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testLambdas() {
    lint()
      .files(
        java(
            """
                package test.pkg;

                import android.app.Activity;
                import android.os.AsyncTask;
                import android.os.Bundle;
                import android.util.Pair;
                import android.widget.Button;

                import java.util.List;

                @SuppressWarnings("ClassNameDiffersFromFileName")
                public class ThreadNesting2 extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        Button shuffle = new Button(this);

                        shuffle.setOnClickListener(view -> {
                            //noinspection unchecked
                            new AsyncTask<List<String>, Void, Pair<List<String>, Object>>() {
                                @Override
                                protected Pair<List<String>, Object> doInBackground(
                                        List<String>... lists) {
                                    return null;
                                }
                            }.execute();
                        });
                    }
                }
                """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expectClean()
  }

  fun testFunctionalInterfacesJava() {
    lint()
      .files(
        java(
            """
                    package test.pkg;

                    import androidx.annotation.MainThread;
                    import androidx.annotation.WorkerThread;

                    public class Test {

                        interface Foo {
                            @WorkerThread
                            void foo();
                        }

                        @MainThread
                        void uiMethod() {}

                        void test(Foo foo) {}

                        void testLambda() {
                            test(() -> { uiMethod(); }); // ERROR
                        }

                        void testMethodRef() {
                            test(this::uiMethod); // ERROR
                        }
                    }
                """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/Test.java:19: Error: Call must be from @{Main,Ui}Thread, but super method Foo.foo(…) is allowing @WorkerThread [ThreadConstraint]
                  test(() -> { uiMethod(); }); // ERROR
                               ~~~~~~~~~~
          src/test/pkg/Test.java:23: Error: Call must be from @{Main,Ui}Thread, but super method Foo.foo(…) is allowing @WorkerThread [ThreadConstraint]
                  test(this::uiMethod); // ERROR
                       ~~~~~~~~~~~~~~
          2 errors
        """
          .trimIndent()
      )
  }

  fun testFunctionalInterfacesKotlin_indirect() {
    lint()
      .files(
        kotlin(
            """
                    package test.pkg

                    import androidx.annotation.MainThread
                    import androidx.annotation.WorkerThread

                    class Test {
                        fun interface Foo {
                            @WorkerThread
                            fun foo()
                        }

                        @MainThread
                        fun uiMethod() {}

                        fun indirectUiMethod() = uiMethod()

                        fun test(foo: Foo) {}

                        fun testLambda() {
                            test { indirectUiMethod() } // ERROR
                        }

                        fun testMethodRef() {
                            test(this::indirectUiMethod) // ERROR
                        }
                    }
                """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/Test.kt:20: Error: Call must be from @{Main,Ui}Thread, but super method Foo.foo(…) is allowing @WorkerThread [ThreadConstraint]
                  test { indirectUiMethod() } // ERROR
                         ~~~~~~~~~~~~~~~~~~
          src/test/pkg/Test.kt:24: Error: Call must be from @{Main,Ui}Thread, but super method Foo.foo(…) is allowing @WorkerThread [ThreadConstraint]
                  test(this::indirectUiMethod) // ERROR
                       ~~~~~~~~~~~~~~~~~~~~~~
          2 errors
        """
          .trimIndent()
      )
  }

  /** Old tests from [WrongTheadInterproceduralDetectorTest] */
  fun testThreadingFromJava() {
    lint()
      .files(
        java(
            """
                    package test.pkg;

                    import androidx.annotation.UiThread;
                    import androidx.annotation.WorkerThread;

                    @SuppressWarnings({"UnnecessaryInterfaceModifier", "ClassNameDiffersFromFileName"})
                    @FunctionalInterface
                    public interface Runnable {
                      public abstract void run();
                    }

                    @SuppressWarnings({"Convert2MethodRef", "MethodMayBeStatic", "override", "ClassNameDiffersFromFileName", "InnerClassMayBeStatic"})
                    class Test {
                      @UiThread static void uiThreadStatic() { unannotatedStatic(); }
                      static void unannotatedStatic() { workerThreadStatic(); }
                      @WorkerThread static void workerThreadStatic() {}

                      @UiThread void uiThread() { unannotated(); }
                      void unannotated() { workerThread(); }
                      @WorkerThread void workerThread() {}

                      @UiThread void runUi() {}
                      void runIt(Runnable r) { r.run(); }
                      @WorkerThread void callRunIt() {
                        runIt(() -> runUi());
                      }

                      public static void main(String[] args) {
                        Test instance = new Test();
                        instance.uiThread();
                      }

                      interface It {
                        void run(Runnable r);
                      }

                      class A implements It {
                        @UiThread
                        public void run(Runnable r) { r.run(); }
                      }

                      class B implements It {
                        @WorkerThread
                        public void run(Runnable r) { r.run(); }
                      }

                      @UiThread
                      void a() {}

                      @WorkerThread
                      void b() {}

                      void runWithIt(It it, Runnable r) { it.run(r); }

                      void f() {
                        runWithIt(new A(), this::b);
                        runWithIt(new B(), this::a);
                      }

                      public static void invokeLater(@UiThread Runnable runnable) { /* place on queue to invoke on UiThread */ }

                      public static void invokeInBackground(@WorkerThread Runnable runnable) { /* place on queue to invoke on background thread */ }

                      @WorkerThread
                      void c() {}

                      @UiThread
                      void d() {}

                      void callInvokeLater() {
                        invokeLater(() -> c());
                        invokeLater(() -> d()); // Ok.
                      }

                      void callInvokeInBackground() {
                        invokeInBackground(() -> d());
                        invokeInBackground(() -> c()); // Ok.
                      }
                    }
                    """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .allowSystemErrors(true)
      .run()
      .expect(
        """
          src/test/pkg/Runnable.java:14: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
            @UiThread static void uiThreadStatic() { unannotatedStatic(); }
                                                     ~~~~~~~~~~~~~~~~~~~
          src/test/pkg/Runnable.java:18: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
            @UiThread void uiThread() { unannotated(); }
                                        ~~~~~~~~~~~~~
          src/test/pkg/Runnable.java:25: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
              runIt(() -> runUi());
              ~~~~~~~~~~~~~~~~~~~~
          src/test/pkg/Runnable.java:56: Error: Argument must allow calling run() from @{Main,Ui}Thread, but that call is requiring @WorkerThread [ThreadConstraint]
              runWithIt(new A(), this::b);
                                 ~~~~~~~
          src/test/pkg/Runnable.java:57: Error: Argument must allow calling run() from @WorkerThread, but that call is requiring @{Main,Ui}Thread [ThreadConstraint]
              runWithIt(new B(), this::a);
                                 ~~~~~~~
          src/test/pkg/Runnable.java:57: Error: Statement must run from @WorkerThread, incompatible with earlier code that must run from @{Main,Ui}Thread [ThreadConstraint]
              runWithIt(new B(), this::a);
              ~~~~~~~~~~~~~~~~~~~~~~~~~~~
          src/test/pkg/Runnable.java:71: Error: Call must be from @WorkerThread, but a super method is allowing @{Main,Ui}Thread [ThreadConstraint]
              invokeLater(() -> c());
                                ~~~
          src/test/pkg/Runnable.java:76: Error: Call must be from @{Main,Ui}Thread, but a super method is allowing @WorkerThread [ThreadConstraint]
              invokeInBackground(() -> d());
                                       ~~~
          8 errors
        """
          .trimIndent()
      )
  }

  fun testThreadingFromKotlin() {
    lint()
      .files(
        kotlin(
            """
                    package test.pkg

                    import androidx.annotation.UiThread
                    import androidx.annotation.WorkerThread

                    @Suppress("MemberVisibilityCanBePrivate")
                    class Test {

                      @UiThread fun uiThread() { unannotated() }
                      fun unannotated() { workerThread() }
                      @WorkerThread fun workerThread() {}

                      @UiThread fun runUi() {}
                      fun runIt(r: () -> Unit) { r() }
                      @WorkerThread fun callRunIt() { runIt({ runUi() }) }


                      interface It { fun run(r: () -> Unit) }

                      inner class A : It {
                        @UiThread
                        override fun run(r: () -> Unit) { r() }
                      }

                      inner class B : It {
                        @WorkerThread
                        override fun run(r: () -> Unit) { r() }
                      }

                      @UiThread
                      fun a() {}

                      @WorkerThread
                      fun b() {}

                      fun runWithIt(it: It, r: () -> Unit) { it.run(r) }

                      fun f() {
                        runWithIt(A(), this::b)
                        runWithIt(B(), this::a)
                      }

                      @WorkerThread
                      fun c() {}

                      @UiThread
                      fun d() {}

                      fun callInvokeLater() {
                        invokeLater({ c() })
                        invokeLater({ d() }) // Ok.
                      }

                      fun callInvokeInBackground() {
                        invokeInBackground({ d() })
                        invokeInBackground({ c() }) // Ok.
                      }

                      companion object {
                        @UiThread fun uiThreadStatic() { unannotatedStatic() }

                        fun unannotatedStatic() { workerThreadStatic() }

                        @WorkerThread fun workerThreadStatic() {}

                        @JvmStatic fun main(args: Array<String>) {
                          val instance = Test()
                          instance.uiThread()
                        }

                        fun invokeLater(@UiThread runnable: () -> Unit) { /* place on queue to invoke on UiThread */ }

                        fun invokeInBackground(@WorkerThread runnable: () -> Unit) { /* place on queue to invoke on background thread */ }
                      }
                    }
                    """
          )
          .indented(),
        SUPPORT_ANNOTATIONS_JAR,
      )
      .run()
      .expect(
        """
          src/test/pkg/Test.kt:9: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
            @UiThread fun uiThread() { unannotated() }
                                       ~~~~~~~~~~~~~
          src/test/pkg/Test.kt:15: Error: Call must be from @{Main,Ui}Thread, but context is allowing @WorkerThread [ThreadConstraint]
            @WorkerThread fun callRunIt() { runIt({ runUi() }) }
                                            ~~~~~~~~~~~~~~~~~~
          src/test/pkg/Test.kt:39: Error: Argument must run from @{Main,Ui}Thread, but is requiring @WorkerThread [ThreadConstraint]
              runWithIt(A(), this::b)
                             ~~~~~~~
          src/test/pkg/Test.kt:40: Error: Argument must run from @WorkerThread, but is requiring @{Main,Ui}Thread [ThreadConstraint]
              runWithIt(B(), this::a)
                             ~~~~~~~
          src/test/pkg/Test.kt:40: Error: Statement must run from @WorkerThread, incompatible with earlier code that must run from @{Main,Ui}Thread [ThreadConstraint]
              runWithIt(B(), this::a)
              ~~~~~~~~~~~~~~~~~~~~~~~
          src/test/pkg/Test.kt:50: Error: Call must be from @WorkerThread, but a super method is allowing @{Main,Ui}Thread [ThreadConstraint]
              invokeLater({ c() })
                            ~~~
          src/test/pkg/Test.kt:55: Error: Call must be from @{Main,Ui}Thread, but a super method is allowing @WorkerThread [ThreadConstraint]
              invokeInBackground({ d() })
                                   ~~~
          src/test/pkg/Test.kt:60: Error: Call must be from @WorkerThread, but context is allowing @{Main,Ui}Thread [ThreadConstraint]
              @UiThread fun uiThreadStatic() { unannotatedStatic() }
                                               ~~~~~~~~~~~~~~~~~~~
          8 errors
        """
          .trimIndent()
      )
  }
}

class AndroidThreadConstraintLatticeTest : ThreadConstraintLatticeTest<Thread>(threadLattice) {
  companion object {
    val threadLattice = ThreadConstraintDetector.ThreadConstraintLattice(Thread::class.java)
  }
}
