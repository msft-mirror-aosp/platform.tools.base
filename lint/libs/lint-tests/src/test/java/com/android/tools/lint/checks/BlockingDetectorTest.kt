/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.tools.lint.checks.fx.AssumptionTableBuilder.Companion.build
import com.android.tools.lint.checks.fx.result.Type
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.static
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.google.common.truth.Truth

@Suppress("LintDocExample")
class BlockingDetectorTest : AbstractCheckTest() {
  override fun getDetector() = BlockingDetector()

  override fun getIssues() = listOf(BlockingDetector.ISSUE)

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

  fun `test simple example`() {
    lint()
        .files(
            kotlin(
                    """
                    annotation class Blocking // TODO get rid of
                    annotation class NonBlocking // TODO get rid of

                    @Blocking
                    fun doSomethingBlocking() { }

                    fun nonBlocking(): () -> Unit = { doSomethingBlocking() }

                    fun maybeBlocking(doIt: () -> Unit) = doIt()

                    @NonBlocking // we want to alert that main() will block
                    fun main() = maybeBlocking(nonBlocking())
                    """
                        .trimIndent()
                )
                .indented()
        )
        .run()
        .expect(
            """
            src/Blocking.kt:12: Error: Call blocks in a context not allowed to block [BlockingMethod]
            fun main() = maybeBlocking(nonBlocking())
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            1 error
            """
                .trimIndent()
        )
  }

  fun `test inheritance`() {
    lint()
        .files(
            kotlin(
                    """
                    annotation class Blocking // TODO get rid of
                    annotation class NonBlocking // TODO get rid of

                    interface UnannotatedIntf {
                      fun doUnannotated()
                    }

                    interface NonBlockingIntf {
                      @NonBlocking fun doAnnotatedNonBlocking()
                    }

                    interface BlockingIntf {
                      @Blocking fun doAnnotatedBlocking()
                    }

                    class Impl: UnannotatedIntf, NonBlockingIntf, BlockingIntf {
                      override fun doUnannotated() = block() // OK

                      override fun doAnnotatedBlocking() = block() // OK

                      override fun doAnnotatedNonBlocking() = block() // ERROR

                      @Blocking private fun block() { }
                    }

                    class RelaxingImpl: BlockingIntf {
                      // it's ok for subclass to strengthen its promise, or equivalently,
                      // relax its requirement, than superclass
                      @NonBlocking override fun doAnnotatedBlocking() { }
                    }
                    """
                        .trimIndent()
                )
                .indented()
        )
        .run()
        .expect(
            """
            src/Blocking.kt:21: Error: Call must not block, as required by super method NonBlockingIntf.doAnnotatedNonBlocking(…) [BlockingMethod]
              override fun doAnnotatedNonBlocking() = block() // ERROR
                                                      ~~~~~~~
            1 error
            """
                .trimIndent()
        )
  }

  fun `test external assumptions available`() {
    getTempDir().absolutePath.let { assumptionsPath ->
      System.setProperty(BlockingDetector.ASSUMPTIONS_PATH, assumptionsPath)
      Truth.assertThat(System.getProperty(BlockingDetector.ASSUMPTIONS_PATH)).isEqualTo(assumptionsPath)

      val assumptions =
          BlockingDetector.statusLattice.build {
            static<Long>(Thread::sleep) assumedAs given(Type.Long) { concreteEffect = BlockingDetector.Status.MaybeBlocking }
          }
      detector.savePartialResults(assumptionsPath, assumptions)
    }

    lint()
        .files(
            kotlin(
                    """
                    annotation class NonBlocking // TODO get rid of

                    @NonBlocking
                    fun nonBlocking1() {
                        doSomethingThenSleep()
                    }

                    @NonBlocking
                    fun nonBlocking2(): Int = 42.also {
                        doSomethingThenSleep()
                    }

                    @NonBlocking
                    fun nonBlocking3() {
                        val t = ::doSomethingThenSleep // ok
                        println("Done")
                    }

                    fun doSomethingThenSleep() {
                        Thread.sleep(10)
                    }
                    """
                        .trimIndent()
                )
                .indented()
        )
        .run()
        .expect(
            """
            src/NonBlocking.kt:5: Error: Call blocks in a context not allowed to block [BlockingMethod]
                doSomethingThenSleep()
                ~~~~~~~~~~~~~~~~~~~~~~
            src/NonBlocking.kt:9: Error: Call blocks in a context not allowed to block [BlockingMethod]
            fun nonBlocking2(): Int = 42.also {
                                         ^
            2 errors
            """
                .trimIndent()
        )
  }
}
