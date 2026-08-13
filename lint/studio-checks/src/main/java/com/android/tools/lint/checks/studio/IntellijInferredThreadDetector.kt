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

import com.android.annotations.concurrency.AnyThread
import com.android.annotations.concurrency.Slow
import com.android.annotations.concurrency.UiThread
import com.android.annotations.concurrency.WorkerThread
import com.android.tools.lint.checks.ThreadConstraintDetector
import com.android.tools.lint.checks.ThreadConstraintDetector.Companion.assumeCommonConcurrencySignatures
import com.android.tools.lint.checks.fx.AssumptionTableBuilder.Companion.build
import com.android.tools.lint.checks.fx.get
import com.android.tools.lint.checks.fx.invoke
import com.android.tools.lint.checks.fx.result.ClassId
import com.android.tools.lint.checks.fx.result.Type
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.rawStatic
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.rawVirtual
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.virtual
import com.android.tools.lint.checks.fx.result.plus
import com.android.tools.lint.checks.studio.IntellijInferredThreadDetector.Thread
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.util.ThrowableRunnable
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.util.concurrent.Callable
import java.util.concurrent.Future
import kotlinx.collections.immutable.plus
import org.jetbrains.uast.UAnnotation

class IntellijInferredThreadDetector : ThreadConstraintDetector<Thread>(lattice, assumptions) {

  override val violationIssue = THREAD
  override val unsatisfiableConstraintIssue = UNSATISFIABLE_CONSTRAINT

  override fun parse(ann: UAnnotation) =
    when (ann.qualifiedName) {
      AnyThread::class.java.canonicalName,
      "androidx.annotation.AnyThread" -> lattice.AnyThread
      RequiresBackgroundThread::class.java.canonicalName,
      Slow::class.java.canonicalName,
      WorkerThread::class.java.canonicalName,
      "androidx.annotation.WorkerThread" -> lattice.of(Thread.Slow)
      UiThread::class.java.canonicalName,
      RequiresEdt::class.java.canonicalName,
      "androidx.annotation.UiThread",
      "androidx.annotation.MainThread" -> lattice.of(Thread.Ui)
      else -> null
    }

  /**
   * Thread groups we track in the Android Studio code base.
   *
   * As far as thread compatibility is concerned, `@Slow` and `@WorkerThread` are equivalent, so they have the same internal representation,
   * simplifying a preorder to a partial order. UX wise, a small price we're currently paying is that when reporting errors back to the
   * user, we're displaying `@{Slow,WorkerThread}` instead of the exact one that they originally write.
   */
  enum class Thread {
    Ui,
    Slow;

    override fun toString() =
      when (this) {
        Ui -> "`@UiThread`"
        Slow -> "`@{Slow,WorkerThread}`"
      }
  }

  companion object {

    private val Impl = Implementation(IntellijInferredThreadDetector::class.java, Scope.JAVA_FILE_SCOPE)

    @JvmField
    val THREAD =
      Issue.create(
        id = "WrongThread",
        briefDescription = "Wrong Thread",
        explanation =
          """
                Ensures that a method which expects to be called on a specific thread, is \
                actually called from that thread. For example, calls on methods in widgets \
                should always be made on the UI thread.
                """,
        //noinspection LintImplUnexpectedDomain
        moreInfo = "http://go/do-not-freeze",
        category = UI_RESPONSIVENESS,
        priority = 6,
        severity = Severity.ERROR,
        enabledByDefault = true,
        implementation = Impl,
      )

    @JvmField
    val UNSATISFIABLE_CONSTRAINT =
      Issue.create(
        id = "UnsatisfiableThreadConstraint",
        briefDescription = "Unsatisfiable Thread Requirement",
        explanation =
          """
                Ensures that different parts of an expression have compatible thread requirements. \
                This check at the moment may have a false positive, not recognizing that some \
                branches of a conditional are safe to run following a condition that refines what \
                is known about the current thread.
                """,
        //noinspection LintImplUnexpectedDomain
        moreInfo = "http://go/do-not-freeze",
        category = Category.CORRECTNESS,
        // TODO(b/379742474) We make this low warning for now, since false positives are quite
        //  likely, especially from conditional branches
        priority = 3,
        severity = Severity.WARNING,
        enabledByDefault = true,
        implementation = Impl,
      )

    private val lattice = ThreadConstraintLattice(Thread::class.java)

    private val assumptions by
      lazy(LazyThreadSafetyMode.NONE) {
        lattice.build {
          assumeCommonJavaAndKotlinSignatures()
          assumeCommonConcurrencySignatures(background = lattice.of(Thread.Slow))

          // `Application.invokeLater` overloadings
          run {
            virtual<Runnable>(Application::invokeLater) assumedAs
              forAll<Runnable> { runnable ->
                given(Application::class(), runnable) { constraint += runnable[Runnable::run] to lattice.of(Thread.Ui) }
              }
            virtual<_, Condition<*>>(Application::invokeLater) assumedAs
              forAll<Runnable> { runnable ->
                given(Application::class(), runnable, Condition::class(Type.WildCard)) {
                  constraint += runnable[Runnable::run] to lattice.of(Thread.Ui)
                }
              }
            virtual<_, ModalityState>(Application::invokeLater) assumedAs
              forAll<Runnable> { runnable ->
                given(Application::class(), runnable, ModalityState::class()) {
                  constraint += runnable[Runnable::run] to lattice.of(Thread.Ui)
                }
              }
            virtual<_, _, _>(Application::invokeLater) assumedAs
              forAll<Runnable> { runnable ->
                given(Application::class(), runnable, ModalityState::class(), Condition::class(Type.WildCard)) {
                  constraint += runnable[Runnable::run] to lattice.of(Thread.Ui)
                }
              }
          }

          // `Application.executeOnPooledThread` overloadings
          run {
            virtual<Runnable>(Application::executeOnPooledThread) assumedAs
              forAll<Runnable> { runnable ->
                given(Application::class(), runnable) {
                  range = Future::class(Type.Unit)
                  constraint += runnable[Runnable::run] to lattice.of(Thread.Slow)
                }
              }
            virtual<Callable<Any>>(Application::executeOnPooledThread) assumedAs
              forAll { a ->
                forAll(Callable::class(a)) { callable ->
                  given(Application::class(), callable) {
                    range = Future::class(a)
                    constraint += callable[Callable<*>::call] to lattice.of(Thread.Slow)
                  }
                }
              }
          }

          // `Application.invokeAndWait` overloadings. Unlike `invokeLater`, the call blocks, but blocking is not this detector's concern;
          // like `invokeLater`, it's safe from any thread, and the runnable executes on the EDT.
          run {
            virtual<Runnable>(Application::invokeAndWait) assumedAs
              forAll<Runnable> { runnable ->
                given(Application::class(), runnable) { constraint += runnable[Runnable::run] to lattice.of(Thread.Ui) }
              }
            virtual<_, ModalityState>(Application::invokeAndWait) assumedAs
              forAll<Runnable> { runnable ->
                given(Application::class(), runnable, ModalityState::class()) {
                  constraint += runnable[Runnable::run] to lattice.of(Thread.Ui)
                }
              }
          }

          // Other EDT-marshalling entry points, all safe from any thread with the runnable executing on the EDT.
          // (`Application.runWriteAction` and `WriteAction.run`/`compute` are deliberately left out: they assert being *already* on the
          // EDT, which existing annotations check with better locations than an assumption's argument constraint would.)
          run {
            val runnableId = ClassId.of<Runnable>()
            val modalityStateId = ClassId.of<ModalityState>()
            val dumbServiceFqn = "com.intellij.openapi.project.DumbService"

            listOf(
              rawStatic("invokeLater", "javax.swing.SwingUtilities", runnableId),
              rawStatic("invokeAndWait", "javax.swing.SwingUtilities", runnableId),
              rawStatic("invokeLater", "java.awt.EventQueue", runnableId),
              rawStatic("invokeAndWait", "java.awt.EventQueue", runnableId),
              rawStatic("invokeLaterIfNeeded", "com.intellij.util.ui.UIUtil", runnableId),
              rawStatic("invokeAndWaitIfNeeded", "com.intellij.util.ui.UIUtil", runnableId),
            ) assumedAs forAll<Runnable> { runnable -> given(runnable) { constraint += runnable[Runnable::run] to lattice.of(Thread.Ui) } }

            rawStatic("invokeLaterIfNeeded", "com.intellij.ui.GuiUtils", runnableId, modalityStateId) assumedAs
              forAll<Runnable> { runnable ->
                given(runnable, ModalityState::class()) { constraint += runnable[Runnable::run] to lattice.of(Thread.Ui) }
              }
            rawStatic("invokeLaterIfNeeded", "com.intellij.ui.GuiUtils", runnableId, modalityStateId, ClassId.of<Condition<*>>()) assumedAs
              forAll<Runnable> { runnable ->
                given(runnable, ModalityState::class(), Condition::class(Type.WildCard)) {
                  constraint += runnable[Runnable::run] to lattice.of(Thread.Ui)
                }
              }

            rawVirtual("smartInvokeLater", dumbServiceFqn, runnableId) assumedAs
              forAll<Runnable> { runnable ->
                given(ClassId.of(dumbServiceFqn)(), runnable) { constraint += runnable[Runnable::run] to lattice.of(Thread.Ui) }
              }
            rawVirtual("smartInvokeLater", dumbServiceFqn, runnableId, modalityStateId) assumedAs
              forAll<Runnable> { runnable ->
                given(ClassId.of(dumbServiceFqn)(), runnable, ModalityState::class()) {
                  constraint += runnable[Runnable::run] to lattice.of(Thread.Ui)
                }
              }

            // `WriteAction.runAndWait`/`computeAndWait` dispatch to the EDT (waiting if needed), so they're safe from any thread.
            val writeActionFqn = "com.intellij.openapi.application.WriteAction"
            rawStatic("runAndWait", writeActionFqn, ClassId.of<ThrowableRunnable<*>>()) assumedAs
              forAll(ThrowableRunnable::class(Throwable::class())) { action ->
                given(action) { constraint += action[ThrowableRunnable<*>::run] to lattice.of(Thread.Ui) }
              }
            rawStatic("computeAndWait", writeActionFqn, ClassId.of<ThrowableComputable<*, *>>()) assumedAs
              forAll { t ->
                forAll(ThrowableComputable::class(t, Throwable::class())) { action ->
                  given(action) {
                    range = t
                    constraint += action[ThrowableComputable<*, *>::compute] to lattice.of(Thread.Ui)
                  }
                }
              }
          }

          // Read actions execute their argument synchronously on the current thread (under the read lock, which any thread may take), so
          // the argument's effect propagates to the caller.
          run {
            virtual<Runnable>(Application::runReadAction) assumedAs
              forAll<Runnable> { runnable -> given(Application::class(), runnable) { symbolicInvocations += runnable[Runnable::run] } }
            virtual<Computable<Any>>(Application::runReadAction) assumedAs
              forAll { t ->
                forAll(Computable::class(t)) { computation ->
                  given(Application::class(), computation) {
                    range = t
                    symbolicInvocations += computation[Computable<*>::compute]
                  }
                }
              }
            virtual<ThrowableComputable<Any, Throwable>>(Application::runReadAction) assumedAs
              forAll { t ->
                forAll(ThrowableComputable::class(t, Throwable::class())) { computation ->
                  given(Application::class(), computation) {
                    range = t
                    symbolicInvocations += computation[ThrowableComputable<*, *>::compute]
                  }
                }
              }

            val readActionFqn = "com.intellij.openapi.application.ReadAction"
            rawStatic("run", readActionFqn, ClassId.of<ThrowableRunnable<*>>()) assumedAs
              forAll(ThrowableRunnable::class(Throwable::class())) { action ->
                given(action) { symbolicInvocations += action[ThrowableRunnable<*>::run] }
              }
            rawStatic("compute", readActionFqn, ClassId.of<ThrowableComputable<*, *>>()) assumedAs
              forAll { t ->
                forAll(ThrowableComputable::class(t, Throwable::class())) { action ->
                  given(action) {
                    range = t
                    symbolicInvocations += action[ThrowableComputable<*, *>::compute]
                  }
                }
              }
          }
        }
      }
  }
}
