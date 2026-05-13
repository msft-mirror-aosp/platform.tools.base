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

import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import com.google.common.annotations.VisibleForTesting
import java.io.File

private const val COMPOSE_UI_GROUP_ID = "androidx.compose.ui"

/** Creates the Compose Inspector on the agent if Jetpack Compose is detected in the target application process. */
internal suspend fun createComposeInspector(
  commandSender: CommandSender,
  injectionManager: InjectionManager,
  resolveJar: (version: String) -> File = { version ->
    val artifactId = getComposeArtifactId(version)
    // TODO: add support for caching
    MavenArtifactResolver().resolve(COMPOSE_UI_GROUP_ID, artifactId, version)
  },
) {
  val composeVersion = getComposeVersion(commandSender) ?: return

  try {
    val jarFile = resolveJar(composeVersion)
    launchComposeInspector(commandSender, injectionManager, jarFile)
  } catch (e: Exception) {
    System.err.println("Failed to resolve or deploy Compose Inspector: ${e.message}")
  }
}

/** Queries the target application agent for its installed Jetpack Compose version. */
private suspend fun getComposeVersion(commandSender: CommandSender): String? {
  val getVersionCommand =
    UiInspectorProtocol.Command.newBuilder()
      .setGetVersion(UiInspectorProtocol.GetVersionCommand.newBuilder().addLibraryIds(ProtocolConstants.COMPOSE_UI_LIBRARY_ID).build())
      .build()

  val versionResponse = commandSender.sendMessage(getVersionCommand)
  if (versionResponse.status != UiInspectorProtocol.Response.Status.SUCCESS) {
    System.err.println("Failed to query Compose version: ${versionResponse.errorMessage}")
    return null
  }

  val composeVersion = versionResponse.getVersion.versionsMap[ProtocolConstants.COMPOSE_UI_LIBRARY_ID]
  if (composeVersion == null) {
    System.err.println("Compose not detected in target application.")
    return null
  }
  System.err.println("Compose detected: $composeVersion")
  return composeVersion
}

/** Deploys the Compose Inspector JAR to the device sandbox and requests the agent to load it dynamically. */
private suspend fun launchComposeInspector(commandSender: CommandSender, injectionManager: InjectionManager, jarFile: File) {
  val inspectorMetadata = InspectorMetadata(id = ProtocolConstants.COMPOSE_INSPECTOR_ID, localJarPath = jarFile.toPath())
  val dexPath = injectionManager.pushInspectorPayload(inspectorMetadata)

  val createCommand =
    UiInspectorProtocol.Command.newBuilder()
      .setCreateInspector(
        UiInspectorProtocol.CreateInspectorCommand.newBuilder()
          .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
          .setDexPath(dexPath)
          .build()
      )
      .build()

  val createResponse = commandSender.sendMessage(createCommand)
  if (createResponse.status != UiInspectorProtocol.Response.Status.SUCCESS) {
    System.err.println("Warning: Failed to load Compose Inspector: ${createResponse.errorMessage}")
  } else {
    System.err.println("Compose Inspector successfully loaded on agent!")
  }
}

/**
 * Resolves the Maven Artifact ID for Jetpack Compose core UI based on its version.
 *
 * Prior to the Kotlin Multiplatform (KMP) transition in Compose 1.5.0, all Android specific binaries were published under the unified "ui"
 * artifact. From 1.5.0 onwards, they were split into platform-specific modules, with the Android-specific classes packaged inside the
 * "ui-android" artifact.
 */
@VisibleForTesting
internal fun getComposeArtifactId(version: String): String {
  val parts = version.split('.')
  if (parts.size < 2) return "ui" // Fallback to legacy
  val major = parts[0].toIntOrNull() ?: 0
  val minor = parts[1].toIntOrNull() ?: 0
  val isPreKmp = major < 1 || (major == 1 && minor < 5)
  return if (isPreKmp) "ui" else "ui-android"
}
