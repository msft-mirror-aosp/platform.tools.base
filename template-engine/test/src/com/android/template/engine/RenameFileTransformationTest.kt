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
package com.android.template.engine

import com.android.template.engine.impl.TemplateFileContentProcessor
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

@Suppress("FunctionName", "CanConvertToMultiDollarString")
class RenameFileTransformationTest {
  private fun runRenameFile(
    templatePath: String,
    sourcePath: String,
    targetPath: String,
    argumentValue: String,
    selector: FileSelector,
  ): TemplateFile {
    val templateFile = TemplateFile(templatePath, "Hello World".toByteArray(Charsets.UTF_8))
    val registry = TransformationRegistry()
    val context = TransformationContext(registry, mapOf("value" to argumentValue))
    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val processor = TemplateFileContentProcessor(messageSink)
    val transformation = RenameFileTransformation()
    val transformationDefinition =
      RenameFileDefinition(
        sourceLocation = FileLocation("foo.json", 1),
        selector = selector,
        sourcePath = sourcePath,
        targetPath = targetPath,
      )
    val outputFile =
      transformation.execute(
        templateFileProcessor = processor,
        transformationContext = context,
        transformation = transformationDefinition,
        inputFile = templateFile,
      )
    return outputFile
  }

  @Test
  fun `test RenameFile can use single file match selector`() {
    val outputFile =
      runRenameFile(
        templatePath = "app/src/main/java/MainActivity.kt",
        sourcePath = "MainActivity.kt",
        targetPath = "\${value}.kt",
        argumentValue = "MyActivity",
        selector = FileSelector.Glob("/app/src/main/java/MainActivity.kt"),
      )

    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/MyActivity.kt")
  }

  @Test
  fun `test RenameFile skips non-matching file selector`() {
    val outputFile =
      runRenameFile(
        templatePath = "app/src/main/java/MainActivity.kt",
        sourcePath = "MainActivity.kt",
        targetPath = "\${value}.kt",
        argumentValue = "MyActivity",
        selector = FileSelector.Glob("/settings.gradle.kts"),
      )

    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/MainActivity.kt")
  }

  @Test
  fun `test RenameFile can use glob match selector`() {
    val outputFile =
      runRenameFile(
        templatePath = "app/src/main/java/MainActivity.kt",
        sourcePath = "MainActivity.kt",
        targetPath = "\${value}.kt",
        argumentValue = "MyActivity",
        selector = FileSelector.Glob("**/*.kt"),
      )

    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/MyActivity.kt")
  }

  @Test
  fun `test RenameFile skips non-matching glob selector`() {
    val outputFile =
      runRenameFile(
        templatePath = "app/src/main/java/MainActivity.kt",
        sourcePath = "MainActivity.kt",
        targetPath = "\${value}.kt",
        argumentValue = "MyActivity",
        selector = FileSelector.Glob("**/*.xml"),
      )

    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/MainActivity.kt")
  }

  @Test
  fun `test RenameFile does nothing when source path is not found`() {
    val outputFile =
      runRenameFile(
        templatePath = "app/src/main/java/MainActivity.kt",
        sourcePath = "SettingsActivity.kt",
        targetPath = "\${value}.kt",
        argumentValue = "MyActivity",
        selector = FileSelector.Glob("**/*.kt"),
      )

    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/MainActivity.kt")
  }

  @Test
  fun `test RenameFile replaces with target path without value placeholder`() {
    val outputFile =
      runRenameFile(
        templatePath = "app/src/main/java/MainActivity.kt",
        sourcePath = "MainActivity.kt",
        targetPath = "HardcodedActivity.kt",
        argumentValue = "MyActivity",
        selector = FileSelector.Glob("**/*.kt"),
      )

    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/HardcodedActivity.kt")
  }

  @Test
  fun `test RenameFile replaces value path separator`() {
    val outputFile =
      runRenameFile(
        templatePath = "app/src/main/java/com/example/app/MainActivity.kt",
        sourcePath = "com/example/app",
        targetPath = "\${value.replace('.','/')}",
        argumentValue = "com.mycompany.app",
        selector = FileSelector.Glob("**/*.kt"),
      )

    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/com/mycompany/app/MainActivity.kt")
  }

  @Test
  fun `test RenameFile with empty argument value`() {
    val outputFile =
      runRenameFile(
        templatePath = "app/src/main/java/MainActivity.kt",
        sourcePath = "MainActivity.kt",
        targetPath = "Activity\${value}.kt",
        argumentValue = "",
        selector = FileSelector.Glob("**/*.kt"),
      )

    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/Activity.kt")
  }

  @Test
  fun `test RenameFile with empty source path`() {
    val exception =
      assertThrows(Exception::class.java) {
        runRenameFile(
          templatePath = "app/src/main/java/MainActivity.kt",
          sourcePath = "",
          targetPath = "\${value}.kt",
          argumentValue = "MyActivity",
          selector = FileSelector.Glob("**/*.kt"),
        )
      }

    assertThat(exception).hasMessageThat().contains("'sourcePath' string cannot be empty")
  }

  @Test
  fun `test RenameFile with empty target path`() {
    val outputFile =
      runRenameFile(
        templatePath = "app/src/main/java/MainActivity.kt",
        sourcePath = "MainActivity",
        targetPath = "",
        argumentValue = "MyActivity",
        selector = FileSelector.Glob("**/*.kt"),
      )

    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/.kt")
  }

  @Test
  fun `test RenameFile with multiple value placeholders in target path`() {
    val outputFile =
      runRenameFile(
        templatePath = "app/src/main/java/MainActivity.kt",
        sourcePath = "MainActivity.kt",
        targetPath = "\${value}_\${value}.kt",
        argumentValue = "MyActivity",
        selector = FileSelector.Glob("**/*.kt"),
      )

    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/MyActivity_MyActivity.kt")
  }
}
