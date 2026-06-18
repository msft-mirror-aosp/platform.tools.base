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

/**
 * Interface for processing a [TemplateDefinition] and its [TemplateFile]'s, applying file transformations and handling the output, either
 * in a dry run or by writing to disk.
 */
interface TemplateEngine {

  /**
   * Processes all [TemplateFile]s of a [TemplateDefinition], invoking all transformations defined in
   * [TemplateDefinition.metadata].[transformations][TemplateMetadata.transformations].
   *
   * The output is handled either as a dry run or by writing to disk.
   *
   * @param template The template definition to process
   * @param predefinedArguments The collection of arguments values that are pre-defined. These arguments are **not** declared in the
   *   [template].
   * @param explicitArguments The collection of arguments values that are explicitly provided by the user. These argument are declared in
   *   the [template]
   */
  fun processTemplate(template: TemplateDefinition, predefinedArguments: Map<String, String>, explicitArguments: Map<String, String>)
}

/** Return a human-readable error for this [Throwable] */
fun Throwable.concatMessages(): String {
  val builder = StringBuilder()
  var current: Throwable? = this
  var count = 0
  while (current != null && count < 10) {
    if (!builder.isEmpty()) {
      builder.append(": ")
    }
    builder.append(current.message ?: this.javaClass.simpleName)
    current = current.cause
    count++
  }
  return builder.toString()
}
