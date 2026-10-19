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
package com.android.tools.lint.checks.fx.result

import com.android.tools.lint.checks.fx.utils.UnboundedSet
import com.android.tools.lint.checks.fx.utils.possibilityLattice
import kotlinx.collections.immutable.persistentSetOf
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUClass

/**
 * Generic errors to be accumulated and reported at the end. We don't report on-the-fly like most simple syntactic Lint checks, because (1)
 * certain checks may not consider events such as "reaching ⊤" a problem, and (2) during the fixpoint computation, later events may subsume
 * earlier ones, which would be redundant if reported on-the-fly.
 */
sealed interface Error<out FX> {
  data class IntroducingTop<out FX>(val site: ErrorSite, val first: FX, val next: FX) : Error<FX> {
    override fun toString() = "(unsatisfiable: ${site.renderAbbrev()})"
  }

  data class CallingTop<out FX>(val source: ErrorSite) : Error<FX> {
    override fun toString() = "(unsatisfiable: ${source.renderAbbrev() })"
  }

  data class ExceedingAnnotation<out FX>(val callerAnnotation: FX, val calleeLowerBound: FX, val site: ErrorSite) : Error<FX> {
    override fun toString() = "(${site.renderAbbrev()}: $callerAnnotation ⋤ $calleeLowerBound)"
  }

  data class FailingConstraint<out FX>(val constraints: UnboundedSet<ConstraintFailure<FX>>, val site: UExpression) : Error<FX> {
    override fun toString(): String {
      val c =
        constraints?.joinToString(separator = " ∧ ", prefix = "(", postfix = ")", transform = { (l, r) -> "$l ⊑ $r" }) ?: "constraints"
      return "($c fails at ${site.renderAbbrev()})"
    }
  }

  data class ConflictingAnnotations<out FX>(val self: EffectAnnotation.Explicit<FX>, val bases: Collection<EffectAnnotation.Explicit<FX>>) :
    Error<FX> {
    init {
      require(bases.isNotEmpty())
    }

    override fun toString() = "$bases @ ${self.origin.renderAbbrev()}"
  }

  data class ConflictingInference<out FX>(
    val site: ErrorSite,
    val inferredLowerBound: FX,
    val conflictingBase: EffectAnnotation.Explicit<FX>,
  ) : Error<FX> {

    override fun toString() = "$site ⋤ $conflictingBase"
  }

  /** Each handler of a concrete [FX] interprets the error as a concrete message, or `null` for no error */
  interface Explanation<FX> {
    /** Describes the call at [ExceedingAnnotation.site], e.g. "must be from X, but context is allowing Y" */
    fun explain(error: ExceedingAnnotation<FX>): String

    /** Message for [error] when its [FailingConstraint.constraints] are unknown */
    fun explain(error: FailingConstraint<FX>): String

    /** Describes [failure] on [argument] (e.g. "Argument"), calling [call] (e.g. "`run()`"), or `null` if invoking the argument itself */
    fun explain(failure: ConstraintFailure<FX>, argument: String, call: String?): String

    fun explain(error: IntroducingTop<FX>): String? = null

    fun explain(error: CallingTop<FX>): String? = null

    fun explain(error: ConflictingAnnotations<FX>): String {
      val (self, bases) = error
      val baseAnnotations = bases.groupBy(keySelector = { it.annotated }, valueTransform = { it.origin })

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

      fun originStr(origins: List<UElement>): String {
        val labels = origins.map(::superMethodLabel).distinct()
        return labels.join(labels.size) { it }
      }

      val baseStr = baseAnnotations.entries.join(baseAnnotations.size) { (ann, origins) -> "$ann (from ${originStr(origins)})" }

      return "${self.annotated} restricts $baseStr"
    }

    fun explain(error: ConflictingInference<FX>): String

    /** Label for the super method at [origin], e.g. "super method `Base.m(…)`" */
    fun superMethodLabel(origin: UElement): String {
      val method = origin as? UMethod
      return when (val baseName = method?.getContainingUClass()?.javaPsi?.name) {
        null -> "a super method"
        else -> "super method `$baseName.${method.name}(…)`"
      }
    }
  }
}

internal fun <FX> errorSetLattice() = possibilityLattice<Error<FX>>()

data class ConstraintFailure<out FX>(val invocation: Type.Sym<FX>, val expectedUpperBound: FX, val inferredLowerBound: FX) {
  override fun toString() = "$invocation : $inferredLowerBound ⋤ $expectedUpperBound"
}

internal fun <FX> UnboundedSet<ConstraintFailure<FX>>.at(site: UExpression) =
  when {
    this == null || isNotEmpty() -> persistentSetOf(Error.FailingConstraint(this, site))
    else -> persistentSetOf()
  }

typealias ErrorSite = UElement
