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
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import org.jetbrains.uast.UAnnotation

class InferredThreadDetector : ThreadConstraintDetector<Thread>(ThreadConstraintLattice.of()) {

  override val violationIssue = THREAD
  override val unsatisfiableConstraintIssue = THREAD

  override fun parse(ann: UAnnotation) =
    when (ann.qualifiedName) {
      ANY_THREAD_ANNOTATION.newName(),
      ANY_THREAD_ANNOTATION.oldName() -> lattice.AnyThread
      UI_THREAD_ANNOTATION.oldName(),
      UI_THREAD_ANNOTATION.newName(),
      MAIN_THREAD_ANNOTATION.oldName(),
      MAIN_THREAD_ANNOTATION.newName() -> lattice.of(Thread.MainOrUi)
      WORKER_THREAD_ANNOTATION.oldName(),
      WORKER_THREAD_ANNOTATION.newName() -> lattice.of(Thread.Worker)
      BINDER_THREAD_ANNOTATION.oldName(),
      BINDER_THREAD_ANNOTATION.newName() -> lattice.of(Thread.Binder)
      else -> null
    }

  /**
   * Thread groups we track in an Android app.
   *
   * As far as thread compatibility is concerned, `@UiThread` and `@MainThread` are equivalent, so
   * they have the same internal representation, simplifying a preorder to a partial order. UX wise,
   * a small price we're currently paying is that when reporting errors back to the user, we're
   * displaying `@{Main,Ui}Thread` instead of the exact one that they originally write.
   */
  enum class Thread {
    MainOrUi,
    Worker,
    Binder;

    override fun toString() =
      when (this) {
        MainOrUi -> "`@{Main,Ui}Thread`"
        Worker -> "`@WorkerThread`"
        Binder -> "`@BinderThread`"
      }
  }

  companion object {

    private val Impl = Implementation(InferredThreadDetector::class.java, Scope.JAVA_FILE_SCOPE)

    @JvmField
    val THREAD =
      Issue.create(
        id = "ThreadConstraint",
        briefDescription = "Wrong Thread (with inference)",
        explanation =
          """
                Ensures that a method which expects to be called on a specific thread, is \
                actually called from that thread. For example, calls on methods in widgets \
                should always be made on the UI thread.
                """,
        moreInfo =
          "https://developer.android.com/guide/components/processes-and-threads.html#Threads",
        category = Category.CORRECTNESS,
        priority = 6,
        severity = Severity.ERROR,
        enabledByDefault = false,
        androidSpecific = true,
        implementation = Impl,
      )
  }
}
