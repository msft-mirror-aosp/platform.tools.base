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
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression

/** An internal representation of a method descriptor, identifying the name and overloading. */
class MethodId(val isVirtual: Boolean, name: String, paramTags: List<ClassId?>) {
  val name = InterningPool.string(name)
  val paramTags = paramListPool.intern(paramTags)

  override fun equals(other: Any?) =
      other is MethodId && name === other.name && isVirtual == other.isVirtual && paramTags === other.paramTags

  override fun hashCode() = 31 * (31 * isVirtual.hashCode() + System.identityHashCode(paramTags)) + System.identityHashCode(name)

  override fun toString(): String = "$name${(paramTags.hashCode() % 1000).subscript()}"

  companion object {
    private val paramListPool = InterningPool<List<ClassId?>>()

    operator fun invoke(method: PsiMethod): MethodId =
        when {
          method.name == "invoke" && method.containingClass?.qualifiedName?.startsWith("kotlin.jvm.functions.Function") == true ->
              Invoke[method.parameterList.parametersCount]
          else -> {
            val typeParams = buildSet {
              for (x in method.containingClass?.typeParameters ?: arrayOf()) x.name?.let(::add)
              for (x in method.typeParameters) x.name?.let(::add)
            }
            val isVirtual = !method.isStatic() && !method.isConstructor
            val params = method.parameterList.parameters.map { PsiTypeAdapter.translate(typeParams, it.type).erased() }
            MethodId(isVirtual, method.name, params)
          }
        }

    operator fun invoke(method: KtNamedFunction): MethodId {
      val typeParams = buildSet {
        for (x in method.containingClass()?.typeParameters ?: listOf()) x.name?.let(::add)
        for (x in method.typeParameters) x.name?.let(::add)
      }
      val params = method.valueParameters.map { KtTypeReferenceAdapter.translate(typeParams, it.typeReference).erased() }
      return MethodId(false, method.name!!, params)
    }

    fun ofVirtual(method: KFunction<*>): MethodId {
      require(method.parameters.isNotEmpty() && method.parameters[0].kind == KParameter.Kind.INSTANCE) { "Method ${method.name} is static" }
      return MethodId(
          true,
          method.name,
          method.parameters.subList(1, method.parameters.size).map { KTypeAdapter.translate(setOf(), it.type).erased() },
      )
    }

    fun ofStatic(method: KFunction<*>): MethodId {
      require(method.parameters.isEmpty() || method.parameters[0].kind != KParameter.Kind.INSTANCE) {
        "Method ${method.name} is not static"
      }
      return MethodId(
          false,
          method.name,
          method.parameters.map { KTypeAdapter.translate(setOf(), it.type).erased() },
      )
    }
  }

  // We create `MethodId` for most methods through reflection. But those for `FunctionN.invoke`
  // aren't available. So we maintain a factory here.
  object Invoke {
    private val cache = HashMap<Int, MethodId>()

    operator fun get(arity: Int): MethodId = cache.getOrPut(arity) { MethodId(true, "invoke", List(arity) { null }) }
  }
}

/**
 * We take the "closure-converted" view of the program. If the method is nested within other classes and methods, its [initEnvironment]
 * accumulates all from that method's lexical scope.
 *
 * In the following example:
 * ```
 * class A<X> {
 *   val a: X
 *   fun<Y> f(y: Y) {
 *     val n: Int
 *     class B<Z> {
 *       fun<T> g(x: T) { }
 *     }
 *   }
 * }
 * ```
 *
 * method `g`'s [initEnvironment] is `[X, Y, Z, T; this@A: A<X>, a: X, y: Y, this@B: B<Z>, x: T]`. Notice that `n` is missing even though
 * it's captured. This is ok-ish, because it's in an "existential" position, and the purpose of [initEnvironment] is to map the fields to
 * more precise instantiated type parameters instead of resorting to the underlying type system.
 */
internal data class MethodBody<out FX>(
    val domains: List<Type<Nothing>>,
    val initEnvironment: Env<Nothing>,
    val returnTypeAnnotation: Type<Nothing>,
    val status: Status<FX>,
    val source: UElement,
) {

  override fun toString(): String {
    val zs = initEnvironment.types.format()
    val body = "(${domains.joinToString()}) -> $returnTypeAnnotation"
    val fx =
        when (status) {
          is Status.Abstract -> "\uD83D\uDD35"
          is Status.BasicConstructor -> "\uD83D\uDEA7"
          is Status.ForChecking -> "@ ${status.upperBound}"
          is Status.ForInference -> "@ ???"
        }
    return when {
      initEnvironment.types.isEmpty() -> "$body $fx"
      else -> "∀ $zs. $body $fx"
    }
  }

  sealed interface Status<out FX> {
    data object Abstract : Status<Nothing>

    data object BasicConstructor : Status<Nothing>

    data class ForChecking<out FX>(
        val upperBound: EffectAnnotation.Explicit<FX>,
        val body: UExpression?,
    ) : Status<FX>

    data class ForInference<out FX>(
        val body: UExpression,
        val base: EffectAnnotation.Implicit<FX>,
    ) : Status<FX>
  }
}

internal fun <FX> returnType(
    env: Set<String>,
    m: PsiMethod,
    classAdapter: TypeAdapter<PsiClass> = PsiClassAdapter,
): Type<FX> =
    when (val t = m.returnType) {
      null ->
          when {
            m.isConstructor -> classAdapter.translate(env, m.containingClass!!)
            // TODO?
            else -> Type.WildCard
          }
      else -> PsiTypeAdapter.translate(env, t)
    }

internal fun PsiMethod.isStatic() = modifierList.hasModifierProperty(PsiModifier.STATIC)

internal fun PsiMethod.isFinal() =
    modifierList.hasModifierProperty(PsiModifier.FINAL) || modifierList.hasModifierProperty(PsiModifier.PRIVATE)

internal fun PsiMethod.isExtension() = parameters.firstOrNull()?.name?.startsWith("\$this") == true
