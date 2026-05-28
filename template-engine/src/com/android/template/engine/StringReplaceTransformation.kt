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

import com.android.template.engine.Transformation.Companion.parseSelector
import com.android.template.engine.impl.TemplateFileContentProcessor

/**
 * Transformation that replaces a string with another string in files matching a selector.
 *
 * Both the `from` and `to` strings can contain newlines (`\n`). If they do, the transformation will be applied to the entire file content
 * at once instead of line-by-line.
 *
 * Example JSON:
 * ```json
 * {
 *   "string-replace": {
 *     "selector": {
 *       "glob": "src/main/res/values/strings.xml"
 *     },
 *     "from": "OldString",
 *     "to": "NewString"
 *   }
 * }
 * ```
 */
internal class StringReplaceTransformation : Transformation {
  val name: String
    get() = "string-replace"

  override fun canParseJson(stepObj: JsonSourceObject): Boolean {
    return stepObj.has(name)
  }

  override fun canExecute(transformation: TransformationDefinition): Boolean {
    return transformation is StringReplaceDefinition
  }

  override fun parseJson(parser: TemplateDefinitionParser, stepObj: JsonSourceObject): StringReplaceDefinition? {
    val replaceObj = parser.getMandatoryObject(stepObj, name) ?: return null
    val selector = parseSelector(parser, replaceObj) ?: return null
    val from = parser.getMandatoryNonEmptyString(replaceObj, "from") ?: return null
    val to = parser.getMandatoryString(replaceObj, "to") ?: return null
    return StringReplaceDefinition(sourceLocation = parser.toSourceLocation(replaceObj), selector = selector, from = from, to = to)
  }

  override fun execute(
    templateFileProcessor: TemplateFileContentProcessor,
    transformationContext: TransformationContext,
    transformation: TransformationDefinition,
    inputFile: TemplateFile,
  ): TemplateFile {
    val stringReplace = transformation as? StringReplaceDefinition ?: return inputFile

    val shouldProcess =
      when (val selector = stringReplace.selector) {
        is FileSelector.Glob -> {
          transformationContext.matchGlob(selector.pattern, inputFile.relativePath)
        }
      }

    return if (shouldProcess) {
      val replacement = transformationContext.evaluateExpression(stringReplace.to)
      if (stringReplace.from.contains('\n') || replacement.contains('\n')) {
        templateFileProcessor.processTemplateFileText(inputFile) { text -> text.replace(stringReplace.from, replacement) }
      } else {
        templateFileProcessor.processTemplateFileContent(inputFile) { _, line -> line.replace(stringReplace.from, replacement) }
      }
    } else {
      inputFile
    }
  }
}
