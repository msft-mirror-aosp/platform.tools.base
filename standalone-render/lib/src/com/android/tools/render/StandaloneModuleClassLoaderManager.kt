/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.tools.render

import com.android.annotations.concurrency.GuardedBy
import com.android.layoutlib.LayoutlibClassLoader
import com.android.tools.rendering.classloading.FilteringClassLoader
import com.android.tools.rendering.classloading.FirewalledResourcesClassLoader
import com.android.tools.rendering.classloading.ModuleClassLoader
import com.android.tools.rendering.classloading.ModuleClassLoaderManager
import com.intellij.openapi.module.Module
import com.intellij.util.containers.ContainerUtil
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [ModuleClassLoaderManager] for [ModuleClassLoader]s used in standalone rendering.
 *
 * In warm daemon mode, a single [StandaloneModuleClassLoaderManager] instance is retained inside a
 * cached [RenderEnvironment] across multiple render RPCs:
 * - Within a single render RPC (which may render multiple `@Preview`s in a batch), all [RenderTask]
 *   instances share the same [currentSharedClassLoader] via [getShared] so that library `R` classes
 *   initialized on the first preview remain initialized for subsequent previews in the same batch.
 * - Between render RPCs, [refreshClassPath] disposes [currentSharedClassLoader] so the next render
 *   RPC creates a fresh [DefaultModuleClassLoader] that picks up newly recompiled project `.class`
 *   files from [projectClassPath] while reusing pre-transformed external library bytecode from
 *   [LibraryClassBinaryCache].
 *
 * Note that both external library classes (`classPath - projectClassPath`) and user project classes
 * ([projectClassPath]) are defined inside the same flat [DefaultModuleClassLoader] instance (via
 * [DefaultLoader]) rather than a parent-child `ClassLoader` hierarchy. This is required because
 * framework/tooling classes loaded from the library classpath (such as `ComposableInvoker`,
 * `PreviewParameterProvider`, and `TileServiceViewAdapter`) resolve user project classes via 1-arg
 * `Class.forName(name)` or from their own defining classloader.
 */
class StandaloneModuleClassLoaderManager(
  @Volatile private var classPath: List<String>,
  @Volatile private var projectClassPath: List<String>,
) : ModuleClassLoaderManager<ModuleClassLoader>, AutoCloseable {

  companion object {
    /**
     * Canonical wrapped parent [ClassLoader] for the standard standalone rendering hierarchy:
     * `FirewalledResourcesClassLoader -> FilteringClassLoader -> LayoutlibClassLoader -> host ClassLoader`.
     */
    private val defaultWrappedParent: ClassLoader by lazy {
      FirewalledResourcesClassLoader(
        FilteringClassLoader.allowedPrefixes(
          LayoutlibClassLoader(StandaloneModuleClassLoaderManager::class.java.classLoader),
          ALLOWED_PACKAGES_FROM_PARENT,
        )
      )
    }

    /**
     * Cache of wrapped custom parent [ClassLoader]s (used when callers supply a non-default parent,
     * e.g., in unit tests). Weak keys prevent leaking caller [ClassLoader]s; see [close] and
     * [clearCache] for explicit cleanup of soft values that reference their keys.
     */
    private val wrappedParents = ContainerUtil.createConcurrentWeakKeySoftValueMap<ClassLoader, ClassLoader>()

    /**
     * Wraps [parent] in [FilteringClassLoader] and [FirewalledResourcesClassLoader] to isolate the
     * rendered module from host IDE/CLI classes and resources, while canonicalizing standard parent
     * classloaders to the singleton [defaultWrappedParent].
     *
     * Why canonicalization matters for warm renders:
     * [LibraryClassBinaryCacheEntry] caches ASM-transformed library class bytes in a map keyed by
     * the wrapped parent [ClassLoader] instance (`transformedBytesByParent`). However, the first
     * caller of [getShared] differs between cold and warm renders:
     * 1. On a **cold render** (initial [RenderEnvironment] bootstrap), `RClassResourceResolver`
     *    calls `getShared(this::class.java.classLoader)` first, passing the host classloader
     *    (`StandaloneModuleClassLoaderManager::class.java.classLoader`).
     * 2. On a **warm render** (after [refreshClassPath] clears [currentSharedClassLoader]),
     *    `RClassResourceResolver` is skipped because resource IDs are already initialized. The
     *    first caller of [getShared] is `RenderTask`, which passes `myLayoutLib.getClassLoader()` —
     *    an instance of `LayoutlibClassLoader(StandaloneModuleClassLoaderManager::class.java.classLoader)`.
     *
     * Both parent classloaders delegate to the exact same underlying classes, so canonicalizing
     * `null`, the host classloader, and a `LayoutlibClassLoader` wrapping the host classloader to
     * [defaultWrappedParent] ensures that warm renders hit the exact same per-parent ASM bytecode
     * cache populated during the cold bootstrap.
     */
    private fun wrapParent(parent: ClassLoader?): ClassLoader {
      if (
        parent == null ||
          parent === StandaloneModuleClassLoaderManager::class.java.classLoader ||
          (parent is LayoutlibClassLoader && parent.parent === StandaloneModuleClassLoaderManager::class.java.classLoader)
      ) {
        return defaultWrappedParent
      }
      return wrappedParents.computeIfAbsent(parent) { p ->
        FirewalledResourcesClassLoader(FilteringClassLoader.allowedPrefixes(p, ALLOWED_PACKAGES_FROM_PARENT))
      }
    }
  }

  private val sharedClassLoadersLock = ReentrantLock()
  @GuardedBy("sharedClassLoadersLock") private var currentSharedClassLoader: DefaultModuleClassLoader? = null

  override fun close() {
    sharedClassLoadersLock.withLock {
      currentSharedClassLoader?.dispose()
      currentSharedClassLoader = null
    }
    // Clear soft-valued wrapper entries because each value (FirewalledResourcesClassLoader ->
    // FilteringClassLoader -> parent) holds a strong reference back to its key.
    wrappedParents.clear()
  }

  /**
   * Updates the active [classPath] and [projectClassPath] and invalidates the current per-RPC
   * [DefaultModuleClassLoader] so the next render creates a fresh project classloader while reusing
   * the warm [LibraryClassBinaryCache].
   */
  fun refreshClassPath(newClassPath: List<String>, newProjectClassPath: List<String>) {
    sharedClassLoadersLock.withLock {
      classPath = newClassPath
      projectClassPath = newProjectClassPath
      currentSharedClassLoader?.dispose()
      currentSharedClassLoader = null
    }
  }

  private fun createClassLoader(parent: ClassLoader?): DefaultModuleClassLoader {
    return DefaultModuleClassLoader(wrapParent(parent), classPath, projectClassPath)
  }

  /**
   * Returns the shared [ModuleClassLoader] for the current render batch, creating it lazily if
   * needed.
   *
   * The returned [ModuleClassLoaderManager.Reference] uses a no-op release callback (`{}`) so that
   * individual [RenderTask]s closing at the end of each preview in a multi-preview batch do not
   * dispose the shared classloader (which would reset `rClassesInitialized` and force every
   * subsequent preview in the batch to re-load all classes). Instead, [currentSharedClassLoader] is
   * explicitly disposed between RPCs via [refreshClassPath] or on environment teardown via [close].
   */
  fun getShared(parent: ClassLoader?): ModuleClassLoaderManager.Reference<ModuleClassLoader> {
    val classLoader = sharedClassLoadersLock.withLock {
      currentSharedClassLoader ?: createClassLoader(parent).also { currentSharedClassLoader = it }
    }
    return ModuleClassLoaderManager.Reference(classLoader, {})
  }

  /**
   * Returns a dedicated, unshared [ModuleClassLoader] that is disposed as soon as the caller
   * releases the returned [ModuleClassLoaderManager.Reference].
   */
  fun getPrivate(parent: ClassLoader?): ModuleClassLoaderManager.Reference<ModuleClassLoader> {
    val classLoader = createClassLoader(parent)
    return ModuleClassLoaderManager.Reference(classLoader) { classLoader.dispose() }
  }

  override fun clearCache(module: Module) {
    sharedClassLoadersLock.withLock {
      currentSharedClassLoader?.dispose()
      currentSharedClassLoader = null
    }
    wrappedParents.clear()
  }
}
