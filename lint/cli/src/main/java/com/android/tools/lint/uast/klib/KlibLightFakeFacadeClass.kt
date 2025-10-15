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

import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypeParameterList
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.asJava.classes.KotlinSuperTypeListBuilder
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.asJava.elements.KtLightIdentifier
import org.jetbrains.kotlin.light.classes.symbol.annotations.EmptyAnnotationsBox
import org.jetbrains.kotlin.light.classes.symbol.classes.SymbolLightClassBase
import org.jetbrains.kotlin.light.classes.symbol.compareSymbolPointers
import org.jetbrains.kotlin.light.classes.symbol.modifierLists.InitializedModifiersBox
import org.jetbrains.kotlin.light.classes.symbol.modifierLists.SymbolLightClassModifierList
import org.jetbrains.kotlin.light.classes.symbol.withSymbol
import org.jetbrains.kotlin.load.java.structure.LightClassOriginKind
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassOrObject

internal class KlibLightFakeFacadeClass(
  callableSymbol: KaCallableSymbol,
  val kaModule: KaModule,
  val psiManager: PsiManager,
  val fakeContainingFile: PsiFile?,
) : SymbolLightClassBase(kaModule, psiManager) {

  val callableSymbolPointer = callableSymbol.createPointer()

  val fields: MutableList<PsiField> = mutableListOf()
  val methods: MutableList<PsiMethod> = mutableListOf()

  override fun getOwnFields(): List<PsiField> = fields

  override fun getOwnMethods(): List<PsiMethod> = methods

  override fun getOwnInnerClasses(): List<PsiClass> = emptyList()

  override fun equals(other: Any?): Boolean {
    return this === other ||
      other is KlibLightFakeFacadeClass &&
        kaModule == other.kaModule &&
        psiManager == other.psiManager &&
        compareSymbolPointers(callableSymbolPointer, other.callableSymbolPointer)
  }

  // TODO: This is not good, but perhaps it doesn't matter.
  override fun hashCode(): Int = 0

  // TODO: PsiElement.copy KDoc indicates that we should be creating a copy of
  //  of the entire file, but other SLCs don't seem to do that?
  //  Could we just return null? Should we just return this, assuming we would
  //  normally cache the copy anyway?
  override fun copy(): KlibLightFakeFacadeClass =
    callableSymbolPointer.withSymbol(kaModule) { callableSymbol ->
      KlibLightFakeFacadeClass(callableSymbol, kaModule, psiManager, containingFile)
    }

  override fun toString(): String =
    "${KlibLightFakeFacadeClass::class.java.simpleName}:${callableSymbolPointer}"

  private val _classId: ClassId?
    get() =
      callableSymbolPointer.withSymbol(kaModule) { callableSymbol ->
        val callableId = callableSymbol.callableId ?: return@withSymbol null

        // TODO: I am guessing we might need to process/escape the callable name
        //  before simply using it as a class name. But I am unsure exactly what
        //  is allowed, given that this doesn't really exist.
        ClassId(callableId.packageName, Name.identifier("Facade$${callableId.callableName}"))
      }

  override fun getQualifiedName(): String? = _classId?.asFqNameString()

  override fun isInterface() = false

  override fun isAnnotationType() = false

  override fun isEnum() = false

  // SymbolLightClassForFacade just returns null here. Not sure why.
  // TODO: Cache this, and more?
  override fun getExtendsList(): PsiReferenceList {
    val list =
      KotlinSuperTypeListBuilder(
        this,
        kotlinOrigin = null,
        manager = psiManager,
        language = language,
        role = PsiReferenceList.Role.EXTENDS_LIST,
      )
    superClass?.let { list.addReference(it) }
    return list
  }

  override fun getImplementsList(): PsiReferenceList =
    KotlinSuperTypeListBuilder(
      this,
      kotlinOrigin = null,
      manager = psiManager,
      language = language,
      role = PsiReferenceList.Role.IMPLEMENTS_LIST,
    )

  override fun getSuperClass(): PsiClass? {
    return JavaPsiFacade.getInstance(project)
      .findClass(CommonClassNames.JAVA_LANG_OBJECT, resolveScope)
  }

  override fun getInterfaces(): Array<out PsiClass> = PsiClass.EMPTY_ARRAY

  override fun getSupers(): Array<out PsiClass> =
    superClass?.let { arrayOf(it) } ?: PsiClass.EMPTY_ARRAY

  override fun getSuperTypes(): Array<out PsiClassType> =
    arrayOf(PsiType.getJavaLangObject(manager, resolveScope))

  override fun getNameIdentifier(): PsiIdentifier? =
    _classId?.shortClassName?.identifier?.let { KtLightIdentifier(this, null, it) }

  override fun getScope(): PsiElement? = containingFile

  override fun isInheritorDeep(baseClass: PsiClass, classToByPass: PsiClass?): Boolean = false

  override fun getContainingClass(): PsiClass? = null

  override fun getContainingFile() = fakeContainingFile

  @OptIn(KaImplementationDetail::class)
  private val _modifierList: PsiModifierList by lazyPub {
    SymbolLightClassModifierList(
      containingDeclaration = this,
      modifiersBox = InitializedModifiersBox(PsiModifier.PUBLIC, PsiModifier.FINAL),
      annotationsBox = EmptyAnnotationsBox,
    )
  }

  override fun getModifierList(): PsiModifierList = _modifierList

  override fun hasModifierProperty(name: @NonNls String) =
    name == PsiModifier.PUBLIC || name == PsiModifier.FINAL

  override fun isDeprecated() = false

  override fun getTypeParameterList(): PsiTypeParameterList? = null

  override fun getTypeParameters(): Array<out PsiTypeParameter> = PsiTypeParameter.EMPTY_ARRAY

  override val kotlinOrigin: KtClassOrObject?
    get() = null

  override val originKind: LightClassOriginKind
    get() = LightClassOriginKind.BINARY
}
