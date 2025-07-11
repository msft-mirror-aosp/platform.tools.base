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
package com.android.adblib.tools.debugging.processinventory.impl

import com.android.adblib.AdbSession
import com.android.adblib.adbLogger
import com.android.adblib.generateUniqueUUID
import com.android.adblib.tools.debugging.ExternalJdwpProcessCommandDispatcher
import com.android.adblib.tools.debugging.ExternalJdwpProcessCommandDispatcher.ProcessCommand
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.impl.JdwpProcessImpl
import com.android.adblib.tools.debugging.jdwpPropertiesCollector
import com.android.adblib.tools.debugging.processinventory.ProcessInventoryServerConnection
import com.android.adblib.tools.debugging.processinventory.ProcessInventoryServerConnection.ConnectionForDevice
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.ProcessCommandReply.CommandResultCase
import com.android.adblib.tools.debugging.processinventory.server.ProcessInventoryServer
import com.android.adblib.tools.debugging.resumeProcessImpl
import com.android.adblib.utils.logIOCompletionErrors
import com.android.adblib.utils.runAlongOtherScope
import com.android.adblib.withProcessPrefix
import com.google.protobuf.TextFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Implementation of [ExternalJdwpProcessCommandDispatcher] that connects to a remote
 * [ProcessInventoryServer]
 */
internal class ProcessInventoryJdwpProcessCommandDispatcher(
    private val serverConnection: ProcessInventoryServerConnection,
    override val process: JdwpProcess
) : ExternalJdwpProcessCommandDispatcher {

    private val session: AdbSession
        get() = process.device.session

    private val logger = adbLogger(session).withProcessPrefix(process.device, process.pid)

    private val processDispatchedCommandsDeferred by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        process.scope.launch {
            runCatching {
                processDispatchedCommands()
            }.onFailure { throwable ->
                logger.logIOCompletionErrors(throwable)
            }
        }
        CompletableDeferred<Unit>()
    }

    override suspend fun start() {
        processDispatchedCommandsDeferred.await()
    }

    /**
     * Sends the [command] to the process inventory server for dispatching
     */
    override suspend fun executeCommand(command: ProcessCommand) {
        serverConnection.withConnectionForDevice(process.device) {
            logger.verbose { "Sending command $command for execution on device ${process.device}" }

            // The command UUID (used to match with the reply)
            val commandUuid = process.device.session.generateUniqueUUID()

            coroutineScope {
                // Set up a "reply handler" before sending the command so we are ready
                val replyProcessingCoroutineReady = CompletableDeferred<Unit>()
                val replyDeferred = async {
                    processCommandReplySharedFlow.onSubscription {
                        replyProcessingCoroutineReady.complete(Unit)
                    }.first { replyProto ->
                        logger.verbose { "Received reply from device connection: ${TextFormat.shortDebugString(replyProto)}" }
                        if (replyProto.commandUuid == commandUuid) {
                            when (replyProto.commandResultCase) {
                                CommandResultCase.COMMAND_IGNORED -> {
                                    // Keep collecting: command ignored by peer
                                    false
                                }

                                CommandResultCase.COMMAND_EXECUTED_OK -> {
                                    // Stop collecting: command was executed correctly
                                    true
                                }

                                CommandResultCase.COMMAND_EXECUTED_WITH_ERROR -> {
                                    // Stop collecting: command was executed with an error
                                    true
                                }

                                CommandResultCase.COMMANDRESULT_NOT_SET, null -> {
                                    // Keep collecting: command not supported by peer
                                    false
                                }
                            }
                        } else {
                            // Keep collecting: reply to some other command
                            false
                        }
                    }
                }
                replyProcessingCoroutineReady.await()

                // Send command to peers
                sendProcessCommand(command.toProcessCommandProto(commandUuid))

                // Wait for the reply from the peer than handled the command
                val replyProto = replyDeferred.await()
                logger.debug { "Received reply: $replyProto" }
                if (replyProto.hasCommandExecutedWithError()) {
                    throw IOException(replyProto.commandExecutedWithError)
                }
            }
        }
    }

    /**
     * Process commands targeted at this [process]
     */
    private suspend fun processDispatchedCommands() {
        // Use the device flow, and filter to this process only
        serverConnection.withConnectionForDevice(process.device) {
            val connectionForDevice = this

            connectionForDevice.processCommandSharedFlow.onSubscription {
                logger.debug { "Dispatching commands from device connection $connectionForDevice" }
                processDispatchedCommandsDeferred.complete(Unit)
            }.filter { processCommand ->
                processCommand.pid == process.pid
            }.collect { processCommand ->
                logger.debug { "Process ${processCommand.pid} command received: ${TextFormat.shortDebugString(processCommand)}" }
                runCatching {
                    when (processCommand.commandCase) {
                        ProcessInventoryServerProto.ProcessCommand.CommandCase.RESUME_JDWP_PROCESS -> {
                            // We want to execute the command only if this process instances is
                            // holding on the JDWP session *and* the process is waiting
                            process.resumeProcessIfJdwpSessionHolder()
                        }

                        else -> {
                            logger.info { "Unsupported process command: $processCommand" }
                            false // not handled
                        }
                    }
                }.onFailure { t ->
                    logger.logIOCompletionErrors(t)
                    connectionForDevice.sendErrorCommandReply(processCommand, t)
                }.onSuccess { handled ->
                    if (handled) {
                        connectionForDevice.sendOkCommandReply(processCommand)
                    } else {
                        connectionForDevice.sendIgnoredCommandReply(processCommand)
                    }
                }
            }
        }
    }

    /**
     * Calls [resumeProcessImpl] on this [JdwpProcess] if this process is currently holding onto
     * the JDWP connection to the process on the Android device.
     * * Returns whether [resumeProcessImpl] was actually called
     */
    private suspend fun JdwpProcess.resumeProcessIfJdwpSessionHolder(): Boolean {
        val process = this as? JdwpProcessImpl ?: return false

        // Note: we need to use `runAlongOtherScope` because 1) we are waiting on a value from a
        // `StateFlow` and 2) `StateFlows` never end.
        val isWaitingForDebugger = runAlongOtherScope(process.scope) {
            // Wait for the "isWaitingForDebugger" property
            process.jdwpPropertiesCollector.stateFlow.first { props ->
                props.isWaitingForDebugger.hasValue
            }.isWaitingForDebugger.getOrThrow()
        }

        return if (isWaitingForDebugger) {
            if (process.jdwpSessionActivationCount.value > 0) {
                logger.debug {
                    "Resuming this JDWP process instance because it holds the " +
                            "JDWP session and `isWaitingForDebugger` is `true`"
                }
                process.resumeProcessImpl()
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

    override fun toString(): String {
        return "${this::class.java.simpleName}(process=$process)"
    }

    companion object {

        private fun ProcessCommand.toProcessCommandProto(commandUuid: String): ProcessInventoryServerProto.ProcessCommand {
            return when (this) {
                is ProcessCommand.ResumeJdwpProcess -> {
                    ProcessInventoryServerProto.ProcessCommand
                        .newBuilder()
                        .setCommandUuid(commandUuid)
                        .setPid(pid)
                        .setResumeJdwpProcess(
                            ProcessInventoryServerProto.ProcessCommand.ResumeJdwpProcess
                                .newBuilder()
                                .build()
                        )
                        .build()
                }
            }
        }

        private suspend fun ConnectionForDevice.sendOkCommandReply(
            processCommand: ProcessInventoryServerProto.ProcessCommand
        ) {
            sendProcessCommandReply(
                ProcessInventoryServerProto.ProcessCommandReply
                    .newBuilder()
                    .setCommandUuid(processCommand.commandUuid)
                    .setPid(processCommand.pid)
                    .setCommandExecutedOk(true)
                    .build()
            )
        }

        private suspend fun ConnectionForDevice.sendIgnoredCommandReply(
            processCommand: ProcessInventoryServerProto.ProcessCommand
        ) {
            sendProcessCommandReply(
                ProcessInventoryServerProto.ProcessCommandReply
                    .newBuilder()
                    .setCommandUuid(processCommand.commandUuid)
                    .setPid(processCommand.pid)
                    .setCommandIgnored(true)
                    .build()
            )
        }

        private suspend fun ConnectionForDevice.sendErrorCommandReply(
            processCommand: ProcessInventoryServerProto.ProcessCommand,
            t: Throwable
        ) {
            sendProcessCommandReply(
                ProcessInventoryServerProto.ProcessCommandReply
                    .newBuilder()
                    .setCommandUuid(processCommand.commandUuid)
                    .setPid(processCommand.pid)
                    .setCommandExecutedWithError(t.message)
                    .build()
            )
        }
    }
}
