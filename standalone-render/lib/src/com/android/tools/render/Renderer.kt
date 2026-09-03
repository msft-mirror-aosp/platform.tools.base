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
import com.android.resources.ResourceFolderType
import com.android.sdklib.devices.screenShape
import com.android.tools.configurations.Configuration
import com.android.tools.preview.applyTo
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.render.common.toScreenshotError
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.render.discovery.PreviewDiscoveryEngine
import com.android.tools.render.validation.PreviewValidator
import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.parsers.RenderXmlFileSnapshot
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO

/** A renderer that can handle [RenderRequest]. */
class Renderer(
  val project: Project,
  val module: StandaloneRenderModelModule,
  private val renderService: RenderService,
  private val baseConfiguration: Configuration,
  private val moduleClassLoaderManager: StandaloneModuleClassLoaderManager,
) : Closeable {
  private val logger = Logger.getLogger(Renderer::class.java.name)
  private val discoveryEngine by lazy { PreviewDiscoveryEngine(module) }
  private val previewValidator = PreviewValidator()

  /**
   * Renders a given [PreviewScreenshot] and saves the output as PNG files.
   *
   * Discovers all previews declared on the screenshot's method (including multi-previews), validates each preview's parameters, and renders
   * them into PNG images.
   *
   * @param screenshot The metadata of the Jetpack Compose `@Preview` to render.
   * @param outputFolderPath The root directory where the resulting PNG images will be saved.
   * @return A list of [PreviewScreenshotResult]s, each detailing the outcome of a single render operation, including the output path and
   *   any potential errors.
   */
  fun render(screenshot: PreviewScreenshot, outputFolderPath: String): List<PreviewScreenshotResult> {
    // If the screenshot is non-Compose or its preview and method parameters have already been resolved/specified,
    // bypass bytecode preview discovery and render it directly.
    if (screenshot !is ComposeScreenshot || screenshot.previewParams.isNotEmpty() || screenshot.methodParams.isNotEmpty()) {
      return renderResolvedScreenshot(screenshot, outputFolderPath)
    }

    val packagePath = screenshot.methodFQN.substringBeforeLast(".").replace(".", File.separator)
    val baseResultId = screenshot.previewId.substringAfterLast(".")
    var imageCounter = 0

    return discoveryEngine.discoverAllPreviews(screenshot.methodFQN, screenshot.previewId).flatMap { method ->
      if (method.methodValidationResult.hasErrors) {
        val defaultRelativeImagePath = packagePath + File.separator + "${baseResultId}_${imageCounter++}.png"
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
            val defaultRelativeImagePath = packagePath + File.separator + "${baseResultId}_${imageCounter++}.png"
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
            renderScreenshotElement(currentScreenshot, outputFolderPath, packagePath, baseResultId) { imageCounter++ }
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
  private fun renderResolvedScreenshot(screenshot: PreviewScreenshot, outputFolderPath: String): List<PreviewScreenshotResult> {
    val packagePath = screenshot.methodFQN.substringBeforeLast(".").replace(".", File.separator)
    val baseResultId = screenshot.previewId.substringAfterLast(".")
    val validationResult = previewValidator.validate(screenshot)
    return if (validationResult.hasErrors) {
      val defaultRelativeImagePath = packagePath + File.separator + "${baseResultId}_0.png"
      listOf(
        PreviewScreenshotResult(screenshot.previewId, screenshot.methodFQN, defaultRelativeImagePath, validationResult.toScreenshotError())
      )
    } else {
      if (validationResult.hasWarnings) {
        val warningMessages = validationResult.warnings.joinToString("; ") { it.message }
        logger.log(Level.WARNING, "Preview parameter validation warning for ${screenshot.methodFQN}: $warningMessages")
      }
      var imageCounter = 0
      renderScreenshotElement(screenshot, outputFolderPath, packagePath, baseResultId) { imageCounter++ }
    }
  }

  private fun renderScreenshotElement(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    packagePath: String,
    baseResultId: String,
    nextImageIndex: () -> Int,
  ): List<PreviewScreenshotResult> {
    val previewElement = screenshot.toPreviewElement(module)
    val renderRequest =
      RenderRequest(configurationModifier = previewElement::applyTo, xmlLayoutsProvider = { previewElement.resolveXmlLayouts() })

    return render(renderRequest)
      .map { (config, renderResult) ->
        val index = nextImageIndex()
        val resultId = "${baseResultId}_$index"
        val imageName = "$resultId.png"
        val relativeImagePath = packagePath + File.separator + imageName
        try {
          val imageRendered = postProcessRenderedImage(config, renderResult)
          if (imageRendered != null) {
            saveImage(imageRendered, outputFolderPath, relativeImagePath)
          }

          val screenshotError = renderResult.toScreenshotError(imageRendered)
          PreviewScreenshotResult(screenshot.previewId, screenshot.methodFQN, relativeImagePath, screenshotError)
        } catch (t: Throwable) {
          PreviewScreenshotResult(screenshot.previewId, screenshot.methodFQN, relativeImagePath, t.toScreenshotError())
        } finally {
          Disposer.dispose(renderResult)
        }
      }
      .toList()
  }

  private fun saveImage(image: BufferedImage, outputFolderPath: String, relativeImagePath: String) {
    val imagePath = Paths.get(outputFolderPath, relativeImagePath)
    try {
      Files.createDirectories(imagePath.parent)
      val imgFile = imagePath.toFile()
      imgFile.createNewFile()
      ImageIO.write(image, "png", imgFile)
    } catch (e: IOException) {
      logger.log(Level.SEVERE, "Failed to write image to $imagePath", e)
    }
  }

  fun render(request: RenderRequest): Sequence<Pair<Configuration, RenderResult>> {
    return request.xmlLayoutsProvider().map {
      val configuration = baseConfiguration.clone()
      request.configurationModifier(configuration)
      configuration to render(configuration, it)
    }
  }

  private fun render(configuration: Configuration, xmlLayout: String): RenderResult {
    val disposable = Disposer.newCheckedDisposable()
    val logger = RenderLogger()
    return try {
      val renderTask =
        renderService.taskBuilder(module, configuration, logger).build(disposable).get()
          ?: run {
            Disposer.dispose(disposable)
            return RenderResult.createRenderTaskErrorResult(
              module,
              { throw NotImplementedError("PsiFile supplier is not supported") },
              null,
              logger,
            )
          }

      // b/469819154: Release render after use to avoid accumulating heap memory usage.
      Disposer.register(disposable) { renderTask.releaseRender() }

      val xmlFile = RenderXmlFileSnapshot(project, "layout.xml", ResourceFolderType.LAYOUT, xmlLayout)

      renderTask.setXmlFile(xmlFile)

      val result = renderTask.render().get(100, TimeUnit.SECONDS)
      Disposer.register(result, disposable)
      result
    } catch (t: Throwable) {
      Disposer.dispose(disposable)
      RenderResult.createRenderTaskErrorResult(module, { throw NotImplementedError("PsiFile supplier is not supported") }, t, logger)
    }
  }

  private fun postProcessRenderedImage(config: Configuration, renderResult: RenderResult): BufferedImage? {
    val imageCopy = renderResult.renderedImage.copy
    if (imageCopy == null && renderResult.renderResult.status != com.android.ide.common.rendering.api.Result.Status.SUCCESS) return null

    val image = imageCopy ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    val screenShape = config.device?.screenShape(0.0, 0.0, Dimension(image.width, image.height)) ?: return image
    return resizeImage(image, screenShape)
  }

  private fun resizeImage(image: BufferedImage, shape: java.awt.Shape): BufferedImage {
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
    moduleClassLoaderManager.close()
    Disposer.dispose(project)
  }
}
