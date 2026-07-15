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
package com.android.tools.lint.checks.fx.utils

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

typealias Memo<K, V> = PersistentMap<K, V>

/**
 * A [Monotone] on functions ([K] -> [V]), where [V] forms a lattice. This is conceptually ([K] -> [V]) -> ([K] -> [V]), but the uncurried
 * form is more convenient.
 *
 * An instance `F` of [Monotone] is also expected to satisfy that if `f ⊑ g` then `F(f) ⊑ F(g)`, (or, more spelled out: ∀ `k`, if `f(k) ⊑
 * g(k)` then `F(f)(k) ⊑ F(g)(k)`).
 */
interface Monotone<K, V> : Lattice<V>, ((K) -> V, K) -> V, DependentMonotone<K, V> {
  override fun latticeAt(point: K) = this
}

/**
 * A [DependentMonotone] is a generalization of [Monotone], over functions that can map each argument to an element belonging to a different
 * lattice.
 *
 * This is needed for when we need to encode a family of mutually recursively functions with different domains and (lattice) ranges. Kotlin
 * doesn't allow expressing dependencies between types, so we squash everything into [V].
 */
interface DependentMonotone<K, V> : ((K) -> V, K) -> V {
  fun latticeAt(point: K): Lattice<V>
}

/**
 * Compute the least fix point of a [Monotone] : ([K] -> [V]) -> ([K] -> [V]) of functions, starting from a [bootstrap] (default being `_ ↦
 * ⊥`), over a given [domain] of interest.
 *
 * In order for [leastFixPoint] to converge: (1) The domain spanned by [domain] must be finite (i.e. the [Monotone]'s implementation
 * shouldn't keep applying its bootstrapping procedure to fresh arguments), although it's ok for [domain] to span larger as computation
 * progresses. (2) the lattice on [V] must have a finite height.
 *
 * The [Monotone] implementation has the freedom to call its bootstrapping function as much or as little as it wants, as long as it runs
 * finitely. Calling the bootstrapping function too often will only produce excessive and ineffective "checkpoints", while too little will
 * result in suboptimal sharing of intermediate results.
 *
 * An example of a reasonable [Monotone] implementation is an abstract interpreter or type inference that structurally recurses on
 * expressions, but relies on the bootstrapping function for assumptions on top-level bindings.
 */
fun <K : Any, V> DependentMonotone<K, V>.leastFixPoint(domain: Collection<K>, bootstrap: Memo<K, V> = persistentMapOf()): Memo<K, V> {
  tailrec fun loop(domain: Collection<K>, bootstrap: Memo<K, V>): Memo<K, V> {
    if (domain.isEmpty()) return bootstrap
    val (remaining, learned) = step(domain, bootstrap)
    return loop(remaining, learned)
  }
  return loop(domain, bootstrap)
}

fun <K : Any, V> DependentMonotone<K, V>.leastFixPoint(vararg points: K): Memo<K, V> = leastFixPoint(points.asList())

/**
 * Given a [Monotone] describing a function ([K] -> [V]) and a [bootstrap], make progress computing the function's mappings over the
 * [domain] of interest.
 *
 * @return a more computed function that subsumes [bootstrap], paired with parts of [domain] that need re-iteration.
 */
private fun <K : Any, V> DependentMonotone<K, V>.step(domain: Collection<K>, bootstrap: Memo<K, V>): Pair<Collection<K>, Memo<K, V>> {
  val learned = bootstrap.builder()
  val cacheDependents = hashMapOf<K, MutableSet<K>>()
  val cacheUpdates = mutableSetOf<K>()
  val cacheUpdateTriggers = hashMapOf<K, MutableSet<K>>()

  class Step(private val caller: K?) : (K) -> V {
    override fun invoke(point: K): V =
      when (val deps = cacheDependents[point]) {
        null -> {
          cacheDependents[point] = if (caller != null) mutableSetOf(caller) else mutableSetOf()
          val lattice = latticeAt(point)
          val knownAnswer = if (point in bootstrap) bootstrap[point] as V else lattice.bottom
          when (val iteratedAnswer = lattice.joinOf(knownAnswer, invoke(Step(point), point))) {
            knownAnswer -> knownAnswer
            else ->
              iteratedAnswer.also {
                cacheUpdates.add(point)
                learned[point] = iteratedAnswer
              }
          }
        }
        else -> {
          if (caller != null) {
            deps.add(caller)
            if (point !in cacheUpdates) cacheUpdateTriggers.getOrPut(point, ::mutableSetOf).add(caller)
          }
          if (point in learned) learned[point] as V else latticeAt(point).bottom
        }
      }
  }

  for (d in domain) Step(null)(d)

  val invalidated = buildSet {
    fun visit(k: K) {
      if (add(k)) cacheDependents[k]?.forEach(::visit)
    }
    for (p in cacheUpdates) cacheUpdateTriggers[p]?.forEach(::visit)
  }

  return domain.filter(invalidated::contains) to learned.build()
}
