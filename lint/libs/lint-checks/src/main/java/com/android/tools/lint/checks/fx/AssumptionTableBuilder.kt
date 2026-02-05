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
package com.android.tools.lint.checks.fx

import com.android.tools.lint.checks.fx.result.AssumptionTable
import com.android.tools.lint.checks.fx.result.ClassId
import com.android.tools.lint.checks.fx.result.Constraint
import com.android.tools.lint.checks.fx.result.Effect
import com.android.tools.lint.checks.fx.result.MethodId
import com.android.tools.lint.checks.fx.result.ResultTemplate
import com.android.tools.lint.checks.fx.result.Type
import com.android.tools.lint.checks.fx.result.Type.MethodRef
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.rawStatic
import com.android.tools.lint.checks.fx.result.Type.MethodRef.Companion.rawVirtual
import com.android.tools.lint.checks.fx.result.TypeBounds
import com.android.tools.lint.checks.fx.result.subscript
import com.android.tools.lint.checks.fx.utils.Lattice
import kotlin.reflect.KClass
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KFunction4
import kotlin.reflect.KFunction5
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus

open class AssumptionTableBuilder<FX>(lattice: Lattice<FX>) : Lattice<FX> by lattice, TypeBounds<Nothing> by persistentMapOf() {
  private var results: AssumptionTable<FX> = persistentMapOf()

  /** Adds an assumption that [this] has signature [result] */
  infix fun MethodRef.assumedAs(result: ResultTemplate<FX>) {
    val sigParams = method.paramTags
    require(method !in (results[klass] ?: persistentMapOf())) {
      "Method `${method.name}` already assumed as ${results[klass]!![method]}, now as $result"
    }
    require(result.domains.size == sigParams.size + (if (method.isVirtual) 1 else 0)) {
      val domains = result.domains
      "Method `${method.name}` takes ${sigParams.size} parameters $sigParams, but given ${domains.size}: $domains"
    }
    results += klass to (results[klass] ?: persistentMapOf()).put(method, result)
  }

  infix fun Iterable<MethodRef>.assumedAs(result: ResultTemplate<FX>) = forEach { it.assumedAs(result) }

  infix fun Pair<ClassId, String>.assumedVirtual(result: ResultTemplate<FX>) {
    val domains = result.domains
    val receiver = domains.firstOrNull() ?: throw IllegalArgumentException("No receiver")
    val receiverC = receiver.erase()
    require(receiverC == first) { throw IllegalArgumentException("Invalid receiver $receiverC") }
    val rest = domains.subList(1, domains.size)
    val method = MethodId(true, second, rest.map { it.erase() })
    MethodRef(first, method) assumedAs result
  }

  infix fun Pair<ClassId, String>.assumedStatic(result: ResultTemplate<FX>) {
    val method = MethodId(false, second, result.domains.map { it.erase() })
    MethodRef(first, method) assumedAs result
  }

  private fun Type<FX>.erase(): ClassId? =
    when (this) {
      is Type.Application -> constructor
      is Type.Sym.Param,
      is Type.WildCard -> null
      is Type.Ellipsis -> ClassId.Array
      else -> throw IllegalArgumentException("Unexpected: $this")
    }

  /** Assumes a monomorphic method signature, taking the domain from the method descriptor */
  infix fun MethodRef.assumedMonoAs(setUp: ResultTemplateBuilder<FX>.() -> Unit) {
    val (container, sig) = this
    val domains: Array<Type<Nothing>> =
      when {
        sig.isVirtual ->
          Array(1 + sig.paramTags.size) {
            when (it) {
              0 -> Type.Application(container)
              else -> sig.paramTags[it - 1]?.let(Type<Nothing>::Application) ?: Type.WildCard
            }
          }
        else -> Array(sig.paramTags.size) { sig.paramTags[it]?.let(Type<Nothing>::Application) ?: Type.WildCard }
      }
    this assumedAs given(domains = domains, setUp)
  }

  /** Creates a monomorphic signature, [setUp]-ing the type and effect given [domains] */
  fun TypeBounds<Nothing>.given(vararg domains: Type<Nothing>, setUp: ResultTemplateBuilder<FX>.() -> Unit): ResultTemplate<FX> =
    with(ResultTemplateBuilder(this@AssumptionTableBuilder).apply(setUp)) {
      ResultTemplate(this@given, domains.asList(), range, Effect(concreteEffect, symbolicInvocations, constraint))
    }

  /** Creates a polymorphic signature, introducing unbounded type parameter */
  fun TypeBounds<Nothing>.forAll(make: TypeBounds<Nothing>.(Type.Sym<Nothing>) -> ResultTemplate<FX>) =
    uncheckedForAll(persistentSetOf()) { (x) -> make(x) }

  fun TypeBounds<Nothing>.forAll(make: TypeBounds<Nothing>.(Type.Sym<Nothing>, Type.Sym<Nothing>) -> ResultTemplate<FX>) =
    uncheckedForAll(persistentSetOf(), persistentSetOf()) { (x0, x1) -> make(x0, x1) }

  /** Creates a polymorphic signature, introducing type parameter bounded by [X] */
  @JvmName("forAllReified")
  inline fun <reified X : Any> TypeBounds<Nothing>.forAll(noinline make: TypeBounds<Nothing>.(Type.Sym<Nothing>) -> ResultTemplate<FX>) =
    forAll(X::class(), make)

  /** Creates a polymorphic signature, introducing type parameter bounded by [bound] */
  fun TypeBounds<Nothing>.forAll(bound: Type<Nothing>, make: TypeBounds<Nothing>.(Type.Sym<Nothing>) -> ResultTemplate<FX>) =
    uncheckedForAll(persistentSetOf(bound)) { (x) -> make(x) }

  /** Creates a polymorphic signature introducing type parameter bounded by [boundList] */
  private fun TypeBounds<Nothing>.uncheckedForAll(
    vararg boundList: PersistentSet<Type<Nothing>>,
    make: TypeBounds<Nothing>.(List<Type.Sym<Nothing>>) -> ResultTemplate<FX>,
  ): ResultTemplate<FX> {
    val names = Array(boundList.size) { i -> "x${(size + i).subscript()}" }
    val extendedContext = (names zip boundList).fold(this) { acc, bound -> acc + bound }
    return extendedContext.make(names.map(Type.Sym<Nothing>::Param))
  }

  open class Container<FX>(val containerFqn: String) {
    val self = ClassId.of(containerFqn)
    val prefix = with(containerFqn) { substring(0, lastIndexOf('.')) }

    fun static(name: String, vararg params: ClassId?) = rawStatic(name, containerFqn, *params)

    fun virtual(name: String, vararg params: ClassId?) = rawVirtual(name, containerFqn, *params)

    operator fun String.unaryPlus() = self to this

    companion object {
      fun <FX, C : Container<FX>> with(vararg containers: C, setUp: C.() -> Unit) {
        for (container in containers) with(container, setUp)
      }
    }
  }

  open class Pkg<FX>(val path: String) {
    fun klass(name: String) = object : Container<FX>("$path.$name") {}

    fun child(name: String) = object : Pkg<FX>("$path.$name") {}
  }

  companion object {
    fun <FX> Lattice<FX>.build(setUp: AssumptionTableBuilder<FX>.() -> Unit): AssumptionTable<FX> =
      AssumptionTableBuilder(this).apply(setUp).results
  }
}

class ResultTemplateBuilder<FX>(lattice: Lattice<FX>) {
  var range: Type<FX> = Type.Unit
  var concreteEffect: FX = lattice.bottom
  var symbolicInvocations: PersistentSet<Type.Sym<FX>> = persistentSetOf()
  var constraint: Constraint<FX> = Constraint()
}

/** Build symbolic invocation with receiver [this] and no other argument */
operator fun <FX> Type.Sym<FX>.get(method: KFunction1<*, *>) = Type.Sym.Invoke(this, MethodId.ofVirtual(method), listOf())

/** Build symbolic invocation with receiver [this] and 1 other argument [x0] */
operator fun <FX> Type.Sym<FX>.get(method: KFunction2<*, *, *>, x0: Type<FX>) =
  Type.Sym.Invoke(this, MethodId.ofVirtual(method), listOf(x0))

/** Build symbolic invocation with receiver [this] and 2 other arguments [x0], [x1] */
operator fun <FX> Type.Sym<FX>.get(method: KFunction3<*, *, *, *>, x0: Type<FX>, x1: Type<FX>) =
  Type.Sym.Invoke(this, MethodId.ofVirtual(method), listOf(x0, x1))

/** Build symbolic invocation with receiver [this] and 3 other arguments [x0], [x1], [x2] */
operator fun <FX> Type.Sym<FX>.get(method: KFunction4<*, *, *, *, *>, x0: Type<FX>, x1: Type<FX>, x2: Type<FX>) =
  Type.Sym.Invoke(this, MethodId.ofVirtual(method), listOf(x0, x1, x2))

/** Build symbolic invocation with receiver [this] and 4 other arguments [x0], [x1], [x2], [x3] */
operator fun <FX> Type.Sym<FX>.get(method: KFunction5<*, *, *, *, *, *>, x0: Type<FX>, x1: Type<FX>, x2: Type<FX>, x3: Type<FX>) =
  Type.Sym.Invoke(this, MethodId.ofVirtual(method), listOf(x0, x1, x2, x3))

/**
 * Build symbolic invocation with receiver [this] and some other arguments [xs]. Because the method reference [method] is dynamically
 * constructed, the arity check is at runtime.
 */
operator fun <FX> Type.Sym<FX>.get(method: MethodId, vararg xs: Type<FX>): Type.Sym.Invoke<FX> {
  require(method.paramTags.size == xs.size) {
    "Method `${method.name}` expects ${method.paramTags.size} params ${method.paramTags}, given ${xs.size}: ${xs.asList()}"
  }
  return Type.Sym.Invoke(this, method, xs.asList())
}

/** Use the metalanguage's class tag as the object language's type constructor, with a run-time arity check. */
operator fun <FX, C : Any> KClass<C>.invoke(vararg ts: Type<FX>): Type.Application<FX> {
  require(typeParameters.size == ts.size) {
    "Type constructor `$simpleName` takes ${typeParameters.size} parameters, given ${ts.size}: ${ts.asList()}"
  }
  return Type.Application(ClassId.of(this), ts.asList())
}

operator fun <FX> ClassId.invoke(vararg ts: Type<FX>): Type.Application<FX> = Type.Application(this, ts.asList())
