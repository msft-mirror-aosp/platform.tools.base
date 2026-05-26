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
 * Abstraction over the underlying storage (e.g. ZipFile) containing one or more [TemplateDefinition]. Reading [TemplateDefinition] content
 * is done through [TemplateDefinition.loader], but this abstraction provides the ability to read the content of multiple
 * [TemplateDefinition] in "batch" by calling [open]/[Handle.close] before using [TemplateDefinition.loader].
 *
 * For example, if multiple [TemplateDefinition] are read from a [TemplateDefinitionStorage] representing a ZipFile, calling [open] before
 * using [TemplateDefinition.loader] will open the ZipFile, providing access to all zip entries of the ZipFile until [Handle.close] is
 * called. Without the [open] call, each [TemplateDefinition.loader] use would incur an "on-demand" [open]/[Handle.close] pair of call,
 * potentially forcing reading the ZipFile directory of entries multiple times.
 */
interface TemplateDefinitionStorage {
  /** Opens the underlying storage for reading, returning a unique [Handle] to be used to [Handle.close] */
  fun open(): Handle

  interface Handle : AutoCloseable {
    /** Closes the underlying storage (if no other [Handle] is active) */
    override fun close()
  }
}
