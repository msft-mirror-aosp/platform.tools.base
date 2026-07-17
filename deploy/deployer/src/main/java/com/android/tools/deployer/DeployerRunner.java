/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tools.deployer;

import static com.android.tools.deployer.common.InstallOptions.MOBILE_INSTALL_DEFAULTS;
import static com.android.tools.deployer.common.InstallOptions.STUDIO_DEFAULTS;

import com.android.adblib.AdbSession;
import com.android.adblib.ConnectedDevice;
import com.android.adblib.ConnectedDeviceKt;
import com.android.adblib.ConnectedDeviceList;
import com.android.adblib.tools.AdbLibSessionFactoryKt;
import com.android.adblib.tools.JavaBridge;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ddmlib.AdbInitOptions;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.IDevice;
import com.android.tools.deployer.common.AdbClient;
import com.android.tools.deployer.common.Canceller;
import com.android.tools.deployer.common.ChangeType;
import com.android.tools.deployer.common.DeployMetric;
import com.android.tools.deployer.common.DeployerException;
import com.android.tools.deployer.common.DeployerOption;
import com.android.tools.deployer.common.DeploymentCacheDatabase;
import com.android.tools.deployer.common.DeviceHolder;
import com.android.tools.deployer.common.InstallOptions;
import com.android.tools.deployer.common.Installer;
import com.android.tools.deployer.common.UIService;
import com.android.tools.deployer.install.InstallMode;
import com.android.tools.deployer.model.App;
import com.android.tools.deployer.model.component.ApkParserException;
import com.android.tools.deployer.tasks.TaskRunner;
import com.android.tools.tracer.Trace;
import com.android.utils.ILogger;
import com.android.utils.StdLogger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DeployerRunner {
    private static final int SUCCESS = 0;

    // These values are > 1000 in order to prevent collision with the DeployerException.Error
    // ordinal that is returned if a DeployerException is thrown during deployment.
    private static final int ERR_SPECIFIED_DEVICE_NOT_FOUND = 1002;
    private static final int ERR_NO_MATCHING_DEVICE = 1003;
    private static final int ERR_BAD_ARGS = 1004;

    private static final String DB_DIR_PATH = getDbDirPath();
    private static final String DEX_DB_PATH = DB_DIR_PATH + File.separator + "studio_dex.db";
    private static final String DEPLOY_DB_PATH = DB_DIR_PATH + File.separator + "studio_deploy.db";

    @VisibleForTesting
    public static String getDbDirPath() {
        String tmpDir = System.getProperty("java.io.tmpdir");
        String userName = System.getProperty("user.name");
        if (tmpDir == null) {
            tmpDir = "/tmp";
        }
        if (userName == null || userName.isEmpty()) {
            userName = "unknown";
        }
        return tmpDir + File.separator + "android-" + userName;
    }

    private final InstallOptions defaultInstallOptions;
    private final DeploymentCacheDatabase cacheDb;
    private final SqlApkFileDatabase dexDb;
    private final MetricsRecorder metrics;
    private final UIService service;

    private long deviceWaitTimeoutMs = TimeUnit.SECONDS.toMillis(30);

    private static final long ADBLIB_TRACKER_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    // Run it from bazel with the following command:
    // bazel run :deployer.runner INSTALL --device=<target device> <package name> <apk 1> <apk 2>
    // ... <apk N>
    public static void main(String[] args) {
        Trace.start();
        Trace.begin("main");

        DeployerRunner runner =
                new DeployerRunner(
                        MOBILE_INSTALL_DEFAULTS,
                        new File(DEPLOY_DB_PATH),
                        new File(DEX_DB_PATH),
                        new AlwaysYesService());

        int errorCode = runner.run(args);
        Trace.end();
        Trace.flush();
        System.exit(errorCode);
    }

    public DeployerRunner(File deployCacheFile, File databaseFile, UIService service) {
        this(STUDIO_DEFAULTS, deployCacheFile, databaseFile, service);
    }

    private DeployerRunner(
            InstallOptions defaultInstallOptions,
            File deployCacheFile,
            File databaseFile,
            UIService service) {
        ensurePrivateDir(deployCacheFile.getParentFile());
        ensurePrivateDir(databaseFile.getParentFile());
        this.defaultInstallOptions = defaultInstallOptions;
        this.cacheDb = new DeploymentCacheDatabase(deployCacheFile);
        this.dexDb = new SqlApkFileDatabase(databaseFile, null);
        this.service = service;
        this.metrics = new MetricsRecorder();
    }

    /**
     * Ensures that the database directory exists and is secured.
     *
     * <p>On POSIX systems (Linux/macOS), we explicitly restrict the directory to 0700 (owner-only
     * read/write/execute) to prevent other users on a shared machine from accessing the database
     * files.
     *
     * <p>On Windows, we rely on the fact that {@code java.io.tmpdir} resolves to the user's local
     * AppData Temp directory (e.g., C:\Users\<username>\AppData\Local\Temp), which is already
     * secured by Windows NTFS ACLs to be owner-only accessible. The file permission calls here act
     * as a best-effort fallback.
     */
    private static void ensurePrivateDir(File dir) {
        if (dir == null) {
            return;
        }
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            dir.setReadable(false, false);
            dir.setReadable(true, true);
            dir.setWritable(false, false);
            dir.setWritable(true, true);
            dir.setExecutable(false, false);
            dir.setExecutable(true, true);
        } catch (SecurityException e) {
            System.err.println(
                    "Warning: Failed to secure database directory: "
                            + dir.getAbsolutePath()
                            + " - "
                            + e.getMessage());
        }
    }

    @VisibleForTesting
    public DeployerRunner(
            DeploymentCacheDatabase cacheDb, SqlApkFileDatabase dexDb, UIService service) {
        this.defaultInstallOptions = STUDIO_DEFAULTS;
        this.cacheDb = cacheDb;
        this.dexDb = dexDb;
        this.service = service;
        this.metrics = new MetricsRecorder();
    }

    @VisibleForTesting
    public void setDeviceWaitTimeout(long duration, TimeUnit unit) {
        deviceWaitTimeoutMs = unit.toMillis(duration);
    }

    public int run(String[] args) {
        if (args.length < 3) {
            // The values for --user come directly from the framework's package manager, and is
            // passed directly through to pm.
            System.out.println(
                    "Usage: {install | codeswap | fullswap} [--device=<serial>] [--user=<user"
                            + " id>|all|current] [--adb=<path>] packageName baseApk [splitApk1,"
                            + " splitApk2, ...]");
            return ERR_BAD_ARGS;
        }

        try {
            DeployRunnerParameters parameters = DeployRunnerParameters.parse(args);
            StdLogger.Level logLevel = parameters.getLogLevel();
            ILogger logger = new StdLogger(logLevel);
            if (parameters.getJdwpClientSupport()) {
                AndroidDebugBridge.init(AdbInitOptions.DEFAULT);
            } else {
                AndroidDebugBridge.init(
                        AdbInitOptions.builder()
                                .setClientSupportEnabled(false)
                                .useJdwpProxyService(false)
                                .build());
            }
            try (WaitForDevicesResult devicesResult =
                    waitForDevices(
                            parameters.getAdbExecutablePath(),
                            parameters.getTargetDevices(),
                            logLevel,
                            logger)) {

                if (devicesResult.devices.isEmpty()) {
                    logger.error(null, "No device connected to ddmlib");
                    return ERR_NO_MATCHING_DEVICE;
                }

                for (String expectedDevice : parameters.getTargetDevices()) {
                    if (!devicesResult.devices.containsKey(expectedDevice)) {
                        logger.error(null, "Could not find specified device: %s", expectedDevice);
                        return ERR_SPECIFIED_DEVICE_NOT_FOUND;
                    }
                }

                for (DeviceHolder device : devicesResult.devices.values()) {
                    int status = run(device, devicesResult.session, parameters, logger);
                    if (status != SUCCESS) {
                        logger.error(null, "Error deploying to device: %s", device.getName());
                        return status;
                    }
                }

                return SUCCESS;
            }
        } finally {
            AndroidDebugBridge.terminate();
        }
    }

    // Left in to support how DeployService calls us.
    public int run(IDevice device, String[] args, ILogger logger) {
        DeployRunnerParameters parameters = DeployRunnerParameters.parse(args);
        // Use an adblib connection. This is piggybacking on the adb server guaranteed to be
        // spawned by DDMLIB.
        AdbSession session =
                AdbLibSessionFactoryKt.createSocketConnectSession(
                        AndroidDebugBridge::getSocketAddress,
                        new DeployerRunnerLoggerFactory(parameters.getLogLevel()));
        // TODO: We need to lookup ConnectedDevice here or in DeployService to fully migrate.
        DeviceHolder deviceHolder = new DeviceHolder(device, null);
        try {
            return run(deviceHolder, session, parameters, logger);
        } finally {
            try {
                session.close();
            } catch (Exception e) {
                logger.warning("Failed to close AdbSession: " + e.getMessage());
            }
        }
    }

    private int run(
            DeviceHolder device,
            AdbSession session,
            DeployRunnerParameters parameters,
            ILogger logger) {
        EnumSet<ChangeType> optimisticInstallSupport = EnumSet.noneOf(ChangeType.class);
        if (parameters.isOptimisticInstall()) {
            optimisticInstallSupport.add(ChangeType.DEX);
            optimisticInstallSupport.add(ChangeType.NATIVE_LIBRARY);
        }

        metrics.getDeployMetrics().clear();

        AdbClient adb = new AdbClient(device, logger, session);
        Installer installer =
                new AdbInstaller(
                        parameters.getInstallersPath(), adb, metrics.getDeployMetrics(), logger);
        ExecutorService service = Executors.newFixedThreadPool(5);
        TaskRunner runner = new TaskRunner(service);
        DeployerOption deployerOption =
                new DeployerOption.Builder()
                        .setUseOptimisticSwap(true)
                        .setUseOptimisticResourceSwap(true)
                        .setUseStructuralRedefinition(true)
                        .setUseVariableReinitialization(true)
                        .setFastRestartOnSwapFail(false)
                        .setOptimisticInstallSupport(optimisticInstallSupport)
                        .enableCoroutineDebugger(true)
                        .setAllowAssumeVerified(device.getVersion().isAtLeast(35))
                        .skipPostInstallTasks(parameters.getSkipPostInstallTasks())
                        .useRootPushInstall(parameters.getUseRootPushInstall())
                        .build();

        final Deployer.Result deployResult;
        try {
            App app =
                    parameters.hasStrategyJson()
                            ? getAppToInstall(parameters.getStrategyJson(), logger)
                            : getAppToInstall(parameters.getApplicationId(), parameters.getApks());

            Deployer deployer =
                    new Deployer(
                            adb,
                            cacheDb,
                            dexDb,
                            runner,
                            installer,
                            // This is only need for IWI and we don't support IWI termination
                            // outside of
                            // Studio and our own unit testing.
                            new DeployerRunnerApplicationTerminator(device, app.getAppId()),
                            this.service,
                            metrics,
                            logger,
                            deployerOption);

            if (parameters.getCommands().contains(DeployRunnerParameters.Command.INSTALL)) {
                InstallOptions.Builder options = defaultInstallOptions.toBuilder();

                if (device.isEmbedded()) {
                    options.setGrantAllPermissions();
                }

                InstallMode installMode = InstallMode.DELTA;
                if (parameters.isForceFullInstall()) {
                    installMode = InstallMode.FULL;
                }

                if (parameters.getTargetUserId() != null) {
                    options.setInstallOnUser(parameters.getTargetUserId());
                }

                options.setShouldUseAssumeVerified(deployerOption.allowAssumeVerified);
                options.setUserInstallOptions(parameters.getUserInstallFlags());
                deployResult = deployer.install(app, options.build(), installMode);
            } else if (parameters.getCommands().contains(DeployRunnerParameters.Command.FULLSWAP)) {
                deployResult = deployer.fullSwap(app, Canceller.NO_OP);
            } else if (parameters.getCommands().contains(DeployRunnerParameters.Command.CODESWAP)) {
                deployResult = deployer.codeSwap(app, ImmutableMap.of(), Canceller.NO_OP);
            } else {
                throw new RuntimeException("UNKNOWN command");
            }
            runner.run(Canceller.NO_OP);
            if (parameters.getCommands().contains(DeployRunnerParameters.Command.ACTIVATE)) {
                DeployRunnerParameters.Component component = parameters.getComponentToActivate();
                assert component != null;
                Activator activator = new Activator(deployResult.app, logger);
                activator.activate(
                        component.type, component.name, new LoggerReceiver(logger), device);
            }
        } catch (DeployerException e) {
            String commands =
                    parameters.getCommands().stream()
                            .map(String::valueOf)
                            .map(String::toLowerCase)
                            .collect(Collectors.joining(","));
            logger.error(e, "Not possible to execute " + commands);
            logger.warning(e.getDetails());
            return e.getError().ordinal();
        } finally {
            service.shutdown();
        }
        return SUCCESS;
    }

    public List<DeployMetric> getMetrics() {
        return metrics.getDeployMetrics();
    }

    public static App getAppToInstall(String appId, List<Path> apks) throws DeployerException {
        try {
            return App.fromPaths(appId, apks);
        } catch (ApkParserException e) {
            throw DeployerException.parseFailed(e.getMessage());
        }
    }

    public static App getAppToInstall(String strategyJson, ILogger logger)
            throws DeployerException {
        try {
            Path path = Paths.get(strategyJson);
            return DeployStrategyReader.fromStrategy(path, logger);
        } catch (ApkParserException e) {
            throw DeployerException.parseFailed(e.getMessage());
        }
    }

    private WaitForDevicesResult waitForDevices(
            String adbExecutablePath,
            List<String> deviceSerials,
            StdLogger.Level logLevel,
            ILogger logger) {
        try (Trace unused = Trace.begin("waitForDevices()")) {
            int expectedDevices = deviceSerials.isEmpty() ? 1 : deviceSerials.size();
            CountDownLatch latch = new CountDownLatch(expectedDevices);
            ConcurrentHashMap<String, IDevice> devices = new ConcurrentHashMap<>();

            AndroidDebugBridge.IDeviceChangeListener listener =
                    new AndroidDebugBridge.IDeviceChangeListener() {
                        @Override
                        public void deviceConnected(@NonNull IDevice device) {
                            final String serial = device.getSerialNumber();
                            logger.info("Found device with serial: %s", serial);
                            if (deviceSerials.isEmpty() || deviceSerials.contains(serial)) {
                                devices.put(serial, device);
                                latch.countDown();
                            }
                        }

                        @Override
                        public void deviceDisconnected(IDevice device) {}

                        @Override
                        public void deviceChanged(IDevice device, int changeMask) {}
                    };

            AndroidDebugBridge.addDeviceChangeListener(listener);

            // This needs to be done *after* we add the listener, or else we risk missing devices.
            AndroidDebugBridge bridge;
            if (adbExecutablePath == null) {
                bridge = AndroidDebugBridge.createBridge(5, TimeUnit.SECONDS);
            } else {
                // The value of forceNewBridge doesn't really matter here, since the bridge is only
                // going to exist for a single deployment.
                bridge =
                        AndroidDebugBridge.createBridge(
                                adbExecutablePath, true, 5, TimeUnit.SECONDS);
            }
            if (bridge == null) {
                logger.error(null, "Could not create debug bridge");
                return WaitForDevicesResult.empty();
            }

            try {
                if (!latch.await(deviceWaitTimeoutMs, TimeUnit.MILLISECONDS)) {
                    return WaitForDevicesResult.empty();
                }
            } catch (InterruptedException e) {
                return WaitForDevicesResult.empty();
            } finally {
                AndroidDebugBridge.removeDeviceChangeListener(listener);
            }

            // Create AdbSession and lookup ConnectedDevices
            AdbSession session =
                    AdbLibSessionFactoryKt.createSocketConnectSession(
                            AndroidDebugBridge::getSocketAddress,
                            new DeployerRunnerLoggerFactory(logLevel));

            boolean useConnectedDevice = DeviceHolder.checkEnableUseConnectedDevice(session);

            if (useConnectedDevice) {
                ConnectedDeviceList connectedDeviceList =
                        getConnectedDeviceList(session, ADBLIB_TRACKER_TIMEOUT_MS, logger);
                return WaitForDevicesResult.of(
                        createDeviceHolders(devices, connectedDeviceList), session, logger);
            } else {
                return WaitForDevicesResult.of(createLegacyDeviceHolders(devices), session, logger);
            }
        }
    }

    @Nullable
    private ConnectedDeviceList getConnectedDeviceList(
            AdbSession session, long timeoutMs, ILogger logger) {
        try {
            return JavaBridge.runBlocking(
                    session,
                    continuation ->
                            DeployerRunnerUtilsKt.getConnectedDevicesOrNull(
                                    session, timeoutMs, continuation));
        } catch (Exception e) {
            logger.warning(
                    "Failed to wait for adblib connectedDevices tracker to become active: "
                            + e.getMessage());
            return null;
        }
    }

    /**
     * Creates {@link DeviceHolder} instances by matching {@link IDevice}s with their corresponding
     * {@link ConnectedDevice}s from the adblib session when connected device mode is enabled.
     */
    @NonNull
    private Map<String, DeviceHolder> createDeviceHolders(
            @NonNull Map<String, IDevice> devices,
            @Nullable ConnectedDeviceList connectedDeviceList) {
        Map<String, DeviceHolder> deviceHolders = new HashMap<>(devices.size());
        for (Map.Entry<String, IDevice> entry : devices.entrySet()) {
            IDevice device = entry.getValue();
            ConnectedDevice connectedDevice = null;
            if (connectedDeviceList != null) {
                String serial = device.getSerialNumber();
                connectedDevice =
                        connectedDeviceList.stream()
                                .filter(d -> ConnectedDeviceKt.getSerialNumber(d).equals(serial))
                                .findFirst()
                                .orElse(null);
            }
            deviceHolders.put(
                    entry.getKey(),
                    new DeviceHolder(device, Optional.ofNullable(connectedDevice), true));
        }
        return deviceHolders;
    }

    /**
     * Creates legacy {@link DeviceHolder} instances without adblib {@link ConnectedDevice} lookup.
     */
    @Deprecated
    @NonNull
    private Map<String, DeviceHolder> createLegacyDeviceHolders(
            @NonNull Map<String, IDevice> devices) {
        Map<String, DeviceHolder> deviceHolders = new HashMap<>(devices.size());
        for (Map.Entry<String, IDevice> entry : devices.entrySet()) {
            deviceHolders.put(entry.getKey(), new DeviceHolder(entry.getValue(), null));
        }
        return deviceHolders;
    }

    // MI has no way for users to respond to a prompt; just always proceed.
    static class AlwaysYesService implements UIService {
        @Override
        public boolean prompt(String result) {
            return true;
        }

        @Override
        public void message(String message) {}
    }

    /**
     * A container for the results of a {@link #waitForDevices} operation.
     *
     * <p>The main purpose of this class is to encapsulate the device discovery results while
     * managing the lifecycle of the `AdbSession`.
     */
    private static final class WaitForDevicesResult implements AutoCloseable {
        @NonNull private final Map<String, DeviceHolder> devices;
        @Nullable private final AdbSession session;
        @Nullable private final ILogger logger;

        private WaitForDevicesResult(
                @NonNull Map<String, DeviceHolder> devices,
                @Nullable AdbSession session,
                @Nullable ILogger logger) {
            this.devices = devices;
            this.session = session;
            this.logger = logger;
        }

        public static WaitForDevicesResult empty() {
            return new WaitForDevicesResult(Collections.emptyMap(), null, null);
        }

        public static WaitForDevicesResult of(
                @NonNull Map<String, DeviceHolder> devices,
                @NonNull AdbSession session,
                @NonNull ILogger logger) {
            Objects.requireNonNull(devices, "devices must not be null");
            if (devices.isEmpty()) {
                throw new IllegalArgumentException("devices must not be empty");
            }
            Objects.requireNonNull(session, "session must not be null");
            Objects.requireNonNull(logger, "logger must not be null");
            return new WaitForDevicesResult(devices, session, logger);
        }

        @Override
        public void close() {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception e) {
                    if (logger != null) {
                        logger.warning("Failed to close AdbSession: " + e.getMessage());
                    }
                }
            }
        }
    }
}
