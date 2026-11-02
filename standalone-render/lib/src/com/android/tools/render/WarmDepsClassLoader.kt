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

import com.android.tools.rendering.classloading.PseudoClassLocatorForLoader
import com.android.tools.rendering.classloading.loaders.AsmTransformingLoader
import com.android.tools.rendering.classloading.loaders.ClassBinaryCacheLoader
import com.android.tools.rendering.classloading.loaders.ClassLoaderLoader
import com.android.tools.rendering.classloading.loaders.DelegatingClassLoader
import com.android.tools.rendering.classloading.loaders.ListeningLoader
import com.android.tools.rendering.classloading.loaders.NameRemapperLoader
import com.android.tools.res.ids.ResourceIdManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.lang.UrlClassLoader
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import kotlin.io.path.Path
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter

private val projectJarScanCache = ConcurrentHashMap<String, CachedProjectJarScan>()

private data class CachedProjectJarScan(
  val hasRClasses: Boolean,
  val nonRClassNames: Set<String>,
)

internal data class ProjectClassPathScan(
  val rClassEntries: List<String>,
  val rSignature: List<String>,
  val nonRClassNames: Set<String>,
)

/**
 * Scans [projectClassPath] to identify:
 * 1. Entries containing `R` / `R$*` classes (`rClassEntries` + `rSignature`) so [WarmDepsClassLoader] can resolve linked `R` classes
 *    ahead of unlinked library `R.jar`s and invalidate cleanly if any `R` class changes.
 * 2. Non-`R` class names (`nonRClassNames`) so [WarmDepsClassLoader] never claims user project classes (or user classes shadowing a
 *    library class), delegating them to the per-RPC child [DefaultModuleClassLoader].
 *
 * `.jar` scans (such as AGP's `processDebugResources/R.jar`) are cached by `(path, lastModified, length)` so Fast Preview `.kt` edits do
 * not re-open `R.jar`.
 */
internal fun scanProjectClassPath(projectClassPath: List<String>): ProjectClassPathScan {
  if (projectClassPath.isEmpty()) {
    return ProjectClassPathScan(emptyList(), emptyList(), emptySet())
  }
  val rEntries = ArrayList<String>(2)
  val rSignature = ArrayList<String>(4)
  val nonRClasses = HashSet<String>()

  for (entry in projectClassPath) {
    val file = File(entry)
    if (!file.exists()) continue
    if (file.isFile && entry.endsWith(".jar", ignoreCase = true)) {
      val stampKey = "$entry@${file.lastModified()}:${file.length()}"
      if (projectJarScanCache.size > 64 && !projectJarScanCache.containsKey(stampKey)) {
        projectJarScanCache.clear()
      }
      val jarScan =
        projectJarScanCache.computeIfAbsent(stampKey) {
          var hasR = false
          val jarNonR = HashSet<String>()
          try {
            ZipFile(file).use { zf ->
              val entries = zf.entries()
              while (entries.hasMoreElements()) {
                val ze = entries.nextElement()
                val name = ze.name
                if (!ze.isDirectory && name.endsWith(".class")) {
                  val fqcn = name.removeSuffix(".class").replace('/', '.')
                  if (isResourceClass(fqcn)) {
                    hasR = true
                  } else {
                    jarNonR.add(fqcn)
                  }
                }
              }
            }
          } catch (_: Exception) {
            // Ignore unreadable or concurrently modified JARs and fall back to an empty scan.
          }
          CachedProjectJarScan(hasRClasses = hasR, nonRClassNames = jarNonR)
        }
      if (jarScan.hasRClasses) {
        rEntries.add(entry)
        rSignature.add("jar:$stampKey")
      }
      nonRClasses.addAll(jarScan.nonRClassNames)
    } else if (file.isDirectory) {
      val rootPath = file.toPath()
      var dirHasR = false
      val dirRRecords = ArrayList<String>()
      try {
        Files.walkFileTree(
          rootPath,
          object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(child: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
              val fileName = child.fileName?.toString()
              if (attrs.isRegularFile && fileName != null && fileName.endsWith(".class")) {
                val rel = rootPath.relativize(child).toString().replace('\\', '/')
                val fqcn = rel.removeSuffix(".class").replace('/', '.')
                if (isResourceClass(fqcn)) {
                  dirHasR = true
                  dirRRecords.add("dir:$entry:$rel@${attrs.lastModifiedTime().toMillis()}:${attrs.size()}")
                } else {
                  nonRClasses.add(fqcn)
                }
              }
              return FileVisitResult.CONTINUE
            }
          },
        )
      } catch (_: IOException) {
        // Ignore unreadable or concurrently deleted directory entries during scan.
      }
      if (dirHasR) {
        rEntries.add(entry)
        dirRRecords.sort()
        rSignature.addAll(dirRRecords)
      }
    }
  }
  return ProjectClassPathScan(
    rClassEntries = rEntries,
    rSignature = rSignature,
    nonRClassNames = nonRClasses,
  )
}

internal fun clearProjectJarScanCacheForTest() {
  projectJarScanCache.clear()
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
internal fun isResourceClass(fqcn: String): Boolean {
  val simpleName = fqcn.substringAfterLast('.')
  return simpleName == "R" || simpleName.startsWith("R$")
}

/**
 * Returns `true` if [fqcn] belongs to a preview tooling adapter package that resolves user project classes via 1-arg `Class.forName(name)`
 * or its own defining [ClassLoader] (such as `androidx.compose.ui.tooling.ComposableInvoker`, `ComposeViewAdapter`, or
 * `TileServiceViewAdapter`).
 *
 * These lightweight adapter classes (~10–20 loaded per render) must be defined in the per-RPC child [DefaultModuleClassLoader] rather than
 * the persistent parent [WarmDepsClassLoader] so they always see newly recompiled user `@Preview` and `PreviewParameterProvider` classes,
 * while all heavy library packages (`androidx.compose.runtime.*`, `androidx.compose.ui.*`, `androidx.compose.foundation.*`,
 * `androidx.compose.material3.*`, `androidx.compose.ui.tooling.data.*`, `androidx.compose.ui.tooling.preview.*`, and repackaged `kotlin.*`
 * / `kotlinx.*`) remain warm, verified, `<clinit>`-initialized, and JIT-compiled in [WarmDepsClassLoader].
 */
internal fun isPreviewToolingAdapterClass(fqcn: String): Boolean {
  val name = ON_DISK_LOOKUP(fqcn)
  if (isResourceClass(name)) return false
  if (name.startsWith("androidx.compose.ui.tooling.")) {
    return !name.startsWith("androidx.compose.ui.tooling.data.") &&
      !name.startsWith("androidx.compose.ui.tooling.preview.")
  }
  if (name.startsWith("androidx.wear.tiles.tooling.")) {
    return !name.startsWith("androidx.wear.tiles.tooling.preview.")
  }
  return name.startsWith("androidx.glance.appwidget.preview.") ||
    name.startsWith("androidx.glance.preview.")
}

internal data class WarmDepsKey(
  val wrappedParentIdentity: Int,
  val stableDepsClassPath: List<String>,
  val libraryIdentities: Map<String, String>,
  val projectRSignature: List<String>,
)

/** Builds the [ClassBinaryCacheLoader] pipeline for external library classes (`classPath - projectClassPath`). */
internal fun buildDepsBytecodeLoader(
  parent: ClassLoader?,
  state: LibraryLoaderState,
): DelegatingClassLoader.Loader {
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
  return ClassBinaryCacheLoader(
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
            asmFlags = ClassWriter.COMPUTE_MAXS,
            onRewrite = { _, _, _ -> LibraryClassBinaryCache.recordAsmTransform() },
          ),
        onAfterLoad = { fqcn, bytes ->
          fqcnToLibraryIdentity.remove(ON_DISK_LOOKUP(fqcn))?.let { libraryIdentity ->
            state.binaryCache.put(fqcn, depsTransformationId, libraryIdentity, bytes)
          }
        },
      ),
    transformationId = depsTransformationId,
    binaryCache = state.binaryCache,
  )
}

/**
 * Persistent parent [ClassLoader] retained by [StandaloneModuleClassLoaderManager] across `refreshClassPath` calls when external library
 * JARs and linked `R` classes are unchanged.
 *
 * Keeping external library `Class<?>` objects (Compose Runtime, UI, Foundation, Animation, Material3, Kotlin stdlib, Coroutines, and
 * initialized `R` classes) alive across Fast Preview `.kt` edits eliminates ~450–500ms of per-render `defineClass` + bytecode verification
 * + `<clinit>` static initialization + lost HotSpot C2 JIT code, while delegating preview tooling adapters ([isPreviewToolingAdapterClass])
 * and user project classes (`projectNonRClasses`) to the disposable per-RPC child [DefaultModuleClassLoader].
 */
internal class WarmDepsClassLoader(
  wrappedParent: ClassLoader,
  val key: WarmDepsKey,
  val state: LibraryLoaderState,
  projectRClassEntries: List<String>,
  @Volatile var projectNonRClasses: Set<String>,
) : ClassLoader(wrappedParent), AutoCloseable {

  /**
   * Generation stamp guarded by `StandaloneModuleClassLoaderManager.sharedClassLoadersLock` to skip redundant directory scans within a
   * batch.
   */
  var verifiedAtGeneration: Long = 0L

  companion object {
    private const val ANDROID_COMPOSE_VIEW_FQN = "androidx.compose.ui.platform.AndroidComposeView"
    private const val FONT_REQUEST_WORKER_FQN = "androidx.core.provider.FontRequestWorker"
    private const val TYPEFACE_COMPAT_FQN = "androidx.core.graphics.TypefaceCompat"
    private const val SYSTEM_PROPERTIES_FQN = "android.os.SystemProperties"

    init {
      registerAsParallelCapable()
    }
  }

  private val closed = AtomicBoolean(false)
  internal val isClosed: Boolean
    get() = closed.get()

  val depsBytecodeLoader: DelegatingClassLoader.Loader = buildDepsBytecodeLoader(wrappedParent, state)
  private val depsClassLoader: ClassLoader = state.externalLibraryEntry.classLoader
  private val projectRUrlClassLoader: ClassLoader? =
    if (projectRClassEntries.isNotEmpty()) {
      UrlClassLoader.build().useCache(false).allowLock(!SystemInfo.isWindows).parent(null).files(projectRClassEntries.map { Path(it) }).get()
    } else {
      null
    }
  private val projectRLoader: DelegatingClassLoader.Loader? =
    projectRUrlClassLoader?.let { rCl ->
      val rRawLoader = NameRemapperLoader(ClassLoaderLoader(rCl), ON_DISK_LOOKUP)
      val parentLoader = ClassLoaderLoader(wrappedParent)
      AsmTransformingLoader(
        transform = DEPS_CLASS_TRANSFORMS,
        delegate = rRawLoader,
        pseudoClassLocator =
          PseudoClassLocatorForLoader(
            loaders = sequenceOf(rRawLoader, depsBytecodeLoader, parentLoader),
            fallbackClassloader = wrappedParent,
          ),
        asmFlags = ClassWriter.COMPUTE_MAXS,
      )
    }

  private val renamedClasses = ConcurrentHashMap<String, String>()
  private val loadedOnDiskClasses: MutableSet<String> = ConcurrentHashMap.newKeySet()

  @Volatile private var rInitPackages: List<String>? = null
  @Volatile private var rInitManagerRef: WeakReference<ResourceIdManager>? = null

  internal fun hasLoadedOnDiskClass(fqcn: String): Boolean = loadedOnDiskClasses.contains(ON_DISK_LOOKUP(fqcn))

  internal fun findLoadedClassOrNull(fqcn: String): Class<*>? = findLoadedClass(renamedClasses[fqcn] ?: fqcn)

  internal fun isLibraryRInitializedFor(packages: List<String>, resourceIdManager: ResourceIdManager): Boolean =
    rInitManagerRef?.get() === resourceIdManager && rInitPackages == packages

  internal fun markLibraryRInitialized(packages: List<String>, resourceIdManager: ResourceIdManager) {
    rInitPackages = packages.toList()
    rInitManagerRef = WeakReference(resourceIdManager)
  }

  override fun loadClass(name: String, resolve: Boolean): Class<*> {
    return super.loadClass(renamedClasses[name] ?: name, resolve)
  }

  override fun findClass(name: String): Class<*> {
    val onDiskName = ON_DISK_LOOKUP(name)
    if (isPreviewToolingAdapterClass(onDiskName) || (!isResourceClass(onDiskName) && projectNonRClasses.contains(onDiskName))) {
      throw ClassNotFoundException(name)
    }
    val bytes =
      (if (isResourceClass(onDiskName)) {
        projectRLoader?.loadClass(name) ?: depsBytecodeLoader.loadClass(name)
      } else {
        depsBytecodeLoader.loadClass(name)
      })
        ?: throw ClassNotFoundException(name)

    val redefinedName = ClassReader(bytes).className.replace('/', '.')
    synchronized(getClassLoadingLock(redefinedName)) {
      if (name != redefinedName) {
        renamedClasses[name] = redefinedName
      }
      findLoadedClass(redefinedName)?.let { return it }
      val defined = defineClass(redefinedName, bytes, 0, bytes.size)
      loadedOnDiskClasses.add(onDiskName)
      return defined
    }
  }

  private fun isResourceClassResource(resourceName: String): Boolean =
    resourceName.endsWith(".class") &&
      isResourceClass(resourceName.removeSuffix(".class").replace('/', '.'))

  override fun getResource(name: String): URL? {
    val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
    val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
    val rUrl = if (isResourceClassResource(mappedName)) projectRUrlClassLoader?.getResource(mappedName) else null
    val url = rUrl ?: depsClassLoader.getResource(mappedName)
    if (url != null) {
      return if (name.startsWith("META-INF/services/")) LibraryClassBinaryCache.wrapServiceUrl(url) else url
    }
    return super.getResource(name)
  }

  override fun getResources(name: String): Enumeration<URL> {
    val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
    val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
    val rRes =
      if (isResourceClassResource(mappedName)) {
        projectRUrlClassLoader?.getResources(mappedName)?.toList().orEmpty()
      } else {
        emptyList()
      }
    val depsRes = depsClassLoader.getResources(mappedName).toList()
    var localRes = (rRes + depsRes).distinctBy { it.toString() }
    if (name.startsWith("META-INF/services/")) {
      localRes = localRes.map { LibraryClassBinaryCache.wrapServiceUrl(it) }
    }
    val parentRes = super.getResources(name).toList()
    return Collections.enumeration((localRes + parentRes).distinctBy { it.toString() })
  }

  private inline fun runCleanupStep(block: () -> Unit) {
    try {
      block()
    } catch (_: Exception) {
      // Best-effort cleanup of optional framework/AndroidX classes when closing WarmDepsClassLoader.
    } catch (_: LinkageError) {
      // Best-effort cleanup if optional classes cannot be linked.
    }
  }

  /**
   * Unregisters static callbacks and shuts down background threads created by AndroidX classes loaded inside this [WarmDepsClassLoader]
   * (`AndroidComposeView.systemPropertiesChangedRunnable` in `android.os.SystemProperties.sChangeCallbacks`, `FontRequestWorker`, and
   * `TypefaceCompat`), since `RenderSessionCleaner` skips classes whose defining loader is not the child [DefaultModuleClassLoader].
   */
  private fun unregisterWarmDepsFrameworkCallbacks() {
    if (hasLoadedOnDiskClass(ANDROID_COMPOSE_VIEW_FQN)) {
      findLoadedClassOrNull(ANDROID_COMPOSE_VIEW_FQN)?.let { acvClass ->
        runCleanupStep {
          val composeViews = acvClass.getDeclaredField("composeViews").apply { isAccessible = true }.get(null)
          if (composeViews != null) {
            val clearMethod =
              composeViews.javaClass.methods.firstOrNull { it.name == "clear" && it.parameterCount == 0 }?.apply { isAccessible = true }
            if (clearMethod != null) {
              synchronized(composeViews) { clearMethod.invoke(composeViews) }
            }
          }
        }
        runCleanupStep {
          val runnableField = acvClass.getDeclaredField("systemPropertiesChangedRunnable").apply { isAccessible = true }
          val runnable = runnableField.get(null) as? Runnable
          runnableField.set(null, null)
          if (runnable != null) {
            val sysPropsClass =
              (try {
                acvClass.getDeclaredField("systemPropertiesClass").apply { isAccessible = true }.get(null) as? Class<*>
              } catch (_: Exception) {
                null
              } catch (_: LinkageError) {
                null
              }) ?: Class.forName(SYSTEM_PROPERTIES_FQN, false, parent)
            val callbacks = sysPropsClass.getDeclaredField("sChangeCallbacks").apply { isAccessible = true }.get(null) as? MutableCollection<*>
            if (callbacks != null) {
              synchronized(callbacks) { callbacks.removeIf { it === runnable } }
            }
          }
        }
      }
    }
    if (hasLoadedOnDiskClass(FONT_REQUEST_WORKER_FQN)) {
      findLoadedClassOrNull(FONT_REQUEST_WORKER_FQN)?.let { frwClass ->
        runCleanupStep {
          val pending = frwClass.getDeclaredField("PENDING_REPLIES").apply { isAccessible = true }.get(null)
          if (pending != null) {
            val lock =
              try {
                frwClass.getDeclaredField("LOCK").apply { isAccessible = true }.get(null)
              } catch (_: Exception) {
                null
              } catch (_: LinkageError) {
                null
              }
            val clearMethod = pending.javaClass.getMethod("clear")
            if (lock != null) synchronized(lock) { clearMethod.invoke(pending) } else clearMethod.invoke(pending)
          }
        }
        runCleanupStep { frwClass.getDeclaredMethod("resetTypefaceCache").apply { isAccessible = true }.invoke(null) }
        runCleanupStep {
          (frwClass.getDeclaredField("DEFAULT_EXECUTOR_SERVICE").apply { isAccessible = true }.get(null) as? ExecutorService)?.shutdownNow()
        }
      }
    }
    if (hasLoadedOnDiskClass(TYPEFACE_COMPAT_FQN)) {
      findLoadedClassOrNull(TYPEFACE_COMPAT_FQN)?.let { tcClass ->
        runCleanupStep { tcClass.getDeclaredMethod("clearCache").apply { isAccessible = true }.invoke(null) }
      }
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    unregisterWarmDepsFrameworkCallbacks()
    rInitPackages = null
    rInitManagerRef = null
    (projectRUrlClassLoader as? UrlClassLoader)?.classPath?.reset()
    (projectRUrlClassLoader as? AutoCloseable)?.close()
  }
}
