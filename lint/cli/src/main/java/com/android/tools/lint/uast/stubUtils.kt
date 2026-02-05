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
package com.android.tools.lint.uast

import com.android.tools.lint.uast.PsiBuilderUtils.buildLightField
import com.android.tools.lint.uast.PsiBuilderUtils.buildLightMethod
import com.android.tools.lint.uast.PsiBuilderUtils.orAnonymous
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.util.indexing.FileContentImpl
import org.jetbrains.kotlin.analysis.decompiler.konan.KlibMetaFileType
import org.jetbrains.kotlin.analysis.decompiler.konan.KotlinKlibMetadataDecompiler
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.stubs.impl.KotlinClassStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinFileStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinFunctionStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinObjectStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinPropertyStubImpl
import org.jetbrains.kotlin.psi.stubs.impl.KotlinStubBaseImpl

internal fun klibMetaFiles(root: VirtualFile): Collection<VirtualFile> {
  return buildList {
    VfsUtilCore.visitChildrenRecursively(
      root,
      object : VirtualFileVisitor<Void>() {
        override fun visitFile(file: VirtualFile): Boolean {
          if (file.fileType == KlibMetaFileType) {
            add(file)
          }
          return true
        }
      },
    )
  }
}

internal fun buildStubByVirtualFile(file: VirtualFile): KotlinFileStubImpl? {
  val fileContent = FileContentImpl.createByFile(file)
  return KotlinKlibMetadataDecompiler().stubBuilder.buildFileStub(fileContent) as? KotlinFileStubImpl
}

internal fun buildPsiSymbolByKotlinStub(psiManager: PsiManager, ktFile: KtFile, ktStub: KotlinStubBaseImpl<*>): PsiNameIdentifierOwner? {
  return when (ktStub) {
    is KotlinClassStubImpl -> {
      val ktClass = ktStub.psi
      LintFakeLightClassForKlib(ktClass, psiManager, ktClass.name.orAnonymous(ktClass), ktFile)
    }
    is KotlinObjectStubImpl -> {
      val ktObject = ktStub.psi
      LintFakeLightClassForKlib(ktObject, psiManager, ktObject.name.orAnonymous(ktObject), ktFile)
    }
    // These declarations are top-level and have no containing class in Kotlin/Native
    is KotlinPropertyStubImpl -> {
      val ktProperty = ktStub.psi
      ktProperty.buildLightField(containingClass = null)
    }
    is KotlinFunctionStubImpl -> {
      val ktFunction = ktStub.psi
      ktFunction.buildLightMethod(containingClass = null)
    }
    else -> null
  }
}
