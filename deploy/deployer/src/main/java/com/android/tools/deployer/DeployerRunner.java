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

import com.android.adblib.AdbServerController;
import com.android.adblib.AdbSession;
import com.android.adblib.ConnectedDevice;
import com.android.adblib.tools.JavaBridge;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DeployerRunner {
    private static final int SUCCESS = 0;

    // These values are > 1000 in order to prevent collision with the DeployerException.Error
    // ordinal that is returned if a DeployerException is thrown during deployment.
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
    private Integer serverPort = null;

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

    @VisibleForTesting
    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
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

        DeployRunnerParameters parameters = DeployRunnerParameters.parse(args);
        StdLogger.Level logLevel = parameters.getLogLevel();
        ILogger logger = new StdLogger(logLevel);
        int serverPort =
                this.serverPort != null
                        ? this.serverPort
                        : DeployerRunnerUtilsKt.getAdbServerPort(logger);

        AdbServerController adbServerController =
                DeployerRunnerUtilsKt.createAndStartAdbServerControllerBlocking(
                        parameters.getAdbExecutablePath(), serverPort, logLevel);
        AdbSession session = null;
        try {
            session = DeployerRunnerUtilsKt.createAdbSession(adbServerController, logLevel);
            List<DeviceHolder> devices =
                    waitForConnectedDevices(session, parameters.getTargetDevices(), logger);
            return executeDeployment(parameters, devices, session, logger);
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception e) {
                    logger.warning("Failed to close AdbSession: " + e.getMessage());
                }
            }
            try {
                adbServerController.close();
            } catch (Exception e) {
                logger.warning("Failed to close AdbServerController: " + e.getMessage());
            }
        }
    }

    private int executeDeployment(
            DeployRunnerParameters parameters,
            List<DeviceHolder> devices,
            AdbSession session,
            ILogger logger) {
        if (devices.isEmpty()) {
            logger.error(null, "No device connected");
            return ERR_NO_MATCHING_DEVICE;
        }

        for (DeviceHolder device : devices) {
            int status = run(device, session, parameters, logger);
            if (status != SUCCESS) {
                logger.error(null, "Error deploying to device: %s", device.getSerialNumber());
                return status;
            }
        }

        return SUCCESS;
    }

    // Left in to support how DeployService calls us.
    public int run(DeviceHolder device, AdbSession session, String[] args, ILogger logger) {
        DeployRunnerParameters parameters = DeployRunnerParameters.parse(args);
        return run(device, session, parameters, logger);
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

    private List<DeviceHolder> waitForConnectedDevices(
            AdbSession session, List<String> deviceSerials, ILogger logger) {
        try (Trace unused = Trace.begin("waitForConnectedDevices()")) {
            List<ConnectedDevice> connectedDevices;
            try {
                connectedDevices =
                        JavaBridge.runBlocking(
                                session,
                                continuation ->
                                        DeployerRunnerUtilsKt.waitForConnectedDevices(
                                                session,
                                                deviceSerials,
                                                deviceWaitTimeoutMs,
                                                continuation));
            } catch (Exception e) {
                logger.warning("Unexpected error during device discovery: " + e.getMessage());
                connectedDevices = Collections.emptyList();
            }

            return connectedDevices.stream().map(DeviceHolder::new).collect(Collectors.toList());
        }
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
}
