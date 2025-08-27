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
package com.android.tools.lint.uast

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.impl.compiled.ClsTypeElementImpl
import com.intellij.psi.impl.light.LightFieldBuilder
import com.intellij.psi.impl.light.LightMethodBuilder
import com.intellij.psi.impl.light.LightTypeParameterBuilder
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.parameterIndex

object PsiBuilderUtils {

  internal fun KtProperty.buildLightField(containingClass: PsiClass?): LightFieldBuilder =
    LightFieldBuilder(
        manager,
        name.orAnonymous(this),
        PsiTypes.voidType(), // TODO: property return type
      )
      .apply { this.containingClass = containingClass }

  internal fun KtFunction.buildLightMethod(containingClass: PsiClass?): LightMethodBuilder =
    LightMethodBuilder(manager, language, name.orAnonymous(this)).apply {
      this.containingClass = containingClass
      isConstructor = this@buildLightMethod is KtConstructor<*>
      if (!isConstructor) {
        setMethodReturnType {
          val ktTypeReference = typeReference ?: return@setMethodReturnType null
          buildCompiledTypeFromReference(ktTypeReference, this)
        }
      }
      for (param in valueParameters) {
        val name = param.name ?: continue
        val ktTypeReference = param.typeReference ?: continue
        addParameter(name, buildCompiledTypeFromReference(ktTypeReference, this))
      }
      for (param in this@buildLightMethod.typeParameters) {
        val name = param.name ?: continue
        addTypeParameter(LightTypeParameterBuilder(name, this, param.parameterIndex()))
      }
    }

  private fun buildCompiledTypeFromReference(
    ktTypeReference: KtTypeReference,
    parent: PsiElement,
  ): PsiType {
    // TODO: This likely won't work for non-class types, and that would be a much harder issue to
    // fix. Refer to
    // https://github.com/JetBrains/kotlin/blob/master/compiler/light-classes/src/org/jetbrains/kotlin/asJava/classes/ultraLightUtils.kt#L202
    // for a more sophisticated example.
    val typeText = ktTypeReference.getTypeText()
    return ClsTypeElementImpl(parent, typeText, '\u0000').type
  }

  internal fun String?.orAnonymous(ktDeclaration: KtNamedDeclaration): String {
    if (this != null) return this
    return when (ktDeclaration) {
      is KtEnumEntry -> "<anonymous enum entry>"
      is KtClass -> "<anonymous class>"
      is KtObjectDeclaration -> "<anonymous object>"
      is KtNamedFunction -> "<anonymous function>"
      is KtProperty -> "<anonymous property>"
      else -> "<unknown ${ktDeclaration::class}>"
    }
  }
}
