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

import com.android.tools.lint.checks.ThreadConstraintDetector.ThreadConstraint
import com.android.tools.lint.checks.fx.AssumptionTableBuilder
import com.android.tools.lint.checks.fx.JoinEffectDetector
import com.android.tools.lint.checks.fx.analysis.isKtProperty
import com.android.tools.lint.checks.fx.get
import com.android.tools.lint.checks.fx.invoke
import com.android.tools.lint.checks.fx.result.AssumptionTable
import com.android.tools.lint.checks.fx.result.EffectAnnotation
import com.android.tools.lint.checks.fx.result.EffectAnnotation.Explicit
import com.android.tools.lint.checks.fx.result.Error
import com.android.tools.lint.checks.fx.result.MethodId
import com.android.tools.lint.checks.fx.result.Type
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.static
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.virtual
import com.android.tools.lint.checks.fx.result.Type.Sym.Companion.chain
import com.android.tools.lint.checks.fx.utils.Encoder
import com.android.tools.lint.checks.fx.utils.Encoder.Companion.adapt
import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.client.api.JavaEvaluator
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.UastLintUtils.Companion.tryResolveUDeclaration
import com.intellij.psi.PsiParameter
import java.lang.Iterable as JIterable
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Function as JFunction
import java.util.function.Predicate
import java.util.stream.Stream
import kotlin.collections.forEach as ktForEach
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.plus
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpressionList
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.USwitchExpression
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.resolveToUElement

/**
 * An abstract thread detector parameterized by:
 *
 * @param T an enumeration of thread categories of interest (e.g. "Ui", "Worker", "Binder", etc.).
 *   These are assumed incomplete, and *disjoint*.
 *
 * The (induced) thread requirement [lattice] is ordered "more permissive" ⊑ "less permissive", with
 * [ThreadConstraintLattice.AnyThread] being (⊥) (the most permissive), and
 * [ThreadConstraintLattice.NoThread] (⊤) (the least).
 *
 * Note that [ThreadConstraintLattice.AnyThread] is strictly more permissive than the "meet" over
 * all explicitly given categories [T]. For example, for Android threads,
 * [ThreadConstraintLattice.AnyThread] is not equivalent to `@Ui ⊓ @Worker ⊓ @Binder`, but
 * conceptually equivalent to `@Ui ⊓ @Worker ⊓ @Binder ⊓ Other`, where `Other` is an implicit,
 * programmer-inaccessible thread category.
 *
 * The analysis is also parameterizable by [initialAssumptions], which can be provided either from
 * the analysis result of a dependent module, or assumed for primitives.
 */
abstract class ThreadConstraintDetector<T : Enum<T>>(
  protected val lattice: ThreadConstraintLattice<T>,
  initialAssumptions: AssumptionTable<ThreadConstraint<T>> = persistentMapOf(),
) : JoinEffectDetector<ThreadConstraint<T>>(lattice, initialAssumptions) {

  protected abstract val violationIssue: Issue
  protected abstract val unsatisfiableConstraintIssue: Issue
  override val mainIssue
    get() = violationIssue

  override val effectEncoder
    get() = lattice.encoder

  override fun report(context: Context, error: Error<ThreadConstraint<T>>) =
    when (error) {
      is Error.ExceedingAnnotation -> {
        val callLabel =
          when ((error.site.tryResolveUDeclaration() as? UMethod)?.isKtProperty()) {
            true -> "Property call"
            else -> "Call"
          }
        val message =
          when {
            error.calleeLowerBound == lattice.NoThread ->
              "$callLabel has an unsatisfiable thread requirement, but context is allowing ${error.callerAnnotation}"
            else ->
              "$callLabel must be from ${error.calleeLowerBound}, but context is allowing ${error.callerAnnotation}"
          }
        context.report(violationIssue, context.locationOf(error.site), message)
      }
      is Error.IntroducingTop -> {
        val site = error.site
        val t1 = error.first
        val t2 = error.next
        val message =
          when (val parentSite = site.uastParent) {
            is UIfExpression ->
              when (site) {
                parentSite.condition ->
                  "Condition must run from $t1, while branches must run from $t2"
                else ->
                  "Branch must run from $t2, incompatible with the other that must run from $t1"
              }
            is UExpressionList,
            is UBlockExpression ->
              "Statement must run from $t2, incompatible with earlier code that must run from $t1"
            is USwitchExpression ->
              when (site) {
                parentSite.expression ->
                  "Condition must run from $t1, while branches must run from $t2"
                else ->
                  "Branch must run from $t2, incompatible with the another that must run from $t1"
              }
            else ->
              "Expression results in an unsatisfiable thread requirement ($t2, after inferred $t1)"
          }
        context.report(unsatisfiableConstraintIssue, context.locationOf(site), message)
      }
      is Error.CallingTop ->
        context.report(
          unsatisfiableConstraintIssue,
          context.locationOf(error.source),
          "Call results in an unsatisfiable thread requirement",
        )
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
              violationIssue,
              context.locationOf(call),
              "Call fails thread requirements on arguments",
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
                  constraints.joinToString(" ") { (symCall, expectedUpper, inferredLower) ->
                    val (param, chain) = symCall.chain
                    when {
                      chain.size == 1 && chain.first().name == "invoke" ->
                        "Argument at `$param` must run from $expectedUpper, but is requiring $inferredLower."
                      else ->
                        "Argument at `$param` must allow calling `${chain.joinToString(".") {"${it.name}()"}}` from $expectedUpper, but that call is requiring $inferredLower."
                    }
                  }
                context.report(violationIssue, context.locationOf(call), message)
              }
              // If have concrete locations, report some, sloppily skipping some others (e.g. the
              // receiver) for now.
              else ->
                for ((arg, failure) in concreteReasons) {
                  val (symCall, expectedUpper, inferredLower) = failure
                  val (_, chain) = symCall.chain
                  // Friendlier message for special cases
                  val message =
                    when {
                      chain.size == 1 && chain.first().name == "invoke" ->
                        "Argument must run from $expectedUpper, but is requiring $inferredLower"
                      else ->
                        "Argument must allow calling `${chain.joinToString(".") {"${it.name}()"}}` from $expectedUpper, but that call is requiring $inferredLower"
                    }
                  context.report(violationIssue, context.locationOf(arg), message)
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
          violationIssue,
          context.locationOf(self.origin),
          "${self.annotated} restricts $baseStr",
        )
      }
      is Error.ConflictingInference -> {
        val baseAnn = error.conflictingBase.annotated
        val baseStr =
          when (
            val baseName =
              (error.conflictingBase.origin as? UMethod)?.getContainingUClass()?.javaPsi?.name
          ) {
            null -> "a super method"
            else -> "super method `$baseName.${error.conflictingBase.origin.name}(…)`"
          }

        val message =
          when (error.inferredLowerBound) {
            lattice.NoThread ->
              "Call has an unsatisfiable thread requirement, but $baseStr is allowing $baseAnn"
            else ->
              "Call must be from ${error.inferredLowerBound}, but $baseStr is allowing $baseAnn"
          }

        context.report(violationIssue, context.locationOf(error.site), message)
      }
    }

  private fun Context.locationOf(site: UElement) =
    client.getUastParser(project).createLocation(site)

  override fun resolveAnnotations(
    context: JavaContext,
    targetAnn: Explicit<ThreadConstraint<T>>,
    baseAnns: List<Explicit<ThreadConstraint<T>>>,
  ): Explicit<ThreadConstraint<T>> =
    targetAnn.also {
      val conflicts = baseAnns.filter { !(lattice.precede(targetAnn.annotated, it.annotated)) }
      if (conflicts.isNotEmpty()) {
        report(context, Error.ConflictingAnnotations(targetAnn, conflicts))
      }
    }

  override fun inheritAnnotations(
    evaluator: JavaEvaluator,
    baseAnns: List<Explicit<ThreadConstraint<T>>>,
  ): EffectAnnotation.Implicit<ThreadConstraint<T>> =
    when {
      baseAnns.isEmpty() -> EffectAnnotation.None
      else -> EffectAnnotation.Implicit(baseAnns)
    }

  override fun parseMethodImmediateAnnotations(
    evaluator: JavaEvaluator,
    method: UMethod,
  ): ThreadConstraint<T>? {
    fun fromMethod() = parseAnnotations(evaluator.getAnnotations(method.javaPsi, false))
    fun fromClass() =
      parseAnnotations(evaluator.getAnnotations(method.getContainingUClass()?.javaPsi, false))
    fun fromClassOrDefault() =
      when {
        // Constructors don't inherit from class annotations.
        // If it's trivial, it's `@AnyThread`. Otherwise, it's inferred.
        method.isConstructor -> if (method.uastBody == null) lattice.AnyThread else null
        // Properties only inherit from class annotation if they're open.
        // Otherwise, trivial properties are `@AnyThread`, and user-written {g,s}etters are
        // inferred.
        method.sourcePsi is KtProperty -> if (method.isFinal) lattice.AnyThread else fromClass()
        method.sourcePsi is KtPropertyAccessor -> if (method.isFinal) null else fromClass()
        else -> fromClass()
      }

    return fromMethod() ?: fromClassOrDefault()
  }

  override fun parseAnnotations(annotations: List<UAnnotation>): ThreadConstraint<T>? {
    // We interpret a list of multiple annotations as taking their meet,
    // but treat the no-annotation case specially as "infer this", instead of `⊤` (aka `NoThread`),
    // because nobody intends to mark code as `NoThread`
    val explicitlyAnnotatedEffects = annotations.mapNotNull(::parse)
    return when {
      explicitlyAnnotatedEffects.isEmpty() -> null
      else -> explicitlyAnnotatedEffects.reduce(lattice::meetOf)
    }
  }

  protected abstract fun parse(ann: UAnnotation): ThreadConstraint<T>?

  /** A bit-set representation of ([T] ⊕ `Other`), assuming the enum [T] has few enough variants. */
  data class ThreadConstraint<T : Enum<T>>
  internal constructor(private val tag: ThreadConstraintLattice<T>, internal val cases: ULong) {
    internal fun isMostPermissive() = cases == tag.fullCases

    internal fun isLeastPermissive() = cases == 0UL

    override fun toString() =
      when {
        isMostPermissive() -> "`@AnyThread`"
        isLeastPermissive() -> "`@NoThread`"
        else ->
          tag.threadTag.enumConstants
            .asSequence()
            .filterIndexed { i, _ -> cases and (1UL shl i) != 0UL }
            .joinToString("|")
      }
  }

  /**
   * Given an enumeration of disjoint (but not necessarily exhaustive) thread tags, generate a
   * lattice whose `⊥` is "most inclusive", and `⊤` is "least inclusive".
   *
   * There's always an implicit "other" thread tag. So `⊥` is strictly more inclusive than the
   * inclusion of all user-specified tags.
   */
  class ThreadConstraintLattice<T : Enum<T>>(val threadTag: Class<T>) :
    Lattice<ThreadConstraint<T>> {
    internal val numExplicitCases = threadTag.enumConstants.size
    internal val fullCases = (1UL shl (numExplicitCases + 1)) - 1UL

    init {
      require(numExplicitCases <= ULong.SIZE_BITS - 1) {
        "Max ${ULong.SIZE_BITS - 1} thread tags supported, but `${threadTag.simpleName}` has $numExplicitCases."
      }
    }

    // Caching common instances
    val AnyThread = ThreadConstraint(this, fullCases)
    val NoThread = ThreadConstraint(this, 0UL)
    private val cache = Array(numExplicitCases + 1) { ThreadConstraint(this, 1UL shl it) }

    fun of(vararg cases: T): ThreadConstraint<T> =
      of(cases.fold(0UL) { acc, case -> acc or (1UL shl case.ordinal) })

    /** Micro-optimized constructor, reusing common instances */
    private fun of(cases: ULong): ThreadConstraint<T> =
      when {
        cases == 0UL -> NoThread
        cases == fullCases -> AnyThread
        cases.countOneBits() == 1 -> cache[cases.countTrailingZeroBits()]
        else -> ThreadConstraint(this, cases)
      }

    override val bottom = AnyThread
    override val top = NoThread

    override fun precede(first: ThreadConstraint<T>, second: ThreadConstraint<T>) =
      (first.cases and second.cases) == second.cases

    override fun joinOf(first: ThreadConstraint<T>, second: ThreadConstraint<T>) =
      when {
        first == second -> first
        first.isMostPermissive() -> second
        second.isMostPermissive() -> first
        // Only last case needed. Above cases are micro-optimization re-using common instances
        else -> of(first.cases and second.cases)
      }

    override fun meetOf(first: ThreadConstraint<T>, second: ThreadConstraint<T>) =
      when {
        first == second -> first
        first.isLeastPermissive() -> second
        second.isLeastPermissive() -> first
        // Only last case needed. Above cases are micro-optimization re-using common instances
        else -> of(first.cases or second.cases)
      }

    val encoder: Encoder<ThreadConstraint<T>> =
      when {
        fullCases < Byte.MAX_VALUE.toULong() ->
          Encoder.byte.adapt({ it.cases.toByte() }, { of(it.toULong()) })
        else -> Encoder.int.adapt({ it.cases.toInt() }, { of(it.toULong()) })
      }

    companion object {
      inline fun <reified T : Enum<T>> of(): ThreadConstraintLattice<T> =
        ThreadConstraintLattice(T::class.java)

      /** Adds assumptions on common Java and Kotlin utils */
      fun <T : Enum<T>> AssumptionTableBuilder<ThreadConstraint<T>>
        .assumeCommonJavaAndKotlinSignatures() {
        // Iterable
        run {
          virtual(JIterable<*>::forEach) assumedAs
            forAll { a ->
              forAll(Consumer::class(a)) { action ->
                given(Iterable::class(a), action) {
                  symbolicInvocations += action[Consumer<*>::accept, a]
                }
              }
            }
          static(Iterable<*>::ktForEach) assumedAs
            forAll { a ->
              forAll(Function1::class(a, Type.Unit)) { action ->
                given(Iterable::class(a), action) {
                  symbolicInvocations += action[MethodId.Invoke[1], a]
                }
              }
            }
          static(Iterable<*>::forEachIndexed) assumedAs
            forAll { a ->
              forAll(Function2::class(Type.Int, a, Type.Unit)) { action ->
                given(Iterable::class(a), action) {
                  symbolicInvocations += action[MethodId.Invoke[2], Type.Int, a]
                }
              }
            }
          (static<_, (Any) -> Comparable<Any>>(Iterable<Nothing>::maxBy) +
            static<_, (Any) -> Comparable<Any>>(Iterable<Nothing>::maxByOrNull) +
            static<_, (Any) -> Comparable<Any>>(Iterable<Nothing>::minBy) +
            static<_, (Any) -> Comparable<Any>>(Iterable<Nothing>::minByOrNull)) assumedAs
            forAll { t ->
              forAll { r ->
                forAll(Function1::class(t, r)) { selector ->
                  given(Iterable::class(t), selector) {
                    range = r
                    symbolicInvocations += selector[MethodId.Invoke[1], t]
                    symbolicInvocations += r[Comparable<*>::compareTo, r]
                  }
                }
              }
            }
          (static<_, Comparator<Any>, (Any) -> Any>(Iterable<Nothing>::maxOfWith) +
            static<_, Comparator<Any>, (Any) -> Any>(Iterable<Nothing>::maxOfWithOrNull) +
            static<_, Comparator<Any>, (Any) -> Any>(Iterable<Nothing>::minOfWith) +
            static<_, Comparator<Any>, (Any) -> Any>(Iterable<Nothing>::minOfWithOrNull)) assumedAs
            forAll { t ->
              forAll { r ->
                forAll(Comparator::class(r)) { comparator ->
                  forAll(Function1::class(t, r)) { selector ->
                    given(Iterable::class(t), comparator, selector) {
                      range = r
                      symbolicInvocations += comparator[Comparator<*>::compare, r, r]
                      symbolicInvocations += selector[MethodId.Invoke[1], t]
                    }
                  }
                }
              }
            }
          (static(Iterable<*>::maxWith) +
            static(Iterable<*>::maxWithOrNull) +
            static(Iterable<*>::minWith) +
            static(Iterable<*>::minWithOrNull)) assumedAs
            forAll { t ->
              forAll(Comparator::class(t)) { comparator ->
                given(Iterable::class(t), comparator) {
                  range = t
                  symbolicInvocations += comparator[Comparator<*>::compare, t, t]
                }
              }
            }
          (static(Iterable<*>::all) +
            static<_, _>(Iterable<*>::any) +
            static<_, _>(Iterable<*>::none)) assumedAs
            forAll { t ->
              forAll(Function1::class(t, Type.Boolean)) { predicate ->
                given(Iterable::class(t), predicate) {
                  range = Type.Boolean
                  symbolicInvocations += predicate[MethodId.Invoke[1], t]
                }
              }
            }
          static(Iterable<*>::onEach) assumedAs
            forAll { t ->
              forAll(Iterable::class(t)) { self ->
                forAll(Function1::class(t, Type.Unit)) { action ->
                  given(self, action) {
                    range = self
                    symbolicInvocations += action[MethodId.Invoke[1], t]
                  }
                }
              }
            }
          static(Iterable<*>::onEachIndexed) assumedAs
            forAll { t ->
              forAll(Iterable::class(t)) { self ->
                forAll(Function2::class(Type.Int, t, Type.Unit)) { action ->
                  given(Iterable::class(t), action) {
                    range = self
                    symbolicInvocations += action[MethodId.Invoke[2], Type.Int, t]
                  }
                }
              }
            }
          (static(Iterable<*>::reduce) + static(Iterable<*>::reduceOrNull)) assumedAs
            forAll { s ->
              forAll(s) { t ->
                forAll(Function2::class(s, t, s)) { operation ->
                  given(Iterable::class(t), operation) {
                    range = s
                    symbolicInvocations += operation[MethodId.Invoke[2], s, t]
                  }
                }
              }
            }
          (static(Iterable<*>::reduceIndexed) + static(Iterable<*>::reduceIndexedOrNull)) assumedAs
            forAll { s ->
              forAll(s) { t ->
                forAll(Function3::class(Type.Int, s, t, s)) { operation ->
                  given(Iterable::class(t), operation) {
                    range = s
                    symbolicInvocations += operation[MethodId.Invoke[3], Type.Int, s, t]
                  }
                }
              }
            }

          static<_, (Any?) -> Any>(Iterable<*>::map) assumedAs
            forAll { x ->
              forAll { y ->
                forAll(Function1::class(x, y)) { transform ->
                  given(Iterable::class(x), transform) {
                    range = List::class(y)
                    symbolicInvocations += transform[MethodId.Invoke[1], x]
                  }
                }
              }
            }
          static(Iterable<*>::filter) assumedAs
            forAll { x ->
              forAll(Function1::class(x, Type.Boolean)) { predicate ->
                given(Iterable::class(x), predicate) {
                  range = List::class(x)
                  symbolicInvocations += predicate[MethodId.Invoke[1], x]
                }
              }
            }
          static<_, Nothing, _>(Iterable<*>::fold) assumedAs
            forAll { t ->
              forAll { r ->
                forAll(Function2::class(r, t, r)) { operation ->
                  given(Iterable::class(t), r, operation) {
                    range = r
                    symbolicInvocations += operation[MethodId.Invoke[2], r, t]
                  }
                }
              }
            }
        }

        // List
        run {
          (static(List<*>::reduceRight) + static(List<*>::reduceRightOrNull)) assumedAs
            forAll { s ->
              forAll(s) { t ->
                forAll(Function2::class(t, s, s)) { operation ->
                  given(List::class(t), operation) {
                    range = s
                    symbolicInvocations += operation[MethodId.Invoke[2], t, s]
                  }
                }
              }
            }
          (static(List<*>::reduceRightIndexed) +
            static(List<*>::reduceRightIndexedOrNull)) assumedAs
            forAll { s ->
              forAll(s) { t ->
                forAll(Function3::class(Type.Int, t, s, s)) { operation ->
                  given(Iterable::class(t), operation) {
                    range = s
                    symbolicInvocations += operation[MethodId.Invoke[3], Type.Int, t, s]
                  }
                }
              }
            }
        }

        // Map
        run {
          virtual(Map<*, *>::forEach) assumedAs
            forAll { k ->
              forAll { v ->
                forAll(BiConsumer::class(k, v)) { action ->
                  given(Map::class(k, v), action) {
                    symbolicInvocations += action[BiConsumer<*, *>::accept, k, v]
                  }
                }
              }
            }
          static(Map<*, *>::ktForEach) assumedAs
            forAll { k ->
              forAll { v ->
                forAll(Function1::class(Map.Entry::class(k, v), Type.Unit)) { action ->
                  given(Map::class(k, v), action) {
                    symbolicInvocations += action[MethodId.Invoke[1], Map::class(k, v)]
                  }
                }
              }
            }
          static<Map<*, *>, (Map.Entry<*, *>) -> Any>(Map<*, *>::map) assumedAs
            forAll { k ->
              forAll { v ->
                forAll { r ->
                  forAll(Function1::class(Map.Entry::class(k, v), r)) { transform ->
                    given(Map::class(k, v), transform) {
                      range = List::class(r)
                      symbolicInvocations += transform[MethodId.Invoke[1], Map.Entry::class(k, v)]
                    }
                  }
                }
              }
            }
        }

        // Array
        run {
          static(Array<*>::ktForEach) assumedAs
            forAll { a ->
              forAll(Function1::class(a, Type.Unit)) { action ->
                given(Array::class(a), action) {
                  symbolicInvocations += action[MethodId.Invoke[1], a]
                }
              }
            }
          static<_, (Any?) -> Any>(Array<*>::map) assumedAs
            forAll { a ->
              forAll(Function1::class(a, Type.Unit)) { action ->
                given(Array::class(a), action) {
                  symbolicInvocations += action[MethodId.Invoke[1], a]
                }
              }
            }
          static(Array<*>::filter) assumedAs
            forAll { a ->
              forAll(Function1::class(a, Type.Boolean)) { predicate ->
                given(Array::class(a), predicate) {
                  range = List::class(a)
                  symbolicInvocations += predicate[MethodId.Invoke[1], a]
                }
              }
            }
          static<_, Nothing, _>(Array<*>::fold) assumedAs
            forAll { t ->
              forAll { r ->
                forAll(Function2::class(r, t, r)) { operation ->
                  given(Array::class(t), r, operation) {
                    range = r
                    symbolicInvocations += operation[MethodId.Invoke[2], r, t]
                  }
                }
              }
            }
        }

        // Sequence
        // TODO below ain't accurate. Calls to `map`/`filter` only build up `Sequence`, and defer
        //  the actions to iteration time.
        run {
          static(Sequence<*>::forEach) assumedAs
            forAll { a ->
              forAll(Function1::class(a, Type.Unit)) { action ->
                given(Sequence::class(a), action) {
                  symbolicInvocations += action[MethodId.Invoke[1], a]
                }
              }
            }
          static<_, (Any?) -> Any>(Sequence<*>::map) assumedAs
            forAll { x ->
              forAll { y ->
                forAll(Function1::class(x, y)) { transform ->
                  given(Sequence::class(x), transform) {
                    range = Sequence::class(y)
                    symbolicInvocations += transform[MethodId.Invoke[1], x]
                  }
                }
              }
            }
          static(Sequence<*>::filter) assumedAs
            forAll { x ->
              forAll(Function1::class(x, Type.Boolean)) { predicate ->
                given(Sequence::class(x), predicate) {
                  range = Sequence::class(x)
                  symbolicInvocations += predicate[MethodId.Invoke[1], x]
                }
              }
            }
          static<_, Nothing, _>(Sequence<*>::fold) assumedAs
            forAll { t ->
              forAll { r ->
                forAll(Function2::class(r, t, r)) { operation ->
                  given(Sequence::class(t), r, operation) {
                    range = r
                    symbolicInvocations += operation[MethodId.Invoke[2], r, t]
                  }
                }
              }
            }
        }

        // Stream
        // TODO(b/418276115)
        run {
          virtual(Stream<*>::forEach) assumedAs
            forAll { a ->
              forAll(Consumer::class(a)) { action ->
                given(Stream::class(a), action) {
                  symbolicInvocations += action[Consumer<*>::accept, a]
                }
              }
            }
          virtual<JFunction<Any?, *>>(Stream<*>::map) assumedAs
            forAll { x ->
              forAll { y ->
                forAll(JFunction::class(x, y)) { mapper ->
                  given(Stream::class(x), mapper) {
                    range = Stream::class(y)
                    symbolicInvocations += mapper[JFunction<*, *>::apply, x]
                  }
                }
              }
            }
          virtual(Stream<*>::filter) assumedAs
            forAll { x ->
              forAll(Predicate::class(x)) { predicate ->
                given(Stream::class(x), predicate) {
                  range = Stream::class(x)
                  symbolicInvocations += predicate[Predicate<*>::test, x]
                }
              }
            }
        }

        // Common scoping functions
        run {
          (static(Any::apply) + static(Any::also)) assumedAs
            forAll { self ->
              forAll(Function1::class(self, Type.Unit)) { block ->
                given(self, block) {
                  range = self
                  symbolicInvocations += block[MethodId.Invoke[1], self]
                }
              }
            }
          (static<Any, Any.() -> Any>(::with) + static<Any, (Any) -> Any>(Any::let)) assumedAs
            forAll { receiver ->
              forAll { result ->
                forAll(Function1::class(receiver, result)) { block ->
                  given(receiver, block) {
                    range = result
                    symbolicInvocations += block[MethodId.Invoke[1], receiver]
                  }
                }
              }
            }
        }
      }
    }
  }
}
