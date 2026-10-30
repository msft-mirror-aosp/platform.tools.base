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

import com.android.ide.common.rendering.api.Result
import com.android.resources.ResourceFolderType
import com.android.tools.configurations.Configuration
import com.android.tools.preview.applyTo
import com.android.tools.render.animation.AnimationPreviewRunner
import com.android.tools.render.animation.AnimationRenderOptions
import com.android.tools.render.animation.PreviewAnimationManifest
import com.android.tools.render.animation.PreviewAnimationResult
import com.android.tools.render.animation.writeAnimationManifestToJson
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.ScreenshotError
import com.android.tools.render.common.toScreenshotError
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.RenderTask
import com.android.tools.rendering.parsers.RenderXmlFileSnapshot
import com.intellij.openapi.util.Disposer
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Helper owned by [Renderer] that executes multi-frame `PreviewAnimationClock` rendering sessions.
 *
 * Extracted from [Renderer] to keep static screenshot/layout rendering focused while sharing [Renderer]'s warm [RenderService],
 * [StandaloneModuleClassLoaderManager], [StaticPreviewCallbackRunner], and output path sanitization/image-saving utilities.
 */
internal class PreviewAnimationRenderer(private val renderer: Renderer) {
  private val logger = Logger.getLogger(PreviewAnimationRenderer::class.java.name)

  /**
   * Discovers and validates all previews targeted by [screenshot], then steps `PreviewAnimationClock` inside a single warm [RenderTask]
   * session per resolved preview layout, writing frame PNGs and an `<resultId>.animation.json` manifest under [outputFolderPath].
   */
  fun renderAnimations(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    options: AnimationRenderOptions = AnimationRenderOptions(),
  ): List<PreviewAnimationResult> {
    val packagePath = renderer.sanitizePackagePath(screenshot.methodFQN)
    val baseResultId = renderer.sanitizeBaseResultId(screenshot.previewId)
    var previewCounter = 0

    if (screenshot !is ComposeScreenshot || screenshot.previewParams.isNotEmpty() || screenshot.methodParams.isNotEmpty()) {
      val validationResult = renderer.previewValidator.validate(screenshot)
      if (validationResult.hasErrors) {
        return listOf(
          PreviewAnimationResult(
            previewId = screenshot.previewId,
            methodFQN = screenshot.methodFQN,
            manifestPath = "",
            framePaths = emptyList(),
            error = validationResult.toScreenshotError(),
          )
        )
      }
      return renderAnimatedScreenshotElement(screenshot, outputFolderPath, packagePath, baseResultId, options) { previewCounter++ }
    }

    return renderer.discoveryEngine.discoverAllPreviews(screenshot.methodFQN, screenshot.previewId).flatMap { method ->
      if (method.methodValidationResult.hasErrors) {
        previewCounter++
        listOf(
          PreviewAnimationResult(
            previewId = screenshot.previewId,
            methodFQN = screenshot.methodFQN,
            manifestPath = "",
            framePaths = emptyList(),
            error = method.methodValidationResult.toScreenshotError(),
          )
        )
      } else {
        method.previews.flatMap { currentScreenshot ->
          val validationResult = renderer.previewValidator.validate(currentScreenshot)
          if (validationResult.hasErrors) {
            previewCounter++
            listOf(
              PreviewAnimationResult(
                previewId = currentScreenshot.previewId,
                methodFQN = currentScreenshot.methodFQN,
                manifestPath = "",
                framePaths = emptyList(),
                error = validationResult.toScreenshotError(),
              )
            )
          } else {
            renderAnimatedScreenshotElement(currentScreenshot, outputFolderPath, packagePath, baseResultId, options) { previewCounter++ }
          }
        }
      }
    }
  }

  private fun renderAnimatedScreenshotElement(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    packagePath: String,
    baseResultId: String,
    options: AnimationRenderOptions,
    nextPreviewIndex: () -> Int,
  ): List<PreviewAnimationResult> {
    val (previewElement, resolvedLayouts) =
      try {
        val element = screenshot.toPreviewElement(renderer.module)
        element to element.resolveLayouts().toList()
      } catch (t: Throwable) {
        rethrowIfFatalOrCancelled(t)
        nextPreviewIndex()
        return listOf(
          PreviewAnimationResult(
            previewId = screenshot.previewId,
            methodFQN = screenshot.methodFQN,
            manifestPath = "",
            framePaths = emptyList(),
            error = t.toScreenshotError(),
          )
        )
      }

    val idAllocator = LayoutResultIdAllocator(screenshot, resolvedLayouts, baseResultId)

    return resolvedLayouts.map { layout ->
      val resultId = idAllocator.nextResultId(layout, nextPreviewIndex)
      val relativeManifestPath = packagePath + File.separator + "$resultId.animation.json"
      val layoutPreviewName = idAllocator.previewNameFor(layout)

      val configuration = renderer.baseConfiguration.clone()
      previewElement.applyTo(configuration)
      val animatedXmlLayout = AnimationPreviewRunner.injectAnimationClockStartTime(layout.xmlLayout, 0L)

      renderAnimatedLayoutSession(
        screenshot = screenshot,
        displayName = layout.displayName,
        previewName = layoutPreviewName,
        configuration = configuration,
        xmlLayout = animatedXmlLayout,
        outputFolderPath = outputFolderPath,
        packagePath = packagePath,
        resultId = resultId,
        relativeManifestPath = relativeManifestPath,
        options = options,
      )
    }
  }

  private fun renderAnimatedLayoutSession(
    screenshot: PreviewScreenshot,
    displayName: String?,
    previewName: String,
    configuration: Configuration,
    xmlLayout: String,
    outputFolderPath: String,
    packagePath: String,
    resultId: String,
    relativeManifestPath: String,
    options: AnimationRenderOptions,
  ): PreviewAnimationResult {
    renderer.awaitOutstandingDisposals()
    val disposable = Disposer.newCheckedDisposable()
    val renderLogger = renderer.renderService.createLogger(renderer.project)
    val composeViewAdapters: MutableSet<Any> = ConcurrentHashMap.newKeySet()
    val recordAdapters: (RenderResult) -> Unit = { composeViewAdapters.addAll(findComposeViewAdapters(it.rootViews)) }
    var initialResult: RenderResult? = null
    return try {
      var poisonedError: Throwable? = null
      if (
        !renderer.callbackRunner.ensureRenderThreadAvailable(
          RenderService.getRenderAsyncActionExecutor()::runAsyncAction,
          reportError = { message, throwable ->
            poisonedError = throwable
            renderLogger.error(null, message, throwable, null, null)
          },
        )
      ) {
        val errResult = renderer.createRenderTaskErrorResult(renderLogger, poisonedError)
        val error =
          try {
            errResult.toScreenshotError(null, expectImage = true)
          } finally {
            Disposer.dispose(errResult)
          }
        return PreviewAnimationResult(
          previewId = screenshot.previewId,
          methodFQN = screenshot.methodFQN,
          manifestPath = "",
          framePaths = emptyList(),
          error = error,
          displayName = displayName,
        )
      }

      val renderTask =
        renderer.renderService.taskBuilder(renderer.module, configuration, renderLogger).build(disposable).get()
          ?: run {
            val errResult = renderer.createRenderTaskErrorResult(renderLogger)
            val error =
              try {
                errResult.toScreenshotError(null, expectImage = true)
              } finally {
                Disposer.dispose(errResult)
              }
            return PreviewAnimationResult(
              previewId = screenshot.previewId,
              methodFQN = screenshot.methodFQN,
              manifestPath = "",
              framePaths = emptyList(),
              error = error,
              displayName = displayName,
            )
          }
      val moduleClassLoader: ClassLoader = renderer.moduleClassLoaderManager.getShared(null).classLoader
      restoreWarmGlobalSnapshotObserver(moduleClassLoader)
      Disposer.register(disposable) { renderer.releaseRender(renderTask, composeViewAdapters, moduleClassLoader) }

      val xmlFile = RenderXmlFileSnapshot(renderer.project, "layout.xml", ResourceFolderType.LAYOUT, xmlLayout)
      renderTask.setXmlFile(xmlFile)

      val settledInitial =
        renderer.callbackRunner.renderExecutingCallbacks(
          RenderTaskPreviewSession(renderTask, renderLogger, renderer::createRenderTaskErrorResult, moduleClassLoader),
          previewName,
          recordAdapters,
        )
      initialResult = settledInitial
      settledInitial.rethrowIfFatalVirtualMachineError()
      if (settledInitial.renderResult.status != Result.Status.SUCCESS) {
        return PreviewAnimationResult(
          previewId = screenshot.previewId,
          methodFQN = screenshot.methodFQN,
          manifestPath = "",
          framePaths = emptyList(),
          error = settledInitial.toScreenshotError(null, expectImage = true),
          displayName = displayName,
        )
      }

      val savedFramePaths = mutableListOf<String>()
      var firstFrameError: ScreenshotError? = null
      val manifest =
        AnimationPreviewRunner.stepAndCaptureAnimation(
          previewId = screenshot.previewId,
          methodFQN = screenshot.methodFQN,
          displayName = displayName,
          renderTask = renderTask,
          initialResult = settledInitial,
          options = options,
          postProcessImage = { frameResult ->
            recordAdapters(frameResult)
            frameResult.rethrowIfFatalVirtualMachineError()
            if (frameResult.renderResult.status != Result.Status.SUCCESS && firstFrameError == null) {
              firstFrameError = frameResult.toScreenshotError(null, expectImage = true)
            }
            renderer.postProcessRenderedImage(configuration, frameResult)
          },
          saveFrameImage = { frameIndex, frameImage ->
            val relFramePath = packagePath + File.separator + "${resultId}_frame_${String.format(Locale.US, "%02d", frameIndex)}.png"
            renderer.saveImage(frameImage, outputFolderPath, relFramePath)
            savedFramePaths.add(relFramePath)
            relFramePath
          },
          renderLogger = renderLogger,
        )

      val savedManifest = saveAnimationManifest(manifest, outputFolderPath, relativeManifestPath)
      PreviewAnimationResult(
        previewId = screenshot.previewId,
        methodFQN = screenshot.methodFQN,
        manifestPath = if (savedManifest) relativeManifestPath else "",
        framePaths = savedFramePaths,
        manifest = manifest,
        error = firstFrameError ?: if (savedFramePaths.isEmpty()) settledInitial.toScreenshotError(null, expectImage = true) else null,
        displayName = displayName,
      )
    } catch (t: Throwable) {
      rethrowIfFatalOrCancelled(t)
      t.findTimeoutException()?.let { timeout ->
        renderer.callbackRunner.recordTimeout(
          previewName,
          extractTimeoutBudgetMs(timeout, TimeUnit.SECONDS.toMillis(RENDER_TIMEOUT_SECONDS)),
        )
      }
      PreviewAnimationResult(
        previewId = screenshot.previewId,
        methodFQN = screenshot.methodFQN,
        manifestPath = "",
        framePaths = emptyList(),
        error = t.toScreenshotError(),
        displayName = displayName,
      )
    } finally {
      try {
        initialResult?.let { Disposer.dispose(it) }
      } finally {
        if (!disposable.isDisposed) {
          Disposer.dispose(disposable)
        }
      }
    }
  }

  private fun saveAnimationManifest(
    manifest: PreviewAnimationManifest,
    outputFolderPath: String,
    relativeManifestPath: String,
  ): Boolean {
    return try {
      val rootDir = Paths.get(outputFolderPath).toAbsolutePath().normalize()
      val manifestPath = rootDir.resolve(relativeManifestPath).normalize()
      if (!manifestPath.startsWith(rootDir)) {
        logger.log(Level.SEVERE, "Refusing to write animation manifest outside output folder: $manifestPath")
        return false
      }
      manifestPath.parent?.let { Files.createDirectories(it) }
      Files.newBufferedWriter(manifestPath, StandardCharsets.UTF_8).use { writer ->
        writeAnimationManifestToJson(writer, manifest)
      }
      true
    } catch (e: Exception) {
      logger.log(Level.SEVERE, "Failed to write animation manifest to $relativeManifestPath", e)
      false
    }
  }
}
