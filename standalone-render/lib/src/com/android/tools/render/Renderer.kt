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
import com.android.tools.render.common.BrokenClass
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.render.common.RenderProblem
import com.android.tools.render.common.ScreenshotError
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

  /**
   * Renders a given [PreviewScreenshot] and saves the output as PNG files.
   *
   * For a single [PreviewScreenshot] that may have multiple configurations (e.g., different devices or themes), this function generates a
   * corresponding rendered image for each one.
   *
   * @param screenshot The metadata of the Jetpack Compose `@Preview` to render.
   * @param outputFolderPath The root directory where the resulting PNG images will be saved.
   * @return A list of [PreviewScreenshotResult]s, each detailing the outcome of a single render operation, including the output path and
   *   any potential errors.
   */
  fun render(screenshot: PreviewScreenshot, outputFolderPath: String): List<PreviewScreenshotResult> {
    val (effectiveScreenshot, validationError) = resolvePreviewParameters(screenshot)
    val previewId = effectiveScreenshot.previewId
    val methodFQN = effectiveScreenshot.methodFQN
    val packagePath = methodFQN.substringBeforeLast(".").replace(".", File.separator)
    val baseResultId = previewId.substringAfterLast(".")

    if (validationError != null) {
      val defaultRelativeImagePath = packagePath + File.separator + "${baseResultId}_0.png"
      return listOf(PreviewScreenshotResult(previewId, methodFQN, defaultRelativeImagePath, validationError))
    }

    val previewElement = effectiveScreenshot.toPreviewElement(module)
    val renderRequest =
      RenderRequest(configurationModifier = previewElement::applyTo, xmlLayoutsProvider = { previewElement.resolveXmlLayouts() })

    return render(renderRequest)
      .withIndex()
      .map { (index, value) ->
        val (config, renderResult) = value
        val resultId = "${baseResultId}_$index"
        val imageName = "$resultId.png"
        val relativeImagePath = packagePath + File.separator + imageName
        val screenshotResult =
          try {
            val imageRendered = postProcessRenderedImage(config, renderResult)
            if (imageRendered != null) {
              val imagePath = Paths.get(outputFolderPath, relativeImagePath)
              try {
                Files.createDirectories(imagePath.parent)
                val imgFile = imagePath.toFile()
                imgFile.createNewFile()
                ImageIO.write(imageRendered, "png", imgFile)
              } catch (e: IOException) {
                logger.log(Level.SEVERE, "Failed to write image to $imagePath", e)
              }
            }

            val screenshotError = extractError(renderResult, imageRendered)
            PreviewScreenshotResult(previewId, methodFQN, relativeImagePath, screenshotError)
          } catch (t: Throwable) {
            PreviewScreenshotResult(previewId, methodFQN, relativeImagePath, ScreenshotError(t))
          } finally {
            Disposer.dispose(renderResult)
          }
        screenshotResult
      }
      .toList()
  }

  /**
   * Resolves and populates preview parameters for the given [screenshot] if they were not explicitly provided, and validates the resulting
   * configuration parameters.
   *
   * When callers (such as CLI tools) submit render requests specifying only the method FQN and preview ID, this method utilizes
   * [PreviewDiscoveryEngine] to inspect the compiled bytecode, discover the target `@Preview` annotation, and populate missing
   * configuration values before Layoutlib rendering.
   *
   * @param screenshot The input preview screenshot request.
   * @return A pair containing the updated [PreviewScreenshot] and an optional [ScreenshotError] if validation fails.
   */
  private fun resolvePreviewParameters(screenshot: PreviewScreenshot): Pair<PreviewScreenshot, ScreenshotError?> {
    if (screenshot !is ComposeScreenshot) {
      return screenshot to null
    }

    val effectiveScreenshot =
      if (screenshot.previewParams.isEmpty()) {
        val discoveryEngine = PreviewDiscoveryEngine(module)
        val discovered = discoveryEngine.discover(screenshot.methodFQN, screenshot.previewId)
        if (discovered != null) {
          screenshot.copy(previewParams = discovered.previewParams)
        } else {
          screenshot
        }
      } else {
        screenshot
      }

    if (effectiveScreenshot.previewParams.isNotEmpty()) {
      val validationResult = PreviewValidator().validateParams(effectiveScreenshot.previewParams)
      if (validationResult.hasErrors) {
        val errorMessages = validationResult.errors.joinToString("; ") { it.message }
        val screenshotError =
          ScreenshotError(
            status = "VALIDATION_ERROR",
            message = errorMessages,
            stackTrace = "",
            problems = validationResult.errors.map { RenderProblem(it.message, null) },
            brokenClasses = emptyList(),
            missingClasses = emptyList(),
          )
        return effectiveScreenshot to screenshotError
      }
      if (validationResult.hasWarnings) {
        val warningMessages = validationResult.warnings.joinToString("; ") { it.message }
        logger.log(Level.WARNING, "Preview parameter validation warning for ${effectiveScreenshot.methodFQN}: $warningMessages")
      }
    }

    return effectiveScreenshot to null
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

  private fun extractError(renderResult: RenderResult, imageRendered: BufferedImage?): ScreenshotError? {
    if (
      renderResult.renderResult.status == com.android.ide.common.rendering.api.Result.Status.SUCCESS &&
        !renderResult.logger.hasErrors() &&
        imageRendered != null
    ) {
      return null
    }
    val errorMessage =
      when {
        imageRendered == null && renderResult.renderResult.status == Result.Status.SUCCESS ->
          "Nothing to render in Preview. Cannot generate image"
        else -> renderResult.renderResult.errorMessage ?: ""
      }
    return ScreenshotError(
      renderResult.renderResult.status.name,
      errorMessage,
      renderResult.renderResult.exception?.stackTraceToString() ?: "",
      renderResult.logger.messages.map { RenderProblem(it.html, it.throwable?.stackTraceToString()) },
      renderResult.logger.brokenClasses.map { BrokenClass(it.key, it.value.stackTraceToString()) },
      renderResult.logger.missingClasses.toList(),
    )
  }

  override fun close() {
    moduleClassLoaderManager.close()
    Disposer.dispose(project)
  }
}
