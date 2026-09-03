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
class StringReplaceTransformationTest {
  private fun runStringReplace(
    templatePath: String,
    templateText: String,
    fromParameter: String,
    toParameter: String,
    argumentValue: String,
    selector: FileSelector,
  ): TemplateFile {
    val templateFile = TemplateFile(templatePath, templateText.toByteArray(Charsets.UTF_8))
    val registry = TransformationRegistry()
    val context = TransformationContext(registry, mapOf("value" to argumentValue))
    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val processor = TemplateFileContentProcessor(messageSink)
    val transformation = StringReplaceTransformation()
    val transformationDefinition =
      StringReplaceDefinition(sourceLocation = FileLocation("foo.json", 1), selector = selector, from = fromParameter, to = toParameter)
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
  fun `test StringReplace can use single file match selector`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World",
        fromParameter = "World",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace skips non-matching file selector`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World",
        fromParameter = "World",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/settings.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello World")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace can use glob match selector`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/src/main/java/MainActivity.kt",
        templateText = "Hello World",
        fromParameter = "World",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("**/*.kt"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/MainActivity.kt")
  }

  @Test
  fun `test StringReplace skips non-matching glob selector`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/src/main/java/MainActivity.kt",
        templateText = "Hello World",
        fromParameter = "World",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("**/*.xml"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello World")
    assertThat(outputFile.relativePath).isEqualTo("app/src/main/java/MainActivity.kt")
  }

  @Test
  fun `test StringReplace does nothing when from string is not found`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World",
        fromParameter = "Universe",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello World")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace replaces all occurrences of from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World, World!",
        fromParameter = "World",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!, Kotlin!!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with to string without value placeholder`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World",
        fromParameter = "World",
        toParameter = "Kotlin",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with empty argument value`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World",
        fromParameter = "World",
        toParameter = "Kotlin\${value}",
        argumentValue = "",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with empty from string`() {
    val exception =
      assertThrows(Exception::class.java) {
        /*val outputFile = */ runStringReplace(
          templatePath = "app/build.gradle.kts",
          templateText = "Hello World",
          fromParameter = "",
          toParameter = "Kotlin\${value}",
          argumentValue = "!",
          selector = FileSelector.Glob("/app/build.gradle.kts"),
        )
      }

    assertThat(exception).hasMessageThat().contains("'from' string cannot be empty")
  }

  @Test
  fun `test StringReplace with empty to string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World",
        fromParameter = "World",
        toParameter = "",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello ")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with multiple value placeholders in to string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World",
        fromParameter = "World",
        toParameter = "Kotlin\${value} and \${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin! and !")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with regex special characters in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World.?",
        fromParameter = "World.?",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with other regex special characters in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World*",
        fromParameter = "World*",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with plus regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World+",
        fromParameter = "World+",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with caret regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World^",
        fromParameter = "World^",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with dollar regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World$",
        fromParameter = "World$",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with pipe regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World|",
        fromParameter = "World|",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with open parenthesis regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World(",
        fromParameter = "World(",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with close parenthesis regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World)",
        fromParameter = "World)",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with open square bracket regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World[",
        fromParameter = "World[",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with close square bracket regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World]",
        fromParameter = "World]",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with backslash regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World\\",
        fromParameter = "World\\",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with open curly brace regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World{",
        fromParameter = "World{",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with close curly brace regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World}",
        fromParameter = "World}",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with dot regex special character in from string`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/build.gradle.kts",
        templateText = "Hello World.",
        fromParameter = "World.",
        toParameter = "Kotlin\${value}",
        argumentValue = "!",
        selector = FileSelector.Glob("/app/build.gradle.kts"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText).isEqualTo("Hello Kotlin!")
    assertThat(outputFile.relativePath).isEqualTo("app/build.gradle.kts")
  }

  @Test
  fun `test StringReplace with multi-line from and to strings`() {
    val outputFile =
      runStringReplace(
        templatePath = "app/lightbuild.yaml",
        templateText =
          """
          |  minSdk:
          |    version:
          |      release:
          |        apiLevel: 36
          """
            .trimMargin(),
        fromParameter = "  minSdk:\n    version:\n      release:\n        apiLevel: 36",
        toParameter = "  minSdk:\n    version:\n      release:\n        apiLevel: \${value}",
        argumentValue = "24",
        selector = FileSelector.Glob("/app/lightbuild.yaml"),
      )

    val resultText = String(outputFile.content, Charsets.UTF_8)
    assertThat(resultText)
      .isEqualTo(
        """
        |  minSdk:
        |    version:
        |      release:
        |        apiLevel: 24
        """
          .trimMargin()
      )
  }

  @Test
  fun `test parseJson returns null when string-replace is not an object`() {
    val json =
      """
      {
        "string-replace": "not-an-object"
      }
      """
        .trimIndent()
    val root = JsonSourceParser.parseString(json).asJsonObject!!
    val messageSink = DefaultTemplateMessageSink(TemplateMessageSink.Severity.Error)
    val parser = TemplateDefinitionParser(messageSink, "template.json")
    val transformation = StringReplaceTransformation()

    val result = transformation.parseJson(parser, root)

    assertThat(result).isNull()
    assertThat(messageSink.messages).hasSize(1)
    assertThat(messageSink.messages[0].severity).isEqualTo(TemplateMessageSink.Severity.Error)
    assertThat(messageSink.messages[0].message)
      .contains("template.json:0: Json object does not contain member named 'string-replace'")
  }
}

