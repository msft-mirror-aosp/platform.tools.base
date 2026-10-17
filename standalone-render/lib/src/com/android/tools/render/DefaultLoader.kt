/*
 * Copyright (C) 2026 The Android Open Source Project
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

import com.android.tools.rendering.classloading.CodeExecutionTrackerTransform
import com.android.tools.rendering.classloading.PseudoClassLocatorForLoader
import com.android.tools.rendering.classloading.RepackageTransform
import com.android.tools.rendering.classloading.ResourcesCompatTransform
import com.android.tools.rendering.classloading.SdkIntReplacer
import com.android.tools.rendering.classloading.StringReplaceTransform
import com.android.tools.rendering.classloading.loaders.AsmTransformingLoader
import com.android.tools.rendering.classloading.loaders.ClassLoaderLoader
import com.android.tools.rendering.classloading.loaders.DelegatingClassLoader
import com.android.tools.rendering.classloading.loaders.MultiLoader
import com.android.tools.rendering.classloading.loaders.NameRemapperLoader
import com.android.tools.rendering.classloading.toClassTransform
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.lang.UrlClassLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Collections
import java.util.Enumeration
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import org.objectweb.asm.ClassWriter

/**
 * Bytecode transforms applied to external library classes (`classPath - projectClassPath`).
 *
 * Unlike [PROJECT_CLASS_TRANSFORMS], library classes do not need [CodeExecutionTrackerTransform]
 * (which tracks which user classes were executed during rendering), allowing their transformed
 * bytecode to be cached across renders in [LibraryClassBinaryCacheEntry].
 */
private val DEPS_CLASS_TRANSFORMS =
  toClassTransform(
    ::ResourcesCompatTransform,
    ::SdkIntReplacer,
    { StringReplaceTransform(it, STRING_REPLACEMENTS) },
    { RepackageTransform(it, PACKAGES_TO_RENAME, INTERNAL_PACKAGE) },
  )

/**
 * Bytecode transforms applied to user project classes (`projectClassPath`).
 *
 * These classes are loaded uncached on every render RPC so that incremental `.kt` / `.java` edits
 * compiled to disk are picked up immediately.
 */
private val PROJECT_CLASS_TRANSFORMS =
  toClassTransform(
    { CodeExecutionTrackerTransform(it, CLASSES_TRACKER_KEY) },
    ::SdkIntReplacer,
    { RepackageTransform(it, PACKAGES_TO_RENAME, INTERNAL_PACKAGE) },
  )

/**
 * Maps internal repackaged class names (e.g. `_layoutlib_._internal_.kotlin.Unit`) back to their
 * original on-disk class names (`kotlin.Unit`) before looking up `.class` files in JARs or output
 * directories.
 */
private val ON_DISK_LOOKUP: (String) -> String = { fqcn ->
  if (fqcn.startsWith(INTERNAL_PACKAGE)) fqcn.removePrefix(INTERNAL_PACKAGE) else fqcn
}

/**
 * Cache key identifying a specific ordered set of external library classpath entries and their
 * last-modified timestamps on disk.
 *
 * If any library JAR is added, removed, reordered, or modified on disk (for example after a Gradle
 * dependency update or `R.jar` regeneration), [fileTimestamps] or [stableDepsClassPath] changes and
 * [LibraryClassBinaryCache] invalidates the previous [LibraryClassBinaryCacheEntry].
 */
internal data class LibraryCacheKey(
  val stableDepsClassPath: List<String>,
  val fileTimestamps: List<Long>,
)

/**
 * Process-wide single-entry cache of external library JAR indexes, ASM-transformed library class
 * bytes, and `META-INF/services` remapped payloads, keyed by [LibraryCacheKey].
 *
 * In the standalone preview renderer daemon, user project classes (`projectClassPath`) recompile
 * frequently while external dependency JARs (`classPath - projectClassPath`, such as Compose UI,
 * Material3, Kotlin stdlib, and AndroidX) remain immutable across incremental edits. Caching the
 * indexed [UrlClassLoader] and ASM-transformed bytecode for external libraries eliminates ~120 ms
 * of redundant ZIP central-directory indexing and ~340 ms of redundant ASM bytecode rewriting on
 * every warm render RPC.
 */
object LibraryClassBinaryCache {
  @Volatile private var currentEntry: LibraryClassBinaryCacheEntry? = null
  private val lock = Any()
  private val directoryTimestampCache = ConcurrentHashMap<String, CachedDirectoryTimestamp>()

  private data class CachedDirectoryTimestamp(val timestamp: Long, val cachedAtMs: Long)

  /**
   * Computes the external dependency classpath by subtracting [projectClassPath] from [classPath]
   * while preserving classpath order and deduplicating entries.
   *
   * Deduplication (`.distinct()`) is necessary because callers such as `Main.kt` append extracted
   * `rClassJars` (`classPath + rClassJars`) when constructing [StandaloneModuleClassLoaderManager],
   * and those `R.jar` paths may already be present in [classPath].
   */
  fun computeStableDepsClassPath(classPath: List<String>, projectClassPath: List<String>): List<String> {
    val projectClassPathSet = projectClassPath.toSet()
    return classPath.filter { !projectClassPathSet.contains(it) }.distinct()
  }

  /**
   * Returns the last-modified timestamp for [path], walking subdirectories if [path] is a
   * directory.
   *
   * Why directory walking + TTL caching is needed:
   * - On POSIX/Linux filesystems, a directory's own `mtime` ([File.lastModified]) only updates when
   *   direct child entries are created, renamed, or deleted — it does NOT update when a file nested
   *   inside a package subdirectory (e.g. `com/example/Foo.class` or `res/layout/main.xml`) is
   *   modified in place or replaced in a nested directory. Walking the directory tree with
   *   [File.walkTopDown] ensures nested file edits are detected.
   * - Because both `WarmEnvironmentKey` (in `Main.kt`) and [getEntry] inspect classpath timestamps
   *   during the same render RPC, [directoryTimestampCache] caches directory walk results for
   *   [maxCacheAgeMs] (default 1 second) to avoid walking the same directory tree twice per RPC.
   */
  fun computePathTimestamp(path: String, maxCacheAgeMs: Long = 1000L): Long {
    val file = File(path)
    if (!file.exists()) return 0L
    if (file.isDirectory) {
      val now = System.currentTimeMillis()
      val cached = directoryTimestampCache[path]
      if (cached != null && (now - cached.cachedAtMs) < maxCacheAgeMs) {
        return cached.timestamp
      }
      val computed = file.walkTopDown().maxOfOrNull { it.lastModified() } ?: 0L
      directoryTimestampCache[path] = CachedDirectoryTimestamp(computed, now)
      return computed
    }
    return file.lastModified()
  }

  /**
   * Returns a retained [LibraryClassBinaryCacheEntry] matching [classPath] and [projectClassPath],
   * creating and caching a fresh entry (and evicting the previous one) if the key changed.
   *
   * Callers **must** invoke [LibraryClassBinaryCacheEntry.release] when finished with the returned
   * entry (typically in [DefaultLoader.close]) so evicted entries can close their underlying
   * [UrlClassLoader] ZIP file handles once all in-flight renders complete.
   */
  internal fun getEntry(classPath: List<String>, projectClassPath: List<String>): LibraryClassBinaryCacheEntry {
    val stableDepsClassPath = computeStableDepsClassPath(classPath, projectClassPath)
    val timestamps = stableDepsClassPath.map { computePathTimestamp(it) }
    val key = LibraryCacheKey(stableDepsClassPath, timestamps)
    while (true) {
      // Lock-free fast path: if the current entry matches `key` and we successfully increment its
      // reference count before it is evicted, return it directly without contending on `lock`.
      val existing = currentEntry
      if (existing != null && existing.key == key && existing.retain()) {
        return existing
      }
      synchronized(lock) {
        // Re-check under `lock` in case another thread already installed a matching entry.
        val again = currentEntry
        if (again != null && again.key == key && again.retain()) {
          return again
        }
        // Key mismatch (or previous entry was closed): mark the old entry as evicted so it closes
        // its JAR file handles as soon as its last active reference is released, then install a
        // fresh entry with an initial reference count of 1 for the caller.
        again?.evictAndRelease()
        currentEntry = null
        val fresh = LibraryClassBinaryCacheEntry(key)
        val retained = fresh.retain()
        check(retained) { "Fresh cache entry must be retainable" }
        currentEntry = fresh
        return fresh
      }
    }
  }

  fun clearForTest() {
    synchronized(lock) {
      currentEntry?.close()
      currentEntry = null
      directoryTimestampCache.clear()
    }
  }
}

/**
 * Holds the warm external-library state for a single [LibraryCacheKey], including:
 * 1. [depsClassLoader]: A [UrlClassLoader] with `useCache(true)` that keeps ZIP central-directory
 *    indexes in memory across render RPCs for fast `.class` and resource lookups.
 * 2. [nullParentTransformedBytes] & [transformedBytesByParent]: Thread-safe caches of
 *    ASM-transformed library `.class` bytecode (`ByteArray`) produced by [DEPS_CLASS_TRANSFORMS].
 *    Each per-RPC [DefaultModuleClassLoader] still calls `defineClass` into its own classloader
 *    namespace, but skips re-reading the JAR entry and re-running the 4-stage ASM pipeline.
 * 3. [serviceUrlCache]: In-memory cache of rewritten `META-INF/services/` files with `kotlin.*` and
 *    `kotlinx.*` implementation names prefixed with [INTERNAL_PACKAGE].
 *
 * ### Concurrency & Reference-Counting Lifecycle
 * Because an entry may be evicted by a new render RPC with updated dependency JARs while an older
 * render (or a concurrent test) is still loading classes from it, [depsClassLoader] cannot be
 * closed immediately upon eviction. Instead, [LibraryClassBinaryCacheEntry] uses a lock-free
 * reference-counting state machine ([activeReferences], [isEvicted], [isClosed]):
 * - Every caller acquiring the entry via [LibraryClassBinaryCache.getEntry] increments
 *   [activeReferences] via [retain] and decrements it via [release] in [DefaultLoader.close].
 * - When replaced in [LibraryClassBinaryCache], [evictAndRelease] sets `isEvicted = true` and
 *   closes the entry immediately only if `activeReferences <= 0`; otherwise, the last caller to
 *   call [release] closes it.
 */
internal class LibraryClassBinaryCacheEntry(val key: LibraryCacheKey) : AutoCloseable {
  val depsClassLoader: ClassLoader =
    UrlClassLoader.build().useCache(true).parent(null).files(key.stableDepsClassPath.map { Path(it) }).get()
  private val depsClassLoaderLoader = NameRemapperLoader(ClassLoaderLoader(depsClassLoader), ON_DISK_LOOKUP)

  /**
   * Cached ASM-transformed library bytecode when `parent == null`.
   *
   * Uses [Optional] because [ConcurrentHashMap] does not permit `null` values, allowing us to apply
   * negative caching (`Optional.empty()`) for class names that do not exist in [depsClassLoader]
   * so repeated lookups for missing classes do not re-scan all library JARs.
   */
  private val nullParentTransformedBytes = ConcurrentHashMap<String, Optional<ByteArray>>()

  /**
   * Cached ASM-transformed library bytecode keyed by non-null `parent` [ClassLoader].
   *
   * Why transformed bytecode is keyed by `parent`:
   * [AsmTransformingLoader] uses [PseudoClassLocatorForLoader] (which queries `parent`) to compute
   * common supertypes when `ClassWriter` emits stack map frames. In normal daemon rendering,
   * [StandaloneModuleClassLoaderManager] canonicalizes `parent` to a single `defaultWrappedParent`
   * instance so all warm renders share one inner [ConcurrentHashMap]. Weak keys and soft values
   * ensure that custom parent [ClassLoader]s passed in tests are never strongly retained.
   */
  private val transformedBytesByParent =
    ContainerUtil.createConcurrentWeakKeySoftValueMap<ClassLoader, ConcurrentHashMap<String, Optional<ByteArray>>>()

  /**
   * Cache of rewritten `META-INF/services/` file payloads keyed by the original JAR entry URL
   * string. Avoids re-opening JAR streams on repeated `ServiceLoader` lookups (e.g.
   * `MainDispatcherFactory` and `CoroutineExceptionHandler` on every Compose frame).
   */
  val serviceUrlCache = ConcurrentHashMap<String, ByteArray>()

  private val _asmTransformCount = AtomicInteger(0)
  private val activeReferences = AtomicInteger(0)
  @Volatile private var isEvicted = false
  @Volatile var isClosed = false
    private set

  /** Number of unique external library classes transformed by ASM for this cache entry (exposed for testing/diagnostics). */
  val asmTransformCount: Int
    get() = _asmTransformCount.get()

  /**
   * Attempts to increment the active reference count for this entry.
   *
   * Returns `true` if the entry is still active and the caller now holds a reference that must be
   * balanced by a call to [release], or `false` if the entry has already been evicted or closed.
   */
  fun retain(): Boolean {
    while (true) {
      if (isEvicted || isClosed) return false
      val current = activeReferences.get()
      if (activeReferences.compareAndSet(current, current + 1)) {
        // Re-check `isEvicted || isClosed` AFTER incrementing `activeReferences` to close a race
        // window with `evictAndRelease()`:
        // Suppose Thread A (in `evictAndRelease()`) sets `isEvicted = true` while `activeReferences`
        // is 0, and right before Thread A checks `activeReferences.get() <= 0`, Thread B (here)
        // increments `activeReferences` from 0 to 1. Thread A will see `activeReferences == 1` and
        // skip calling `close()`, delegating cleanup responsibility to Thread B. Therefore, when
        // Thread B detects `isEvicted == true` here, it MUST call `release()` (not just
        // `decrementAndGet()`) so `close()` is invoked when rolling back from 1 to 0.
        if (isEvicted || isClosed) {
          release()
          return false
        }
        return true
      }
    }
  }

  /**
   * Decrements the active reference count and closes [depsClassLoader] if this entry has been
   * evicted and no other active references remain.
   */
  fun release() {
    val remaining = activeReferences.decrementAndGet()
    if (remaining <= 0 && isEvicted) {
      close()
    }
  }

  /**
   * Marks this entry as evicted from [LibraryClassBinaryCache] and closes it immediately if no
   * callers currently hold an active reference. If callers are still using this entry, the last
   * caller to invoke [release] will close it.
   */
  fun evictAndRelease() {
    isEvicted = true
    if (activeReferences.get() <= 0) {
      close()
    }
  }

  /**
   * Returns the ASM-transformed bytecode for external library class [fqcn] using [parent] for
   * superclass hierarchy resolution, computing and caching the result on first access.
   *
   * Returns `null` (and caches `Optional.empty()`) if [fqcn] is not found in [depsClassLoader].
   */
  fun loadDepsClassBytes(fqcn: String, parent: ClassLoader?): ByteArray? {
    val map =
      if (parent == null) {
        nullParentTransformedBytes
      } else {
        transformedBytesByParent.computeIfAbsent(parent) { ConcurrentHashMap() }
      }
    return map
      .computeIfAbsent(fqcn) { targetFqcn ->
        val parentLoader = parent?.let { ClassLoaderLoader(it) }
        val depsLoader =
          AsmTransformingLoader(
            DEPS_CLASS_TRANSFORMS,
            depsClassLoaderLoader,
            PseudoClassLocatorForLoader(listOfNotNull(depsClassLoaderLoader, parentLoader).asSequence(), parent),
            // COMPUTE_MAXS is required because some transforms (e.g. CodeExecutionTrackerTransform) inject method calls that alter the
            // maximum stack size.
            asmFlags = ClassWriter.COMPUTE_MAXS,
          )
        val bytes = depsLoader.loadClass(targetFqcn)
        if (bytes != null) {
          _asmTransformCount.incrementAndGet()
        }
        Optional.ofNullable(bytes)
      }
      .orElse(null)
  }

  /**
   * Wraps a `META-INF/services/` resource [originalUrl] in a synthetic `metainfservices:` [URL]
   * whose stream rewrites `kotlin.*` and `kotlinx.*` service provider implementation class names to
   * start with [INTERNAL_PACKAGE] (`_layoutlib_._internal_.`).
   *
   * Why this is needed:
   * [DEPS_CLASS_TRANSFORMS] and [PROJECT_CLASS_TRANSFORMS] apply [RepackageTransform] to rename all
   * `kotlin.*` and `kotlinx.*` classes loaded from the module classpath into
   * `_layoutlib_._internal_.kotlin(x).*` so they do not clash with the Kotlin stdlib/coroutines
   * bundled in the host JVM. When repackaged code calls `ServiceLoader.load(...)` (for example,
   * `kotlinx.coroutines` loading `MainDispatcherFactory`), `ServiceLoader` reads the text file
   * under `META-INF/services/` and instantiates the listed implementation classes by name; without
   * prefixing those class names with [INTERNAL_PACKAGE], `ServiceLoader` would fail with
   * `ClassNotFoundException` or `ServiceConfigurationError`.
   */
  fun wrapServiceUrl(originalUrl: URL): URL {
    val handler =
      object : URLStreamHandler() {
        override fun openConnection(u: URL): URLConnection {
          return object : URLConnection(u) {
            override fun connect() {}

            override fun getInputStream(): InputStream {
              val urlString = u.file
              val cachedBytes = serviceUrlCache[urlString]
              if (cachedBytes != null) {
                return ByteArrayInputStream(cachedBytes)
              }

              val realUrl = URL(urlString)
              val out = ByteArrayOutputStream()
              realUrl.openStream().bufferedReader(Charsets.UTF_8).use { reader ->
                OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                  reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("kotlin.") || trimmed.startsWith("kotlinx.")) {
                      writer.write("$INTERNAL_PACKAGE$trimmed\n")
                    } else {
                      writer.write("$line\n")
                    }
                  }
                }
              }
              val bytes = out.toByteArray()
              serviceUrlCache[urlString] = bytes
              return ByteArrayInputStream(bytes)
            }
          }
        }
      }
    return URL("metainfservices", "", -1, originalUrl.toString(), handler)
  }

  override fun close() {
    synchronized(this) {
      if (isClosed) return
      isClosed = true
    }
    // Release cached ZIP file handles held by UrlClassLoader's internal ClassPath index.
    (depsClassLoader as? UrlClassLoader)?.classPath?.reset()
    (depsClassLoader as? AutoCloseable)?.close()
  }
}

/**
 * [DelegatingClassLoader.Loader] responsible for loading both user project classes and external
 * library classes into a single out-of-studio [DefaultModuleClassLoader].
 *
 * Splits class and resource loading across two tiers:
 * 1. **Project tier (`projectClassPath`)**: Uses a per-RPC [projectClassLoader] built with
 *    `useCache(false)` so newly recompiled user `.class` files on disk are always read freshly on
 *    every render RPC and transformed via [PROJECT_CLASS_TRANSFORMS].
 * 2. **Library tier (`classPath - projectClassPath`)**: Delegates to [cacheEntry] from
 *    [LibraryClassBinaryCache], reusing the warm [UrlClassLoader] ZIP indexes and cached
 *    ASM-transformed library class bytes across render RPCs.
 *
 * Both tiers return raw transformed `ByteArray`s to the owning [DefaultModuleClassLoader], which
 * defines both library and user classes inside the **same** flat `ClassLoader` instance.
 */
internal class DefaultLoader(parent: ClassLoader?, classPath: List<String>, projectClassPath: List<String>) :
  DelegatingClassLoader.Loader, AutoCloseable {
  val classesToPaths = ConcurrentHashMap<String, String>()

  private val cacheEntry: LibraryClassBinaryCacheEntry = LibraryClassBinaryCache.getEntry(classPath, projectClassPath)
  private val loader: DelegatingClassLoader.Loader
  private val depsClassLoader: ClassLoader = cacheEntry.depsClassLoader
  private val projectClassLoader: ClassLoader

  init {
    val parentLoader = parent?.let { ClassLoaderLoader(it) }
    val depsLoader =
      object : DelegatingClassLoader.Loader {
        override fun loadClass(fqcn: String): ByteArray? = cacheEntry.loadDepsClassBytes(fqcn, parent)
      }

    // Disable UrlClassLoader caching (`useCache(false)`) for projectClassPath so in-place edits to
    // user `.class` files on disk are never served from a stale directory/JAR index.
    projectClassLoader = UrlClassLoader.build().useCache(false).parent(parent).files(projectClassPath.map { Path(it) }).get()
    val projectClassLoaderLoaderRaw = ClassLoaderLoader(projectClassLoader) { fqcn, path, _ -> classesToPaths[fqcn] = path }
    val projectClassLoaderLoader = NameRemapperLoader(projectClassLoaderLoaderRaw, ON_DISK_LOOKUP)
    val projectLoader =
      AsmTransformingLoader(
        PROJECT_CLASS_TRANSFORMS,
        projectClassLoaderLoader,
        PseudoClassLocatorForLoader(listOfNotNull(projectClassLoaderLoader, depsLoader, parentLoader).asSequence(), parent),
        // COMPUTE_MAXS is required because some transforms (e.g. CodeExecutionTrackerTransform) inject method calls that alter the
        // maximum stack size.
        asmFlags = ClassWriter.COMPUTE_MAXS,
      )
    // Search `projectLoader` before `depsLoader` so user project classes take precedence over
    // external dependency JARs.
    loader = MultiLoader(projectLoader, depsLoader)
  }

  fun getResource(name: String): URL? {
    // Strip `_layoutlib_._internal_.` from resource lookups initiated by repackaged classes (e.g.
    // `ServiceLoader.load(_layoutlib_._internal_.kotlinx.coroutines.internal.MainDispatcherFactory)`
    // looking up `META-INF/services/_layoutlib_._internal_.kotlinx.coroutines...`).
    val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
    val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
    val url = projectClassLoader.getResource(mappedName) ?: depsClassLoader.getResource(mappedName)
    if (url != null && name.startsWith("META-INF/services/")) {
      return cacheEntry.wrapServiceUrl(url)
    }
    return url
  }

  fun getResources(name: String): Enumeration<URL> {
    val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
    val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
    val res1 = projectClassLoader.getResources(mappedName).toList()
    val res2 = depsClassLoader.getResources(mappedName).toList()
    var allRes = (res1 + res2).distinctBy { it.toString() }
    if (name.startsWith("META-INF/services/")) {
      allRes = allRes.map { cacheEntry.wrapServiceUrl(it) }
    }
    return Collections.enumeration(allRes)
  }

  override fun loadClass(fqcn: String): ByteArray? = loader.loadClass(fqcn)

  override fun close() {
    classesToPaths.clear()
    (projectClassLoader as? UrlClassLoader)?.classPath?.reset()
    (projectClassLoader as? AutoCloseable)?.close()
    // Release our reference to the shared LibraryClassBinaryCacheEntry so it can close its JAR
    // handles if it was evicted while this loader was active.
    cacheEntry.release()
  }
}
