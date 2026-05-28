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

import com.android.template.engine.TemplateListBuilderImpl.Companion.TEMPLATE_JSON_FILE_LOCATION
import com.android.template.engine.impl.DirectoryBeforeFileComparator
import java.nio.file.Path
import java.util.SortedMap
import java.util.TreeMap
import java.util.zip.ZipInputStream

interface TemplateListBuilder {

  /**
   * Loads all template definitions found in the [zipStream]. Given a [ZipInputStream] is forward only, all template files content is loaded
   * in memory, including [TemplateDefinition.extraFiles] and [TemplateDefinition.files].
   */
  fun loadFromZipStream(zipStream: ZipInputStream): TemplateListBuilder

  /**
   * Loads all template definitions found in the [zipFile]. The content of the template files is loaded accessed "on demand" through the
   * [TemplateDefinition.loader], which re-open the [zipFile] everytime [TemplateFileLoader.withLoader] is invoked. Or use
   * [copyAndLoadAllFiles] to create a [TemplateDefinition] with all files loaded in memory.
   */
  fun loadFromZipFile(zipFile: Path): TemplateListBuilder

  fun parseTemplateMetadata(relativePath: String, json: String): TemplateMetadata?

  fun toTemplateList(): TemplateList
}

internal class TemplateListBuilderImpl(
  private val messageSink: TemplateMessageSink,
  private val registry: TransformationRegistry,
  private val filterTemplateDefinitionStrategy: TemplateEngineFactory.FilterTemplateDefinitionStrategy,
) : TemplateListBuilder {
  private val templateStorages = mutableSetOf<TemplateDefinitionStorage>()
  private val templateDefinitions = mutableListOf<TemplateDefinition>()

  override fun toTemplateList(): TemplateList {
    return TemplateList(templates = templateDefinitions.filter { filterTemplateDefinitionStrategy.accept(it) })
  }

  override fun loadFromZipFile(zipFile: Path): TemplateListBuilder {
    val storage = ZipFileTemplateStorage(zipFile)
    // Enumerate all template definition, but don't load file content in memory
    val fileContentsByRelativePath =
      storage.withZipInputStream { zipInputStream -> createTemplateFilesMapFromZipInputStream(zipInputStream, loadFileContent = false) }

    // Copy all template definition with a "ZipFile" loader to load file content
    // from the zip file "on demand"
    val definitions =
      parse(fileContentsByRelativePath).map { (dir, templateDefinition) ->
        val templateFileLoader = TemplateFileLoader.forZipStorage(storage, dir)
        templateDefinition.copy(loader = templateFileLoader)
      }

    templateDefinitions.addAll(definitions)
    templateStorages.add(storage)
    return this
  }

  override fun loadFromZipStream(zipStream: ZipInputStream): TemplateListBuilder {
    val fileContentsByRelativePath = createTemplateFilesMapFromZipInputStream(zipStream, loadFileContent = true)

    templateDefinitions.addAll(parse(fileContentsByRelativePath).map { it.second })
    return this
  }

  /**
   * Enumerates entries from the given [zipStream] and returns a map of [java.util.zip.ZipEntry.name] paths to their byte contents.
   *
   * @param zipStream The [ZipInputStream] containing the template files.
   * @param loadFileContent If `true`, the content of all files is read and loaded into memory. If `false`, only the template definition
   *   JSON files ([TEMPLATE_JSON_FILE_LOCATION]) are loaded into memory, and other files are stored as empty byte arrays to save memory.
   * @return A sorted [SortedMap] containing the file paths as keys and their byte contents as values.
   */
  private fun createTemplateFilesMapFromZipInputStream(zipStream: ZipInputStream, loadFileContent: Boolean): SortedMap<String, ByteArray> {
    val fileContentsByRelativePath = TreeMap<String, ByteArray>(DirectoryBeforeFileComparator())
    var entry = zipStream.nextEntry

    while (entry != null) {
      if (!entry.isDirectory) {
        fileContentsByRelativePath[entry.name] =
          if (loadFileContent || entry.name.endsWith(TEMPLATE_JSON_FILE_LOCATION)) zipStream.readBytes() else ByteArray(0)
      }
      entry = zipStream.nextEntry
    }
    return fileContentsByRelativePath
  }

  fun parse(fileContentsByRelativePath: Map<String, ByteArray>): List<Pair<String, TemplateDefinition>> {
    // Find all the template directories by looking the template "json" files
    val templateDirs =
      fileContentsByRelativePath.entries.mapNotNull {
        if (it.key.endsWith(TEMPLATE_JSON_FILE_LOCATION)) {
          it.key.removeSuffix(TEMPLATE_JSON_FILE_LOCATION)
        } else {
          null
        }
      }

    // Group files by template directory
    val filesByDir =
      fileContentsByRelativePath.entries.groupBy { fileContentsEntry ->
        templateDirs.firstOrNull { templateDir -> fileContentsEntry.key.startsWith("$templateDir/") } ?: ""
      }

    val templates = mutableListOf<Pair<String, TemplateDefinition>>()
    for ((dir, entries) in filesByDir) {
      // The empty "dir" contains all misc. files that don't belong to a template
      // i.e. misc. "extra" files that should be ignored.
      if (dir.isEmpty()) continue
      val jsonDefinition = entries.firstOrNull { it.key.endsWith(TEMPLATE_JSON_FILE_LOCATION) } ?: continue
      val templateContent = mutableMapOf<TemplateFileEntry, TemplateFile>()

      val extraFiles =
        entries
          .filter { it.key.startsWith("$dir/$DOT_TEMPLATE_NAME/") && !it.key.endsWith(TEMPLATE_JSON_FILE_LOCATION) }
          .map { entry ->
            val relativePath = entry.key.substringAfter("$dir/")
            TemplateFileEntry(relativePath).also { templateContent[it] = TemplateFile(it.relativePath, entry.value) }
          }

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
              !it.key.startsWith("$dir/$DOT_TEMPLATE_NAME/")
            }
            .map { entry ->
              // Compute relative path to the `dir` directory
              val relativePath = entry.key.substringAfter("$dir/")
              TemplateFileEntry(relativePath).also { templateContent[it] = TemplateFile(it.relativePath, entry.value) }
            }

        val templateFileLoader = TemplateFileLoader.forMap(templateContent)
        templates.add(Pair(dir, TemplateDefinition(metadata, templateFiles, extraFiles, templateFileLoader)))
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

  companion object {
    private const val DOT_TEMPLATE_NAME = ".template"
    private const val TEMPLATE_JSON_FILE_LOCATION = "/$DOT_TEMPLATE_NAME/template-definition.json"
  }
}
