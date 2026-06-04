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

internal sealed interface Transformation {
  fun canParseJson(stepObj: JsonSourceObject): Boolean

  fun parseJson(parser: TemplateDefinitionParser, stepObj: JsonSourceObject): TransformationDefinition?

  fun canExecute(transformation: TransformationDefinition): Boolean

  fun execute(
    templateFileProcessor: TemplateFileContentProcessor,
    transformationContext: TransformationContext,
    transformation: TransformationDefinition,
    inputFile: TemplateFile,
  ): TemplateFile

  companion object {
    internal fun parseSelector(parser: TemplateDefinitionParser, replaceObj: JsonSourceObject): FileSelector? {
      val selectorObj = parser.getMandatoryObject(replaceObj, "selector") ?: return null
      return when {
        selectorObj.has("glob") -> {
          val pattern = parser.getMandatoryString(selectorObj, "glob") ?: return null
          FileSelector.Glob(pattern)
        }
        else -> {
          parser.addError(selectorObj, "Unknown selector: Only `glob` is supported")
        }
      }
    }
  }
}
