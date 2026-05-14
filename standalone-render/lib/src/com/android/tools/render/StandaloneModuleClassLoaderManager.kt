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
import com.android.tools.rendering.classloading.ClassTransform
import com.android.tools.rendering.classloading.ClassesTracker
import com.android.tools.rendering.classloading.CodeExecutionTrackerTransform
import com.android.tools.rendering.classloading.FilteringClassLoader
import com.android.tools.rendering.classloading.FirewalledResourcesClassLoader
import com.android.tools.rendering.classloading.ModuleClassLoader
import com.android.tools.rendering.classloading.ModuleClassLoaderDiagnosticsRead
import com.android.tools.rendering.classloading.ModuleClassLoaderManager
import com.android.tools.rendering.classloading.Preloader
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
import com.google.common.cache.CacheBuilder
import com.intellij.openapi.module.Module
import com.intellij.util.lang.UrlClassLoader
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.Path

/**
 * [ModuleClassLoaderManager] for the [ModuleClassLoader]s used in the standalone rendering. Currently, it is a thin wrapper around
 * [UrlClassLoader].
 */
class StandaloneModuleClassLoaderManager(private val classPath: List<String>, private val projectClassPath: List<String>) :
  ModuleClassLoaderManager<ModuleClassLoader>, AutoCloseable {

  private val preloaderExecutor = Executors.newSingleThreadExecutor()
  private val preloader = Preloader(createClassLoader(null), preloaderExecutor, COMPOSE_CLASSES)

  override fun close() {
    preloaderExecutor.shutdownNow()
  }

  /**
   * A loader responsible for loading all the classes in out-of-studio version of [ModuleClassLoader].
   *
   * TODO: Merge this with ModuleClassLoaderImpl
   */
  private class DefaultLoader(parent: ClassLoader?, classPath: List<String>, projectClassPath: List<String>) :
    DelegatingClassLoader.Loader {
    val classesToPaths = mutableMapOf<String, String>()
    private val classTransforms =
      toClassTransform(
        ::ResourcesCompatTransform,
        ::SdkIntReplacer,
        { StringReplaceTransform(it, STRING_REPLACEMENTS) },
        { RepackageTransform(it, PACKAGES_TO_RENAME, INTERNAL_PACKAGE) },
      )
    private val projectClassTransforms =
      toClassTransform(
        { CodeExecutionTrackerTransform(it, CLASSES_TRACKER_KEY) },
        ::SdkIntReplacer,
        { RepackageTransform(it, PACKAGES_TO_RENAME, INTERNAL_PACKAGE) },
      )

    private val loader: DelegatingClassLoader.Loader
    private val depsClassLoader: ClassLoader
    private val projectClassLoader: ClassLoader

    init {
      val projectClassPathSet = projectClassPath.toSet()
      val depsClassPath = classPath.filter { !projectClassPathSet.contains(it) }
      val parentLoader = parent?.let { ClassLoaderLoader(it) }

      val onDiskLookup: (String) -> String = { fqcn ->
        if (fqcn.startsWith(INTERNAL_PACKAGE)) fqcn.removePrefix(INTERNAL_PACKAGE) else fqcn
      }

      depsClassLoader = UrlClassLoader.build().useCache(false).parent(parent).files(depsClassPath.map { Path(it) }).get()
      val depsClassLoaderLoaderRaw = ClassLoaderLoader(depsClassLoader)
      val depsClassLoaderLoader = NameRemapperLoader(depsClassLoaderLoaderRaw, onDiskLookup)
      val depsLoader =
        AsmTransformingLoader(
          classTransforms,
          depsClassLoaderLoader,
          PseudoClassLocatorForLoader(listOfNotNull(depsClassLoaderLoader, parentLoader).asSequence(), parent),
          // COMPUTE_MAXS is required because some transforms (e.g. CodeExecutionTrackerTransform) inject method calls that alter the
          // maximum stack size.
          asmFlags = org.objectweb.asm.ClassWriter.COMPUTE_MAXS,
        )
      projectClassLoader = UrlClassLoader.build().useCache(false).parent(parent).files(projectClassPath.map { Path(it) }).get()
      val projectClassLoaderLoaderRaw = ClassLoaderLoader(projectClassLoader) { fqcn, path, _ -> classesToPaths[fqcn] = path }
      val projectClassLoaderLoader = NameRemapperLoader(projectClassLoaderLoaderRaw, onDiskLookup)
      val projectLoader =
        AsmTransformingLoader(
          projectClassTransforms,
          projectClassLoaderLoader,
          PseudoClassLocatorForLoader(listOfNotNull(projectClassLoaderLoader, depsLoader, parentLoader).asSequence(), parent),
          // COMPUTE_MAXS is required because some transforms (e.g. CodeExecutionTrackerTransform) inject method calls that alter the
          // maximum stack size.
          asmFlags = org.objectweb.asm.ClassWriter.COMPUTE_MAXS,
        )
      loader = MultiLoader(projectLoader, depsLoader)
    }

    private val serviceUrlCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private fun wrapServiceUrl(originalUrl: java.net.URL): java.net.URL {
      val handler =
        object : java.net.URLStreamHandler() {
          override fun openConnection(u: java.net.URL): java.net.URLConnection {
            return object : java.net.URLConnection(u) {
              override fun connect() {}

              override fun getInputStream(): java.io.InputStream {
                val urlString = u.file
                val cachedBytes = serviceUrlCache[urlString]
                if (cachedBytes != null) {
                  return java.io.ByteArrayInputStream(cachedBytes)
                }

                val realUrl = java.net.URL(urlString)
                val out = java.io.ByteArrayOutputStream()
                realUrl.openStream().bufferedReader(Charsets.UTF_8).use { reader ->
                  java.io.OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
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
                return java.io.ByteArrayInputStream(bytes)
              }
            }
          }
        }
      return java.net.URL("metainfservices", "", -1, originalUrl.toString(), handler)
    }

    fun getResource(name: String): java.net.URL? {
      val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
      val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
      val url = projectClassLoader.getResource(mappedName) ?: depsClassLoader.getResource(mappedName)
      if (url != null && name.startsWith("META-INF/services/")) {
        return wrapServiceUrl(url)
      }
      return url
    }

    fun getResources(name: String): java.util.Enumeration<java.net.URL> {
      val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
      val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
      val res1 = projectClassLoader.getResources(mappedName).toList()
      val res2 = depsClassLoader.getResources(mappedName).toList()
      var allRes = res1 + res2
      if (name.startsWith("META-INF/services/")) {
        allRes = allRes.map { wrapServiceUrl(it) }
      }
      return java.util.Collections.enumeration(allRes)
    }

    override fun loadClass(fqcn: String): ByteArray? = loader.loadClass(fqcn)
  }

  class DefaultModuleClassLoader private constructor(parent: ClassLoader?, private val loader: DefaultLoader) :
    ModuleClassLoader(parent, loader) {
    constructor(
      parent: ClassLoader?,
      classPath: List<String>,
      projectClassPath: List<String>,
    ) : this(parent, DefaultLoader(parent, classPath, projectClassPath))

    override fun getResource(name: String): java.net.URL? {
      return loader.getResource(name) ?: super.getResource(name)
    }

    override fun getResources(name: String): java.util.Enumeration<java.net.URL> {
      val localRes = loader.getResources(name).toList()
      val parentRes = super.getResources(name).toList()
      return java.util.Collections.enumeration(localRes + parentRes)
    }

    private val loadedClasses = mutableSetOf<String>()
    override val stats: ModuleClassLoaderDiagnosticsRead =
      object : ModuleClassLoaderDiagnosticsRead {
        override val classesFound: Long = 0
        override val accumulatedFindTimeMs: Long = 0
        override val accumulatedRewriteTimeMs: Long = 0
      }
    override val isUserCodeUpToDate: Boolean = true

    override fun hasLoadedClass(fqcn: String): Boolean = loadedClasses.contains(fqcn)

    override val projectLoadedClasses: Set<String> = emptySet()
    override val nonProjectLoadedClasses: Set<String>
      get() = loadedClasses

    override val projectClassesTransform: ClassTransform = ClassTransform.identity
    override val nonProjectClassesTransform: ClassTransform = ClassTransform.identity

    override fun dispose() {}

    override val isDisposed: Boolean = false

    override fun isCompatibleParentClassLoader(parent: ClassLoader?): Boolean = true

    override fun areDependenciesUpToDate(): Boolean = true

    override fun onAfterLoadClass(fqcn: String, loaded: Boolean, durationMs: Long) {
      if (loaded) {
        loadedClasses.add(fqcn)
      }
    }

    val classesToPaths: Map<String, String>
      get() = loader.classesToPaths
  }

  private fun createClassLoader(parent: ClassLoader?): DefaultModuleClassLoader {
    val wrappedParent =
      FirewalledResourcesClassLoader(
        FilteringClassLoader.allowedPrefixes(parent ?: LayoutlibClassLoader(this::class.java.classLoader), ALLOWED_PACKAGES_FROM_PARENT)
      )
    return DefaultModuleClassLoader(wrappedParent, classPath, projectClassPath)
  }

  private val sharedClassLoadersLock = ReentrantLock()
  @GuardedBy("sharedClassLoadersLock")
  private val sharedClassLoaders = CacheBuilder.newBuilder().maximumSize(10).build<ClassLoader?, DefaultModuleClassLoader>()

  fun getShared(parent: ClassLoader?): ModuleClassLoaderManager.Reference<ModuleClassLoader> {
    val classLoader = sharedClassLoadersLock.withLock { sharedClassLoaders.get(parent) { preloader.getClassLoader() } }
    return ModuleClassLoaderManager.Reference(classLoader, {})
  }

  fun getPrivate(parent: ClassLoader?): ModuleClassLoaderManager.Reference<ModuleClassLoader> {
    return ModuleClassLoaderManager.Reference(createClassLoader(parent), {})
  }

  override fun clearCache(module: Module) {}

  companion object {
    /**
     * Kotlin/kotlinx packages whose user-supplied versions should be repackaged into an internal namespace, so they don't collide with the
     * layoutlib-side Kotlin runtime.
     *
     * See go/screenshot-classloading-design §6 for full design context.
     */
    val PACKAGES_TO_RENAME = listOf("kotlin.", "kotlinx.")

    const val INTERNAL_PACKAGE = "_layoutlib_._internal_."

    /**
     * Packages allowed to be loaded from the parent classloader (the outer URLClassLoader). Notably excludes "kotlin." and "kotlinx." —
     * those must come from the user's classpath via the renamed namespace.
     */
    val ALLOWED_PACKAGES_FROM_PARENT =
      listOf(
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.sun.",
        "org.w3c.",
        "org.xml.",
        "org.apache.",
        "org.xmlpull.",
        "org.json.",
        "android.",
        "dalvik.",
        "com.android.",
        "androidx.compose.animation.tooling.",
        "junit.",
      )

    val STRING_REPLACEMENTS =
      mapOf(
        INTERNAL_PACKAGE + "kotlin.reflect.jvm.internal.impl.load.java.JvmAnnotationNames" to
          mapOf(
            "kotlin.Metadata" to "${INTERNAL_PACKAGE}kotlin.Metadata",
            "kotlin.annotations.jvm.ReadOnly" to "${INTERNAL_PACKAGE}kotlin.annotations.jvm.ReadOnly",
            "kotlin.annotations.jvm.Mutable" to "${INTERNAL_PACKAGE}kotlin.annotations.jvm.Mutable",
            "kotlin.jvm.internal" to "${INTERNAL_PACKAGE}kotlin.jvm.internal",
            "kotlin.jvm.internal.EnhancedNullability" to "${INTERNAL_PACKAGE}kotlin.jvm.internal.EnhancedNullability",
            "kotlin.jvm.internal.SerializedIr" to "${INTERNAL_PACKAGE}kotlin.jvm.internal.SerializedIr",
          )
      )

    /**
     * A key used for identifying classes loaded by the [DefaultModuleClassLoader] in [ClassesTracker], both for writing with
     * [ClassesTracker.trackClass] and for retrieving with [ClassesTracker.getClasses].
     */
    const val CLASSES_TRACKER_KEY = ""
  }
}
