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
@file:Suppress("INVISIBLE_REFERENCE", "INFERRED_INVISIBLE_WHEN_TYPE_WARNING", "UastImplementation")

package com.android.tools.lint.uast.klib

import com.android.tools.lint.uast.DecompiledPsiDeclarationProvider.provide as jvmProvide
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiParameterizedCachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.asSafely
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.symbols.KaAnonymousObjectSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
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
import org.jetbrains.kotlin.light.classes.symbol.methods.SymbolLightMethodBase
import org.jetbrains.kotlin.platform.jvm.JvmPlatform
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.uast.kotlin.internal.FirKotlinUastLibraryPsiProviderService
import org.jetbrains.uast.kotlin.readWriteAccess

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
        is KaPropertySymbol -> provideForPropertySymbol(symbol, module, context)
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
    // symbol can be a property accessor contained within a property symbol.
    val functionOrPropertySymbol = symbol.containingDeclaration as? KaPropertySymbol ?: symbol
    val psiManager = PsiManager.getInstance(module.project)
    // Get the containing light class (a facade class, or otherwise).
    val lightClass =
      when {
        functionOrPropertySymbol.isTopLevel -> {
          if (
            functionOrPropertySymbol !is KaNamedFunctionSymbol &&
              functionOrPropertySymbol !is KaPropertySymbol
          ) {
            log {
              "RETURN ERROR: Can only create a facade class for top-level named function or property symbol"
            }
            return null
          }
          // TODO(b/461753685): Consider creating a facade class per package, .nm file, or similar.
          // We create our own fake facade class per top-level function since .containingFile,
          // .containingSymbol, .containingJvmClassName, and KaScopes don't currently work with
          // klibs.
          val facade =
            createFacadeForTopLevelCallable(
              functionOrPropertySymbol,
              module,
              psiManager,
              getPsiFile(functionOrPropertySymbol, psiManager),
            )
          if (facade == null) {
            log { "RETURN Did not create facade class, but that can be OK." }
            return null
          }
          facade
        }
        else -> {
          val containingClass =
            functionOrPropertySymbol.containingDeclaration as? KaNamedClassSymbol
          if (containingClass == null) {
            log {
              "ERROR expected containing declaration to be a KaNamedClassSymbol, but is: ${functionOrPropertySymbol.containingDeclaration}"
            }
            return null
          }
          val lightClass = provide(containingClass) as? SymbolLightClassBase
          if (lightClass == null) {
            log { "ERROR Expected to get a light class from the containing KaNamedClassSymbol" }
            return null
          }
          lightClass
        }
      }
    val pointer = symbol.createPointer()
    val lightMethod =
      lightClass.ownMethods.firstOrNull {
        (it as? SymbolLightMethodBase)?.extractSymbolPointer?.pointsToTheSameSymbolAs(pointer) ==
          true
      }
    if (lightMethod == null) {
      log { "WARNING RETURN Could not find method in light class, but that can be OK." }
      return null
    }
    return lightMethod
  }

  @Suppress("UnstableApiUsage")
  private fun KaSession.provideForPropertySymbol(
    symbol: KaPropertySymbol,
    module: KaModule,
    context: KtElement?,
  ): PsiElement? {
    // TODO: fields are incorrectly added, even for properties without a backing field.
    //  We might be able to fix in SLC, but we might be blocked by:
    //  https://youtrack.jetbrains.com/issue/KT-77281

    // Note: UAST behaves differently depending on whether the resolved property is in Kotlin source
    // code or in a jar. For source code, a property access resolves to the getter/setter PsiMethod.
    // For a jar, the property access (often) resolves to a PsiField. In hindsight, resolving to a
    // PsiField is strange: (a) the PsiField corresponds to a backing field, which may or may not be
    // present, depending on how the property is defined. Synthetic properties derived from Java
    // code will also not have backing fields. (b) Even if there is a backing field, it is not being
    // referenced by the property access expression. Below, we follow the source code approach: we
    // prefer to resolve to getter/setter methods.

    // We don't need to consider synthetic properties derived from Java code.
    if (symbol !is KaKotlinPropertySymbol) {
      log { "ERROR RETURN Expected Kotlin property, but found: $symbol" }
      return null
    }

    // For a compound access like `a.propInt += 1`, the left operand resolves (using AA) to a read
    // access call. And yet, UAST (when the property is in source code) resolves it to the setter
    // method (I think this is only because we find the setter method first; if the getter was
    // declared first, we would return the getter). We try to match this convention.
    val accessorSymbol =
      when {
        context?.asSafely<KtExpression>()?.readWriteAccess()?.isWrite == true -> symbol.setter
        else -> symbol.getter
      }

    val psiManager = PsiManager.getInstance(module.project)

    // Get the containing light class (a facade class, or otherwise).
    val lightClass: SymbolLightClassBase =
      when {
        symbol.isTopLevel -> {
          // We create our own fake facade class per top-level property since .containingFile,
          // .containingSymbol, .containingJvmClassName, and KaScopes don't currently work with
          // klibs.
          val facade =
            createFacadeForTopLevelCallable(
              symbol,
              module,
              psiManager,
              getPsiFile(symbol, psiManager),
            )
          if (facade == null) {
            log { "RETURN Did not create facade class, but that can be OK." }
            return null
          }
          facade
        }
        else -> {
          val containingClass = symbol.containingDeclaration as? KaNamedClassSymbol
          if (containingClass == null) {
            log {
              "ERROR expected containing declaration to be a KaNamedClassSymbol, but is: ${symbol.containingDeclaration}"
            }
            return null
          }
          val lightClass = provide(containingClass) as? SymbolLightClassBase
          if (lightClass == null) {
            log { "ERROR Expected to get a light class from the containing KaNamedClassSymbol" }
            return null
          }
          lightClass
        }
      }

    val propertySymbolPointer = symbol.createPointer()

    if (accessorSymbol != null) {
      val accessorSymbolPointer = accessorSymbol.createPointer()
      val lightMethod =
        lightClass.ownMethods.firstOrNull {
          (it as? SymbolLightMethodBase)
            ?.extractSymbolPointer
            ?.pointsToTheSameSymbolAs(accessorSymbolPointer) == true
        }
      if (lightMethod != null) {
        return lightMethod
      }
      log { "WARNING Could not find accessor method for $accessorSymbol, but that can be OK." }
    }

    val lightField =
      lightClass.ownFields.firstOrNull {
        (it as? SymbolLightField)
          ?.extractSymbolPointer
          ?.pointsToTheSameSymbolAs(propertySymbolPointer) == true
      }
    if (lightField != null) {
      return lightField
    }

    log { "WARNING RETURN Could not find field in containing class, but that can be OK." }
    return null
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
