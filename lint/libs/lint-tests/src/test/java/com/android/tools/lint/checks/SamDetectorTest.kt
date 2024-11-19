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

package com.android.tools.lint.checks

import com.android.tools.lint.detector.api.Detector

class SamDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return SamDetector()
  }

  fun testStashingImplicitInstances() {
    lint()
      .files(
        kotlin(
            """
                @file:Suppress("RedundantSamConstructor", "MoveLambdaOutsideParentheses")

                package test.pkg

                fun test(handler: MyHandler, list: List<MyInterface>) {
                    handler.handle(MyInterface { println("hello") }) // OK
                    handler.handle({ println("hello") }) // OK
                    handler.stash(MyInterface { println("hello") }, list) // OK
                    handler.stash({ println("hello") }, list) // OK
                    handler.store({ println("hello") }) // OK
                    handler.delete({ println("hello") }) // OK
                    handler.delete(MyInterface { println("hello") }) // OK
                    handler.compareIdentity1({ println("hello") }) // OK
                    handler.compareIdentity2({ println("hello") }) // OK
                    handler.compareEquals1({ println("hello") }) // OK
                    handler.compareEquals2({ println("hello") }) // OK

                    val lambda = { println("hello") }
                    handler.stash(lambda, list) // OK
                    handler.store(lambda) // OK
                    handler.delete(lambda) // ERROR 1
                    handler.compareIdentity1(lambda) // ERROR 2
                    handler.compareIdentity2(lambda) // ERROR 3
                    handler.compareEquals1(lambda) // OK
                    handler.compareEquals2(lambda) // OK

                    @Suppress("CanBeVal", "JoinDeclarationAndAssignment")
                    var lambda2: () -> Unit
                    lambda2 = { println("hello") }
                    handler.stash(lambda2, list) // OK
                    handler.delete(lambda2) // ERROR 4

                    val lambda3: () -> Unit = { println("hello") }
                    handler.stash(lambda3, list) // OK
                    handler.delete(lambda3) // ERROR 5

                    handler.act({ println("hello") }) // OK
                    handler.act(::callback) // OK
                }

                fun callback() {}

                fun viewpost(view: android.view.View) {
                    view.postDelayed({ println ("Hello") }, 50)
                }
                """
          )
          .indented(),
        java(
            """
                package test.pkg;

                import java.util.List;

                public class JavaTest {
                    public void test(MyHandler handler, List<MyInterface> list) {
                        handler.handle(() -> System.out.println("hello")); // OK
                        handler.stash(() -> System.out.println("hello"), list); // OK
                    }
                }
                """
          )
          .indented(),
        java(
            """
                package test.pkg;

                public interface MyInterface {
                    void act();
                }
                """
          )
          .indented(),
        java(
            """
                package test.pkg;

                import java.util.List;

                public class MyHandler {
                    public void handle(MyInterface actor) {
                        actor.act();
                        System.out.println(actor);
                        MyInterface copy = actor;
                        System.out.println(copy);
                    }

                    public void stash(MyInterface actor, List<MyInterface> actors) {
                        actors.add(actor);
                    }

                    public void store(MyInterface actor) {
                        last = actor;
                    }

                    private MyInterface last;

                    private void removeActor(MyInterface actor) {
                    }

                    public fun delete(MyInterface actor) {
                        remove(actor);
                    }

                    public void compareIdentity1(MyInterface actor) {
                        if (actor == last) {
                            System.out.println("last");
                        }
                    }

                    public void compareIdentity2(MyInterface actor) {
                        if (actor != last) {
                            System.out.println("not last");
                        }
                    }

                    public void compareEquals1(MyInterface actor) {
                        if (actor.equals(last)) {
                            System.out.println("last");
                        }
                    }

                    public void compareEquals2(MyInterface actor) {
                        if (last.equals(actor)) {
                            System.out.println("last");
                        }
                    }

                    public void act(MyInterface actor) {
                        if (actor != null) {
                            actor.act();
                        }
                        //noinspection StatementWithEmptyBody
                        if (actor == null) {
                        } else {
                            actor.act();
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
        src/test/pkg/test.kt:21: Warning: Implicit new MyInterface instance being passed to method which ends up checking instance equality; this can lead to subtle bugs [ImplicitSamInstance]
            handler.delete(lambda) // ERROR 1
                           ~~~~~~
        src/test/pkg/test.kt:22: Warning: Implicit new MyInterface instance being passed to method which ends up checking instance equality; this can lead to subtle bugs [ImplicitSamInstance]
            handler.compareIdentity1(lambda) // ERROR 2
                                     ~~~~~~
        src/test/pkg/test.kt:23: Warning: Implicit new MyInterface instance being passed to method which ends up checking instance equality; this can lead to subtle bugs [ImplicitSamInstance]
            handler.compareIdentity2(lambda) // ERROR 3
                                     ~~~~~~
        src/test/pkg/test.kt:31: Warning: Implicit new MyInterface instance being passed to method which ends up checking instance equality; this can lead to subtle bugs [ImplicitSamInstance]
            handler.delete(lambda2) // ERROR 4
                           ~~~~~~~
        src/test/pkg/test.kt:35: Warning: Implicit new MyInterface instance being passed to method which ends up checking instance equality; this can lead to subtle bugs [ImplicitSamInstance]
            handler.delete(lambda3) // ERROR 5
                           ~~~~~~~
        0 errors, 5 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/test.kt line 21: Explicitly create MyInterface instance:
        @@ -18 +18
        -     val lambda = { println("hello") }
        +     val lambda = MyInterface { println("hello") }
        Fix for src/test/pkg/test.kt line 22: Explicitly create MyInterface instance:
        @@ -18 +18
        -     val lambda = { println("hello") }
        +     val lambda = MyInterface { println("hello") }
        Fix for src/test/pkg/test.kt line 23: Explicitly create MyInterface instance:
        @@ -18 +18
        -     val lambda = { println("hello") }
        +     val lambda = MyInterface { println("hello") }
        Fix for src/test/pkg/test.kt line 31: Explicitly create MyInterface instance:
        @@ -29 +29
        -     lambda2 = { println("hello") }
        +     lambda2 = MyInterface { println("hello") }
        Fix for src/test/pkg/test.kt line 35: Explicitly create MyInterface instance:
        @@ -33 +33
        -     val lambda3: () -> Unit = { println("hello") }
        +     val lambda3: () -> Unit = MyInterface { println("hello") }
        """
      )
  }

  fun testHandler() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.os.Handler
            import android.widget.TextView

            fun callback() {}

            fun test(handler: Handler, view: TextView, ok: Runnable) {
                handler.post(::callback) // OK 1
                handler.removeCallbacks(::callback) // ERROR 1
                view.post(::callback) // OK 2
                view.removeCallbacks { callback() } // ERROR 2
                view.post(ok) // OK 3
                view.removeCallbacks(ok) // OK 4
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:10: Warning: Implicit new Runnable instance being passed to method which ends up checking instance equality; this can lead to subtle bugs [ImplicitSamInstance]
            handler.removeCallbacks(::callback) // ERROR 1
                                    ~~~~~~~~~~
        src/test/pkg/test.kt:12: Warning: { callback() } is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { callback() } and post and remove the runnable val instead. [ImplicitSamInstance]
            view.removeCallbacks { callback() } // ERROR 2
                                 ~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
      )
      .expectFixDiffs("")
  }

  fun testAidlCompileTestCase() {
    lint()
      .files(
        kotlin(
            """
                package test.pkg
                abstract class AidlCompile {
                    abstract val execOperations: ExecOperations
                    val path = "/sdcard/path"
                    fun doTaskAction() {
                        val processor = GradleProcessExecutor(execOperations::exec)
                    }
                }
                """
          )
          .indented(),
        java(
          """
                package test.pkg;
                import java.util.function.Function;
                public class GradleProcessExecutor {
                    private final Function<Action<? super ExecSpec>, ExecResult> execOperations;

                    // Lambda is stored but not compared
                    @SuppressWarnings("ImplicitSamInstance")
                    public GradleProcessExecutor(
                            Function<Action<? super ExecSpec>, ExecResult> execOperations) {
                        this.execOperations = execOperations;
                    }
                }
                """
        ),
        java(
          """
                package test.pkg;
                public interface Action<T> {
                    void execute(T var1);
                }
                """
        ),
        java(
          """
                package test.pkg;
                public interface ExecOperations {
                    ExecResult exec(Action<? super ExecSpec> var1);
                    ExecResult javaexec(Action<? super ExecSpec> var1);
                }
                """
        ),
        java(
          """
                package test.pkg;
                public interface ExecResult { }
                """
        ),
        java(
          """
                package test.pkg;
                import java.util.List;
                public interface ExecSpec {
                    void setCommandLine(List<String> var1);
                }
                """
        ),
      )
      .run()
      .expectClean()
  }

  fun testPostRemove() {
    // Regression test for
    // 376498180: kotlin android.os.Handler removeCallbacks Runnable
    lint()
      .files(
        kotlin(
            """
            @file:Suppress("unused", "RedundantExplicitType")

            package test.pkg

            import android.os.Handler

            class Test {
                private lateinit var handler: Handler
                private lateinit var connectRunnable: () -> Unit

                private lateinit var connectRunnable2: Runnable

                fun testError_referencedFieldLambda() {
                    handler.postDelayed(connectRunnable, 100) // ERROR 1
                    handler.removeCallbacks(connectRunnable) // ERROR 1
                }

                fun testError_referencedParameterLambda(handler: Handler, connectRunnable: () -> Unit) {
                    handler.postDelayed(connectRunnable, 100) // ERROR 2
                    handler.removeCallbacks(connectRunnable) // ERROR 2
                }

                fun testError_referencedLocalVariableLambda(handler: Handler, connectRunnable: () -> Unit) {
                    val same = connectRunnable
                    handler.postDelayed(same, 100) // ERROR 3
                    handler.removeCallbacks(same) // ERROR 3
                }

                fun testError_referencedLocalVariableLambda(handler: Handler, connectRunnable: () -> Unit) {
                    val same = connectRunnable
                    handler.postDelayed(same, 100) // ERROR 4
                    handler.removeCallbacks(same) // ERROR 4
                }

                fun testError_onlyRemoval(connectRunnable: () -> Unit) {
                    handler.removeCallbacks(connectRunnable) // ERROR 5
                }

                fun testError_multiplePosts(type: Int, connectRunnable: () -> Unit, unrelated: () -> Unit) {
                    if (type == 1) {
                        handler.post(connectRunnable) // ERROR 6
                    } else if (type == 2) {
                        handler.postAtTime(connectRunnable, 0L) // ERROR 6
                    } else if (type == 3) {
                        handler.postAtFrontOfQueue(connectRunnable) // ERROR 6
                    } else {
                        handler.postAtFrontOfQueue(unrelated) // OK
                    }
                    handler.removeCallbacks(connectRunnable) // ERROR 6
                }

                fun testError_inlinedLambda(connectRunnable: () -> Unit) {
                    handler.removeCallbacks(Runnable { connectRunnable() }) // ERROR 7
                }

                class DifferentMethods(val handler: Handler, var lambdaField: () -> Unit) {
                  fun postMethod() {
                      handler.post(lambdaField) // ERROR 8
                  }

                  fun removeMethod() {
                    handler.removeCallbacks(lambdaField) // ERROR 8
                  }
                }

                fun MutableList<Runnable>.addItem(s: Runnable) { add(s) }
                fun MutableList<Runnable>.removeItem(s: Runnable) { remove(s) }

                fun testExtensionMethods(lambda: () -> Unit, list: MutableList<Runnable>) {
                    list.addItem(lambda) // ERROR 9
                    list.removeItem(lambda) // ERROR 9
                }

                fun testOk1() {
                    handler.postDelayed(connectRunnable2, 100) // OK
                    handler.removeCallbacks(connectRunnable2) // OK 1
                }

                fun testOk2(handler: Handler, runnable: Runnable) {
                    handler.postDelayed(runnable, 100) // OK
                    handler.removeCallbacks(runnable) // OK 2
                }

                fun testOk3(handler: Handler, connectRunnable: () -> Unit) {
                    val same: Runnable = Runnable { connectRunnable() }
                    handler.postDelayed(same, 100) // OK
                    handler.removeCallbacks(same) // OK 3
                }

                fun testOk4(handler: Handler, connectRunnable: () -> Unit, unrelated: Runnable) {
                    val same: Runnable = Runnable { connectRunnable() }
                    handler.postDelayed(same, 100) // OK
                    handler.removeCallbacks(unrelated) // OK 3
                }

                fun testOk_removeNotSymmetric(list1: MutableList<String>, list2: MutableList<String>) {
                    list2.removeIf { it.length > 3 } // OK
                    list1.removeAll { true } // OK
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.kt:15: Warning: connectRunnable is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { connectRunnable() } and post and remove the runnable val instead. [ImplicitSamInstance]
                handler.removeCallbacks(connectRunnable) // ERROR 1
                                        ~~~~~~~~~~~~~~~
            src/test/pkg/Test.kt:14: Different instance than the one for removeCallbacks() due to SAM conversion; wrap with a shared Runnable
                handler.postDelayed(connectRunnable, 100) // ERROR 1
                                    ~~~~~~~~~~~~~~~
        src/test/pkg/Test.kt:20: Warning: connectRunnable is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { connectRunnable() } and post and remove the runnable val instead. [ImplicitSamInstance]
                handler.removeCallbacks(connectRunnable) // ERROR 2
                                        ~~~~~~~~~~~~~~~
            src/test/pkg/Test.kt:19: Different instance than the one for removeCallbacks() due to SAM conversion; wrap with a shared Runnable
                handler.postDelayed(connectRunnable, 100) // ERROR 2
                                    ~~~~~~~~~~~~~~~
        src/test/pkg/Test.kt:26: Warning: same is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { same() } and post and remove the runnable val instead. [ImplicitSamInstance]
                handler.removeCallbacks(same) // ERROR 3
                                        ~~~~
            src/test/pkg/Test.kt:25: Different instance than the one for removeCallbacks() due to SAM conversion; wrap with a shared Runnable
                handler.postDelayed(same, 100) // ERROR 3
                                    ~~~~
        src/test/pkg/Test.kt:32: Warning: same is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { same() } and post and remove the runnable val instead. [ImplicitSamInstance]
                handler.removeCallbacks(same) // ERROR 4
                                        ~~~~
            src/test/pkg/Test.kt:31: Different instance than the one for removeCallbacks() due to SAM conversion; wrap with a shared Runnable
                handler.postDelayed(same, 100) // ERROR 4
                                    ~~~~
        src/test/pkg/Test.kt:36: Warning: connectRunnable is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { connectRunnable() } and post and remove the runnable val instead. [ImplicitSamInstance]
                handler.removeCallbacks(connectRunnable) // ERROR 5
                                        ~~~~~~~~~~~~~~~
        src/test/pkg/Test.kt:49: Warning: connectRunnable is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { connectRunnable() } and post and remove the runnable val instead. [ImplicitSamInstance]
                handler.removeCallbacks(connectRunnable) // ERROR 6
                                        ~~~~~~~~~~~~~~~
            src/test/pkg/Test.kt:41: Different instance than the one for removeCallbacks() due to SAM conversion; wrap with a shared Runnable
                    handler.post(connectRunnable) // ERROR 6
                                 ~~~~~~~~~~~~~~~
            src/test/pkg/Test.kt:43: Different instance than the one for removeCallbacks() due to SAM conversion; wrap with a shared Runnable
                    handler.postAtTime(connectRunnable, 0L) // ERROR 6
                                       ~~~~~~~~~~~~~~~
            src/test/pkg/Test.kt:45: Different instance than the one for removeCallbacks() due to SAM conversion; wrap with a shared Runnable
                    handler.postAtFrontOfQueue(connectRunnable) // ERROR 6
                                               ~~~~~~~~~~~~~~~
        src/test/pkg/Test.kt:53: Warning: This argument is a new instance so removeCallbacks will not remove anything [ImplicitSamInstance]
                handler.removeCallbacks(Runnable { connectRunnable() }) // ERROR 7
                                        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/Test.kt:62: Warning: lambdaField is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { lambdaField() } and post and remove the runnable val instead. [ImplicitSamInstance]
                handler.removeCallbacks(lambdaField) // ERROR 8
                                        ~~~~~~~~~~~
            src/test/pkg/Test.kt:58: Different instance than the one for removeCallbacks() due to SAM conversion; wrap with a shared Runnable
                  handler.post(lambdaField) // ERROR 8
                               ~~~~~~~~~~~
        src/test/pkg/Test.kt:71: Warning: lambda is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { lambda() } and add and remove the runnable val instead. [ImplicitSamInstance]
                list.removeItem(lambda) // ERROR 9
                                ~~~~~~
        0 errors, 9 warnings
        """
      )
      .expectFixDiffs(
        """

        """
      )
  }

  fun testRemoveMethod() {
    lint()
      .files(
        kotlin(
            """
            @file:Suppress("unused", "RedundantExplicitType")

            package test.pkg

            import java.util.function.Predicate

            class MyOwnHandler {
                private val predicates: MutableList<Predicate<Boolean>> = mutableListOf()

                fun removePredicate(predicate: Predicate<Boolean>) {
                    predicates.remove(predicate)
                }

                fun addPredicate(predicate: Predicate<Boolean>) {
                    predicates.add(predicate)
                }

                fun allMatch(condition: Boolean): Boolean {
                    return predicates.all { it.test(condition) }
                }
            }

            fun handlerTest(predicate1: (Boolean) -> Boolean, data: List<Boolean>) {
                val handler = MyOwnHandler()
                handler.addPredicate { predicate1(it) }
                handler.addPredicate(predicate1)
                val ok = data.all { handler.allMatch(it) }

                handler.removePredicate(predicate1) // ERROR 1
                handler.removePredicate { predicate1(it) } // ERROR 2
            }

            fun listRemoval(predicate: (Boolean) -> Boolean, list: MutableList<Predicate<Boolean>>, predicate2: Predicate<Boolean>) {
                list.remove(predicate) // ERROR 3
                list.remove(predicate2) // OK
            }

            fun testFixableError(handler: MyOwnHandler) {
                val lambda: (Boolean)->Boolean = {
                    !it
                }
                handler.addPredicate(lambda)
                handler.removePredicate(lambda) // ERROR 4
            }

            fun testFixableError2(handler: MyOwnHandler) {
                // Check that the quickfix regex match can span lines
                val lambda: (Boolean)->Boolean
                  = { !it }
                handler.removePredicate(lambda) // ERROR 5
            }

            fun testNoTypeArgs(view: android.widget.TextView) {
                // Make sure quickfix handles a missing type reference
                val runner = {}
                view.removeCallbacks(runner) // ERROR 6
            }

            open class RangeConstraint
            class FloatRangeConstraint : RangeConstraint() {
                fun remove(other: RangeConstraint): RangeConstraint? {
                    return remove(FloatRangeConstraint()) // OK
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/MyOwnHandler.kt:29: Warning: predicate1 is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val predicate = Predicate<Boolean> { predicate1() } and add and remove the predicate val instead. [ImplicitSamInstance]
            handler.removePredicate(predicate1) // ERROR 1
                                    ~~~~~~~~~~
        src/test/pkg/MyOwnHandler.kt:30: Warning: { predicate1(it) } is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val predicate = Predicate<Boolean> { predicate1(it) } and add and remove the predicate val instead. [ImplicitSamInstance]
            handler.removePredicate { predicate1(it) } // ERROR 2
                                    ~~~~~~~~~~~~~~~~~~
        src/test/pkg/MyOwnHandler.kt:34: Warning: predicate is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val object = Object { predicate() } and add and remove the object val instead. [ImplicitSamInstance]
            list.remove(predicate) // ERROR 3
                        ~~~~~~~~~
        src/test/pkg/MyOwnHandler.kt:43: Warning: lambda is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val predicate = Predicate<Boolean> { lambda() } and add and remove the predicate val instead. [ImplicitSamInstance]
            handler.removePredicate(lambda) // ERROR 4
                                    ~~~~~~
        src/test/pkg/MyOwnHandler.kt:50: Warning: lambda is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val predicate = Predicate<Boolean> { lambda() } and add and remove the predicate val instead. [ImplicitSamInstance]
            handler.removePredicate(lambda) // ERROR 5
                                    ~~~~~~
        src/test/pkg/MyOwnHandler.kt:56: Warning: runner is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { runner() } and post and remove the runnable val instead. [ImplicitSamInstance]
            view.removeCallbacks(runner) // ERROR 6
                                 ~~~~~~
        0 errors, 6 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/MyOwnHandler.kt line 43: Explicitly create Predicate<Boolean> instance:
        @@ -39 +39
        -     val lambda: (Boolean)->Boolean = {
        +     val lambda = Predicate<Boolean> {
        Fix for src/test/pkg/MyOwnHandler.kt line 50: Explicitly create Predicate<Boolean> instance:
        @@ -48 +48
        -     val lambda: (Boolean)->Boolean
        -       = { !it }
        +     val lambda = Predicate<Boolean> { !it }
        Fix for src/test/pkg/MyOwnHandler.kt line 56: Explicitly create Runnable instance:
        @@ -55 +55
        -     val runner = {}
        +     val runner = Runnable {}
        """
      )
  }

  fun testNoRemoval() {
    // Using a lambda here is fine
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.os.Handler
            import android.os.Looper

            const val UPDATE_CADEANCE_MS = 10000L

            class BackgroundDataSourceService {
                private val handler = Handler(Looper.getMainLooper())

                fun onComplicationActivated() {
                    backgroundUpdate()
                }

                private fun backgroundUpdate() {
                    handler.postDelayed(this::backgroundUpdate, UPDATE_CADEANCE_MS) // OK
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testNonFunctionalInterface() {
    // Example from AndroidX
    lint()
      .files(
        kotlin(
            """
            @file:Suppress("unused")
            package test.pkg.sub

            sealed class LoadState
            enum class LoadType
            class AsyncPagedListDiffer(val loadStateListeners: MutableList<(LoadType, LoadState) -> Unit>) {
                fun removeLoadStateListener(listener: (LoadType, LoadState) -> Unit) {
                    loadStateListeners.remove(listener) // OK
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testNonFunctionalInterface2() {
    // Example from AndroidX
    lint()
      .files(
        kotlin(
            """
            package test.pkg.sub

            import java.util.concurrent.CopyOnWriteArrayList

            class CombinedLoadStates
            internal class MutableCombinedLoadStateCollection {
                private val listeners = CopyOnWriteArrayList<(CombinedLoadStates) -> Unit>()
                fun addListener(listener: (CombinedLoadStates) -> Unit) {
                    listeners.add(listener)
                }
                fun removeListener(listener: (CombinedLoadStates) -> Unit) {
                    listeners.remove(listener)
                }
            }

            abstract class PagingDataPresenter<T : Any> {
                private val combinedLoadStatesCollection =
                    MutableCombinedLoadStateCollection().apply {
                        //cachedPagingData?.cachedEvent()?.let { set(it.sourceLoadStates, it.mediatorLoadStates) }
                    }
                private val onPagesUpdatedListeners = CopyOnWriteArrayList<() -> Unit>()

                fun addLoadStateListener(listener: (@JvmSuppressWildcards CombinedLoadStates) -> Unit) {
                    combinedLoadStatesCollection.addListener(listener)
                }

                fun removeLoadStateListener(
                    listener: (@JvmSuppressWildcards CombinedLoadStates) -> Unit
                ) {
                    combinedLoadStatesCollection.removeListener(listener)
                }

                fun addOnPagesUpdatedListener(listener: () -> Unit) {
                    onPagesUpdatedListeners.add(listener)
                }

                fun removeOnPagesUpdatedListener(listener: () -> Unit) {
                    onPagesUpdatedListeners.remove(listener)
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testWear() {
    lint()
      .files(
        java(
            """
            package test.pkg;

            import android.app.AlarmManager;

            class UpdateScheduler implements AlarmManager.OnAlarmListener {
                private final Clock mClock;
                private final AlarmManager mAlarmManager;

                UpdateScheduler(AlarmManager alarmManager, Clock clock) {
                    this.mAlarmManager = alarmManager;
                    this.mClock = clock;
                }

                @Override
                public void onAlarm() {
                }

                interface Clock {
                    long getElapsedTimeMillis();
                }
            }
            """
          )
          .indented(),
        kotlin(
            """
            package test.pkg

            import android.app.AlarmManager
            import android.content.Context
            import android.os.SystemClock

            class TileUiClient(val context: Context) {
                private val updateScheduler =
                    UpdateScheduler(
                        context.getSystemService(AlarmManager::class.java),
                        SystemClock::elapsedRealtime
                    )
            }
            """
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testRemoveSecondArg() {
    // Make sure the lambda parameter doesn't have to be the first argument
    lint()
      .files(
        kotlin(
            """
            @file:Suppress("unused", "RedundantExplicitType")

            package test.pkg

            import java.util.function.Predicate

            class Handler {
                fun removePredicate(taskId: Int, predicate: Predicate<Boolean>) {
                }
                fun addPredicate(taskId: Int, save: Boolean, predicate: Predicate<Boolean>) {
                }
            }

            fun handlerTest(predicate1: (Boolean) -> Boolean, data: List<Boolean>) {
                val handler = Handler()
                handler.addPredicate(0, true) { predicate1(it) }
                handler.removePredicate(0, predicate1) // ERROR 1
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/Handler.kt:17: Warning: predicate1 is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val predicate = Predicate<Boolean> { predicate1() } and add and remove the predicate val instead. [ImplicitSamInstance]
            handler.removePredicate(0, predicate1) // ERROR 1
                                       ~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testUnregisterTerminology() {
    // Make sure we also handle register/unregister terminology and start/stop
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.app.AppOpsManager

            class Test {
              private val list = mutableListOf<Runnable>()
              fun registerAntennaInfoListener(runnable: Runnable) { list.add(runnable) }
              fun unregisterAntennaInfoListener(runnable: Runnable) { list.remove(runnable) }
              fun test(lambda: ()->Unit) {
                registerAntennaInfoListener(lambda)
                unregisterAntennaInfoListener(lambda) // ERROR 1
              }
            }

            fun testStartStop(manager: AppOpsManager,  ) {
                val lambda: (String, String)->Unit = { _,_ -> }
                manager.startWatchingMode("", "", lambda)
                manager.stopWatchingMode(lambda) // ERROR 2
                val lambda2: (String, Int, String, Boolean)->Unit = { _, _, _, _ -> }
                manager.stopWatchingActive(lambda2) // ERROR 3
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/Test.kt:11: Warning: lambda is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val runnable = Runnable { lambda() } and add and remove the runnable val instead. [ImplicitSamInstance]
            unregisterAntennaInfoListener(lambda) // ERROR 1
                                          ~~~~~~
        src/test/pkg/Test.kt:18: Warning: lambda is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val onOpChangedListener = OnOpChangedListener { lambda() } and add and remove the onOpChangedListener val instead. [ImplicitSamInstance]
            manager.stopWatchingMode(lambda) // ERROR 2
                                     ~~~~~~
        src/test/pkg/Test.kt:20: Warning: lambda2 is an implicit SAM conversion, so the instance you are removing here will not match anything. To fix this, use for example val onOpActiveChangedListener = OnOpActiveChangedListener { lambda2() } and add and remove the onOpActiveChangedListener val instead. [ImplicitSamInstance]
            manager.stopWatchingActive(lambda2) // ERROR 3
                                       ~~~~~~~
        0 errors, 3 warnings
        """
      )
  }
}
