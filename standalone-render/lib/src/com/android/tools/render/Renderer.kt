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

import com.android.ide.common.rendering.HardwareConfigHelper
import com.android.ide.common.rendering.api.Result
import com.android.ide.common.rendering.api.SessionParams
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import com.android.resources.ResourceFolderType
import com.android.sdklib.devices.Device
import com.android.sdklib.devices.screenShape
import com.android.tools.configurations.Configuration
import com.android.tools.preview.applyTo
import com.android.tools.preview.config.ExpandAxis
import com.android.tools.preview.config.ExpandSettings
import com.android.tools.render.animation.AnimationRenderOptions
import com.android.tools.render.animation.PreviewAnimationResult
import com.android.tools.render.common.ComponentTreeExtractor
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.render.common.ResolvedScreenshotLayout
import com.android.tools.render.common.ResourceTarget
import com.android.tools.render.common.offsetBy
import com.android.tools.render.common.toScreenshotError
import com.android.tools.render.common.toScreenshotWarnings
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
import com.android.tools.rendering.compose.supportedComposePreviewDeviceSpecParameters
import com.android.tools.rendering.compose.supportsExpand
import com.android.tools.rendering.compose.unsupportedComposePreviewDeviceSpecWarning
import com.android.tools.rendering.expandedScreenShape
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
import kotlin.math.roundToInt

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
  internal val renderService: RenderService,
  val baseConfiguration: Configuration,
  internal val moduleClassLoaderManager: StandaloneModuleClassLoaderManager,
) : Closeable {
  private val logger = Logger.getLogger(Renderer::class.java.name)
  internal val discoveryEngine by lazy { PreviewDiscoveryEngine(module) }
  // The renderer resolves `device` references in the device catalog of the base configuration, so validate them against the same catalog.
  internal val previewValidator by lazy { PreviewValidator(baseConfiguration.settings.devices) }
  internal val callbackRunner = StaticPreviewCallbackRunner()
  private val animationRenderer = PreviewAnimationRenderer(this)
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

  internal fun awaitOutstandingDisposals() {
    disposePendingRenderTasks()
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

  /**
   * The `spec:` device string parameter names supported by the project's `androidx.compose.ui:ui-tooling`, see
   * [ClassLoader.supportedComposePreviewDeviceSpecParameters].
   */
  internal val supportedDeviceSpecParameters: Set<String>
    get() = moduleClassLoaderManager.getShared(Renderer::class.java.classLoader).classLoader.supportedComposePreviewDeviceSpecParameters()

  companion object {
    private const val END_OF_BATCH_IN_FLIGHT_TIMEOUT_MS = 250L
    private const val DISPOSAL_DRAIN_TIMEOUT_SECONDS = 5L
    internal val invalidCharsRegex = """[\u0000-\u001F\\/:*?"<>| ]+""".toRegex()
    private val invalidPathCharsRegex = """[\u0000-\u001F\\/:*?"<>|]+""".toRegex()

    /** Returns the directory, relative to the output folder, of the images of the previews of the method [methodFqn]. */
    fun sanitizePackagePath(methodFqn: String): String =
      methodFqn
        .substringBeforeLast(".")
        .split('.')
        .map { it.replace(invalidPathCharsRegex, "_") }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString(File.separator)

    /**
     * Returns the base of the image file names of the preview [previewId] of the method [methodFqn].
     *
     * The images are written to the directory of the method's class (see [sanitizePackagePath]), so the class prefix is removed from
     * [previewId], for example `pkg.MyTestKt.myPreview_Devices.TV_1080p_68b37a47` becomes `myPreview_Devices.TV_1080p_68b37a47`. Only the
     * class prefix is removed: a preview name that contains `.` must not cut off the method name, otherwise the same `@Preview` on two
     * methods of a class would write the same file. A [previewId] that doesn't start with the class prefix, such as a resource name, keeps
     * only its last `.`-separated segment.
     */
    fun sanitizeBaseResultId(previewId: String, methodFqn: String): String {
      val classPrefix = methodFqn.substringBeforeLast(".", missingDelimiterValue = "")
      val relativePreviewId =
        if (classPrefix.isNotEmpty() && previewId.startsWith("$classPrefix.")) previewId.removePrefix("$classPrefix.")
        else previewId.substringAfterLast(".")
      val sanitized = relativePreviewId.replace(invalidPathCharsRegex, "_")
      return if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") "preview" else sanitized
    }
  }

  /**
   * Renders a given [PreviewScreenshot] and saves the output as PNG files.
   *
   * Discovers all previews declared on the screenshot's method (including multi-previews), validates each preview's parameters, and renders
   * them into PNG images according to [configuration].
   *
   * @param screenshot The metadata of the Jetpack Compose `@Preview` to render.
   * @param outputFolderPath The root directory where the resulting PNG images and component trees will be saved.
   * @param configuration Output and inspection options controlling image rendering, component tree extraction, system UI decorations, and
   *   animation detection.
   * @return A list of [PreviewScreenshotResult]s, each detailing the outcome of a single render operation, including the output path and
   *   any potential errors.
   */
  @JvmOverloads
  fun render(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    configuration: RenderConfiguration = RenderConfiguration(),
  ): List<PreviewScreenshotResult> {
    // If the screenshot is non-Compose or its preview and method parameters have already been resolved/specified,
    // bypass bytecode preview discovery and render it directly.
    if (screenshot !is ComposeScreenshot || screenshot.previewParams.isNotEmpty() || screenshot.methodParams.isNotEmpty()) {
      return renderResolvedScreenshot(
        screenshot,
        outputFolderPath,
        configuration,
      )
    }

    val packagePath = sanitizePackagePath(screenshot.methodFQN)
    val baseResultId = sanitizeBaseResultId(screenshot.previewId, screenshot.methodFQN)
    var imageCounter = 0

    return discoveryEngine.discoverAllPreviews(screenshot.methodFQN, screenshot.previewId).flatMap { method ->
      if (method.methodValidationResult.hasErrors) {
        val index = imageCounter++
        val defaultRelativeImagePath = if (configuration.renderImage) packagePath + File.separator + "${baseResultId}_$index.png" else ""
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
            val defaultRelativeImagePath =
              if (configuration.renderImage) packagePath + File.separator + "${baseResultId}_$index.png" else ""
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
              configuration,
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
    configuration: RenderConfiguration = RenderConfiguration(),
  ): List<PreviewScreenshotResult> {
    // Resource targets (for example R.layout.activity_main) are written to <resource type>/<resource name>_<n>.png.
    val resourceTarget = ResourceTarget.parse(screenshot.methodFQN) as? ResourceTarget.Valid
    val packagePath = resourceTarget?.type?.getName()?.replace(invalidPathCharsRegex, "_") ?: sanitizePackagePath(screenshot.methodFQN)
    // A preview ID that is itself a resource target, for example R.layout.activity_main, is replaced by the resource name.
    val previewTarget =
      if (screenshot.previewId == screenshot.methodFQN) resourceTarget
      else ResourceTarget.parse(screenshot.previewId) as? ResourceTarget.Valid
    val baseResultId = sanitizeBaseResultId(previewTarget?.name ?: screenshot.previewId, screenshot.methodFQN)
    val validationResult = previewValidator.validate(screenshot)
    return if (validationResult.hasErrors) {
      val defaultRelativeImagePath = if (configuration.renderImage) packagePath + File.separator + "${baseResultId}_0.png" else ""
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
        configuration,
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
    configuration: RenderConfiguration = RenderConfiguration(),
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
        val defaultRelativeImagePath = if (configuration.renderImage) packagePath + File.separator + "${baseResultId}_$index.png" else ""
        return listOf(PreviewScreenshotResult(screenshot.previewId, screenshot.methodFQN, defaultRelativeImagePath, t.toScreenshotError()))
      }
    val idAllocator = LayoutResultIdAllocator(screenshot, resolvedLayouts, baseResultId)
    val showDecorations = configuration.allowShowDecorations && previewElement.displaySettings.showDecoration

    val renderRequest =
      RenderRequest.withResolvedLayouts(
        configurationModifier = previewElement::applyTo,
        resolvedLayoutsProvider = { resolvedLayouts.asSequence() },
        showDecorations = showDecorations,
        transparentBackground = previewElement.transparentBackground,
        expandToContent = previewElement.expandToContent,
      )

    return renderResolvedLayouts(renderRequest, configuration.renderImage, idAllocator.basePreviewName)
      .map { (config, renderResult, layout) ->
        val resultId = idAllocator.nextResultId(layout, nextImageIndex)
        val imageName = "$resultId.png"
        val relativeImagePath = if (configuration.renderImage) packagePath + File.separator + imageName else ""
        val relativeComponentTreePath = packagePath + File.separator + "$resultId.json"
        try {
          // Walking the View and Compose hierarchies is only needed for the component tree.
          val viewInfos = if (configuration.includeComponentTree) renderResult.rootViews.flatMap { parseViewInfo(it) } else emptyList()
          val imageRendered =
            if (configuration.renderImage) {
              val image =
                postProcessRenderedImage(
                  config,
                  renderResult,
                  renderRequest.expandToContent,
                  renderRequest.showDecorations,
                  layout.expandSettings,
                )
              if (image != null) {
                saveImage(image, outputFolderPath, relativeImagePath)
              }
              image
            } else {
              null
            }

          val savedComponentTreePath =
            if (
              configuration.includeComponentTree &&
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
                  detectAnimations = configuration.detectAnimations,
                  // RenderTask forces no decor on Wear, so system UI is never drawn.
                  showSystemUi = renderRequest.showDecorations && !Device.isWear(config.device),
                  // On Wear with showDecorations = true, showSystemUi is false while the image is not cropped.
                  expandToContent = !renderRequest.showDecorations && renderRequest.expandToContent,
                )
            ) {
              relativeComponentTreePath
            } else {
              null
            }

          val screenshotError = renderResult.toScreenshotError(imageRendered, expectImage = configuration.renderImage)
          val screenshotWarnings = renderResult.toScreenshotWarnings()
          // Pass the raw unsanitized display name so downstream test descriptors can display it in reports.
          PreviewScreenshotResult(
            screenshot.previewId,
            screenshot.methodFQN,
            relativeImagePath,
            screenshotError,
            layout.displayName,
            viewInfos,
            savedComponentTreePath,
            warnings = screenshotWarnings,
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
            warnings = renderResult.toScreenshotWarnings(),
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
    detectAnimations: Boolean = false,
    showSystemUi: Boolean = false,
    expandToContent: Boolean = false,
  ): Boolean {
    return try {
      val rootDir = Paths.get(outputFolderPath).toAbsolutePath().normalize()
      val treePath = rootDir.resolve(relativeComponentTreePath).normalize()
      if (!treePath.startsWith(rootDir)) {
        logger.log(Level.SEVERE, "Refusing to write component tree outside output folder: $treePath")
        return false
      }
      val (offsetX, offsetY) =
        if (!showSystemUi && expandToContent && renderResult != null) {
          expandedRootViewsOrigin(config, renderResult)
        } else {
          0 to 0
        }
      val tree =
        ComponentTreeExtractor.extract(
            previewId = previewId,
            methodFQN = methodFQN,
            displayName = displayName,
            config = config,
            renderResult = renderResult,
            viewInfos = viewInfos,
            detectAnimations = detectAnimations,
            showSystemUi = showSystemUi,
          )
          .offsetBy(offsetX, offsetY)
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

  internal fun saveImage(image: BufferedImage, outputFolderPath: String, relativeImagePath: String) {
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

  /**
   * Renders an animated [PreviewScreenshot] across multiple `PreviewAnimationClock` timestamps inside a single warm Layoutlib [RenderTask]
   * session per preview element, emitting frame PNGs and an `<resultId>.animation.json` manifest into [outputFolderPath].
   *
   * @see PreviewAnimationRenderer.renderAnimations
   */
  @JvmOverloads
  fun renderAnimations(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    options: AnimationRenderOptions = AnimationRenderOptions(),
  ): List<PreviewAnimationResult> = animationRenderer.renderAnimations(screenshot, outputFolderPath, options)

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
          transparentBackground = request.transparentBackground,
          expandToContent = request.expandToContent,
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
   *
   * Layouts with [ResolvedScreenshotLayout.expandSettings] are rendered with an expanded rendering mode only if the project's
   * `androidx.compose.ui:ui-tooling` supports it. Otherwise, they are rendered at the device size, and the returned
   * [ResolvedScreenshotLayout] has null [ResolvedScreenshotLayout.expandSettings]. Device spec parameters that the project's `ui-tooling`
   * doesn't support are reported with a warning in the [RenderResult] of each layout, and logged once.
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
    // Only read from the project's ui-tooling if a layout is expanded or uses ui-tooling device spec parameters.
    val supportedParameters by lazy(LazyThreadSafetyMode.NONE) { supportedDeviceSpecParameters }
    var warningLogged = false
    return layoutsProvider().map {
      val configuration = baseConfiguration.clone()
      request.configurationModifier(configuration)
      val layoutPreviewName = it.displayName?.let { displayName -> "$previewName [$displayName]" } ?: previewName
      val expandSettings = it.expandSettings?.takeIf { supportedParameters.supportsExpand() }
      val warning =
        unsupportedComposePreviewDeviceSpecWarning(it.deviceSpec, hasExpandSettings = it.expandSettings != null) { supportedParameters }
      if (warning != null && !warningLogged) {
        warningLogged = true
        logger.log(Level.WARNING, "Preview $previewName: $warning")
      }
      val renderResult =
        render(
          configuration,
          it.xmlLayout,
          renderImage,
          executeCallbacks = true,
          previewName = layoutPreviewName,
          allowShowDecorations = request.showDecorations,
          transparentBackground = request.transparentBackground,
          expandToContent = request.expandToContent,
          renderingMode = expandSettings?.axis?.renderingMode,
          warning = warning,
        )
      Triple(configuration, renderResult, it.copy(expandSettings = expandSettings))
    }
  }

  /**
   * Renders [xmlLayout]. When [renderImage] and [executeCallbacks] are `true`, layoutlib callbacks are executed between renders (see
   * [StaticPreviewCallbackRunner.renderExecutingCallbacks]). When [renderImage] is `false`, the layout is only inflated.
   *
   * @param renderingMode The [RenderingMode] to use for this render, or null to use the default one of the [renderService].
   * @param warning A warning to add to the [RenderLogger] of this render, or null if there is none. Warnings do not make the render fail.
   */
  private fun render(
    configuration: Configuration,
    xmlLayout: String,
    renderImage: Boolean,
    executeCallbacks: Boolean = false,
    previewName: String? = null,
    allowShowDecorations: Boolean = false,
    transparentBackground: Boolean = false,
    expandToContent: Boolean = false,
    renderingMode: RenderingMode? = null,
    warning: String? = null,
  ): RenderResult {
    awaitOutstandingDisposals()
    val disposable = Disposer.newCheckedDisposable()
    val logger = renderService.createLogger(project)
    warning?.let { logger.warning(null, it, null, null) }
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
        renderService
          .taskBuilder(module, configuration, logger)
          .apply { renderingMode?.let { withRenderingMode(it) } }
          .build(disposable)
          .get()
          ?: run {
            Disposer.dispose(disposable)
            return createRenderTaskErrorResult(logger)
          }
      val moduleClassLoader: ClassLoader = moduleClassLoaderManager.getShared(null).classLoader
      restoreWarmGlobalSnapshotObserver(moduleClassLoader)

      if (allowShowDecorations) {
        renderTask.setDecorations(true)
        if (renderingMode == null) {
          renderTask.setRenderingMode(SessionParams.RenderingMode.NORMAL)
        }
      } else if (expandToContent && renderingMode == null) {
        renderTask.setRenderingMode(SessionParams.RenderingMode.FULL_EXPAND)
      }

      // b/469819154: Release render and dispose Compose views after use to avoid accumulating heap memory usage
      // and leaving effects or animations from this preview running on the shared AndroidUiDispatcher.Main.
      // Note on Disposer order: RenderService.RenderTaskBuilder.build(disposable) only checks disposable.isDisposed() during task
      // construction and does not register renderTask.disposeAsync() on disposable, so renderTask's session is still alive when
      // releaseRender runs upon disposal of result -> disposable.
      Disposer.register(disposable) { releaseRender(renderTask, composeViewAdapters, moduleClassLoader) }

      val xmlFile = RenderXmlFileSnapshot(project, "layout.xml", ResourceFolderType.LAYOUT, xmlLayout)

      renderTask.setXmlFile(xmlFile)
      if (transparentBackground) {
        renderTask.setTransparentBackground()
      }

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
  internal fun releaseRender(renderTask: RenderTask, composeViewAdapters: Set<Any>, moduleClassLoader: ClassLoader) {
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

  internal fun createRenderTaskErrorResult(logger: RenderLogger, throwable: Throwable? = null): RenderResult =
    RenderResult.createRenderTaskErrorResult(
      module,
      { throw NotImplementedError("PsiFile supplier is not supported") },
      throwable,
      logger,
    )

  internal fun postProcessRenderedImage(
    config: Configuration,
    renderResult: RenderResult,
    expandToContent: Boolean = false,
    showDecorations: Boolean = false,
    expandSettings: ExpandSettings? = null,
  ): BufferedImage? {
    val imageCopy = renderResult.renderedImage.copy
    if (imageCopy == null && renderResult.renderResult.status != Result.Status.SUCCESS) return null

    val rawImage = imageCopy ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val image =
      if (!showDecorations && expandToContent) {
        cropExpandedImageToRootViews(config, renderResult, rawImage)
      } else {
        rawImage
      }
    val deviceShape = config.device?.screenShape(0.0, 0.0, Dimension(image.width, image.height))
    val screenShape = expandedScreenShape(deviceShape, expandSettings?.axis?.renderingMode, image.width, image.height) ?: return image
    return resizeImage(image, screenShape)
  }

  private fun expandedRootViewsOrigin(config: Configuration, renderResult: RenderResult): Pair<Int, Int> {
    val rootDims = renderResult.rootViewDimensions
    if (rootDims.width <= 0 || rootDims.height <= 0 || config.device == null) return 0 to 0
    val minLeft = renderResult.rootViews.minOfOrNull { it.left }?.coerceAtLeast(0) ?: 0
    val minTop = renderResult.rootViews.minOfOrNull { it.top }?.coerceAtLeast(0) ?: 0
    return minLeft to minTop
  }

  /**
   * Layoutlib's [SessionParams.RenderingMode.FULL_EXPAND] grows the canvas past the device screen without shrinking axes whose root view is
   * smaller than the screen (`max(screenSize, rootViewSize)`), and may over-expand `wrap_content` axes across multiple measure passes.
   * Crops the rendered image to [RenderResult.getRootViewDimensions], preserving any uniform downscaling applied to fit [RenderTask]'s max
   * image size.
   */
  private fun cropExpandedImageToRootViews(config: Configuration, renderResult: RenderResult, image: BufferedImage): BufferedImage {
    val rootDims = renderResult.rootViewDimensions
    if (rootDims.width <= 0 || rootDims.height <= 0) return image
    val device = config.device ?: return image
    val (minLeft, minTop) = expandedRootViewsOrigin(config, renderResult)
    val hardwareConfig =
      HardwareConfigHelper(device).apply { config.fullConfig.screenOrientationQualifier?.value?.let(::setOrientation) }.config
    val measuredWidth = maxOf(1, hardwareConfig.screenWidth, minLeft + rootDims.width)
    val measuredHeight = maxOf(1, hardwareConfig.screenHeight, minTop + rootDims.height)
    val scale =
      minOf(
        image.width.toDouble() / measuredWidth,
        image.height.toDouble() / measuredHeight,
        1.0,
      )
    val targetWidth = (rootDims.width * scale).roundToInt().coerceIn(1, image.width)
    val targetHeight = (rootDims.height * scale).roundToInt().coerceIn(1, image.height)
    val srcX = (minLeft * scale).roundToInt().coerceIn(0, image.width - targetWidth)
    val srcY = (minTop * scale).roundToInt().coerceIn(0, image.height - targetHeight)
    if (srcX == 0 && srcY == 0 && targetWidth == image.width && targetHeight == image.height) return image
    val cropped = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
    val g = cropped.createGraphics()
    try {
      g.composite = AlphaComposite.Src
      g.drawImage(image, 0, 0, targetWidth, targetHeight, srcX, srcY, srcX + targetWidth, srcY + targetHeight, null)
    } finally {
      g.dispose()
    }
    return cropped
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
internal fun rethrowIfFatalOrCancelled(t: Throwable) {
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
internal fun RenderResult.rethrowIfFatalVirtualMachineError() {
  val allThrowables =
    sequenceOf(renderResult.exception) + logger.messages.asSequence().map { it.throwable } + logger.brokenClasses.values.asSequence()
  val fatal = allThrowables.mapNotNull { it.findFatalVirtualMachineError() }.firstOrNull()
  if (fatal != null) {
    throw fatal
  }
}

/**
 * Disambiguates output file stems (`<baseResultId>_<suffix>`) and diagnostic preview names across the [ResolvedScreenshotLayout]s of a
 * single [PreviewScreenshot] (for example, `@PreviewParameter` elements with duplicate or blank display names).
 */
internal class LayoutResultIdAllocator(
  screenshot: PreviewScreenshot,
  resolvedLayouts: List<ResolvedScreenshotLayout>,
  private val baseResultId: String,
) {
  val basePreviewName: String =
    when {
      screenshot.previewId.isEmpty() -> screenshot.methodFQN
      screenshot.previewId.startsWith(screenshot.methodFQN) -> screenshot.previewId
      else -> "${screenshot.methodFQN} (${screenshot.previewId})"
    }

  private val duplicateNames: Set<String> = buildSet {
    val seenNames = mutableSetOf<String>()
    for (layout in resolvedLayouts) {
      val name = layout.displayName?.replace(Renderer.invalidCharsRegex, "_")?.trim('_')
      if (!name.isNullOrBlank() && !seenNames.add(name)) {
        add(name)
      }
    }
  }
  private val usedSuffixes = mutableSetOf<String>()

  /**
   * Allocates the filesystem-safe `<baseResultId>_<suffix>` stem for [layout] using [nextIndex] on demand (preserving lazy `Sequence`
   * evaluation order in [Renderer.renderScreenshotElement]).
   */
  fun nextResultId(layout: ResolvedScreenshotLayout, nextIndex: () -> Int): String {
    val index = nextIndex()
    // If a custom display name is provided (e.g. from PreviewParameterProvider.getDisplayName),
    // sanitize it for filesystem paths by replacing whitespace and reserved characters with underscores.
    // If duplicate, append the preview index to disambiguate. If blank or not provided, fall back to numerical index.
    val sanitizedDisplayName = layout.displayName?.replace(Renderer.invalidCharsRegex, "_")?.trim('_')
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
    return "${baseResultId}_$suffix"
  }

  fun previewNameFor(layout: ResolvedScreenshotLayout): String = layout.displayName?.let { "$basePreviewName [$it]" } ?: basePreviewName
}

/** The [RenderingMode] that grows the render area along this axis, to fit the scrollable content of an expanded preview. */
internal val ExpandAxis.renderingMode: RenderingMode
  get() =
    when (this) {
      ExpandAxis.vertical -> RenderingMode.V_SCROLL
      ExpandAxis.horizontal -> RenderingMode.H_SCROLL
    }
