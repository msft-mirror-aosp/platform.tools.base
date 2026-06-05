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

import com.android.tools.rendering.classloading.ClassTransform
import com.android.tools.rendering.classloading.ModuleClassLoader
import com.android.tools.rendering.classloading.ModuleClassLoaderDiagnosticsRead
import java.net.URL
import java.util.Enumeration

/** Default implementation of [ModuleClassLoader] for standalone rendering. */
class DefaultModuleClassLoader private constructor(parent: ClassLoader?, private val loader: DefaultLoader) :
  ModuleClassLoader(parent, loader) {
  constructor(
    parent: ClassLoader?,
    classPath: List<String>,
    projectClassPath: List<String>,
  ) : this(parent, DefaultLoader(parent, classPath, projectClassPath))

  override fun getResource(name: String): URL? {
    return loader.getResource(name) ?: super.getResource(name)
  }

  override fun getResources(name: String): Enumeration<URL> {
    val localRes = loader.getResources(name).toList()
    val parentRes = super.getResources(name).toList()
    return java.util.Collections.enumeration(localRes + parentRes)
  }

  private val loadedClasses = mutableSetOf<String>()
  override val stats: ModuleClassLoaderDiagnosticsRead =
    object : ModuleClassLoaderDiagnosticsRead {
      override val classesFound: Long = 0
      override val accumulatedFindTimeMs: Long = 0
      override val accumulatedRewriteTimeMs: Long = 0
    }
  override val isUserCodeUpToDate: Boolean = true

  override fun hasLoadedClass(fqcn: String): Boolean = loadedClasses.contains(fqcn)

  override val projectLoadedClasses: Set<String> = emptySet()
  override val nonProjectLoadedClasses: Set<String>
    get() = loadedClasses

  override val projectClassesTransform: ClassTransform = ClassTransform.identity
  override val nonProjectClassesTransform: ClassTransform = ClassTransform.identity

  override fun dispose() {}

  override val isDisposed: Boolean = false

  override fun isCompatibleParentClassLoader(parent: ClassLoader?): Boolean = true

  override fun areDependenciesUpToDate(): Boolean = true

  override fun onAfterLoadClass(fqcn: String, loaded: Boolean, durationMs: Long) {
    if (loaded) {
      loadedClasses.add(fqcn)
    }
  }

  val classesToPaths: Map<String, String>
    get() = loader.classesToPaths
}
