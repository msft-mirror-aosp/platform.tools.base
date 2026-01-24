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
import com.android.tools.lint.checks.fx.analysis.isKtProperty
import com.android.tools.lint.checks.fx.result.EffectAnnotation
import com.android.tools.lint.checks.fx.result.Error
import com.android.tools.lint.checks.fx.result.Type.Sym.Companion.chain
import com.android.tools.lint.checks.fx.utils.Encoder
import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.client.api.JavaEvaluator
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.UastLintUtils.Companion.tryResolveUDeclaration
import com.intellij.psi.PsiParameter
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.resolveToUElement

class BlockingDetector : JoinEffectDetector<BlockingDetector.Status>(statusLattice, assumptions) {
  override val mainIssue = ISSUE

  override val effectEncoder = Encoder.enum<Status>()

  override fun report(context: Context, error: Error<Status>) =
    when (error) {
      is Error.ExceedingAnnotation -> {
        val callLabel =
          when ((error.site.tryResolveUDeclaration() as? UMethod)?.isKtProperty()) {
            true -> "Property call"
            else -> "Call"
          }
        val message = "$callLabel blocks in a context not allowed to block"
        context.report(mainIssue, context.locationOf(error.site), message)
      }
      is Error.FailingConstraint -> {
        val call = error.site
        val paramToArg =
          when (val method = (call as? UCallExpression)?.resolveToUElement()) {
            is UMethod -> {
              val params = method.uastParameters
              val receiver = call.receiver
              buildMap {
                if (receiver != null) put("this", receiver)
                for ((x, v) in params zip call.valueArguments) {
                  put((x.javaPsi as PsiParameter).name, v)
                }
              }
            }
            else -> mapOf()
          }
        when (val constraints = error.constraints) {
          null ->
            context.report(
              mainIssue,
              context.locationOf(call),
              "Call fails non-blocking requirements on arguments",
            )
          else -> {
            assert(constraints.isNotEmpty())
            val concreteReasons =
              constraints.mapNotNull { failure ->
                when (val arg = paramToArg[failure.invocation.chain.first]) {
                  null -> null
                  else -> arg to failure
                }
              }
            when {
              concreteReasons.isEmpty() -> {
                val message =
                  constraints.joinToString(" ") { (symCall, _, _) ->
                    val (param, chain) = symCall.chain
                    when {
                      chain.size == 1 && chain.first().name == "invoke" ->
                        "Argument at `$param` must not block, but does."
                      else ->
                        "Argument at `$param`'s calling `${chain.joinToString(".") {"${it.name}()"}}` must not block, but does."
                    }
                  }
                context.report(mainIssue, context.locationOf(call), message)
              }
              // If have concrete locations, report some, sloppily skipping some others (e.g. the
              // receiver) for now.
              else ->
                for ((arg, failure) in concreteReasons) {
                  val (symCall, _, _) = failure
                  val (_, chain) = symCall.chain
                  // Friendlier message for special cases
                  val message =
                    when {
                      chain.size == 1 && chain.first().name == "invoke" ->
                        "Argument must not block, but does"
                      else ->
                        "Argument's calling `${chain.joinToString(".") {"${it.name}()"}}` must not block, but does"
                    }
                  context.report(mainIssue, context.locationOf(arg), message)
                }
            }
          }
        }
      }
      is Error.ConflictingAnnotations -> {
        val (self, bases) = error
        val baseAnnotations =
          bases.groupBy(keySelector = { it.annotated }, valueTransform = { it.origin as? UMethod })

        fun <T> Iterable<T>.join(size: Int, format: (T) -> String): String = buildString {
          for ((i, elem) in this@join.withIndex()) {
            val sep =
              when {
                i == 0 -> ""
                size > 1 && i == size - 1 -> ", and "
                else -> ","
              }
            append("$sep${format(elem)}")
          }
        }

        fun originStr(origins: List<UMethod?>): String {
          val originStrs =
            origins.mapNotNullTo(mutableListOf()) {
              when (val baseName = it?.getContainingUClass()?.javaPsi?.name) {
                null -> null
                else -> "super method `$baseName.${it.name}(…)`"
              }
            }
          if (null in origins) originStrs.add("a super method")
          return originStrs.join(originStrs.size) { it }
        }

        val baseStr =
          baseAnnotations.entries.join(baseAnnotations.size) { (ann, origins) ->
            "$ann (from ${originStr(origins)})"
          }

        context.report(
          mainIssue,
          context.locationOf(self.origin),
          "${self.annotated} restricts $baseStr",
        )
      }
      is Error.ConflictingInference -> {
        val baseStr =
          when (
            val baseName =
              (error.conflictingBase.origin as? UMethod)?.getContainingUClass()?.javaPsi?.name
          ) {
            null -> "a super method"
            else -> "super method `$baseName.${error.conflictingBase.origin.name}(…)`"
          }

        val message = "Call must not block, as required by $baseStr"
        context.report(mainIssue, context.locationOf(error.site), message)
      }
      // Reaching `⊤` is not an error for this problem
      is Error.IntroducingTop,
      is Error.CallingTop -> {}
    }

  private fun Context.locationOf(site: UElement) =
    client.getUastParser(project).createLocation(site)

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
  ): EffectAnnotation.Explicit<Status> =
    targetAnn.also {
      val conflicts =
        baseAnns.filter { !(statusLattice.precede(targetAnn.annotated, it.annotated)) }
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
        androidSpecific = false,
        implementation = Impl,
      )

    val assumptions = statusLattice.build { assumeCommonJavaAndKotlinSignatures() }
  }
}
