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
import com.intellij.openapi.util.Disposer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Snapshot of the environment inputs that require a full [Renderer] re-bootstrap when changed.
 *
 * Bootstrapping a [Renderer] via [RenderEnvironmentBootstrapper] is expensive because it initializes
 * a headless IntelliJ [com.intellij.openapi.project.Project], parses `res.apk` into an
 * `ApkResourceRepository`, configures Layoutlib's `Bridge` / `StudioAndroidTargetData`, and sets up
 * `RenderService`.
 *
 * Notice that `projectClassPath` (the user's compiled project `.class` files/directories) is
 * intentionally **excluded** from this key:
 * - When only `projectClassPath` changes between renders (the common case during iterative code
 *   edits in the persistent preview daemon), [WarmEnvironmentKey] remains unchanged (`warmKey == key`).
 *   This allows [StandalonePreviewRenderer] to reuse the warm [Renderer] and simply reload user
 *   classes via [Renderer.refreshClassPath].
 * - When resources (`resourceApkPath` / `resourceDirs`), `R.jar` files (`rClassJars`), external
 *   library dependencies (`stableDepsClassPath`), or their modification timestamps change,
 *   [WarmEnvironmentKey] changes (`warmKey != key`), triggering disposal of the stale [Renderer]
 *   and a clean re-bootstrap.
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

/**
 * Renders [PreviewScreenshot]s (e.g. Compose previews or Wear Tiles previews) outside of Android Studio.
 *
 * Supports two execution lifecycles controlled by `reuseEnvironment` in [render]:
 * 1. **Warm Daemon Mode (`reuseEnvironment = true`, default)**: Reuses a process-wide warm [Renderer]
 *    across invocations (such as repeated gRPC requests in `PreviewRendererServiceImpl`) as long as
 *    [WarmEnvironmentKey] matches, refreshing only the project `.class` loader on each call while
 *    preserving the initialized Layoutlib environment and [LibraryClassBinaryCache].
 * 2. **One-Shot CLI Mode (`reuseEnvironment = false`)**: Bootstraps a dedicated [Renderer] for a
 *    single batch and closes it immediately after rendering (used by [main]).
 */
class StandalonePreviewRenderer(
  private val fontsPath: String?,
  private val resourceApkPath: String,
  private val namespace: String,
  private val classPath: List<String>,
  private val projectClassPath: List<String> = emptyList(),
  private val layoutlibPath: String,
  private val resourceDirs: List<String> = emptyList(),
  private val rClassJars: List<String> = emptyList(),
) {

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
  )

  /**
   * Renders the requested [screenshots] into [outputFolder].
   *
   * @param screenshots The list of preview configurations (method FQNs and parameters) to render.
   * @param outputFolder Destination directory for rendered PNG images and component tree JSON files.
   * @param includeComponentTree Whether to extract and serialize the `ComposeViewInfo` hierarchy.
   * @param renderImage Whether to produce PNG screenshots (can be `false` when only extracting the
   *   component tree).
   * @param reuseEnvironment When `true`, acquires and reuses the process-wide warm [Renderer] via
   *   [acquireWarmRenderer] instead of bootstrapping and tearing down the environment on every call.
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
      if (reuseEnvironment) {
        // Warm path (used by the persistent preview daemon): reuse the cached Renderer across RPCs
        // and do NOT close it at the end of this render call.
        val handle =
          acquireWarmRenderer(
            fontsPath = fontsPath,
            resourceApkPath = resourceApkPath,
            namespace = namespace,
            classPath = classPath,
            projectClassPath = projectClassPath,
            layoutlibPath = layoutlibPath,
            resourceDirs = resourceDirs,
            rClassJars = resolvedRClassJars,
          )
        try {
          val results =
            screenshots
              .flatMap { handle.renderer.render(it, outputFolder, includeComponentTree, renderImage) }
              .sortedWith(
                compareBy(
                  { it.imagePath.ifEmpty { null } ?: it.componentTreePath.orEmpty() },
                  { it.methodFQN },
                  { it.previewId },
                  { it.displayName.orEmpty() },
                )
              )
          return PreviewRenderingResult(globalError = null, results)
        } finally {
          handle.releaseRender()
        }
      }

      // Cold / one-shot path: bootstrap a fresh Renderer and close it immediately via `.use {}`.
      val bootstrapper =
        RenderEnvironmentBootstrapper(
          fontsPath = fontsPath,
          resourceApkPath = resourceApkPath,
          namespace = namespace,
          classPath = classPath,
          projectClassPath = projectClassPath,
          layoutlibPath = layoutlibPath,
          resourceDirs = resourceDirs,
          rClassJars = resolvedRClassJars,
        )
      bootstrapper.bootstrap().use { renderer ->
        val results =
          screenshots
            .flatMap { renderer.render(it, outputFolder, includeComponentTree, renderImage) }
            .sortedWith(
              compareBy(
                { it.imagePath.ifEmpty { null } ?: it.componentTreePath.orEmpty() },
                { it.methodFQN },
                { it.previewId },
                { it.displayName.orEmpty() },
              )
            )
        PreviewRenderingResult(globalError = null, results)
      }
    } catch (t: Throwable) {
      PreviewRenderingResult(t.stackTraceToString(), emptyList())
    }
  }

  companion object {
    /**
     * Ref-counted handle holding an active [Renderer] and its corresponding [WarmEnvironmentKey].
     *
     * Reference counting guarantees that if [closeWarmRenderer] or an environment eviction occurs while a
     * render is actively executing on another thread, the underlying [Renderer] is not closed until that render
     * completes its work and releases its reference.
     */
    private class WarmRendererHandle(
      val key: WarmEnvironmentKey,
      val renderer: Renderer,
    ) {
      private val refCount = AtomicInteger(1)
      private val activeRenders = AtomicInteger(0)
      @Volatile var isEvicted = false

      val hasActiveRenders: Boolean
        get() = activeRenders.get() > 0

      fun retain(): Boolean {
        while (true) {
          val current = refCount.get()
          if (current <= 0 || isEvicted) return false
          if (refCount.compareAndSet(current, current + 1)) return true
        }
      }

      fun markRenderStart() {
        activeRenders.incrementAndGet()
      }

      fun releaseRender() {
        activeRenders.decrementAndGet()
        release()
      }

      fun release() {
        if (refCount.decrementAndGet() == 0) {
          if (!renderer.project.isDisposed) {
            renderer.close()
          }
        }
      }

      fun evictAndRelease() {
        isEvicted = true
        release()
      }
    }

    /**
     * Synchronizes access to [warmKey] and [warmHandle].
     *
     * Although callers instantiate a new [StandalonePreviewRenderer] per render request, the warm
     * [Renderer] is held statically in this companion object so that the bootstrapped Layoutlib and
     * IntelliJ project environment persists across requests within the same daemon JVM process.
     */
    private val warmLock = Any()
    private var warmKey: WarmEnvironmentKey? = null
    private var warmHandle: WarmRendererHandle? = null

    /**
     * Returns a warm [WarmRendererHandle] matching the given environment parameters, or bootstraps a new one
     * if none exists or if the environment inputs have changed. The caller MUST call [WarmRendererHandle.releaseRender]
     * when done with the handle.
     *
     * Lifecycle on each call:
     * 1. **Key computation**: Partitions `classPath + rClassJars` into mutable `projectClassPath`
     *    entries and immutable external library dependencies (`stableDeps`), and snapshots file
     *    modification timestamps for `resourceApkPath`, `resourceDirs`, `rClassJars`, and `stableDeps` into a
     *    [WarmEnvironmentKey].
     * 2. **Cache hit (fast path)**: If [warmHandle] is active, not disposed, and its [WarmEnvironmentKey] matches
     *    [key], resources and external dependencies are unchanged. If no render task is currently executing on
     *    the renderer, we call [Renderer.refreshClassPath] to dispose only the previous per-render
     *    `DefaultModuleClassLoader` so any newly recompiled project `.class` files in
     *    [projectClassPath] are loaded freshly on this render while reusing the warm Layoutlib
     *    environment and [LibraryClassBinaryCache]. If a render is already active (e.g. concurrent renders in
     *    daemon mode), we avoid refreshing the classloader mid-render to prevent classloader disposal races.
     * 3. **Cache miss / invalidation (slow path)**: If no warm renderer exists, its project was
     *    disposed, or [key] differs (e.g. `res.apk`, a resource XML file, or a dependency JAR was updated on disk),
     *    we evict the previous [warmHandle] (which safely closes it immediately if idle, or upon completion of any
     *    in-flight render) before bootstrapping and caching a fresh [Renderer].
     */
    private fun acquireWarmRenderer(
      fontsPath: String?,
      resourceApkPath: String,
      namespace: String,
      classPath: List<String>,
      projectClassPath: List<String>,
      layoutlibPath: String,
      resourceDirs: List<String>,
      rClassJars: List<String>,
    ): WarmRendererHandle =
      synchronized(warmLock) {
        // Combine `classPath` and `rClassJars` to match the full classpath passed to
        // StandaloneModuleClassLoaderManager during cold bootstrap in RenderEnvironmentBootstrapper.
        val fullClassPath = classPath + rClassJars
        val stableDeps = LibraryClassBinaryCache.computeStableDepsClassPath(fullClassPath, projectClassPath)
        val key =
          WarmEnvironmentKey(
            fontsPath = fontsPath,
            resourceApkPath = resourceApkPath,
            resourceApkLastModified = if (resourceApkPath.isNotEmpty()) File(resourceApkPath).lastModified() else 0L,
            namespace = namespace,
            layoutlibPath = layoutlibPath,
            resourceDirs = resourceDirs,
            resourceDirsTimestamps = resourceDirs.map { LibraryClassBinaryCache.computePathTimestamp(it) },
            rClassJars = rClassJars,
            rClassJarTimestamps = rClassJars.map { File(it).lastModified() },
            stableDepsClassPath = stableDeps,
            stableDepsTimestamps = stableDeps.map { LibraryClassBinaryCache.computePathTimestamp(it) },
          )
        val existingHandle = warmHandle
        if (existingHandle != null && !existingHandle.renderer.project.isDisposed && warmKey == key && existingHandle.retain()) {
          try {
            // Environment and external libraries are unchanged; only swap the project classloader
            // when idle so modified user `.class` files on disk are picked up by the next render task,
            // without disrupting concurrent renders actively executing on another thread.
            if (!existingHandle.hasActiveRenders) {
              existingHandle.renderer.refreshClassPath(fullClassPath, projectClassPath)
            }
            existingHandle.markRenderStart()
            return existingHandle
          } catch (t: Throwable) {
            existingHandle.evictAndRelease()
            existingHandle.release()
            warmHandle = null
            warmKey = null
            throw t
          }
        }
        // Environment inputs changed (or first render): dispose the old renderer before
        // bootstrapping a new one so we do not leak IntelliJ Project instances or classloaders.
        existingHandle?.evictAndRelease()
        warmHandle = null
        warmKey = null
        val freshRenderer =
          RenderEnvironmentBootstrapper(
              fontsPath = fontsPath,
              resourceApkPath = resourceApkPath,
              namespace = namespace,
              classPath = classPath,
              projectClassPath = projectClassPath,
              layoutlibPath = layoutlibPath,
              resourceDirs = resourceDirs,
              rClassJars = rClassJars,
            )
            .bootstrap()
        val freshHandle = WarmRendererHandle(key, freshRenderer)
        freshHandle.retain()
        freshHandle.markRenderStart()
        warmKey = key
        warmHandle = freshHandle
        freshHandle
      }

    /**
     * Closes and clears the cached warm [Renderer] (if any) without disposing the global
     * [IJFramework] application instance.
     */
    fun closeWarmRenderer() {
      synchronized(warmLock) {
        warmHandle?.evictAndRelease()
        warmHandle = null
        warmKey = null
      }
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
    System.err.println("Error: ${t.message}")
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
  val previewRendering = readPreviewRenderingJson(previewRenderingJson.reader())
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

  writePreviewRenderingResult(File(previewRendering.resultsFilePath).writer(), previewRenderingResult)

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
