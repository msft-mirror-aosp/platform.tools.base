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

import com.android.tools.rendering.classloading.ClassBinaryCache
import com.android.tools.rendering.classloading.ClassBinaryCacheManager
import com.android.tools.rendering.classloading.CodeExecutionTrackerTransform
import com.android.tools.rendering.classloading.ExternalLibraryClassLoaderEntry
import com.android.tools.rendering.classloading.ExternalLibraryClassLoaderPool
import com.android.tools.rendering.classloading.PseudoClassLocatorForLoader
import com.android.tools.rendering.classloading.RepackageTransform
import com.android.tools.rendering.classloading.ResourcesCompatTransform
import com.android.tools.rendering.classloading.SdkIntReplacer
import com.android.tools.rendering.classloading.StringReplaceTransform
import com.android.tools.rendering.classloading.loaders.AsmTransformingLoader
import com.android.tools.rendering.classloading.loaders.ClassBinaryCacheLoader
import com.android.tools.rendering.classloading.loaders.ClassLoaderLoader
import com.android.tools.rendering.classloading.loaders.DelegatingClassLoader
import com.android.tools.rendering.classloading.loaders.ListeningLoader
import com.android.tools.rendering.classloading.loaders.MultiLoaderWithAffinity
import com.android.tools.rendering.classloading.loaders.NameRemapperLoader
import com.android.tools.rendering.classloading.toClassTransform
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path
import org.objectweb.asm.ClassWriter

/**
 * Bytecode transforms applied to external library classes (`classPath - projectClassPath`).
 *
 * Unlike [PROJECT_CLASS_TRANSFORMS], library classes do not need [CodeExecutionTrackerTransform] (which tracks which user classes were
 * executed during rendering), allowing their transformed bytecode to be cached across renders via [ClassBinaryCacheManager] and
 * [ClassBinaryCacheLoader].
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
 * These classes are loaded uncached on every render RPC so that incremental `.kt` / `.java` edits compiled to disk are picked up
 * immediately.
 */
private val PROJECT_CLASS_TRANSFORMS =
  toClassTransform(
    { CodeExecutionTrackerTransform(it, CLASSES_TRACKER_KEY) },
    ::SdkIntReplacer,
    { RepackageTransform(it, PACKAGES_TO_RENAME, INTERNAL_PACKAGE) },
  )

/**
 * Maps internal repackaged class names (e.g. `_layoutlib_._internal_.kotlin.Unit`) back to their original on-disk class names
 * (`kotlin.Unit`) before looking up `.class` files in JARs or output directories.
 */
private val ON_DISK_LOOKUP: (String) -> String = { fqcn ->
  if (fqcn.startsWith(INTERNAL_PACKAGE)) fqcn.removePrefix(INTERNAL_PACKAGE) else fqcn
}

/**
 * Snapshot of the warm external-library state for a [DefaultLoader] instance, backed by Android Studio's shared
 * [ExternalLibraryClassLoaderPool] and [ClassBinaryCacheManager].
 */
internal data class LibraryLoaderState(
  val externalLibraryEntry: ExternalLibraryClassLoaderEntry,
  val binaryCache: ClassBinaryCache,
  val libraryIdentities: Map<String, String>,
)

/**
 * Coordinates external library caching for standalone preview rendering using Android Studio's shared classloading infrastructure
 * ([ExternalLibraryClassLoaderPool], [ClassBinaryCacheManager], and [ClassBinaryCacheLoader]):
 *
 * 1. **[ExternalLibraryClassLoaderPool]**: Reuses the warm external-library [UrlClassLoader] (backed by the shared `ourLoaderCachePool`)
 *    and `missingClasses` negative-lookup set across [DefaultLoader] instances, keyed by `libraryPath -> "$libraryPath@$modificationTime"`.
 * 2. **[ClassBinaryCacheManager] + [ClassBinaryCacheLoader]**: Caches ASM-transformed library bytecode keyed by
 *    `"$libraryPath@$modificationTime"` via [ClassBinaryCache.setDependencies], automatically invalidating stale library classes when a
 *    dependency JAR or directory updates.
 * 3. **[serviceUrlCache]**: Caches rewritten `META-INF/services/` files with `kotlin.*` and `kotlinx.*` implementation names prefixed with
 *    [INTERNAL_PACKAGE].
 */
object LibraryClassBinaryCache {
  private val lock = Any()
  @Volatile private var currentLibraryIdentities: ConcurrentHashMap<String, String>? = null
  @Volatile private var cacheScope: Any = Any()
  private val directoryTimestampCache = ConcurrentHashMap<String, CachedDirectoryTimestamp>()
  val serviceUrlCache = ConcurrentHashMap<String, ByteArray>()
  private val _asmTransformCount = AtomicInteger(0)

  private data class CachedDirectoryTimestamp(val timestamp: Long, val cachedAtMs: Long)

  /** Number of external library classes transformed by ASM (exposed for testing/diagnostics). */
  val asmTransformCount: Int
    get() = _asmTransformCount.get()

  internal fun recordAsmTransform() {
    _asmTransformCount.incrementAndGet()
  }

  /**
   * Computes the external dependency classpath by subtracting [projectClassPath] from [classPath] while preserving classpath order and
   * deduplicating entries.
   *
   * Deduplication (`.distinct()`) is necessary because callers such as `Main.kt` append extracted `rClassJars` (`classPath + rClassJars`)
   * when constructing [StandaloneModuleClassLoaderManager], and those `R.jar` paths may already be present in [classPath].
   */
  fun computeStableDepsClassPath(classPath: List<String>, projectClassPath: List<String>): List<String> {
    val projectClassPathSet = projectClassPath.toSet()
    return classPath.filter { !projectClassPathSet.contains(it) }.distinct()
  }

  /**
   * Returns the last-modified timestamp for [path], walking subdirectories if [path] is a directory.
   *
   * Why directory walking + TTL caching is needed:
   * - On POSIX/Linux filesystems, a directory's own `mtime` ([File.lastModified]) only updates when direct child entries are created,
   *   renamed, or deleted — it does NOT update when a file nested inside a package subdirectory (e.g. `com/example/Foo.class` or
   *   `res/layout/main.xml`) is modified in place or replaced in a nested directory. Walking the directory tree with [File.walkTopDown]
   *   ensures nested file edits are detected.
   * - Because both `WarmEnvironmentKey` (in `Main.kt`) and [prepareState] inspect classpath timestamps during the same render RPC,
   *   [directoryTimestampCache] caches directory walk results for [maxCacheAgeMs] (default 1 second) to avoid walking the same directory
   *   tree twice per RPC.
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
   * Prepares and returns the [LibraryLoaderState] for [classPath] and [projectClassPath], acquiring the shared
   * [ExternalLibraryClassLoaderEntry] and updating [ClassBinaryCache.setDependencies].
   */
  internal fun prepareState(classPath: List<String>, projectClassPath: List<String>): LibraryLoaderState {
    val stableDepsPaths = computeStableDepsClassPath(classPath, projectClassPath).map { Path(it) }
    val libraryIdentities =
      ExternalLibraryClassLoaderPool.computeLibraryIdentities(stableDepsPaths) { path ->
        computePathTimestamp(path.toString())
      }
    val externalLibraryEntry = ExternalLibraryClassLoaderPool.getOrCreateEntry(stableDepsPaths, libraryIdentities)
    synchronized(lock) {
      if (currentLibraryIdentities != libraryIdentities) {
        currentLibraryIdentities = ConcurrentHashMap(libraryIdentities)
        serviceUrlCache.clear()
      }
      val binaryCache = ClassBinaryCacheManager.getInstance().getCache(cacheScope)
      binaryCache.setDependencies(libraryIdentities.values)
      return LibraryLoaderState(
        externalLibraryEntry = externalLibraryEntry,
        binaryCache = binaryCache,
        libraryIdentities = libraryIdentities,
      )
    }
  }

  /**
   * Wraps a `META-INF/services/` resource [originalUrl] in a synthetic `metainfservices:` [URL] whose stream rewrites `kotlin.*` and
   * `kotlinx.*` service provider implementation class names to start with [INTERNAL_PACKAGE] (`_layoutlib_._internal_.`).
   *
   * Why this is needed: [DEPS_CLASS_TRANSFORMS] and [PROJECT_CLASS_TRANSFORMS] apply [RepackageTransform] to rename all `kotlin.*` and
   * `kotlinx.*` classes loaded from the module classpath into `_layoutlib_._internal_.kotlin(x).*` so they do not clash with the Kotlin
   * stdlib/coroutines bundled in the host JVM. When repackaged code calls `ServiceLoader.load(...)` (for example, `kotlinx.coroutines`
   * loading `MainDispatcherFactory`), `ServiceLoader` reads the text file under `META-INF/services/` and instantiates the listed
   * implementation classes by name; without prefixing those class names with [INTERNAL_PACKAGE], `ServiceLoader` would fail with
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

  fun clearForTest() {
    synchronized(lock) {
      currentLibraryIdentities = null
      cacheScope = Any()
      serviceUrlCache.clear()
      directoryTimestampCache.clear()
      _asmTransformCount.set(0)
      ExternalLibraryClassLoaderPool.clearForTest()
    }
  }
}

/**
 * Returns `true` if [fqcn] is a top-level or inner Android resource (`R` or `R$*`) class.
 *
 * Resource classes must always be resolved in strict `projectLoader` -> `depsLoader` order rather than via [MultiLoaderWithAffinity]:
 * 1. In Android builds (e.g., library screenshot tests), AGP places the linked `R.jar` (containing finalized `0x7f...` resource IDs for
 *    both project and transitive library packages such as `androidx.compose.ui.R$id`) on `projectClassPath` (`projectLoader`), while
 *    unlinked compile-time `R.jar`s (where all resource IDs are `0`) remain on `depsClassPath` (`depsLoader`). Using package-affinity
 *    dispatch for `R` classes would cause `depsLoader` (which has affinity for `androidx.compose.ui` after loading Compose library code) to
 *    shadow the linked `R.jar` in `projectLoader` with uninitialized `0` resource IDs.
 * 2. Conversely, scanning `rClassJars` (on `depsClassPath`) during bootstrap loads `R` classes in the module's own package from
 *    `depsLoader`, which would otherwise assign `depsLoader` affinity to the user module's package before user project classes are loaded.
 */
private fun isResourceClass(fqcn: String): Boolean {
  val simpleName = fqcn.substringAfterLast('.')
  return simpleName == "R" || simpleName.startsWith("R$")
}

/**
 * [DelegatingClassLoader.Loader] responsible for loading both user project classes and external library classes into a single out-of-studio
 * [DefaultModuleClassLoader].
 *
 * Shares the same classloading components as Android Studio's `ModuleClassLoaderImpl` (`ModuleClassLoaderUtil.kt`):
 * 1. **Project tier (`projectClassPath`)**: Uses a per-RPC [projectClassLoader] built with `useCache(false)` so newly recompiled user
 *    `.class` files on disk are always read freshly on every render RPC and transformed via [PROJECT_CLASS_TRANSFORMS].
 * 2. **Library tier (`classPath - projectClassPath`)**: Reuses the warm external-library [UrlClassLoader] and negative-lookup set from
 *    [ExternalLibraryClassLoaderPool] (backed by the shared `ourLoaderCachePool`) and caches ASM-transformed library class bytes in
 *    [ClassBinaryCacheManager] via [ClassBinaryCacheLoader].
 * 3. **Package-affinity dispatch**: Combines `projectLoader` and `depsLoader` using [MultiLoaderWithAffinity] so once a package prefix is
 *    resolved in `depsLoader`, subsequent classes in that package check `depsLoader` first without probing `projectClassLoader` on disk.
 */
internal class DefaultLoader(parent: ClassLoader?, classPath: List<String>, projectClassPath: List<String>) :
  DelegatingClassLoader.Loader, AutoCloseable {
  val classesToPaths = ConcurrentHashMap<String, String>()

  private val state: LibraryLoaderState = LibraryClassBinaryCache.prepareState(classPath, projectClassPath)
  private val projectLoader: DelegatingClassLoader.Loader
  private val depsLoader: DelegatingClassLoader.Loader
  private val loader: DelegatingClassLoader.Loader
  private val depsClassLoader: ClassLoader = state.externalLibraryEntry.classLoader
  private val projectClassLoader: ClassLoader

  init {
    val parentLoader = parent?.let { ClassLoaderLoader(it) }
    // Keying by the parent classloader identity ensures that if different parents lead to
    // different supertype resolution results during ASM's stack map frame computation
    // (COMPUTE_MAXS/FRAMES via PseudoClassLocatorForLoader), their transformed bytecode does not
    // collide in the cache.
    val depsTransformationId = "${DEPS_CLASS_TRANSFORMS.id}@${parent?.let { System.identityHashCode(it) } ?: "null"}"
    val fqcnToLibraryIdentity = ConcurrentHashMap<String, String>()
    val underlyingJarLoader =
      state.externalLibraryEntry.createCachingJarLoader(state.libraryIdentities) { fqcn, libraryIdentity ->
        fqcnToLibraryIdentity[fqcn] = libraryIdentity
      }
    val jarLoader = NameRemapperLoader(underlyingJarLoader, ON_DISK_LOOKUP)
    depsLoader =
      ClassBinaryCacheLoader(
        delegate =
          ListeningLoader(
            delegate =
              AsmTransformingLoader(
                transform = DEPS_CLASS_TRANSFORMS,
                delegate = jarLoader,
                pseudoClassLocator =
                  PseudoClassLocatorForLoader(
                    loaders = listOfNotNull(jarLoader, parentLoader).asSequence(),
                    fallbackClassloader = parent,
                  ),
                // COMPUTE_MAXS is required because some transforms (e.g. CodeExecutionTrackerTransform) inject method calls that alter the
                // maximum stack size.
                asmFlags = ClassWriter.COMPUTE_MAXS,
                onRewrite = { _, _, _ -> LibraryClassBinaryCache.recordAsmTransform() },
              ),
            onAfterLoad = { fqcn, bytes ->
              // `fqcn` is the requested name (potentially remapped with INTERNAL_PACKAGE prefix),
              // while `fqcnToLibraryIdentity` is keyed by the on-disk name returned by the underlying JAR loader.
              fqcnToLibraryIdentity.remove(ON_DISK_LOOKUP(fqcn))?.let { libraryIdentity ->
                state.binaryCache.put(fqcn, depsTransformationId, libraryIdentity, bytes)
              }
            },
          ),
        transformationId = depsTransformationId,
        binaryCache = state.binaryCache,
      )

    // Disable UrlClassLoader caching (`useCache(false)`) for projectClassPath so in-place edits to
    // user `.class` files on disk are never served from a stale directory/JAR index.
    projectClassLoader = UrlClassLoader.build().useCache(false).parent(parent).files(projectClassPath.map { Path(it) }).get()
    val projectClassLoaderLoaderRaw = ClassLoaderLoader(projectClassLoader) { fqcn, path, _ -> classesToPaths[fqcn] = path }
    val projectClassLoaderLoader = NameRemapperLoader(projectClassLoaderLoaderRaw, ON_DISK_LOOKUP)
    projectLoader =
      AsmTransformingLoader(
        PROJECT_CLASS_TRANSFORMS,
        projectClassLoaderLoader,
        PseudoClassLocatorForLoader(listOfNotNull(projectClassLoaderLoader, depsLoader, parentLoader).asSequence(), parent),
        // COMPUTE_MAXS is required because some transforms (e.g. CodeExecutionTrackerTransform) inject method calls that alter the
        // maximum stack size.
        asmFlags = ClassWriter.COMPUTE_MAXS,
      )
    // Search `projectLoader` before `depsLoader` by default so user project classes take precedence,
    // while recording package-prefix affinity via `MultiLoaderWithAffinity` to avoid probing
    // `projectClassLoader` on disk for known external library packages.
    loader = MultiLoaderWithAffinity(projectLoader, depsLoader)
  }

  fun getResource(name: String): URL? {
    // Strip `_layoutlib_._internal_.` from resource lookups initiated by repackaged classes (e.g.
    // `ServiceLoader.load(_layoutlib_._internal_.kotlinx.coroutines.internal.MainDispatcherFactory)`
    // looking up `META-INF/services/_layoutlib_._internal_.kotlinx.coroutines...`).
    val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
    val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
    val url = projectClassLoader.getResource(mappedName) ?: depsClassLoader.getResource(mappedName)
    if (url != null && name.startsWith("META-INF/services/")) {
      return LibraryClassBinaryCache.wrapServiceUrl(url)
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
      allRes = allRes.map { LibraryClassBinaryCache.wrapServiceUrl(it) }
    }
    return Collections.enumeration(allRes)
  }

  override fun loadClass(fqcn: String): ByteArray? {
    if (isResourceClass(fqcn)) {
      return projectLoader.loadClass(fqcn) ?: depsLoader.loadClass(fqcn)
    }
    return loader.loadClass(fqcn)
  }

  override fun close() {
    classesToPaths.clear()
    (projectClassLoader as? UrlClassLoader)?.classPath?.reset()
    (projectClassLoader as? AutoCloseable)?.close()
  }
}
