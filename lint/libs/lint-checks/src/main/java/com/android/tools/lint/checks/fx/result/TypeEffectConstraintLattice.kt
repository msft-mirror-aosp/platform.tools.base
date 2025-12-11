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

import com.android.tools.lint.checks.fx.result.Type.Sym.Fix.Companion.hasFreeRec
import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.checks.fx.utils.UnboundedSet
import com.android.tools.lint.checks.fx.utils.flatMapToPersistentSet
import com.android.tools.lint.checks.fx.utils.map
import com.android.tools.lint.checks.fx.utils.possibilityLattice
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.intersect
import kotlinx.collections.immutable.minus
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import kotlinx.collections.immutable.toPersistentSet

/**
 * Given a [Lattice] on the concrete effect, mutually recursively define [Lattice]s on [Type],
 * [Effect], and [Constraint]
 */
class TypeEffectConstraintLattice<FX>(private val fxLattice: Lattice<FX>) {
  val symbolicEffectLattice =
    object : Lattice<UnboundedSet<Type.Sym<FX>>> by possibilityLattice() {
      override fun joinOf(
        first: UnboundedSet<Type.Sym<FX>>,
        second: UnboundedSet<Type.Sym<FX>>,
      ): UnboundedSet<Type.Sym<FX>> =
        when {
          first == null -> null
          second == null -> null
          else -> {
            var baseCases = persistentSetOf<Type<FX>>()
            var indCases = persistentSetOf<Type<FX>>()
            var widened = persistentSetOf<Type.Sym<FX>>()
            var fixCaseCount = 0
            fun check(cases: PersistentSet<Type.Sym<FX>>) {
              for (case in cases) {
                if (case is Type.Sym.Fix) {
                  baseCases += case.baseCases
                  indCases += case.inductiveCases
                  widened += case
                  fixCaseCount++
                }
              }
            }
            check(first)
            check(second)
            val allCases = first + second
            when {
              fixCaseCount > 1 -> allCases - widened + Type.Sym.Fix(baseCases, indCases)
              else -> allCases
            }
          }
        }

      override fun widen(prev: UnboundedSet<Type.Sym<FX>>, now: UnboundedSet<Type.Sym<FX>>) =
        when {
          prev != null && now != null ->
            (typeLattice as TypeLattice)
              .summarize(prev, now)
              .filterIsInstance<Type.Sym<FX>>()
              .toPersistentSet()
          else -> joinOf(prev, now)
        }
    }

  val constraintLattice = Constraint.domain(fxLattice)

  val effectLattice =
    Lattice.product(
      ::Effect,
      Effect<FX>::concrete,
      Effect<FX>::invocations,
      Effect<FX>::constraint,
      fxLattice,
      symbolicEffectLattice,
      constraintLattice,
    )

  val typeLattice: Lattice<Type<FX>> = TypeLattice(effectLattice)

  private class TypeLattice<FX>(private val effectLattice: Lattice<Effect<FX>>) :
    Lattice<Type<FX>> {
    override val bottom = Type.None
    override val top = Type.WildCard

    // Inconsequential for now
    override fun meetOf(first: Type<FX>, second: Type<FX>) =
      when {
        top precedes first -> second
        top precedes second -> first
        first precedes bottom || second precedes bottom -> bottom
        first == second -> first
        second is Type.Union -> meetUnion(first, second)
        first is Type.Union -> meetUnion(second, first)
        second is Type.Sym.Fix -> meetFix(first, second)
        first is Type.Sym.Fix -> meetFix(second, first)
        else -> bottom
      }

    private fun meetUnion(first: Type<FX>, second: Type.Union<FX>): Type<FX> =
      when (first) {
        is Type.Union -> Type.Union(first.cases intersect second.cases)
        is Type.Sym.Fix -> Type.Union(first.baseCases intersect second.cases)
        else -> if (first in second.cases) first else bottom
      }

    private fun meetFix(first: Type<FX>, second: Type.Sym.Fix<FX>): Type<FX> =
      when (first) {
        is Type.Union -> Type.Union(first.cases intersect second.baseCases)
        is Type.Sym.Fix -> {
          val baseCases = first.baseCases intersect second.baseCases
          val inductiveCases = first.inductiveCases intersect second.inductiveCases
          when {
            baseCases.isNotEmpty() && inductiveCases.isNotEmpty() ->
              Type.Sym.Fix(baseCases, inductiveCases)
            baseCases.isNotEmpty() -> Type.Union(baseCases)
            else -> bottom
          }
        }
        else -> if (first in second.baseCases) first else bottom
      }

    override fun joinOf(first: Type<FX>, second: Type<FX>): Type<FX> =
      when {
        first is Type.WildCard || second is Type.WildCard -> Type.WildCard
        first is Type.Union && second is Type.Union -> Type.Union(first.cases + second.cases)
        first is Type.Union -> Type.Union(first.cases + second)
        second is Type.Union -> Type.Union(second.cases + first)
        first is Type.Lambda &&
          second is Type.Lambda &&
          first.intf != null &&
          first.intf == second.intf &&
          first.params == second.params -> {
          val (bodyType1, bodyFx1) = first.body
          val (bodyType2, bodyFx2) = second.body
          first.copy(
            body = Result(joinOf(bodyType1, bodyType2), effectLattice.joinOf(bodyFx1, bodyFx2))
          )
        }
        else -> Type.Union(persistentSetOf(first, second))
      }

    override fun precede(first: Type<FX>, second: Type<FX>) =
      when {
        second == top -> true
        first == top -> false
        first == bottom -> true
        second == bottom -> false
        first == second -> true
        second is Type.Union ->
          when (first) {
            is Type.Union -> second.cases.containsAll(first.cases)
            else -> first in second.cases
          }
        second is Type.Sym.Fix ->
          when (first) {
            is Type.Union -> second.baseCases.containsAll(first.cases)
            is Type.Sym.Fix ->
              second.baseCases.containsAll(first.baseCases) &&
                second.inductiveCases.containsAll(first.inductiveCases)
            else -> first in second.baseCases
          }
        else -> false
      }

    override fun widen(prev: Type<FX>, now: Type<FX>): Type<FX> {
      fun <FX> Type<FX>.asCases(): PersistentSet<Type<FX>> =
        when (this) {
          is Type.Union -> cases
          is Type.Sym.Fix -> cases.toPersistentSet()
          else -> persistentSetOf(this)
        }

      val casesPrev = prev.asCases()
      val casesNow = now.asCases()
      val casesJoined = casesPrev + casesNow
      return when {
        casesJoined.isEmpty() -> Type.None
        casesJoined.size == 1 -> casesJoined.first()
        // In simple cases where `now` contains all of `prev`, but the extra symbols in `now`
        // don't "grow" from any in `prev`, we return `now` without widening
        casesNow.all {
          it !is Type.Sym.Fix && !it.hasFreeRec() && (it in casesPrev || !it.containsAny(casesPrev))
        } && casesNow.containsAll(casesPrev) -> now
        else -> Type.Union(summarize(casesPrev, casesNow))
      }
    }

    /**
     * Given how a set of symbol progresses from one to the next, attempt to summarize it as a least
     * fix point of some generation rules
     */
    fun summarize(
      prev: PersistentSet<Type<FX>>,
      now: PersistentSet<Type<FX>>,
    ): PersistentSet<Type<FX>> {
      // Temporal information simply erased for now
      val cases = now.fold(prev.fold(persistentSetOf(), ::flatMerge), ::flatMerge)
      val fixed =
        fix(cases) { cases -> cases.map { fix(it, { cases.productiveSubst(it) }) } }
          // TODO refactor. Below should only take 1 pass
          .consolidateSymbols()
          .consolidateConstants()

      return when {
        fixed.any { it.hasFreeRec() } ->
          try {
            persistentSetOf(Type.Sym.Fix(fixed))
          } catch (e: Type.Sym.Fix.TrivialInduction) {
            e.cases as PersistentSet<Type<FX>>
          }
        else -> fixed
      }
    }

    private tailrec fun <T> fix(start: T, step: (T) -> T): T =
      when (val next = step(start)) {
        start -> start
        else -> fix(next, step)
      }

    private fun flatMerge(types: PersistentSet<Type<FX>>, type: Type<FX>): PersistentSet<Type<FX>> =
      when (type) {
        is Type.Sym.Fix -> type.cases.fold(types, ::flatMerge)
        is Type.Sym.Invoke ->
          type.args.fold(flatMergeFix(types + type, type.receiver), ::flatMergeFix)
        is Type.Sym.Param,
        is Type.Sym.This,
        is Type.Sym.Rec -> types + type
        is Type.Application -> type.args.fold(types + type, ::flatMergeFix)
        is Type.Ellipsis -> flatMergeFix(types + type, type.element)
        is Type.Lambda -> flatMergeFix(types + type, type.body.value)
        is Type.Union -> type.cases.fold(types, ::flatMerge)
        is Type.MethodRef,
        is Type.SpecializedMethodRef,
        Type.WildCard -> types + type
      }

    private fun flatMergeFix(
      types: PersistentSet<Type<FX>>,
      type: Type<FX>,
    ): PersistentSet<Type<FX>> =
      when (type) {
        is Type.Sym.Fix -> type.cases.fold(types, ::flatMerge)
        is Type.Union -> type.cases.fold(types, ::flatMergeFix)
        is Type.Application -> type.args.fold(types, ::flatMergeFix)
        is Type.Ellipsis -> flatMergeFix(types, type.element)
        is Type.Sym.Invoke -> type.args.fold(flatMergeFix(types, type.receiver), ::flatMergeFix)
        is Type.SpecializedMethodRef -> flatMergeFix(types, type.receiver)
        is Type.Lambda<*> -> {
          val body = type.body
          val bodyType = body.value as Type<FX>
          val bodySymFx = body.effect.invocations as UnboundedSet<Type.Sym<FX>>
          val accFromType = flatMergeFix(types, bodyType)
          val accFromFx = bodySymFx?.fold(accFromType, ::flatMerge) ?: accFromType
          accFromFx
        }
        is Type.Sym,
        is Type.WildCard,
        is Type.MethodRef -> types
      }

    private fun PersistentSet<Type<FX>>.productiveSubstSym(t: Type.Sym<FX>): Type.Sym<FX> =
      when (t) {
        is Type.Sym.Invoke -> {
          val recv = substBy<Type.Sym<FX>>({ productiveSubstSym(it) })(t.receiver)
          val args = t.args.map(substBy({ productiveSubst(it) }))
          when {
            recv !== t.receiver || args.indices.any { args[it] !== t.args[it] } ->
              t.copy(receiver = recv, args = args)
            else -> t
          }
        }
        is Type.Sym.Param,
        is Type.Sym.This,
        is Type.Sym.Rec -> t
        is Type.Sym.Fix -> Type.Sym.Rec // assuming Fix's body have been extracted
      }

    private fun PersistentSet<Type<FX>>.productiveSubst(t: Type<FX>): Type<FX> =
      when (t) {
        is Type.Sym -> productiveSubstSym(t)
        is Type.Application -> {
          val args = t.args.map(substBy { productiveSubst(it) })
          if (args.indices.any { args[it] !== t.args[it] }) t.copy(args = args) else t
        }
        is Type.Union -> t.cases.joinedOver { productiveSubst(it) }
        is Type.Ellipsis ->
          when (val elemSubst = substBy<Type<FX>>({ productiveSubst(it) })(t.element)) {
            t.element -> t
            else -> t.copy(element = elemSubst)
          }
        is Type.Lambda -> {
          val (params, body, intf) = t
          t.copy(
            body =
              body.copy(
                value = substBy<Type<FX>>({ productiveSubst(it) })(body.value),
                effect =
                  body.effect.copy(
                    invocations =
                      body.effect.invocations?.map { invk ->
                        substBy<Type.Sym<FX>>({ productiveSubstSym(it) })(invk)
                      }
                  ),
              )
          )
        }
        is Type.MethodRef,
        is Type.SpecializedMethodRef,
        is Type.WildCard -> t
      }

    private inline fun <T> PersistentSet<Type<FX>>.substBy(crossinline rec: (T) -> T): (T) -> T =
      { target ->
        when {
          target is Type.Union<*> ->
            target.cases.joinedOver {
              (if ((it as Type<FX>) in this) (Type.Sym.Rec as T) else rec(it as T)) as Type<FX>
            } as T
          (target as Type<FX>) in this -> (Type.Sym.Rec as T)
          else -> rec(target)
        }
      }

    /** Checks [this] "grows" from any symbol in [base] */
    private fun Type<FX>.containsAny(base: PersistentSet<Type<FX>>): Boolean =
      when (this) {
        is Type.Union -> cases.any { it.containsAny(base) }
        !is Type.Sym -> false
        in base -> true
        is Type.Sym.Invoke -> receiver.containsAny(base) || args.any { it.containsAny(base) }
        else -> false
      }

    /** Collapse symbolic invocations of the same receiver and method */
    private fun PersistentSet<Type<FX>>.consolidateSymbols(): PersistentSet<Type<FX>> =
      consolidate(
        disassemble = { t: Type.Sym.Invoke<FX> -> (t.receiver to t.method) to t.args },
        reassemble = { (recv, method), args -> Type.Sym.Invoke(recv, method, args) },
        merge = { ts0, ts1 -> (ts0 zip ts1).map { (t0, t1) -> joinOf(t0, t1) } },
      )

    /** Collapse lambdas of the same source code signatures */
    private fun PersistentSet<Type<FX>>.consolidateConstants(): PersistentSet<Type<FX>> =
      consolidate(
        disassemble = { t: Type.Lambda<FX> -> (t.params to t.intf) to t.body },
        reassemble = { (params, intf), body -> Type.Lambda(params, body, intf) },
        merge = { (body0, fx0), (body1, fx1) ->
          Result(widen(body0, body1), effectLattice.widen(fx0, fx1))
        },
      )

    /** Consolidate the set of [X] by potentially merging elements of subtype [Y] */
    private inline fun <X, reified Y : X, K, V : Any> PersistentSet<X>.consolidate(
      disassemble: (Y) -> Pair<K, V>,
      reassemble: (K, V) -> Y,
      merge: (V, V) -> V,
    ): PersistentSet<X> {
      val consolidated = mutableMapOf<K, V>()
      var untouched = persistentSetOf<X>()
      for (x in this) {
        when (x) {
          is Y -> {
            val (key, value) = disassemble(x)
            val consolidatedValue =
              when (val oldValue = consolidated[key]) {
                null -> value
                else -> merge(oldValue, value)
              }
            consolidated[key] = consolidatedValue
          }
          else -> untouched += x
        }
      }
      return when {
        untouched.size + consolidated.size == size -> this // If did nothing, reuse old instance
        else -> consolidated.asSequence().fold(untouched) { acc, (k, v) -> acc + reassemble(k, v) }
      }
    }
  }
}

fun <FX> Lattice<Type<FX>>.widen(t: Type<FX>): Type<FX> =
  when (t) {
    is Type.Sym.Invoke -> {
      val m = t.method
      /**
       * Along a symbolic invocation chain with repeated method, guess the base case(s), then defer
       * to [Lattice.widen] to find an over-approximating inductive description of this invocation.
       */
      fun bases(t: Type<FX>): PersistentSet<Type<FX>> =
        when (t) {
          is Type.Sym.Invoke ->
            when (t.method) {
              m -> persistentSetOf(t)
              else -> bases(t.receiver) + t.args.flatMapToPersistentSet(::bases)
            }
          is Type.Union -> t.cases.flatMapToPersistentSet(::bases)
          else -> persistentSetOf()
        }
      val bases = bases(t.receiver) + t.args.flatMapToPersistentSet(::bases)
      widen(Type.Union(bases), t)
    }
    is Type.Lambda -> {
      val (params, _, intf) = t
      val copies = mutableListOf<Type.Lambda<FX>>()
      fun generalize(t: Type<FX>): Type<FX> =
        when (t) {
          is Type.Lambda -> {
            val t1 = t.copy(body = t.body.copy(value = generalize(t.body.value)))
            when {
              t.intf == intf && t.params == params -> {
                copies.add(t1)
                Type.Sym.Rec
              }
              else -> t1
            }
          }
          is Type.Union -> Type.Union(t.cases.map(::generalize))
          is Type.Application -> t.copy(args = t.args.map(::generalize))
          is Type.Sym.Invoke ->
            t.copy(
              receiver = generalize(t.receiver) as? Type.Sym ?: t.receiver,
              args = t.args.map(::generalize),
            )
          is Type.Sym.Fix -> {
            val (base, ind) = t
            val base1 = base.map(::generalize)
            val ind1 = ind.map(::generalize)
            if (copies.isEmpty()) t else Type.Union(base1 + ind1)
          }
          else -> t
        }
      val summarizedBody = generalize(t.body.value)
      when {
        copies.isEmpty() -> t
        else ->
          Type.Sym.Fix(
            persistentSetOf(),
            persistentSetOf(t.copy(body = t.body.copy(value = summarizedBody))),
          )
      }
    }
    is Type.Union -> widen(Type.None, t.cases.joinedOver(::widen))
    else -> t
  }
