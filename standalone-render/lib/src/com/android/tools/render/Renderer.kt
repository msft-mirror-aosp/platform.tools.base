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

import com.android.ide.common.rendering.api.Result
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.ResourceFolderType
import com.android.sdklib.devices.screenShape
import com.android.tools.configurations.Configuration
import com.android.tools.preview.applyTo
import com.android.tools.render.common.ComponentTreeExtractor
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.render.common.ResolvedScreenshotLayout
import com.android.tools.render.common.toScreenshotError
import com.android.tools.render.common.writeComponentTreeToJson
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.render.discovery.PreviewDiscoveryEngine
import com.android.tools.render.validation.PreviewValidator
import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.RenderTask
import com.android.tools.rendering.compose.ComposeViewInfo
import com.android.tools.rendering.compose.parseViewInfo
import com.android.tools.rendering.parsers.RenderXmlFileSnapshot
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.Shape
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * A renderer that can handle [RenderRequest].
 *
 * Previews ([render] with a [PreviewScreenshot]) are rendered like Android Studio renders static previews: layoutlib callbacks are executed
 * between renders, so the image includes the work scheduled by the initial composition, such as `LaunchedEffect`s and animations (see
 * [StaticPreviewCallbackRunner.renderExecutingCallbacks]). Plain layouts ([render] with a [RenderRequest]) are rendered once, like the
 * Android Studio layout editor does.
 *
 * Note: A [Renderer] instance is intended to be used from a single caller thread at a time (matching layoutlib's single render thread).
 */
class Renderer(
  val project: Project,
  val module: StandaloneRenderModelModule,
  private val renderService: RenderService,
  val baseConfiguration: Configuration,
  private val moduleClassLoaderManager: StandaloneModuleClassLoaderManager,
) : Closeable {
  private val logger = Logger.getLogger(Renderer::class.java.name)
  private val discoveryEngine by lazy { PreviewDiscoveryEngine(module) }
  private val previewValidator = PreviewValidator()
  internal val callbackRunner = StaticPreviewCallbackRunner()
  private val pendingDisposeTasks = ConcurrentLinkedQueue<() -> Future<*>>()
  private val inFlightDisposals = ConcurrentLinkedQueue<CompletableFuture<Void>>()
  private val outstandingDisposeFutures = ConcurrentLinkedQueue<Future<*>>()
  private val retiredClassLoaders = ConcurrentLinkedQueue<WeakReference<ClassLoader>>()

  private fun collectRetiredClassLoaders(): Boolean {
    val drained = moduleClassLoaderManager.drainRetiredClassLoaders()
    var added = false
    for (ref in drained) {
      if (ref.get() != null) {
        retiredClassLoaders.add(ref)
        added = true
      }
    }
    return added
  }

  private fun snapshotInFlightDisposals(): List<CompletableFuture<Void>> {
    inFlightDisposals.removeIf { it.isDone }
    // Use ArrayList(queue) (ConcurrentLinkedQueue.toArray) rather than Kotlin's Iterable.toList(), whose `size == 1 -> iterator().next()`
    // fast path throws NoSuchElementException when the render thread concurrently removes the sole element.
    return ArrayList(inFlightDisposals)
  }

  private fun awaitOutstandingDisposals() {
    outstandingDisposeFutures.removeIf { it.isDone }
    val futures = ArrayList(outstandingDisposeFutures)
    if (futures.isEmpty()) return
    try {
      awaitFutures(futures, DISPOSAL_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS, "RenderTask disposals")
    } finally {
      outstandingDisposeFutures.removeIf { it.isDone }
    }
  }

  private fun awaitFutures(futures: List<Future<*>>, timeout: Long, unit: TimeUnit, description: String) {
    if (futures.isEmpty()) return
    val deadlineNanos = System.nanoTime() + unit.toNanos(timeout)
    for (i in futures.indices) {
      val remainingNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
      try {
        futures[i].get(remainingNanos, TimeUnit.NANOSECONDS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        logger.log(Level.WARNING, "Interrupted while waiting for $description", e)
        return
      } catch (_: TimeoutException) {
        val pendingCount = futures.subList(i, futures.size).count { !it.isDone }
        if (pendingCount > 0) {
          logger.log(
            Level.WARNING,
            "Timed out after $timeout ${unit.name.lowercase()} waiting for $description ($pendingCount pending)",
          )
        }
        return
      } catch (t: Throwable) {
        logger.log(Level.WARNING, "Failed while waiting for $description", t)
      }
    }
  }

  private fun disposePendingRenderTasks() {
    while (true) {
      val disposeAction = pendingDisposeTasks.poll() ?: break
      logFailure("Unable to dispose RenderTask") {
        outstandingDisposeFutures.add(disposeAction())
      }
    }
  }

  private fun sweepRetiredClassLoaders() {
    collectRetiredClassLoaders()
    val iter = retiredClassLoaders.iterator()
    while (iter.hasNext()) {
      val retired = iter.next().get()
      if (retired == null) {
        iter.remove()
      } else {
        clearThreadLocalEventLoop(retired)
      }
    }
  }

  /**
   * Removes any `BlockingEventLoop` cached in `_layoutlib_._internal_.kotlinx.coroutines.ThreadLocalEventLoop` on the current thread (the
   * Layoutlib render thread) for a retired [DefaultModuleClassLoader] or closed [WarmDepsClassLoader].
   *
   * `ThreadLocalEventLoop` stores a `ThreadLocal<EventLoop>` (`ref`) whose value is a `BlockingEventLoop` defined by [classLoader]. Because
   * the `ThreadLocal` key is referenced by `ThreadLocalEventLoop` (which is kept alive by the `BlockingEventLoop` value's class ->
   * [classLoader] -> static `ref` chain), leaving the entry in the render thread's `ThreadLocalMap` prevents [classLoader] from ever being
   * garbage-collected or unloaded.
   */
  private fun clearThreadLocalEventLoop(classLoader: ClassLoader) {
    val eventLoopClass =
      when (classLoader) {
        is WarmDepsClassLoader ->
          if (classLoader.hasLoadedOnDiskClass(THREAD_LOCAL_EVENT_LOOP_FQN)) {
            classLoader.findLoadedClassOrNull(THREAD_LOCAL_EVENT_LOOP_FQN)
          } else {
            null
          }
        is DefaultModuleClassLoader ->
          if (classLoader.hasLoadedOwnClass(THREAD_LOCAL_EVENT_LOOP_FQN)) {
            try {
              Class.forName(THREAD_LOCAL_EVENT_LOOP_FQN, false, classLoader).takeIf { it.classLoader === classLoader }
            } catch (t: Throwable) {
              rethrowIfFatalOrCancelled(t)
              null
            }
          } else {
            null
          }
        else -> null
      } ?: return
    try {
      val refField = eventLoopClass.getDeclaredField("ref").apply { isAccessible = true }
      (refField.get(null) as? ThreadLocal<*>)?.remove()
    } catch (t: Throwable) {
      rethrowIfFatalOrCancelled(t)
      logger.log(Level.FINE, "Unable to clear ThreadLocalEventLoop for retired classloader", t)
    }
  }

  /**
   * Drains and disposes completed [RenderTask]s from the finished batch.
   *
   * [RenderTask.disposeAsync] is deferred until after a batch finishes because it runs `RenderSessionCleaner.dispose`, which clears static
   * state in the shared [DefaultModuleClassLoader] that later previews in the same batch still use. Callers that reuse this [Renderer] for
   * another batch must call [refreshClassPath] before rendering again.
   */
  fun disposeCompletedRenders() {
    awaitFutures(
      snapshotInFlightDisposals(),
      END_OF_BATCH_IN_FLIGHT_TIMEOUT_MS,
      TimeUnit.MILLISECONDS,
      "in-flight preview disposals",
    )
    disposePendingRenderTasks()
  }

  /**
   * Refreshes the module classloader with updated [newClassPath] and [newProjectClassPath] so modified user `.class` files are loaded
   * freshly while keeping the warm Layoutlib environment and external library classes intact.
   */
  fun refreshClassPath(newClassPath: List<String>, newProjectClassPath: List<String>) {
    awaitFutures(snapshotInFlightDisposals(), DISPOSAL_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS, "in-flight preview disposals")
    disposePendingRenderTasks()
    awaitOutstandingDisposals()
    val retiredClassLoader = moduleClassLoaderManager.refreshClassPath(newClassPath, newProjectClassPath)
    val hasNewlyRetired = collectRetiredClassLoaders()
    (retiredClassLoader?.parent as? WarmDepsClassLoader)?.let { warmParent ->
      if (!warmParent.isClosed) {
        restoreWarmGlobalSnapshotObserver(warmParent)
      }
    }
    if (hasNewlyRetired || retiredClassLoaders.isNotEmpty()) {
      logFailure("Unable to schedule ThreadLocalEventLoop cleanup") {
        RenderService.getRenderAsyncActionExecutor().runAsyncAction {
          sweepRetiredClassLoaders()
        }
      }
    }
    callbackRunner.onRefreshClassPath()
  }

  /**
   * Reason why Layoutlib's single render thread is still blocked by the timed-out work of a previous request, or `null` if it is available
   * or recovered in place. This only reports what the first preview of the current request observed when it probed the render thread (see
   * [StaticPreviewCallbackRunner.unrecoveredRenderThreadReason]); it does not probe or block.
   */
  val renderThreadPoisonedReason: String?
    get() = callbackRunner.unrecoveredRenderThreadReason

  companion object {
    private const val END_OF_BATCH_IN_FLIGHT_TIMEOUT_MS = 250L
    private const val DISPOSAL_DRAIN_TIMEOUT_SECONDS = 5L
    private val invalidCharsRegex = """[\u0000-\u001F\\/:*?"<>| ]+""".toRegex()
    private val invalidPathCharsRegex = """[\u0000-\u001F\\/:*?"<>|]+""".toRegex()
  }

  /**
   * Renders a given [PreviewScreenshot] and saves the output as PNG files.
   *
   * Discovers all previews declared on the screenshot's method (including multi-previews), validates each preview's parameters, and renders
   * them into PNG images.
   *
   * @param screenshot The metadata of the Jetpack Compose `@Preview` to render.
   * @param outputFolderPath The root directory where the resulting PNG images will be saved.
   * @param includeComponentTree Whether to extract and write the component tree JSON.
   * @param renderImage Whether to render and save the bitmap image. When `true`, layoutlib callbacks are executed before rendering and any
   *   extracted component tree reflects the settled post-callback hierarchy. When `false` (component-tree-only mode), the layout is only
   *   inflated without executing layoutlib callbacks or rendering a bitmap, so any extracted component tree reflects the initial
   *   post-inflation hierarchy before `LaunchedEffect`s or animations run.
   * @param allowShowDecorations Whether to honor [com.android.tools.preview.PreviewDisplaySettings.showDecoration] (for example,
   *   `@Preview(showSystemUi = true)`) by rendering device system decorations (status/navigation bars, device frame). Defaults to `false`
   *   so existing screenshot tests render composable content only.
   * @return A list of [PreviewScreenshotResult]s, each detailing the outcome of a single render operation, including the output path and
   *   any potential errors.
   */
  @JvmOverloads
  fun render(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    includeComponentTree: Boolean = false,
    renderImage: Boolean = true,
    allowShowDecorations: Boolean = false,
  ): List<PreviewScreenshotResult> {
    // If the screenshot is non-Compose or its preview and method parameters have already been resolved/specified,
    // bypass bytecode preview discovery and render it directly.
    if (screenshot !is ComposeScreenshot || screenshot.previewParams.isNotEmpty() || screenshot.methodParams.isNotEmpty()) {
      return renderResolvedScreenshot(
        screenshot,
        outputFolderPath,
        includeComponentTree,
        renderImage,
        allowShowDecorations,
      )
    }

    val packagePath = sanitizePackagePath(screenshot.methodFQN)
    val baseResultId = sanitizeBaseResultId(screenshot.previewId)
    var imageCounter = 0

    return discoveryEngine.discoverAllPreviews(screenshot.methodFQN, screenshot.previewId).flatMap { method ->
      if (method.methodValidationResult.hasErrors) {
        val index = imageCounter++
        val defaultRelativeImagePath = if (renderImage) packagePath + File.separator + "${baseResultId}_$index.png" else ""
        listOf(
          PreviewScreenshotResult(
            screenshot.previewId,
            screenshot.methodFQN,
            defaultRelativeImagePath,
            method.methodValidationResult.toScreenshotError(),
          )
        )
      } else {
        method.previews.flatMap { currentScreenshot ->
          val validationResult = previewValidator.validate(currentScreenshot)
          if (validationResult.hasErrors) {
            val index = imageCounter++
            val defaultRelativeImagePath = if (renderImage) packagePath + File.separator + "${baseResultId}_$index.png" else ""
            listOf(
              PreviewScreenshotResult(
                currentScreenshot.previewId,
                currentScreenshot.methodFQN,
                defaultRelativeImagePath,
                validationResult.toScreenshotError(),
              )
            )
          } else {
            if (validationResult.hasWarnings) {
              val warningMessages = validationResult.warnings.joinToString("; ") { it.message }
              logger.log(Level.WARNING, "Preview parameter validation warning for ${currentScreenshot.methodFQN}: $warningMessages")
            }
            renderScreenshotElement(
              currentScreenshot,
              outputFolderPath,
              packagePath,
              baseResultId,
              includeComponentTree,
              renderImage,
              allowShowDecorations,
            ) {
              imageCounter++
            }
          }
        }
      }
    }
  }

  /**
   * Directly renders a pre-configured or non-Compose [PreviewScreenshot] without performing bytecode preview discovery.
   *
   * Validates the screenshot configuration and outputs the rendered PNG file(s) or records validation errors if parameters are invalid.
   */
  private fun renderResolvedScreenshot(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    includeComponentTree: Boolean,
    renderImage: Boolean,
    allowShowDecorations: Boolean = false,
  ): List<PreviewScreenshotResult> {
    val packagePath = sanitizePackagePath(screenshot.methodFQN)
    val baseResultId = sanitizeBaseResultId(screenshot.previewId)
    val validationResult = previewValidator.validate(screenshot)
    return if (validationResult.hasErrors) {
      val defaultRelativeImagePath = if (renderImage) packagePath + File.separator + "${baseResultId}_0.png" else ""
      listOf(
        PreviewScreenshotResult(screenshot.previewId, screenshot.methodFQN, defaultRelativeImagePath, validationResult.toScreenshotError())
      )
    } else {
      if (validationResult.hasWarnings) {
        val warningMessages = validationResult.warnings.joinToString("; ") { it.message }
        logger.log(Level.WARNING, "Preview parameter validation warning for ${screenshot.methodFQN}: $warningMessages")
      }
      var imageCounter = 0
      renderScreenshotElement(
        screenshot,
        outputFolderPath,
        packagePath,
        baseResultId,
        includeComponentTree,
        renderImage,
        allowShowDecorations,
      ) {
        imageCounter++
      }
    }
  }

  private fun renderScreenshotElement(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    packagePath: String,
    baseResultId: String,
    includeComponentTree: Boolean,
    renderImage: Boolean,
    allowShowDecorations: Boolean = false,
    nextImageIndex: () -> Int,
  ): List<PreviewScreenshotResult> {
    // Creating the preview element can fail for reasons specific to a single screenshot (e.g. a class that cannot be loaded). Report
    // those as an error for this screenshot rather than letting the exception abort the whole render call.
    val (previewElement, resolvedLayouts) =
      try {
        val element = screenshot.toPreviewElement(module)
        element to element.resolveLayouts().toList()
      } catch (t: Throwable) {
        rethrowIfFatalOrCancelled(t)
        // Catch Throwable rather than Exception: class loading failures surface as Errors (e.g. NoClassDefFoundError, LinkageError).
        val index = nextImageIndex()
        val defaultRelativeImagePath = if (renderImage) packagePath + File.separator + "${baseResultId}_$index.png" else ""
        return listOf(PreviewScreenshotResult(screenshot.previewId, screenshot.methodFQN, defaultRelativeImagePath, t.toScreenshotError()))
      }
    val seenNames = mutableSetOf<String>()
    val duplicateNames = mutableSetOf<String>()
    for (layout in resolvedLayouts) {
      val name = layout.displayName?.replace(invalidCharsRegex, "_")?.trim('_')
      if (!name.isNullOrBlank() && !seenNames.add(name)) {
        duplicateNames.add(name)
      }
    }

    val renderRequest =
      RenderRequest.withResolvedLayouts(
        configurationModifier = previewElement::applyTo,
        resolvedLayoutsProvider = { resolvedLayouts.asSequence() },
        showDecorations = allowShowDecorations && previewElement.displaySettings.showDecoration,
      )

    val usedSuffixes = mutableSetOf<String>()
    val previewName =
      when {
        screenshot.previewId.isEmpty() -> screenshot.methodFQN
        screenshot.previewId.startsWith(screenshot.methodFQN) -> screenshot.previewId
        else -> "${screenshot.methodFQN} (${screenshot.previewId})"
      }
    return renderResolvedLayouts(renderRequest, renderImage, previewName)
      .map { (config, renderResult, layout) ->
        val index = nextImageIndex()
        // If a custom display name is provided (e.g. from PreviewParameterProvider.getDisplayName),
        // sanitize it for filesystem paths by replacing whitespace and reserved characters with underscores.
        // If duplicate, append the preview index to disambiguate. If blank or not provided, fall back to numerical index.
        val sanitizedDisplayName = layout.displayName?.replace(invalidCharsRegex, "_")?.trim('_')
        val baseSuffix =
          when {
            sanitizedDisplayName.isNullOrBlank() -> index.toString()
            sanitizedDisplayName in duplicateNames -> "${sanitizedDisplayName}_$index"
            else -> sanitizedDisplayName
          }
        val suffix =
          if (usedSuffixes.add(baseSuffix)) {
            baseSuffix
          } else {
            val nameWithIndex = "${baseSuffix}_$index"
            usedSuffixes.add(nameWithIndex)
            nameWithIndex
          }
        val resultId = "${baseResultId}_$suffix"
        val imageName = "$resultId.png"
        val relativeImagePath = if (renderImage) packagePath + File.separator + imageName else ""
        val relativeComponentTreePath = packagePath + File.separator + "$resultId.json"
        try {
          val viewInfos = renderResult.rootViews.flatMap { parseViewInfo(it) }
          val imageRendered =
            if (renderImage) {
              val image = postProcessRenderedImage(config, renderResult)
              if (image != null) {
                saveImage(image, outputFolderPath, relativeImagePath)
              }
              image
            } else {
              null
            }

          val savedComponentTreePath =
            if (
              includeComponentTree &&
                renderResult.renderResult.status == Result.Status.SUCCESS &&
                saveComponentTree(
                  previewId = screenshot.previewId,
                  methodFQN = screenshot.methodFQN,
                  displayName = layout.displayName,
                  outputFolderPath = outputFolderPath,
                  relativeComponentTreePath = relativeComponentTreePath,
                  config = config,
                  renderResult = renderResult,
                  viewInfos = viewInfos,
                )
            ) {
              relativeComponentTreePath
            } else {
              null
            }

          val screenshotError = renderResult.toScreenshotError(imageRendered, expectImage = renderImage)
          // Pass the raw unsanitized display name so downstream test descriptors can display it in reports.
          PreviewScreenshotResult(
            screenshot.previewId,
            screenshot.methodFQN,
            relativeImagePath,
            screenshotError,
            layout.displayName,
            viewInfos,
            savedComponentTreePath,
          )
        } catch (t: Throwable) {
          rethrowIfFatalOrCancelled(t)
          PreviewScreenshotResult(
            screenshot.previewId,
            screenshot.methodFQN,
            relativeImagePath,
            t.toScreenshotError(),
            layout.displayName,
            viewInfos = emptyList(),
            componentTreePath = null,
          )
        } finally {
          Disposer.dispose(renderResult)
        }
      }
      .toList()
  }

  internal fun saveComponentTree(
    previewId: String,
    methodFQN: String,
    displayName: String?,
    outputFolderPath: String,
    relativeComponentTreePath: String,
    config: Configuration = baseConfiguration,
    renderResult: RenderResult? = null,
    viewInfos: List<ComposeViewInfo> = emptyList(),
  ): Boolean {
    return try {
      val rootDir = Paths.get(outputFolderPath).toAbsolutePath().normalize()
      val treePath = rootDir.resolve(relativeComponentTreePath).normalize()
      if (!treePath.startsWith(rootDir)) {
        logger.log(Level.SEVERE, "Refusing to write component tree outside output folder: $treePath")
        return false
      }
      val tree =
        ComponentTreeExtractor.extract(
          previewId = previewId,
          methodFQN = methodFQN,
          displayName = displayName,
          config = config,
          renderResult = renderResult,
          viewInfos = viewInfos,
        )
      treePath.parent?.let { Files.createDirectories(it) }
      Files.newBufferedWriter(treePath, StandardCharsets.UTF_8).use { writer ->
        writeComponentTreeToJson(writer, tree)
      }
      true
    } catch (e: Exception) {
      logger.log(Level.SEVERE, "Failed to write component tree to $relativeComponentTreePath", e)
      false
    }
  }

  private fun saveImage(image: BufferedImage, outputFolderPath: String, relativeImagePath: String) {
    try {
      val rootDir = Paths.get(outputFolderPath).toAbsolutePath().normalize()
      val imagePath = rootDir.resolve(relativeImagePath).normalize()
      if (!imagePath.startsWith(rootDir)) {
        logger.log(Level.SEVERE, "Refusing to write image outside output folder: $imagePath")
        return
      }
      Files.createDirectories(imagePath.parent)
      val writers = ImageIO.getImageWritersByFormatName("png")
      if (writers.hasNext()) {
        val writer = writers.next()
        try {
          val writeParam = writer.defaultWriteParam
          if (writeParam.canWriteCompressed()) {
            writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
            // PNG is always 100% lossless; in JDK's PNGImageWriter, deflate level = 9 - round(9 * quality),
            // so 0.89f selects Deflater.BEST_SPEED (level 1) instead of level 4, cutting PNG encode latency.
            writeParam.compressionQuality = 0.89f
          }
          BufferedOutputStream(Files.newOutputStream(imagePath), 65_536).use { fileOut ->
            MemoryCacheImageOutputStream(fileOut).use { imageOut ->
              writer.output = imageOut
              writer.write(null, IIOImage(image, null, null), writeParam)
            }
          }
          return
        } finally {
          writer.dispose()
        }
      }
      val imgFile = imagePath.toFile()
      imgFile.createNewFile()
      ImageIO.write(image, "png", imgFile)
    } catch (e: IOException) {
      logger.log(Level.SEVERE, "Failed to write image to $relativeImagePath", e)
    }
  }

  private fun sanitizePackagePath(methodFqn: String): String =
    methodFqn
      .substringBeforeLast(".")
      .split('.')
      .map { it.replace(invalidPathCharsRegex, "_") }
      .filter { it.isNotEmpty() && it != "." && it != ".." }
      .joinToString(File.separator)

  private fun sanitizeBaseResultId(previewId: String): String {
    val sanitized = previewId.substringAfterLast(".").replace(invalidPathCharsRegex, "_")
    return if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") "preview" else sanitized
  }

  /** Renders the layouts of [request] once, like the Android Studio layout editor does, without executing layoutlib callbacks. */
  fun render(request: RenderRequest, renderImage: Boolean = true): Sequence<Pair<Configuration, RenderResult>> {
    return request.xmlLayoutsProvider().map {
      val configuration = baseConfiguration.clone()
      request.configurationModifier(configuration)
      configuration to
        render(
          configuration,
          it,
          renderImage,
          allowShowDecorations = request.showDecorations,
        )
    }
  }

  /**
   * Renders the preview layouts of the given [RenderRequest] while preserving each layout's metadata (such as custom parameter display
   * names in [ResolvedScreenshotLayout]).
   *
   * When [renderImage] is `true`, the layouts are rendered like Android Studio renders static previews, executing layoutlib callbacks
   * between renders (see [StaticPreviewCallbackRunner.renderExecutingCallbacks]), so any extracted component tree reflects the settled
   * post-callback hierarchy. When [renderImage] is `false` (component-tree-only mode), the layouts are only inflated without executing
   * layoutlib callbacks or rendering a bitmap, so any extracted component tree reflects the initial post-inflation hierarchy.
   *
   * If [RenderRequest.resolvedLayoutsProvider] is null, falls back to rendering each layout from [RenderRequest.xmlLayoutsProvider] wrapped
   * in a [ResolvedScreenshotLayout] with null display name.
   */
  @JvmOverloads
  fun renderResolvedLayouts(
    request: RenderRequest,
    renderImage: Boolean = true,
  ): Sequence<Triple<Configuration, RenderResult, ResolvedScreenshotLayout>> =
    renderResolvedLayouts(request, renderImage, previewName = "preview")

  internal fun renderResolvedLayouts(
    request: RenderRequest,
    renderImage: Boolean,
    previewName: String,
  ): Sequence<Triple<Configuration, RenderResult, ResolvedScreenshotLayout>> {
    val layoutsProvider =
      request.resolvedLayoutsProvider
        ?: {
          request.xmlLayoutsProvider().map { ResolvedScreenshotLayout(it) }
        }
    return layoutsProvider().map {
      val configuration = baseConfiguration.clone()
      request.configurationModifier(configuration)
      val layoutPreviewName = it.displayName?.let { displayName -> "$previewName [$displayName]" } ?: previewName
      Triple(
        configuration,
        render(
          configuration,
          it.xmlLayout,
          renderImage,
          executeCallbacks = true,
          previewName = layoutPreviewName,
          allowShowDecorations = request.showDecorations,
        ),
        it,
      )
    }
  }

  /**
   * Renders [xmlLayout]. When [renderImage] and [executeCallbacks] are `true`, layoutlib callbacks are executed between renders (see
   * [StaticPreviewCallbackRunner.renderExecutingCallbacks]). When [renderImage] is `false`, the layout is only inflated.
   */
  private fun render(
    configuration: Configuration,
    xmlLayout: String,
    renderImage: Boolean,
    executeCallbacks: Boolean = false,
    previewName: String? = null,
    allowShowDecorations: Boolean = false,
  ): RenderResult {
    awaitOutstandingDisposals()
    val disposable = Disposer.newCheckedDisposable()
    val logger = RenderLogger()
    val composeViewAdapters: MutableSet<Any> = ConcurrentHashMap.newKeySet()
    val recordAdapters: (RenderResult) -> Unit = { composeViewAdapters.addAll(findComposeViewAdapters(it.rootViews)) }
    return try {
      var poisonedError: Throwable? = null
      if (
        !callbackRunner.ensureRenderThreadAvailable(
          RenderService.getRenderAsyncActionExecutor()::runAsyncAction,
          reportError = { message, throwable ->
            poisonedError = throwable
            // RenderLogger.error passes `message` to HtmlBuilder.add(String), which XML-escapes plain text automatically.
            logger.error(null, message, throwable, null, null)
          },
        )
      ) {
        Disposer.dispose(disposable)
        return createRenderTaskErrorResult(logger, poisonedError)
      }

      val renderTask =
        renderService.taskBuilder(module, configuration, logger).build(disposable).get()
          ?: run {
            Disposer.dispose(disposable)
            return createRenderTaskErrorResult(logger)
          }
      val moduleClassLoader: ClassLoader = moduleClassLoaderManager.getShared(null).classLoader
      restoreWarmGlobalSnapshotObserver(moduleClassLoader)

      if (allowShowDecorations) {
        renderTask.setDecorations(true)
        renderTask.setRenderingMode(SessionParams.RenderingMode.NORMAL)
      }

      // b/469819154: Release render and dispose Compose views after use to avoid accumulating heap memory usage
      // and leaving effects or animations from this preview running on the shared AndroidUiDispatcher.Main.
      // Note on Disposer order: RenderService.RenderTaskBuilder.build(disposable) only checks disposable.isDisposed() during task
      // construction and does not register renderTask.disposeAsync() on disposable, so renderTask's session is still alive when
      // releaseRender runs upon disposal of result -> disposable.
      Disposer.register(disposable) { releaseRender(renderTask, composeViewAdapters, moduleClassLoader) }

      val xmlFile = RenderXmlFileSnapshot(project, "layout.xml", ResourceFolderType.LAYOUT, xmlLayout)

      renderTask.setXmlFile(xmlFile)

      val result =
        if (renderImage) {
          if (executeCallbacks) {
            callbackRunner.renderExecutingCallbacks(
              RenderTaskPreviewSession(renderTask, logger, ::createRenderTaskErrorResult, moduleClassLoader),
              previewName ?: "preview",
              recordAdapters,
            )
          } else {
            renderTask.render().get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS).also(recordAdapters)
          }
        } else {
          (renderTask.inflate().get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: createRenderTaskErrorResult(logger)).also(recordAdapters)
        }
      Disposer.register(result, disposable)
      try {
        result.rethrowIfFatalVirtualMachineError()
      } catch (e: VirtualMachineError) {
        Disposer.dispose(result)
        throw e
      }
      result
    } catch (t: Throwable) {
      Disposer.dispose(disposable)
      rethrowIfFatalOrCancelled(t)
      createRenderTaskErrorResult(logger, t)
    }
  }

  /**
   * Disposes any [composeViewAdapters] inflated for this preview (cancelling their compositions, `LaunchedEffect`s, animations, and
   * `Recomposer`s so they do not keep running on the shared `AndroidUiDispatcher.Main` during later previews), clears any work left queued
   * in `AndroidUiDispatcher.Main` by unscoped coroutines or post-dispose callbacks (including on [moduleClassLoader] even if no
   * `ComposeViewAdapter` was recorded), and releases the rendered image of [renderTask].
   *
   * Ordering guarantee: `runAsyncRenderActionWithSession` (with [DEFAULT_RENDER_THREAD_TIMEOUT_SECONDS], i.e. `RenderTask`'s default 10s
   * render-thread timeout) and `RenderAsyncActionExecutor.runAsyncAction` submit on `RenderingTopic.NOT_SPECIFIED`. Because standalone
   * rendering submits all render, callback, exception-handler, and `releaseRender` actions on `RenderingTopic.NOT_SPECIFIED` without
   * calling `RenderAsyncActionExecutor.cancelActionsByTopic` or `cancelLowerPriorityActions`, this cleanup runs in FIFO order on the single
   * render thread before the next preview's `inflate()`.
   */
  private fun releaseRender(renderTask: RenderTask, composeViewAdapters: Set<Any>, moduleClassLoader: ClassLoader) {
    val disposalReady = CompletableFuture<Void>()
    inFlightDisposals.add(disposalReady)
    val completed = AtomicBoolean(false)
    val onComplete = {
      if (completed.compareAndSet(false, true)) {
        pendingDisposeTasks.add { renderTask.disposeAsync() }
        inFlightDisposals.remove(disposalReady)
        disposalReady.complete(null)
      }
    }
    var disposeScheduled = false
    try {
      logFailure("Unable to schedule ComposeViewAdapter disposal") {
        disposeComposeViewsAndClearDispatcher(renderTask, composeViewAdapters, moduleClassLoader, onComplete)
        disposeScheduled = true
      }
    } finally {
      renderTask.releaseRender()
      if (!disposeScheduled) {
        onComplete()
      }
    }
  }

  private fun disposeComposeViewsAndClearDispatcher(
    renderTask: RenderTask,
    composeViewAdapters: Set<Any>,
    moduleClassLoader: ClassLoader,
    onComplete: () -> Unit,
  ) {
    val composeClassLoaders =
      composeViewAdapters.mapNotNullTo(linkedSetOf(moduleClassLoader)) {
        it.javaClass.classLoader
      }
    val clearDispatcherWork = Runnable {
      for (classLoader in composeClassLoaders) {
        clearComposeUiDispatcherWork(classLoader)
      }
      sweepRetiredClassLoaders()
    }
    val scheduleClearDispatcherWork = {
      try {
        RenderService.getRenderAsyncActionExecutor().runAsyncAction(clearDispatcherWork).whenComplete { _, _ ->
          onComplete()
        }
      } catch (t: Throwable) {
        onComplete()
        throw t
      }
    }
    val disposeActions = composeViewAdapters.mapNotNull { adapter ->
      logFailure("Unable to look up ComposeViewAdapter.dispose") {
        disposeMethodByClass.get(adapter.javaClass)?.let { method -> { method.invoke(adapter) } }
      }
    }
    if (disposeActions.isNotEmpty()) {
      val disposeFuture =
        logFailure("Unable to schedule ComposeViewAdapter disposal") {
          renderTask.runAsyncRenderActionWithSession(
            {
              try {
                for (disposeAction in disposeActions) {
                  logFailure("Unable to dispose ComposeViewAdapter") { disposeAction() }
                }
              } finally {
                clearDispatcherWork.run()
              }
            },
            DEFAULT_RENDER_THREAD_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
          )
        }
      if (disposeFuture != null) {
        disposeFuture.whenComplete { _, t ->
          if (t != null) {
            if (t !is CancellationException && t.cause !is CancellationException) {
              logger.log(Level.WARNING, "Unable to dispose ComposeViewAdapter", t)
            }
            logFailure("Unable to schedule ComposeUiDispatcher cleanup") {
              scheduleClearDispatcherWork()
            }
          } else {
            onComplete()
          }
        }
      } else {
        scheduleClearDispatcherWork()
      }
    } else {
      scheduleClearDispatcherWork()
    }
  }

  private inline fun <T> logFailure(message: String, block: () -> T): T? =
    try {
      block()
    } catch (e: Exception) {
      logger.log(Level.WARNING, message, e)
      null
    } catch (e: LinkageError) {
      logger.log(Level.WARNING, message, e)
      null
    }

  private fun createRenderTaskErrorResult(logger: RenderLogger, throwable: Throwable? = null): RenderResult =
    RenderResult.createRenderTaskErrorResult(
      module,
      { throw NotImplementedError("PsiFile supplier is not supported") },
      throwable,
      logger,
    )

  private fun postProcessRenderedImage(config: Configuration, renderResult: RenderResult): BufferedImage? {
    val imageCopy = renderResult.renderedImage.copy
    if (imageCopy == null && renderResult.renderResult.status != Result.Status.SUCCESS) return null

    val image = imageCopy ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val screenShape = config.device?.screenShape(0.0, 0.0, Dimension(image.width, image.height)) ?: return image
    return resizeImage(image, screenShape)
  }

  private fun resizeImage(image: BufferedImage, shape: Shape): BufferedImage {
    val newImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    val g = newImage.createGraphics()
    try {
      g.composite = AlphaComposite.Clear
      g.fillRect(0, 0, image.width, image.height)
      g.composite = AlphaComposite.Src
      g.clip = shape
      g.drawImage(image, 0, 0, null)
    } finally {
      g.dispose()
    }
    return newImage
  }

  override fun close() {
    try {
      awaitFutures(snapshotInFlightDisposals(), DISPOSAL_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS, "in-flight preview disposals")
      disposePendingRenderTasks()
      awaitOutstandingDisposals()
    } finally {
      try {
        if (!module.isDisposed) {
          module.close()
        }
      } finally {
        try {
          moduleClassLoaderManager.closeAndReturnRetired()
          collectRetiredClassLoaders()
          if (retiredClassLoaders.isNotEmpty()) {
            logFailure("Unable to schedule ThreadLocalEventLoop cleanup") {
              RenderService.getRenderAsyncActionExecutor().runAsyncAction {
                sweepRetiredClassLoaders()
              }
            }
          }
        } finally {
          if (!project.isDisposed) {
            Disposer.dispose(project)
          }
        }
      }
    }
  }
}

private const val THREAD_LOCAL_EVENT_LOOP_FQN = "_layoutlib_._internal_.kotlinx.coroutines.ThreadLocalEventLoop"

/** Maximum number of causes inspected by [findFatalVirtualMachineError], guarding against cyclic cause chains. */
private const val MAX_CAUSE_CHAIN_DEPTH = 16

/**
 * Returns the first [VirtualMachineError] other than [StackOverflowError] in [this] cause chain, or `null` if none is present.
 *
 * Reflection (`InvocationTargetException`), class initialization (`ExceptionInInitializerError`), and async futures (`ExecutionException` /
 * `CompletionException`) wrap fatal JVM errors such as [OutOfMemoryError], so walking the cause chain ensures they are not mistaken for
 * recoverable per-preview errors.
 */
private fun Throwable?.findFatalVirtualMachineError(): VirtualMachineError? {
  var current = this
  var depth = 0
  while (current != null && depth < MAX_CAUSE_CHAIN_DEPTH) {
    if (current is VirtualMachineError && current !is StackOverflowError) {
      return current
    }
    current = current.cause
    depth++
  }
  return null
}

/**
 * Rethrows [t] when it represents caller-thread interruption/cancellation or wraps a fatal [VirtualMachineError] (other than
 * [StackOverflowError]), restoring the thread's interrupt flag on [InterruptedException].
 */
private fun rethrowIfFatalOrCancelled(t: Throwable) {
  when (t) {
    is InterruptedException -> {
      Thread.currentThread().interrupt()
      throw t
    }
    is ProcessCanceledException,
    is CancellationException -> throw t
  }
  t.findFatalVirtualMachineError()?.let { throw it }
}

/**
 * Rethrows any fatal [VirtualMachineError] (other than [StackOverflowError]) that `RenderTask`, Layoutlib, or the render-thread uncaught
 * exception handler captured inside [RenderResult] instead of propagating directly.
 */
private fun RenderResult.rethrowIfFatalVirtualMachineError() {
  val allThrowables =
    sequenceOf(renderResult.exception) + logger.messages.asSequence().map { it.throwable } + logger.brokenClasses.values.asSequence()
  val fatal = allThrowables.mapNotNull { it.findFatalVirtualMachineError() }.firstOrNull()
  if (fatal != null) {
    throw fatal
  }
}
