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

import com.android.annotations.VisibleForTesting
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.ide.common.rendering.api.Result
import com.android.ide.common.rendering.api.SessionParams
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.ResourceFolderType
import com.android.resources.ResourceType
import com.android.sdklib.AndroidVersion
import com.android.sdklib.devices.screenShape
import com.android.tools.configurations.Configuration
import com.android.tools.module.ModuleKey
import com.android.tools.preview.applyTo
import com.android.tools.render.common.BrokenClass
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.PreviewScreenshotResult
import com.android.tools.render.common.RenderProblem
import com.android.tools.render.common.ScreenshotError
import com.android.tools.render.configuration.StandaloneConfigurationModelModule
import com.android.tools.render.configuration.StandaloneConfigurationSettings
import com.android.tools.render.environment.StandaloneEnvironmentContext
import com.android.tools.render.framework.IJFramework
import com.android.tools.rendering.RenderLogger
import com.android.tools.rendering.RenderResult
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.classloading.ModuleClassLoaderManager
import com.android.tools.rendering.parsers.RenderXmlFileSnapshot
import com.android.tools.res.LocalResourceRepository
import com.android.tools.res.SingleRepoResourceRepositoryManager
import com.android.tools.res.apk.ApkResourceRepository
import com.android.tools.res.ids.ResourceIdManager
import com.android.tools.res.ids.apk.ApkResourceIdManager
import com.android.tools.sdk.AndroidPlatform
import com.android.tools.sdk.AndroidSdkData
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ReadAndWriteScope
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.ReadResult
import com.intellij.openapi.application.ReadWriteActionSupport
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ThrowableComputable
import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.ImageIO

/** A renderer that can handle [RenderRequest]. */
class Renderer(
  fontsPath: String?,
  resourceApkPath: String?,
  namespace: String,
  classPath: List<String>,
  projectClassPath: List<String>,
  layoutlibPath: String,
  resourceDirs: List<String> = emptyList(),
  rClassJars: List<String> = emptyList(),
) : Closeable {
  private val logger = Logger.getLogger(Renderer::class.java.name)

  private val project: Project = IJFramework.createProject()
  private val baseConfiguration: Configuration
  val module: StandaloneRenderModelModule
  private val renderService: RenderService
  private val moduleClassLoaderManager: StandaloneModuleClassLoaderManager

  init {
    TimeZone.getDefault()

    moduleClassLoaderManager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)

    val apkIdManager = ApkResourceIdManager()
    resourceApkPath?.let { apkIdManager.loadApkResources(it) }

    val baseIdManager =
      com.android.tools.res.ids.ResourceIdManagerBase(com.android.tools.res.ids.ResourceIdManagerModelModule.noNamespacingApp(true), true)

    val resourceIdManager = StandaloneResourceIdManager(apkIdManager, baseIdManager)

    val rClassPackages = mutableSetOf<String>()
    // Load R classes from rClassJars into the ID manager
    resourceIdManager.resetCompiledIds { parser ->
      try {
        val classLoader = this::class.java.classLoader
        parser.parseUsingReflection(classLoader.loadClass("android.R"))
        parser.parseUsingReflection(classLoader.loadClass("com.android.internal.R"))
      } catch (e: Throwable) {
        logger.log(Level.WARNING, "Could not load Android framework R classes", e)
      }

      for (path in rClassJars) {
        val file = File(path)
        if (file.isDirectory) {
          moduleClassLoaderManager.getShared(this::class.java.classLoader).use { ref ->
            resolveClassesFromDirectory(file, ref.classLoader, parser, apkIdManager, rClassPackages, logger)
          }
        } else if (file.isFile) {
          moduleClassLoaderManager.getShared(this::class.java.classLoader).use { ref ->
            resolveClassesFromJar(file, path, ref.classLoader, parser, apkIdManager, rClassPackages, logger)
          }
        }
      }
    }
    val apkResourcesRepo =
      if (resourceApkPath != null) ApkResourceRepository(resourceApkPath, resourceIdManager::findById)
      else LocalResourceRepository.EmptyRepository<Path>(ResourceNamespace.RES_AUTO)

    val resourcesRepo =
      if (resourceDirs.isNotEmpty()) {
        StandaloneResourceRepository(resourceDirs, apkResourcesRepo)
      } else {
        apkResourcesRepo
      }

    val androidVersion = AndroidVersion(33)
    val androidTarget = StandaloneAndroidTarget(androidVersion)
    val androidModuleInfo = StandaloneModuleInfo(namespace, androidVersion)

    val androidSdkData = AndroidSdkData.getSdkDataWithoutValidityCheck(File(""))

    val androidPlatform = AndroidPlatform(androidSdkData, androidTarget)

    val resourceRepositoryManager = SingleRepoResourceRepositoryManager(resourcesRepo)

    IJFramework.registerService(ModuleClassLoaderManager::class.java, moduleClassLoaderManager, project)

    IJFramework.registerService(
      ReadWriteActionSupport::class.java,
      object : ReadWriteActionSupport {
        override fun committedDocumentsConstraint(project: Project): ReadConstraint = ReadConstraint.withDocumentsCommitted(project)

        override fun <X, E : Throwable> computeCancellable(action: ThrowableComputable<X, E>): X = ReadAction.compute(action)

        override suspend fun <X> executeReadAction(
          constraints: List<ReadConstraint>,
          undispatched: Boolean,
          blocking: Boolean,
          action: () -> X,
        ): X {
          throw UnsupportedOperationException()
        }

        override suspend fun <X> executeReadAndWriteAction(
          constraints: Array<out ReadConstraint>,
          runWriteActionOnEdt: Boolean,
          undispatched: Boolean,
          action: ReadAndWriteScope.() -> ReadResult<X>,
        ): X {
          throw UnsupportedOperationException()
        }

        override fun smartModeConstraint(project: Project): ReadConstraint = ReadConstraint.inSmartMode(project)

        override suspend fun <T> runWriteAction(action: () -> T): T {
          throw UnsupportedOperationException()
        }
      },
      project,
    )

    val environment = StandaloneEnvironmentContext(project, moduleClassLoaderManager, StandaloneFontCacheService(fontsPath))
    val moduleDependencies = StandaloneModuleDependencies(rClassPackages.toList())
    val moduleKey = ModuleKey()

    val configModule =
      StandaloneConfigurationModelModule(
        resourceRepositoryManager,
        androidModuleInfo,
        androidPlatform,
        moduleKey,
        moduleDependencies,
        namespace,
        environment.layoutlibContext,
        layoutlibPath,
      )

    val configurationSettings = StandaloneConfigurationSettings(configModule, androidTarget)

    baseConfiguration = Configuration.create(configurationSettings, FolderConfiguration())

    module =
      StandaloneRenderModelModule(
        resourceRepositoryManager,
        androidModuleInfo,
        androidPlatform,
        moduleKey,
        moduleDependencies,
        project,
        namespace,
        environment,
        resourceIdManager,
        resourceApkPath,
      )

    renderService = RenderService {
      it.apply {
        disableDecorations()
        withRenderingMode(SessionParams.RenderingMode.SHRINK)
        // The security manager was removed in JDK 24. We disable it unconditionally to
        // support running on newer JDKs.
        disableSecurityManager()
      }
    }
    Disposer.register(project, renderService)
  }

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
    val previewElement = screenshot.toPreviewElement(module)
    val renderRequest =
      RenderRequest(configurationModifier = previewElement::applyTo, xmlLayoutsProvider = { previewElement.resolveXmlLayouts() })

    return render(renderRequest)
      .withIndex()
      .map { (index, value) ->
        val (config, renderResult) = value
        val previewId = screenshot.previewId
        val resultId = "${previewId.substringAfterLast(".")}_$index"
        val imageName = "$resultId.png"
        val methodFQN = screenshot.methodFQN
        val relativeImagePath = methodFQN.substringBeforeLast(".").replace(".", File.separator) + File.separator + imageName
        val screenshotResult =
          try {
            val imageRendered = postProcessRenderedImage(config, renderResult)
            if (imageRendered != null) {
              val imagePath = Paths.get(outputFolderPath, relativeImagePath)
              Files.createDirectories(imagePath.parent)
              val imgFile = imagePath.toFile()
              imgFile.createNewFile()
              ImageIO.write(imageRendered, "png", imgFile)
            }

            val screenshotError = extractError(renderResult, imageRendered)
            PreviewScreenshotResult(previewId, methodFQN, relativeImagePath, screenshotError)
          } catch (t: Throwable) {
            PreviewScreenshotResult(previewId, methodFQN, relativeImagePath, ScreenshotError(t))
          }
        screenshotResult
      }
      .toList()
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
        renderService.taskBuilder(module, configuration, logger).disableImagePool().disableCachingImageFactory().build(disposable).get()
          ?: return RenderResult.createRenderTaskErrorResult(
            module,
            { throw NotImplementedError("PsiFile supplier is not supported") },
            null,
            logger,
          )

      // b/469819154: Release render after use to avoid accumulating heap memory usage.
      Disposer.register(disposable) { renderTask.releaseRender() }

      val xmlFile = RenderXmlFileSnapshot(project, "layout.xml", ResourceFolderType.LAYOUT, xmlLayout)

      renderTask.setXmlFile(xmlFile)

      renderTask.render().get(100, TimeUnit.SECONDS)
    } catch (t: Throwable) {
      RenderResult.createRenderTaskErrorResult(module, { throw NotImplementedError("PsiFile supplier is not supported") }, t, logger)
    } finally {
      Disposer.dispose(disposable)
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

/**
 * Maps the compiled resource IDs from the APK to the R class fields.
 *
 * @param className The name of the R class.
 * @param pkg The package name of the R class.
 * @param classLoader The ClassLoader used to load the R class.
 * @param apkIdManager The ApkResourceIdManager that provides the compiled IDs.
 */
@VisibleForTesting
fun mapCompiledIdsToRClass(className: String, pkg: String, classLoader: ClassLoader, apkIdManager: ApkResourceIdManager) {
  val typeName = className.substringAfterLast("$")
  val resType = ResourceType.fromClassName(typeName)
  if (resType != null && resType != ResourceType.STYLEABLE) {
    val namespace = ResourceNamespace.fromPackageName(pkg)
    val rClass = classLoader.loadClass(className)
    for (field in rClass.declaredFields) {
      if (field.type == Int::class.java && Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers)) {
        val resRef = ResourceReference(namespace, resType, field.name)
        val apkId = apkIdManager.getCompiledId(resRef)
        if (apkId != null && apkId != 0) {
          field.isAccessible = true
          field.setInt(null, apkId)
        }
      }
    }
  }
}

/**
 * Scans a directory for compiled R.class and R$*.class files, loading them to resolve resource IDs.
 *
 * For top-level R.class files, it parses the bytecode to discover and parse any inner classes. For R$*.class files, it extracts the
 * compiled resource IDs and maps them to Layoutlib's APK parser output via [mapCompiledIdsToRClass]. This is necessary because modern
 * Android Gradle Plugins generate Light R-Classes that lack the InnerClasses attribute, breaking reflection-based discovery.
 */
@VisibleForTesting
fun resolveClassesFromDirectory(
  file: File,
  classLoader: ClassLoader,
  parser: ResourceIdManager.RClassParser,
  apkIdManager: ApkResourceIdManager,
  rClassPackages: MutableSet<String>,
  logger: Logger,
) {
  file
    .walk()
    .filter { it.name == "R.class" || (it.name.startsWith("R$") && it.name.endsWith(".class")) }
    .forEach { rClassFile ->
      val relativePath = rClassFile.relativeTo(file).path
      val className = relativePath.removeSuffix(".class").replace(File.separatorChar, '.')
      val pkg = className.substringBeforeLast('.', "")
      if (pkg.isNotEmpty()) rClassPackages.add(pkg)
      try {
        if (rClassFile.name == "R.class") {
          val rClassBytes = rClassFile.readBytes()
          parser.parseBytecode(rClassBytes) { innerName ->
            val innerFile = File(file, innerName.replace('.', File.separatorChar) + ".class")
            if (innerFile.exists()) innerFile.readBytes() else ByteArray(0)
          }
        } else {
          mapCompiledIdsToRClass(className, pkg, classLoader, apkIdManager)
        }
      } catch (e: Exception) {
        logger.log(Level.FINE, "Failed to load R class $className. Resource IDs for this package may not be resolved.", e)
      } catch (e: LinkageError) {
        logger.log(Level.FINE, "Linkage error while loading R class $className. This often indicates a classpath conflict.", e)
      }
    }
}

/**
 * Scans a JAR file for compiled R.class and R$*.class entries, loading them to resolve resource IDs.
 *
 * For top-level R.class entries, it reads the entry's bytes and parses them to discover inner classes. For R$*.class entries, it extracts
 * the compiled resource IDs and maps them to Layoutlib's APK parser output via [mapCompiledIdsToRClass].
 *
 * @param path The absolute path to the JAR file (used for logging).
 */
@VisibleForTesting
fun resolveClassesFromJar(
  file: File,
  path: String,
  classLoader: ClassLoader,
  parser: ResourceIdManager.RClassParser,
  apkIdManager: ApkResourceIdManager,
  rClassPackages: MutableSet<String>,
  logger: Logger,
) {
  try {
    JarFile(file).use { jar ->
      for (entry in jar.entries()) {
        val name = entry.name
        val simpleName = name.substringAfterLast('/')
        val isRClass = simpleName == "R.class"
        val isRInnerClass = simpleName.startsWith("R$") && simpleName.endsWith(".class")
        if (isRClass || isRInnerClass) {
          val className = name.removeSuffix(".class").replace('/', '.')
          val pkg = className.substringBeforeLast('.', "")
          if (pkg.isNotEmpty()) rClassPackages.add(pkg)
          try {
            if (isRClass) {
              val rClassBytes = jar.getInputStream(entry).use { it.readBytes() }
              parser.parseBytecode(rClassBytes) { innerName ->
                val innerEntryName = innerName.replace('.', '/') + ".class"
                val innerEntry = jar.getJarEntry(innerEntryName)
                if (innerEntry != null) jar.getInputStream(innerEntry).use { it.readBytes() } else ByteArray(0)
              }
            } else {
              mapCompiledIdsToRClass(className, pkg, classLoader, apkIdManager)
            }
          } catch (e: Exception) {
            logger.log(Level.FINE, "Failed to load R class $className from JAR $path.", e)
          } catch (e: LinkageError) {
            logger.log(Level.FINE, "Linkage error while loading R class $className from JAR $path.", e)
          }
        }
      }
    }
  } catch (e: Exception) {
    logger.log(Level.FINE, "Failed to read JAR file $path during R-class scanning.", e)
  } catch (e: LinkageError) {
    logger.log(Level.FINE, "Linkage error while reading JAR file $path.", e)
  }
}
