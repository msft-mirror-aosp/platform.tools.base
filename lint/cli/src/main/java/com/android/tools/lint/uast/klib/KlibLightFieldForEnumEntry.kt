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
@file:Suppress("INVISIBLE_REFERENCE")

package com.android.tools.lint.uast.klib

import com.intellij.psi.JavaResolveResult
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiEnumConstantInitializer
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiType
import kotlin.getValue
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.asJava.classes.cannotModify
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.light.classes.symbol.annotations.GranularAnnotationsBox
import org.jetbrains.kotlin.light.classes.symbol.annotations.SymbolAnnotationsProvider
import org.jetbrains.kotlin.light.classes.symbol.classes.SymbolLightClassForClassOrObject
import org.jetbrains.kotlin.light.classes.symbol.compareSymbolPointers
import org.jetbrains.kotlin.light.classes.symbol.fields.SymbolLightField
import org.jetbrains.kotlin.light.classes.symbol.modifierLists.InitializedModifiersBox
import org.jetbrains.kotlin.light.classes.symbol.modifierLists.SymbolLightMemberModifierList
import org.jetbrains.kotlin.light.classes.symbol.nonExistentType
import org.jetbrains.kotlin.light.classes.symbol.withSymbol

@OptIn(KaExperimentalApi::class)
internal class KlibLightFieldForEnumEntry(
  enumEntrySymbol: KaEnumEntrySymbol,
  val containingClass: SymbolLightClassForClassOrObject,
  val psiManager: PsiManager,
) : SymbolLightField(containingClass = containingClass, lightMemberOrigin = null), PsiEnumConstant {

  val enumEntrySymbolPointer = enumEntrySymbol.createPointer()

  @OptIn(KaImplementationDetail::class)
  private val _modifierList by lazyPub {
    SymbolLightMemberModifierList(
      containingDeclaration = this,
      modifiersBox =
        InitializedModifiersBox(PsiModifier.STATIC, PsiModifier.FINAL, PsiModifier.PUBLIC),
      annotationsBox =
        GranularAnnotationsBox(
          annotationsProvider =
            SymbolAnnotationsProvider(
              ktModule = ktModule,
              annotatedSymbolPointer = enumEntrySymbolPointer,
            )
        ),
    )
  }

  private val _type: PsiType by lazyPub {
    enumEntrySymbolPointer.withSymbol(ktModule) { enumEntrySymbol ->
      enumEntrySymbol.returnType.asPsiType(
        this@KlibLightFieldForEnumEntry,
        allowErrorTypes = true,
        allowNonJvmPlatforms = true,
      ) ?: nonExistentType()
    }
  }

  override fun equals(other: Any?): Boolean {
    return this === other ||
      other is KlibLightFieldForEnumEntry &&
        containingClass == other.containingClass &&
        psiManager == other.psiManager &&
        compareSymbolPointers(enumEntrySymbolPointer, other.enumEntrySymbolPointer)
  }

  // TODO: This is not good, but perhaps it doesn't matter.
  override fun hashCode(): Int = 0

  override fun isDeprecated(): Boolean =
    enumEntrySymbolPointer.withSymbol(ktModule) { enumEntrySymbol ->
      @Suppress("UnstableApiUsage")
      enumEntrySymbol.deprecationStatus != null
    }

  override fun getName(): String =
    enumEntrySymbolPointer.withSymbol(ktModule) { enumEntrySymbol ->
      enumEntrySymbol.name.asString()
    }

  override fun getModifierList(): PsiModifierList = _modifierList

  override fun getType(): PsiType = _type

  override fun getInitializer(): PsiExpression? = null

  override fun getArgumentList(): PsiExpressionList? = null

  // TODO: This is definitely wrong.
  override fun getInitializingClass(): PsiEnumConstantInitializer? = null

  override fun getOrCreateInitializingClass(): PsiEnumConstantInitializer =
    initializingClass ?: cannotModify()

  override fun resolveConstructor(): PsiMethod? = null

  override fun resolveMethod(): PsiMethod? = null

  override fun resolveMethodGenerics(): JavaResolveResult = JavaResolveResult.EMPTY
}
