/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.adblib.tools.debugging.impl

import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.property
import com.android.adblib.tools.AdbLibToolsProperties.RESUME_PROCESS_DELAY_BEFORE_CLOSING_JDWP_SESSION
import com.android.adblib.tools.debugging.ExternalJdwpProcessCommandDispatcher
import com.android.adblib.tools.debugging.ExternalJdwpProcessCommandDispatcher.ProcessCommand.ResumeJdwpProcess
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.JdwpSession
import com.android.adblib.tools.debugging.OptionalValue
import com.android.adblib.tools.debugging.adbLogger
import com.android.adblib.tools.debugging.externalJdwpProcessCommandDispatcherList
import com.android.adblib.tools.debugging.jdwpPropertiesCollector
import com.android.adblib.tools.debugging.jdwpProxySocketServer
import com.android.adblib.tools.debugging.packets.JdwpPacketBuilders
import com.android.adblib.tools.debugging.sendAndReceiveCommand
import com.android.adblib.tools.debugging.useAppInfoForProcessProperties
import com.android.adblib.utils.runAlongOtherScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Implementation of services related to [JdwpProcess.resumeProcess], [ExternalJdwpProcessCommandDispatcher] and
 * [useAppInfoForProcessProperties].
 */
internal class ResumeProcessImpl(private val process: JdwpProcess) {
  private val logger = process.adbLogger()

  private val device: ConnectedDevice
    get() = process.device

  private val pid: Int
    get() = process.pid

  /**
   * Resumes execution of this [JdwpProcess] if it is in the [JdwpProcessProperties.isWaitingForDebugger] state.
   *
   * Note: This method uses different strategies depending on the device configuration and [AdbSession] configuration, including dispatching
   * the call to other [AdbSession] instances via pre-registered [ExternalJdwpProcessCommandDispatcher] if needed.
   */
  suspend fun resumeProcess() {
    val externalDispatchers = process.externalJdwpProcessCommandDispatcherList()
    when {
      device.useAppInfoForProcessProperties() -> {
        // When using `app_info`, we can resume the process from any session, as there should
        // no active JDWP connection, since no session should be using a JDWP connection to
        // collect JdwpProcessProperties
        resumeProcessImpl()
      }
      externalDispatchers.isEmpty() -> {
        // If there are no external command dispatchers installed, we resume the process
        // directly from this session
        resumeProcessImpl()
      }
      else -> {
        // If there are external dispatchers, forward the command to all of them so the
        // process is resumed by the one and only one implementation that has an active
        // JDWP connection on the process
        externalDispatchers.forEach {
          logger.debug { "Forwarding `resumeProcess` call to '$it'" }
          it.executeCommand(ResumeJdwpProcess(pid))
        }
      }
    }
  }

  /**
   * Calls [resumeProcessImpl] on this [JdwpProcess] if this process is currently holding onto the JDWP connection to the process on the
   * Android device.
   * * Returns whether [resumeProcessImpl] was actually called
   */
  internal suspend fun resumeProcessIfJdwpSessionHolder(): Boolean {
    val process = process as? JdwpProcessImpl ?: return false

    // Note: we need to use `runAlongOtherScope` because 1) we are waiting on a value from a
    // `StateFlow` and 2) `StateFlows` never end.
    val isWaitingForDebugger =
      runAlongOtherScope(process.scope) {
        // Wait for the "isWaitingForDebugger" property
        process.jdwpPropertiesCollector.stateFlow.first { props -> props.isWaitingForDebugger.hasValue }.isWaitingForDebugger.getOrThrow()
      }

    return if (isWaitingForDebugger) {
      if (process.jdwpSessionActivationCount.value > 0) {
        logger.debug { "Resuming this JDWP process instance because it holds the " + "JDWP session and `isWaitingForDebugger` is `true`" }
        resumeProcessImpl()
        true
      } else {
        logger.debug { "Skipping resume because JDWP session is not active" }
        false
      }
    } else {
      logger.debug { "Skipping resume: `isWaitingForDebugger` is false" }
      false
    }
  }

  /** Resumes execution of this [JdwpProcess] if it is in the [JdwpProcessProperties.isWaitingForDebugger] state. */
  private suspend fun resumeProcessImpl() {
    // Note: we need to use `runAlongOtherScope` because we are waiting on a value from a
    // `StateFlow` and `StateFlows` never end.
    val isWaitingForDebugger =
      runAlongOtherScope(process.scope) {
        logger.debug { "Waiting for `isWaitingForDebugger` property to be set" }
        process.jdwpPropertiesCollector.stateFlow.first { props -> props.isWaitingForDebugger.hasValue }.isWaitingForDebugger.getOrThrow()
      }
    logger.debug { "isWaitingForDebugger = $isWaitingForDebugger" }

    if (isWaitingForDebugger) {
      if (device.useAppInfoForProcessProperties()) {
        // If `app_info` is supported, we can open a direct JDWP connection to the
        // process (via the `jdwp:` service) to resume the process. `app_info` tracks the
        // `isWaitingForDebugger` status from the Art VM on the device, so there is no
        // need to go through the JDWP Proxy Server.
        resumeProcessUsingDirectJdwpConnection()
      } else {
        // If `app_info` is **not** supported, we need to open a JDWP connection to
        // the process through the JDWP Proxy Server so that it can see the process is
        // resumed by inspecting the JDWP packets. This is required as the Art VM
        // does not have the capability to inform us the process has been resumed.
        resumeProcessUsingJdwpProxySocketServer()
      }
      logger.debug { "Process execution should now be resumed" }
    }
  }

  private suspend fun resumeProcessUsingDirectJdwpConnection() {
    require(device.useAppInfoForProcessProperties()) { "This method should only be called if the device supports `app_info`" }
    logger.debug { "Opening direct JDWP connection to resume process execution" }
    process.withJdwpSession {
      // Sends an "VM_ID_SIZES" command packet and wait for the reply. The Android VM (Art)
      // considers this as a signal to "resume" the JDWP process execution.
      val idSizesCommand = JdwpPacketBuilders.Commands.vmIdSizes(nextPacketId())
      sendAndReceiveCommand(idSizesCommand)

      // See b/437438918: The debugger thread on Art VM has a 200 millis spin loop
      // delay that can make it miss resetting the "waiting for debugger" flag if
      // the JDWP connection is closed quickly after processing the first JDWP packet.
      waitForProcessToResumeWhileJdwpSessionIsOpen()
    }
  }

  private suspend fun resumeProcessUsingJdwpProxySocketServer() {
    require(!device.useAppInfoForProcessProperties()) { "This method should not be called if the device supports `app_info`" }
    logger.debug { "Opening JDWP connection to JDWP Proxy Server to resume process execution" }

    // Wait for JDWP proxy server to be ready.
    val status = process.jdwpProxySocketServer.proxyStatusFlow.first { it.socketAddress.hasValue }

    // Connect to JDWP Proxy server and send a `ID_SIZES` jdwp packet
    device.session.channelFactory.connectSocket(status.socketAddress.getOrThrow()).use { socket ->
      logger.debug { "Connection to Debugger proxy successfully established: $socket" }
      JdwpSession.wrapSocketChannel(device = device, channel = socket, pid = pid, nextPacketIdBase = 100).use { jdwpSession ->
        logger.debug { "jdwp session opened successfully = $jdwpSession" }
        // Sends an "VM_ID_SIZES" command packet and wait for the reply. The Android VM (Art)
        // considers this as a signal to "resume" the JDWP process execution.
        val idSizesCommand = JdwpPacketBuilders.Commands.vmIdSizes(jdwpSession.nextPacketId())
        jdwpSession.sendPacket(idSizesCommand)
        logger.verbose { "jdwp session packet sent: $idSizesCommand" }

        // Wait for reply to the VM_ID_SIZES command packet, as an indicator the process
        // has been resumed by the Art VM.
        while (true) {
          val reply = jdwpSession.receivePacket()
          logger.verbose { "jdwp session packet received: $reply" }
          if (reply.isReply && reply.id == idSizesCommand.id) {
            break
          }
        }

        // See b/437438918: The debugger thread on Art VM has a 200 millis spin loop
        // delay that can make it miss resetting the "waiting for debugger" flag if
        // the JDWP connection is closed quickly after processing the first JDWP packet.
        waitForProcessToResumeWhileJdwpSessionIsOpen()
      }
    }
  }

  /** Waits for [JdwpProcessProperties.isWaitingForDebugger] to be `false`. */
  private suspend fun waitForProcessToResumeWhileJdwpSessionIsOpen() {
    // Note: Use the process scope to ensure prompt cancellation if the process is terminated
    runAlongOtherScope(process.scope) {
      if (!device.useAppInfoForProcessProperties()) {
        // If **not** using `app_info`, [JdwpProcessProperties.isWaitingForDebugger] may
        // be updated to `false` too early or a little late, because the value is updated by
        // the [JdwpProxySocketServer], not by the Art VM.
        // So, the only "reliable" wait is to wait for some amount of time
        delay(device.session.property(RESUME_PROCESS_DELAY_BEFORE_CLOSING_JDWP_SESSION).toMillis())
      }

      // Whether we use `app_info` or local JDWP connection or Process Inventory Server,
      // `isWaitingForDebugger` should always end up with a `false` value.
      process.jdwpPropertiesCollector.stateFlow.first { props -> props.isWaitingForDebugger.isValue(false) }
    }
  }

  private fun <T : Any> OptionalValue<T>.isValue(value: T): Boolean {
    return this.hasValue && this.getOrThrow() == value
  }
}
