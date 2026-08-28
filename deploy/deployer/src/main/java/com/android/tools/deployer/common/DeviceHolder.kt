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
package com.android.tools.deployer.common

import com.android.adblib.AdbChannel
import com.android.adblib.AdbPackageManagerException
import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.DevicePropertyNames
import com.android.adblib.DeviceSelector
import com.android.adblib.RemoteFileMode
import com.android.adblib.ShellCollector
import com.android.adblib.abbCommand
import com.android.adblib.availableFeatures
import com.android.adblib.deviceProperties
import com.android.adblib.isRoot
import com.android.adblib.packageManager
import com.android.adblib.property
import com.android.adblib.read
import com.android.adblib.rootAndWait
import com.android.adblib.serialNumber
import com.android.adblib.shellCommand
import com.android.adblib.syncSend
import com.android.adblib.tools.debugging.getOrNull
import com.android.adblib.tools.debugging.jdwpProcessTracker
import com.android.adblib.tools.debugging.properties
import com.android.adblib.write
import com.android.ddmlib.AdbCommandRejectedException
import com.android.ddmlib.IDevice
import com.android.ddmlib.IShellOutputReceiver
import com.android.ddmlib.InstallException
import com.android.ddmlib.ShellCommandUnresponsiveException
import com.android.ddmlib.SimpleConnectedSocket
import com.android.ddmlib.SyncException
import com.android.ddmlib.TimeoutException as DdmlibTimeoutException
import com.android.sdklib.AndroidVersion
import com.android.sdklib.AndroidVersionUtil
import com.android.tools.deploy.proto.Deploy
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Duration
import java.util.Optional
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking

class DeviceHolder
@JvmOverloads
constructor(
  private val iDevice: IDevice,
  /**
   * The resolved [ConnectedDevice] from the [AdbSession] when adblib migration is enabled.
   *
   * The nullability of this [Optional] reflects the lookup outcome:
   * - `null`: Lookup was not attempted (legacy execution path or tests).
   * - `Optional.empty()`: Lookup was attempted, but no matching [ConnectedDevice] was found, e.g. because the device got disconnected.
   * - `Optional.of(device)`: Lookup was attempted and a matching [ConnectedDevice] was successfully resolved.
   */
  private val connectedDevice: Optional<ConnectedDevice>?,
  /** Whether the adblib migration is enabled. */
  private val useConnectedDevice: Boolean = false,
) {

  constructor(
    iDevice: IDevice,
    connectedDevice: ConnectedDevice?,
    session: AdbSession,
  ) : this(
    iDevice = iDevice,
    connectedDevice = Optional.ofNullable(connectedDevice),
    useConnectedDevice = checkEnableUseConnectedDevice(session),
  )

  val version: AndroidVersion
    get() {
      return runMigrated(
        onLegacy = {
          // Retrieve using IDevice
          iDevice.version
        },
        onMigrated = { connectedDevice ->
          // Retrieve using ConnectedDevice
          val properties =
            try {
              runBlocking { connectedDevice.deviceProperties().allReadonly() }
            } catch (_: Exception) {
              // Do not throw exceptions to match the iDevice.version behavior
              null
            }
          properties?.let { AndroidVersionUtil.androidVersionFromDeviceProperties(it) } ?: AndroidVersion.DEFAULT
        },
        onMigratedWhenDeviceNotFound = { AndroidVersion.DEFAULT },
      )
    }

  val serialNumber: String
    get() =
      runMigrated(
        onLegacy = { iDevice.serialNumber },
        onMigrated = { connectedDevice -> connectedDevice.serialNumber },
        onMigratedWhenDeviceNotFound = {
          // When removing IDevice from deployer we would have to pass `serialNumber` directly.
          iDevice.serialNumber
        },
      )

  val abis: List<String>
    get() =
      runMigrated(
        onLegacy = { iDevice.abis },
        onMigrated = { connectedDevice ->
          val properties =
            try {
              runBlocking { connectedDevice.deviceProperties().allReadonly() }
            } catch (_: Exception) {
              null
            }
          val abiList = properties?.get(DevicePropertyNames.RO_PRODUCT_CPU_ABILIST)
          if (abiList != null) {
            abiList.split(",")
          } else {
            val abi = properties?.get(DevicePropertyNames.RO_PRODUCT_CPU_ABI)
            val abi2 = properties?.get(DevicePropertyNames.RO_PRODUCT_CPU_ABI2)
            listOfNotNull(abi, abi2)
          }
        },
        onMigratedWhenDeviceNotFound = { emptyList() },
      )

  fun getPidsForPackageName(packageName: String): List<Int> {
    return runMigrated(
      onLegacy = { iDevice.clients.filter { packageName == it.clientData.packageName }.map { it.clientData.pid } },
      onMigrated = { connectedDevice ->
        val realPkgNameSupported = isRealPkgNameSupported
        connectedDevice.jdwpProcessTracker.processesFlow.value
          .filter { process ->
            val properties = process.properties
            val processPackageName =
              if (realPkgNameSupported) {
                properties.packageName.getOrNull()
              } else {
                properties.processName.getOrNull()?.substringBefore(':')
              }
            processPackageName == packageName
          }
          .map { it.pid }
      },
      onMigratedWhenDeviceNotFound = { emptyList() },
    )
  }

  fun getArchForPid(pid: Int): Deploy.Arch {
    return runMigrated(
      onLegacy = {
        val client = iDevice.clients.firstOrNull { it.clientData.pid == pid } ?: return@runMigrated Deploy.Arch.ARCH_UNKNOWN
        val abi = client.clientData.abi ?: return@runMigrated Deploy.Arch.ARCH_UNKNOWN
        when {
          abi.startsWith("32-bit") -> Deploy.Arch.ARCH_32_BIT
          abi.startsWith("64-bit") -> Deploy.Arch.ARCH_64_BIT
          else -> AdbClient.getArchForAbi(abi) ?: Deploy.Arch.ARCH_UNKNOWN
        }
      },
      onMigrated = { connectedDevice ->
        val process = connectedDevice.jdwpProcessTracker.processesFlow.value.firstOrNull { it.pid == pid }
        val instructionSet = process?.properties?.instructionSet?.getOrNull() ?: return@runMigrated Deploy.Arch.ARCH_UNKNOWN
        if (instructionSet.is64Bit) Deploy.Arch.ARCH_64_BIT else Deploy.Arch.ARCH_32_BIT
      },
      onMigratedWhenDeviceNotFound = { Deploy.Arch.ARCH_UNKNOWN },
    )
  }

  val isRoot: Boolean
    get() = runMigrated(onLegacy = { iDevice.isRoot }, onMigrated = { connectedDevice -> runBlocking { connectedDevice.isRoot() } })

  @Throws(IOException::class)
  fun rawExec2(executable: String, parameters: Array<String>): DeployerConnectedSocket {
    return runMigrated(
      onLegacy = {
        try {
          DdmlibSocketAdapter(iDevice.rawExec2(executable, parameters))
        } catch (e: Exception) {
          when (e) {
            is AdbCommandRejectedException,
            is DdmlibTimeoutException -> throw IOException(e)
            else -> throw e
          }
        }
      },
      onMigrated = { connectedDevice ->
        val command = (listOf(executable) + parameters).joinToString(" ")
        try {
          val channel = runBlocking {
            connectedDevice.session.deviceServices.rawExec(DeviceSelector.fromSerialNumber(connectedDevice.serialNumber), command)
          }
          AdblibChannelSocket(channel)
        } catch (e: TimeoutException) {
          throw IOException(e)
        }
      },
    )
  }

  @Throws(IOException::class)
  fun executeShellCommand(
    command: String,
    receiver: DeployerIShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeToOutputResponseUnit: TimeUnit,
  ) {
    runMigrated(
      onLegacy = {
        try {
          iDevice.executeShellCommand(
            command,
            DeployerToDdmlibReceiverAdapter(receiver),
            maxTimeToOutputResponse,
            maxTimeToOutputResponseUnit,
          )
        } catch (e: Exception) {
          when (e) {
            is AdbCommandRejectedException,
            is ShellCommandUnresponsiveException,
            is DdmlibTimeoutException -> throw IOException(e)
            else -> throw e
          }
        }
      },
      onMigrated = { connectedDevice ->
        try {
          runBlocking {
            val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)
            // forceLegacyShell() call is needed so that we match `legacy` behavior where shell v1 protocol is forced
            val shellCommand =
              connectedDevice.session.deviceServices
                .shellCommand(deviceSelector, command)
                .forceLegacyShell()
                .shutdownOutputForLegacyShell(true)

            if (maxTimeToOutputResponse > 0) {
              shellCommand.withCommandOutputTimeout(Duration.ofMillis(maxTimeToOutputResponseUnit.toMillis(maxTimeToOutputResponse)))
            }

            shellCommand.withLegacyCollector(IShellOutputReceiverCollector(receiver)).execute().single()
          }
        } catch (e: TimeoutException) {
          throw IOException(e)
        }
      },
    )
  }

  @Throws(IOException::class)
  fun executeBinderCommand(
    parameters: Array<String>,
    receiver: DeployerIShellOutputReceiver,
    maxTimeToOutputResponse: Long,
    maxTimeToOutputResponseUnit: TimeUnit,
    inputStream: InputStream?,
  ) {
    runMigrated(
      onLegacy = {
        try {
          iDevice.executeBinderCommand(
            parameters,
            DeployerToDdmlibReceiverAdapter(receiver),
            maxTimeToOutputResponse,
            maxTimeToOutputResponseUnit,
            inputStream,
          )
        } catch (e: Exception) {
          when (e) {
            is AdbCommandRejectedException,
            is ShellCommandUnresponsiveException,
            is DdmlibTimeoutException -> throw IOException(e)
            else -> throw e
          }
        }
      },
      onMigrated = { connectedDevice ->
        try {
          runBlocking {
            val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)
            val availableFeatures = connectedDevice.availableFeatures()
            val stdinChannel = inputStream?.let { connectedDevice.session.channelFactory.wrapInputStream(it) }
            val outputTimeout =
              if (maxTimeToOutputResponse > 0) {
                Duration.ofMillis(maxTimeToOutputResponseUnit.toMillis(maxTimeToOutputResponse))
              } else null

            if (availableFeatures.contains("abb_exec")) {
              val abbCommand =
                connectedDevice.session.deviceServices
                  .abbCommand(deviceSelector, parameters.toList())
                  .forceExecProtocol()
                  .shutdownOutputForExecProtocol(false)

              outputTimeout?.let { abbCommand.withCommandOutputTimeout(it) }
              stdinChannel?.let { abbCommand.withStdin(it) }

              abbCommand.withLegacyCollector(IShellOutputReceiverCollector(receiver)).execute().single()
            } else {
              val command = "cmd " + parameters.joinToString(" ")
              val shellCommand =
                connectedDevice.session.deviceServices
                  .shellCommand(deviceSelector, command)
                  .forceLegacyShell()
                  .shutdownOutputForLegacyShell(true)

              outputTimeout?.let { shellCommand.withCommandOutputTimeout(it) }
              stdinChannel?.let { shellCommand.withStdin(it) }

              shellCommand.withLegacyCollector(IShellOutputReceiverCollector(receiver)).execute().single()
            }
          }
        } catch (e: TimeoutException) {
          throw IOException(e)
        }
      },
    )
  }

  @Throws(IOException::class)
  fun uninstallPackage(packageName: String): String? {
    return runMigrated(
      onLegacy = {
        try {
          iDevice.uninstallPackage(packageName)
        } catch (e: InstallException) {
          throw IOException(e)
        }
      },
      onMigrated = { connectedDevice ->
        try {
          runBlocking { connectedDevice.packageManager.uninstall(packageName) }
          null
        } catch (e: AdbPackageManagerException) {
          parsePmInstallErrorOutput(e.errorOutput)
        }
      },
    )
  }

  val isRealPkgNameSupported: Boolean
    get() =
      runMigrated(
        onLegacy = { iDevice.supportsFeature(IDevice.Feature.REAL_PKG_NAME) },
        onMigrated = { _ -> version.isAtLeast(29, "R") },
        onMigratedWhenDeviceNotFound = { false },
      )

  val isSkipVerificationSupported: Boolean
    get() =
      runMigrated(
        onLegacy = { iDevice.supportsFeature(IDevice.Feature.SKIP_VERIFICATION) },
        onMigrated = { _ ->
          // Relaxed check: only support API >= 30, ignoring pre-release R previews (API 29 with "R" codename).
          version.isAtLeast(30)
        },
        onMigratedWhenDeviceNotFound = { false },
      )

  val isEmbedded: Boolean
    get() =
      runMigrated(
        onLegacy = { iDevice.supportsFeature(IDevice.HardwareFeature.EMBEDDED) },
        onMigrated = { connectedDevice ->
          try {
            runBlocking {
              val characteristics = connectedDevice.deviceProperties().allReadonly()["ro.build.characteristics"] ?: ""
              characteristics.split(",").contains(IDevice.HardwareFeature.EMBEDDED.characteristic)
            }
          } catch (_: Exception) {
            false
          }
        },
        onMigratedWhenDeviceNotFound = { false },
      )

  @Throws(IOException::class)
  fun pushFile(local: String, remote: String) {
    runMigrated(
      onLegacy = {
        try {
          iDevice.pushFile(local, remote)
        } catch (e: Exception) {
          when (e) {
            is AdbCommandRejectedException,
            is SyncException,
            is DdmlibTimeoutException -> throw IOException(e)
            else -> throw e
          }
        }
      },
      onMigrated = { connectedDevice ->
        val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)
        val localFile = File(local).toPath()
        val localFileDate =
          try {
            Files.getLastModifiedTime(localFile)
          } catch (e: Exception) {
            throw IOException(e)
          }
        try {
          runBlocking {
            connectedDevice.session.deviceServices.syncSend(
              deviceSelector,
              localFile,
              remote,
              RemoteFileMode.fromPath(localFile) ?: RemoteFileMode.DEFAULT,
              localFileDate,
            )
          }
        } catch (e: TimeoutException) {
          throw IOException(e)
        }
      },
    )
  }

  @Throws(IOException::class)
  fun root(): Boolean {
    return runMigrated(
      onLegacy = {
        try {
          iDevice.root()
        } catch (e: Exception) {
          when (e) {
            is AdbCommandRejectedException,
            is ShellCommandUnresponsiveException,
            is DdmlibTimeoutException -> throw IOException(e)
            else -> throw e
          }
        }
      },
      onMigrated = { connectedDevice ->
        try {
          runBlocking {
            val deviceSelector = DeviceSelector.fromSerialNumber(connectedDevice.serialNumber)
            connectedDevice.session.deviceServices.rootAndWait(deviceSelector)
          }
          isRoot
        } catch (e: TimeoutException) {
          throw IOException(e)
        }
      },
    )
  }

  /**
   * Executes an operation using either the migrated [ConnectedDevice] path or the legacy [IDevice] path.
   *
   * Execution branches:
   * - If migration is disabled, [onLegacy] is executed.
   * - If migration is enabled and [connectedDevice] is present, [onMigrated] is executed with the unwrapped [ConnectedDevice].
   * - If migration is enabled but [connectedDevice] is missing, [onMigratedWhenDeviceNotFound] is executed.
   *
   *     TODO: Consider logging missing device warnings at call sites (e.g. Deployer/AdbInstaller) or device lookup site rather than inside
   *       individual methods here.
   */
  private inline fun <T> runMigrated(
    crossinline onLegacy: () -> T,
    crossinline onMigrated: (ConnectedDevice) -> T,
    crossinline onMigratedWhenDeviceNotFound: () -> T = { throw IOException("Connected device is not present") },
  ): T {
    // TODO: Once we add code that looks up `connectedDevice` using AdbSession.connectedDeviceTracker
    //  and feed it to DeviceHolder across the codebase we could remove the check for
    //  `connectedDevice != null`.
    return if (useConnectedDevice && connectedDevice != null) {
      if (connectedDevice.isPresent) {
        onMigrated(connectedDevice.get())
      } else {
        onMigratedWhenDeviceNotFound()
      }
    } else {
      onLegacy()
    }
  }

  companion object {
    @JvmStatic
    fun checkEnableUseConnectedDevice(session: AdbSession): Boolean {
      return session.property(DeployerProperties.USE_CONNECTED_DEVICE)
    }

    /**
     * Regex pattern to match Package Manager failure outputs and extract the failure details inside brackets. For example a failure output
     * can look like this: "Failure \[DELETE_FAILED_INTERNAL_ERROR\]"
     */
    private val PM_FAILURE_REGEX = Regex("""Failure\s+\[(.*)]""")

    /** We need to match error output parsing logic implemented in `com.android.ddmlib.InstallReceiver`. */
    private fun parsePmInstallErrorOutput(output: String): String {
      for (line in output.lines()) {
        val trimmed = line.trim()
        if (trimmed.isNotEmpty()) {
          PM_FAILURE_REGEX.matchEntire(trimmed)?.let { match ->
            return match.groupValues[1]
          }
        }
      }
      val firstLine = output.lines().firstOrNull { it.isNotBlank() } ?: output
      return if (firstLine.startsWith("Unknown failure: ")) firstLine else "Unknown failure: $firstLine"
    }
  }
}

/**
 * Adapts a [DeployerIShellOutputReceiver] to a ddmlib [IShellOutputReceiver].
 *
 * This adapter is required for the legacy [IDevice] execution path in [DeviceHolder]. It can be removed once deployer is fully migrated
 * away from ddmlib.
 */
private class DeployerToDdmlibReceiverAdapter(private val receiver: DeployerIShellOutputReceiver) : IShellOutputReceiver {
  override fun addOutput(data: ByteArray, offset: Int, length: Int) {
    receiver.addOutput(data, offset, length)
  }

  override fun flush() {
    receiver.flush()
  }

  override fun isCancelled(): Boolean {
    return receiver.isCancelled
  }
}

/**
 * Note that this is copied from
 * adblib-ddmlibcompatibility/src/com/android/adblib/ddmlibcompatibility/debugging/ShellCollectorToIShellOutputReceiver.kt
 */
private class IShellOutputReceiverCollector(private val receiver: DeployerIShellOutputReceiver) : ShellCollector<Unit> {
  private val buf = ByteArrayFromByteBuffer()

  override suspend fun start(collector: FlowCollector<Unit>) {
    // Nothing to do
  }

  override suspend fun collect(collector: FlowCollector<Unit>, stdout: ByteBuffer) {
    if (receiver.isCancelled) {
      throw CancellationException("DeployerIShellOutputReceiver was cancelled during shell command execution")
    }
    buf.convert(stdout)
    receiver.addOutput(buf.bytes, buf.offset, buf.count)
  }

  override suspend fun end(collector: FlowCollector<Unit>) {
    receiver.flush()
    collector.emit(Unit)
  }

  private class ByteArrayFromByteBuffer {
    var bytes = ByteArray(0)
    var offset = 0
    var count = 0

    fun convert(buffer: ByteBuffer) {
      if (buffer.hasArray()) {
        bytes = buffer.array()
        offset = buffer.arrayOffset() + buffer.position()
        count = buffer.remaining()
        buffer.position(buffer.limit())
      } else {
        offset = 0
        count = buffer.remaining()
        val bytes = ByteArray(count)
        buffer.get(bytes)
        this.bytes = bytes
      }
    }
  }
}

private class AdblibChannelSocket(private val channel: AdbChannel) : DeployerConnectedSocket {
  private var closed = false

  override fun read(dst: ByteBuffer, timeoutMs: Long): Int = runBlocking {
    try {
      channel.read(dst, timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      0
    }
  }

  override fun write(dst: ByteBuffer, timeoutMs: Long): Int = runBlocking {
    try {
      channel.write(dst, timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      0
    }
  }

  override fun isOpen(): Boolean = !closed

  override fun close() {
    closed = true
    channel.close()
  }
}

/**
 * Adapts a ddmlib [SimpleConnectedSocket] to a [DeployerConnectedSocket].
 *
 * This adapter is required for the legacy [IDevice] execution path in [DeviceHolder]. It can be removed once deployer is fully migrated
 * away from ddmlib.
 */
private class DdmlibSocketAdapter(private val socket: SimpleConnectedSocket) : DeployerConnectedSocket {
  override fun read(dst: ByteBuffer, timeoutMs: Long): Int = socket.read(dst, timeoutMs)

  override fun write(dst: ByteBuffer, timeoutMs: Long): Int = socket.write(dst, timeoutMs)

  override fun isOpen(): Boolean = socket.isOpen

  override fun close() = socket.close()
}
