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

package com.android.build.gradle.internal.services

import com.google.common.annotations.VisibleForTesting
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.concurrent.ThreadSafe
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Build service used to instantiate and cache isolated [URLClassLoader] instances for the R8 compiler.
 *
 * To ensure isolation from AGP and Gradle runtime classes, all created ClassLoaders use [ClassLoader.getPlatformClassLoader] as their
 * parent.
 */
@ThreadSafe
abstract class R8ClassloaderBuildService : BuildService<BuildServiceParameters.None>, AutoCloseable {

  private val classLoaderCache = ConcurrentHashMap<List<File>, URLClassLoader>()

  /** Retrieves or creates an isolated [ClassLoader] containing the provided [r8Classpath]. */
  fun getClassLoader(r8Classpath: Set<File>): ClassLoader {
    val key = r8Classpath.map { it.canonicalFile }.sortedBy { it.path }
    return classLoaderCache.computeIfAbsent(key) { files -> createClassLoader(files) }
  }

  @VisibleForTesting fun getCachedClassLoaderCount(): Int = classLoaderCache.size

  override fun close() {
    for (classLoader in classLoaderCache.values) {
      try {
        classLoader.close()
      } catch (_: Throwable) {
        // Ignored during shutdown
      }
    }
    classLoaderCache.clear()
  }

  companion object {
    /** Creates a new isolated [URLClassLoader] for the provided [files]. */
    fun createClassLoader(files: Collection<File>): URLClassLoader {
      val urls = files.map { it.canonicalFile.toURI().toURL() }.toTypedArray()
      return URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
    }
  }

  class RegistrationAction(project: Project) :
    ServiceRegistrationAction<R8ClassloaderBuildService, BuildServiceParameters.None>(project, R8ClassloaderBuildService::class.java) {

    override fun configure(parameters: BuildServiceParameters.None) {}
  }
}
