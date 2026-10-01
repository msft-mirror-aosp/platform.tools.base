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

package com.android.tools.ui.inspector

import com.android.adblib.AdbSession
import com.android.tools.ui.inspector.deploy.InjectionManager
import com.android.tools.ui.inspector.model.UiDump
import java.nio.file.Path

/**
 * Dumps the UI tree of a debuggable app on a device: Views and Composables in one tree, read from inside the app's process by an agent the
 * inspector attaches over adb.
 *
 * @param adbSession The session to the adb server.
 * @param cacheDir Where the Compose inspector jars downloaded from Maven are kept.
 * @param composeInspectorJar An optional local Compose inspector jar to use instead of the one Maven has for the app's Compose version.
 * @param logger An optional logger the ui inspector can use to log its progress.
 */
class UiInspector
internal constructor(
  private val adbSession: AdbSession,
  private val cacheDir: Path,
  private val composeInspectorJar: Path?,
  logger: Logger?,
  private val injectionManagerFactory: InjectionManagerFactory,
) {
  constructor(
    adbSession: AdbSession,
    cacheDir: Path,
    composeInspectorJar: Path? = null,
    logger: Logger? = null,
  ) : this(adbSession, cacheDir, composeInspectorJar, logger, ::InjectionManager)

  private val logger: Logger = logger ?: Logger { _, _ -> }

  /** Captures the UI of [packageName] on the device [serial]. */
  suspend fun dump(serial: String, packageName: String, facets: Set<Facet> = emptySet()): UiDump {
    val options = DumpOptions.of(facets)
    return runWithConnectedInspectors(
      adbSession,
      serial,
      packageName,
      options.resolutionStack,
      composeInspectorJar,
      cacheDir,
      logger,
      injectionManagerFactory,
    ) { commandSender, composeInspectorConnected ->
      dumpUi(
        commandSender = commandSender,
        includeAttributes = options.attributes,
        includeResolutionStack = options.resolutionStack,
        composeInspectorConnected = composeInspectorConnected,
        includeSystemComposables = options.systemComposables,
        includeSemantics = options.semantics,
        logger = logger,
      )
    }
  }
}
