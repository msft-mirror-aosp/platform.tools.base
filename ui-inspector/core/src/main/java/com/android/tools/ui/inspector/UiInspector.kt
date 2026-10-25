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
import com.android.tools.ui.inspector.client.ComposeInspectorResolution
import com.android.tools.ui.inspector.deploy.ArtifactDownloader
import com.android.tools.ui.inspector.deploy.DEFAULT_TEMP_FILE_SUFFIX_GENERATOR
import com.android.tools.ui.inspector.deploy.DeviceArtifactsDirectory
import com.android.tools.ui.inspector.deploy.HttpArtifactDownloader
import com.android.tools.ui.inspector.deploy.InjectionManager
import com.android.tools.ui.inspector.model.ParameterReference
import com.android.tools.ui.inspector.model.UiDump
import com.android.tools.ui.inspector.model.UiNode
import java.nio.file.Path

/**
 * Dumps the UI tree of a debuggable app on a device: Views and Composables in one tree, read from inside the app's process by an agent the
 * inspector attaches over adb.
 */
class UiInspector
internal constructor(
  private val adbSession: AdbSession,
  private val composeInspectorResolution: ComposeInspectorResolution,
  logger: Logger?,
  private val injectionManagerFactory: InjectionManagerFactory,
) {
  /**
   * @param adbSession The session to the adb server.
   * @param cacheDir Where the Compose inspector jars downloaded from Maven are kept.
   * @param composeInspectorJar An optional local Compose inspector jar to use instead of the one Maven has for the app's Compose version.
   * @param logger An optional logger the ui inspector can use to log its progress.
   * @param artifactDownloader An optional downloader for the Compose inspector jars, e.g. to use the HTTP stack of the tool embedding the
   *   inspector. Defaults to [java.net.URL] connections.
   * @param deviceArtifactsDir An optional directory with the files the inspector pushes to the device, for tools that don't bundle them in
   *   their classpath, e.g. the Android CLI, which downloads them in a pack: the directory where the files of the
   *   `//tools/base/ui-inspector/core:device_artifacts_pkg_files` target were extracted. When null, the files are read from the classpath,
   *   where the standalone CLI bundles them.
   */
  constructor(
    adbSession: AdbSession,
    cacheDir: Path,
    composeInspectorJar: Path? = null,
    logger: Logger? = null,
    artifactDownloader: ArtifactDownloader? = null,
    deviceArtifactsDir: Path? = null,
  ) : this(
    adbSession,
    ComposeInspectorResolution(composeInspectorJar, cacheDir, artifactDownloader ?: HttpArtifactDownloader()),
    logger,
    defaultInjectionManagerFactory(deviceArtifactsDir),
  )

  private val logger: Logger = logger ?: Logger { _, _ -> }

  /** Captures the UI of [packageName] on the device [serial]. */
  suspend fun dump(serial: String, packageName: String, facets: Set<Facet> = emptySet()): UiDump {
    val options = DumpOptions.of(facets)
    return runWithConnectedInspectors(
      adbSession,
      serial,
      packageName,
      options.resolutionStack,
      composeInspectorResolution,
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

  /**
   * Fetches the content that a dump or an earlier call left out of the parameter value [reference] identifies, in [packageName] on the
   * device [serial]. The UI must not have changed since then.
   */
  suspend fun getParameter(serial: String, packageName: String, reference: ParameterReference): UiNode.ComposeParameter =
    runWithConnectedInspectors(
      adbSession,
      serial,
      packageName,
      needsDebugViewAttributes = false,
      composeInspectorResolution,
      logger,
      injectionManagerFactory,
    ) { commandSender, composeInspectorConnected ->
      fetchParameter(commandSender, composeInspectorConnected, reference)
    }
}

/** Creates the [InjectionManager]s of a [UiInspector], which ship the files in [deviceArtifactsDir], or the bundled ones if it is null. */
private fun defaultInjectionManagerFactory(deviceArtifactsDir: Path?): InjectionManagerFactory =
  if (deviceArtifactsDir == null) ::InjectionManager
  else deviceArtifactsInjectionManagerFactory(DeviceArtifactsDirectory(deviceArtifactsDir))

/**
 * Creates [InjectionManager]s that ship the files of [deviceArtifacts], naming their temporary copies on the device with
 * [tempFileSuffixGenerator]. Each creation first checks that the directory of [deviceArtifacts] exists, and throws if not.
 */
internal fun deviceArtifactsInjectionManagerFactory(
  deviceArtifacts: DeviceArtifactsDirectory,
  tempFileSuffixGenerator: () -> String = DEFAULT_TEMP_FILE_SUFFIX_GENERATOR,
): InjectionManagerFactory = { adbSession, serial, packageName, composeInspectorOverrideJarPath, logger ->
  deviceArtifacts.checkExists()
  InjectionManager(
    adbSession,
    serial,
    packageName,
    composeInspectorOverrideJarPath,
    logger,
    agentPathResolver = deviceArtifacts::agentPath,
    libraryDexPath = deviceArtifacts.libraryDexPath,
    payloadJarPath = deviceArtifacts.payloadJarPath,
    viewInspectorJarPath = deviceArtifacts.viewInspectorJarPath,
    tempFileSuffixGenerator = tempFileSuffixGenerator,
  )
}
