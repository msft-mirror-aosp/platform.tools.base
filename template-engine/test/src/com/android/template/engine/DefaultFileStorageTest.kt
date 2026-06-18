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

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createFile
import kotlin.io.path.getPosixFilePermissions
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@Suppress("FunctionName")
@RunWith(Parameterized::class)
class DefaultFileStorageTest(private val fileSystemId: FileSystemId) {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `default file storage set execute bit on gradlew`() {
    // Prepare
    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val rootPath = getTestRootPath()
    val storage = DefaultFileStorage(messageSink, destinationPathProvider = { rootPath })
    val templateFile = TemplateFile("gradlew", byteArrayOf())

    // Act
    storage.saveFile(templateFile)

    // Assert
    val path = rootPath.resolve("gradlew")
    // On platforms where posix attributes are not supported (i.e. windows), the test implicitly
    // checks that no exception is thrown
    if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
      assertThat(path.getPosixFilePermissions().contains(PosixFilePermission.OWNER_EXECUTE)).isTrue()
    }
  }

  @Test
  fun `default file storage throws lazily if path is invalid`() {
    // Prepare
    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val rootPath = getTestRootPath()
    val invalidPathString = "\u0000" // Invalid with all known file systems
    val storage = DefaultFileStorage(messageSink, destinationPathProvider = { rootPath.resolve(invalidPathString) })

    // Assert
    assertThrows(InvalidPathException::class.java) { storage.checkDestinationDirectoryIsEmpty() }
    assertThrows(InvalidPathException::class.java) { storage.createDestinationDirectory() }
    assertThrows(InvalidPathException::class.java) { storage.saveFile(TemplateFile("test.txt", byteArrayOf())) }
  }

  @Test
  fun `check destination directory is empty fails if it exists`() {
    // Prepare
    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val rootPath = getTestRootPath()
    val storage = DefaultFileStorage(messageSink, destinationPathProvider = { rootPath })

    // Act
    Files.createDirectories(rootPath)
    Files.createFile(rootPath.resolve("someFile.txt"))

    // Assert
    assertThrows(IOException::class.java) { storage.checkDestinationDirectoryIsEmpty() }
  }

  @Test
  fun `check destination directory is empty succeeds`() {
    // Prepare
    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val rootPath = getTestRootPath()
    val storage = DefaultFileStorage(messageSink, destinationPathProvider = { rootPath })

    // Act
    Files.createDirectories(rootPath)

    // Assert
    storage.checkDestinationDirectoryIsEmpty()
  }

  @Test
  fun `create destination directory`() {
    // Prepare
    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val rootPath = getTestRootPath()
    val storage = DefaultFileStorage(messageSink, destinationPathProvider = { rootPath })

    // Act
    storage.createDestinationDirectory()

    // Assert
    assertThat(Files.exists(rootPath)).isTrue()
    assertThat(Files.isDirectory(rootPath)).isTrue()
  }

  private fun getTestRootPath(): Path {
    return getTestRootPath(fileSystemId, tempFolder)
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun allFileSystems(): Collection<Array<Any>> {
      return FileSystemId.entries.map { arrayOf(it) }
    }

    fun getTestRootPath(fileSystemId: FileSystemId, temporaryFolder: TemporaryFolder): Path {
      return when (fileSystemId) {
        FileSystemId.JimfsWindows -> {
          val config = Configuration.windows()
          Jimfs.newFileSystem(config).getPath("c:\\foo")
        }

        FileSystemId.JimfsUnix -> {
          val config = Configuration.unix().toBuilder().setAttributeViews("basic", "owner", "posix", "unix").build()
          Jimfs.newFileSystem(config).getPath("/foo")
        }

        FileSystemId.JimfsMacos -> {
          val config = Configuration.osX().toBuilder().setAttributeViews("basic", "owner", "posix", "unix").build()
          Jimfs.newFileSystem(config).getPath("/foo")
        }

        FileSystemId.DefaultFileSystem -> {
          temporaryFolder.root.toPath()
        }
      }
    }

    enum class FileSystemId {
      JimfsWindows,
      JimfsUnix,
      JimfsMacos,
      DefaultFileSystem,
    }
  }
}
