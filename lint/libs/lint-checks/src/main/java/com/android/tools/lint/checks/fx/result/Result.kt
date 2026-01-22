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

import com.android.tools.lint.checks.fx.utils.InterningPool
import com.android.tools.lint.checks.fx.utils.Lattice
import com.android.tools.lint.checks.fx.utils.UnboundedSet
import com.android.tools.lint.checks.fx.utils.map
import com.android.tools.lint.checks.fx.utils.partitionToPersistentSets
import com.android.tools.lint.checks.fx.utils.unboundedSetOf
import com.intellij.psi.PsiMethod
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KFunction0
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
import kotlin.reflect.KFunction3
import kotlin.reflect.KFunction4
import kotlin.reflect.KFunction5
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaMethod
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

sealed interface Instantiable<out FX>

/** A [Type] is parameterized by the effect [FX] that methods can have. */
sealed interface Type<out FX> {
  data class Application<out FX>(val constructor: ClassId, val args: List<Type<FX>> = listOf()) :
    Type<FX> {
    internal constructor(
      classFqn: String,
      args: List<Type<FX>> = listOf(),
    ) : this(ClassId.of(classFqn), args)

    override fun toString(): String {
      val func = constructor.toString()
      return if (args.isEmpty()) func else "$func<${args.joinToString()}>"
    }
  }

  data class Ellipsis<out FX>(val element: Type<FX>) : Type<FX> {
    override fun toString() = "$element…"
  }

  data object WildCard : /* TODO bound? */ Type<Nothing> {
    override fun toString() = "●"
  }

  data class Union<out FX> private constructor(val cases: PersistentSet<Type<FX>>) : Type<FX> {
    override fun toString() =
      when {
        cases.isEmpty() -> "∅"
        else -> cases.joinToString(" ∪ ")
      }

    companion object {
      internal val Empty: Union<Nothing> = Union(persistentSetOf())

      operator fun <FX> invoke(cases: PersistentSet<Type<FX>>): Type<FX> =
        when (cases.size) {
          0 -> Empty
          1 -> cases.first()
          else -> Union(cases)
        }
    }
  }

  /**
   * It seems confusing that we need a dedicated representation for [Lambda] even though we already
   * take the first-order, "closure-converted" view of the program, and have [MethodRef]. The
   * [Lambda] form is the reason [Type] (and many other classes) need parameterizing by [FX].
   *
   * But [Lambda] can do things that a lifted global function can't (e.g. early non-local return),
   * and has restrictions compared to general methods (e.g. no calling to self, no introducing new
   * type parameters, etc.). Having [Lambda] seems more straightforward for now.
   *
   * If we ever explore the route of having [MethodRef] only, we'll need to generalize
   * [SpecializedMethodRef] to carry around an arbitrary partial substitution, corresponding to a
   * returned, partially substituted closure.
   */
  data class Lambda<out FX>(
    val params: List<Type<FX>>,
    val body: Result<Type<FX>, Effect<FX>>,
    val intf: ClassId?,
  ) : Type<FX>, Instantiable<FX> {
    override fun toString() =
      "⟪${if (intf != null) "$intf : " else ""}(${params.joinToString()}) -> $body⟫"
  }

  data class MethodRef(val klass: ClassId, val method: MethodId) :
    Type<Nothing>, Point<Nothing>, Instantiable<Nothing> {
    constructor(method: PsiMethod) : this(ClassId.of(method.containingClass!!), MethodId(method))

    override fun toString() = "$klass::$method"

    companion object {
      @JvmName("virtual0") fun virtual(method: KFunction1<*, *>) = uncheckedVirtual(method)

      @JvmName("virtual1") fun <X0> virtual(method: KFunction2<*, X0, *>) = uncheckedVirtual(method)

      @JvmName("virtual2")
      fun <X0, X1> virtual(method: KFunction3<*, X0, X1, *>) = uncheckedVirtual(method)

      @JvmName("virtual3")
      fun <X0, X1, X2> virtual(method: KFunction4<*, X0, X1, X2, *>) = uncheckedVirtual(method)

      @JvmName("virtual4")
      fun <X0, X1, X2, X3> virtual(method: KFunction5<*, X0, X1, X2, X3, *>) =
        uncheckedVirtual(method)

      @JvmName("static0") fun static(method: KFunction0<*>) = uncheckedStatic(method)

      @JvmName("static1") fun <X0> static(method: KFunction1<X0, *>) = uncheckedStatic(method)

      @JvmName("static2")
      fun <X0, X1> static(method: KFunction2<X0, X1, *>) = uncheckedStatic(method)

      @JvmName("static3")
      fun <X0, X1, X2> static(method: KFunction3<X0, X1, X2, *>) = uncheckedStatic(method)

      @JvmName("static4")
      fun <X0, X1, X2, X3> static(method: KFunction4<X0, X1, X2, X3, *>) = uncheckedStatic(method)

      @JvmName("static5")
      fun <X0, X1, X2, X3, X4> static(method: KFunction5<X0, X1, X2, X3, X4, *>) =
        uncheckedStatic(method)

      private fun uncheckedVirtual(method: KFunction<*>): MethodRef {
        val receiver =
          method.parameters.firstOrNull()?.takeIf { it.kind == KParameter.Kind.INSTANCE }
            ?: throw IllegalArgumentException("$method is static")
        return MethodRef(
          ClassId.of(receiver.type.classifier as KClass<*>),
          MethodId.ofVirtual(method),
        )
      }

      // TODO for some reason, `kotlin.collections.CollectionsKt` show up as either
      //  `kotlin.collections.CollectionsKt___CollectionsKt` or
      //  `kotlin.collections.CollectionsKt` in tests and android studio.
      //   So we're adding both for now
      private fun uncheckedStatic(method: KFunction<*>): List<MethodRef> {
        val methodId = MethodId.ofStatic(method)
        fun methodRef(classFqn: String) = MethodRef(ClassId.of(classFqn), methodId)
        val rawName = method.javaMethod!!.declaringClass.canonicalName!!
        val ktTruncatedName = run {
          val lastDot = rawName.lastIndexOf('.')
          require(lastDot >= 0)
          val lastUnderscore = rawName.lastIndexOf('_')
          if (lastUnderscore == -1) return@run null
          if (lastUnderscore + 1 !in rawName.indices) return@run null
          "${rawName.substring(0, lastDot)}.${rawName.substring(lastUnderscore + 1, rawName.length)}"
        }
        return listOfNotNull(methodRef(rawName), ktTruncatedName?.let(::methodRef))
      }
    }
  }

  data class SpecializedMethodRef<out FX>(val receiver: Type<FX>, val ref: MethodRef) : Type<FX> {
    override fun toString() = "$receiver@${ref.method}"
  }

  sealed interface Sym<out FX> : Type<FX> {
    data object Rec : Sym<Nothing> {
      override fun toString() = "\uD835\uDEC2"
    }

    class Param(name: String) : Sym<Nothing> {
      val name = InterningPool.string(name)

      override fun equals(other: Any?) = other is Param && name === other.name

      override fun hashCode() = System.identityHashCode(name)

      init {
        require(!name.isReceiverName()) { "Should be `This`" }
      }

      override fun toString() = name.bold()
    }

    data class This(val site: ClassId) : Sym<Nothing> {
      val uniqueName = "\$this\$$site"

      override fun toString() = "this"
    }

    class Invoke<out FX>(receiver: Sym<FX>, method: MethodId, args: List<Type<FX>>) : Sym<FX> {
      val receiver: Sym<FX> = receiverPool.intern(receiver) as Sym<FX>
      val method: MethodId = methodPool.intern(method) as MethodId
      val args: List<Type<FX>> = argListPool.intern(args) as List<Type<FX>>

      override fun equals(other: Any?) =
        other is Invoke<*> &&
          receiver === other.receiver &&
          method === other.method &&
          args === other.args

      override fun hashCode() =
        31 * (31 * System.identityHashCode(receiver) + System.identityHashCode(method)) +
          System.identityHashCode(args)

      fun copy(
        receiver: Sym<@UnsafeVariance FX> = this.receiver,
        args: List<Type<@UnsafeVariance FX>> = this.args,
      ): Invoke<FX> = Invoke(receiver, method, args)

      override fun toString() = "$receiver.$method(${args.joinToString()})"

      companion object {
        private val receiverPool = InterningPool<Sym<*>>()
        private val methodPool = InterningPool<MethodId>()
        private val argListPool = InterningPool<List<Type<*>>>()
      }
    }

    data class Fix<out FX>
    internal constructor(
      val baseCases: PersistentSet<Type<FX>>,
      val inductiveCases: PersistentSet<Type<FX>>, // that refer to 1+ `Rec`
    ) : Sym<FX> {
      init {
        if (inductiveCases.isEmpty()) throw TrivialInduction(baseCases)
      }

      class TrivialInduction(val cases: PersistentSet<Type<*>>) : Exception()

      val cases: Sequence<Type<FX>>
        get() = baseCases.asSequence() + inductiveCases.asSequence()

      override fun toString() = "(μ\uD835\uDEC2. ${cases.joinToString(" ∪ ")})"

      companion object {
        operator fun <FX> invoke(cases: Collection<Type<FX>>): Fix<FX> {
          val (indCases, baseCases) = cases.partitionToPersistentSets { it.hasFreeRec() }
          return Fix(baseCases, indCases.remove(Rec))
        }

        internal fun <FX> Type<FX>.hasFreeRec(): Boolean =
          when (this) {
            is Application -> args.any { it.hasFreeRec() }
            is Lambda ->
              body.value.hasFreeRec() || body.effect.invocations?.any { it.hasFreeRec() } == true
            is Union -> cases.any { it.hasFreeRec() }
            is SpecializedMethodRef -> receiver.hasFreeRec()
            is Rec -> true
            is Invoke -> receiver.hasFreeRec() || args.any { it.hasFreeRec() }
            is Ellipsis<*> -> element.hasFreeRec()
            is Param,
            is This,
            is Fix,
            is MethodRef,
            is WildCard -> false
          }

        private fun <FX> Sym<FX>.substSym(base: PersistentSet<Sym<FX>>): PersistentSet<Sym<FX>> =
          when (this) {
            is Rec -> base
            is Param,
            is This -> persistentSetOf(this)
            is Invoke -> {
              val substReceivers = receiver.substSym(base)
              val substArgs = args.map { it.subst(base) }
              substReceivers.map { copy(receiver = it, args = substArgs) }
            }
            is Fix -> throw IllegalStateException("Nested inductive set not expected")
          }

        private fun <FX> Type<FX>.subst(base: PersistentSet<Sym<FX>>): Type<FX> =
          when (this) {
            is Application,
            is Ellipsis,
            is WildCard,
            is MethodRef -> this
            is Union -> Union(cases.map { it.subst(base) })
            is Lambda -> Lambda(params, body.copy(value = body.value.subst(base)), intf)
            is SpecializedMethodRef -> copy(receiver = receiver.subst(base))
            is Sym -> Union(substSym(base))
          }
      }
    }

    companion object {
      internal val <FX> Sym<FX>.chain: Pair<String, List<MethodId>>
        get() =
          when (this) {
            is Param -> name to listOf()
            is This -> toString() to listOf()
            is Invoke -> receiver.chain.let { (x, ms) -> x to ms + method }
            is Rec,
            is Fix -> throw java.lang.IllegalStateException()
          }
    }
  }

  companion object {
    val None: Type<Nothing> = Union.Empty

    // TODO ensure distinct. Sloppy for debugging for now.
    fun genParam(hint: String): Sym.Param = Sym.Param(hint)

    fun ofLiteral(value: Any?): Type<Nothing> =
      when (value) {
        null -> None // TODO: ignoring `null` for now, so what's left is `Nothing`
        is Boolean -> Boolean
        is Char -> Char
        is Byte -> Byte
        is Short -> Short
        is Int -> Int
        is Long -> Long
        is Float -> Float
        is Double -> Double
        is String -> String
        is UByte -> UByte
        is UShort -> UShort
        is UInt -> UInt
        is ULong -> ULong
        else -> throw IllegalStateException("Unexpected value literal: $value")
      }

    // For each type, we're conflating Java's boxed and unboxed variant, and Kotlin's variant
    val Boolean = Application<Nothing>(ClassId.of<Boolean>())
    val Int = Application<Nothing>(ClassId.of<Int>())
    val Char = Application<Nothing>(ClassId.of<Char>())
    val Byte = Application<Nothing>(ClassId.of<Byte>())
    val Short = Application<Nothing>(ClassId.of<Short>())
    val Long = Application<Nothing>(ClassId.of<Long>())
    val Float = Application<Nothing>(ClassId.of<Float>())
    val Double = Application<Nothing>(ClassId.of<Double>())
    val String = Application<Nothing>(ClassId.of<String>())
    val Unit = Application<Nothing>(ClassId.of<Unit>())
    val EmptyArray = Application<Nothing>(ClassId.Array, listOf(None))
    val UByte = Application<Nothing>(ClassId.of<UByte>())
    val UShort = Application<Nothing>(ClassId.of<UShort>())
    val UInt = Application<Nothing>(ClassId.of<UInt>())
    val ULong = Application<Nothing>(ClassId.of<ULong>())
  }
}

/** Erase a type's generic parameters for use in JVM's method descriptors */
fun Type<*>.erased(): ClassId? =
  when (this) {
    is Type.Application -> constructor
    is Type.Ellipsis -> ClassId.Array
    is Type.WildCard,
    is Type.Lambda,
    is Type.MethodRef,
    is Type.SpecializedMethodRef,
    is Type.Sym -> null
    is Type.Union -> throw IllegalStateException()
  }

/**
 * An [Effect] has the [concrete] effect, the symbolic [invocations] of virtual methods, and the
 * constraints on symbolic invocations.
 */
data class Effect<out FX>(
  val concrete: FX,
  val invocations: UnboundedSet<Type.Sym<FX>> = unboundedSetOf(),
  val constraint: Constraint<FX> = Constraint.Companion.MostPermissive,
) {
  override fun toString(): String {
    val fx =
      when {
        invocations == null -> "⊤"
        invocations.isEmpty() -> concrete.toString()
        else -> "$concrete ⊔ ${invocations.joinToString(" ⊔ ")}"
      }
    return "$fx${constraint.prettyPrint()}"
  }
}

/** A [Result] of [T] paired with effect [FX] */
data class Result<out T, out FX>(val value: T, val effect: FX) {
  override fun toString() = "$value @ $effect"

  companion object {
    fun <T, FX> domain(onValue: Lattice<T>, onEffects: Lattice<FX>): Lattice<Result<T, FX>> =
      Lattice.product(::Result, Result<T, FX>::value, Result<T, FX>::effect, onValue, onEffects)
  }
}

/**
 * A [Point] is either a [Type.MethodRef] whose summary is polymorphic, or an [Instantiation] whose
 * summary is monomorphic
 */
sealed interface Point<out FX> {
  data class Instantiation<out FX>(val method: Instantiable<FX>, val args: List<Type<FX>>) :
    Point<FX> {
    override fun toString() = "$method @ (${args.joinToString()})"
  }
}
