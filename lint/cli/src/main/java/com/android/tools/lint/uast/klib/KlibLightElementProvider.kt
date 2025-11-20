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
@file:Suppress("INVISIBLE_REFERENCE", "UastImplementation")

package com.android.tools.lint.uast.klib

import com.android.tools.lint.uast.DecompiledPsiDeclarationProvider.provide as jvmProvide
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiParameterizedCachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaTypeAliasSymbol
import org.jetbrains.kotlin.analysis.api.symbols.isLocal
import org.jetbrains.kotlin.analysis.api.symbols.isTopLevel
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.analysis.api.types.symbol
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.light.classes.symbol.classes.SymbolLightClassBase
import org.jetbrains.kotlin.light.classes.symbol.classes.SymbolLightClassForClassLike
import org.jetbrains.kotlin.light.classes.symbol.classes.SymbolLightClassForClassOrObject
import org.jetbrains.kotlin.light.classes.symbol.classes.createLightClassNoCache
import org.jetbrains.kotlin.light.classes.symbol.fields.SymbolLightField
import org.jetbrains.kotlin.light.classes.symbol.fields.SymbolLightFieldForProperty
import org.jetbrains.kotlin.light.classes.symbol.methods.SymbolLightMethodBase
import org.jetbrains.kotlin.platform.jvm.JvmPlatform
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.uast.kotlin.internal.FirKotlinUastLibraryPsiProviderService

/**
 * An experimental light element provider that tries to provide light elements (Java-like PSI) for
 * klibs. Note that it still defers to
 * [com.android.tools.lint.uast.DecompiledPsiDeclarationProvider] for non-klib (jar) elements.
 */
internal object KlibLightElementProvider : FirKotlinUastLibraryPsiProviderService {

  private fun String.getLineNumber(offset: Int): Int {
    var lineNumber = 1
    for (i in 0 until offset) {
      if (this[i] == '\n') lineNumber++
    }
    return lineNumber
  }

  private inline fun log(message: () -> String) {
    // System.err.println("SlcDeclarationProvider: ${message()}")
  }

  private inline fun logHeader(message: () -> String) {
    // System.err.println("\n\nSlcDeclarationProvider: ${message()}")
  }

  @OptIn(SymbolInternals::class)
  override fun KaSession.provide(symbol: KaSymbol, context: KtElement?): PsiElement? {
    val module = useSiteModule

    logHeader {
      "Providing symbol for: $symbol; name: ${symbol.name}; callableId: ${(symbol as? KaCallableSymbol)?.callableId}"
    }

    if (context != null) {
      log { "File path: ${context.containingFile?.virtualFile?.path}" }
      logHeader { "Element text: ${context.text}" }
      logHeader { "Parent text: ${context.parent?.text}" }
      logHeader { "Line: ${context.containingFile?.text?.getLineNumber(context.textOffset)}" }
    }

    // For JVM:
    if (symbol.containingModule.targetPlatform.componentPlatforms.all { it is JvmPlatform }) {
      log { "RETURN Deferring to DecompiledPsiDeclarationProvider (Java)" }
      return jvmProvide(symbol, context)
    }

    // We shouldn't need to provide light elements for local symbols.
    if (symbol.isLocal) {
      log { "RETURN Local symbol" }
      return symbol.psi
    }

    // The general approach is:
    // Use the existing SLC utility functions to create the containing class.
    // If we are just creating a class, then we are done.
    // Otherwise, find the appropriate member (field/method) in the created SLC by comparing symbol
    // pointers, and return that.

    val result =
      when (symbol) {
        is KaFunctionSymbol -> provideForFunctionSymbol(symbol, module)
        is KaKotlinPropertySymbol -> provideForPropertySymbol(symbol, module)
        is KaEnumEntrySymbol -> provideForEnumEntrySymbol(symbol, module)
        is KaClassLikeSymbol -> provideForClassLikeSymbol(symbol, module)
        else -> null
      }
    if (result == null) {
      log { "ERROR? returning null" }
    }
    return result
  }

  private fun KaSession.provideForFunctionSymbol(
    symbol: KaFunctionSymbol,
    module: KaModule,
  ): PsiElement? {
    if (symbol.isTopLevel) {
      // TODO(b/461753685): Consider creating a facade class per package, .nm file, or similar.
      // We create our own fake facade class per top-level function since .containingFile,
      // .containingSymbol, .containingJvmClassName, and KaScopes don't currently work with klibs.
      if (symbol !is KaNamedFunctionSymbol) {
        log { "ERROR: Can only create facade class for top-level _named_ functions" }
        return null
      }
      val psiManager = PsiManager.getInstance(module.project)
      val facade =
        createFacadeForTopLevelCallable(symbol, module, psiManager, getPsiFile(symbol, psiManager))
      if (facade == null) {
        log { "RETURN Did not create facade class, but that can be OK." }
        return null
      }
      val pointer = symbol.createPointer()
      val lightMethod =
        facade.ownMethods.firstOrNull {
          (it as? SymbolLightMethodBase)?.extractSymbolPointer?.pointsToTheSameSymbolAs(pointer) ==
            true
        }

      if (lightMethod == null) {
        log { "WARNING RETURN Could not find method in facade class, but that can be OK." }
        return null
      }
      log { "RETURN light method from facade class" }
      return lightMethod
    }

    when (val containingDeclaration = symbol.containingDeclaration) {
      is KaNamedClassSymbol -> {
        val lightClass = provide(containingDeclaration) as? SymbolLightClassBase
        if (lightClass == null) {
          log { "ERROR Expected to get a light class from the containing KaNamedClassSymbol" }
          return null
        }
        val pointer = symbol.createPointer()
        val lightMethod =
          lightClass.ownMethods.firstOrNull {
            (it as? SymbolLightMethodBase)
              ?.extractSymbolPointer
              ?.pointsToTheSameSymbolAs(pointer) == true
          }
        if (lightMethod == null) {
          log { "WARNING RETURN Could not find method in containing class" }
          return null
        }
        return lightMethod
      }
      is KaPropertySymbol -> {
        // symbol is a getter/setter function contained by property symbol
        // containingDeclaration.
        // The containing class gets a field and a method.
        val lightProperty = provide(containingDeclaration) as? SymbolLightFieldForProperty
        if (lightProperty == null) {
          log { "ERROR Expected to get a light field from the containing KaPropertySymbol" }
          return null
        }
        val lightClass = lightProperty.getContainingClass()
        val pointer = symbol.createPointer()
        val lightMethod =
          lightClass.ownMethods.firstOrNull {
            (it as? SymbolLightMethodBase)
              ?.extractSymbolPointer
              ?.pointsToTheSameSymbolAs(pointer) == true
          }
        if (lightMethod == null) {
          log { "WARNING RETURN Could not find method in containing class" }
          return null
        }
        return lightMethod
      }
      else -> {
        log { "ERROR: Unexpected containing declaration type: $containingDeclaration" }
        return null
      }
    }
  }

  private fun KaSession.provideForPropertySymbol(
    symbol: KaKotlinPropertySymbol,
    module: KaModule,
  ): PsiElement? {
    // TODO: we need to return the getter/setter in some cases.

    // TODO: fields are incorrectly added, even for properties without a backing field.
    //  We might be able to fix in SLC, but we might be blocked by:
    //  https://youtrack.jetbrains.com/issue/KT-77281

    if (symbol.isTopLevel) {
      // We create our own fake facade class per top-level property since .containingFile,
      // .containingSymbol, .containingJvmClassName, and KaScopes don't currently work with klibs.
      val psiManager = PsiManager.getInstance(module.project)
      val facade =
        createFacadeForTopLevelCallable(symbol, module, psiManager, getPsiFile(symbol, psiManager))
      if (facade == null) {
        log { "RETURN Did not create facade class, but that can be OK." }
        return null
      }
      val pointer = symbol.createPointer()
      val lightField =
        facade.ownFields.firstOrNull {
          (it as? SymbolLightField)?.extractSymbolPointer?.pointsToTheSameSymbolAs(pointer) == true
        }
      if (lightField == null) {
        log { "WARNING RETURN Could not find field in facade class" }
        return null
      }
      return lightField
    }

    var containingClass = symbol.containingDeclaration as? KaNamedClassSymbol
    if (containingClass == null) {
      log {
        "ERROR expected containing declaration to be a KaNamedClassSymbol, but is: ${symbol.containingDeclaration}"
      }
      return null
    }
    if (containingClass.classKind == KaClassKind.COMPANION_OBJECT) {
      // The containing class of a companion object gets the fields, so we want that.
      // TODO: Check if we should return the getter on the companion object.
      containingClass = containingClass.containingDeclaration as? KaNamedClassSymbol
      if (containingClass == null) {
        log {
          "ERROR expected containing declaration of this companion object " +
            "to be a KaNamedClassSymbol, but is: ${symbol.containingDeclaration?.containingDeclaration}"
        }
        return null
      }
    }
    val lightClass = provide(containingClass) as? SymbolLightClassBase
    if (lightClass == null) {
      log { "ERROR Expected to get a light class from the containing KaNamedClassSymbol" }
      return null
    }
    val pointer = symbol.createPointer()
    val lightField =
      lightClass.ownFields.firstOrNull {
        (it as? SymbolLightField)?.extractSymbolPointer?.pointsToTheSameSymbolAs(pointer) == true
      }
    if (lightField == null) {
      log { "WARNING RETURN Could not find field in containing class, but that can be OK." }
      return null
    }
    return lightField
  }

  private fun KaSession.provideForEnumEntrySymbol(
    symbol: KaEnumEntrySymbol,
    module: KaModule,
  ): PsiElement? {
    val containingClass = symbol.containingDeclaration as? KaNamedClassSymbol
    if (containingClass == null) {
      log {
        "ERROR expected containing declaration to be a KaNamedClassSymbol, but is: ${symbol.containingDeclaration}"
      }
      return null
    }
    val lightClass = provide(containingClass) as? SymbolLightClassForClassOrObject
    if (lightClass == null) {
      log { "ERROR Expected to get a light class from the containing KaNamedClassSymbol" }
      return null
    }

    // TODO: enum classes are currently broken (0 fields) because the enum entries require
    //  Kotlin PSI. For now, we provide the entry here, but we should really fix enum classes.
    val psiManager = PsiManager.getInstance(module.project)
    val lightEnumEntry = KlibLightFieldForEnumEntry(symbol, lightClass, psiManager)
    return lightEnumEntry
  }

  private fun KaSession.provideForClassLikeSymbol(
    symbol: KaClassLikeSymbol,
    module: KaModule,
  ): PsiElement? {
    when (symbol) {
      is KaNamedClassSymbol -> {
        // Unlike functions and properties, the LCs for classes do not take a reference to the
        // containing class, so we don't need to create the containing class.
        // However, getContainingClass() and getOwnInnerClasses() both create fresh instances.
        // We need to "fix up" these instances, so we must intercept these methods/initializers.
        val psiManager = PsiManager.getInstance(module.project)
        val cls =
          createLightClassNoCache(classSymbol = symbol, ktModule = module, manager = psiManager)
            as? SymbolLightClassForClassLike<*>

        if (cls == null) {
          log { "ERROR: Could not cast LC to SymbolLightClassForClassLike<*>" }
          return null
        }

        fixUpClass(cls)

        return cls
      }
      is KaTypeAliasSymbol -> {
        symbol.expandedType.symbol?.let { classSymbol ->
          return provide(classSymbol)
        }
        return null
      }
      is KaAnonymousObjectSymbol -> {
        // Not possible because it is local.
        return null
      }
    }
  }

  @Suppress("UnstableApiUsage")
  private fun KaSession.fixUpClass(cls: SymbolLightClassForClassLike<*>) {
    // If top-level, fix up containing file.
    // We don't need to do this for nested classes, as long as containingClass is fixed up.
    // Fix up containing class (call this function).
    // Fix up innerClasses (call this function).

    // TODO: We need to fix enum classes; currently, they have 0 fields.
    //  SymbolLightClassForClassOrObject$getOwnFields$$inlined$cachedValue$1.class

    run {
      val classSymbol = cls.classSymbolPointer.restoreSymbol() ?: return@run
      if (classSymbol.isTopLevel) {
        // TODO: Log?
        val containingFile = getPsiFile(classSymbol, cls.manager) ?: return@run
        updateContainingFile(cls, containingFile)
      }
    }

    // _containingClass$delegate
    val field =
      SymbolLightClassForClassLike::class.java.getDeclaredField("_containingClass\$delegate")
    field.isAccessible = true

    @Suppress("UNCHECKED_CAST") val lazyContainingClassOriginal = field.get(cls) as? Lazy<PsiClass?>

    field.set(
      cls,
      lazyPub {
        lazyContainingClassOriginal
          ?.value
          ?.let { it as? SymbolLightClassForClassLike<*> }
          ?.also { fixUpClass(it) }
      },
    )

    // Request the inner classes.
    val unused = cls.ownInnerClasses
    // No need to do anything if there are no inner classes.
    if (unused.isEmpty()) return

    // At this point, the cached list of inner classes is stored in the user map.
    // The cached value can recompute itself, if needed.
    // We replace it with a new cached value that is computed by getting the original cached value
    // (a list of inner classes), but fixes up each inner class before returning the list.

    // TODO: log on failure?
    @Suppress("UNCHECKED_CAST")
    val key =
      cls.get().keys.firstOrNull { it.toString().contains("\$getOwnInnerClasses$") }
        as? Key<PsiParameterizedCachedValue.Soft<List<SymbolLightClassBase>, PsiElement>> ?: return

    @Suppress("UNCHECKED_CAST")
    val originalCachedValue =
      key.get(cls) as? PsiParameterizedCachedValue<List<SymbolLightClassBase>, PsiElement> ?: return
    val newCachedValue =
      PsiParameterizedCachedValue.Soft<List<SymbolLightClassBase>, PsiElement>(cls.manager) { param
        ->
        val innerClasses = originalCachedValue.getValue(param) // Will recompute, if necessary.
        val freshList =
          ArrayList<SymbolLightClassBase>(innerClasses ?: emptyList<SymbolLightClassBase>())
        for (innerClass in freshList) {
          if (innerClass !is SymbolLightClassForClassLike<*>) {
            // TODO: Log?
            continue
          }
          fixUpClass(innerClass)
        }
        CachedValueProvider.Result.createSingleDependency(
          innerClasses,
          PsiModificationTracker.MODIFICATION_COUNT,
        )
      }
    key.set(cls, newCachedValue)
  }
}
