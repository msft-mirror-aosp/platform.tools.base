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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiCapturedWildcardType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDisjunctionType
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.plus
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.uast.UastErrorType
import org.jetbrains.uast.kotlin.BaseKotlinUastResolveProviderService

/** A converter of [T] into the internal [Type] representation */
internal interface TypeAdapter<T> {
  fun translate(env: Set<String>, repr: T): Type<Nothing>

  fun isFinal(repr: T): Boolean
}

internal object PsiClassAdapter : TypeAdapter<PsiClass> {
  override fun translate(env: Set<String>, repr: PsiClass): Type<Nothing> {
    val c = repr.qualifiedName
    return when {
      c in env -> Type.Sym.Param(c!!)
      else -> {
        val params = repr.typeParameters.map { Type.Sym.Param(it.name!!) }
        Type.Application(ClassId.of(repr), params)
      }
    }
  }

  // TODO: does below check for user-annotated `final`, or effective `final`?
  override fun isFinal(repr: PsiClass) = repr.hasModifierProperty(PsiModifier.FINAL)
}

internal object PsiTypeAdapter : TypeAdapter<PsiType> {
  override fun translate(env: Set<String>, repr: PsiType): Type<Nothing> {
    fun loop(t: PsiType): Type<Nothing> =
      when (t) {
        PsiTypes.booleanType() -> Type.Boolean
        PsiTypes.intType() -> Type.Int
        PsiTypes.charType() -> Type.Char
        PsiTypes.byteType() -> Type.Byte
        PsiTypes.shortType() -> Type.Short
        PsiTypes.longType() -> Type.Long
        PsiTypes.floatType() -> Type.Float
        PsiTypes.doubleType() -> Type.Double
        PsiTypes.voidType() -> Type.Unit
        is PsiClassType ->
          when (val c = t.className) {
            in env -> Type.Sym.Param(c)
            else -> Type.Application(ClassId.of(t), t.parameters.map(::loop))
          }
        is PsiTypeParameter -> Type.Sym.Param(t.canonicalText)
        is PsiWildcardType -> Type.WildCard // TODO
        is PsiEllipsisType -> Type.Ellipsis(translate(env, t.componentType))
        is PsiArrayType -> Type.Application(ClassId.Array, listOf(translate(env, t.componentType)))
        is PsiDisjunctionType ->
          Type.Union(
            t.disjunctions.map(::loop).fold(persistentSetOf<Type<Nothing>>()) { acc, t -> acc + t }
          )
        is UastErrorType -> Type.WildCard
        is PsiCapturedWildcardType -> Type.WildCard // TODO
        else -> throw NotImplementedError("Translate $t of type ${t::class.java}")
      }
    return loop(repr)
  }

  override fun isFinal(repr: PsiType): Boolean =
    when (repr) {
      is PsiPrimitiveType -> true
      is PsiEllipsisType -> isFinal(repr.componentType)
      is PsiArrayType -> isFinal(repr.deepComponentType)
      is PsiClassType -> repr.resolve()?.let(PsiClassAdapter::isFinal) == true
      else -> false
    }
}

internal object KtTypeReferenceAdapter : TypeAdapter<KtTypeReference?> {
  internal val resolveProviderService: BaseKotlinUastResolveProviderService by
    lazy(LazyThreadSafetyMode.NONE) {
      ApplicationManager.getApplication()
        .getService(BaseKotlinUastResolveProviderService::class.java)
    }

  override fun translate(env: Set<String>, repr: KtTypeReference?) =
    when (repr) {
      null -> Type.WildCard
      else ->
        resolveProviderService.resolveToType(repr, null)?.let { PsiTypeAdapter.translate(env, it) }
          ?: Type.WildCard
    }

  override fun isFinal(repr: KtTypeReference?) =
    repr != null &&
      resolveProviderService.resolveToType(repr, null)?.let(PsiTypeAdapter::isFinal) == true
}
