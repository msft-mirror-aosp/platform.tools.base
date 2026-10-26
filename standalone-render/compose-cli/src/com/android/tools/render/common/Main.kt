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

package com.android.tools.render.common

import com.android.tools.render.LibraryClassBinaryCache
import com.android.tools.render.RenderEnvironmentBootstrapper
import com.android.tools.render.Renderer
import com.android.tools.render.framework.IJFramework
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import java.io.Closeable
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.system.exitProcess

/**
 * Snapshot of the environment inputs that require a full [Renderer] re-bootstrap when changed.
 *
 * Bootstrapping a [Renderer] via [RenderEnvironmentBootstrapper] is expensive because it initializes a headless IntelliJ
 * [com.intellij.openapi.project.Project], parses `res.apk` into an `ApkResourceRepository`, configures Layoutlib's `Bridge` /
 * `StudioAndroidTargetData`, and sets up `RenderService`.
 *
 * Notice that `projectClassPath` (the user's compiled project `.class` files/directories) is intentionally **excluded** from this key:
 * - When only `projectClassPath` changes between renders (the common case during iterative code edits in the persistent preview daemon),
 *   [WarmEnvironmentKey] remains unchanged (`warmKey == key`). This allows [StandalonePreviewRenderer] to reuse the warm [Renderer] and
 *   reload user classes into a fresh project classloader via [Renderer.refreshClassPath] on each warm batch while preserving external
 *   library classloaders in [LibraryClassBinaryCache].
 * - When resources (`resourceApkPath` / `resourceDirs`), `R.jar` files (`rClassJars`), external library dependencies
 *   (`stableDepsClassPath`), or their modification timestamps change, [WarmEnvironmentKey] changes (`warmKey != key`), triggering disposal
 *   of the stale [Renderer] and a clean re-bootstrap.
 */
private data class WarmEnvironmentKey(
  val fontsPath: String?,
  val resourceApkPath: String,
  val resourceApkLastModified: Long,
  val namespace: String,
  val layoutlibPath: String,
  val resourceDirs: List<String>,
  val resourceDirsTimestamps: List<Long>,
  val rClassJars: List<String>,
  val rClassJarTimestamps: List<Long>,
  val stableDepsClassPath: List<String>,
  val stableDepsTimestamps: List<Long>,
)

internal data class RendererBootstrapRequest(
  val fontsPath: String?,
  val resourceApkPath: String,
  val namespace: String,
  val classPath: List<String>,
  val projectClassPath: List<String>,
  val layoutlibPath: String,
  val resourceDirs: List<String>,
  val rClassJars: List<String>,
)

internal interface ManagedRenderer : Closeable {
  val isDisposed: Boolean

  fun refreshClassPath(newClassPath: List<String>, newProjectClassPath: List<String>)

  fun render(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    includeComponentTree: Boolean,
    renderImage: Boolean,
  ): List<PreviewScreenshotResult>
}

private class RealManagedRenderer(private val delegate: Renderer) : ManagedRenderer {
  override val isDisposed: Boolean
    get() = delegate.project.isDisposed || delegate.module.isDisposed

  override fun refreshClassPath(newClassPath: List<String>, newProjectClassPath: List<String>) {
    delegate.refreshClassPath(newClassPath, newProjectClassPath)
  }

  override fun render(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    includeComponentTree: Boolean,
    renderImage: Boolean,
  ): List<PreviewScreenshotResult> = delegate.render(screenshot, outputFolderPath, includeComponentTree, renderImage)

  override fun close() {
    delegate.close()
  }
}

private val INVALID_PATH_CHARS_REGEX = """[\u0000-\u001F\\/:*?"<>|]+""".toRegex()

private val RESULT_COMPARATOR: Comparator<PreviewScreenshotResult> =
  compareBy(
    { it.imagePath.ifEmpty { null } ?: it.componentTreePath.orEmpty() },
    { it.methodFQN },
    { it.previewId },
    { it.displayName.orEmpty() },
  )

private fun defaultErrorImagePath(screenshot: PreviewScreenshot, renderImage: Boolean): String {
  if (!renderImage) return ""
  val packagePath =
    screenshot.methodFQN
      .substringBeforeLast(".")
      .split('.')
      .map { it.replace(INVALID_PATH_CHARS_REGEX, "_") }
      .filter { it.isNotEmpty() && it != "." && it != ".." }
      .joinToString(File.separator)
  val sanitizedId = screenshot.previewId.substringAfterLast(".").replace(INVALID_PATH_CHARS_REGEX, "_")
  val baseResultId = if (sanitizedId.isEmpty() || sanitizedId == "." || sanitizedId == "..") "preview" else sanitizedId
  return if (packagePath.isEmpty()) "${baseResultId}_0.png" else packagePath + File.separator + "${baseResultId}_0.png"
}

/**
 * Renders [PreviewScreenshot]s (e.g. Compose previews or Wear Tiles previews) outside of Android Studio.
 *
 * Supports two execution lifecycles controlled by `reuseEnvironment` in [render]:
 * 1. **Warm Daemon Mode (`reuseEnvironment = true`, default)**: Reuses a process-wide warm [Renderer] across invocations (such as repeated
 *    gRPC requests in `PreviewRendererServiceImpl`) as long as [WarmEnvironmentKey] matches, refreshing the project `.class` loader via
 *    [Renderer.refreshClassPath] before each warm batch while preserving the initialized Layoutlib environment and
 *    [LibraryClassBinaryCache].
 * 2. **One-Shot CLI Mode (`reuseEnvironment = false`)**: Bootstraps a dedicated [Renderer] for a single batch and closes it immediately
 *    after rendering (used by [main]).
 */
class StandalonePreviewRenderer
/** Allows tests to inject a fake [ManagedRenderer] through [rendererFactory] instead of bootstrapping a real [Renderer]. */
internal constructor(
  private val fontsPath: String?,
  private val resourceApkPath: String,
  private val namespace: String,
  private val classPath: List<String>,
  private val projectClassPath: List<String>,
  private val layoutlibPath: String,
  private val resourceDirs: List<String>,
  private val rClassJars: List<String>,
  private val rendererFactory: (RendererBootstrapRequest) -> ManagedRenderer,
) {

  constructor(
    fontsPath: String?,
    resourceApkPath: String,
    namespace: String,
    classPath: List<String>,
    projectClassPath: List<String> = emptyList(),
    layoutlibPath: String,
    resourceDirs: List<String> = emptyList(),
    rClassJars: List<String> = emptyList(),
  ) : this(
    fontsPath = fontsPath,
    resourceApkPath = resourceApkPath,
    namespace = namespace,
    classPath = classPath,
    projectClassPath = projectClassPath,
    layoutlibPath = layoutlibPath,
    resourceDirs = resourceDirs,
    rClassJars = rClassJars,
    rendererFactory = ::defaultRendererFactory,
  )

  @JvmOverloads
  constructor(
    fontsPath: String?,
    resourceApkPath: String,
    namespace: String,
    classPath: List<String>,
    layoutlibPath: String,
    resourceDirs: List<String> = emptyList(),
    rClassJars: List<String> = emptyList(),
  ) : this(
    fontsPath = fontsPath,
    resourceApkPath = resourceApkPath,
    namespace = namespace,
    classPath = classPath,
    projectClassPath = emptyList(),
    layoutlibPath = layoutlibPath,
    resourceDirs = resourceDirs,
    rClassJars = rClassJars,
    rendererFactory = ::defaultRendererFactory,
  )

  /**
   * Renders the requested [screenshots] into [outputFolder].
   *
   * This method does not throw: batch-level failures are returned in [PreviewRenderingResult.globalError]. Any [Error] evicts the warm
   * [Renderer] from the cache and closes it once no other caller is using it, since the error may have left it in an inconsistent state. A
   * [StackOverflowError] (for example, a user preview with unbounded recursion) is reported with [PreviewRenderingResult.isFatalJvmError]
   * set to `false`. Other [VirtualMachineError]s, such as [OutOfMemoryError], mean that the hosting JVM itself is no longer healthy, so
   * they are reported in [PreviewRenderingResult.globalError] with [PreviewRenderingResult.isFatalJvmError] set to `true` (and
   * [PreviewRenderingResult.fatalJvmError] set to the error class name) so long-running daemon callers can retire after answering while CLI
   * callers still write the results JSON file.
   *
   * @param screenshots The list of preview configurations (method FQNs and parameters) to render.
   * @param outputFolder Destination directory for rendered PNG images and component tree JSON files.
   * @param includeComponentTree Whether to extract and serialize the `ComposeViewInfo` hierarchy.
   * @param renderImage Whether to produce PNG screenshots (can be `false` when only extracting the component tree).
   * @param reuseEnvironment When `true`, acquires and reuses the process-wide warm [Renderer] via [acquireWarmRenderer] instead of
   *   bootstrapping and tearing down the environment on every call.
   * @return the per-screenshot results, or a [PreviewRenderingResult.globalError] when the batch could not be rendered.
   */
  @JvmOverloads
  fun render(
    screenshots: List<PreviewScreenshot>,
    outputFolder: String,
    includeComponentTree: Boolean = false,
    renderImage: Boolean = true,
    reuseEnvironment: Boolean = true,
  ): PreviewRenderingResult {
    return try {
      // Some callers supply R.jar files inside the general `classPath` rather than via `rClassJars`.
      // Extract them explicitly so ResourceIdManager can initialize library R-class resource IDs.
      val resolvedRClassJars = rClassJars.ifEmpty {
        classPath.filter { it.endsWith("/R.jar") || it.endsWith("\\R.jar") || it.endsWith("-R.jar") }
      }
      val bootstrapRequest =
        RendererBootstrapRequest(
          fontsPath = fontsPath,
          resourceApkPath = resourceApkPath,
          namespace = namespace,
          classPath = classPath,
          projectClassPath = projectClassPath,
          layoutlibPath = layoutlibPath,
          resourceDirs = resourceDirs,
          rClassJars = resolvedRClassJars,
        )
      if (reuseEnvironment) {
        // Combine `classPath` and `resolvedRClassJars` (preserving order while removing duplicates) to match
        // StandaloneModuleClassLoaderManager's full classpath.
        val fullClassPath = (classPath + resolvedRClassJars).distinct()
        repeat(2) {
          val acquired =
            try {
              acquireWarmRenderer(bootstrapRequest, fullClassPath, rendererFactory)
            } catch (e: Error) {
              // Errors thrown while rendering are already handled by `renderBatch`, which evicts the affected handle. Errors thrown
              // before a handle is acquired (computing the environment key or bootstrapping) also close the warm renderer.
              try {
                closeWarmRenderer()
              } catch (closeFailure: Throwable) {
                e.addSuppressed(closeFailure)
              }
              throw e
            }
          val results =
            try {
              acquired.handle.renderBatch(fullClassPath, projectClassPath, acquired.freshlyBootstrapped) { managedRenderer ->
                renderAll(managedRenderer, screenshots, outputFolder, includeComponentTree, renderImage)
              }
            } finally {
              acquired.handle.release()
            }
          if (results != null) {
            return PreviewRenderingResult(globalError = null, results)
          }
        }
        throw IllegalStateException("Warm renderer was repeatedly disposed or invalidated before rendering could start")
      }

      // Cold / one-shot path: bootstrap a fresh Renderer and close it immediately via `.use {}`.
      rendererFactory(bootstrapRequest).use { managedRenderer ->
        val results = renderAll(managedRenderer, screenshots, outputFolder, includeComponentTree, renderImage)
        PreviewRenderingResult(globalError = null, results)
      }
    } catch (e: StackOverflowError) {
      // The warm renderer has already been evicted (see the KDoc above), so the next request bootstraps a fresh one.
      PreviewRenderingResult(e.stackTraceToString(), emptyList())
    } catch (e: VirtualMachineError) {
      val errorName = e.javaClass.simpleName.ifEmpty { e.javaClass.name }
      val errorText = runCatching { e.stackTraceToString() }.getOrElse { e.javaClass.name }
      PreviewRenderingResult(errorText, emptyList(), fatalJvmError = errorName)
    } catch (t: Throwable) {
      PreviewRenderingResult(t.stackTraceToString(), emptyList())
    }
  }

  private fun renderAll(
    renderer: ManagedRenderer,
    screenshots: List<PreviewScreenshot>,
    outputFolder: String,
    includeComponentTree: Boolean,
    renderImage: Boolean,
  ): List<PreviewScreenshotResult> =
    screenshots
      .flatMap { screenshot ->
        try {
          renderer.render(screenshot, outputFolder, includeComponentTree, renderImage)
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          throw e
        } catch (e: ProcessCanceledException) {
          throw e
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          listOf(
            PreviewScreenshotResult(
              previewId = screenshot.previewId,
              methodFQN = screenshot.methodFQN,
              imagePath = defaultErrorImagePath(screenshot, renderImage),
              error = e.toScreenshotError(),
            )
          )
        }
      }
      .sortedWith(RESULT_COMPARATOR)

  companion object {
    private fun defaultRendererFactory(request: RendererBootstrapRequest): ManagedRenderer =
      RealManagedRenderer(
        RenderEnvironmentBootstrapper(
            fontsPath = request.fontsPath,
            resourceApkPath = request.resourceApkPath,
            namespace = request.namespace,
            classPath = request.classPath,
            projectClassPath = request.projectClassPath,
            layoutlibPath = request.layoutlibPath,
            resourceDirs = request.resourceDirs,
            rClassJars = request.rClassJars,
          )
          .bootstrap()
      )

    private data class AcquiredWarmRenderer(
      val handle: WarmRendererHandle,
      val freshlyBootstrapped: Boolean,
    )

    /**
     * Ref-counted handle holding an active [ManagedRenderer] and its corresponding [WarmEnvironmentKey].
     *
     * Reference counting guarantees that if [closeWarmRenderer] or an environment eviction occurs while a render is actively executing on
     * another thread, the underlying [ManagedRenderer] is not closed until that render completes its work and releases its reference.
     *
     * Rendering and `projectClassPath` refreshes on a given handle are serialized via [renderLock], matching [Renderer]'s single-caller
     * contract. Every warm batch after the initial bootstrap refreshes `projectClassPath` so modified user `.class` files are reloaded into
     * a fresh project classloader without leaking user static state across requests, while external library classloaders remain cached in
     * [LibraryClassBinaryCache].
     */
    private class WarmRendererHandle(
      val key: WarmEnvironmentKey,
      val renderer: ManagedRenderer,
      private val bootstrapProjectClassPath: List<String>,
    ) {
      private val refCount = AtomicInteger(1)
      private val renderLock = ReentrantLock()
      @Volatile private var isEvicted = false
      @Volatile private var isCorrupted = false
      private var skipInitialClassPathRefresh = true

      val isUsable: Boolean
        get() = !isEvicted && !isCorrupted && !renderer.isDisposed

      /** Number of callers waiting to acquire [renderLock]. */
      val queuedRenderCallers: Int
        get() = renderLock.queueLength

      fun retain(): Boolean {
        while (true) {
          val current = refCount.get()
          if (current <= 0 || !isUsable) return false
          if (refCount.compareAndSet(current, current + 1)) {
            if (!isUsable) {
              release()
              return false
            }
            return true
          }
        }
      }

      /**
       * Executes [block] under [renderLock], refreshing `projectClassPath` before each warm batch (except the first batch run by the caller
       * that freshly bootstrapped this handle with the same `projectClassPath`).
       *
       * @return the rendered results, or `null` if this handle was disposed or corrupted while waiting on [renderLock] so the caller can
       *   release this handle and acquire a fresh one.
       */
      fun renderBatch(
        fullClassPath: List<String>,
        projectClassPath: List<String>,
        freshlyBootstrapped: Boolean,
        block: (ManagedRenderer) -> List<PreviewScreenshotResult>,
      ): List<PreviewScreenshotResult>? {
        renderLock.lock()
        try {
          // An evicted (but not corrupted) handle still serves callers that retained it before eviction; it closes once the last
          // caller releases it.
          if (isCorrupted || renderer.isDisposed) {
            evictWarmHandleIfMatch(this)
            return null
          }
          val skipRefresh = freshlyBootstrapped && skipInitialClassPathRefresh && projectClassPath == bootstrapProjectClassPath
          skipInitialClassPathRefresh = false
          if (!skipRefresh) {
            try {
              renderer.refreshClassPath(fullClassPath, projectClassPath)
            } catch (t: Throwable) {
              // A failed refresh leaves the classloader in an unknown state for any Throwable, not only Errors, so mark the handle as
              // corrupted here. The outer catch below repeats the eviction, which is then a no-op.
              isCorrupted = true
              evictWarmHandleIfMatch(this)
              throw t
            }
          }
          return block(renderer)
        } catch (t: Throwable) {
          if (t is Error) {
            isCorrupted = true
            evictWarmHandleIfMatch(this)
          }
          throw t
        } finally {
          renderLock.unlock()
        }
      }

      fun markEvicted() {
        isEvicted = true
      }

      fun release() {
        if (refCount.decrementAndGet() == 0) {
          renderer.close()
        }
      }
    }

    /**
     * Lightweight lock guarding strictly O(1) in-memory reads/writes of [warmKey], [warmHandle], and [closeGeneration].
     *
     * Never held during filesystem walks, [ManagedRenderer.refreshClassPath], [RenderEnvironmentBootstrapper.bootstrap], or
     * [ManagedRenderer.close] disposal I/O.
     */
    private val warmStateLock = Any()

    /** Serializes slow-path cache misses so concurrent callers for the same environment bootstrap once instead of duplicating Projects. */
    private val bootstrapLock = ReentrantLock()

    private var warmKey: WarmEnvironmentKey? = null
    private var warmHandle: WarmRendererHandle? = null
    private var closeGeneration: Long = 0L

    /** Number of callers waiting for [bootstrapLock]. Lets tests wait deterministically for a caller to queue up. */
    internal val queuedBootstrapCallers: Int
      get() = bootstrapLock.queueLength

    /** Number of callers waiting to render on the cached warm handle. Lets tests wait deterministically for a caller to queue up. */
    internal val queuedWarmRenderCallers: Int
      get() = synchronized(warmStateLock) { warmHandle }?.queuedRenderCallers ?: 0

    private fun computeEnvironmentKey(request: RendererBootstrapRequest, fullClassPath: List<String>): WarmEnvironmentKey {
      val stableDeps = LibraryClassBinaryCache.computeStableDepsClassPath(fullClassPath, request.projectClassPath)
      return WarmEnvironmentKey(
        fontsPath = request.fontsPath,
        resourceApkPath = request.resourceApkPath,
        resourceApkLastModified = if (request.resourceApkPath.isNotEmpty()) File(request.resourceApkPath).lastModified() else 0L,
        namespace = request.namespace,
        layoutlibPath = request.layoutlibPath,
        resourceDirs = request.resourceDirs,
        resourceDirsTimestamps = request.resourceDirs.map { LibraryClassBinaryCache.computePathTimestamp(it) },
        rClassJars = request.rClassJars,
        rClassJarTimestamps = request.rClassJars.map { File(it).lastModified() },
        stableDepsClassPath = stableDeps,
        stableDepsTimestamps = stableDeps.map { LibraryClassBinaryCache.computePathTimestamp(it) },
      )
    }

    /**
     * Returns a retained [WarmRendererHandle] matching [request], or bootstraps a new one if none exists or if environment inputs changed.
     * The caller MUST call [WarmRendererHandle.release] on [AcquiredWarmRenderer.handle] when done.
     */
    private fun acquireWarmRenderer(
      request: RendererBootstrapRequest,
      fullClassPath: List<String>,
      rendererFactory: (RendererBootstrapRequest) -> ManagedRenderer,
    ): AcquiredWarmRenderer {
      // Compute environment timestamps outside all locks so filesystem walks never block `closeWarmRenderer()` or other callers.
      val fastKey = computeEnvironmentKey(request, fullClassPath)

      synchronized(warmStateLock) {
        val existing = warmHandle
        if (existing != null && warmKey == fastKey && existing.retain()) {
          return AcquiredWarmRenderer(existing, freshlyBootstrapped = false)
        }
      }

      bootstrapLock.lock()
      try {
        synchronized(warmStateLock) {
          val existing = warmHandle
          if (existing != null && warmKey == fastKey && existing.retain()) {
            return AcquiredWarmRenderer(existing, freshlyBootstrapped = false)
          }
        }

        // Re-read environment timestamps under bootstrapLock before evicting an existing handle so a thread preempted before
        // bootstrapLock cannot tag a fresh bootstrap with a stale pre-lock WarmEnvironmentKey.
        val key = computeEnvironmentKey(request, fullClassPath)
        val generationAtStart: Long
        var evictedHandle: WarmRendererHandle? = null
        synchronized(warmStateLock) {
          val existing = warmHandle
          if (existing != null && warmKey == key && existing.retain()) {
            return AcquiredWarmRenderer(existing, freshlyBootstrapped = false)
          }
          existing?.markEvicted()
          evictedHandle = existing
          warmHandle = null
          warmKey = null
          generationAtStart = closeGeneration
        }
        // Release the cache's reference outside warmStateLock so any resulting `renderer.close()` I/O does not hold warmStateLock.
        evictedHandle?.release()

        val freshRenderer = rendererFactory(request)
        val freshHandle =
          WarmRendererHandle(
            key = key,
            renderer = freshRenderer,
            bootstrapProjectClassPath = request.projectClassPath,
          )
        if (!freshHandle.retain()) {
          // `freshRenderer` reported `isDisposed == true` immediately after creation; release the initial refCount (1 -> 0) to close it.
          freshHandle.markEvicted()
          freshHandle.release()
          throw IllegalStateException("Bootstrapped Renderer was already disposed")
        }

        var dropCacheReference = false
        synchronized(warmStateLock) {
          if (closeGeneration == generationAtStart) {
            warmKey = key
            warmHandle = freshHandle
          } else {
            // `closeWarmRenderer()` was invoked while bootstrap was running; drop the cache reference so `freshHandle` closes
            // immediately when the current caller calls `freshHandle.release()`.
            freshHandle.markEvicted()
            dropCacheReference = true
          }
        }
        if (dropCacheReference) {
          freshHandle.release()
        }
        return AcquiredWarmRenderer(freshHandle, freshlyBootstrapped = true)
      } finally {
        bootstrapLock.unlock()
      }
    }

    private fun evictWarmHandleIfMatch(target: WarmRendererHandle) {
      var shouldRelease = false
      synchronized(warmStateLock) {
        if (warmHandle === target) {
          target.markEvicted()
          warmHandle = null
          warmKey = null
          shouldRelease = true
        }
      }
      if (shouldRelease) {
        target.release()
      }
    }

    /** Closes and clears the cached warm [Renderer] (if any) without disposing the global [IJFramework] application instance. */
    fun closeWarmRenderer() {
      val evicted: WarmRendererHandle?
      synchronized(warmStateLock) {
        closeGeneration++
        evicted = warmHandle
        evicted?.markEvicted()
        warmHandle = null
        warmKey = null
      }
      evicted?.release()
    }

    /**
     * Shuts down both the cached warm [Renderer] and the underlying headless [IJFramework].
     *
     * Must close the warm renderer first while [IJFramework] application services are still active.
     */
    fun disposeFramework() {
      closeWarmRenderer()
      Disposer.dispose(IJFramework)
    }
  }
}

fun main(args: Array<String>) {
  if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
    printUsage()
    return
  }
  var exitCode = 0
  try {
    renderPreview(File(args[0]))
  } catch (t: Throwable) {
    System.err.println("Error: ${t.message ?: t.javaClass.simpleName}")
    exitCode = 1
  } finally {
    StandalonePreviewRenderer.disposeFramework()
  }
  exitProcess(exitCode)
}

private fun printUsage() {
  println("Usage: compose-preview-renderer <path-to-rendering-settings-json>")
  println()
  println("Renders Compose previews as screenshots based on the provided JSON settings file.")
  println()
  println("Options:")
  println("  -h, --help  Show this help message")
  println()
  println("JSON Settings File Format:")
  println("  The JSON file must contain the following fields:")
  println("    layoutlibPath:    Path to layoutlib installation directory")
  println("    outputFolder:     Directory where screenshots will be saved")
  println("    metaDataFolder:   Directory where metadata is stored")
  println("    classPath:        List of classpath entries (project & dependencies)")
  println("    projectClassPath: List of project-only classpath entries")
  println("    namespace:        Application package name")
  println("    resourceApkPath:  Path to resource APK")
  println("    resultsFilePath:  Path to write the results JSON file")
  println("    screenshots:      List of screenshot configurations to render")
  println()
  println("  Each screenshot in the 'screenshots' list needs:")
  println("    methodFQN:        Fully qualified name of Composable function")
  println("    previewId:        Unique ID for the preview (used in filename)")
  println("    previewParams:    (Optional) Map of preview parameters")
  println()
  println("Example JSON:")
  println(
    """  {
    "layoutlibPath": "/path/to/layoutlib",
    "outputFolder": "/path/to/output",
    "metaDataFolder": "/path/to/metadata",
    "classPath": ["/path/to/classes", "/path/to/dependencies.jar"],
    "projectClassPath": ["/path/to/classes"],
    "namespace": "com.example.myapp",
    "resourceApkPath": "/path/to/app-debug.apk",
    "resultsFilePath": "/path/to/results.json",
    "screenshots": [
      {
        "methodFQN": "com.example.myapp.MainActivityKt.DefaultPreview",
        "previewId": "com.example.myapp.MainActivityKt.DefaultPreview_screenshot",
        "previewParams": { "showBackground": "true" }
      }
    ]
  }"""
  )
}

private fun renderPreview(previewRenderingJson: File) {
  val previewRendering = previewRenderingJson.reader().use { readPreviewRenderingJson(it) }
  val renderer =
    StandalonePreviewRenderer(
      fontsPath = previewRendering.fontsPath,
      resourceApkPath = previewRendering.resourceApkPath,
      namespace = previewRendering.namespace,
      classPath = previewRendering.classPath,
      projectClassPath = previewRendering.projectClassPath,
      layoutlibPath = previewRendering.layoutlibPath,
      resourceDirs = previewRendering.resourceDirs,
      rClassJars = previewRendering.rClassJars,
    )
  val previewRenderingResult = renderer.render(previewRendering.screenshots, previewRendering.outputFolder, reuseEnvironment = false)

  val resultsFile = File(previewRendering.resultsFilePath)
  resultsFile.parentFile?.mkdirs()
  resultsFile.writer().use { writePreviewRenderingResult(it, previewRenderingResult) }

  var hasErrors = false
  if (previewRenderingResult.globalError != null) {
    System.err.println("Global error: ${previewRenderingResult.globalError}")
    hasErrors = true
  }

  val outputFolder = File(previewRendering.outputFolder)
  previewRenderingResult.screenshotResults.forEach { result ->
    val error = result.error
    if (error != null) {
      System.err.println("Error rendering ${result.previewId}: ${error.message}")
      if (error.stackTrace.isNotEmpty()) {
        System.err.println(error.stackTrace)
      }
      error.problems.forEach { problem ->
        System.err.println("  Problem: ${problem.html}")
        if (problem.stackTrace != null) {
          System.err.println(problem.stackTrace)
        }
      }
      hasErrors = true
    } else {
      val absolutePath = File(outputFolder, result.imagePath).absolutePath
      println(absolutePath)
    }
  }

  if (hasErrors) {
    throw RuntimeException("Rendering failed with errors")
  }
}
