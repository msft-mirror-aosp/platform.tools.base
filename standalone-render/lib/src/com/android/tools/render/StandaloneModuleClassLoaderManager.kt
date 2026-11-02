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
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [ModuleClassLoaderManager] for [ModuleClassLoader]s used in standalone rendering.
 *
 * In warm daemon mode, a single [StandaloneModuleClassLoaderManager] instance is retained inside a cached [RenderEnvironment] across
 * multiple render RPCs:
 * - Within a single render RPC (which may render multiple `@Preview`s in a batch), all [RenderTask] instances share the same
 *   [currentSharedClassLoader] via [getShared] so that library `R` classes initialized on the first preview remain initialized for
 *   subsequent previews in the same batch.
 * - Between render RPCs, [refreshClassPath] disposes [currentSharedClassLoader] so the next render RPC creates a fresh
 *   [DefaultModuleClassLoader] that picks up newly recompiled project `.class` files from [projectClassPath] while reusing both the
 *   persistent [WarmDepsClassLoader] (which retains loaded, verified, initialized, and JIT-compiled external library and `R` classes) and
 *   [LibraryClassBinaryCache].
 *
 * Preview tooling adapter classes (such as `ComposableInvoker`, `ComposeViewAdapter`, and `TileServiceViewAdapter`, matched by
 * [isPreviewToolingAdapterClass]) are excluded from [WarmDepsClassLoader] and defined inside the per-RPC child [DefaultModuleClassLoader]
 * alongside user project classes ([projectClassPath]), because those adapters resolve user `@Preview` and `PreviewParameterProvider`
 * classes via 1-arg `Class.forName(name)` or from their own defining classloader.
 */
class StandaloneModuleClassLoaderManager(
  @Volatile private var classPath: List<String>,
  @Volatile private var projectClassPath: List<String>,
) : ModuleClassLoaderManager<ModuleClassLoader>, AutoCloseable {

  companion object {
    /** Maximum number of distinct wrapped parent [WarmDepsClassLoader] instances retained per manager. */
    private const val MAX_WARM_DEPS_LOADERS = 4
    /**
     * Canonical wrapped parent [ClassLoader] for the standard standalone rendering hierarchy: `FirewalledResourcesClassLoader ->
     * FilteringClassLoader -> LayoutlibClassLoader -> host ClassLoader`.
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
     * Cache of wrapped custom parent [ClassLoader]s (used when callers supply a non-default parent, e.g., in unit tests). Weak keys prevent
     * leaking caller [ClassLoader]s; see [close] and [clearCache] for explicit cleanup of soft values that reference their keys.
     */
    private val wrappedParents = ContainerUtil.createConcurrentWeakKeySoftValueMap<ClassLoader, ClassLoader>()

    /**
     * Wraps [parent] in [FilteringClassLoader] and [FirewalledResourcesClassLoader] to isolate the rendered module from host IDE/CLI
     * classes and resources, while canonicalizing standard parent classloaders to the singleton [defaultWrappedParent].
     *
     * Why canonicalization matters for warm renders: [LibraryClassBinaryCacheEntry] caches ASM-transformed library class bytes in a map
     * keyed by the wrapped parent [ClassLoader] instance (`transformedBytesByParent`). However, the first caller of [getShared] differs
     * between cold and warm renders:
     * 1. On a **cold render** (initial [RenderEnvironment] bootstrap), `RClassResourceResolver` calls
     *    `getShared(this::class.java.classLoader)` first, passing the host classloader
     *    (`StandaloneModuleClassLoaderManager::class.java.classLoader`).
     * 2. On a **warm render** (after [refreshClassPath] clears [currentSharedClassLoader]), `RClassResourceResolver` is skipped because
     *    resource IDs are already initialized. The first caller of [getShared] is `RenderTask`, which passes `myLayoutLib.getClassLoader()`
     *    — an instance of `LayoutlibClassLoader(StandaloneModuleClassLoaderManager::class.java.classLoader)`.
     *
     * Both parent classloaders delegate to the exact same underlying classes, so canonicalizing `null`, the host classloader, and a
     * `LayoutlibClassLoader` wrapping the host classloader to [defaultWrappedParent] ensures that warm renders hit the exact same
     * per-parent ASM bytecode cache populated during the cold bootstrap.
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
  @GuardedBy("sharedClassLoadersLock") private var refreshGeneration: Long = 1L
  @GuardedBy("sharedClassLoadersLock") private var cachedProjectScan: ProjectClassPathScan? = null
  @GuardedBy("sharedClassLoadersLock") private var cachedProjectScanGeneration: Long = 0L
  @GuardedBy("sharedClassLoadersLock")
  private val warmDepsClassLoaders = LinkedHashMap<ClassLoader, WarmDepsClassLoader>(4, 0.75f, true)
  @GuardedBy("sharedClassLoadersLock") private val pendingRetiredClassLoaders = ArrayList<WeakReference<ClassLoader>>()

  @GuardedBy("sharedClassLoadersLock")
  private fun recordRetiredClassLoaderLocked(loader: ClassLoader?) {
    if (loader == null) return
    pendingRetiredClassLoaders.removeIf { it.get() == null }
    pendingRetiredClassLoaders.add(WeakReference(loader))
  }

  /**
   * Drains weak references to [DefaultModuleClassLoader] and [WarmDepsClassLoader] instances that were disposed or evicted since the last
   * drain so [Renderer] can clear any render-thread `ThreadLocalEventLoop` state associated with them.
   */
  internal fun drainRetiredClassLoaders(): List<WeakReference<ClassLoader>> {
    return sharedClassLoadersLock.withLock {
      pendingRetiredClassLoaders.removeIf { it.get() == null }
      if (pendingRetiredClassLoaders.isEmpty()) {
        emptyList()
      } else {
        ArrayList(pendingRetiredClassLoaders).also { pendingRetiredClassLoaders.clear() }
      }
    }
  }

  /** Disposes the active shared [DefaultModuleClassLoader] and retained [WarmDepsClassLoader]s (if any), and clears wrapped parent caches. */
  override fun close() {
    closeAndReturnRetired()
  }

  /**
   * Disposes the active shared [DefaultModuleClassLoader] and retained [WarmDepsClassLoader]s (if any), clears wrapped parent caches, and
   * returns the retired [DefaultModuleClassLoader] so the caller can release any thread-local state associated with it.
   */
  internal fun closeAndReturnRetired(): DefaultModuleClassLoader? {
    val retired = sharedClassLoadersLock.withLock {
      refreshGeneration++
      cachedProjectScan = null
      val prev = currentSharedClassLoader
      prev?.dispose()
      recordRetiredClassLoaderLocked(prev)
      currentSharedClassLoader = null
      warmDepsClassLoaders.values.forEach { warm ->
        warm.close()
        recordRetiredClassLoaderLocked(warm)
      }
      warmDepsClassLoaders.clear()
      prev
    }
    // Clear soft-valued wrapper entries because each value (FirewalledResourcesClassLoader ->
    // FilteringClassLoader -> parent) holds a strong reference back to its key.
    wrappedParents.clear()
    return retired
  }

  /**
   * Updates the active [classPath] and [projectClassPath], disposes the current per-RPC [DefaultModuleClassLoader] so the next render
   * creates a fresh project classloader while reusing the persistent [WarmDepsClassLoader] and [LibraryClassBinaryCache], and returns the
   * retired [DefaultModuleClassLoader] (if any) so the caller can release any thread-local state associated with it.
   */
  fun refreshClassPath(
    newClassPath: List<String>,
    newProjectClassPath: List<String>,
  ): DefaultModuleClassLoader? {
    return sharedClassLoadersLock.withLock {
      refreshGeneration++
      cachedProjectScan = null
      LibraryClassBinaryCache.invalidateDirectoryTimestampCache()
      classPath = newClassPath
      projectClassPath = newProjectClassPath
      val prev = currentSharedClassLoader
      prev?.dispose()
      recordRetiredClassLoaderLocked(prev)
      currentSharedClassLoader = null
      prev
    }
  }

  @GuardedBy("sharedClassLoadersLock")
  private fun getOrCreateWarmDepsClassLoaderLocked(
    wrappedParent: ClassLoader,
    currentClassPath: List<String>,
    currentProjectClassPath: List<String>,
  ): WarmDepsClassLoader {
    val existing = warmDepsClassLoaders[wrappedParent]
    if (existing != null && currentSharedClassLoader != null && existing.verifiedAtGeneration == refreshGeneration) {
      return existing
    }
    val stableDeps = LibraryClassBinaryCache.computeStableDepsClassPath(currentClassPath, currentProjectClassPath)
    val state = LibraryClassBinaryCache.prepareState(currentClassPath, currentProjectClassPath)
    val projectScan =
      if (cachedProjectScanGeneration == refreshGeneration && cachedProjectScan != null) {
        cachedProjectScan!!
      } else {
        scanProjectClassPath(currentProjectClassPath).also {
          cachedProjectScan = it
          cachedProjectScanGeneration = refreshGeneration
        }
      }
    val desiredKey =
      WarmDepsKey(
        wrappedParentIdentity = System.identityHashCode(wrappedParent),
        stableDepsClassPath = stableDeps,
        libraryIdentities = state.libraryIdentities,
        projectRSignature = projectScan.rSignature,
      )
    if (
      existing != null &&
        existing.key == desiredKey &&
        projectScan.nonRClassNames.none { existing.hasLoadedOnDiskClass(it) }
    ) {
      existing.projectNonRClasses = projectScan.nonRClassNames
      existing.verifiedAtGeneration = refreshGeneration
      return existing
    }
    if (existing != null) {
      existing.close()
      recordRetiredClassLoaderLocked(existing)
    }
    warmDepsClassLoaders.remove(wrappedParent)
    val activeParent = currentSharedClassLoader?.parent
    if (warmDepsClassLoaders.size >= MAX_WARM_DEPS_LOADERS) {
      val iterator = warmDepsClassLoaders.entries.iterator()
      while (warmDepsClassLoaders.size >= MAX_WARM_DEPS_LOADERS && iterator.hasNext()) {
        val eldest = iterator.next()
        if (eldest.value === activeParent) continue
        iterator.remove()
        eldest.value.close()
        recordRetiredClassLoaderLocked(eldest.value)
      }
    }
    return WarmDepsClassLoader(
        wrappedParent = wrappedParent,
        key = desiredKey,
        state = state,
        projectRClassEntries = projectScan.rClassEntries,
        projectNonRClasses = projectScan.nonRClassNames,
      )
      .also {
        it.verifiedAtGeneration = refreshGeneration
        warmDepsClassLoaders[wrappedParent] = it
      }
  }

  private fun createClassLoader(parent: ClassLoader?): DefaultModuleClassLoader {
    val wrapped = wrapParent(parent)
    return sharedClassLoadersLock.withLock {
      val warmDeps = getOrCreateWarmDepsClassLoaderLocked(wrapped, classPath, projectClassPath)
      DefaultModuleClassLoader(warmDeps, projectClassPath)
    }
  }

  /**
   * Returns the shared [ModuleClassLoader] for the current render batch, creating it lazily if needed.
   *
   * The returned [ModuleClassLoaderManager.Reference] uses a no-op release callback (`{}`) so that individual [RenderTask]s closing at the
   * end of each preview in a multi-preview batch do not dispose the shared classloader (which would reset `rClassesInitialized` and force
   * every subsequent preview in the batch to re-load all classes). Instead, [currentSharedClassLoader] is explicitly disposed between RPCs
   * via [refreshClassPath] or on environment teardown via [close].
   */
  fun getShared(parent: ClassLoader?): ModuleClassLoaderManager.Reference<ModuleClassLoader> {
    val classLoader = sharedClassLoadersLock.withLock {
      currentSharedClassLoader ?: createClassLoader(parent).also { currentSharedClassLoader = it }
    }
    return ModuleClassLoaderManager.Reference(classLoader, {})
  }

  /**
   * Returns a dedicated, unshared [ModuleClassLoader] that is disposed as soon as the caller releases the returned
   * [ModuleClassLoaderManager.Reference].
   */
  fun getPrivate(parent: ClassLoader?): ModuleClassLoaderManager.Reference<ModuleClassLoader> {
    val classLoader = createClassLoader(parent)
    return ModuleClassLoaderManager.Reference(classLoader) { classLoader.dispose() }
  }

  override fun clearCache(module: Module) {
    sharedClassLoadersLock.withLock {
      refreshGeneration++
      cachedProjectScan = null
      LibraryClassBinaryCache.invalidateDirectoryTimestampCache()
      val prev = currentSharedClassLoader
      prev?.dispose()
      recordRetiredClassLoaderLocked(prev)
      currentSharedClassLoader = null
      warmDepsClassLoaders.values.forEach { warm ->
        warm.close()
        recordRetiredClassLoaderLocked(warm)
      }
      warmDepsClassLoaders.clear()
    }
    wrappedParents.clear()
  }
}
