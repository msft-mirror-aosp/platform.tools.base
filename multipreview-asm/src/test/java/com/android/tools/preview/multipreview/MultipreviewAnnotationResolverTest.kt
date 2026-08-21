/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.preview.multipreview

import com.android.tools.preview.multipreview.PreviewMethodFinder.Companion.COMPOSE_PREVIEW_ANNOTATION
import com.android.tools.preview.multipreview.PreviewMethodFinder.Companion.COMPOSE_PREVIEW_ANNOTATION_CONTAINER
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.StringBuilder
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MultipreviewAnnotationResolverTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val resolver: MultipreviewAnnotationResolver by lazy {
    val testClassesDir = tempDir.newFolder()
    ZipInputStream(requireNotNull(this::class.java.classLoader.getResourceAsStream("testJarsAndClasses.zip"))).use { zipInputStream ->
      generateSequence { zipInputStream.nextEntry }
        .forEach { entry ->
          val outputFile = File(testClassesDir, entry.name)
          if (entry.isDirectory) {
            outputFile.mkdirs()
          } else {
            outputFile.parentFile.mkdirs()
            Files.copy(zipInputStream, outputFile.toPath())
          }
        }
    }

    val rootDir = File(testClassesDir, "testJarsAndClasses")
    val screenshotTestDirectory = listOf(File(rootDir, "screenshotTestDirs/dir1"))
    val screenshotTestJars = listOf(File(rootDir, "screenshotTestJars/precompiledTestClasses.jar"))
    val mainDirectory = listOf(File(rootDir, "mainDirs/dir1"))
    val mainJars = listOf(File(rootDir, "mainJars/jar1.jar"))
    val dependencyJars = listOf(File(rootDir, "depsJars/libjar1.jar"))

    fun resolveClassReader(annotationClassDescriptor: String): org.objectweb.asm.ClassReader? {
      val relativeFilePath = annotationClassDescriptor.substring(1, annotationClassDescriptor.length - 1) + ".class"

      fun findInDir(dir: File): org.objectweb.asm.ClassReader? {
        val classFile = File(dir, relativeFilePath)
        if (classFile.isFile && classFile.exists()) {
          return org.objectweb.asm.ClassReader(classFile.readBytes())
        }
        return null
      }

      fun findInJar(jar: File): org.objectweb.asm.ClassReader? {
        if (!jar.exists() || !jar.isFile || !jar.name.endsWith(".jar", ignoreCase = true)) return null
        return java.util.zip.ZipFile(jar).use { zipFile ->
          zipFile.getEntry(relativeFilePath)?.let {
            zipFile.getInputStream(it).use { stream -> org.objectweb.asm.ClassReader(stream.readAllBytes()) }
          }
        }
      }

      return screenshotTestDirectory.firstNotNullOfOrNull(::findInDir)
        ?: screenshotTestJars.firstNotNullOfOrNull(::findInJar)
        ?: mainDirectory.firstNotNullOfOrNull(::findInDir)
        ?: mainJars.firstNotNullOfOrNull(::findInJar)
        ?: dependencyJars.firstNotNullOfOrNull(::findInJar)
    }

    MultipreviewAnnotationResolver(
      COMPOSE_PREVIEW_ANNOTATION,
      COMPOSE_PREVIEW_ANNOTATION_CONTAINER,
      ::resolveClassReader,
    )
  }

  private fun getPreviewAnnotationsForClass(annotationClassDescriptor: String): String {
    val previewAnnotations = mutableSetOf<BaseAnnotationRepresentation>()
    resolver.findAllPreviewAnnotations(annotationClassDescriptor, previewAnnotations::addAll)

    val debugString = StringBuilder()
    debugString.appendLine("size = ${previewAnnotations.size}")
    previewAnnotations.forEach {
      it.parameters.entries.forEach { (key, value) -> debugString.appendLine("$key: $value") }
      debugString.appendLine("----")
    }
    return debugString.toString().trim()
  }

  @Test
  fun multipreviewIsDefinedInTestSource() {
    assertThat(getPreviewAnnotationsForClass("Lcom/example/myscreenshottestexample/screenshottest/MyCustomMultipreviewAnnotation;"))
      .isEqualTo(
        """
        size = 2
        showBackground: true
        ----
        showBackground: false
        ----
        """
          .trimIndent()
      )
  }

  @Test
  fun multipreviewIsDefinedInMainSource() {
    assertThat(getPreviewAnnotationsForClass("Lcom/example/myscreenshottestexample/MyCustomMultipreviewAnnotationInMain;"))
      .isEqualTo(
        """
        size = 1
        showBackground: true
        ----
        """
          .trimIndent()
      )
  }

  @Test
  fun multipreviewIsDefinedInDependency() {
    assertThat(getPreviewAnnotationsForClass("Lcom/example/mylibrary/MyCustomPreviewAnnotationInLibrary;"))
      .isEqualTo(
        """
        size = 1
        ----
        """
          .trimIndent()
      )
  }

  @Test
  fun cyclicPreviewAnnotation() {
    assertThat(getPreviewAnnotationsForClass("Lcom/example/myscreenshottestexample/screenshottest/CyclicPreviewableAnnotation;"))
      .isEqualTo(
        """
        size = 2
        showBackground: true
        ----
        showBackground: false
        ----
        """
          .trimIndent()
      )
  }

  @Test
  fun cyclicNonPreviewAnnotation() {
    assertThat(getPreviewAnnotationsForClass("Lcom/example/myscreenshottestexample/screenshottest/CyclicNonPreviewableAnnotation;"))
      .isEqualTo("size = 0")
  }
}
