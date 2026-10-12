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
import com.android.tools.rendering.compose.ComposeViewInfo
import com.android.tools.rendering.compose.parseViewInfo
import com.android.tools.rendering.parsers.RenderXmlFileSnapshot
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.Shape
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
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
  val baseConfiguration: Configuration,
  private val moduleClassLoaderManager: StandaloneModuleClassLoaderManager,
) : Closeable {
  private val logger = Logger.getLogger(Renderer::class.java.name)
  private val discoveryEngine by lazy { PreviewDiscoveryEngine(module) }
  private val previewValidator = PreviewValidator()

  companion object {
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
   * @param renderImage Whether to render and save the bitmap image.
   * @return A list of [PreviewScreenshotResult]s, each detailing the outcome of a single render operation, including the output path and
   *   any potential errors.
   */
  @JvmOverloads
  fun render(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    includeComponentTree: Boolean = false,
    renderImage: Boolean = true,
  ): List<PreviewScreenshotResult> {
    // If the screenshot is non-Compose or its preview and method parameters have already been resolved/specified,
    // bypass bytecode preview discovery and render it directly.
    if (screenshot !is ComposeScreenshot || screenshot.previewParams.isNotEmpty() || screenshot.methodParams.isNotEmpty()) {
      return renderResolvedScreenshot(screenshot, outputFolderPath, includeComponentTree, renderImage)
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
      renderScreenshotElement(screenshot, outputFolderPath, packagePath, baseResultId, includeComponentTree, renderImage) { imageCounter++ }
    }
  }

  private fun renderScreenshotElement(
    screenshot: PreviewScreenshot,
    outputFolderPath: String,
    packagePath: String,
    baseResultId: String,
    includeComponentTree: Boolean,
    renderImage: Boolean,
    nextImageIndex: () -> Int,
  ): List<PreviewScreenshotResult> {
    // Creating the preview element can fail for reasons specific to a single screenshot (e.g. a class that cannot be loaded). Report
    // those as an error for this screenshot rather than letting the exception abort the whole render call.
    val (previewElement, resolvedLayouts) =
      try {
        val element = screenshot.toPreviewElement(module)
        element to element.resolveLayouts().toList()
      } catch (t: Throwable) {
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
      )

    val usedSuffixes = mutableSetOf<String>()
    return renderResolvedLayouts(renderRequest, renderImage)
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

  fun render(request: RenderRequest, renderImage: Boolean = true): Sequence<Pair<Configuration, RenderResult>> {
    return request.xmlLayoutsProvider().map {
      val configuration = baseConfiguration.clone()
      request.configurationModifier(configuration)
      configuration to render(configuration, it, renderImage)
    }
  }

  /**
   * Renders the given [RenderRequest] while preserving each layout's metadata (such as custom parameter display names in
   * [ResolvedScreenshotLayout]).
   *
   * If [RenderRequest.resolvedLayoutsProvider] is null, falls back to rendering each layout from [RenderRequest.xmlLayoutsProvider] wrapped
   * in a [ResolvedScreenshotLayout] with null display name.
   */
  fun renderResolvedLayouts(
    request: RenderRequest,
    renderImage: Boolean = true,
  ): Sequence<Triple<Configuration, RenderResult, ResolvedScreenshotLayout>> {
    val layoutsProvider =
      request.resolvedLayoutsProvider
        ?: {
          request.xmlLayoutsProvider().map { ResolvedScreenshotLayout(it) }
        }
    return layoutsProvider().map {
      val configuration = baseConfiguration.clone()
      request.configurationModifier(configuration)
      Triple(configuration, render(configuration, it.xmlLayout, renderImage), it)
    }
  }

  private fun render(configuration: Configuration, xmlLayout: String, renderImage: Boolean): RenderResult {
    val disposable = Disposer.newCheckedDisposable()
    val logger = RenderLogger()
    return try {
      val renderTask =
        renderService.taskBuilder(module, configuration, logger).build(disposable).get()
          ?: run {
            Disposer.dispose(disposable)
            return createRenderTaskErrorResult(logger)
          }

      // b/469819154: Release render after use to avoid accumulating heap memory usage.
      Disposer.register(disposable) { renderTask.releaseRender() }

      val xmlFile = RenderXmlFileSnapshot(project, "layout.xml", ResourceFolderType.LAYOUT, xmlLayout)

      renderTask.setXmlFile(xmlFile)

      val result =
        if (renderImage) {
          renderTask.render().get(100, TimeUnit.SECONDS)
        } else {
          renderTask.inflate().get(100, TimeUnit.SECONDS) ?: createRenderTaskErrorResult(logger)
        }
      Disposer.register(result, disposable)
      result
    } catch (t: Throwable) {
      Disposer.dispose(disposable)
      createRenderTaskErrorResult(logger, t)
    }
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
    moduleClassLoaderManager.close()
    Disposer.dispose(project)
  }
}
