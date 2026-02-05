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

package com.android.build.gradle.integration.common.fixture.project.builder

import com.android.SdkConstants
import com.android.utils.toSystemLineSeparator
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.jetbrains.annotations.VisibleForTesting
import org.junit.Assert

/** Allows manipulating files of a [GenericProjectDefinition] */
@GradleDefinitionDsl
interface GradleProjectFiles {

  /**
   * Adds a file to the given location with the given content.
   *
   * If the file already exists, an exception is thrown
   */
  fun add(relativePath: String, content: String)

  /**
   * Adds a file to the given location with the given content.
   *
   * If the file already exists, an exception is thrown
   */
  fun add(relativePath: String, content: ByteArray)

  /** Returns a [FileUpdateBuilder] to update the content of a file. */
  fun update(relativePath: String): FileUpdateBuilder

  /** Update a file via the provided action on a [FileUpdateBuilder] */
  fun update(relativePath: String, action: FileUpdateBuilder.() -> Unit) {
    action(update(relativePath))
  }

  /** Removes the file at the given location. It is OK if the file does not exist */
  fun remove(relativePath: String)
}

@GradleDefinitionDsl
interface FileUpdateBuilder {
  val exists: Boolean

  /**
   * Replaces the content of the file with the provided content.
   *
   * If the file does not exist, a new file is created with the provided content.
   */
  fun replaceWith(newContent: String)

  /**
   * Replaces the content of the file with the provided content.
   *
   * If the file does not exist, a new file is created with the provided content.
   */
  fun replaceWith(newContent: ByteArray)

  /**
   * Search the content of the file with the given string and replace all occurrences with the new content.
   *
   * The file must always exist or an exception is thrown. If the string to search is not found an exception is thrown, unless `lenient` is
   * set to `true`.
   *
   * @param search the string to search for
   * @param replace the string with which to replace all occurrences of `search`
   * @param lenient whether the call is lenient to content that don't have any occurrence of `search`
   */
  fun searchAndReplace(search: String, replace: String, lenient: Boolean = false): FileUpdateBuilder

  /**
   * Search the content of the file with the given string and replace all occurrences with the new content.
   *
   * The file must always exist or an exception is thrown. If the string to search is not found an exception is thrown, unless `lenient` is
   * set to `true`.
   *
   * @param search the string to search for
   * @param replace the string with which to replace all occurrences of `search`
   * @param lenient whether the call is lenient to content that don't have any occurrence of `search`
   */
  fun searchAndReplace(search: Regex, replace: String, lenient: Boolean = false): FileUpdateBuilder

  /**
   * Appends the content of the file with the provided content.
   *
   * If the file does not exist, a new file is created with the provided content.
   */
  fun append(newContent: String)

  /**
   * Appends the provided method body to the Kotlin/Java class.
   *
   * The file must always exist or an exception is thrown. The file extension must be '.kt'/'.java'.
   */
  fun appendMethod(method: String): FileUpdateBuilder

  /**
   * Transforms the file with the provided lambda.
   *
   * The lambda receives the current content as a string, and returns the new content for the file.
   *
   * The file must always exist or an exception is thrown.
   */
  fun transform(action: (String) -> String): FileUpdateBuilder

  /** Destination of the file to be moved. If there is an existing file, it will be overwritten. */
  fun moveTo(relativePath: String): FileUpdateBuilder
}

/**
 * Allows manipulating files of a [GradleProject] that is an Android project
 *
 * The main goal is to give access to the namespace to create files in the right location.
 */
@GradleDefinitionDsl
interface AndroidProjectFiles : GradleProjectFiles {
  val namespace: String
  val namespaceAsPath: String

  /** Sets up a basic minimum Manifest, enough to build some projects */
  fun setupMinimumManifest() {
    update("src/main/AndroidManifest.xml")
      .replaceWith(
        // language=XML
        """
        |                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
        |                             xmlns:dist="http://schemas.android.com/apk/distribution">
        |                        <application />
        |                    </manifest>
        """
          .trimMargin()
      )
  }
}

/**
 * Implementation of [GradleProjectFiles] that only records the actions but does not yet write anything on disk. This is done later when the
 * project is created via [write]
 *
 * It is possible to transform this implementation to instead directly manipulate the file system. This is done by calling [makeDirect],
 * after which the implementation is superseded by a [DirectGradleProjectFiles] instance.
 */
internal open class DelayedGradleProjectFiles : GradleProjectFiles {
  // map from relative path to file content
  @get:VisibleForTesting internal val sourceFiles = mutableMapOf<String, Any>()

  @get:VisibleForTesting internal var directFiles: DirectGradleProjectFiles? = null

  val isDirect: Boolean
    get() = directFiles != null

  internal open fun makeDirect(location: Path) {
    directFiles = DirectGradleProjectFiles(location)
    sourceFiles.clear()
  }

  override fun add(relativePath: String, content: String) {
    val dFiles = directFiles
    if (dFiles != null) {
      dFiles.add(relativePath, content)
      return
    }

    val existingContent = sourceFiles[relativePath]
    if (existingContent != null) {
      throw RuntimeException("A file already exist at $relativePath")
    }

    sourceFiles[relativePath] = content
  }

  override fun add(relativePath: String, content: ByteArray) {
    val dFiles = directFiles
    if (dFiles != null) {
      dFiles.add(relativePath, content)
      return
    }

    val existingContent = sourceFiles[relativePath]
    if (existingContent != null) {
      throw RuntimeException("A file already exist at $relativePath")
    }

    sourceFiles[relativePath] = content
  }

  override fun update(relativePath: String): FileUpdateBuilder {
    val dFiles = directFiles
    if (dFiles != null) {
      return dFiles.update(relativePath)
    }

    return FileUpdater(sourceFiles, relativePath)
  }

  override fun remove(relativePath: String) {
    val dFiles = directFiles
    if (dFiles != null) {
      dFiles.remove(relativePath)
      return
    }

    sourceFiles.remove(relativePath)
  }

  internal fun write(location: Path) {
    if (directFiles != null) {
      throw RuntimeException("Should not call write after makeDirect()")
    }
    // write the content of the project
    for ((path, content) in sourceFiles) {

      val fileLocation = location.resolve(path)
      fileLocation.parent.createDirectories()
      when (content) {
        is String -> {
          fileLocation.writeText(content)
        }
        is ByteArray -> {
          fileLocation.writeBytes(content)
        }
        else -> throw RuntimeException("Unsupported file content type for $path")
      }
    }
  }

  private class FileUpdater(private val map: MutableMap<String, Any>, private val key: String) : FileUpdateBuilder {

    override val exists: Boolean
      get() = map[key] != null

    override fun replaceWith(newContent: String) {
      map[key] = newContent
    }

    override fun replaceWith(newContent: ByteArray) {
      map[key] = newContent
    }

    override fun searchAndReplace(search: String, replace: String, lenient: Boolean): FileUpdateBuilder {
      return searchAndReplace(search.toRegex(RegexOption.LITERAL), replace, lenient)
    }

    override fun searchAndReplace(search: Regex, replace: String, lenient: Boolean): FileUpdateBuilder {
      val content = map[key] ?: throw RuntimeException("File $key not found. Cannot searchAndReplace")

      val stringContent = content as? String ?: throw RuntimeException("Can only do searchAndReplace on string content (key: $key")

      map[key] = stringContent.searchAndReplace(key, search, replace, lenient)
      return this
    }

    override fun append(newContent: String) {
      val content = map[key]

      if (content == null) {
        map[key] = newContent
      } else {
        val stringContent = content as? String ?: throw RuntimeException("Can only do append on string content (key: $key")

        map[key] = stringContent + newContent
      }
    }

    override fun transform(action: (String) -> String): FileUpdateBuilder {
      val content = map[key] ?: throw RuntimeException("File $key not found. Cannot transform")

      val stringContent = content as? String ?: throw RuntimeException("Can only do transform on string content (key: $key")

      map[key] = action(stringContent)
      return this
    }

    override fun appendMethod(methodBody: String): FileUpdateBuilder {
      if (!key.endsWith(SdkConstants.DOT_KT) && !key.endsWith(SdkConstants.DOT_JAVA)) {
        throw RuntimeException(
          "Cannot append method to content at key '$key'." + "File must end in '${SdkConstants.DOT_KT}' or '${SdkConstants.DOT_JAVA}'"
        )
      }
      return searchAndReplace(Regex("\n}\\s*$"), "\n    $methodBody\n\n}")
    }

    override fun moveTo(relativePath: String): FileUpdater {
      map[relativePath] = map[key] ?: throw RuntimeException("File $key not found. Cannot move.")
      map.remove(key)
      return this
    }
  }
}

/**
 * This is an implementation of [GradleProjectFiles] that directly writes into a root folder.
 *
 * The relative paths provided via the API are resolved from this root, and each call directly impacts the file system.
 */
internal open class DirectGradleProjectFiles(@get:VisibleForTesting internal val location: Path) : GradleProjectFiles {

  override fun add(relativePath: String, content: String) {
    val file = location.resolve(relativePath)

    if (file.isRegularFile()) {
      throw RuntimeException("A file already exist at $relativePath")
    }

    file.parent.createDirectories()
    file.writeText(content)
  }

  override fun add(relativePath: String, content: ByteArray) {
    val file = location.resolve(relativePath)

    if (file.isRegularFile()) {
      throw RuntimeException("A file already exist at $relativePath")
    }

    file.parent.createDirectories()
    file.writeBytes(content)
  }

  override fun update(relativePath: String): FileUpdateBuilder = FileUpdater(location.resolve(relativePath), location)

  override fun remove(relativePath: String) {
    location.resolve(relativePath).deleteIfExists()
  }

  private class FileUpdater(private val file: Path, private val location: Path) : FileUpdateBuilder {

    override val exists: Boolean
      get() = file.isRegularFile()

    override fun replaceWith(newContent: String) {
      file.parent.createDirectories()
      file.writeText(newContent)
    }

    override fun replaceWith(newContent: ByteArray) {
      file.parent.createDirectories()
      file.writeBytes(newContent)
    }

    override fun searchAndReplace(search: String, replace: String, lenient: Boolean): FileUpdateBuilder {
      return searchAndReplace(search.toRegex(RegexOption.LITERAL), replace, lenient)
    }

    override fun searchAndReplace(search: Regex, replace: String, lenient: Boolean): FileUpdateBuilder {
      val content = if (file.isRegularFile()) file.readText() else throw RuntimeException("File $file not found. Cannot update")

      file.writeText(content.searchAndReplace(file.toString(), search, replace, lenient))

      return this
    }

    override fun append(newContent: String) {
      val oldContent = if (file.isRegularFile()) file.readText() else null
      file.parent.createDirectories()
      file.writeText(oldContent?.let { it + newContent } ?: newContent)
    }

    override fun appendMethod(methodBody: String): FileUpdateBuilder {
      if (file.extension != SdkConstants.EXT_JAVA && file.extension != SdkConstants.EXT_KT) {
        throw RuntimeException(
          "Cannot append method to $file. " + "File extension must be '${SdkConstants.DOT_JAVA}' or ${SdkConstants.DOT_KT}"
        )
      }
      return searchAndReplace(Regex("\n}\\s*$"), "\n    $methodBody\n\n}")
    }

    override fun transform(action: (String) -> String): FileUpdateBuilder {
      val content = if (file.isRegularFile()) file.readText() else throw RuntimeException("File $file not found. Cannot update")

      file.writeText(action(content))
      return this
    }

    override fun moveTo(relativePath: String): FileUpdateBuilder {
      val destination = location.resolve(relativePath)
      destination.parent.createDirectories()
      file.moveTo(destination, true)
      return this
    }
  }
}

internal class DelayedAndroidProjectFiles(private val namespaceProvider: () -> String) : DelayedGradleProjectFiles(), AndroidProjectFiles {

  override fun makeDirect(location: Path) {
    directFiles = DirectAndroidProjectFiles(location, namespaceProvider())
    sourceFiles.clear()
  }

  override val namespace: String
    get() = namespaceProvider()

  override val namespaceAsPath: String
    get() = namespace.replace('.', '/')
}

internal class DirectAndroidProjectFiles(location: Path, override val namespace: String) :
  DirectGradleProjectFiles(location), AndroidProjectFiles {
  override val namespaceAsPath: String
    get() = namespaceAsPath.replace('.', '/')
}

internal fun String.searchAndReplace(name: String, search: Regex, replace: String, lenient: Boolean): String {
  var rwReplace = replace

  // Handle patterns that use unix-style line endings even on Windows where the test
  // projects are sometimes checked out with Windows-style endings depending on the .gitconfig
  // "autocrlf" property
  if (this.contains("\r\n")) {
    rwReplace = replace.toSystemLineSeparator()
  }
  val newContent = search.replace(this, rwReplace)
  if (!lenient) {
    Assert.assertNotEquals(
      """
                No match in file
                - File:   $name
                - Search: $search
                - Replace: $replace
            """
        .trimIndent(),
      this,
      newContent,
    )
  }

  return newContent
}
