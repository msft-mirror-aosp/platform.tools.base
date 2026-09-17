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

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.resources.configuration.FolderConfiguration
import com.android.resources.ResourceType
import com.android.sdklib.AndroidVersion
import com.android.tools.configurations.Configuration
import com.android.tools.configurations.Wallpaper
import com.android.tools.module.ModuleKey
import com.android.tools.render.configuration.StandaloneConfigurationModelModule
import com.android.tools.render.configuration.StandaloneConfigurationSettings
import com.android.tools.render.environment.StandaloneEnvironmentContext
import com.android.tools.render.framework.IJFramework
import com.android.tools.rendering.RenderService
import com.android.tools.rendering.classloading.ModuleClassLoaderManager
import com.android.tools.res.FrameworkOverlay
import com.android.tools.res.LocalResourceRepository
import com.android.tools.res.SingleRepoResourceRepositoryManager
import com.android.tools.res.apk.ApkResourceRepository
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
import java.io.File
import java.nio.file.Path
import java.util.TimeZone
import java.util.logging.Logger

/** Bootstraps the Layoutlib and IntelliJ environment required for standalone rendering. */
class RenderEnvironmentBootstrapper(
  private val fontsPath: String?,
  private val resourceApkPath: String?,
  private val namespace: String,
  private val classPath: List<String>,
  private val projectClassPath: List<String>,
  private val layoutlibPath: String,
  private val resourceDirs: List<String> = emptyList(),
  private val rClassJars: List<String> = emptyList(),
) {
  private val logger = Logger.getLogger(RenderEnvironmentBootstrapper::class.java.name)

  /** Initializes the environment and returns a configured [Renderer]. */
  fun bootstrap(): Renderer {
    TimeZone.getDefault()

    val project = IJFramework.createProject()
    val moduleClassLoaderManager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)

    val apkIdManager = ApkResourceIdManager()
    resourceApkPath?.let { apkIdManager.loadApkResources(it) }

    val baseIdManager =
      com.android.tools.res.ids.ResourceIdManagerBase(com.android.tools.res.ids.ResourceIdManagerModelModule.noNamespacingApp(true), true)

    val resourceIdManager = StandaloneResourceIdManager(apkIdManager, baseIdManager)

    val rClassResolver = RClassResourceResolver(moduleClassLoaderManager)
    val rClassPackages = rClassResolver.resolve(rClassJars, resourceIdManager, apkIdManager)

    val apkResourcesRepo =
      if (resourceApkPath != null) ApkResourceRepository(resourceApkPath, resourceIdManager::findById)
      else LocalResourceRepository.EmptyRepository<Path>(com.android.ide.common.rendering.api.ResourceNamespace.RES_AUTO)

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
    val moduleDependencies = StandaloneModuleDependencies(rClassPackages)
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
    val defaultTheme =
      if (resourcesRepo.hasResources(ResourceNamespace.RES_AUTO, ResourceType.STYLE, "Theme.Material3.DayNight.NoActionBar")) {
        "@style/Theme.Material3.DayNight.NoActionBar"
      } else {
        "@android:style/Theme.Material.Light.NoActionBar" // Fallback if app does not depend on Material 3
      }

    // Standalone rendering runs headlessly without the IDE ConfigurationManager.
    // Providing these defaults matches Android Studio and PreviewConfiguration.applyTo,
    // ensuring proper resolution of Material 3 themes, dynamic color tokens, and system UI settings.
    val baseConfiguration =
      Configuration.create(configurationSettings, FolderConfiguration()).apply {
        setTheme(defaultTheme)
        setWallpaper(Wallpaper.GREEN) // Default dynamic color palette (Wallpaper.kt)
        // Defaults in SystemUiPreferences.kt
        setGestureNav(true)
        setEdgeToEdge(true)
        setCutoutOverlay(FrameworkOverlay.CUTOUT_NONE)
        setFontScale(1.0f)
        // Default device ("medium_phone") without frame.
        configurationSettings.defaultDevice?.let { setDevice(it, false) }
      }

    val module =
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

    val renderService = RenderService {
      it.apply {
        disableDecorations()
        withRenderingMode(com.android.ide.common.rendering.api.SessionParams.RenderingMode.SHRINK)
        disableSecurityManager()
      }
    }
    Disposer.register(project, renderService)

    return Renderer(project, module, renderService, baseConfiguration, moduleClassLoaderManager)
  }
}
