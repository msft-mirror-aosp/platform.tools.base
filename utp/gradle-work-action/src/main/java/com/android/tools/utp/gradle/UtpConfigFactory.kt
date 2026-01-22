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

package com.android.tools.utp.gradle

import com.android.tools.utp.gradle.api.EmulatorControlConfig
import com.android.tools.utp.gradle.api.ShardConfig
import com.android.tools.utp.gradle.api.TargetApkConfigBundle
import com.android.tools.utp.gradle.api.TestData
import com.android.tools.utp.gradle.api.UtpDependencies
import com.android.tools.utp.gradle.api.UtpDependency
import com.android.tools.utp.plugins.deviceprovider.ddmlib.proto.AndroidDeviceProviderDdmlibConfigProto.DdmlibAndroidDeviceProviderConfig
import com.android.tools.utp.plugins.host.additionaltestoutput.proto.AndroidAdditionalTestOutputConfigProto.AndroidAdditionalTestOutputConfig
import com.android.tools.utp.plugins.host.apkinstaller.proto.AndroidApkInstallerConfigProto.AndroidApkInstallerConfig
import com.android.tools.utp.plugins.host.apkinstaller.proto.AndroidApkInstallerConfigProto.InstallableApk.InstallOption.ForceCompilation
import com.android.tools.utp.plugins.host.coverage.proto.AndroidTestCoverageConfigProto.AndroidTestCoverageConfig
import com.android.tools.utp.plugins.host.emulatorcontrol.proto.EmulatorControlPluginProto.EmulatorControlPlugin
import com.android.tools.utp.plugins.host.logcat.proto.AndroidTestLogcatConfigProto.AndroidTestLogcatConfig
import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerConfigProto.GradleAndroidTestResultListenerConfig
import com.google.common.collect.Iterables
import com.google.protobuf.Any
import com.google.protobuf.Message
import com.google.testing.platform.plugin.android.proto.AndroidDevicePluginProto.AndroidDevicePlugin
import com.google.testing.platform.proto.api.config.AndroidInstrumentationDriverProto.AndroidInstrumentationDriver
import com.google.testing.platform.proto.api.config.DeviceProto
import com.google.testing.platform.proto.api.config.EnvironmentProto
import com.google.testing.platform.proto.api.config.ExecutorProto
import com.google.testing.platform.proto.api.config.FixtureProto
import com.google.testing.platform.proto.api.config.LocalAndroidDeviceProviderProto.LocalAndroidDeviceProvider
import com.google.testing.platform.proto.api.config.RunnerConfigProto
import com.google.testing.platform.proto.api.core.ExtensionProto
import com.google.testing.platform.proto.api.core.ExtensionProto.Extension
import com.google.testing.platform.proto.api.core.LabelProto
import com.google.testing.platform.proto.api.core.PathProto
import org.gradle.api.logging.Logging
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

// This is an arbitrary string. This ID is used to lookup test results from UTP.
// UTP can run multiple test fixtures at a time so we have to give a name for
// each test fixture. However, AGP always has only one test fixture in the
// runner-config so this string is arbitrary.
private const val UTP_TEST_FIXTURE_ID = "AGP_Test_Fixture"

private const val TEST_RUNNER_LOG_FILE_NAME = "test-results.log"

private val AM_INSTRUMENT_COMMAND_TIME_OUT_SECONDS = TimeUnit.DAYS.toSeconds(365)

// Relative path to the UTP outputDir for test log directory.
private const val TEST_LOG_DIR = "testlog"

/**
 * Creates a runner config proto which you can pass into the Unified Test Platform's
 * test executor.
 *
 * @param uninstallIncompatibleApks uninstalls APKs on the device when an installation failure
 * occurs due to incompatible APKs such as INSTALL_FAILED_UPDATE_INCOMPATIBLE,
 * INCONSISTENT_CERTIFICATES, etc.
 * @param additionalTestOutputDir an additional test output directory on host machine, or null
 *     when disabled.
 */
fun createRunnerConfigProtoForLocalDevice(
    deviceId: String,
    deviceName: String,
    deviceShardName: String,
    projectPath: String,
    variantName: String,
    deviceSerialNumber: String,
    testData: TestData,
    targetApkConfigBundle: TargetApkConfigBundle,
    additionalInstallOptions: Iterable<String>,
    helperApks: Iterable<File>,
    uninstallIncompatibleApks: Boolean,
    utpDependencies: UtpDependencies,
    androidSdkPath: String,
    adbExecutablePath: String,
    aaptExecutablePath: String,
    dexdumpExecutablePath: String,
    outputDir: File,
    tmpDir: File,
    emulatorControlConfig: EmulatorControlConfig,
    coverageOutputDir: File,
    useOrchestrator: Boolean,
    forceCompilation: Boolean,
    additionalTestOutputDir: File?,
    additionalTestOutputOnDeviceDir: String?,
    installApkTimeout: Int?,
    dependencyApks: List<List<Path>>,
    uninstallApksAfterTest: Boolean,
    reinstallIncompatibleApksBeforeTest: Boolean,
    shardConfig: ShardConfig?,
    enableUtpTestReportingForAndroidStudio: Boolean,
    xmlTestReportOutputDirectory: File,
    utpResultProtoOutputFile: File,
): RunnerConfigProto.RunnerConfig {
    return RunnerConfigProto.RunnerConfig.newBuilder().apply {
        val grpcInfo = findGrpcInfo(deviceSerialNumber)
        addDevice(createLocalDevice(deviceSerialNumber, uninstallIncompatibleApks, utpDependencies))
        addTestFixture(
            createTestFixture(
                grpcInfo,
                targetApkConfigBundle,
                additionalInstallOptions,
                helperApks,
                testData,
                utpDependencies,
                androidSdkPath,
                adbExecutablePath,
                aaptExecutablePath,
                dexdumpExecutablePath,
                outputDir,
                tmpDir,
                emulatorControlConfig,
                useOrchestrator,
                forceCompilation,
                additionalTestOutputDir,
                additionalTestOutputOnDeviceDir,
                coverageOutputDir,
                installApkTimeout,
                shardConfig,
                uninstallApksAfterTest,
                dependencyApks,
                reinstallIncompatibleApksBeforeTest,
            )
        )
        singleDeviceExecutor = createSingleDeviceExecutor(deviceSerialNumber, shardConfig)
        addTestResultListenerPlugin(
            utpDependencies, deviceId, deviceName, deviceShardName, projectPath, variantName,
            enableUtpTestReportingForAndroidStudio, xmlTestReportOutputDirectory,
            utpResultProtoOutputFile)
        cancellationConfigBuilder.apply {
            pluginCleanupTimeoutMs = 1.seconds.toLong(DurationUnit.MILLISECONDS)
            executorCancellationTimeoutMs = 1.seconds.toLong(DurationUnit.MILLISECONDS)
            executorCancellationAbortMs = 1.seconds.toLong(DurationUnit.MILLISECONDS)
        }
    }.build()
}

private fun RunnerConfigProto.RunnerConfig.Builder.addTestResultListenerPlugin(
    utpDependencies: UtpDependencies,
    deviceId: String,
    deviceName: String,
    deviceShardName: String,
    projectPath: String,
    variantName: String,
    enableUtpTestReportingForAndroidStudio: Boolean,
    xmlTestReportOutputDirectory: File,
    utpResultProtoOutputFile: File,
) {
    addTestResultListener(
        UtpDependency.ANDROID_TEST_PLUGIN_RESULT_LISTENER_GRADLE.toExtensionProto(
        utpDependencies, GradleAndroidTestResultListenerConfig::newBuilder
    ) {
        this.deviceId = deviceId
        this.deviceName = deviceName
        this.deviceShardName = deviceShardName
        this.gradleProjectPath = projectPath
        this.variantName = variantName
        this.enableUtpTestReportingForAndroidStudio = enableUtpTestReportingForAndroidStudio
        this.xmlTestReportOutputDirectoryPath = xmlTestReportOutputDirectory.absolutePath
        this.utpResultProtoOutputFilePath = utpResultProtoOutputFile.absolutePath
    })
}

private fun createLocalDevice(
    deviceSerialNumber: String,
    uninstallIncompatibleApks: Boolean,
    utpDependencies: UtpDependencies
): DeviceProto.Device {
    return DeviceProto.Device.newBuilder().apply {
        deviceIdBuilder.apply {
            id = deviceSerialNumber
        }
        provider = createLocalDeviceProvider(deviceSerialNumber, uninstallIncompatibleApks, utpDependencies)
    }.build()
}

private fun createLocalDeviceProvider(
    deviceSerialNumber: String,
    uninstallIncompatibleApks: Boolean,
    utpDependencies: UtpDependencies
): Extension {
    val localConfig = LocalAndroidDeviceProvider.newBuilder().apply {
        serial = deviceSerialNumber
    }.build()
    return UtpDependency.ANDROID_DEVICE_PROVIDER_DDMLIB.toExtensionProto(
        utpDependencies, DdmlibAndroidDeviceProviderConfig::newBuilder
    ) {
        localAndroidDeviceProviderConfig = Any.pack(localConfig)
        this.uninstallIncompatibleApks = uninstallIncompatibleApks
    }
}

/**
 * Creates the test fixture proto for the device to be run against.
 */
private fun createTestFixture(
    grpcInfo: EmulatorGrpcInfo?,
    targetApkConfigBundle: TargetApkConfigBundle,
    additionalInstallOptions: Iterable<String>,
    helperApks: Iterable<File>,
    testData: TestData,
    utpDependencies: UtpDependencies,
    androidSdkPath: String,
    adbExecutablePath: String,
    aaptExecutablePath: String,
    dexdumpExecutablePath: String,
    outputDir: File,
    tmpDir: File,
    emulatorControlConfig: EmulatorControlConfig,
    useOrchestrator: Boolean,
    forceCompilation: Boolean,
    additionalTestOutputDir: File?,
    additionalTestOutputOnDeviceDir: String?,
    coverageOutputDir: File,
    installApkTimeout: Int?,
    shardConfig: ShardConfig?,
    uninstallApksAfterTest: Boolean,
    dependencyApks: List<List<Path>>,
    reinstallIncompatibleApksBeforeTest: Boolean,
): FixtureProto.TestFixture {
    return FixtureProto.TestFixture.newBuilder().apply {
        val additionalTestParams: MutableMap<String, String> = mutableMapOf()
        testFixtureIdBuilder.apply {
            id = UTP_TEST_FIXTURE_ID
        }
        environment = createEnvironment(
            outputDir,
            tmpDir,
            androidSdkPath,
            adbExecutablePath,
            aaptExecutablePath,
            dexdumpExecutablePath,
        )

        if (emulatorControlConfig.enabled) {
            if (grpcInfo != null) {
                // We are configuring a running emulator
                val cfg = createTokenConfig(
                    emulatorControlConfig.allowedEndpoints,
                    emulatorControlConfig.secondsValid,
                    "gradle-utp-emulator-control",
                    grpcInfo
                )

                if (cfg != INVALID_JWT_CONFIG) {
                    // Note cfg != INVALID -> grpcInfo != null
                    additionalTestParams["grpc.port"] = grpcInfo.port.toString()
                    additionalTestParams["grpc.token"] = cfg.token
                    addHostPlugin(
                        createEmulatorControlPlugin(
                            grpcInfo.port,
                            cfg.token,
                            cfg.jwkPath,
                            emulatorControlConfig.secondsValid,
                            emulatorControlConfig.allowedEndpoints,
                            utpDependencies,
                        )
                    )
                } else {
                    Logging.getLogger(this.javaClass).warn(
                        "Control of the emulator is not supported for emulators without " +
                                "security features enabled. Please upgrade to a " +
                                "later version of the emulator."
                    )
                }
            } else {
                // We are doing late stage configuration with managed devices.
                addHostPlugin(
                    createEmulatorControlPlugin(
                        /* grpcPort= */ 0,
                        /* jwtToken= */ "",
                        /* jwkPath= */ "",
                        emulatorControlConfig.secondsValid,
                        emulatorControlConfig.allowedEndpoints,
                        utpDependencies,
                    )
                )
            }
        }

        testDriver = createTestDriver(
            testData,
            utpDependencies,
            useOrchestrator,
            additionalTestOutputOnDeviceDir,
            shardConfig,
            additionalTestParams,
        )

        addHostPlugin(
            createApkInstallerPlugin(
                targetApkConfigBundle,
                dependencyApks,
                helperApks,
                installApkTimeout,
                additionalInstallOptions,
                testData,
                uninstallApksAfterTest,
                utpDependencies,
                reinstallIncompatibleApksBeforeTest,
                forceCompilation,
            )
        )
        // This line is required since AndroidTestPlugin sends event message to context after
        // installing the APKs
        addHostPlugin(createAndroidTestPlugin(utpDependencies))
        addHostPlugin(createAndroidTestDeviceInfoPlugin(utpDependencies))
        addHostPlugin(
            createAndroidTestLogcatPlugin(
                testData.instrumentationTargetPackageId, utpDependencies
            )
        )
        if (testData.isTestCoverageEnabled) {
            addHostPlugin(
                createAndroidTestCoveragePlugin(
                    coverageOutputDir, useOrchestrator, testData, utpDependencies
                )
            )
        }
        if (additionalTestOutputDir != null) {
            addHostPlugin(
                createAdditionalTestOutputPlugin(
                    additionalTestOutputDir,
                    additionalTestOutputOnDeviceDir,
                    utpDependencies
                )
            )
        }
    }.build()
}

private fun createEmulatorControlPlugin(
    grpcPort: Int?,
    jwtToken: String,
    jwkPath: String,
    validTimeInSeconds: Int,
    allowed: Set<String>,
    utpDependencies: UtpDependencies,
): Extension {
    return UtpDependency.ANDROID_TEST_PLUGIN_HOST_EMULATOR_CONTROL.toExtensionProto(
        utpDependencies, EmulatorControlPlugin::newBuilder
    ) {
        emulatorGrpcPort = grpcPort ?: 0
        token = jwtToken
        jwkFile = jwkPath
        secondsValid = validTimeInSeconds
        addAllAllowedEndpoints(allowed)
        emulatorClientPrivateKeyFilePath = ""
        emulatorClientCaFilePath = ""
        trustedCollectionRootPath = ""
        tlsCfgPrefix = ""
    }
}

private fun createAndroidTestPlugin(
    utpDependencies: UtpDependencies
): Extension {
    return UtpDependency.ANDROID_TEST_PLUGIN.toExtensionProto(
        utpDependencies, AndroidDevicePlugin::newBuilder
    ) {
    }
}

private fun createEnvironment(
    outputDir: File,
    tmpDir: File,
    androidSdkPath: String,
    adbExecutablePath: String,
    aaptExecutablePath: String,
    dexdumpExecutablePath: String,
): EnvironmentProto.Environment {
    return EnvironmentProto.Environment.newBuilder().apply {
        outputDirBuilder.apply {
            path = outputDir.absolutePath
        }
        tmpDirBuilder.apply {
            path = tmpDir.absolutePath
        }
        androidEnvironmentBuilder.apply {
            androidSdkBuilder.apply {
                sdkPathBuilder.apply {
                    path = androidSdkPath
                }
                adbPathBuilder.apply {
                    path = adbExecutablePath
                }
                aaptPathBuilder.apply {
                    path = aaptExecutablePath
                }
                dexdumpPathBuilder.apply {
                    path = dexdumpExecutablePath
                }
                testLogDirBuilder.apply {
                    path = TEST_LOG_DIR // Must be relative path to outputDir
                }
                testRunLogBuilder.apply {
                    path = TEST_RUNNER_LOG_FILE_NAME
                }
            }
        }
    }.build()
}

private fun createTestDriver(
    testData: TestData,
    utpDependencies: UtpDependencies,
    useOrchestrator: Boolean,
    additionalTestOutputOnDeviceDir: String?,
    shardConfig: ShardConfig?,
    additionalTestParams: Map<String, String> = mapOf(),
): Extension {
    return UtpDependency.ANDROID_DRIVER_INSTRUMENTATION.toExtensionProto(
        utpDependencies, AndroidInstrumentationDriver::newBuilder
    ) {
        androidInstrumentationRuntimeBuilder.apply {
            instrumentationInfoBuilder.apply {
                appPackage = testData.testedApplicationId
                testPackage = testData.applicationId
                testRunnerClass = testData.instrumentationRunner
            }
            instrumentationArgsBuilder.apply {
                putAllArgsMap(testData.instrumentationRunnerArguments)
                putAllArgsMap(additionalTestParams)

                useTestStorageService =
                    testData.instrumentationRunnerArguments.getOrDefault(
                        "useTestStorageService", "false"
                    ).toBoolean()

                if (testData.isTestCoverageEnabled) {
                    putArgsMap("coverage", "true")
                    val testCoverageArgName = if (useOrchestrator) {
                        "coverageFilePath"
                    } else {
                        "coverageFile"
                    }
                    putArgsMap(
                        testCoverageArgName,
                        testData.getTestCoverageFilePath(useOrchestrator)
                    )
                }

                if (additionalTestOutputOnDeviceDir != null) {
                    putArgsMap("additionalTestOutputDir", additionalTestOutputOnDeviceDir)
                }

                if (shardConfig != null) {
                    require(
                        !testData.instrumentationRunnerArguments.containsKey("numShards") &&
                                !testData.instrumentationRunnerArguments.containsKey("shardIndex")
                    ) {
                        "testInstrumentationRunnerArguments.[numShards | shardIndex] is " +
                                "currently incompatible with sharding support for Gradle Managed " +
                                "Devices, and you should try running this test again without setting " +
                                "this property."
                    }
                    putArgsMap("numShards", shardConfig.totalCount.toString())
                    putArgsMap("shardIndex", shardConfig.index.toString())
                }

                noWindowAnimation = testData.animationsDisabled
            }
        }
        this.useOrchestrator = useOrchestrator
        amInstrumentTimeout = AM_INSTRUMENT_COMMAND_TIME_OUT_SECONDS
    }
}

private fun createAndroidTestDeviceInfoPlugin(utpDependencies: UtpDependencies): Extension {
    return UtpDependency.ANDROID_TEST_DEVICE_INFO_PLUGIN.toExtensionProto(utpDependencies)
}

/**
 * Creates and configures AndroidTestCoverage UTP plugin.
 *
 * It specifies two paths, a directory or file path to writes test coverage files on device and
 * a destination directory on a host. This logic used to be implemented in SimpleTestRunnable
 * and this new implementation is compatible with it (a drop-in replacement).
 */
private fun createAndroidTestCoveragePlugin(
    coverageOutputDir: File,
    useOrchestrator: Boolean,
    testData: TestData,
    utpDependencies: UtpDependencies
): Extension {
    val coverageFilePath = testData.getTestCoverageFilePath(useOrchestrator)

    return UtpDependency.ANDROID_TEST_COVERAGE_PLUGIN.toExtensionProto(
        utpDependencies, AndroidTestCoverageConfig::newBuilder
    ) {
        if (useOrchestrator) {
            multipleCoverageFilesInDirectory = coverageFilePath
        } else {
            singleCoverageFile = coverageFilePath
        }
        outputDirectoryOnHost = coverageOutputDir.absolutePath + File.separator
        runAsPackageName = testData.instrumentationTargetPackageId
        useTestStorageService = testData.instrumentationRunnerArguments.getOrDefault(
            "useTestStorageService", "false"
        ).toBoolean()
    }
}

private fun TestData.getTestCoverageFilePath(useOrchestrator: Boolean): String {
    val customCoveragePath =
        instrumentationRunnerArguments.getOrDefault("coverageFilePath", "")
    return when {
        customCoveragePath.isNotBlank() -> {
            customCoveragePath
        }

        useOrchestrator -> {
            "/data/data/${instrumentationTargetPackageId}/coverage_data/"
        }

        else -> {
            "/data/data/${instrumentationTargetPackageId}/coverage.ec"
        }
    }
}

private fun createAdditionalTestOutputPlugin(
    additionalTestOutputDir: File,
    additionalTestOutputOnDeviceDir: String?,
    utpDependencies: UtpDependencies
): Extension {
    return UtpDependency.ANDROID_TEST_ADDITIONAL_TEST_OUTPUT_PLUGIN.toExtensionProto(
        utpDependencies, AndroidAdditionalTestOutputConfig::newBuilder
    ) {
        additionalOutputDirectoryOnHost =
            additionalTestOutputDir.absolutePath + File.separator
        additionalTestOutputOnDeviceDir?.let {
            additionalOutputDirectoryOnDevice = it
        }
    }
}

private fun createAndroidTestLogcatPlugin(
    testPackageName: String,
    utpDependencies: UtpDependencies
): Extension {
    return UtpDependency.ANDROID_TEST_LOGCAT_PLUGIN.toExtensionProto(
        utpDependencies, AndroidTestLogcatConfig::newBuilder
    ) {
        targetTestProcessName = testPackageName
    }
}

// APK install sequence is aligned with legacy installer for better compatibility
private fun createApkInstallerPlugin(
    targetApkConfigBundle: TargetApkConfigBundle,
    dependencyApks: List<List<Path>>,
    helperApks: Iterable<File>,
    installApkTimeout: Int?,
    additionalInstallOptions: Iterable<String>,
    testData: TestData,
    uninstallApksAfterTest: Boolean,
    utpDependencies: UtpDependencies,
    reinstallIncompatibleApksBeforeTest: Boolean,
    forceCompilation: Boolean,
): Extension {
    return UtpDependency.ANDROID_TEST_PLUGIN_APK_INSTALLER.toExtensionProto(
        utpDependencies, AndroidApkInstallerConfig::newBuilder
    ) {
        instrumentationTargetPackageId = testData.instrumentationTargetPackageId
        if (dependencyApks.isNotEmpty() && dependencyApks.first().isNotEmpty()) {
            dependencyApks.forEach { apks ->
                addApksToInstallBuilder().apply {
                    addAllApkPaths(apks.map { it.absolutePathString() })
                    installOptionsBuilder.apply {
                        addAllCommandLineParameter(additionalInstallOptions)
                        installAsSplitApk = (apks.size > 1)
                        if (installApkTimeout != null) setInstallApkTimeout(
                            installApkTimeout
                        )
                        this.forceCompilation = getForceCompilationEnum(forceCompilation)
                    }.build()
                    uninstallAfterTest = uninstallApksAfterTest
                    forceReinstallBeforeTest = reinstallIncompatibleApksBeforeTest
                }.build()
            }
        }

        if (Iterables.size(targetApkConfigBundle.appApks) > 0) {
            addApksToInstallBuilder().apply {
                addAllApkPaths(targetApkConfigBundle.appApks.map { it.absolutePath })
                installOptionsBuilder.apply {
                    addAllCommandLineParameter(additionalInstallOptions)
                    installAsSplitApk = targetApkConfigBundle.isSplitApk
                    if (installApkTimeout != null) setInstallApkTimeout(installApkTimeout)
                    this.forceCompilation = getForceCompilationEnum(forceCompilation)
                }.build()
                uninstallAfterTest = uninstallApksAfterTest
                forceReinstallBeforeTest = reinstallIncompatibleApksBeforeTest
            }.build()
        }

        if (Iterables.size(helperApks) > 0) {
            addApksToInstallBuilder().apply {
                addAllApkPaths(helperApks.map { it.absolutePath })
                installOptionsBuilder.apply {
                    addAllCommandLineParameter(additionalInstallOptions)
                    if (installApkTimeout != null) setInstallApkTimeout(installApkTimeout)
                    installAsTestService = true
                    this.forceCompilation = getForceCompilationEnum(forceCompilation)
                }.build()
                uninstallAfterTest = uninstallApksAfterTest
                forceReinstallBeforeTest = reinstallIncompatibleApksBeforeTest
            }.build()
        }

        if (testData.testApk.absolutePath.isNotEmpty()) {
            addApksToInstallBuilder().apply {
                addAllApkPaths(listOf(testData.testApk.absolutePath))
                installOptionsBuilder.apply {
                    addAllCommandLineParameter(additionalInstallOptions)
                    if (installApkTimeout != null) setInstallApkTimeout(installApkTimeout)
                    this.forceCompilation = getForceCompilationEnum(forceCompilation)
                }.build()
                uninstallAfterTest = uninstallApksAfterTest
                forceReinstallBeforeTest = reinstallIncompatibleApksBeforeTest
            }.build()
        }
    }
}

private fun getForceCompilationEnum(forceAotCompilation: Boolean): ForceCompilation {
    return if (forceAotCompilation) {
        ForceCompilation.FULL_COMPILATION
    } else {
        ForceCompilation.NO_FORCE_COMPILATION
    }
}

private fun createSingleDeviceExecutor(
    identifier: String,
    shardConfig: ShardConfig?
): ExecutorProto.SingleDeviceExecutor {
    return ExecutorProto.SingleDeviceExecutor.newBuilder().apply {
        deviceExecutionBuilder.apply {
            deviceIdBuilder.apply {
                id = identifier
            }
            testFixtureIdBuilder.apply {
                id = UTP_TEST_FIXTURE_ID
            }
        }
        shardConfig?.let {
            shardingConfigBuilder.apply {
                shardCount = it.totalCount
                shardIndex = it.index
            }
        }
    }.build()
}

/**
 * Creates [ExtensionProto.Extension] for the given [UtpDependency] with a config.
 */
private fun UtpDependency.toExtensionProto(
    utpDependencies: UtpDependencies,
    config: Any? = null
): Extension {
    val builder = Extension.newBuilder().apply {
        label = LabelProto.Label.newBuilder().apply {
            label = name
        }.build()
        className = mainClass
        addAllJar(mapperFunc(utpDependencies).files.map {
            PathProto.Path.newBuilder().apply {
                path = it.absolutePath
            }.build()
        })
        useSingleClassLoader = true
    }
    if (config != null) {
        builder.config = config
    }
    return builder.build()
}

private fun <T : Message.Builder> UtpDependency.toExtensionProto(
    utpDependencies: UtpDependencies,
    newBuilder: () -> T,
    configFunc: T.() -> Unit
): Extension {
    val config = newBuilder().apply {
        configFunc(this)
    }.build()
    return toExtensionProto(utpDependencies, Any.pack(config))
}
