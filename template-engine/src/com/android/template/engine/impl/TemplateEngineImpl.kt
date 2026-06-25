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
package com.android.template.engine.impl

import com.android.template.engine.DependencyInstaller
import com.android.template.engine.SchemaVersion
import com.android.template.engine.TemplateDefinition
import com.android.template.engine.TemplateDependency
import com.android.template.engine.TemplateEngine
import com.android.template.engine.TemplateFile
import com.android.template.engine.TemplateFileStorage
import com.android.template.engine.TemplateMessageSink
import com.android.template.engine.TemplateMessageSink.Severity
import com.android.template.engine.TemplateMetadata
import com.android.template.engine.TransformationContext
import com.android.template.engine.TransformationRegistry
import com.android.template.engine.concatMessages
import com.android.template.engine.message
import java.io.ByteArrayInputStream

/** Common base class implementation of [TemplateEngine] */
internal class TemplateEngineImpl(
  private val messageSink: TemplateMessageSink,
  private val registry: TransformationRegistry,
  private val fileStorage: TemplateFileStorage,
  private val dependencyInstaller: DependencyInstaller,
) : TemplateEngine {

  val hasErrors: Boolean
    get() = messageSink.hasErrors()

  val maxSchemaVersionSupported: SchemaVersion
    get() = SchemaVersion.implicitVersion

  private inline fun message(severity: Severity, lazyMessage: () -> String) {
    messageSink.message(severity, lazyMessage)
  }

  private inline fun message(severity: Severity, templateFile: TemplateFile, lazyMessage: () -> String) {
    messageSink.message(severity, templateFile, lazyMessage)
  }

  override fun processTemplate(
    template: TemplateDefinition,
    predefinedArguments: Map<String, String>,
    explicitArguments: Map<String, String>,
  ) {
    processTemplateWorker(template, predefinedArguments, explicitArguments)
    if (hasErrors) {
      message(Severity.Error) { "Failed to create project '${template.name}' due to previous error(s)" }
    } else {
      message(Severity.Info) { "Successfully created project '${template.name}' at '${fileStorage.destinationPath}'" }
    }
  }

  private fun processTemplateWorker(
    template: TemplateDefinition,
    predefinedArguments: Map<String, String>,
    explicitArguments: Map<String, String>,
  ) {
    message(Severity.Info) { "Processing template '${template.shortName}'" }

    checkSchemaVersion(template)
    if (hasErrors) {
      // Stop processing template if any error so far
      return
    }

    runCatching { fileStorage.checkDestinationDirectoryIsEmpty() }
      .onFailure {
        message(Severity.Error) { "Cannot create template: ${it.concatMessages()}" }
        return
      }

    val transformationContext = createTransformationContext(template, predefinedArguments, explicitArguments)
    if (hasErrors) {
      // Stop processing template if any error so far
      return
    }

    // Install dependencies
    template.metadata.dependencies.forEach { installSdkDependency(transformationContext, it) }
    if (hasErrors) {
      // Stop processing template if any error so far
      return
    }

    val newFiles =
      processTemplateFilesImpl(template) { templateFile -> transformTemplateFile(template, transformationContext, templateFile) }
    if (hasErrors) {
      // Stop processing template if any error so far
      return
    }

    // Save everything to disk
    runCatching { fileStorage.checkDestinationDirectoryIsEmpty() }
      .onFailure { t ->
        message(Severity.Error) { "Error creating template directory: ${t.concatMessages()}" }
        return
      }

    newFiles.forEach { templateFile ->
      runCatching { fileStorage.saveFile(templateFile) }
        .onFailure { t -> message(Severity.Error, templateFile) { "Error saving template file: ${t.concatMessages()}" } }
    }
  }

  private fun createTransformationContext(
    template: TemplateDefinition,
    predefinedArguments: Map<String, String>,
    explicitArguments: Map<String, String>,
  ): TransformationContext {
    val arguments = createEffectiveArgumentValues(template, predefinedArguments, explicitArguments)
    val transformationContext = TransformationContext(registry, arguments)
    return transformationContext
  }

  private fun installSdkDependency(transformationContext: TransformationContext, dependency: TemplateDependency) {
    runCatching { transformationContext.evaluateExpression(dependency.sdkPackage) }
      .onFailure { t ->
        message(Severity.Error) {
          "${dependency.sourceLocation}: Error evaluating SDK package expression ${dependency.sdkPackage}: ${t.concatMessages()}"
        }
      }
      .onSuccess { actualSdkPath ->
        runCatching { dependencyInstaller.installAndroidSdkPackage(actualSdkPath) }
          .onFailure { t ->
            message(Severity.Error) { "${dependency.sourceLocation}: Error installing SDK package $actualSdkPath: ${t.concatMessages()}" }
          }
      }
  }

  private fun checkSchemaVersion(template: TemplateDefinition) {
    if (template.metadata.schemaVersion > maxSchemaVersionSupported) {
      message(Severity.Error) {
        "Template '${template.shortName}' uses schema version '${template.metadata.schemaVersion}', " +
          "but the current template engine only support versions up to '$maxSchemaVersionSupported'. " +
          "Use \"android update\" to update to the latest template engine."
      }
    }
  }

  /**
   * Create the "final" template arguments as a map of `name` -> `value` by merging 2 sources of argument values:
   * - [explicitArguments] are the argument values provided on the command line
   * - [TemplateMetadata.arguments] from [TemplateDefinition.metadata] from [template] are the arguments provided in the template definition
   *   file.
   */
  private fun createEffectiveArgumentValues(
    template: TemplateDefinition,
    predefinedArguments: Map<String, String>,
    explicitArguments: Map<String, String>,
  ): Map<String, String> {
    return (predefinedArguments +
        template.metadata.arguments.associate { argDef ->
          // We use a "limited" transformation context that contains only the effective template arguments, and also
          // does not contain the current argument (to prevent recursive evaluation)
          val argumentsDefaultValues = template.metadata.arguments.associate { it.id to it.defaultValue } + explicitArguments - argDef.id
          val transformationContext = TransformationContext(registry, argumentsDefaultValues)

          // Either get the value passed as parameter, or evaluate the "default-value" expression from the template definition
          argDef.id to
            (explicitArguments[argDef.id]
              ?: runCatching { transformationContext.evaluateExpression(argDef.defaultValue) }
                .onFailure { t ->
                  message(Severity.Error) {
                    "${argDef.sourceLocation}: Error evaluating default expression for argument '${argDef.id}': ${t.concatMessages()}"
                  }
                }
                .getOrDefault(argDef.defaultValue))
        })
      .also {
        if (!hasErrors) {
          message(Severity.Verbose) { "Effective arguments values: $it" }
        }
      }
  }

  private fun processTemplateFilesImpl(template: TemplateDefinition, fileTransformer: (TemplateFile) -> TemplateFile): List<TemplateFile> {
    return template.loader.withLoader { loader ->
      template.files.map { templateFileEntry ->
        val templateFile = loader.loadFile(templateFileEntry)
        val newTemplateFile = fileTransformer(templateFile)

        // Log no-nop file copy here
        if (templateFile.content.contentEquals(newTemplateFile.content)) {
          message(Severity.Verbose, newTemplateFile) { "Copying contents unchanged" }
        }

        // Return new file always
        newTemplateFile
      }
    }
  }

  private fun transformTemplateFile(
    template: TemplateDefinition,
    transformationContext: TransformationContext,
    inputFile: TemplateFile,
  ): TemplateFile {
    // Execute all transformations on "inputFile"
    return template.metadata.transformations.fold(inputFile) { templateFile, transformationDefinition ->
      message(Severity.Debug, templateFile) { "Running transformation '$transformationDefinition'" }
      val transformation =
        transformationContext.registry.findTransformForOperation(transformationDefinition)
          ?: run {
            message(Severity.Error, templateFile) { "Unknown transformation: '${transformationDefinition.name}'" }
            return templateFile
          }
      val processor = TemplateFileContentProcessor(messageSink)
      runCatching { transformation.execute(processor, transformationContext, transformationDefinition, templateFile) }
        .onFailure { throwable ->
          message(Severity.Error, templateFile) {
            "Error executing transformation '${transformationDefinition.name}': ${throwable.concatMessages()}"
          }
        }
        .getOrDefault(templateFile)
    }
  }
}

internal inline fun TemplateMessageSink.message(severity: Severity, templateFile: TemplateFile, lazyMessage: () -> String) {
  message(severity) { "Template file '${templateFile.relativePath}': ${lazyMessage()}" }
}

internal class TemplateFileContentProcessor(val messageSink: TemplateMessageSink) {
  /**
   * Returns a copy of this [inputFile] after applying [lineTransformer] to each line of the [TemplateFile.content].
   * * Note: [TemplateFile.content] is assumed to be text
   * * Note: If [lineTransformer] does not change any line, this method returns this [inputFile] instance
   */
  fun processTemplateFileContent(inputFile: TemplateFile, lineTransformer: (Int, String) -> String): TemplateFile {
    val byteArray = inputFile.content

    val isCrlf = run {
      var found = false
      for (i in 0 until byteArray.size - 1) {
        if (byteArray[i] == '\r'.code.toByte() && byteArray[i + 1] == '\n'.code.toByte()) {
          found = true
          break
        }
      }
      found
    }
    val separator = if (isCrlf) "\r\n" else "\n"
    val postfix = if (byteArray.isNotEmpty() && byteArray.last() == '\n'.code.toByte()) separator else ""

    var hasChanges = false
    val newContent =
      ByteArrayInputStream(byteArray).bufferedReader().useLines { lines ->
        lines
          .mapIndexed { index, line ->
            lineTransformer(index, line).also {
              if (it != line) {
                hasChanges = true
                messageSink.message(Severity.Verbose, inputFile) { "Updating line ${index + 1} from [$line] to [$it]" }
              }
            }
          }
          .joinToString(separator = separator, postfix = postfix)
      }

    return if (hasChanges) {
      TemplateFile(inputFile.relativePath, newContent.toByteArray(Charsets.UTF_8))
    } else {
      inputFile
    }
  }

  /**
   * Returns a copy of this [inputFile] after applying [textTransformer] to the entire [TemplateFile.content].
   * * Note: [TemplateFile.content] is assumed to be text
   * * Note: If [textTransformer] does not change the content, this method returns this [inputFile] instance
   */
  fun processTemplateFileText(inputFile: TemplateFile, textTransformer: (String) -> String): TemplateFile {
    val oldContent = String(inputFile.content, Charsets.UTF_8)
    val newContent = textTransformer(oldContent)
    return if (newContent != oldContent) {
      messageSink.message(Severity.Verbose, inputFile) { "Updating file content" }
      TemplateFile(inputFile.relativePath, newContent.toByteArray(Charsets.UTF_8))
    } else {
      inputFile
    }
  }
}
