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

import com.android.template.engine.impl.DirectoryBeforeFileComparator
import java.util.TreeMap
import java.util.zip.ZipInputStream

interface TemplateListBuilder {
  fun loadFromZipStream(zipStream: ZipInputStream): TemplateListBuilder

  fun parseTemplateMetadata(relativePath: String, json: String): TemplateMetadata?

  fun toTemplateList(): TemplateList
}

internal class TemplateListBuilderImpl(
  private val messageSink: TemplateMessageSink,
  private val registry: TransformationRegistry,
  private val filterTemplateDefinitionStrategy: TemplateEngineFactory.FilterTemplateDefinitionStrategy,
) : TemplateListBuilder {
  private val templateDefinitions = mutableListOf<TemplateDefinition>()

  override fun toTemplateList(): TemplateList {
    return TemplateList(templateDefinitions.filter { filterTemplateDefinitionStrategy.accept(it) })
  }

  override fun loadFromZipStream(zipStream: ZipInputStream): TemplateListBuilder {
    val fileContentsByRelativePath = TreeMap<String, ByteArray>(DirectoryBeforeFileComparator())
    var entry = zipStream.nextEntry

    while (entry != null) {
      if (!entry.isDirectory) {
        fileContentsByRelativePath[entry.name] = zipStream.readBytes()
      }
      entry = zipStream.nextEntry
    }

    templateDefinitions.addAll(parse(fileContentsByRelativePath))
    return this
  }

  fun parse(fileContentsByRelativePath: Map<String, ByteArray>): MutableList<TemplateDefinition> {
    val templateDirectoryName = ".template"
    val jsonFileLocation = "/$templateDirectoryName/template-definition.json"

    // Find all the template directories by looking the template "json" files
    val templateDirs =
      fileContentsByRelativePath.entries.mapNotNull {
        if (it.key.endsWith(jsonFileLocation)) {
          it.key.removeSuffix(jsonFileLocation)
        } else {
          null
        }
      }

    // Group files by template directory
    val filesByDir =
      fileContentsByRelativePath.entries.groupBy { fileContentsEntry ->
        templateDirs.firstOrNull { templateDir -> fileContentsEntry.key.startsWith("$templateDir/") } ?: ""
      }

    val templates = mutableListOf<TemplateDefinition>()
    for ((dir, entries) in filesByDir) {
      // Find template "json" file
      val jsonDefinition = entries.first { it.key.endsWith(jsonFileLocation) }

      val jsonContent = jsonDefinition.value.toString(Charsets.UTF_8)
      val parser = TemplateDefinitionParser(messageSink, jsonDefinition.key)
      val metadata = parseMetadata(parser, jsonContent)
      if (metadata == null || messageSink.hasErrors()) {
        messageSink.message(TemplateMessageSink.Severity.Error) {
          "Skipping template definition from '${parser.relativePath}' because of the errors above"
        }
      } else {
        val templateFiles =
          entries
            .filter {
              // Ignore all files in the `.template` directory
              !it.key.startsWith("$dir/$templateDirectoryName/")
            }
            .map { entry ->
              // Compute relative path to the `dir` directory
              val relativePath = entry.key.substringAfter("$dir/")
              TemplateFile(relativePath, entry.value)
            }

        templates.add(TemplateDefinition(metadata, templateFiles))
      }
    }

    return templates.also { messageSink.message(TemplateMessageSink.Severity.Debug) { "Successfully added ${it.size} templates" } }
  }

  override fun parseTemplateMetadata(relativePath: String, json: String): TemplateMetadata? {
    val parser = TemplateDefinitionParser(messageSink, relativePath)
    return parseMetadata(parser, json)
  }

  private fun parseMetadata(parser: TemplateDefinitionParser, json: String): TemplateMetadata? {
    val jsonDoc = JsonSourceParser.parseString(json)
    val jsonObject = jsonDoc.asJsonObject ?: return parser.addError(jsonDoc, "Top-level element should be a json object")
    val name = parser.getMandatoryString(jsonObject, "name") ?: return null
    val shortName = parser.getMandatoryString(jsonObject, "short-name") ?: return null
    val tags =
      jsonObject["tags"]?.asJsonArray?.mapNotNull { it.asString ?: parser.addWarning(it, "Ignoring tag because it is not a string") }
        ?: emptyList()

    val arguments =
      jsonObject["arguments"]?.asJsonArray?.mapNotNull { stepJson ->
        val stepObj = stepJson.asJsonObject ?: return@mapNotNull parser.addError(stepJson, "Json object expected")
        val id = stepObj["id"]?.asString ?: return@mapNotNull null
        val defaultValue = stepObj["default-value"]?.asString ?: return@mapNotNull null
        TemplateArgument(sourceLocation = parser.toSourceLocation(stepObj), id = id, defaultValue = defaultValue)
      } ?: emptyList()

    val dependencies =
      jsonObject["dependencies"]?.asJsonArray?.mapNotNull { stepJson ->
        val stepObj = stepJson.asJsonObject ?: return@mapNotNull parser.addError(stepJson, "Json object expected")
        val sdk = stepObj["sdk-package"]?.asString ?: return@mapNotNull parser.addError(stepObj, "'sdk-package' expected")
        TemplateDependency(sourceLocation = parser.toSourceLocation(stepObj), sdkPackage = sdk)
      } ?: emptyList()

    // Each argument contains either an array of transformations, or a single one
    val transformations =
      jsonObject["transformations"]?.asJsonArray?.let { stepArray -> parseTransformations(parser, registry, stepArray) } ?: emptyList()

    val schemaVersion =
      runCatching { parser.getOptionalString(jsonObject, "schema-version")?.let { SchemaVersion.fromString(it) } }
        .onFailure { t -> parser.addError<Unit>(jsonObject, "Invalid schema-version value: ${t.concatMessages()}") }
        .getOrNull() ?: SchemaVersion.implicitVersion

    return TemplateMetadata(
      sourceLocation = parser.toSourceLocation(jsonObject),
      name = name,
      shortName = shortName,
      tags = tags,
      arguments = arguments,
      dependencies = dependencies,
      transformations = transformations,
      schemaVersion = schemaVersion,
    )
  }

  private fun parseTransformations(
    parser: TemplateDefinitionParser,
    registry: TransformationRegistry,
    stepArray: JsonSourceArray,
  ): List<TransformationDefinition> {
    return stepArray.mapNotNull { it.asJsonObject?.let { obj -> parseTransformation(parser, registry, obj) } }
  }

  private fun parseTransformation(
    parser: TemplateDefinitionParser,
    registry: TransformationRegistry,
    stepObj: JsonSourceObject,
  ): TransformationDefinition? {
    return registry.findTransformForJsonField(stepObj)?.parseJson(parser, stepObj)
      ?: parser.addWarning(stepObj, "Ignoring unknown transformation")
  }
}
