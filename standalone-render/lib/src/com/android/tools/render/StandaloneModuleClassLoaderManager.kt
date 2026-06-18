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

import com.android.annotations.concurrency.GuardedBy
import com.android.layoutlib.LayoutlibClassLoader
import com.android.tools.rendering.classloading.FilteringClassLoader
import com.android.tools.rendering.classloading.FirewalledResourcesClassLoader
import com.android.tools.rendering.classloading.ModuleClassLoader
import com.android.tools.rendering.classloading.ModuleClassLoaderManager
import com.android.tools.rendering.classloading.Preloader
import com.google.common.cache.CacheBuilder
import com.intellij.openapi.module.Module
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** [ModuleClassLoaderManager] for the [ModuleClassLoader]s used in the standalone rendering. */
class StandaloneModuleClassLoaderManager(private val classPath: List<String>, private val projectClassPath: List<String>) :
  ModuleClassLoaderManager<ModuleClassLoader>, AutoCloseable {

  private val preloaderExecutor = Executors.newSingleThreadExecutor()
  private val preloader = Preloader(createClassLoader(null), preloaderExecutor, COMPOSE_CLASSES)

  override fun close() {
    preloaderExecutor.shutdownNow()
  }

  private fun createClassLoader(parent: ClassLoader?): DefaultModuleClassLoader {
    val wrappedParent =
      FirewalledResourcesClassLoader(
        FilteringClassLoader.allowedPrefixes(parent ?: LayoutlibClassLoader(this::class.java.classLoader), ALLOWED_PACKAGES_FROM_PARENT)
      )
    return DefaultModuleClassLoader(wrappedParent, classPath, projectClassPath)
  }

  private val sharedClassLoadersLock = ReentrantLock()
  @GuardedBy("sharedClassLoadersLock")
  private val sharedClassLoaders = CacheBuilder.newBuilder().maximumSize(10).build<ClassLoader?, DefaultModuleClassLoader>()

  fun getShared(parent: ClassLoader?): ModuleClassLoaderManager.Reference<ModuleClassLoader> {
    val classLoader = sharedClassLoadersLock.withLock { sharedClassLoaders.get(parent) { preloader.getClassLoader() } }
    return ModuleClassLoaderManager.Reference(classLoader, {})
  }

  fun getPrivate(parent: ClassLoader?): ModuleClassLoaderManager.Reference<ModuleClassLoader> {
    return ModuleClassLoaderManager.Reference(createClassLoader(parent), {})
  }

  override fun clearCache(module: Module) {}
}
