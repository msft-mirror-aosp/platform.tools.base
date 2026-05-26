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

/** Provides access to all available [TemplateDefinition]. See [TemplateListBuilder] */
class TemplateList(
  /** The list of [TemplateDefinition] that are part of this list. */
  val templates: List<TemplateDefinition>
) {
  /** The set of [TemplateDefinitionStorage] (e.g. zip files or directories) that were used to build this [TemplateList] */
  val storages: Set<TemplateDefinitionStorage> = templates.map { it.loader.storage }.toSet()
}

/**
 * Preloads template files matching [predicate] into memory and returns a new [TemplateList] containing the updated template definitions
 * with cached files.
 *
 * This function handles opening and closing of all involved [TemplateDefinitionStorage] backends safely, ensuring that any resources opened
 * during the process are closed under all conditions.
 */
fun TemplateList.copyAndLoadFiles(predicate: (TemplateDefinition, TemplateFileEntry) -> Boolean): TemplateList {
  val handles = storages.map { runCatching { it.open() } }
  return try {
    // Throw if any handle is invalid (inside "try/finally" block so that opened resources are always closed).
    handles.forEach { it.getOrThrow() }

    // All handles are valid, proceed with loading files.
    val newDefinitions = this.templates.map { definition -> definition.copyAndLoadFiles { entry -> predicate(definition, entry) } }
    TemplateList(newDefinitions)
  } finally {
    handles.forEach { it.getOrNull()?.close() }
  }
}

/** Preloads all extra files (such as icons, thumbnails, ...) associated with the template definitions in this list. */
fun TemplateList.copyAndLoadExtraFiles(): TemplateList {
  val extraFiles = templates.associateWith { it.extraFiles.toSet() }
  return copyAndLoadFiles { definition, entry -> extraFiles[definition]?.contains(entry) ?: false }
}

/**
 * Preloads the primary template files (source and resource files intended for generation) associated with the template definitions in this
 * list.
 */
fun TemplateList.copyAndLoadTemplateFiles(): TemplateList {
  val files = templates.associateWith { it.files.toSet() }
  return copyAndLoadFiles { definition, entry -> files[definition]?.contains(entry) ?: false }
}

/** Preloads all associated files (both primary template files and extra files) for all template definitions in this list. */
fun TemplateList.copyAndLoadAllFiles(): TemplateList {
  return copyAndLoadFiles { _, _ -> true }
}
