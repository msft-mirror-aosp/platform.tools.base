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

import com.android.adblib.AdbServerConfiguration
import com.android.adblib.AdbServerController
import com.android.adblib.AdbSession
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.InvalidParameterException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.Path

class AdbLibAndroidDebugBridge(
    private val session: AdbSession,
    private val adbServerController: AdbServerController,
    private val adbServerConfiguration: MutableStateFlow<AdbServerConfiguration>
) : AndroidDebugBridgeBase() {

    var iDeviceManager: IDeviceManager? = null

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
    @Synchronized
    override fun createBridge(timeout: Long, unit: TimeUnit): AndroidDebugBridge? {
        var localThis: AndroidDebugBridge
        if (sThis != null) {
            return sThis
        }
        try {
            localThis = AndroidDebugBridge()
            if (!start(localThis, timeout, unit)) {
                // We return without notifying listeners, since there were no changes
                return null
            }
        } catch (_: InvalidParameterException) {
            // We return without notifying listeners, since there were no changes
            return null
        }

        // Success, store static instance
        sThis = localThis

        // Notify the listeners of the change (outside of the lock to decrease the likelihood
        // of deadlocks)
        for (listener in sBridgeListeners) {
            // we attempt to catch any exception so that a bad listener doesn't kill our thread
            try {
                listener.bridgeChanged(localThis)
            } catch (t: Throwable) {
                Log.e(ADB, t)
            }
        }

        return localThis
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
    @Synchronized
    override fun createBridge(
        osLocation: String,
        forceNewBridge: Boolean,
        timeout: Long,
        unit: TimeUnit
    ): AndroidDebugBridge? {
        var localThis: AndroidDebugBridge?
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

        try {
            localThis = AndroidDebugBridge()
            initOsLocationAndCheckVersion(osLocation)
            if (!start(localThis, rem.remainingNanos, TimeUnit.NANOSECONDS)) {
                // Note: Don't return here, as we want to notify listeners
                localThis = null
            }
        } catch (_: InvalidParameterException) {
            // Note: Don't return here, as we want to notify listeners
            localThis = null
        }

        // Success, store static instance
        sThis = localThis

        // Notify the listeners of the change (outside of the lock to decrease the likelihood
        // of deadlocks)
        for (listener in sBridgeListeners) {
            // we attempt to catch any exception so that a bad listener doesn't kill our thread
            try {
                listener.bridgeChanged(localThis)
            } catch (t: Throwable) {
                Log.e(ADB, t)
            }
        }

        return localThis
    }

    /**
     * Kills the debug bridge, and the adb host server.
     *
     * @return `true` if success within the specified timeout
     */
    private fun stop(timeout: Long, unit: TimeUnit): Boolean {
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
        localThis: AndroidDebugBridge,
        timeout: Long,
        unit: TimeUnit
    ): Boolean {
        // Skip server start check if using user managed ADB server

        updateAdbServerConfiguration()

        // TODO: these checks are duplicated inside startAdb, so they could be removed
        //  here once figure out what to do with mVersionCheck
        if (!sUserManagedAdbMode) {
            // If we are configured correctly, check if we need to start ADB
            if (mAdbOsLocation != null && sAdbServerPort != 0) {
                // If we don't have a valid ADB version (or if we have not checked successfully), we
                // can't start
                if (!mVersionCheck) {
                    return false
                }
                // Try to start adb
                // TODO: handle exceptions thrown from `start` and return a correct value
                if (!startAdb(timeout, unit)) {
                    return false
                }
            }
        }

        mStarted = true

        // Start the underlying services.
        startMonitoringServices(localThis)

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
                adbServerController.start()
                true
            } ?: run {
                false
            }
        }
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

    private fun startMonitoringServices(localThis: AndroidDebugBridge) {
        iDeviceManager =
            AdbLibIDeviceManagerFactory(session).createIDeviceManager(
                localThis,
                IDeviceManagerUtils.createIDeviceManagerListener()
            )
    }

    private fun killMonitoringServices() {
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
                // TODO: handle exceptions thrown from `stop` and return a correct value
                adbServerController.stop()
                true
            } ?: run {
                false
            }
        }
    }

    @Synchronized
    override fun terminate() {
        if (sThis != null) {
            killMonitoringServices()
        }

        sInitialized = false
        sThis = null
        sLastKnownGoodAddress = null
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
    @Synchronized
    override fun disconnectBridge(timeout: Long, unit: TimeUnit): Boolean {
        if (sThis != null) {
            if (!stop(timeout, unit)) {
                // We could not stop ADB. Assume we are still running.
                return false
            }
            // Success, store our local instance
            sThis = null
        }

        // Notify the listeners of the change (outside of the lock to decrease the likelihood
        // of deadlocks)
        for (listener in sBridgeListeners) {
            // we attempt to catch any exception so that a bad listener doesn't kill our thread
            try {
                listener.bridgeChanged(null)
            } catch (t: Throwable) {
                Log.e(ADB, t)
            }
        }

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
        for (listener in sBridgeListeners) {
            // we attempt to catch any exception so that a bad listener doesn't kill our thread
            try {
                listener.restartInitiated()
            } catch (t: Throwable) {
                Log.e(ADB, t)
            }
        }

        var isSuccessful: Boolean
        synchronized(this) {
            isSuccessful = stopAdb(rem.remainingNanos, TimeUnit.NANOSECONDS)
            if (!isSuccessful) {
                Log.w(ADB, "Error stopping ADB without specified timeout")
            }

            if (isSuccessful) {
                // TODO: handle exceptions thrown from `start` and return a correct value
                isSuccessful = startAdb(rem.remainingNanos, TimeUnit.NANOSECONDS)
            }
            if (isSuccessful && iDeviceManager == null) {
                checkNotNull(sThis)
                startMonitoringServices(sThis)
            }
        }

        // Notify the listeners of the change (outside of the lock to decrease the likelihood
        // of deadlocks)
        for (listener in sBridgeListeners) {
            // we attempt to catch any exception so that a bad listener doesn't kill our thread
            try {
                listener.restartCompleted(isSuccessful)
            } catch (t: Throwable) {
                Log.e(ADB, t)
            }
        }

        return isSuccessful
    }
}
