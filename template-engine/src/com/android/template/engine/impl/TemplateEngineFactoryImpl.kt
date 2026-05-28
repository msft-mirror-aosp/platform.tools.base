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

import com.android.template.engine.DefaultFileStorage
import com.android.template.engine.DependencyInstaller
import com.android.template.engine.DryRunFileStorage
import com.android.template.engine.TemplateEngine
import com.android.template.engine.TemplateEngineFactory
import com.android.template.engine.TemplateListBuilder
import com.android.template.engine.TemplateListBuilderImpl
import com.android.template.engine.TemplateMessageSink
import com.android.template.engine.TemplateMessageSink.Severity
import com.android.template.engine.TransformationRegistry
import com.android.template.engine.message
import java.nio.file.Path

internal class TemplateEngineFactoryImpl(
  private val filterTemplateDefinitionStrategy: TemplateEngineFactory.FilterTemplateDefinitionStrategy,
  private val registry: TransformationRegistry,
) : TemplateEngineFactory {

  override fun createTemplateListBuilder(messageSink: TemplateMessageSink): TemplateListBuilder {
    return TemplateListBuilderImpl(messageSink = messageSink, registry = registry, filterTemplateDefinitionStrategy)
  }

  override fun createDefaultEngine(
    messageSink: TemplateMessageSink,
    dependencyInstaller: DependencyInstaller,
    destinationPathProvider: () -> Path,
  ): TemplateEngine {
    val fileStorage = DefaultFileStorage(messageSink, destinationPathProvider)
    return TemplateEngineImpl(messageSink, registry, fileStorage, dependencyInstaller)
  }

  override fun createDryRunEngine(messageSink: TemplateMessageSink, destinationPathProvider: () -> Path): TemplateEngine {
    val dryRunInstaller =
      object : DependencyInstaller {
        override fun installAndroidSdkPackage(packagePath: String) {
          messageSink.message(Severity.Info) { "Dry run: Would install Android SDK package '$packagePath'" }
        }
      }
    val fileStorage = DryRunFileStorage(messageSink, destinationPathProvider)
    return TemplateEngineImpl(messageSink, registry, fileStorage, dryRunInstaller)
  }
}
