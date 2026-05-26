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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

@Suppress("FunctionName")
class TemplateListTest {

  @Test
  fun `test copyAndLoadFiles preloads correct files and opens and closes storage`() {
    // Prepare metadata
    val metadata1 = createMetadata("template1")
    val metadata2 = createMetadata("template2")

    val file1 = TemplateFileEntry("src/File1.kt")
    val file2 = TemplateFileEntry("src/File2.txt")
    val extraFile = TemplateFileEntry(".template/icon.png")

    val spyStorage = SpyStorage()

    var loadCount1 = 0
    val loader1 =
      TestLoader(
        storage = spyStorage,
        files = mapOf(file1 to "content1".toByteArray(Charsets.UTF_8), file2 to "content2".toByteArray(Charsets.UTF_8)),
        onFileLoaded = { loadCount1++ },
      )

    var loadCount2 = 0
    val loader2 =
      TestLoader(
        storage = spyStorage,
        files = mapOf(extraFile to "icon_bytes".toByteArray(Charsets.UTF_8)),
        onFileLoaded = { loadCount2++ },
      )

    val def1 = TemplateDefinition(metadata1, listOf(file1, file2), emptyList(), loader1)
    val def2 = TemplateDefinition(metadata2, emptyList(), listOf(extraFile), loader2)

    val list = TemplateList(listOf(def1, def2))

    // Verify initial count
    assertThat(spyStorage.openCount).isEqualTo(0)
    assertThat(spyStorage.closeCount).isEqualTo(0)

    // Act
    // We want to preload files with relativePath containing "File1" or "icon"
    val resultList = list.copyAndLoadFiles { _, entry -> entry.relativePath.contains("File1") || entry.relativePath.contains("icon") }

    // Assert
    // Verify storage was opened and closed exactly once
    assertThat(spyStorage.openCount).isEqualTo(1)
    assertThat(spyStorage.closeCount).isEqualTo(1)

    // During copyAndLoadFiles, file1 of def1 and extraFile of def2 should have been loaded
    assertThat(loadCount1).isEqualTo(1) // loaded file1
    assertThat(loadCount2).isEqualTo(1) // loaded extraFile

    // Verify caching behavior in resulting list:
    val resultDef1 = resultList.templates[0]
    val resultDef2 = resultList.templates[1]

    // Fetching preloaded file1 should NOT call underlying loader
    resultDef1.loader.withLoader { loader ->
      val loaded = loader.loadFile(file1)
      assertThat(loaded.content.toString(Charsets.UTF_8)).isEqualTo("content1")
    }
    assertThat(loadCount1).isEqualTo(1) // Still 1

    // Fetching non-preloaded file2 should trigger the underlying loader
    resultDef1.loader.withLoader { loader ->
      val loaded = loader.loadFile(file2)
      assertThat(loaded.content.toString(Charsets.UTF_8)).isEqualTo("content2")
    }
    assertThat(loadCount1).isEqualTo(2) // Incremented to 2

    // Fetching preloaded extraFile should NOT call underlying loader
    resultDef2.loader.withLoader { loader ->
      val loaded = loader.loadFile(extraFile)
      assertThat(loaded.content.toString(Charsets.UTF_8)).isEqualTo("icon_bytes")
    }
    assertThat(loadCount2).isEqualTo(1) // Still 1
  }

  @Test
  fun `test copyAndLoadFiles cleans up storage even if copy fails`() {
    // Prepare metadata
    val metadata = createMetadata("template1")
    val file = TemplateFileEntry("src/File1.kt")
    val spyStorage = SpyStorage()

    val loader =
      TestLoader(
        storage = spyStorage,
        files = emptyMap(), // This will cause loadFile to throw an exception
      )

    val def = TemplateDefinition(metadata, listOf(file), emptyList(), loader)
    val list = TemplateList(listOf(def))

    // Act & Assert
    assertThrows(Exception::class.java) { list.copyAndLoadFiles { _, _ -> true } }

    // Verify storage was closed despite the failure
    assertThat(spyStorage.openCount).isEqualTo(1)
    assertThat(spyStorage.closeCount).isEqualTo(1)
  }

  @Test
  fun `test copyAndLoadExtraFiles preloads only extra files`() {
    // Prepare
    val metadata = createMetadata("template1")
    val file = TemplateFileEntry("src/File1.kt")
    val extraFile = TemplateFileEntry(".template/icon.png")
    val spyStorage = SpyStorage()

    var loadCount = 0
    val loader =
      TestLoader(
        storage = spyStorage,
        files = mapOf(file to "src_content".toByteArray(Charsets.UTF_8), extraFile to "extra_content".toByteArray(Charsets.UTF_8)),
        onFileLoaded = { loadCount++ },
      )

    val def = TemplateDefinition(metadata, listOf(file), listOf(extraFile), loader)
    val list = TemplateList(listOf(def))

    // Act
    val resultList = list.copyAndLoadExtraFiles()

    // Assert
    // It should have preloaded only extraFile
    assertThat(loadCount).isEqualTo(1)

    // Verify cache is used for extra file
    val resultDef = resultList.templates[0]
    resultDef.loader.withLoader { l ->
      val loaded = l.loadFile(extraFile)
      assertThat(loaded.content.toString(Charsets.UTF_8)).isEqualTo("extra_content")
    }
    assertThat(loadCount).isEqualTo(1) // Still 1 (cached)

    // Standard template file should be loaded dynamically
    resultDef.loader.withLoader { l ->
      val loaded = l.loadFile(file)
      assertThat(loaded.content.toString(Charsets.UTF_8)).isEqualTo("src_content")
    }
    assertThat(loadCount).isEqualTo(2) // Incremented to 2
  }

  @Test
  fun `test copyAndLoadTemplateFiles preloads only standard files`() {
    // Prepare
    val metadata = createMetadata("template1")
    val file = TemplateFileEntry("src/File1.kt")
    val extraFile = TemplateFileEntry(".template/icon.png")
    val spyStorage = SpyStorage()

    var loadCount = 0
    val loader =
      TestLoader(
        storage = spyStorage,
        files = mapOf(file to "src_content".toByteArray(Charsets.UTF_8), extraFile to "extra_content".toByteArray(Charsets.UTF_8)),
        onFileLoaded = { loadCount++ },
      )

    val def = TemplateDefinition(metadata, listOf(file), listOf(extraFile), loader)
    val list = TemplateList(listOf(def))

    // Act
    val resultList = list.copyAndLoadTemplateFiles()

    // Assert
    // It should have preloaded only standard file
    assertThat(loadCount).isEqualTo(1)

    // Verify cache is used for standard file
    val resultDef = resultList.templates[0]
    resultDef.loader.withLoader { l ->
      val loaded = l.loadFile(file)
      assertThat(loaded.content.toString(Charsets.UTF_8)).isEqualTo("src_content")
    }
    assertThat(loadCount).isEqualTo(1) // Still 1 (cached)

    // Extra file should be loaded dynamically
    resultDef.loader.withLoader { l ->
      val loaded = l.loadFile(extraFile)
      assertThat(loaded.content.toString(Charsets.UTF_8)).isEqualTo("extra_content")
    }
    assertThat(loadCount).isEqualTo(2) // Incremented to 2
  }

  @Test
  fun `test copyAndLoadAllFiles preloads both standard and extra files`() {
    // Prepare
    val metadata = createMetadata("template1")
    val file = TemplateFileEntry("src/File1.kt")
    val extraFile = TemplateFileEntry(".template/icon.png")
    val spyStorage = SpyStorage()

    var loadCount = 0
    val loader =
      TestLoader(
        storage = spyStorage,
        files = mapOf(file to "src_content".toByteArray(Charsets.UTF_8), extraFile to "extra_content".toByteArray(Charsets.UTF_8)),
        onFileLoaded = { loadCount++ },
      )

    val def = TemplateDefinition(metadata, listOf(file), listOf(extraFile), loader)
    val list = TemplateList(listOf(def))

    // Act
    val resultList = list.copyAndLoadAllFiles()

    // Assert
    // It should have preloaded both files immediately
    assertThat(loadCount).isEqualTo(2)

    // Verify cache is used for both files
    val resultDef = resultList.templates[0]
    resultDef.loader.withLoader { l ->
      val loadedFile = l.loadFile(file)
      val loadedExtra = l.loadFile(extraFile)
      assertThat(loadedFile.content.toString(Charsets.UTF_8)).isEqualTo("src_content")
      assertThat(loadedExtra.content.toString(Charsets.UTF_8)).isEqualTo("extra_content")
    }
    assertThat(loadCount).isEqualTo(2) // Still 2 (both cached)
  }

  private fun createMetadata(shortName: String): TemplateMetadata {
    return TemplateMetadata(
      sourceLocation = FileLocation("template.json", 0),
      name = shortName,
      shortName = shortName,
      tags = emptyList(),
      arguments = emptyList(),
      dependencies = emptyList(),
      transformations = emptyList(),
      schemaVersion = SchemaVersion.implicitVersion,
    )
  }

  private class SpyStorage : TemplateDefinitionStorage {
    var openCount = 0
    var closeCount = 0

    override fun open(): TemplateDefinitionStorage.Handle {
      openCount++
      return object : TemplateDefinitionStorage.Handle {
        override fun close() {
          closeCount++
        }
      }
    }
  }

  private class TestLoader(
    override val storage: TemplateDefinitionStorage,
    val files: Map<TemplateFileEntry, ByteArray>,
    val onFileLoaded: () -> Unit = {},
  ) : TemplateFileLoader {
    private val loader =
      object : TemplateFileLoader.Loader {
        override fun loadFile(entry: TemplateFileEntry): TemplateFile {
          onFileLoaded()
          val content = files[entry] ?: throw IllegalArgumentException("Unknown file: ${entry.relativePath}")
          return TemplateFile(entry.relativePath, content)
        }
      }

    override fun <R> withLoader(block: (TemplateFileLoader.Loader) -> R): R {
      return block(loader)
    }
  }
}
