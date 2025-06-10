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

package com.android.tools.journeys.testengine.robo

import androidx.test.tools.crawler.proto.CrawlGuidanceProto.CrawlParameter
import com.google.appcrawler.platform.client.GrpcClient
import com.google.cloud.test.appcrawler.proto.Artifact
import com.google.cloud.test.appcrawler.proto.ClientMetadata
import com.google.cloud.test.appcrawler.proto.CrawlSetup
import com.google.cloud.test.appcrawler.proto.RoboConfig
import com.google.common.base.Preconditions
import com.google.protobuf.ByteString
import com.google.protobuf.Duration
import io.grpc.CallCredentials
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.CompositeCallCredentials
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.inputStream

/**
 * Executes a journey by installing necessary components on a device,
 * establishing a connection with the crawler backend via gRPC, managing ADB forwarding,
 * and cleaning up the environment.
 *
 * @param adb An [Adb] instance for interacting with the target device.
 * @param crawlerAppApkPath Path to the crawler APK.
 * @param applicationId The package ID of the application under test.
 * @param appApkPath Path to the application under test APK.
 * @param accessTokenPath Path to obtain access token for establishing connection to backend.
 * @param channelProvider A lambda function to create a [ManagedChannel] for gRPC communication.
 */
class Proxy(
    private val adb: Adb,
    private val crawlerAppApkPath: String,
    private val applicationId: String,
    private val appApkPath: String,
    private val accessTokenPath: String,
    private val channelProvider: (String, String) -> ManagedChannel
) {

    /**
     * Executes a journey defined by the script at the given path.
     * Manages instrumentation setup, crawl execution, and cleanup.
     *
     * @param journeyPath Path to the file containing the journey script definition.
     * @param artifactProcessor A lambda function to process any [Artifact] produced during the crawl.
     * @throws JourneyExecutionException if any stage of the execution fails.
     */
    fun executeJourney(journeyPath: Path, artifactProcessor: (Artifact) -> Unit) {
        var hostPort = 0
        try {
            val journeyScript = readJourneyScript(journeyPath)
            val instrumentationProcess = setupInstrumentation()
            hostPort = setupAdbForward()
            val result = connectToCrawlerBackend(hostPort, journeyScript, accessTokenPath, artifactProcessor)
            if (result.outcome().equals(GrpcClient.SUCCESS_RESULT)) {
                stopInstrumentation(instrumentationProcess, true)
            } else {
                stopInstrumentation(instrumentationProcess, false)
            }
        } catch (e: JourneyExecutionException) {
            throw e
        } catch (e: Exception) {
            throw JourneyExecutionException(
                "An unexpected error occurred during journey execution: ${e.message}",
                e,
                JourneyFailureReason.UNKNOWN_FAILURE
            )
        } finally {
            cleanup(hostPort)
        }
    }

    /**
     * Reads journey script and converts it to roboscript.
     *
     * @param journeyPath The path to read joureny from.
     * @return The converted roboscript.
     */
    private fun readJourneyScript(journeyPath: Path): String {
        try {
            return journeyPath.inputStream().use { inputStream ->
                RoboConverter.convert(inputStream)
            }
        } catch (e: Exception) {
            throw JourneyExecutionException(
                "Failed to read journey: ${e.message}",
                e,
                JourneyFailureReason.JOURNEY_READ_FAILED
            )
        }
    }

    /**
     * Installs required apks and starts instrumentation
     *
     * @return The started instrumentation process.
     */
    private fun setupInstrumentation(): Process {
        try {
            // Install apks required for instrumentation process.
            val deviceApiLevel = adb.getDeviceApiLevel()
            if (deviceApiLevel >= 33) {
                // Disable "Unsafe app blocked" dialog. b/407500906.
                adb.setGlobalSettingsValue("verifier_verify_adb_installs", "0")
            }
            adb.install(crawlerAppApkPath, getCrawlerInstallFlags(deviceApiLevel))
            adb.install(appApkPath, getAppInstallFlags(deviceApiLevel))
        } catch (e: Exception) {
            throw JourneyExecutionException(
                "Installation failure: ${e.message}",
                e,
                JourneyFailureReason.ADB_INSTALL_FAILED
            )
        }

        try {
            val instrumentation = adb.runInstrumentation(
                RoboConfigConstants.CRAWLER_PACKAGE_ID,
                RoboConfigConstants.TEST_RUNNER_CLASS,
                args = mapOf(
                    RoboConfigConstants.ROBO_V2_APP_PACKAGE_FLAG to applicationId,
                    RoboConfigConstants.ROBO_V2_UI_AUTOMATOR_ONLY_MODE to "true",
                    RoboConfigConstants.ROBO_ADB_FORWARD to "true",
                    RoboConfigConstants.ROBO_PICK_OPEN_PORT to "true"
                )
            )
            return instrumentation
        } catch (e: Exception) {
            throw JourneyExecutionException(
                "Failed to run instrumentation: ${e.message}",
                e,
                JourneyFailureReason.INSTRUMENTATION_FAILED
            )
        }
    }

    /**
     * Provides a list of flags to use for installing the crawler app based on
     * device API level.
     *
     * @param deviceApiLevel The device API level to determine flags.
     * @return The list of install flags.
     */
    private fun getCrawlerInstallFlags(deviceApiLevel: Int): List<String> {
        // -r, -d: Allow re-install, downgrade
        val baseCrawlerInstallFlags = listOf("-r", "-d")
        val extraCrawlerInstallFlags = if (deviceApiLevel >= 34) {
            // -g: Grant permissions requested in manifest
            listOf("-g", "--bypass-low-target-sdk-block")
        } else if (deviceApiLevel >= 23) {
            listOf("-g")
        } else {
            emptyList()
        }
        return baseCrawlerInstallFlags + extraCrawlerInstallFlags
    }

    /**
     * Provides a list of flags to use for installing the app based on
     * device API level.
     *
     * @param deviceApiLevel The device API level to determine flags.
     * @return The list of install flags.
     */
    private fun getAppInstallFlags(deviceApiLevel: Int): List<String> {
        // -r, -d: Allow re-install, downgrade
        val baseInstallFlags = listOf("-r", "-d")
        val extraInstallFlags = if (deviceApiLevel >= 23) {
            // -g: Grant permissions requested in manifest
            listOf("-g")
        } else {
            emptyList()
        }
        return baseInstallFlags + extraInstallFlags
    }

    /**
     * Clean up by removing host forwarding and uninstalling apks.
     *
     * @param hostPort The host port to remove forwarding from.
     */
    private fun cleanup(hostPort: Int) {
        try {
            adb.removeForward(hostPort)
        } catch (e: Exception) {
            System.err.println(e.message)
        }

        try {
            adb.uninstall(applicationId)
        } catch (e: Exception) {
            System.err.println(e.message)
        }

        try {
            adb.uninstall(RoboConfigConstants.CRAWLER_PACKAGE_ID)
        } catch (e: Exception) {
            System.err.println(e.message)
        }
    }

    /**
     * Stops instrumentation process by checking for exit value.
     *
     * @param instrumentation The instrumentation process to wait for.
     * @param destroyIfActiveBeforeExit Whether to destroy the process before checking exit value
     * to exit gracefully. If crawl passed, we exit gracefully.
     */
    private fun stopInstrumentation(instrumentation: Process, destroyIfActiveBeforeExit: Boolean) {
        val stdout = instrumentation.inputStream.bufferedReader().use { it.readText() }
        val stderr = instrumentation.errorStream.bufferedReader().use { it.readText() }
        val fullOut = "$stdout\n$stderr"
        if (instrumentation.isAlive && destroyIfActiveBeforeExit) {
            instrumentation.destroy()
        }
        val exitValue = instrumentation.exitValue()

        val failed = exitValue != 0 || listOf(
            "failures",
            "INSTRUMENTATION_FAILED",
            "INSTRUMENTATION_CODE: 0"
        ).any { fullOut.contains(it, ignoreCase = true) }

        if (failed) {
            throw JourneyExecutionException(
                "Instrumentation failed (exit code: $exitValue). Output:\n$fullOut",
                reason = JourneyFailureReason.INSTRUMENTATION_FAILED
            )
        }
    }

    /**
     * Sets up ADB forwarding by finding an available host port and forwarding it
     * to the port used by the Robo service on the device. Retries if necessary.
     *
     * @return The host port used for ADB forwarding.
     */
    private fun setupAdbForward(): Int {
        val roboDevicePort = extractRoboPortNumber()
        if (roboDevicePort == 0) {
            throw JourneyExecutionException(
                "Could not determine Robo device port.",
                reason = JourneyFailureReason.ROBO_PORT_EXTRACTION_FAILED
            )
        }

        try {
            return retryIf(
                block = {
                    val hostPort = findAvailablePort()
                    if (hostPort != 0) {
                        adb.forward(hostPort, roboDevicePort)
                        hostPort
                    } else {
                        0
                    }
                },
                retryCondition = { hostPort -> hostPort == 0 },
                blockDoc = "setup adb forward",
                maxRetries = 5
            )
        } catch (e: Exception) {
            throw JourneyExecutionException(
                e.message,
                e,
                JourneyFailureReason.ADB_FORWARDING_FAILED
            )
        }
    }

    /**
     * Extracts the robo port number from the ADB dumpsys output for the proxy service.
     * Retries if the port is not found.
     *
     * @return The robo port number, or `0` if it cannot be extracted after retries.
     */
    private fun extractRoboPortNumber(): Int {
        try {
            return retryIf(
                block = {
                    val dumpsysOutput = adb.dumpsys(
                        "${RoboConfigConstants.CRAWLER_PACKAGE_ID}/${RoboConfigConstants.PROXY_SERVICE}"
                    )
                    val matcher =
                        RoboConfigConstants.ROBO_PROXY_PORT_IS_BOUND_PATTERN.matcher(dumpsysOutput)
                    if (!matcher.find()) {
                        0
                    } else {
                        matcher.group(1)?.toIntOrNull() ?: 0
                    }
                },
                retryCondition = { devicePort -> devicePort == 0 },
                blockDoc = "obtain device port",
                sleepBetweenRetries = 3000, // Wait 3 seconds for service to potentially bind port
                maxRetries = 10 // Retry up to 10 times (total 30 seconds wait)
            )
        } catch (e: Exception) {
            throw JourneyExecutionException(
                e.message,
                e,
                JourneyFailureReason.ROBO_PORT_EXTRACTION_FAILED
            )
        }
    }

    /**
     * Finds an available ephemeral port by binding a ServerSocket to port 0.
     *
     * @return An available port number, or 0 if an error occurs during binding.
     */
    private fun findAvailablePort(): Int {
        return try {
            ServerSocket(0).use {
                it.localPort
            }
        } catch (e: IOException) {
            0
        }
    }

    /**
     * Connects to the crawler backend via gRPC and starts the crawl process.
     *
     * @param hostPort The host port forwarded to the device's Robo service port.
     * @param journeyScript The journey script content as a string.
     * @param accessTokenPath Path to obtain access token for establishing connection to backend.
     * @param artifactProcessor A lambda function to process any [Artifact] produced during the crawl.
     */
    private fun connectToCrawlerBackend(
        hostPort: Int,
        journeyScript: String,
        accessTokenPath: String,
        artifactProcessor: (Artifact) -> Unit
    ): GrpcClient.Result {
        val crawlSetup =
            CrawlSetup.newBuilder()
                .setAppPackageId(applicationId)
                .setIdentifier(UUID.randomUUID().toString())
                .setClientMetadata(
                    ClientMetadata.newBuilder()
                        .setName("journeys-junit5-engine")
                        .build()
                )
                .putAssets(
                    RoboConfigConstants.ROBO_SCRIPT_CONFIG_NAME,
                    ByteString.copyFromUtf8(journeyScript)
                )
                .setTestTimeout(
                    Duration.newBuilder()
                        .setSeconds(RoboConfigConstants.TEST_TIMEOUT_SECONDS)
                        .build()
                )
                .setRoboConfig(RoboConfig.newBuilder().setUseNewExtensionHarness(true).build())
                .addCrawlParameters(
                    CrawlParameter.newBuilder()
                        .setName(RoboConfigConstants.ROBO_ADB_FORWARD)
                        .setValue("true")
                        .build()
                )
                .addCrawlParameters(
                    CrawlParameter.newBuilder()
                        .setName(RoboConfigConstants.ROBO_PICK_OPEN_PORT)
                        .setValue("true")
                        .build()
                )
                .build()

        val channel = channelProvider(RoboConfigConstants.CRAWLER_BACKEND_ENDPOINT, accessTokenPath)
        try {
            val grpcClient = GrpcClient(channel, null, artifactProcessor)
            try {
                val clientLogger = Logger.getLogger(
                    "${RoboConfigConstants.CRAWLER_PACKAGE_ID}.client.GrpcClient"
                )
                clientLogger.setLevel(Level.WARNING)
            } catch (e: Exception) {
                System.err.println("Failed to disable connection logs with error: ${e.message}")
            }

            return grpcClient.use { client ->
                client.startForward(
                    { hostPort },
                    crawlSetup,
                    RoboConfigConstants.DEFAULT_PLATFORM_TIMEOUT,
                    RoboConfigConstants.DEFAULT_RESPONSE_TIMEOUT,
                    RoboConfigConstants.DEFAULT_RESULTS_TIMEOUT
                )
            }
        } finally {
            channel.shutdown()
        }
    }

    /**
     * Retries executing the given function [block] until it meets the success condition
     * (i.e., `!retryCondition(result)` is true) or exceeds [maxRetries].
     *
     * @param T The return type of the function block.
     * @param block The function to execute and retry.
     * @param retryCondition A lambda that returns `true` if the result from `block` indicates a retry is needed.
     * @param blockDoc A description of the block's action for logging purposes.
     * @param sleepBetweenRetries Milliseconds to sleep between retry attempts.
     * @param maxRetries The maximum number of times to execute the block (including the first attempt).
     * @return The result of the [block] function on the first successful attempt.
     */
    private fun <T> retryIf(
        block: () -> T,
        retryCondition: (T) -> Boolean,
        blockDoc: String,
        sleepBetweenRetries: Long = 0,
        maxRetries: Int = 5
    ): T {
        for (attempt in 1..maxRetries) {
            val result = block()
            if (!retryCondition(result)) {
                return result
            }
            if (attempt < maxRetries) {
                if (sleepBetweenRetries > 0) {
                    Thread.sleep(sleepBetweenRetries)
                }
            }
        }
        throw IllegalStateException("Failed to $blockDoc after $maxRetries attempts.")
    }

    /**
     * A gRPC client interceptor that adds [CallCredentials] to outgoing calls.
     * Combines provided credentials with any existing credentials in [CallOptions].
     *
     * @property credentials The primary [CallCredentials] to add.
     */
    class CallCredentialsInterceptor(private val credentials: CallCredentials) : ClientInterceptor {

        init {
            Preconditions.checkNotNull(credentials, "credentials cannot be null")
        }

        override fun <ReqT, RespT> interceptCall(
            method: MethodDescriptor<ReqT, RespT>, callOptions: CallOptions, next: Channel
        ): ClientCall<ReqT, RespT> {
            var callCredentials = callOptions.credentials
            callCredentials = if (callCredentials == null) credentials
            else CompositeCallCredentials(credentials, callOptions.credentials)
            return next.newCall(method, callOptions.withCallCredentials(callCredentials))
        }
    }
}
