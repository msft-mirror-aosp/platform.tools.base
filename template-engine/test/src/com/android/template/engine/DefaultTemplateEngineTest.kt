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
import com.android.template.engine.TemplateMessageSink.Severity
import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import org.intellij.lang.annotations.Language
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@Suppress("FunctionName")
@RunWith(Parameterized::class)
class DefaultTemplateEngineTest(private val fileSystemId: FileSystemId) {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun `verifies dry run captures expression errors on small in memory template`() {
    val messageSink =
      object : DefaultTemplateMessageSink(Severity.Info) {
        override fun onMessage(entry: MessageEntry) {
          // Nothing to do, we capture in memory only
        }
      }
    val factory = TemplateEngineFactory.createDefault()
    val builder = factory.createTemplateListBuilder(messageSink)
    val files = listOf(createTemplateFile("file1.txt", "test-My Template Name--test"), createTemplateFile("file2.txt", "test"))
    @Language("json")
    val jsonFile =
      """
      {
        "name": "Template Name",
        "short-name": "template-name",
        "tags": ["tag1", "tag2"],
        "arguments" : [
          {
            "id" : "name",
            "default-value" : "My Template Name"
          }
        ],
        "transformations" : [
          {
            "rename-file" : {
              "selector" : {
                "glob" : "/file2.txt"
              },
              "source-path" : "file2.txt",
              "target-path" : "${"$"}{name.replace('a','a')}"
            }
          },
          {
            "string-replace" : {
              "selector" : {
                "glob" : "/file1.txt"
              },
              "from" : "My Template Name",
              "to" : "${"$"}{name.replace2('a','b')}"
            }
          }
        ]
      }
      """
        .trimIndent()

    val metadata = builder.parseTemplateMetadata("template.json", jsonFile)
    assertThat(metadata).isNotNull()

    val templateFileLoader =
      TemplateFileLoader.forFunction { entry: TemplateFileEntry -> files.first { it.relativePath == entry.relativePath } }
    val template =
      TemplateDefinition(
        metadata = metadata!!,
        files = files.map { TemplateFileEntry(it.relativePath) },
        extraFiles = emptyList(),
        loader = templateFileLoader,
      )
    val dependencyInstaller =
      object : DependencyInstaller {
        override fun installAndroidSdkPackage(packagePath: String) {
          // Nothing to do
        }
      }
    val engine = factory.createDefaultEngine(messageSink, dependencyInstaller, destinationPathProvider = { getTestRootPath() })
    engine.processTemplate(template, predefinedArguments = emptyMap(), explicitArguments = emptyMap())

    assertThat(messageSink.messages).hasSize(3)
    assertThat(messageSink.messages[1].severity).isEqualTo(Severity.Error)
    assertThat(messageSink.messages[1].message)
      .contains(
        "Template file 'file1.txt': Error executing transformation 'string-replace': Error evaluating expression '\${name.replace2('a','b')}' at position 7: Unknown method 'replace2'"
      )
    assertThat(messageSink.messages[2].severity).isEqualTo(Severity.Error)
    assertThat(messageSink.messages[2].message).contains("Failed to create project 'Template Name' due to previous error(s)")
  }

  @Test
  fun `verifies default-value expressions are evaluated`() {
    val messageSink =
      object : DefaultTemplateMessageSink(Severity.Verbose) {
        override fun onMessage(entry: MessageEntry) {
          // Nothing to do, we capture in memory only
        }
      }
    val factory = TemplateEngineFactory.createDefault()
    val builder = factory.createTemplateListBuilder(messageSink)
    val files = listOf(createTemplateFile("file1.txt", "test-My Template Name--test"), createTemplateFile("file2.txt", "test"))
    @Language("json")
    val jsonFile =
      """
      {
        "name": "Template Name",
        "short-name": "template-name",
        "tags": ["tag1", "tag2"],
        "arguments" : [
          {
            "id" : "name",
            "default-value" : "My Template Name"
          },
          {
            "id" : "name2",
            "default-value" : "${"$"}{name.replace('a', 'b')}"
          }
        ],
        "transformations" : [
          {
            "rename-file" : {
              "selector" : {
                "glob" : "/file2.txt"
              },
              "source-path" : "file2.txt",
              "target-path" : "${"$"}{name2}"
            }
          },
          {
            "string-replace" : {
              "selector" : {
                "glob" : "/file1.txt"
              },
              "from" : "My Template Name",
              "to" : "${"$"}{name2}"
            }
          }
        ]
      }
      """
        .trimIndent()

    val metadata = builder.parseTemplateMetadata("template.json", jsonFile)
    assertThat(metadata).isNotNull()

    val templateFileLoader =
      TemplateFileLoader.forFunction { entry: TemplateFileEntry -> files.first { it.relativePath == entry.relativePath } }
    val template =
      TemplateDefinition(
        metadata = metadata!!,
        files = files.map { TemplateFileEntry(it.relativePath) },
        extraFiles = emptyList(),
        loader = templateFileLoader,
      )
    val dependencyInstaller =
      object : DependencyInstaller {
        override fun installAndroidSdkPackage(packagePath: String) {
          // Nothing to do
        }
      }
    val engine = factory.createDefaultEngine(messageSink, dependencyInstaller, destinationPathProvider = { getTestRootPath() })
    engine.processTemplate(template, predefinedArguments = emptyMap(), explicitArguments = emptyMap())

    assertThat(messageSink.messages.map { it.severity }).doesNotContain(Severity.Error)
    assertThat(messageSink.messages.map { it.severity }).doesNotContain(Severity.Warn)
    assertThat(messageSink.messages.map { it.message })
      .contains("Effective arguments values: {name=My Template Name, name2=My Templbte Nbme}")
    assertThat(messageSink.messages.map { it.message })
      .contains("Template file 'file1.txt': Updating line 1 from [test-My Template Name--test] to [test-My Templbte Nbme--test]")
    assertThat(messageSink.messages.map { it.message }).contains("Template file 'file2.txt': Renaming file to 'My Templbte Nbme'")
    assertThat(messageSink.messages.map { it.message }).contains("Successfully created project 'Template Name' at '${getTestRootPath()}'")
  }

  @Test
  fun `verifies schema-version makes engine produce an error`() {
    val messageSink =
      object : DefaultTemplateMessageSink(Severity.Verbose) {
        override fun onMessage(entry: MessageEntry) {
          // Nothing to do, we capture in memory only
        }
      }
    val factory = TemplateEngineFactory.createDefault()
    val builder = factory.createTemplateListBuilder(messageSink)
    val files = listOf(createTemplateFile("file1.txt", "test-My Template Name--test"), createTemplateFile("file2.txt", "test"))
    @Language("json")
    val jsonFile =
      """
      {
        "name": "Template Name",
        "short-name": "template-name",
        "tags": ["tag1", "tag2"],
        "schema-version": "0.2"
      }
      """
        .trimIndent()

    val metadata = builder.parseTemplateMetadata("template.json", jsonFile)
    assertThat(metadata).isNotNull()

    val templateFileLoader =
      TemplateFileLoader.forFunction { entry: TemplateFileEntry -> files.first { it.relativePath == entry.relativePath } }
    val template =
      TemplateDefinition(
        metadata = metadata!!,
        files = files.map { TemplateFileEntry(it.relativePath) },
        extraFiles = emptyList(),
        loader = templateFileLoader,
      )
    val dependencyInstaller =
      object : DependencyInstaller {
        override fun installAndroidSdkPackage(packagePath: String) {
          // Nothing to do
        }
      }
    val engine = factory.createDefaultEngine(messageSink, dependencyInstaller, destinationPathProvider = { getTestRootPath() })
    engine.processTemplate(template, predefinedArguments = emptyMap(), explicitArguments = emptyMap())

    assertThat(messageSink.messages.map { it.severity }).contains(Severity.Error)
    assertThat(messageSink.messages.map { it.message })
      .contains(
        "Template 'template-name' uses schema version '0.2', but the current template engine only support versions up to '0.1'. Use \"android update\" to update to the latest template engine."
      )
    assertThat(messageSink.messages.map { it.message }).contains("Failed to create project 'Template Name' due to previous error(s)")
  }

  @Test
  fun `verifies CRLF line endings are preserved`() {
    val messageSink =
      object : DefaultTemplateMessageSink(Severity.Verbose) {
        override fun onMessage(entry: MessageEntry) {
          // Nothing to do, we capture in memory only
        }
      }
    val factory = TemplateEngineFactory.createDefault()
    val builder = factory.createTemplateListBuilder(messageSink)
    // Create content with CRLF endings
    val content = "line1: name\r\nline2: name\r\n"
    val files = listOf(createTemplateFile("file1.txt", content))
    @Language("json")
    val jsonFile =
      """
      {
        "name": "Template Name",
        "short-name": "template-name",
        "tags": ["tag1"],
        "arguments" : [
          {
            "id" : "name",
            "default-value" : "My Template Name"
          }
        ],
        "transformations" : [
          {
            "string-replace" : {
              "selector" : {
                "glob" : "/file1.txt"
              },
              "from" : "name",
              "to" : "${"$"}{name}"
            }
          }
        ]
      }
      """
        .trimIndent()

    val metadata = builder.parseTemplateMetadata("template.json", jsonFile)
    assertThat(metadata).isNotNull()

    val templateFileLoader =
      TemplateFileLoader.forFunction { entry: TemplateFileEntry -> files.first { it.relativePath == entry.relativePath } }
    val template =
      TemplateDefinition(
        metadata = metadata!!,
        files = files.map { TemplateFileEntry(it.relativePath) },
        extraFiles = emptyList(),
        loader = templateFileLoader,
      )
    val dependencyInstaller =
      object : DependencyInstaller {
        override fun installAndroidSdkPackage(packagePath: String) {
          // Nothing to do
        }
      }

    // We want to inspect the output file, so let's check where the output files go
    val testRootPath = getTestRootPath()
    val engine = factory.createDefaultEngine(messageSink, dependencyInstaller, destinationPathProvider = { testRootPath })
    engine.processTemplate(template, predefinedArguments = emptyMap(), explicitArguments = emptyMap())

    assertThat(messageSink.messages.map { it.severity }).doesNotContain(Severity.Error)

    // Let's read the saved file from disk and assert it has CRLF
    val savedFile = testRootPath.resolve("file1.txt")
    assertThat(java.nio.file.Files.exists(savedFile)).isTrue()
    val bytes = java.nio.file.Files.readAllBytes(savedFile)
    val expectedContent = "line1: My Template Name\r\nline2: My Template Name\r\n"
    assertThat(bytes).isEqualTo(expectedContent.toByteArray(Charsets.UTF_8))
  }

  private fun createTemplateFile(relativePath: String, content: String): TemplateFile {
    return TemplateFile(relativePath, content = content.toByteArray(Charsets.UTF_8))
  }

  private fun getTestRootPath(): Path {
    return DefaultFileStorageTest.getTestRootPath(fileSystemId, tempFolder).resolve("test-template")
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun allFileSystems(): Collection<Array<Any>> = DefaultFileStorageTest.allFileSystems()
  }
}
