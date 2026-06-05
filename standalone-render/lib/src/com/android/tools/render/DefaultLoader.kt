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

import com.android.tools.rendering.classloading.CodeExecutionTrackerTransform
import com.android.tools.rendering.classloading.PseudoClassLocatorForLoader
import com.android.tools.rendering.classloading.RepackageTransform
import com.android.tools.rendering.classloading.ResourcesCompatTransform
import com.android.tools.rendering.classloading.SdkIntReplacer
import com.android.tools.rendering.classloading.StringReplaceTransform
import com.android.tools.rendering.classloading.loaders.AsmTransformingLoader
import com.android.tools.rendering.classloading.loaders.ClassLoaderLoader
import com.android.tools.rendering.classloading.loaders.DelegatingClassLoader
import com.android.tools.rendering.classloading.loaders.MultiLoader
import com.android.tools.rendering.classloading.loaders.NameRemapperLoader
import com.android.tools.rendering.classloading.toClassTransform
import com.intellij.util.lang.UrlClassLoader
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Enumeration
import kotlin.io.path.Path

/** A loader responsible for loading all the classes in out-of-studio version of [ModuleClassLoader]. */
internal class DefaultLoader(parent: ClassLoader?, classPath: List<String>, projectClassPath: List<String>) : DelegatingClassLoader.Loader {
  val classesToPaths = mutableMapOf<String, String>()
  private val classTransforms =
    toClassTransform(
      ::ResourcesCompatTransform,
      ::SdkIntReplacer,
      { StringReplaceTransform(it, STRING_REPLACEMENTS) },
      { RepackageTransform(it, PACKAGES_TO_RENAME, INTERNAL_PACKAGE) },
    )
  private val projectClassTransforms =
    toClassTransform(
      { CodeExecutionTrackerTransform(it, CLASSES_TRACKER_KEY) },
      ::SdkIntReplacer,
      { RepackageTransform(it, PACKAGES_TO_RENAME, INTERNAL_PACKAGE) },
    )

  private val loader: DelegatingClassLoader.Loader
  private val depsClassLoader: ClassLoader
  private val projectClassLoader: ClassLoader

  init {
    val projectClassPathSet = projectClassPath.toSet()
    val depsClassPath = classPath.filter { !projectClassPathSet.contains(it) }
    val parentLoader = parent?.let { ClassLoaderLoader(it) }

    val onDiskLookup: (String) -> String = { fqcn -> if (fqcn.startsWith(INTERNAL_PACKAGE)) fqcn.removePrefix(INTERNAL_PACKAGE) else fqcn }

    depsClassLoader = UrlClassLoader.build().useCache(false).parent(parent).files(depsClassPath.map { Path(it) }).get()
    val depsClassLoaderLoaderRaw = ClassLoaderLoader(depsClassLoader)
    val depsClassLoaderLoader = NameRemapperLoader(depsClassLoaderLoaderRaw, onDiskLookup)
    val depsLoader =
      AsmTransformingLoader(
        classTransforms,
        depsClassLoaderLoader,
        PseudoClassLocatorForLoader(listOfNotNull(depsClassLoaderLoader, parentLoader).asSequence(), parent),
        // COMPUTE_MAXS is required because some transforms (e.g. CodeExecutionTrackerTransform) inject method calls that alter the
        // maximum stack size.
        asmFlags = org.objectweb.asm.ClassWriter.COMPUTE_MAXS,
      )
    projectClassLoader = UrlClassLoader.build().useCache(false).parent(parent).files(projectClassPath.map { Path(it) }).get()
    val projectClassLoaderLoaderRaw = ClassLoaderLoader(projectClassLoader) { fqcn, path, _ -> classesToPaths[fqcn] = path }
    val projectClassLoaderLoader = NameRemapperLoader(projectClassLoaderLoaderRaw, onDiskLookup)
    val projectLoader =
      AsmTransformingLoader(
        projectClassTransforms,
        projectClassLoaderLoader,
        PseudoClassLocatorForLoader(listOfNotNull(projectClassLoaderLoader, depsLoader, parentLoader).asSequence(), parent),
        // COMPUTE_MAXS is required because some transforms (e.g. CodeExecutionTrackerTransform) inject method calls that alter the
        // maximum stack size.
        asmFlags = org.objectweb.asm.ClassWriter.COMPUTE_MAXS,
      )
    loader = MultiLoader(projectLoader, depsLoader)
  }

  private val serviceUrlCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

  private fun wrapServiceUrl(originalUrl: URL): URL {
    val handler =
      object : URLStreamHandler() {
        override fun openConnection(u: URL): URLConnection {
          return object : URLConnection(u) {
            override fun connect() {}

            override fun getInputStream(): java.io.InputStream {
              val urlString = u.file
              val cachedBytes = serviceUrlCache[urlString]
              if (cachedBytes != null) {
                return java.io.ByteArrayInputStream(cachedBytes)
              }

              val realUrl = URL(urlString)
              val out = java.io.ByteArrayOutputStream()
              realUrl.openStream().bufferedReader(Charsets.UTF_8).use { reader ->
                java.io.OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                  reader.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("kotlin.") || trimmed.startsWith("kotlinx.")) {
                      writer.write("$INTERNAL_PACKAGE$trimmed\n")
                    } else {
                      writer.write("$line\n")
                    }
                  }
                }
              }
              val bytes = out.toByteArray()
              serviceUrlCache[urlString] = bytes
              return java.io.ByteArrayInputStream(bytes)
            }
          }
        }
      }
    return URL("metainfservices", "", -1, originalUrl.toString(), handler)
  }

  fun getResource(name: String): URL? {
    val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
    val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
    val url = projectClassLoader.getResource(mappedName) ?: depsClassLoader.getResource(mappedName)
    if (url != null && name.startsWith("META-INF/services/")) {
      return wrapServiceUrl(url)
    }
    return url
  }

  fun getResources(name: String): Enumeration<URL> {
    val internalPrefix = INTERNAL_PACKAGE.replace('.', '/')
    val mappedName = name.replace("META-INF/services/$INTERNAL_PACKAGE", "META-INF/services/").removePrefix(internalPrefix)
    val res1 = projectClassLoader.getResources(mappedName).toList()
    val res2 = depsClassLoader.getResources(mappedName).toList()
    var allRes = res1 + res2
    if (name.startsWith("META-INF/services/")) {
      allRes = allRes.map { wrapServiceUrl(it) }
    }
    return java.util.Collections.enumeration(allRes)
  }

  override fun loadClass(fqcn: String): ByteArray? = loader.loadClass(fqcn)
}
