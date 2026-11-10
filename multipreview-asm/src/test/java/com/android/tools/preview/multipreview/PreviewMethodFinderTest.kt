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

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.StringBuilder
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

class PreviewMethodFinderTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val finder: PreviewMethodFinder by lazy {
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
    PreviewMethodFinder(
      listOf(File(rootDir, "screenshotTestDirs/dir1")),
      listOf(File(rootDir, "screenshotTestJars/precompiledTestClasses.jar")),
      listOf(File(rootDir, "mainDirs/dir1")),
      listOf(File(rootDir, "mainJars/jar1.jar")),
      listOf(File(rootDir, "depsJars/libjar1.jar")),
    )
  }

  @Test
  fun testFindPreviewMethods() {
    val previewMethods = finder.findAllPreviewMethods()
    assertThat(previewMethods.flatMap { it.toDebugString() }.sorted().joinToString("----\n").trim())
      .isEqualTo(
        """
        com.example.myprecompiledtestclasses.PreviewableMethodsFromStaticLibraryKt.GreetingPreview1
        type: Compose
        showBackground: true
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTest.GreetingPreview2
        type: Compose
        showBackground: true
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.CustomMultipreviewAnnotationTest
        type: Compose
        showBackground: false
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.CustomMultipreviewAnnotationTest
        type: Compose
        showBackground: true
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.CyclicPreviewableAnnotationTest
        type: Compose
        showBackground: false
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.CyclicPreviewableAnnotationTest
        type: Compose
        showBackground: true
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.GreetingPreview1
        type: Compose
        showBackground: true
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.GreetingPreviewWithRepeatedAnnotation
        type: Compose
        showBackground: false
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.GreetingPreviewWithRepeatedAnnotation
        type: Compose
        showBackground: true
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.MultiPreviewWithMultipleCustomAnnotations
        type: Compose
        apiLevel: 29
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.MultiPreviewWithMultipleCustomAnnotations
        type: Compose
        apiLevel: 30
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.MultiPreviewWithMultipleCustomAnnotations
        type: Compose
        showBackground: false
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.MultiPreviewWithMultipleCustomAnnotations
        type: Compose
        showBackground: true
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.PreviewAnnotationFromLibrary
        type: Compose
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.PreviewAnnotationFromMain
        type: Compose
        showBackground: true
        ----
        com.example.myscreenshottestexample.screenshottest.ExampleScreenshotTestKt.PreviewWithParameterProvider
        type: Compose
        provider: Lcom/example/mylibrary/MyPreviewParameterProvider;
        limit: 2
        ----
        com.example.myscreenshottestexample.tile.screenshottest.TileScreenshotTestKt.tilePreview
        type: WearTile
        device: id:wearos_large_round
        """
          .trimIndent()
      )
    assertThat(previewMethods).hasSize(11)
  }

  @Test
  fun testFindMultiplatformPreviewMethods() {
    val targetDir = tempDir.newFolder("kmpTarget")
    val mainDir = tempDir.newFolder("kmpMain")

    // Equivalent Kotlin Multiplatform MultiPreview annotation in mainDir:
    //   package org.example.kmp
    //   import org.jetbrains.compose.ui.tooling.preview.Preview
    //   @Preview(name = "KMP Light", showBackground = false)
    //   @Preview(name = "KMP Dark", showBackground = true)
    //   annotation class KmpThemePreviews
    val multiPreviewCw = ClassWriter(0)
    multiPreviewCw.visit(
      Opcodes.V17,
      Opcodes.ACC_PUBLIC or Opcodes.ACC_ANNOTATION or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
      "org/example/kmp/KmpThemePreviews",
      null,
      "java/lang/Object",
      arrayOf("java/lang/annotation/Annotation"),
    )
    multiPreviewCw.visitAnnotation("Lorg/jetbrains/compose/ui/tooling/preview/Preview\$Container;", false).apply {
      visitArray("value").apply {
        visitAnnotation(null, "Lorg/jetbrains/compose/ui/tooling/preview/Preview;").apply {
          visit("name", "KMP Light")
          visit("showBackground", false)
          visitEnd()
        }
        visitAnnotation(null, "Lorg/jetbrains/compose/ui/tooling/preview/Preview;").apply {
          visit("name", "KMP Dark")
          visit("showBackground", true)
          visitEnd()
        }
        visitEnd()
      }
      visitEnd()
    }
    multiPreviewCw.visitEnd()
    File(mainDir, "org/example/kmp/KmpThemePreviews.class").apply {
      parentFile.mkdirs()
      writeBytes(multiPreviewCw.toByteArray())
    }

    // Equivalent Kotlin Multiplatform preview functions in targetDir:
    //   package org.example.kmp
    //   import androidx.compose.runtime.Composable
    //   import org.jetbrains.compose.ui.tooling.preview.Preview
    //   import org.jetbrains.compose.ui.tooling.preview.PreviewParameter
    //
    //   @Preview(name = "Direct KMP")
    //   @Composable
    //   fun DirectKmpPreview(@PreviewParameter(provider = SampleProvider::class, limit = 3) label: String) {}
    //
    //   @KmpThemePreviews
    //   @Composable
    //   fun MultiKmpPreview() {}
    val ktClassCw = ClassWriter(0)
    ktClassCw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "org/example/kmp/CommonScreenKt", null, "java/lang/Object", null)
    ktClassCw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "DirectKmpPreview", "(Ljava/lang/String;)V", null, null).apply {
      visitAnnotation("Landroidx/compose/runtime/Composable;", true).visitEnd()
      visitAnnotation("Lorg/jetbrains/compose/ui/tooling/preview/Preview;", false).apply {
        visit("name", "Direct KMP")
        visitEnd()
      }
      visitParameterAnnotation(0, "Lorg/jetbrains/compose/ui/tooling/preview/PreviewParameter;", false).apply {
        visit("provider", Type.getObjectType("org/example/kmp/SampleProvider"))
        visit("limit", 3)
        visitEnd()
      }
      visitEnd()
    }
    ktClassCw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "MultiKmpPreview", "()V", null, null).apply {
      visitAnnotation("Landroidx/compose/runtime/Composable;", true).visitEnd()
      visitAnnotation("Lorg/example/kmp/KmpThemePreviews;", false).visitEnd()
      visitEnd()
    }
    ktClassCw.visitEnd()
    File(targetDir, "org/example/kmp/CommonScreenKt.class").apply {
      parentFile.mkdirs()
      writeBytes(ktClassCw.toByteArray())
    }

    val kmpFinder =
      PreviewMethodFinder(
        screenshotTestDirectory = listOf(targetDir),
        screenshotTestJars = emptyList(),
        mainDirectory = listOf(mainDir),
        mainJars = emptyList(),
        dependencyJars = emptyList(),
      )

    val previewMethods = kmpFinder.findAllPreviewMethods()
    assertThat(previewMethods.flatMap { it.toDebugString() }.sorted().joinToString("----\n").trim())
      .isEqualTo(
        """
        org.example.kmp.CommonScreenKt.DirectKmpPreview
        type: Compose
        name: Direct KMP
        provider: Lorg/example/kmp/SampleProvider;
        limit: 3
        ----
        org.example.kmp.CommonScreenKt.MultiKmpPreview
        type: Compose
        name: KMP Dark
        showBackground: true
        ----
        org.example.kmp.CommonScreenKt.MultiKmpPreview
        type: Compose
        name: KMP Light
        showBackground: false
        """
          .trimIndent()
      )
  }

  private fun PreviewMethod.toDebugString(): List<String> {
    return previewAnnotations.map { previewAnnotation ->
      StringBuilder()
        .apply {
          appendLine(method.methodFqn)
          val type =
            when (this@toDebugString) {
              is ComposePreviewMethod -> "Compose"
              is WearTilePreviewMethod -> "WearTile"
            }
          appendLine("type: $type")
          previewAnnotation.parameters.entries.forEach { (key, value) -> appendLine("$key: $value") }
          method.parameters.forEach { it.annotationParameters.entries.forEach { (key, value) -> appendLine("$key: $value") } }
        }
        .toString()
    }
  }
}
