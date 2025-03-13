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
import com.android.adblib.INFINITE_DURATION
import com.android.adblib.adbLogger
import com.android.adblib.isTrackerConnecting
import com.android.adblib.isTrackerDisconnected
import com.android.adblib.trackDevices
import com.android.adblib.withErrorTimeout
import com.android.ddmlib.AdbDelegateUsageTracker
import com.android.ddmlib.AdbDevice
import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AdbVersion
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.AndroidDebugBridge.IClientChangeListener
import com.android.ddmlib.AndroidDebugBridge.IDebugBridgeChangeListener
import com.android.ddmlib.AndroidDebugBridge.IDeviceChangeListener
import com.android.ddmlib.AndroidDebugBridge.MIN_ADB_VERSION
import com.android.ddmlib.AndroidDebugBridgeBase
import com.android.ddmlib.Client
import com.android.ddmlib.DdmPreferences
import com.android.ddmlib.IDevice
import com.android.ddmlib.IDeviceUsageTracker
import com.android.ddmlib.Log
import com.android.ddmlib.TimeoutRemainder
import com.android.ddmlib.clientmanager.ClientManager
import com.android.ddmlib.idevicemanager.IDeviceManager
import com.android.ddmlib.idevicemanager.IDeviceManagerFactory
import com.android.ddmlib.idevicemanager.IDeviceManagerUtils
import com.google.common.base.Preconditions
import com.google.common.base.Throwables
import com.google.common.collect.ImmutableMap
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import kotlinx.coroutines.CoroutineScope
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
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.security.InvalidParameterException
import java.time.Duration
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

    private val lock = ReentrantLock()

    /**
     * We need this because the AndroidDebugBridge class is mostly static, coming from ddmlib,
     * and an instance is required for some APIs (e.g. `getDevices`, `isConnected`, `restart`).
     */
    @Volatile
    private var currentAndroidDebugBridge: AndroidDebugBridge? = null

    /**
     * Set to `true` after one of the `init` methods is called. Is reset to `false` when
     * AndroidDebugBridge is `terminate`-ed.
     */
    @Volatile
    private var initialized: Boolean = false

    /**
     * We re-use the `IDeviceManager` implementation from this ddmlib compatibility module, and
     * we create a new instance every time `start` or `restart` is called.
     */
    private var adblibCompatDeviceManager: IDeviceManager? = null

    private var passedAdbServerVersionCheck: Boolean = false

    /** Port where adb server will be started  */
    private var sAdbServerPort: Int? = null

    /** Full path to adb.  */
    private var mAdbOsLocation: String? = null

    // TODO: Use `adbServerController.isStarted` instead. Note that currently `AdbServerController`
    //  is not used in UserManagedAdbMode and so its `isStarted` value is not updated.
    private var started: Boolean = false

    private var adbDelegateUsageTracker: AdbDelegateUsageTracker? = null

    // Only set when in unit testing mode. This is a hack until we move to devicelib.
    // http://b.android.com/221925
    private var isUnitTestMode: Boolean = false

    /** Don't automatically manage ADB server.  */
    private var isUserManagedAdbMode: Boolean = false

    private var isClientSupport: Boolean = false

    private var clientManager: ClientManager? = null

    private var iDeviceManagerFactory: IDeviceManagerFactory? = null

    private var iDeviceUsageTracker: IDeviceUsageTracker? = null

    private var adbEnvVars: Map<String, String> = emptyMap()

    /**
     * Initialized the library only if needed; deprecated for non-test usages.
     */
    @Deprecated("Used only in tests")
    @Synchronized
    override fun initIfNeeded(clientSupport: Boolean) {
        logUsage(AdbDelegateUsageTracker.Method.INIT_IF_NEEDED) {
            if (!initialized) {
                init(clientSupport)
            }
        }
    }

    @Synchronized
    override fun init(clientSupport: Boolean) {
        logUsage(AdbDelegateUsageTracker.Method.INIT_1) {
            init(clientSupport, false, ImmutableMap.of())
        }
    }

    @Synchronized
    override fun init(
        clientSupport: Boolean, useLibusb: Boolean, env: Map<String?, String?>
    ) {
        logUsage(AdbDelegateUsageTracker.Method.INIT_2) {
            init(
                AdbInitOptions.builder()
                    .withEnv(env)
                    .setClientSupportEnabled(clientSupport)
                    .withEnv("ADB_LIBUSB", if (useLibusb) "1" else "0")
                    .build()
            )
        }
    }

    @Synchronized
    override fun init(options: AdbInitOptions) {
        logUsage(AdbDelegateUsageTracker.Method.INIT_3) {
            Preconditions.checkState(
                !initialized, "AndroidDebugBridge.init() has already been called."
            )
            initialized = true
            iDeviceManagerFactory = options.iDeviceManagerFactory
            iDeviceUsageTracker = options.iDeviceUsageTracker
            adbDelegateUsageTracker = options.adbDelegateUsageTracker
            isClientSupport = options.clientSupport
            clientManager = options.clientManager
            if (clientManager != null) {
                // A custom client manager is not compatible with "client support"
                isClientSupport = false
            }
            if (iDeviceManagerFactory != null) {
                // A custom "IDevice" manager is not compatible with a "Client" manager
                clientManager = null
                isClientSupport = false
            }
            adbEnvVars = options.adbEnvVars
            isUserManagedAdbMode = options.userManagedAdbMode
            DdmPreferences.enableJdwpProxyService(options.useJdwpProxyService)
            DdmPreferences.enableDdmlibCommandService(options.useDdmlibCommandService)
            DdmPreferences.setsJdwpMaxPacketSize(options.maxJdwpPacketSize)

            // Determine port and instantiate socket address.
            initAdbPort(options.userManagedAdbPort)
        }
    }

    override fun enableFakeAdbServerMode(port: Int) {
        logUsage(AdbDelegateUsageTracker.Method.ENABLE_FAKE_ADB_SERVER_MODE) {
            Preconditions.checkState(
                !initialized,
                "AndroidDebugBridge.init() has already been called or "
                        + "terminate() has not been called yet."
            )
            isUnitTestMode = true
            sAdbServerPort = port
        }
    }

    override fun disableFakeAdbServerMode() {
        logUsage(AdbDelegateUsageTracker.Method.DISABLE_FAKE_ADB_SERVER_MODE) {
            Preconditions.checkState(
                !initialized,
                "AndroidDebugBridge.init() has already been called or "
                        + "terminate() has not been called yet."
            )
            isUnitTestMode = false
            sAdbServerPort = null
        }
    }

    override fun getClientSupport(): Boolean {
        return logUsage(AdbDelegateUsageTracker.Method.GET_CLIENT_SUPPORT) {
            isClientSupport
        }
    }

    override fun getClientManager(): ClientManager? {
        return logUsage(AdbDelegateUsageTracker.Method.GET_CLIENT_MANAGER) {
            clientManager
        }
    }

    override fun createBridge(): AndroidDebugBridge? {
        return logUsage(AdbDelegateUsageTracker.Method.CREATE_BRIDGE_1) {
            createBridge(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        }
    }

    @Deprecated("This method may hang if ADB is not responding")
    override fun createBridge(osLocation: String, forceNewBridge: Boolean): AndroidDebugBridge? {
        return logUsage(AdbDelegateUsageTracker.Method.CREATE_BRIDGE_3) {
            createBridge(osLocation, forceNewBridge, Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        }
    }

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
        return logUsage(AdbDelegateUsageTracker.Method.CREATE_BRIDGE_2) {
            // TODO: Rewrite this code to remove non-local returns
            val newBridgeInstance = withLock {
                if (currentAndroidDebugBridge != null) {
                    return@logUsage currentAndroidDebugBridge
                }
                var newBridgeInstance: AndroidDebugBridge
                try {
                    newBridgeInstance = AndroidDebugBridge()
                    if (!start(newBridgeInstance, timeout, unit)) {
                        // We return without notifying listeners, since there were no changes
                        return@logUsage null
                    }
                } catch (_: InvalidParameterException) {
                    // We return without notifying listeners, since there were no changes
                    return@logUsage null
                }

                // Success, store static instance
                currentAndroidDebugBridge = newBridgeInstance
                newBridgeInstance
            }

            // Notify the listeners of the change (outside of the lock to decrease the likelihood
            // of deadlocks)
            adbChangeEvents.notifyBridgeChanged(newBridgeInstance)

            return@logUsage newBridgeInstance
        }
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
        return logUsage(AdbDelegateUsageTracker.Method.CREATE_BRIDGE_4) {
            // TODO: Rewrite this code to remove non-local returns
            val newBridgeInstance = withLock {
                val rem = TimeoutRemainder(timeout, unit)
                if (!isUnitTestMode) {
                    if (currentAndroidDebugBridge != null) {
                        if (mAdbOsLocation != null && mAdbOsLocation.equals(osLocation)
                            && !forceNewBridge
                        ) {
                            // We return without notifying listeners, since there were no changes
                            return@logUsage currentAndroidDebugBridge
                        } else {
                            // stop the current server
                            if (!stop(rem.remainingNanos, TimeUnit.NANOSECONDS)) {
                                // We return without notifying listeners, since there were no changes
                                return@logUsage null
                            }
                        }

                        // We are successfully stopped. We need to notify listeners in all code paths
                        // past this point.
                        currentAndroidDebugBridge = null
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
                currentAndroidDebugBridge = newBridgeInstance
                newBridgeInstance
            }

            // Notify the listeners of the change (outside of the lock to decrease the likelihood
            // of deadlocks)
            adbChangeEvents.notifyBridgeChanged(newBridgeInstance)

            return@logUsage newBridgeInstance
        }
    }

    /**
     * Kills the debug bridge, and the adb host server.
     *
     * @return `true` if success within the specified timeout
     */
    private fun stop(timeout: Long, unit: TimeUnit): Boolean {
        assert(lock.isHeldByCurrentThread)

        // if we haven't started we return true (i.e. success)
        if (!started) {
            return true
        }

        val rem = TimeoutRemainder(timeout, unit)
        stopIDeviceManager()

        if (!stopAdb(rem.remainingNanos, TimeUnit.NANOSECONDS)) {
            return false
        }

        started = false
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
        // If this is not a user managed ADB server, perform version checks
        if (!isUserManagedAdbMode) {
            // If we are configured correctly, check if we need to start ADB
            if (mAdbOsLocation != null && sAdbServerPort != null) {
                // If we don't have a valid ADB version (or if we have not checked successfully), we
                // can't start
                if (!passedAdbServerVersionCheck) {
                    return false
                }
            }
        }

        // Try to start adb
        if (!startAdb(timeout, unit)) {
            return false
        }

        started = true

        // Start the underlying services.
        startIDeviceManager(bridgeInstance)

        return true
    }

    fun updateAdbServerConfiguration() {
        adbServerConfiguration.update {
            AdbServerConfiguration(
                mAdbOsLocation?.let { Path(it) },
                sAdbServerPort,
                isUserManagedAdbMode,
                isUnitTestMode,
                adbEnvVars
            )
        }
    }

    override fun startAdb(timeout: Long, unit: TimeUnit): Boolean {
        return logUsage(AdbDelegateUsageTracker.Method.START_ADB) {
            runBlocking {
                withTimeoutOrNull(unit.toMillis(timeout)) {
                    try {
                        adbServerController.start()
                        true
                    } catch (t: Throwable) {
                        logger.warn(t, "Failed to start adb server")
                        false
                    }
                } ?: run {
                    false
                }
            }
        }
    }

    /** Returns the current debug bridge. Can be `null` if none were created.  */
    override fun getBridge(): AndroidDebugBridge? {
        return currentAndroidDebugBridge
    }

    /**
     * Disconnects the current debug bridge, and destroy the object. A new object will have to be
     * created with [.createBridge].
     *
     * This also stops the current adb host server.
     */
    @Deprecated(
        """This method may hang if ADB is not responding. Use
      {@link #disconnectBridge(long, TimeUnit)} instead."""
    )
    override fun disconnectBridge() {
        logUsage(AdbDelegateUsageTracker.Method.DISCONNECT_BRIDGE_1) {
            disconnectBridge(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        }
    }

    override fun addDebugBridgeChangeListener(
        listener: IDebugBridgeChangeListener
    ) {
        logUsage(AdbDelegateUsageTracker.Method.ADD_DEBUG_BRIDGE_CHANGE_LISTENER) {
            adbChangeEvents.addDebugBridgeChangeListener(listener)

            val localThis = currentAndroidDebugBridge

            if (localThis != null) {
                // we attempt to catch any exception so that a bad listener doesn't kill our thread
                try {
                    listener.bridgeChanged(localThis)
                } catch (t: Throwable) {
                    Log.e(DDMS, t)
                }
            }
        }
    }

    override fun removeDebugBridgeChangeListener(
        listener: IDebugBridgeChangeListener?
    ) {
        logUsage(AdbDelegateUsageTracker.Method.REMOVE_DEBUG_BRIDGE_CHANGE_LISTENER) {
            adbChangeEvents.removeDebugBridgeChangeListener(listener!!)
        }
    }

    override fun getDebugBridgeChangeListenerCount(): Int {
        return logUsage(AdbDelegateUsageTracker.Method.GET_DEBUG_BRIDGE_CHANGE_LISTENER_COUNT) {
            adbChangeEvents.debugBridgeChangeListenerCount()
        }
    }

    override fun addDeviceChangeListener(
        listener: IDeviceChangeListener
    ) {
        logUsage(AdbDelegateUsageTracker.Method.ADD_DEVICE_CHANGE_LISTENER) {
            adbChangeEvents.addDeviceChangeListener(listener)
        }
    }

    override fun removeDeviceChangeListener(listener: IDeviceChangeListener?) {
        logUsage(AdbDelegateUsageTracker.Method.REMOVE_DEVICE_CHANGE_LISTENER) {
            adbChangeEvents.removeDeviceChangeListener(listener!!)
        }
    }

    override fun getDeviceChangeListenerCount(): Int {
        return logUsage(AdbDelegateUsageTracker.Method.GET_DEVICE_CHANGE_LISTENER_COUNT) {
            adbChangeEvents.deviceChangeListenerCount()
        }
    }

    override fun addClientChangeListener(listener: IClientChangeListener?) {
        logUsage(AdbDelegateUsageTracker.Method.ADD_CLIENT_CHANGE_LISTENER) {
            adbChangeEvents.addClientChangeListener(listener!!)
        }
    }

    override fun removeClientChangeListener(listener: IClientChangeListener?) {
        logUsage(AdbDelegateUsageTracker.Method.REMOVE_CLIENT_CHANGE_LISTENER) {
            adbChangeEvents.removeClientChangeListener(listener!!)
        }
    }

    override fun getiDeviceUsageTracker(): IDeviceUsageTracker? {
        return iDeviceUsageTracker
    }

    override fun deviceConnected(device: IDevice) {
        logUsage(AdbDelegateUsageTracker.Method.DEVICE_CONNECTED) {
            adbChangeEvents.notifyDeviceConnected(device)
        }
    }

    override fun deviceDisconnected(device: IDevice) {
        logUsage(AdbDelegateUsageTracker.Method.DEVICE_DISCONNECTED) {
            adbChangeEvents.notifyDeviceDisconnected(device)
        }
    }

    override fun deviceChanged(device: IDevice, changeMask: Int) {
        logUsage(AdbDelegateUsageTracker.Method.DEVICE_CHANGED) {
            // Notify the listeners
            adbChangeEvents.notifyDeviceChanged(device, changeMask)
        }
    }

    override fun clientChanged(client: Client, changeMask: Int) {
        logUsage(AdbDelegateUsageTracker.Method.CLIENT_CHANGED) {
            // Notify the listeners
            adbChangeEvents.notifyClientChanged(client, changeMask)
        }
    }

    override fun isUserManagedAdbMode(): Boolean {
        return isUserManagedAdbMode
    }

    /** Instantiates sSocketAddr with the address of the host's adb process.  */
    private fun initAdbPort(userManagedAdbPort: Int) {
        // If we're in unit test mode, we already manually set sAdbServerPort.
        if (!isUnitTestMode) {
            sAdbServerPort = if (isUserManagedAdbMode) {
                userManagedAdbPort
            } else {
                getAdbServerPort()
            }
        }
    }

    /**
     * Returns the port where adb server should be launched. This looks at:
     *
     *  1. The system property ANDROID_ADB_SERVER_PORT
     *  1. The environment variable ANDROID_ADB_SERVER_PORT
     *  1. Defaults to [.DEFAULT_ADB_PORT] if neither the system property nor the env var
     * are set.
     *
     * @return The port number where the host's adb should be expected or started.
     */
    private fun getAdbServerPort(): Int {
        // check system property
        val prop = Integer.getInteger(SERVER_PORT_ENV_VAR)
        if (prop != null) {
            try {
                return validateAdbServerPort(prop.toString())
            } catch (e: java.lang.IllegalArgumentException) {
                val msg = String.format(
                    "Invalid value (%1\$s) for ANDROID_ADB_SERVER_PORT system property.",
                    prop
                )
                Log.w(DDMS, msg)
            }
        }

        // when system property is not set or is invalid, parse environment property
        try {
            val env = System.getenv(SERVER_PORT_ENV_VAR)
            if (env != null) {
                return validateAdbServerPort(env)
            }
        } catch (ex: SecurityException) {
            // A security manager has been installed that doesn't allow access to env vars.
            // So an environment variable might have been set, but we can't tell.
            // Let's log a warning and continue with ADB's default port.
            // The issue is that adb would be started (by the forked process having access
            // to the env vars) on the desired port, but within this process, we can't figure out
            // what that port is. However, a security manager not granting access to env vars
            // but allowing to fork is a rare and interesting configuration, so the right
            // thing seems to be to continue using the default port, as forking is likely to
            // fail later on in the scenario of the security manager.
            Log.w(
                DDMS,
                "No access to env variables allowed by current security manager. "
                        + "If you've set ANDROID_ADB_SERVER_PORT: it's being ignored."
            )
        } catch (e: java.lang.IllegalArgumentException) {
            val msg = String.format(
                "Invalid value (%1\$s) for ANDROID_ADB_SERVER_PORT environment variable"
                        + " (%2\$s).",
                prop, e.message
            )
            Log.w(DDMS, msg)
        }

        // use default port if neither are set
        return DEFAULT_ADB_PORT
    }

    /**
     * Returns the integer port value if it is a valid value for adb server port
     *
     * @param adbServerPort adb server port to validate
     * @return `adbServerPort` as a parsed integer
     * @throws IllegalArgumentException when `adbServerPort` is not bigger than 0 or it is not
     * a number at all
     */
    @Throws(java.lang.IllegalArgumentException::class)
    private fun validateAdbServerPort(adbServerPort: String): Int {
        try {
            // C tools (adb, emulator) accept hex and octal port numbers, so need to accept them too
            val port = Integer.decode(adbServerPort)
            require(!(port <= 0 || port >= 65535)) { "Should be > 0 and < 65535" }
            return port
        } catch (e: NumberFormatException) {
            throw java.lang.IllegalArgumentException("Not a valid port number")
        }
    }

    override fun getAdbVersion(adbFile: File): ListenableFuture<AdbVersion> {
        return logUsage(AdbDelegateUsageTracker.Method.GET_ADB_VERSION) {
            getAdbVersion(adbFile.toPath())
        }
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
        return logUsage(AdbDelegateUsageTracker.Method.GET_RAW_DEVICE_LIST) {
            val config = adbServerConfiguration.value
            val adbPath = config.adbPath
            val envVars = config.envVars

            if (adbPath == null) {
                return@logUsage Futures.immediateFuture(emptyList())
            }

            session.scope.async {
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
    }

    private fun initOsLocationAndCheckVersion(osLocation: String?) {
        if (osLocation.isNullOrEmpty()) {
            throw InvalidParameterException()
        }
        mAdbOsLocation = osLocation

        try {
            passedAdbServerVersionCheck = checkAdbVersion(fetchAdbVersion())
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
        val knownRemoteAddress = try {
            runBlockingLegacy {
                if (!isUnitTestMode) {
                    adbServerController.lastKnownRemoteAddress ?: run {
                        // Open a connection to try to force setting the `lastKnownRemoteAddress`, but this
                        // can fail for many reasons (server not started, server not available) so we have
                        // to ignore errors.
                        if (adbServerController.isStarted) {
                            runCatching {
                                adbServerController.channelProvider.createChannel().use {}
                            }
                        }
                        adbServerController.lastKnownRemoteAddress
                    }
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            when (t) {
                // `InterruptedException` can be thrown from `runBlocking`, and we simply ignore it,
                // matching the behavior in `AndroidDebugBridgeImpl`, where
                // `ClosedByInterruptException` is also caught and ignored
                is InterruptedException,
                is TimeoutException -> {
                    logger.warn(t, "Exception while getting a socketAddress")
                }

                else -> {
                    logger.warn(t, "Unexpected exception thrown while getting a socketAddress")
                }
            }
            null
        }

        return knownRemoteAddress ?: InetSocketAddress(
            InetAddress.getLoopbackAddress(),
            sAdbServerPort ?: 0
        )
    }

    private fun startIDeviceManager(bridgeInstance: AndroidDebugBridge) {
        assert(lock.isHeldByCurrentThread)

        adblibCompatDeviceManager =
            AdbLibIDeviceManagerFactory(session).createIDeviceManager(
                bridgeInstance,
                IDeviceManagerUtils.createIDeviceManagerListener()
            )
    }

    private fun stopIDeviceManager() {
        assert(lock.isHeldByCurrentThread)

        adblibCompatDeviceManager?.let {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e(ADB, "Could not close IDeviceManager:")
                Log.e(ADB, e)
            }
        }
        adblibCompatDeviceManager = null
    }

    override fun stopAdb(timeout: Long, unit: TimeUnit): Boolean {

        return runBlocking {
            withTimeoutOrNull(unit.toMillis(timeout)) {
                try {
                    adbServerController.stop()
                    true
                } catch (t: Throwable) {
                    logger.warn(t, "Failed to stop adb server")
                    false
                }
            } ?: run {
                false
            }
        }
    }

    override fun terminate() {
        logUsage(AdbDelegateUsageTracker.Method.TERMINATE) {
            withLock {
                if (currentAndroidDebugBridge != null) {
                    stopIDeviceManager()
                }

                initialized = false
                currentAndroidDebugBridge = null
            }
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
        return logUsage(AdbDelegateUsageTracker.Method.DISCONNECT_BRIDGE_2) {
            withLock {
                if (currentAndroidDebugBridge != null) {
                    if (!stop(timeout, unit)) {
                        // We could not stop ADB. Assume we are still running.
                        return@logUsage false
                    }
                    // Success, store our local instance
                    currentAndroidDebugBridge = null
                }
            }

            // Notify the listeners of the change (outside of the lock to decrease the likelihood
            // of deadlocks)
            adbChangeEvents.notifyBridgeChanged(null)

            true
        }
    }

    override fun hasInitialDeviceList(): Boolean {
        return logUsage(AdbDelegateUsageTracker.Method.HAS_INITIAL_DEVICE_LIST) {
            adblibCompatDeviceManager?.hasInitialDeviceList() == true
        }
    }

    override fun getDevices(): Array<IDevice> {
        return logUsage(AdbDelegateUsageTracker.Method.GET_DEVICES) {
            runBlocking {
                adblibCompatDeviceManager?.devices?.toTypedArray() ?: emptyArray()
            }
        }
    }

    override fun isConnected(): Boolean {
        val trackState = session.trackDevices().value
        return !trackState.isTrackerDisconnected && !trackState.isTrackerConnecting
    }

    /**
     * Restarts adb, but not the services around it.
     *
     * @return true if success.
     */
    @Deprecated("This method may hang if ADB is not responding. Use #restart(long, TimeUnit) instead.")
    override fun restart(): Boolean {
        return logUsage(AdbDelegateUsageTracker.Method.RESTART_1) {
            restart(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Restarts adb, but not the services around it.
     *
     * @return true if success.
     */
    override fun restart(timeout: Long, unit: TimeUnit): Boolean {
        return logUsage(AdbDelegateUsageTracker.Method.RESTART_2) {
            if (isUserManagedAdbMode) {
                Log.e(ADB, "Cannot restart adb when using user managed ADB server.")
                return@logUsage false
            }

            if (mAdbOsLocation == null) {
                Log.e(
                    ADB,
                    "Cannot restart adb when AndroidDebugBridge is created without the location of"
                            + " adb."
                )
                return@logUsage false
            }

            if (sAdbServerPort == null) {
                Log.e(
                    ADB,
                    "ADB server port for restarting AndroidDebugBridge is not set."
                )
                return@logUsage false
            }

            if (!passedAdbServerVersionCheck) {
                Log.logAndDisplay(
                    Log.LogLevel.ERROR,
                    ADB,
                    "Attempting to restart adb, but version check failed!"
                )
                return@logUsage false
            }

            val rem = TimeoutRemainder(timeout, unit)
            // Notify the listeners of the change (outside of the lock to decrease the likelihood
            // of deadlocks)
            adbChangeEvents.notifyBridgeRestartInitiated()

            val isSuccessful = withLock {
                var success = stopAdb(rem.remainingNanos, TimeUnit.NANOSECONDS)

                if (success) {
                    success = startAdb(rem.remainingNanos, TimeUnit.NANOSECONDS)
                }
                if (success && adblibCompatDeviceManager == null) {
                    // `sThis` is modified and accessed here from within a `withLock` block
                    checkNotNull(currentAndroidDebugBridge)
                    startIDeviceManager(currentAndroidDebugBridge!!)
                }
                success
            }

            // Notify the listeners of the change (outside of the lock to decrease the likelihood
            // of deadlocks)
            adbChangeEvents.notifyBridgeRestartCompleted(isSuccessful)

            isSuccessful
        }
    }

    override fun getCurrentAdbVersion(): AdbVersion? {
        unsupportedMethod()
    }

    override fun getVirtualDeviceId(
        service: ListeningExecutorService,
        adb: File,
        device: IDevice
    ): ListenableFuture<String?>? {
        unsupportedMethod()
    }

    override fun openConnection(): SocketChannel {
        unsupportedMethod()
    }

    override fun optionsChanged(
        options: AdbInitOptions,
        osLocation: String,
        forceNewBridge: Boolean,
        terminateTimeout: Long,
        initTimeout: Long,
        unit: TimeUnit
    ): Boolean {
        unsupportedMethod()
    }

    override fun queryFeatures(adbFeaturesRequest: String): String {
        unsupportedMethod()
    }

    /**
     * Similar to [runBlocking] but with a custom [timeout]
     *
     * For information about the exceptions that [runBlocking] may throw, see the [runBlocking]
     * documentation. Additionally, this method
     * @throws TimeoutException if [block] take more than [timeout] to execute
     */
    private fun <R> runBlockingLegacy(
        timeout: Duration = Duration.ofMillis(DdmPreferences.getTimeOut().toLong()),
        block: suspend CoroutineScope.() -> R
    ): R {
        return runBlocking {
            if (timeout == INFINITE_DURATION) {
                block()
            } else {
                session.withErrorTimeout(timeout) {
                    block()
                }
            }
        }
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

    /**
     * Executes a block and logs its success of failure status using the Android Studio UsageTracker
     */
    private inline fun <R> logUsage(
        method: AdbDelegateUsageTracker.Method,
        crossinline block: () -> R
    ): R {
        return try {
            block().also {
                adbDelegateUsageTracker?.logUsage(method, /* isException = */ false)
            }
        } catch (t: Throwable) {
            adbDelegateUsageTracker?.logUsage(method, /* isException = */ true)
            throw t
        }
    }

    companion object {
        // ADB exit value when no Universal C Runtime on Windows
        const val STATUS_DLL_NOT_FOUND: Int = -0x3FFFFECB // Signed version of 0xc0000135

        /** Default timeout used when starting the ADB server  */
        const val DEFAULT_START_ADB_TIMEOUT_MILLIS: Long = 20000

        const val ADB: String = "adb"

        const val DDMS: String = "ddms"

        const val SERVER_PORT_ENV_VAR: String = "ANDROID_ADB_SERVER_PORT"

        // Where to find the ADB bridge.
        const val DEFAULT_ADB_PORT: Int = 5037
    }
}
