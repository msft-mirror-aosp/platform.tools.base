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

package com.android.tools.ui.inspector.payload.appinspection

import android.os.Handler
import android.util.Log
import androidx.inspection.ArtTooling
import androidx.inspection.Connection
import androidx.inspection.Inspector
import androidx.inspection.InspectorEnvironment
import androidx.inspection.InspectorExecutors
import androidx.inspection.InspectorFactory
import com.android.tools.idea.protobuf.ByteString
import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.payload.SessionHandler
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import dalvik.system.DexClassLoader
import java.io.IOException
import java.io.OutputStream
import java.util.ServiceLoader
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "studio.AppInspectionUtils"

/**
 * Creates a [Connection] that writes events to the given [OutputStream] using [FramingProtocol].
 *
 * Provides an interoperability bridge with the App Inspection framework, adapting its standard interfaces to the UI Inspector's custom
 * transport and routing protocol.
 */
internal fun createAppInspectionConnection(
  inspectorId: String,
  outputStream: OutputStream,
  crashListener: (Throwable) -> Unit,
): Connection {
  return object : Connection() {
    override fun sendEvent(event: ByteArray) {
      try {
        val payload = ByteString.copyFrom(event)
        val inspectorEvent = UiInspectorProtocol.InspectorMessageEvent.newBuilder().setInspectorId(inspectorId).setPayload(payload).build()
        val eventWrapper = UiInspectorProtocol.Event.newBuilder().setInspectorMessage(inspectorEvent).build()
        // Synchronize to prevent byte interleaving from concurrent events, since output stream is shared across inspectors
        // TODO: refactor using a channel/actor model to enforce sequential writing of messages to the output stream
        synchronized(outputStream) { FramingProtocol.writeMessage(outputStream, eventWrapper.toByteArray()) }
      } catch (e: IOException) {
        Log.e(TAG, "IO error sending event", e)
      } catch (e: Exception) {
        Log.e(TAG, "Unexpected error sending event", e)
        crashListener(e)
      }
    }
  }
}

/** A [Connection] that delegates to another [Connection]. Used to support reconnecting to existing inspectors across different sessions. */
// Volatile ensures that updates to activeConnection by the server thread are immediately visible to the inspector thread.
internal class DelegatingConnection(@Volatile var activeConnection: Connection? = null) : Connection() {

  override fun sendEvent(event: ByteArray) {
    activeConnection?.sendEvent(event) ?: Log.w(TAG, "No active connection to send event")
  }
}

/** Executor for disk I/O operations. We use a fixed thread pool of size 4, matching the behavior of AppInspectionService. */
private val IoExecutor = Executors.newFixedThreadPool(4)

internal fun createInspectorEnvironment(primaryExecutor: HandlerThreadExecutor, crashListener: (Throwable) -> Unit): InspectorEnvironment {
  return object : InspectorEnvironment {
    override fun artTooling(): ArtTooling {
      // TODO: Implement ArtTooling methods to support finding instances and setting hooks.
      return object : ArtTooling {
        override fun <T> findInstances(clazz: Class<T>): List<T> = emptyList()

        override fun registerEntryHook(originClass: Class<*>, originMethod: String, entryHook: ArtTooling.EntryHook) {}

        override fun <T> registerExitHook(originClass: Class<*>, originMethod: String, exitHook: ArtTooling.ExitHook<T>) {}
      }
    }

    override fun executors(): InspectorExecutors {
      return object : InspectorExecutors {
        override fun handler(): Handler = primaryExecutor.handler

        override fun primary(): Executor = primaryExecutor

        override fun io(): Executor = createDelegateExecutor(IoExecutor, crashListener)
      }
    }
  }
}

/** Creates an executor that delegates work to the given one and forwards all uncaught exceptions to [crashListener]. */
private fun createDelegateExecutor(delegate: Executor, crashListener: (Throwable) -> Unit): Executor {
  return Executor { command ->
    delegate.execute {
      try {
        command.run()
      } catch (t: Throwable) {
        crashListener(t)
      }
    }
  }
}

/** Suspends the coroutine until the inspector replies to the command. */
internal suspend fun Inspector.handleCommandSuspend(command: ByteArray): ByteArray = suspendCancellableCoroutine { continuation ->
  val callback =
    object : Inspector.CommandCallback {
      override fun reply(responseBytes: ByteArray) {
        continuation.resume(responseBytes)
      }

      override fun addCancellationListener(executor: Executor, runnable: Runnable) {
        continuation.invokeOnCancellation { executor.execute(runnable) }
      }
    }
  onReceiveCommand(command, callback)
}

/**
 * Dynamically loads an inspector from a dex file using [ServiceLoader], matching the mechanism used by App Inspection's
 * `InspectorContext.java`.
 *
 * Note that there are a few differences from App Inspection's implementation:
 * 1. It does not cache the [DexClassLoader]. App Inspection caches them to avoid native library loading conflicts (b/187342510) if the same
 *    jar is loaded multiple times. We rely on persisting [InspectorBridge]s instead.
 * 2. It uses `SessionHandler::class.java.classLoader` as the parent class loader, whereas App Inspection uses the application's class
 *    loader. Since the session handler's loader is already a child of the application's class loader, app classes remain visible through
 *    delegation.
 * 3. It does not support native pointers in [InspectorEnvironment].
 */
internal fun loadInspectorDynamically(
  inspectorId: String,
  dexPath: String,
  connection: Connection,
  environment: InspectorEnvironment,
): Inspector {
  val optimizedDir = System.getProperty("java.io.tmpdir")
  val classLoader = DexClassLoader(dexPath, optimizedDir, null, SessionHandler::class.java.classLoader)
  val loader = ServiceLoader.load(InspectorFactory::class.java, classLoader)
  val iterator = loader.iterator()
  var inspector: Inspector? = null
  while (iterator.hasNext()) {
    val factory = iterator.next()
    if (factory.inspectorId == inspectorId) {
      inspector = factory.createInspector(connection, environment)
      break
    }
  }
  if (inspector == null) {
    throw Exception("Failed to find InspectorFactory with id $inspectorId")
  }
  return inspector
}
