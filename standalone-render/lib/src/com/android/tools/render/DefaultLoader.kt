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
import com.android.tools.res.ids.ResourceIdManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.lang.UrlClassLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.io.path.Path
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter

/**
 * Bytecode transforms applied to external library classes (`classPath - projectClassPath`).
 *
 * Unlike [PROJECT_CLASS_TRANSFORMS], library classes do not need [CodeExecutionTrackerTransform] (which tracks which user classes were
 * executed during rendering), allowing their transformed bytecode to be cached across renders via [ClassBinaryCacheManager] and
 * [ClassBinaryCacheLoader].
 */
internal val DEPS_CLASS_TRANSFORMS =
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
    { StringReplaceTransform(it, STRING_REPLACEMENTS) },
    { RepackageTransform(it, PACKAGES_TO_RENAME, INTERNAL_PACKAGE) },
  )

/**
 * Maps internal repackaged class names (e.g. `_layoutlib_._internal_.kotlin.Unit`) back to their original on-disk class names
 * (`kotlin.Unit`) before looking up `.class` files in JARs or output directories.
 */
internal val ON_DISK_LOOKUP: (String) -> String = { fqcn ->
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

  internal fun invalidateDirectoryTimestampCache() {
    directoryTimestampCache.clear()
  }

  fun clearForTest() {
    synchronized(lock) {
      currentLibraryIdentities = null
      cacheScope = Any()
      serviceUrlCache.clear()
      directoryTimestampCache.clear()
      clearProjectJarScanCacheForTest()
      _asmTransformCount.set(0)
      ExternalLibraryClassLoaderPool.clearForTest()
    }
  }
}

/**
 * [DelegatingClassLoader.Loader] responsible for loading user project classes (and preview tooling adapter classes when paired with a
 * persistent parent [WarmDepsClassLoader]) into a per-RPC [DefaultModuleClassLoader].
 */
internal class DefaultLoader : DelegatingClassLoader.Loader, AutoCloseable {
  val classesToPaths = ConcurrentHashMap<String, String>()

  private val warmDepsParent: WarmDepsClassLoader?
  private val projectLoader: DelegatingClassLoader.Loader
  private val depsLoader: DelegatingClassLoader.Loader
  private val loader: DelegatingClassLoader.Loader?
  private val depsClassLoader: ClassLoader?
  private val projectClassLoader: ClassLoader

  /** Fast constructor used by [StandaloneModuleClassLoaderManager] with a retained [WarmDepsClassLoader] parent. */
  constructor(warmDepsParent: WarmDepsClassLoader, projectClassPath: List<String>) {
    this.warmDepsParent = warmDepsParent
    this.depsLoader = warmDepsParent.depsBytecodeLoader
    this.depsClassLoader = null
    this.loader = null
    // Parent of `projectClassLoader` MUST be `null` so `ClassLoaderLoader`'s `getResource` probe only inspects `projectClassPath` files.
    this.projectClassLoader =
      UrlClassLoader.build().useCache(false).allowLock(!SystemInfo.isWindows).parent(null).files(projectClassPath.map { Path(it) }).get()
    val projectClassLoaderLoaderRaw = ClassLoaderLoader(projectClassLoader) { fqcn, path, _ -> classesToPaths[fqcn] = path }
    val projectClassLoaderLoader = NameRemapperLoader(projectClassLoaderLoaderRaw, ON_DISK_LOOKUP)
    val parentLoader = ClassLoaderLoader(warmDepsParent)
    this.projectLoader =
      AsmTransformingLoader(
        PROJECT_CLASS_TRANSFORMS,
        projectClassLoaderLoader,
        PseudoClassLocatorForLoader(sequenceOf(projectClassLoaderLoader, depsLoader, parentLoader), warmDepsParent),
        asmFlags = ClassWriter.COMPUTE_MAXS,
      )
  }

  /** Standalone constructor used when [DefaultModuleClassLoader] is instantiated directly without a [WarmDepsClassLoader]. */
  constructor(parent: ClassLoader?, classPath: List<String>, projectClassPath: List<String>) {
    this.warmDepsParent = null
    val state = LibraryClassBinaryCache.prepareState(classPath, projectClassPath)
    this.depsClassLoader = state.externalLibraryEntry.classLoader
    this.depsLoader = buildDepsBytecodeLoader(parent, state)
    val parentLoader = parent?.let { ClassLoaderLoader(it) }
    this.projectClassLoader =
      UrlClassLoader.build().useCache(false).allowLock(!SystemInfo.isWindows).parent(null).files(projectClassPath.map { Path(it) }).get()
    val projectClassLoaderLoaderRaw = ClassLoaderLoader(projectClassLoader) { fqcn, path, _ -> classesToPaths[fqcn] = path }
    val projectClassLoaderLoader = NameRemapperLoader(projectClassLoaderLoaderRaw, ON_DISK_LOOKUP)
    this.projectLoader =
      AsmTransformingLoader(
        PROJECT_CLASS_TRANSFORMS,
        projectClassLoaderLoader,
        PseudoClassLocatorForLoader(listOfNotNull(projectClassLoaderLoader, depsLoader, parentLoader).asSequence(), parent),
        asmFlags = ClassWriter.COMPUTE_MAXS,
      )
    this.loader = MultiLoaderWithAffinity(projectLoader, depsLoader)
  }

  fun getResource(name: String): URL? {
    val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
    val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
    val url = projectClassLoader.getResource(mappedName) ?: depsClassLoader?.getResource(mappedName)
    if (url != null && name.startsWith("META-INF/services/")) {
      return LibraryClassBinaryCache.wrapServiceUrl(url)
    }
    return url
  }

  fun getResources(name: String): Enumeration<URL> {
    val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
    val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
    val res1 = projectClassLoader.getResources(mappedName).toList()
    val res2 = depsClassLoader?.getResources(mappedName)?.toList().orEmpty()
    var allRes = (res1 + res2).distinctBy { it.toString() }
    if (name.startsWith("META-INF/services/")) {
      allRes = allRes.map { LibraryClassBinaryCache.wrapServiceUrl(it) }
    }
    return Collections.enumeration(allRes)
  }

  override fun loadClass(fqcn: String): ByteArray? {
    if (warmDepsParent != null) {
      // Non-excluded library & R classes were already loaded by `warmDepsParent` via parent delegation;
      // here we only load user `projectClassPath` classes and excluded preview-tooling adapter classes.
      return projectLoader.loadClass(fqcn)
        ?: if (isPreviewToolingAdapterClass(fqcn)) depsLoader.loadClass(fqcn) else null
    }
    if (isResourceClass(fqcn)) {
      return projectLoader.loadClass(fqcn) ?: depsLoader.loadClass(fqcn)
    }
    return loader!!.loadClass(fqcn)
  }

  override fun close() {
    classesToPaths.clear()
    (projectClassLoader as? UrlClassLoader)?.classPath?.reset()
    (projectClassLoader as? AutoCloseable)?.close()
  }
}
