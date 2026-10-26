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

package com.android.tools.ui.inspector.client

import com.android.tools.ui.inspector.LogLevel
import com.android.tools.ui.inspector.Logger
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.deploy.InjectionManager
import com.android.tools.ui.inspector.deploy.InspectorMetadata
import com.android.tools.ui.inspector.model.ParameterReference
import com.android.tools.ui.inspector.proto.parseTreeResponse
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import java.io.IOException
import java.nio.file.Path
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol

/** The Maven group of the Compose UI library, whose AAR holds the Compose inspector. */
internal const val COMPOSE_UI_GROUP_ID = "androidx.compose.ui"

/**
 * How deep the Compose inspector expands parameter values, and how many elements of each collection it sends. These are the agent defaults,
 * also used by Android Studio, pinned so that a dump does not depend on the agent version. Content past them is sent as a reference.
 */
private const val MAX_PARAMETER_DEPTH = 2
private const val MAX_COLLECTION_ELEMENTS = 5

/**
 * Creates the Compose Inspector on the agent if Jetpack Compose is detected in the target application process. Returns false when the app
 * does not use Compose. When it does, the inspector is created or the dump fails: a Compose app dumped without its Compose tree would be
 * silently incomplete, so a jar that cannot be resolved, an unknown Compose version without [overrideJar] or an agent that refuses to load
 * the jar fails the dump.
 *
 * @param resolveJar Resolves the Compose inspector jar for the app's Compose version.
 * @param overrideJar The Compose inspector jar given explicitly, or null. It is used when the app's Compose version is unknown, since the
 *   version is only needed to resolve a jar. When the version is known, [resolveJar] already returns this jar.
 */
internal suspend fun createComposeInspector(
  commandSender: CommandSender,
  injectionManager: InjectionManager,
  logger: Logger,
  resolveJar: (version: String) -> Path,
  overrideJar: Path?,
): Boolean {
  val jar =
    when (val compose = detectCompose(commandSender, logger)) {
      ComposeDetection.Absent -> return false
      is ComposeDetection.Present ->
        try {
          resolveJar(compose.version)
        } catch (e: IOException) {
          throw IOException("Could not resolve the Compose inspector for Compose ${compose.version}: ${e.message}", e)
        }
      ComposeDetection.PresentWithoutVersion -> {
        if (overrideJar == null) {
          throw IOException(COMPOSE_VERSION_UNKNOWN_MESSAGE)
        }
        logger.log(LogLevel.PROGRESS, "Using the Compose inspector $overrideJar")
        overrideJar
      }
    }
  launchComposeInspector(commandSender, injectionManager, jar, logger)
  return true
}

/** The failure when the app uses Compose but its Compose version is unknown, and no Compose inspector jar was given. */
private const val COMPOSE_VERSION_UNKNOWN_MESSAGE =
  "Compose inspection unavailable: the app uses Compose, but its APK has no readable META-INF/androidx.compose.ui_ui.version file, so " +
    "its androidx.compose.ui:ui version is unknown. Was the file excluded, e.g. by a \"/META-INF/*\" or \"META-INF/*.version\" pattern " +
    "in the packaging (packagingOptions) resources excludes of the app's build file? Keep the file, then rebuild and reinstall the app."

/** Whether the app uses Compose, as the agent reports it. */
private sealed interface ComposeDetection {
  /** The app does not use Compose. */
  data object Absent : ComposeDetection

  /** The app uses Compose UI [version]. */
  data class Present(val version: String) : ComposeDetection

  /** The app uses Compose, but its version file is missing, unreadable or blank. */
  data object PresentWithoutVersion : ComposeDetection
}

/** Queries the Compose inspector on the agent for the Composable tree of a specific root view. */
internal suspend fun queryComposeTree(
  commandSender: CommandSender,
  rootViewId: Long,
  extractAllParameters: Boolean,
): LayoutInspectorComposeProtocol.GetComposablesResponse {
  val getComposablesCmd =
    LayoutInspectorComposeProtocol.Command.newBuilder()
      .setGetComposablesCommand(
        LayoutInspectorComposeProtocol.GetComposablesCommand.newBuilder()
          .setRootViewId(rootViewId)
          // Always get the full tree from the device: the device-side filter does more than remove nodes (it changes which view the
          // compose root reports as its parent), so system composables are stripped host-side instead.
          .setSkipSystemComposables(false)
          // Set generation to 0 to force the persistent agent inspector to bypass the layout cache
          // and always return a fresh capture of the active screen on subsequent reconnections.
          .setGeneration(0)
          // If extractAllParameters is true, the agent pre-extracts and warms up its combined parameters-and-semantics
          // cache in background memory so the subsequent GetAllParameters query is served from it.
          .setExtractAllParameters(extractAllParameters)
      )
      .build()

  val responsePayload = commandSender.sendInspectorCommand(ProtocolConstants.COMPOSE_INSPECTOR_ID, getComposablesCmd.toByteArray())
  val composeResponse = LayoutInspectorComposeProtocol.Response.parser().parseTreeResponse(responsePayload)

  return composeResponse
    .requireVariant(LayoutInspectorComposeProtocol.Response.SpecializedCase.GET_COMPOSABLES_RESPONSE)
    .getComposablesResponse
}

/** Queries the Compose inspector on the agent for the parameters of all active Composable views in a root layout tree. */
internal suspend fun queryComposeParameters(
  commandSender: CommandSender,
  rootViewId: Long,
): LayoutInspectorComposeProtocol.GetAllParametersResponse {
  val getAllParamsCmd =
    LayoutInspectorComposeProtocol.Command.newBuilder()
      .setGetAllParametersCommand(
        LayoutInspectorComposeProtocol.GetAllParametersCommand.newBuilder()
          .setRootViewId(rootViewId)
          // Never filtered on the device, same as the tree command.
          .setSkipSystemComposables(false)
          // Use the same generation from the get composables command so that we get the cached parameters
          .setGeneration(0)
          .setMaxRecursions(MAX_PARAMETER_DEPTH)
          .setMaxInitialIterableSize(MAX_COLLECTION_ELEMENTS)
          .build()
      )
      .build()

  val responsePayload = commandSender.sendInspectorCommand(ProtocolConstants.COMPOSE_INSPECTOR_ID, getAllParamsCmd.toByteArray())
  val composeResponse = LayoutInspectorComposeProtocol.Response.parser().parseTreeResponse(responsePayload)

  return composeResponse
    .requireVariant(LayoutInspectorComposeProtocol.Response.SpecializedCase.GET_ALL_PARAMETERS_RESPONSE)
    .getAllParametersResponse
}

/**
 * Asks the Compose inspector for the parameter details of the value identified by [reference]: a parameter, or a value nested inside one,
 * like the elements of a collection. The inspector answers from its last capture of the window.
 *
 * When the inspector can't find the value, e.g. because the window closed or the Composable is gone, it returns an empty response instead
 * of an error: one without a parameter, see [LayoutInspectorComposeProtocol.GetParameterDetailsResponse.hasParameter].
 *
 * The value is expanded with the same limits as in the dump, counted from the value itself:
 * - An object comes with its content.
 * - A collection comes with the elements from [ParameterReference.startIndex] on: as many as [elementsReceived], and at least
 *   [MAX_COLLECTION_ELEMENTS]. [elementsReceived] is the number of elements of the collection already received in this call, so each page
 *   is as large as all the previous ones together.
 */
internal suspend fun queryParameterDetails(
  commandSender: CommandSender,
  reference: ParameterReference,
  elementsReceived: Int,
): LayoutInspectorComposeProtocol.GetParameterDetailsResponse {
  val getParameterDetailsCmd =
    LayoutInspectorComposeProtocol.Command.newBuilder()
      .setGetParameterDetailsCommand(
        LayoutInspectorComposeProtocol.GetParameterDetailsCommand.newBuilder()
          .setRootViewId(reference.rootViewId)
          .setReference(
            LayoutInspectorComposeProtocol.ParameterReference.newBuilder()
              .setComposableId(reference.composableId)
              .setKind(reference.kind.toProto())
              .setParameterIndex(reference.parameterIndex)
              .addAllCompositeIndex(reference.compositeIndex)
          )
          .setStartIndex(reference.startIndex)
          // Unlike the other limits, the inspector has no default for this one. It ignores it when starting from the first element.
          .setMaxElements(maxOf(MAX_COLLECTION_ELEMENTS, elementsReceived))
          .setMaxRecursions(MAX_PARAMETER_DEPTH)
          .setMaxInitialIterableSize(MAX_COLLECTION_ELEMENTS)
          // Same as the parameters command, so that the inspector finds the values it captured for the tree command.
          .setSkipSystemComposables(false)
          .setGeneration(0)
          .build()
      )
      .build()

  val responsePayload = commandSender.sendInspectorCommand(ProtocolConstants.COMPOSE_INSPECTOR_ID, getParameterDetailsCmd.toByteArray())
  val composeResponse = LayoutInspectorComposeProtocol.Response.parser().parseTreeResponse(responsePayload)

  return composeResponse
    .requireVariant(LayoutInspectorComposeProtocol.Response.SpecializedCase.GET_PARAMETER_DETAILS_RESPONSE)
    .getParameterDetailsResponse
}

private fun ParameterReference.Kind.toProto(): LayoutInspectorComposeProtocol.ParameterReference.Kind =
  when (this) {
    ParameterReference.Kind.PARAMETERS -> LayoutInspectorComposeProtocol.ParameterReference.Kind.NORMAL
    ParameterReference.Kind.MERGED_SEMANTICS -> LayoutInspectorComposeProtocol.ParameterReference.Kind.MERGED_SEMANTICS
    ParameterReference.Kind.UNMERGED_SEMANTICS -> LayoutInspectorComposeProtocol.ParameterReference.Kind.UNMERGED_SEMANTICS
  }

/**
 * Returns this response when it carries [expected]. A response of another type is a failure: protobuf serves a default message for an unset
 * variant, so reading the wrong one silently yields empty data.
 */
private fun LayoutInspectorComposeProtocol.Response.requireVariant(
  expected: LayoutInspectorComposeProtocol.Response.SpecializedCase
): LayoutInspectorComposeProtocol.Response {
  check(specializedCase == expected) { "Unexpected Compose inspector response: expected $expected, got $specializedCase" }
  return this
}

/** Queries the target application agent for whether the app uses Compose, and which version. */
private suspend fun detectCompose(commandSender: CommandSender, logger: Logger): ComposeDetection {
  val getVersionCommand =
    UiInspectorProtocol.Command.newBuilder()
      .setGetVersion(UiInspectorProtocol.GetVersionCommand.newBuilder().addLibraryIds(ProtocolConstants.COMPOSE_UI_LIBRARY_ID).build())
      .build()

  val versionResponse = commandSender.sendMessage(getVersionCommand)
  // Only a successful answer without a Compose entry means "no Compose": a failed query says nothing about the app.
  check(versionResponse.status == UiInspectorProtocol.Response.Status.SUCCESS) {
    "Failed to query the Compose version: ${versionResponse.errorMessage}"
  }

  val getVersion = versionResponse.requireVariant(UiInspectorProtocol.Response.SpecializedCase.GET_VERSION).getVersion
  val composeVersion = getVersion.versionsMap[ProtocolConstants.COMPOSE_UI_LIBRARY_ID]
  return when {
    composeVersion != null -> {
      logger.log(LogLevel.PROGRESS, "Compose detected: $composeVersion")
      ComposeDetection.Present(composeVersion)
    }
    ProtocolConstants.COMPOSE_UI_LIBRARY_ID in getVersion.librariesWithoutVersionList -> {
      logger.log(LogLevel.PROGRESS, "Compose detected, but its version is unknown (the version file is missing, empty or unreadable)")
      ComposeDetection.PresentWithoutVersion
    }
    else -> {
      logger.log(LogLevel.PROGRESS, "Compose not detected in target application.")
      ComposeDetection.Absent
    }
  }
}

/** Deploys the Compose Inspector JAR to the device sandbox and requests the agent to load it dynamically. */
private suspend fun launchComposeInspector(
  commandSender: CommandSender,
  injectionManager: InjectionManager,
  jar: Path,
  logger: Logger,
) {
  val inspectorMetadata = InspectorMetadata(id = ProtocolConstants.COMPOSE_INSPECTOR_ID, localJarPath = jar)
  val dexPath = injectionManager.stageInspectorPayload(inspectorMetadata)

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
  check(createResponse.status == UiInspectorProtocol.Response.Status.SUCCESS) {
    "The agent could not load the Compose inspector: ${createResponse.errorMessage}"
  }
  createResponse.requireVariant(UiInspectorProtocol.Response.SpecializedCase.CREATE_INSPECTOR)
  logger.log(LogLevel.PROGRESS, "Compose Inspector successfully loaded on agent!")
}

/**
 * Resolves the Maven Artifact ID for Jetpack Compose core UI based on its version.
 *
 * Prior to the Kotlin Multiplatform (KMP) transition in Compose 1.5.0, all Android specific binaries were published under the unified "ui"
 * artifact. From 1.5.0 onwards, they were split into platform-specific modules, with the Android-specific classes packaged inside the
 * "ui-android" artifact.
 */
internal fun getComposeArtifactId(version: String): String {
  val parts = version.split('.')
  if (parts.size < 2) return "ui" // Fallback to legacy
  val major = parts[0].toIntOrNull() ?: 0
  val minor = parts[1].toIntOrNull() ?: 0
  val isPreKmp = major < 1 || (major == 1 && minor < 5)
  return if (isPreKmp) "ui" else "ui-android"
}
