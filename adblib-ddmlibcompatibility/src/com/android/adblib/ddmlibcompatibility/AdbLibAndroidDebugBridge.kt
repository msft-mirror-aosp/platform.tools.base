/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.adblib.ddmlibcompatibility

import com.android.SdkConstants
import com.android.adblib.AdbServerConfiguration
import com.android.adblib.AdbServerController
import com.android.adblib.AdbSession
import com.android.adblib.adbLogger
import com.android.adblib.tools.debugging.rethrowCancellation
import com.android.ddmlib.AdbDevice
import com.android.ddmlib.AdbVersion
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.AndroidDebugBridge.MIN_ADB_VERSION
import com.android.ddmlib.AndroidDebugBridgeBase
import com.android.ddmlib.IDevice
import com.android.ddmlib.Log
import com.android.ddmlib.TimeoutRemainder
import com.android.ddmlib.idevicemanager.IDeviceManager
import com.android.ddmlib.idevicemanager.IDeviceManagerUtils
import com.google.common.base.Throwables
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.asListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.InvalidParameterException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.Path

class AdbLibAndroidDebugBridge(
    private val session: AdbSession,
    private val adbServerController: AdbServerController,
    private val adbServerConfiguration: MutableStateFlow<AdbServerConfiguration>
) : AndroidDebugBridgeBase() {

    private val logger = adbLogger(session)

    var iDeviceManager: IDeviceManager? = null

    val lock = ReentrantLock()

    /**
     * Creates a [AndroidDebugBridge] that is not linked to any particular executable.
     *
     *
     * This bridge will expect adb to be running. It will not be able to start/stop/restart adb.
     *
     *
     * If a bridge has already been started, it is directly returned with no changes (similar to
     * calling [.getBridge]).
     *
     * @return a connected bridge, or null if there were errors while creating or connecting to the
     * bridge
     */
    override fun createBridge(timeout: Long, unit: TimeUnit): AndroidDebugBridge? {
        // TODO: Rewrite this code to remove non-local returns
        val newBridgeInstance = withLock {
            if (sThis != null) {
                return sThis
            }
            var newBridgeInstance: AndroidDebugBridge
            try {
                newBridgeInstance = AndroidDebugBridge()
                if (!start(newBridgeInstance, timeout, unit)) {
                    // We return without notifying listeners, since there were no changes
                    return null
                }
            } catch (_: InvalidParameterException) {
                // We return without notifying listeners, since there were no changes
                return null
            }

            // Success, store static instance
            sThis = newBridgeInstance
            newBridgeInstance
        }

        // Notify the listeners of the change (outside of the lock to decrease the likelihood
        // of deadlocks)
        adbChangeEvents.notifyBridgeChanged(newBridgeInstance)

        return newBridgeInstance
    }

    /**
     * Creates a new debug bridge from the location of the command line tool.
     *
     *
     * Any existing server will be disconnected, unless the location is the same and `
     * forceNewBridge` is set to false.
     *
     * @param osLocation the location of the command line tool 'adb'
     * @param forceNewBridge force creation of a new bridge even if one with the same location
     * already exists.
     * @param timeout the maximum time to wait
     * @param unit the time unit of the `timeout` argument
     * @return a connected bridge, or null if there were errors while creating or connecting to the
     * bridge
     */
    override fun createBridge(
        osLocation: String,
        forceNewBridge: Boolean,
        timeout: Long,
        unit: TimeUnit
    ): AndroidDebugBridge? {
        // TODO: Rewrite this code to remove non-local returns
        val newBridgeInstance = withLock {
            val rem = TimeoutRemainder(timeout, unit)
            if (!sUnitTestMode) {
                if (sThis != null) {
                    if (mAdbOsLocation != null && mAdbOsLocation.equals(osLocation)
                        && !forceNewBridge
                    ) {
                        // We return without notifying listeners, since there were no changes
                        return sThis
                    } else {
                        // stop the current server
                        if (!stop(rem.remainingNanos, TimeUnit.NANOSECONDS)) {
                            // We return without notifying listeners, since there were no changes
                            return null
                        }
                    }

                    // We are successfully stopped. We need to notify listeners in all code paths
                    // past this point.
                    sThis = null
                }
            }

            var newBridgeInstance: AndroidDebugBridge?
            try {
                newBridgeInstance = AndroidDebugBridge()
                initOsLocationAndCheckVersion(osLocation)
                if (!start(newBridgeInstance, rem.remainingNanos, TimeUnit.NANOSECONDS)) {
                    // Note: Don't return here, as we want to notify listeners
                    newBridgeInstance = null
                }
            } catch (_: InvalidParameterException) {
                // Note: Don't return here, as we want to notify listeners
                newBridgeInstance = null
            }

            // Success, store static instance
            sThis = newBridgeInstance
            newBridgeInstance
        }

        // Notify the listeners of the change (outside of the lock to decrease the likelihood
        // of deadlocks)
        adbChangeEvents.notifyBridgeChanged(newBridgeInstance)

        return newBridgeInstance
    }

    /**
     * Kills the debug bridge, and the adb host server.
     *
     * @return `true` if success within the specified timeout
     */
    private fun stop(timeout: Long, unit: TimeUnit): Boolean {
        assert(lock.isHeldByCurrentThread)

        // if we haven't started we return true (i.e. success)
        if (!mStarted) {
            return true
        }

        val rem = TimeoutRemainder(timeout, unit)
        killMonitoringServices()

        // Don't stop ADB when using user managed ADB server.
        if (sUserManagedAdbMode) {
            Log.i(ADB, "User managed ADB mode: Not stopping ADB server")
        } else if (!stopAdb(rem.remainingNanos, TimeUnit.NANOSECONDS)) {
            return false
        }

        mStarted = false
        return true
    }

    /**
     * Starts the debug bridge.
     *
     * @return true if success.
     */
    private fun start(
        bridgeInstance: AndroidDebugBridge,
        timeout: Long,
        unit: TimeUnit
    ): Boolean {
        assert(lock.isHeldByCurrentThread)

        updateAdbServerConfiguration()

        // TODO: these checks are duplicated inside startAdb, so they could be removed
        //  here once figure out what to do with mVersionCheck
        // Skip server start check if using user managed ADB server
        if (!sUserManagedAdbMode) {
            // If we are configured correctly, check if we need to start ADB
            if (mAdbOsLocation != null && sAdbServerPort != 0) {
                // If we don't have a valid ADB version (or if we have not checked successfully), we
                // can't start
                if (!mVersionCheck) {
                    return false
                }
                // Try to start adb
                if (!startAdb(timeout, unit)) {
                    return false
                }
            }
        }

        mStarted = true

        // Start the underlying services.
        startMonitoringServices(bridgeInstance)

        return true
    }

    fun updateAdbServerConfiguration() {
        adbServerConfiguration.update {
            AdbServerConfiguration(
                mAdbOsLocation?.let { Path(mAdbOsLocation) },
                sAdbServerPort,
                sUserManagedAdbMode,
                sUnitTestMode,
                sAdbEnvVars
            )
        }
    }

    override fun startAdb(timeout: Long, unit: TimeUnit): Boolean {
        return runBlocking {
            withTimeoutOrNull(unit.toMillis(timeout)) {
                try {
                    adbServerController.start()
                    true
                } catch (t: Throwable) {
                    t.rethrowCancellation()
                    logger.warn(t, "Failed to start adb server")
                    false
                }
            } ?: run {
                false
            }
        }
    }

    override fun getAdbVersion(adbFile: File): ListenableFuture<AdbVersion> {
        return getAdbVersion(adbFile.toPath())
    }

    internal fun getAdbVersion(adbPath: Path): ListenableFuture<AdbVersion> {
        return session.scope.async {
            val processResult =
                session.host.processRunner.runProcess(
                    adbPath,
                    listOf("version"),
                    envVars = emptyMap()
                )

            processResult.stdout.forEach { line ->
                val version = AdbVersion.parseFrom(line)
                if (version != AdbVersion.UNKNOWN) {
                    return@async version
                }
            }

            val errorMessage = StringBuilder("Unable to detect adb version")
            val exitCode = processResult.exitCode
            if (exitCode != 0) {
                errorMessage.append(", exit value: 0x" + Integer.toHexString(exitCode))
                // Display special message if it is the STATUS_DLL_NOT_FOUND code, and
                // ignore adb output since it's empty anyway
                if (exitCode == STATUS_DLL_NOT_FOUND
                    && SdkConstants.currentPlatform()
                    == SdkConstants.PLATFORM_WINDOWS
                ) {
                    errorMessage.append(
                        ". ADB depends on the Windows Universal C Runtime, which is"
                                + " usually installed by default via Windows Update. You"
                                + " may need to manually fetch and install the runtime"
                                + " package here:"
                                + " https://support.microsoft.com/en-ca/help/2999226/update-for-universal-c-runtime-in-windows"
                    )
                    throw RuntimeException(errorMessage.toString())
                }
            }
            if (processResult.stdout.isNotEmpty()) {
                errorMessage.append(", adb stdout: ${processResult.stdout.joinToString("\n")}")
            }
            if (processResult.stderr.isNotEmpty()) {
                errorMessage.append(", adb stderr: ${processResult.stderr.joinToString("\n")}")
            }
            throw RuntimeException(errorMessage.toString())
        }.asListenableFuture()
    }

    override fun getRawDeviceList(): ListenableFuture<List<AdbDevice>> {
        val config = adbServerConfiguration.value
        val adbPath = config.adbPath
        val envVars = config.envVars

        if (adbPath == null) {
            return Futures.immediateFuture(emptyList())
        }

        return session.scope.async {
            val processResult =
                session.host.processRunner.runProcess(
                    adbPath,
                    listOf("devices", "-l"),
                    envVars
                )
            // The first line of the output is a header, and not a part of the device list. Skip it.
            val devices = processResult.stdout.drop(1).mapNotNull { AdbDevice.parseAdbLine(it) }
            devices
        }.asListenableFuture()
    }

    private fun initOsLocationAndCheckVersion(osLocation: String?) {
        if (osLocation == null || osLocation.isEmpty()) {
            throw InvalidParameterException()
        }
        mAdbOsLocation = osLocation

        try {
            mAdbVersion = fetchAdbVersion()
            mVersionCheck = checkAdbVersion(mAdbVersion)
        } catch (e: IOException) {
            throw IllegalArgumentException(e)
        }
    }

    /**
     * Queries adb for its version number.
     *
     * @return a [AdbVersion] if adb responds correctly, or null otherwise
     */
    @Throws(IOException::class)
    private fun fetchAdbVersion(): AdbVersion? {
        if (mAdbOsLocation == null) {
            return null
        }

        val adb = File(mAdbOsLocation)
        val future = getAdbVersion(adb)
        try {
            return future[DEFAULT_START_ADB_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS]
        } catch (_: InterruptedException) {
            return null
        } catch (_: TimeoutException) {
            val msg = "Unable to obtain result of 'adb version'"
            Log.logAndDisplay(Log.LogLevel.ERROR, ADB, msg)
            return null
        } catch (e: ExecutionException) {
            Log.logAndDisplay(Log.LogLevel.ERROR, ADB, e.cause!!.message)
            Throwables.propagateIfInstanceOf(e.cause, IOException::class.java)
            return null
        }
    }

    /**
     * Checks if given AdbVersion is at least [AndroidDebugBridge.MIN_ADB_VERSION].
     *
     * @return true if given version is at least minimum, false otherwise
     */
    private fun checkAdbVersion(adbVersion: AdbVersion?): Boolean {
        // default is bad check
        var passes = false

        if (adbVersion == null) {
            Log.logAndDisplay(Log.LogLevel.ERROR, ADB, "Could not determine adb version.")
        } else if (adbVersion > MIN_ADB_VERSION) {
            passes = true
        } else {
            val message = String.format(
                "Required minimum version of adb: %1\$s." + "Current version is %2\$s",
                MIN_ADB_VERSION, adbVersion
            )
            Log.logAndDisplay(Log.LogLevel.ERROR, ADB, message)
        }
        return passes
    }

    override fun getSocketAddress(): InetSocketAddress {
        if (!sUnitTestMode) {
            // Use synchronized access to ensure we only ever open one connection to ADB when we
            // need to check which local address to use.
            synchronized(sLastKnownGoodAddressLock) {
                if (sLastKnownGoodAddress != null) {
                    return sLastKnownGoodAddress
                }
                try {
                    // TODO: convert to using adblib
                    openConnection().use { adbChannel ->
                        // SocketAddress from adbChannel is created by openConnection and should always
                        // be an InetSocketAddress.
                        sLastKnownGoodAddress = adbChannel.remoteAddress as InetSocketAddress
                        return sLastKnownGoodAddress
                    }
                } catch (_: IOException) {
                    // Ignore the failure and fallback to old implementation.
                }
            }
        }
        return InetSocketAddress(InetAddress.getLoopbackAddress(), sAdbServerPort)
    }

    private fun startMonitoringServices(bridgeInstance: AndroidDebugBridge) {
        assert(lock.isHeldByCurrentThread)

        iDeviceManager =
            AdbLibIDeviceManagerFactory(session).createIDeviceManager(
                bridgeInstance,
                IDeviceManagerUtils.createIDeviceManagerListener()
            )
    }

    private fun killMonitoringServices() {
        assert(lock.isHeldByCurrentThread)

        iDeviceManager?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e(ADB, "Could not close IDeviceManager:")
                Log.e(ADB, e)
            }
        }
        iDeviceManager = null
    }

    override fun stopAdb(timeout: Long, unit: TimeUnit): Boolean {

        return runBlocking {
            withTimeoutOrNull(unit.toMillis(timeout)) {
                try {
                    adbServerController.stop()
                    true
                } catch (t: Throwable) {
                    t.rethrowCancellation()
                    logger.warn(t, "Failed to stop adb server")
                    false
                }
            } ?: run {
                false
            }
        }
    }

    override fun terminate() {
        withLock {
            if (sThis != null) {
                killMonitoringServices()
            }

            sInitialized = false
            sThis = null
            sLastKnownGoodAddress = null
        }
    }

    /**
     * Disconnects the current debug bridge, and destroy the object. A new object will have to be
     * created with [.createBridge].
     *
     *
     * This also stops the current adb host server.
     *
     * @return `true` if the method succeeds within the specified timeout.
     */
    override fun disconnectBridge(timeout: Long, unit: TimeUnit): Boolean {
        withLock {
            if (sThis != null) {
                if (!stop(timeout, unit)) {
                    // We could not stop ADB. Assume we are still running.
                    return false
                }
                // Success, store our local instance
                sThis = null
            }
        }

        // Notify the listeners of the change (outside of the lock to decrease the likelihood
        // of deadlocks)
        adbChangeEvents.notifyBridgeChanged(null)

        return true
    }

    override fun hasInitialDeviceList(): Boolean {
        return iDeviceManager?.hasInitialDeviceList() == true
    }

    override fun getDevices(): Array<IDevice> = runBlocking {
        iDeviceManager?.devices?.toTypedArray() ?: emptyArray()
    }

    override fun isConnected(): Boolean {
        return adbServerController.isStarted
    }

    /**
     * Restarts adb, but not the services around it.
     *
     * @return true if success.
     */
    @Deprecated("This method may hang if ADB is not responding. Use #restart(long, TimeUnit) instead.")
    override fun restart(): Boolean {
        return restart(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
    }

    /**
     * Restarts adb, but not the services around it.
     *
     * @return true if success.
     */
    override fun restart(timeout: Long, unit: TimeUnit): Boolean {
        if (sUserManagedAdbMode) {
            Log.e(ADB, "Cannot restart adb when using user managed ADB server.")
            return false
        }

        if (mAdbOsLocation == null) {
            Log.e(
                ADB,
                "Cannot restart adb when AndroidDebugBridge is created without the location of"
                        + " adb."
            )
            return false
        }

        if (sAdbServerPort == 0) {
            Log.e(
                ADB,
                "ADB server port for restarting AndroidDebugBridge is not set."
            )
            return false
        }

        if (!mVersionCheck) {
            Log.logAndDisplay(
                Log.LogLevel.ERROR,
                ADB,
                "Attempting to restart adb, but version check failed!"
            )
            return false
        }

        val rem = TimeoutRemainder(timeout, unit)
        // Notify the listeners of the change (outside of the lock to decrease the likelihood
        // of deadlocks)
        adbChangeEvents.notifyBridgeRestartInitiated()

        val isSuccessful = withLock {
            var success = stopAdb(rem.remainingNanos, TimeUnit.NANOSECONDS)
            if (!success) {
                Log.w(ADB, "Error stopping ADB without specified timeout")
            }

            if (success) {
                // TODO: handle exceptions thrown from `start` and return a correct value
                success = startAdb(rem.remainingNanos, TimeUnit.NANOSECONDS)
            }
            if (success && iDeviceManager == null) {
                checkNotNull(sThis)
                startMonitoringServices(sThis)
            }
            success
        }

        // Notify the listeners of the change (outside of the lock to decrease the likelihood
        // of deadlocks)
        adbChangeEvents.notifyBridgeRestartCompleted(isSuccessful)

        return isSuccessful
    }

    override fun getVirtualDeviceId(
        service: ListeningExecutorService,
        adb: File,
        device: IDevice
    ): ListenableFuture<String?>? {
        unsupportedMethod()
    }

    private inline fun <R> withLock(block: () -> R): R {
        return try {
            lock.lock()
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun unsupportedMethod(): Nothing {
        throw UnsupportedOperationException("This method is not used in Android Studio")
    }

    companion object {
        // ADB exit value when no Universal C Runtime on Windows
        const val STATUS_DLL_NOT_FOUND: Int = -0x3FFFFECB // Signed version of 0xc0000135
    }
}
