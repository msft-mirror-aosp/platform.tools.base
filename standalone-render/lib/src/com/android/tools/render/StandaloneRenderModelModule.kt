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

import com.android.tools.module.AndroidModuleInfo
import com.android.tools.module.ModuleDependencies
import com.android.tools.module.ModuleKey
import com.android.tools.render.environment.StandaloneEnvironmentContext
import com.android.tools.rendering.api.RenderModelManifest
import com.android.tools.rendering.api.RenderModelModule
import com.android.tools.rendering.classloading.ClassTransform
import com.android.tools.res.AssetRepositoryBase
import com.android.tools.res.ResourceRepositoryManager
import com.android.tools.res.ids.ResourceIdManager
import com.android.tools.sdk.AndroidPlatform
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import java.util.logging.Level
import java.util.logging.Logger

/** [RenderModelModule] for standalone rendering. */
class StandaloneRenderModelModule(
  override val resourceRepositoryManager: ResourceRepositoryManager,
  override val info: AndroidModuleInfo?,
  override val androidPlatform: AndroidPlatform,
  override val moduleKey: ModuleKey,
  override val dependencies: ModuleDependencies,
  override val project: Project,
  override val resourcePackage: String,
  override val environment: StandaloneEnvironmentContext,
  override val resourceIdManager: ResourceIdManager,
  private val resourceApkPath: String? = null,
) : RenderModelModule {
  private val assetFileOpener = StandaloneAssetFileOpener(resourceApkPath)
  override val assetRepository = AssetRepositoryBase(assetFileOpener)
  private val logger = Logger.getLogger(StandaloneRenderModelModule::class.java.name)

  override val manifest: RenderModelManifest? = null

  override val parentDisposable: CheckedDisposable = Disposer.newCheckedDisposable()
  override val isDisposed: Boolean
    get() = parentDisposable.isDisposed

  init {
    Disposer.register(parentDisposable, assetFileOpener)
  }

  override fun getClassLoaderProvider(privateClassLoader: Boolean): RenderModelModule.ClassLoaderProvider {
    return RenderModelModule.ClassLoaderProvider {
      parent: ClassLoader?,
      additionalProjectTransform: ClassTransform,
      additionalNonProjectTransform: ClassTransform,
      onNewModuleClassLoader: Runnable ->
      val classLoaderRef =
        if (privateClassLoader) {
          environment.moduleClassLoaderManager.getPrivate(parent).also { onNewModuleClassLoader.run() }
        } else {
          environment.moduleClassLoaderManager.getShared(parent)
        }

      initializeLibraryResourceIds(classLoaderRef.classLoader, dependencies.getResourcePackageNames(true), resourceIdManager)
      classLoaderRef
    }
  }

  /**
   * Android library AARs ship with R.class files containing 0 for all IDs. In standard APK builds, AAPT2 and AGP recompile these into
   * finalized R classes. In the standalone rendering environment, we must manually initialize these library R-classes with dynamic
   * Layoutlib IDs before Compose reads them.
   */
  private fun initializeLibraryResourceIds(
    classLoader: ClassLoader,
    packages: List<String>,
    resourceIdManager: com.android.tools.res.ids.ResourceIdManager,
  ) {
    packages.forEach { pkg ->
      try {
        val className = "$pkg.R"
        val rClass =
          try {
            classLoader.loadClass(className)
          } catch (e: ClassNotFoundException) {
            return@forEach
          }

        val innerClasses = com.android.resources.ResourceType.values().mapNotNull { resType ->
          val classFilePath = "${pkg.replace('.', '/')}/R$${resType.getName()}.class"
          if (classLoader.getResource(classFilePath) != null) {
            try {
              classLoader.loadClass("$pkg.R$${resType.getName()}")
            } catch (e: ClassNotFoundException) {
              null
            }
          } else {
            null
          }
        }

        for (innerClass in innerClasses) {
          val typeName = innerClass.simpleName
          val type = com.android.resources.ResourceType.fromClassName(typeName) ?: continue
          if (type == com.android.resources.ResourceType.STYLEABLE) continue

          for (field in innerClass.declaredFields) {
            if (field.type != Int::class.javaPrimitiveType && field.type != Int::class.java) continue
            if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) continue

            try {
              field.isAccessible = true
              if (field.getInt(null) == 0) {
                val dynamicId =
                  resourceIdManager.getOrGenerateId(
                    com.android.ide.common.rendering.api.ResourceReference(
                      com.android.ide.common.rendering.api.ResourceNamespace.RES_AUTO,
                      type,
                      field.name,
                    )
                  )
                field.setInt(null, dynamicId)
              }
            } catch (e: IllegalAccessException) {
              logger.log(Level.FINE, "Failed to set dynamic ID for field ${field.name} in ${innerClass.name}", e)
            }
          }
        }
      } catch (e: Exception) {
        // Log unexpected errors but continue with other packages
        logger.log(Level.FINE, "Failed to initialize resource IDs for package $pkg", e)
      }
    }
  }

  override val name: String = "Fake Module"

  override fun getIdeaModule(): Module {
    throw UnsupportedOperationException("Should not be called in standalone rendering")
  }

  override fun dispose() {
    Disposer.dispose(parentDisposable)
  }
}
