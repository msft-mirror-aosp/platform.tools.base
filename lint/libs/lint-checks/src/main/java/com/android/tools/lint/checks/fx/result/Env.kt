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

import com.android.tools.lint.checks.fx.result.Env.Companion.withReceiver
import com.android.tools.lint.checks.fx.result.Env.Companion.withVar
import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.checks.fx.utils.assoc
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * An environment tracks information on lexically scoped identifiers (e.g. type and term variables,
 * local functions, and receivers). Because the analysis is more precise than the host's type
 * system, it needs to track its own environment instead of just querrying UAST. For example, within
 * the following function's body, variable `r` has a parameterized type, not just `Runnable`:
 * ```
 * // implicitly: fun<R : Runnable> callIt(r: R) = ...
 * fun callIt(r: Runnable) = r.run()
 * ```
 */
internal data class Env<out FX>(
  val types: TypeBounds<FX>,
  val vars: TermEnv<FX>,
  // Local functions and receivers are different: They aren't shadowed by names. So we keep all.
  val funs: FunEnv,
  val virtualReceivers: PersistentMap<ClassId, Type<FX>>,
) {
  override fun toString() =
    "[" +
      types.asSequence().joinToString { (l, rs) -> showBound(l, rs) } +
      "; " +
      vars.asSequence().joinToString { (x, t) -> "$x : $t" } +
      "; " +
      funs.asSequence().joinToString { (f, ts) -> "$f : [${ts.joinToString()}]" } +
      "; " +
      "this: ${virtualReceivers.asSequence().joinToString { (l, t) -> "@$l -> $t" } }" +
      "]"

  fun varAt(name: String): Type<FX>? =
    when {
      name.isReceiverName() -> throw IllegalArgumentException("call `receiver()` instead")
      else -> vars[name]?.widenedByBound()
    }

  fun funAt(
    name: String,
    isRightOverloading: (Type.MethodRef) -> Boolean = { true },
  ): Type.MethodRef? =
    funs[name]?.let { overloadings ->
      when {
        overloadings.size == 1 -> overloadings.first()
        else -> overloadings.findLast(isRightOverloading)
      }
    }

  fun receiver(site: ClassId): Type<FX>? =
    if (areAllVarsFinal) virtualReceivers[site]?.bound()
    else virtualReceivers[site]?.widenedByBound()

  fun receiver(label: String): Type<FX>? {
    val recv =
      virtualReceivers.entries.findLast { (c, _) -> c.fqn?.endsWith(label) == true }?.value
        ?: return null
    return if (areAllVarsFinal) recv.bound() else recv.widenedByBound()
  }

  // big UX hack mitigating redundant error
  // TODO more principled elimination of confusing redundant reports
  private val areAllVarsFinal: Boolean by
    lazy(LazyThreadSafetyMode.NONE) { vars.values.all { it is Type.Application } }

  // TODO hack
  internal fun innermostExtensionReceiver(): Type<FX>? =
    vars.entries.findLast { (x, _) -> x.isExtensionReceiverName() }?.value

  fun isPureRenaming() =
    vars.all { (_, t) -> t is Type.Sym } && virtualReceivers.all { (_, t) -> t is Type.Sym }

  private fun Type<FX>.widenedByBound(): Type<FX> {
    val b =
      when (this) {
        is Type.Sym.Param -> types[name]
        is Type.Sym.This -> types[uniqueName]
        else -> null
      }
    return if (b == null) this else Type.Union(b.add(this))
  }

  private fun Type<FX>.bound(): Type<FX> =
    when (this) {
      is Type.Sym.Param -> types[name]?.let { Type.Union(it) } ?: this
      is Type.Sym.This -> types[uniqueName]?.let { Type.Union(it) } ?: this
      else -> this
    }

  companion object {
    val empty = Env<Nothing>(persistentMapOf(), emptyEnv(), persistentMapOf(), persistentMapOf())

    internal fun <FX> Env<FX>.withReceiver(site: ClassId, type: Type<FX>): Env<FX> =
      copy(virtualReceivers = virtualReceivers.put(site, type))

    internal fun <FX> Env<FX>.withVar(name: String, type: Type<FX>): Env<FX> =
      when {
        name.isReceiverName() -> throw IllegalArgumentException("call `withReceiver` instead")
        else -> copy(vars = vars.put(name, type))
      }

    internal fun <FX> Env<FX>.withFun(name: String, type: Type.MethodRef): Env<FX> =
      copy(funs = funs.put(name, (funs[name] ?: persistentListOf()).add(type)))

    internal fun <FX> Env<FX>.withVars(bindings: Collection<Pair<String, Type<FX>>>): Env<FX> =
      copy(vars = bindings.assoc(vars) { it })

    internal fun <FX> bindParams(
      typeLattice: Lattice<Type<FX>>,
      typeBounds: TypeBounds<FX>,
      params: List<Type<FX>>,
      args: List<Type<FX>>,
    ): Env<FX> {
      var env = empty.unify(typeLattice, params, args)
      for ((param, arg) in params zip args) {
        if (param !is Type.Sym.Param) continue
        val bounds = typeBounds[param.name] ?: continue

        fun unify(arg: Type.Application<FX>) = { bound: Type.Application<FX> ->
          if (bound.constructor == arg.constructor)
            for ((x, y) in bound.args zip arg.args) env = env.unify(typeLattice, x, y)
        }

        fun unify(arg: Type.Union<FX>) = { bound: Type.Application<FX> ->
          for (rhs in arg.cases) if (rhs is Type.Application) unify(rhs)(bound)
        }

        val unifyWithArg: (Type.Application<FX>) -> Unit =
          when (arg) {
            is Type.Application -> unify(arg)
            is Type.Union -> unify(arg)
            else -> continue
          }

        for (bound in bounds) if (bound is Type.Application) unifyWithArg(bound)
      }
      return env
    }
  }
}

typealias TypeBounds<FX> = PersistentMap<String, PersistentSet<Type<FX>>>

/** A mapping from variable name to type. Order matters! */
internal typealias TermEnv<FX> = PersistentMap<String, Type<FX>>

/** A mapping from function name to 1+ overloadings. Order matters!. */
private typealias FunEnv = PersistentMap<String, PersistentList<Type.MethodRef>>

private fun <FX> emptyEnv(): TermEnv<FX> = persistentMapOf()

private fun <FX> Env<FX>.unify(
  typeLattice: Lattice<Type<FX>>,
  params: List<Type<FX>>,
  args: List<Type<FX>>,
): Env<FX> {
  val lastParam = params.lastOrNull()
  val arityChecks =
    params.size == args.size || lastParam is Type.Sym.Param && lastParam.name == "\$completion"
  return when {
    arityChecks -> (params zip args).fold(this) { env, (l, r) -> env.unify(typeLattice, l, r) }
    else -> {
      println(
        "Warning: mismatched arity: (${args.joinToString()}) supplied to (${params.joinToString()})"
      )
      params.fold(this) { env, x -> env.unify(typeLattice, x, Type.None) }
    }
  }
}

private fun <FX> Env<FX>.unify(
  typeLattice: Lattice<Type<FX>>,
  lhs: Type<FX>,
  rhs: Type<FX>,
): Env<FX> =
  when (rhs) {
    is Type.Union -> rhs.cases.fold(this) { env, case -> env.unify(typeLattice, lhs, case) }
    else ->
      when (lhs) {
        is Type.Sym.Param ->
          when (val existing = varAt(lhs.name)) {
            null -> withVar(lhs.name, rhs)
            rhs -> this
            // We assume the program's already type-checked. So calling this "unification" was
            // misleading.
            // It's about collecting concrete types that the parameters may instantiate to.
            else -> withVar(lhs.name, typeLattice.joinOf(existing, rhs))
          }
        is Type.Sym.This ->
          when (val existing = receiver(lhs.site)) {
            null -> withReceiver(lhs.site, rhs)
            rhs -> this
            else -> withReceiver(lhs.site, typeLattice.joinOf(existing, rhs))
          }
        is Type.Application ->
          when (rhs) {
            is Type.Application ->
              when {
                lhs.constructor == rhs.constructor ->
                  when {
                    // TODO hack. Sometimes we only get raw type from `.getExpressionType()`
                    lhs.args.isEmpty() || rhs.args.isEmpty() -> this
                    else -> unify(typeLattice, lhs.args, rhs.args)
                  }
                else -> this // TODO
              }
            else -> this // TODO
          }
        is Type.Ellipsis ->
          when (rhs) {
            is Type.Ellipsis -> unify(typeLattice, lhs.element, rhs.element)
            is Type.Application ->
              when (rhs.constructor) {
                ClassId.Array ->
                  when (val elem = rhs.args.firstOrNull()) {
                    null ->
                      this // TODO hack. Sometimes we only get raw type from `.getExpressionType()`
                    else -> unify(typeLattice, lhs.element, elem)
                  }
                else -> unify(typeLattice, lhs.element, rhs) // TODO??
              }
            else -> throw IllegalStateException("Cannot unify: $lhs with $rhs")
          }
        is Type.Lambda,
        is Type.MethodRef,
        is Type.SpecializedMethodRef,
        is Type.Sym.Invoke,
        is Type.Union,
        is Type.WildCard,
        is Type.Sym.Rec,
        is Type.Sym.Fix ->
          if (typeLattice.precede(rhs, lhs)) this
          else throw IllegalStateException("Cannot unify: $lhs with $rhs")
      }
  }

internal fun showBound(name: String, bounds: Set<Type<*>>): String =
  if (bounds.isEmpty()) name else "$name ≼ ${bounds.joinToString(" ∩ ")}"

internal fun TypeBounds<*>.format(): String =
  asSequence().joinToString { (x, b) -> showBound(x, b) }

internal fun String.isReceiverName() =
  this == "this" // || this == "<this>" || this.contains("\$this")

internal fun String.isExtensionReceiverName() = this == "<this>" || this.startsWith("\$this")
