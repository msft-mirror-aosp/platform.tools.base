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

import com.android.template.engine.TemplateMessageSink.Severity
import com.android.template.engine.Transformation.Companion.parseSelector
import com.android.template.engine.impl.TemplateFileContentProcessor
import com.android.template.engine.impl.message

internal class RenameFileTransformation : Transformation {
  val name: String
    get() = "rename-file"

  override fun canParseJson(stepObj: JsonSourceObject): Boolean {
    return stepObj.has(name)
  }

  override fun canExecute(transformation: TransformationDefinition): Boolean {
    return transformation is RenameFileDefinition
  }

  override fun parseJson(parser: TemplateDefinitionParser, stepObj: JsonSourceObject): RenameFileDefinition? {
    val replaceObj = parser.getMandatoryObject(stepObj, name) ?: return null
    val selector = parseSelector(parser, replaceObj) ?: return null
    val sourcePath = parser.getMandatoryString(replaceObj, "source-path") ?: return null
    val targetPath = parser.getMandatoryString(replaceObj, "target-path") ?: return null
    return RenameFileDefinition(
      sourceLocation = parser.toSourceLocation(replaceObj),
      selector = selector,
      sourcePath = sourcePath,
      targetPath = targetPath,
    )
  }

  override fun execute(
    templateFileProcessor: TemplateFileContentProcessor,
    transformationContext: TransformationContext,
    transformation: TransformationDefinition,
    inputFile: TemplateFile,
  ): TemplateFile {
    val pathReplace = transformation as? RenameFileDefinition ?: return inputFile
    val shouldProcess =
      when (val location = pathReplace.selector) {
        is FileSelector.Glob -> {
          transformationContext.matchGlob(location.pattern, inputFile.relativePath)
        }
      }

    return if (shouldProcess) {
      val replacement = transformationContext.evaluateExpression(pathReplace.targetPath)
      val targetPath = inputFile.relativePath.replace(pathReplace.sourcePath, replacement)
      if (targetPath != inputFile.relativePath) {
        templateFileProcessor.messageSink.message(Severity.Verbose, inputFile) { "Renaming file to '${targetPath}'" }
        TemplateFile(relativePath = targetPath, content = inputFile.content)
      } else {
        inputFile
      }
    } else {
      inputFile
    }
  }
}
