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

import com.android.template.engine.DefaultFileStorageTest.Companion.FileSystemId
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@Suppress("FunctionName")
@RunWith(Parameterized::class)
class TemplateListBuilderTest(private val fileSystemId: FileSystemId) {
  @get:Rule val tempFolder = TemporaryFolder()

  private fun getTestRootPath(): Path {
    return DefaultFileStorageTest.getTestRootPath(fileSystemId, tempFolder).resolve("test-builder")
  }

  @Test
  fun `test parse metadata and load templates from zip file with on demand loading`() {
    // Prepare
    val rootPath = getTestRootPath()
    Files.createDirectories(rootPath)
    val zipFile = rootPath.resolve("templates.zip")

    val jsonContent =
      """
      {
        "name": "My Great Template",
        "short-name": "my-great-template",
        "tags": ["tagA", "tagB"],
        "arguments": [
          {
            "id": "arg1",
            "default-value": "default1"
          }
        ],
        "dependencies": [
          {
            "sdk-package": "platforms;android-30"
          }
        ]
      }
      """
        .trimIndent()

    val fileMap =
      mapOf(
        "my-template/.template/template-definition.json" to jsonContent.toByteArray(Charsets.UTF_8),
        "my-template/.template/icon.png" to byteArrayOf(1, 2, 3),
        "my-template/src/MainActivity.kt" to "class MainActivity".toByteArray(Charsets.UTF_8),
      )

    createZipFile(zipFile, fileMap)

    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val factory = TemplateEngineFactory.createDefault()
    val builder = factory.createTemplateListBuilder(messageSink)

    // Act
    builder.loadFromZipFile(zipFile)
    val list = builder.toTemplateList()

    // Assert
    assertThat(list.templates).hasSize(1)
    val template = list.templates[0]
    assertThat(list.storages).hasSize(1)
    assertThat(list.storages.first()).isInstanceOf(ZipFileTemplateStorage::class.java)
    assertThat(template.name).isEqualTo("My Great Template")
    assertThat(template.shortName).isEqualTo("my-great-template")
    assertThat(template.metadata.tags).containsExactly("tagA", "tagB")

    // Verify template files list
    assertThat(template.files).hasSize(1)
    assertThat(template.files[0].relativePath).isEqualTo("src/MainActivity.kt")

    // Verify extra files list
    assertThat(template.extraFiles).hasSize(1)
    assertThat(template.extraFiles[0].relativePath).isEqualTo(".template/icon.png")

    // Verify files can be loaded on-demand
    template.loader.withLoader { loader ->
      val mainActivity = loader.loadFile(TemplateFileEntry("src/MainActivity.kt"))
      assertThat(mainActivity.relativePath).isEqualTo("src/MainActivity.kt")
      assertThat(mainActivity.content.toString(Charsets.UTF_8)).isEqualTo("class MainActivity")

      val icon = loader.loadFile(TemplateFileEntry(".template/icon.png"))
      assertThat(icon.relativePath).isEqualTo(".template/icon.png")
      assertThat(icon.content).isEqualTo(byteArrayOf(1, 2, 3))
    }
  }

  @Test
  fun `test loadFromZipStream loads extra files and template files content`() {
    // Prepare
    val jsonContent =
      """
      {
        "name": "Stream Template",
        "short-name": "stream-template"
      }
      """
        .trimIndent()

    val fileMap =
      mapOf(
        "my-template/.template/template-definition.json" to jsonContent.toByteArray(Charsets.UTF_8),
        "my-template/.template/sub/extra.txt" to "extra file".toByteArray(Charsets.UTF_8),
        "my-template/src/MainActivity.kt" to "main activity".toByteArray(Charsets.UTF_8),
      )

    val zipBytes = createZipBytes(fileMap)
    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val factory = TemplateEngineFactory.createDefault()
    val builder = factory.createTemplateListBuilder(messageSink)

    // Act
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipStream -> builder.loadFromZipStream(zipStream) }
    val list = builder.toTemplateList()

    // Assert
    assertThat(list.templates).hasSize(1)
    val template = list.templates[0]
    assertThat(list.storages).hasSize(1)
    assertThat(list.storages.first()).isNotInstanceOf(ZipFileTemplateStorage::class.java)
    assertThat(template.name).isEqualTo("Stream Template")
    assertThat(template.shortName).isEqualTo("stream-template")

    // Verify loaded files
    assertThat(template.files).hasSize(1)
    assertThat(template.files[0].relativePath).isEqualTo("src/MainActivity.kt")

    assertThat(template.extraFiles).hasSize(1)
    assertThat(template.extraFiles[0].relativePath).isEqualTo(".template/sub/extra.txt")

    // Verify contents are loaded and retrievable
    template.loader.withLoader { loader ->
      val mainActivity = loader.loadFile(TemplateFileEntry("src/MainActivity.kt"))
      assertThat(mainActivity.content.toString(Charsets.UTF_8)).isEqualTo("main activity")

      val extra = loader.loadFile(TemplateFileEntry(".template/sub/extra.txt"))
      assertThat(extra.content.toString(Charsets.UTF_8)).isEqualTo("extra file")
    }
  }

  @Test
  fun `test load template handles syntax or parsing errors`() {
    // Prepare
    val invalidJson =
      """
      {
        "name": "Invalid JSON",
        "short-name": "invalid-json",
        "tags": [123]
      }
      """
        .trimIndent()

    val fileMap = mapOf("my-template/.template/template-definition.json" to invalidJson.toByteArray(Charsets.UTF_8))

    val zipBytes = createZipBytes(fileMap)
    var errorReported = false
    val messageSink =
      object : DefaultTemplateMessageSink(TemplateMessageSink.Severity.Warn) {
        override fun onMessage(entry: MessageEntry) {
          if (entry.severity == TemplateMessageSink.Severity.Warn && entry.message.contains("Ignoring tag because it is not a string")) {
            errorReported = true
          }
        }
      }
    val factory = TemplateEngineFactory.createDefault()
    val builder = factory.createTemplateListBuilder(messageSink)

    // Act
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipStream -> builder.loadFromZipStream(zipStream) }
    val list = builder.toTemplateList()

    // Assert
    assertThat(list.templates).hasSize(1)
    val template = list.templates[0]
    assertThat(template.name).isEqualTo("Invalid JSON")
    assertThat(template.metadata.tags).isEmpty()
    assertThat(errorReported).isTrue()
  }

  @Test
  fun `test loadFromZipStream ignores miscellaneous files outside template directories`() {
    val jsonContent =
      """
      {
        "name": "Template In Zip",
        "short-name": "template-in-zip"
      }
      """
        .trimIndent()

    val fileMap =
      mapOf(
        "my-template/.template/template-definition.json" to jsonContent.toByteArray(Charsets.UTF_8),
        "my-template/src/MainActivity.kt" to "class MainActivity".toByteArray(Charsets.UTF_8),
        // Miscellaneous files outside of template directories
        "README.md" to "Global README".toByteArray(Charsets.UTF_8),
        "LICENSE" to "Global LICENSE".toByteArray(Charsets.UTF_8),
        "other-folder/some-file.txt" to "other file content".toByteArray(Charsets.UTF_8),
      )

    val zipBytes = createZipBytes(fileMap)
    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val factory = TemplateEngineFactory.createDefault()
    val builder = factory.createTemplateListBuilder(messageSink)

    // Act
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipStream -> builder.loadFromZipStream(zipStream) }
    val list = builder.toTemplateList()

    // Assert
    assertThat(list.templates).hasSize(1)
    val template = list.templates[0]
    assertThat(template.name).isEqualTo("Template In Zip")
    assertThat(template.files).hasSize(1)
    assertThat(template.files[0].relativePath).isEqualTo("src/MainActivity.kt")
  }

  @Test
  fun `test copyAndLoad helper methods preload files and use cache`() {
    // Prepare metadata
    val metadata =
      TemplateMetadata(
        sourceLocation = FileLocation("template.json", 0),
        name = "Test Template",
        shortName = "test-template",
        tags = emptyList(),
        arguments = emptyList(),
        dependencies = emptyList(),
        transformations = emptyList(),
        schemaVersion = SchemaVersion.implicitVersion,
      )

    val templateFileEntry = TemplateFileEntry("src/File.kt")
    val extraFileEntry = TemplateFileEntry(".template/icon.png")

    var loadCount = 0
    val innerLoader =
      TemplateFileLoader.forFunction { entry ->
        loadCount++
        TemplateFile(entry.relativePath, "content of ${entry.relativePath}".toByteArray(Charsets.UTF_8))
      }

    val template =
      TemplateDefinition(metadata = metadata, files = listOf(templateFileEntry), extraFiles = listOf(extraFileEntry), loader = innerLoader)

    // Initially, loadCount should be 0
    assertThat(loadCount).isEqualTo(0)

    // 1. Test copyAndLoadExtraFiles
    loadCount = 0
    val extraLoadedTemplate = template.copyAndLoadExtraFiles()
    // It should have preloaded the extraFiles immediately
    assertThat(loadCount).isEqualTo(1)

    // Loading the extra file again should NOT trigger the fallback loader
    extraLoadedTemplate.loader.withLoader { loader ->
      val file = loader.loadFile(extraFileEntry)
      assertThat(file.content.toString(Charsets.UTF_8)).isEqualTo("content of .template/icon.png")
    }
    assertThat(loadCount).isEqualTo(1)

    // Loading a standard template file should trigger the fallback loader (and increment loadCount)
    extraLoadedTemplate.loader.withLoader { loader ->
      val file = loader.loadFile(templateFileEntry)
      assertThat(file.content.toString(Charsets.UTF_8)).isEqualTo("content of src/File.kt")
    }
    assertThat(loadCount).isEqualTo(2)

    // 2. Test copyAndLoadTemplateFiles
    loadCount = 0
    val templateLoadedTemplate = template.copyAndLoadTemplateFiles()
    // It should have preloaded the standard files immediately
    assertThat(loadCount).isEqualTo(1)

    // Loading the standard file again should NOT trigger the fallback loader
    templateLoadedTemplate.loader.withLoader { loader ->
      val file = loader.loadFile(templateFileEntry)
      assertThat(file.content.toString(Charsets.UTF_8)).isEqualTo("content of src/File.kt")
    }
    assertThat(loadCount).isEqualTo(1)

    // Loading an extra file should trigger the fallback loader
    templateLoadedTemplate.loader.withLoader { loader ->
      val file = loader.loadFile(extraFileEntry)
      assertThat(file.content.toString(Charsets.UTF_8)).isEqualTo("content of .template/icon.png")
    }
    assertThat(loadCount).isEqualTo(2)

    // 3. Test copyAndLoadAllFiles
    loadCount = 0
    val allLoadedTemplate = template.copyAndLoadAllFiles()
    // It should have preloaded both files immediately
    assertThat(loadCount).isEqualTo(2)

    // Loading either file should NOT trigger the fallback loader
    allLoadedTemplate.loader.withLoader { loader ->
      val file1 = loader.loadFile(templateFileEntry)
      val file2 = loader.loadFile(extraFileEntry)
      assertThat(file1.content.toString(Charsets.UTF_8)).isEqualTo("content of src/File.kt")
      assertThat(file2.content.toString(Charsets.UTF_8)).isEqualTo("content of .template/icon.png")
    }
    assertThat(loadCount).isEqualTo(2)
  }

  private fun createZipFile(path: Path, files: Map<String, ByteArray>) {
    ZipOutputStream(Files.newOutputStream(path)).use { zos ->
      for ((name, content) in files) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content)
        zos.closeEntry()
      }
    }
  }

  private fun createZipBytes(files: Map<String, ByteArray>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
      for ((name, content) in files) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content)
        zos.closeEntry()
      }
    }
    return out.toByteArray()
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun allFileSystems(): Collection<Array<Any>> = DefaultFileStorageTest.allFileSystems()
  }
}
