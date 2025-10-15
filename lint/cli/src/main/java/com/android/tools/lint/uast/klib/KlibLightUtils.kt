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

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.isFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.AbstractFilesScope
import java.nio.file.Paths
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.fir.utils.firSymbol
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaAnnotatedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.pointers.KaSymbolPointer
import org.jetbrains.kotlin.fir.declarations.utils.sourceElement
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.library.metadata.KlibDeserializedContainerSource
import org.jetbrains.kotlin.light.classes.symbol.annotations.hasInlineOnlyAnnotation
import org.jetbrains.kotlin.light.classes.symbol.classes.SymbolLightClassBase
import org.jetbrains.kotlin.light.classes.symbol.classes.createAndAddField
import org.jetbrains.kotlin.light.classes.symbol.classes.createMethods
import org.jetbrains.kotlin.light.classes.symbol.fields.SymbolLightField
import org.jetbrains.kotlin.light.classes.symbol.fields.SymbolLightFieldForEnumEntry
import org.jetbrains.kotlin.light.classes.symbol.fields.SymbolLightFieldForObject
import org.jetbrains.kotlin.light.classes.symbol.fields.SymbolLightFieldForProperty
import org.jetbrains.kotlin.light.classes.symbol.methods.SymbolLightAccessorMethod
import org.jetbrains.kotlin.light.classes.symbol.methods.SymbolLightMethod
import org.jetbrains.kotlin.light.classes.symbol.methods.SymbolLightMethodBase
import org.jetbrains.kotlin.light.classes.symbol.methods.SymbolLightMethodForScript
import org.jetbrains.kotlin.light.classes.symbol.methods.SymbolLightNoArgConstructor
import org.jetbrains.kotlin.light.classes.symbol.methods.SymbolLightRepeatableAnnotationContainerMethod

internal fun KaSession.createFacadeForTopLevelCallable(
  callableSymbol: KaCallableSymbol,
  kaModule: KaModule,
  psiManager: PsiManager,
  containingFile: PsiFile?,
): KlibLightFakeFacadeClass? {
  require(callableSymbol is KaNamedFunctionSymbol || callableSymbol is KaKotlinPropertySymbol) {
    "Can only create a facade class for top-level named function or property symbol"
  }

  // Based on SymbolLightClassForFacade.getOwnMethods
  if (callableSymbol.isExpect) return null
  if ((callableSymbol as? KaAnnotatedSymbol)?.hasInlineOnlyAnnotation() == true) return null
  if (callableSymbol.visibility == KaSymbolVisibility.PRIVATE) return null
  val facade = KlibLightFakeFacadeClass(callableSymbol, kaModule, psiManager, containingFile)
  val fields = facade.fields
  val methods = facade.methods
  if (callableSymbol is KaKotlinPropertySymbol) {
    // Based on SymbolLightClassForFacade.loadFieldsFromFile.
    val nameGenerator = SymbolLightField.FieldNameGenerator()
    createAndAddField(facade, callableSymbol, nameGenerator, isStatic = true, fields)
  }
  // Based on SymbolLightClassForFacade.getOwnMethods
  createMethods(facade, sequenceOf(callableSymbol), methods, isTopLevel = true)
  return facade
}

internal val SymbolLightMethodBase.extractSymbolPointer: KaSymbolPointer<KaFunctionSymbol>?
  get() =
    when (this) {
      is SymbolLightMethod<*> -> {
        // this.functionSymbolPointer
        val field = SymbolLightMethod::class.java.getDeclaredField("functionSymbolPointer")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(this) as KaSymbolPointer<KaFunctionSymbol>
      }
      is SymbolLightAccessorMethod -> {
        // TODO: What about containingPropertySymbolPointer?
        // this.propertyAccessorSymbolPointer
        val field =
          SymbolLightAccessorMethod::class.java.getDeclaredField("propertyAccessorSymbolPointer")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(this) as KaSymbolPointer<KaFunctionSymbol>
      }
      is SymbolLightNoArgConstructor -> {
        // Can be null.
        // this.functionSymbolPointer
        val field =
          SymbolLightNoArgConstructor::class.java.getDeclaredField("functionSymbolPointer")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(this) as KaSymbolPointer<KaFunctionSymbol>
      }
      is SymbolLightRepeatableAnnotationContainerMethod -> null
      is SymbolLightMethodForScript -> null
      else -> null
    }

internal val SymbolLightField.extractSymbolPointer: KaSymbolPointer<KaDeclarationSymbol>?
  get() =
    when (this) {
      is SymbolLightFieldForProperty -> {
        // this.propertySymbolPointer
        val field =
          SymbolLightFieldForProperty::class.java.getDeclaredField("propertySymbolPointer")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(this) as KaSymbolPointer<KaDeclarationSymbol>
      }
      is SymbolLightFieldForObject -> {
        // this.objectSymbolPointer
        val field = SymbolLightFieldForObject::class.java.getDeclaredField("objectSymbolPointer")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        field.get(this) as KaSymbolPointer<KaDeclarationSymbol>
      }
      is SymbolLightFieldForEnumEntry -> null
      else -> null
    }

@Suppress("UnstableApiUsage")
@OptIn(SymbolInternals::class, KaPlatformInterface::class)
internal fun KaSession.getPsiFile(symbol: KaSymbol, psiManager: PsiManager): PsiFile? {
  // If we end up using PSI stubs, this hack shouldn't be needed.
  val containerSource =
    (symbol as? KaClassLikeSymbol)?.firSymbol?.sourceElement
      ?: (symbol as? KaCallableSymbol)?.firSymbol?.fir?.containerSource

  if (containerSource == null) {
    // This hack sort of works for the built-ins module.
    // We just need some file.
    (symbol.containingModule.baseContentScope as? AbstractFilesScope?)?.let { scope ->
      scope.filesIfCollection
        ?.firstOrNull { it.isFile }
        ?.let { virtualFile ->
          psiManager.findFile(virtualFile)?.let {
            return it
          }
        }
    }
    throw IllegalStateException("Could not get container source from $this")
  }

  containerSource as KlibDeserializedContainerSource

  val klibFile = containerSource.klib.libraryFile

  val virtualFile =
    when {
      klibFile.isDirectory ->
        VirtualFileManager.getInstance().findFileByNioPath(Paths.get(klibFile.toString()))
      else -> VirtualFileManager.getInstance().getFileSystem("jar").findFileByPath("$klibFile!/")
    } ?: throw IllegalStateException("Could not get virtual file for klib: $klibFile")

  // TODO: We may have to do better than this by returning a PsiJavaFile/PsiClassOwner that
  //  implements getPackageName (as this appears to have a few uses).

  // We are just picking some file within the klib so that the file is deemed as "in scope" for
  // analysis. The klib root is not sufficient, so we use the default manifest file, for now.
  // TODO: We can avoid hardcoding this.
  return psiManager.findFile(virtualFile.findChild("default")!!.findChild("manifest")!!)
}

internal fun KaSession.updateContainingFile(cls: SymbolLightClassBase, file: PsiFile) {
  // _containingFile$delegate
  val field = SymbolLightClassBase::class.java.getDeclaredField("_containingFile\$delegate")
  field.isAccessible = true
  field.set(cls, lazyOf(file))
}
