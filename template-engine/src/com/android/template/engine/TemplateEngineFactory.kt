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

import com.android.template.engine.impl.TemplateEngineFactoryImpl
import java.nio.file.Path

interface TemplateEngineFactory {
  fun createTemplateListBuilder(messageSink: TemplateMessageSink): TemplateListBuilder

  /**
   * Creates an engine that writes template files to disk.
   *
   * By default, the destination directory must either not exist or be empty. When [overwriteExistingFiles] is `true`, a non-empty
   * destination directory is accepted and existing files are overwritten by template files with the same relative path.
   */
  fun createDefaultEngine(
    messageSink: TemplateMessageSink,
    dependencyInstaller: DependencyInstaller,
    destinationPathProvider: () -> Path,
    overwriteExistingFiles: Boolean = false,
  ): TemplateEngine

  /**
   * Creates an engine that processes templates without writing to disk. See [createDefaultEngine] for the meaning of
   * [overwriteExistingFiles].
   */
  fun createDryRunEngine(
    messageSink: TemplateMessageSink,
    destinationPathProvider: () -> Path,
    overwriteExistingFiles: Boolean = false,
  ): TemplateEngine

  companion object {
    fun createDefault(filterTemplateDefinitionStrategy: FilterTemplateDefinitionStrategy = DefaultStrategy()): TemplateEngineFactory {
      return TemplateEngineFactoryImpl(filterTemplateDefinitionStrategy, TransformationRegistry())
    }
  }

  fun interface FilterTemplateDefinitionStrategy {
    fun accept(template: TemplateDefinition): Boolean
  }

  class DefaultStrategy : FilterTemplateDefinitionStrategy {
    override fun accept(template: TemplateDefinition): Boolean {
      return true
    }
  }
}
