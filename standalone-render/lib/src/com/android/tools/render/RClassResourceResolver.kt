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

import com.android.annotations.VisibleForTesting
import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType
import com.android.tools.res.ids.ResourceIdManager
import com.android.tools.res.ids.apk.ApkResourceIdManager
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import java.util.logging.Level
import java.util.logging.Logger

/** Scans directories and JARs for compiled R classes to map resource IDs for Layoutlib. */
class RClassResourceResolver(private val classLoaderManager: StandaloneModuleClassLoaderManager) {
  private val logger = Logger.getLogger(RClassResourceResolver::class.java.name)

  /**
   * Resolves R classes from the given paths and registers them in the ID manager.
   *
   * @return The list of package names discovered.
   */
  fun resolve(rClassJars: List<String>, resourceIdManager: StandaloneResourceIdManager, apkIdManager: ApkResourceIdManager): List<String> {
    val rClassPackages = mutableSetOf<String>()

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
          classLoaderManager.getShared(this::class.java.classLoader).use { ref ->
            resolveClassesFromDirectory(file, ref.classLoader, parser, apkIdManager, rClassPackages)
          }
        } else if (file.isFile) {
          classLoaderManager.getShared(this::class.java.classLoader).use { ref ->
            resolveClassesFromJar(file, path, ref.classLoader, parser, apkIdManager, rClassPackages)
          }
        }
      }
    }
    return rClassPackages.toList()
  }

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

  @VisibleForTesting
  fun resolveClassesFromDirectory(
    file: File,
    classLoader: ClassLoader,
    parser: ResourceIdManager.RClassParser,
    apkIdManager: ApkResourceIdManager,
    rClassPackages: MutableSet<String>,
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

  @VisibleForTesting
  fun resolveClassesFromJar(
    file: File,
    path: String,
    classLoader: ClassLoader,
    parser: ResourceIdManager.RClassParser,
    apkIdManager: ApkResourceIdManager,
    rClassPackages: MutableSet<String>,
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
}
