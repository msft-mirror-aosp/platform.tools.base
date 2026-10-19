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
import com.android.tools.lint.checks.fx.JoinEffectDetector
import com.android.tools.lint.checks.fx.result.ConstraintFailure
import com.android.tools.lint.checks.fx.result.EffectAnnotation
import com.android.tools.lint.checks.fx.result.Error
import com.android.tools.lint.checks.fx.utils.Encoder
import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.client.api.JavaEvaluator
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UMethod

class BlockingDetector : JoinEffectDetector<BlockingDetector.Status>(statusLattice, assumptions) {
  override val mainIssue = ISSUE

  override val effectEncoder = Encoder.enum<Status>()

  override val errorExplanation: Error.Explanation<Status> =
    object : Error.Explanation<Status> {
      override fun explain(error: Error.ExceedingAnnotation<Status>) = "blocks in a context not allowed to block"

      override fun explain(error: Error.FailingConstraint<Status>) = "Call fails non-blocking requirements on arguments"

      override fun explain(failure: ConstraintFailure<Status>, argument: String, call: String?) =
        when (call) {
          null -> "$argument must not block, but does"
          else -> "$argument's calling $call must not block, but does"
        }

      override fun explain(error: Error.ConflictingInference<Status>) =
        "Call must not block, as required by ${superMethodLabel(error.conflictingBase.origin)}"
    }

  override fun parseAnnotations(annotations: List<UAnnotation>): Status? {
    val anns = annotations.mapNotNull(::parse).distinct()
    return when (anns.size) {
      0 -> null
      1 -> anns.first()
      else -> Status.MaybeBlocking // TODO should report but no context here
    }
  }

  // TODO use fully qualified name once we've decided. Below is for initial hacking.
  private fun parse(ann: UAnnotation): Status? {
    val fqn = ann.qualifiedName ?: return null
    if (fqn.endsWith("NonBlocking")) return Status.DefinitelyNonBlocking
    if (fqn.endsWith("Blocking")) return Status.MaybeBlocking
    return null
  }

  override fun parseMethodImmediateAnnotations(evaluator: JavaEvaluator, method: UMethod) =
    parseAnnotations(evaluator.getAnnotations(method.javaPsi, false))

  override fun resolveAnnotations(
    context: JavaContext,
    targetAnn: EffectAnnotation.Explicit<Status>,
    baseAnns: List<EffectAnnotation.Explicit<Status>>,
  ): EffectAnnotation.Explicit<Status> = targetAnn.also {
    val conflicts = baseAnns.filter { !(statusLattice.precede(targetAnn.annotated, it.annotated)) }
    if (conflicts.isNotEmpty()) {
      report(context, Error.ConflictingAnnotations(targetAnn, conflicts))
    }
  }

  override fun inheritAnnotations(
    evaluator: JavaEvaluator,
    baseAnns: List<EffectAnnotation.Explicit<Status>>,
  ): EffectAnnotation.Implicit<Status> =
    when {
      baseAnns.isEmpty() -> EffectAnnotation.None
      else -> EffectAnnotation.Implicit(baseAnns)
    }

  enum class Status {
    DefinitelyNonBlocking,
    MaybeBlocking,

    // TODO temp hack. Turns out the analysis has been hardcoded to treat `⊤` as error, and there's
    //  a UX hack on top of that to discard other errors when certain part reaches `⊤`
    Unsat,
  }

  override val externalAssumptionsDir: String?
    get() = System.getProperty(ASSUMPTIONS_PATH).takeUnless { it.isNullOrEmpty() }

  companion object {
    @VisibleForTesting const val ASSUMPTIONS_PATH = "lint.assumptions.blocking"

    // DefinitelyNonBlocking ⊑ MaybeBlocking ⊑ Unsat
    // Technically we only care about the first 2 values.
    val statusLattice =
      object : Lattice<Status> {
        override val bottom = Status.DefinitelyNonBlocking
        override val top = Status.Unsat

        override fun precede(first: Status, second: Status) = first <= second

        override fun joinOf(first: Status, second: Status) = maxOf(first, second)

        override fun meetOf(first: Status, second: Status) = minOf(first, second)
      }

    private val Impl = Implementation(BlockingDetector::class.java, Scope.JAVA_FILE_SCOPE)

    @JvmField
    val ISSUE =
      Issue.create(
        id = "BlockingMethod",
        briefDescription = "Blocking Method",
        explanation =
          """
          Ensures that blocking methods are not called from those that expect no blocking.
          """,
        category = Category.CORRECTNESS,
        priority = 6,
        severity = Severity.ERROR,
        enabledByDefault = false,
        implementation = Impl,
      )

    val assumptions = statusLattice.build { assumeCommonJavaAndKotlinSignatures() }
  }
}
