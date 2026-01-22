/*
 * Copyright (C) 2020 The Android Open Source Project
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
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.TextFormat.escapeDoubleQuotesAndBackslashes
import com.google.testing.platform.proto.api.config.RunnerConfigProto.RunnerConfig
import org.gradle.api.file.ConfigurableFileCollection
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Answers
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.absolutePathString

/**
 * Unit tests for UtpConfigFactory.kt.
 */
class UtpConfigFactoryTest {
    @get:Rule var temporaryFolder = TemporaryFolder()

    private val mockAppApk: File = mock()
    private val mockTestApk: File = mock()
    private val mockHelperApk: File = mock()
    private val mockOutputDir: File = mock()
    private val mockCoverageOutputDir: File = mock()
    private val mockTmpDir: File = mock()
    private val mockEmulatorControlConfig: EmulatorControlConfig = mock()
    private lateinit var mockDependencyApks: List<List<Path>>
    private lateinit var testTargetApkConfigBundle: TargetApkConfigBundle

    private val testData = TestData(
        applicationId = "com.example.application.test",
        testedApplicationId = "com.example.application",
        instrumentationTargetPackageId = "com.example.application",
        instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
        instrumentationRunnerArguments = emptyMap(),
        animationsDisabled = false,
        isTestCoverageEnabled = false,
        testApk = mockFile("testApk.apk"),
    )

    private val utpDependencies: UtpDependencies = mock<UtpDependencies>(defaultAnswer = {
        val mockFileCollection = mock<ConfigurableFileCollection>()
        val mockJar = mockFile("path-to-${it.method.name.removePrefix("get")}.jar")
        whenever(mockFileCollection.files).thenReturn(setOf(mockJar))
        mockFileCollection
    })

    private val mockDependencyApkPath = mockPath("mockDependencyApkPath")

    companion object {
        private fun mockFile(absolutePath: String): File = mock<File>().also {
            whenever(it.absolutePath).thenReturn(absolutePath)
        }

        private fun mockPath(absolutePath: String): Path =
            mock<Path>(defaultAnswer = Answers.RETURNS_DEEP_STUBS).also {
                whenever(it.absolutePathString()).thenReturn(absolutePath)
            }
    }

    @Before
    fun setupMocks() {
        whenever(mockOutputDir.absolutePath).thenReturn("mockOutputDirPath")
        whenever(mockCoverageOutputDir.absolutePath).thenReturn("mockCoverageOutputDir")
        whenever(mockTmpDir.absolutePath).thenReturn("mockTmpDirPath")
        whenever(mockAppApk.absolutePath).thenReturn("mockAppApkPath")
        whenever(mockTestApk.absolutePath).thenReturn("mockTestApkPath")
        whenever(mockHelperApk.absolutePath).thenReturn("mockHelperApkPath")

        mockDependencyApks = listOf(listOf(mockPath("mockDependencyApkPath")))
        testTargetApkConfigBundle = TargetApkConfigBundle(
            appApks = listOf(mockAppApk, mockTestApk),
            isSplitApk = false
        )
    }

    @Before
    fun setupForEmulatorAccess() {
        // We write a "fake" discover file that indicate we have security features enabled.
        val discoveryDirectory = computeRegistrationDirectoryContainer()!!.resolve("avd/running/")
        if (!discoveryDirectory.toFile().exists()) {
            discoveryDirectory.toFile().mkdirs()
        }
        val filePath = discoveryDirectory.resolve("pid_123.ini")
        val jwkFolder = temporaryFolder.newFolder("jwks")
        val content = """
            port.serial=mockDeviceSerialNumber
            grpc.port=1234
            grpc.jwks=${jwkFolder}
            grpc.allowlist=/unused/access.json
        """.trimIndent()
        Files.writeString(filePath, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE)
    }

    private fun createForLocalDevice(
        testData: TestData = this.testData,
        useOrchestrator: Boolean = false,
        forceCompilation: Boolean = false,
        uninstallIncompatibleApks: Boolean = false,
        additionalTestOutputDir: File? = null,
        additionalTestOutputOnDeviceDir: String? = null,
        installApkTimeout: Int? = null,
        shardConfig: ShardConfig? = null,
        targetApkConfigBundle: TargetApkConfigBundle = testTargetApkConfigBundle,
        dependencyApks: List<List<Path>> = mockDependencyApks,
        cleanTestArtifacts: Boolean = false,
        reinstallIncompatibleApksBeforeTest: Boolean = false,
    ): RunnerConfig {
        return createRunnerConfigProtoForLocalDevice(
            "mockDeviceID",
            "deviceName",
            "deviceShardName",
            "projectPath",
            "variantName",
            "emulator-mockDeviceSerialNumber",
            testData,
            targetApkConfigBundle,
            listOf("-additional_install_option"),
            listOf(mockHelperApk),
            uninstallIncompatibleApks,
            utpDependencies,
            "mockSdkDirPath",
            "mockAdbPath",
            "mockAaptPath",
            "mockDexdumpPath",
            mockOutputDir,
            mockTmpDir,
            mockEmulatorControlConfig,
            mockCoverageOutputDir,
            useOrchestrator,
            forceCompilation,
            additionalTestOutputDir,
            additionalTestOutputOnDeviceDir,
            installApkTimeout,
            dependencyApks,
            cleanTestArtifacts,
            reinstallIncompatibleApksBeforeTest,
            shardConfig,
            enableUtpTestReportingForAndroidStudio = true,
            mockFile("xmlTestReportOutputDirectory"),
            mockFile("utpResultProtoOutputFile"),
        )
    }

    private fun createForManagedDevice(
        testData: TestData = this.testData,
        useOrchestrator: Boolean = false,
        forceCompilation: Boolean = false,
        additionalTestOutputDir: File? = null,
        additionalTestOutputOnDeviceDir: String? = null,
        shardConfig: ShardConfig? = null,
        installApkTimeout: Int? = null,
        targetApkConfigBundle: TargetApkConfigBundle = testTargetApkConfigBundle,
    ): RunnerConfig {
        return createRunnerConfigProtoForLocalDevice(
            "mockDeviceID",
            "deviceName",
            "deviceShardName",
            "projectPath",
            "variantName",
            "emulator-mockDeviceSerialNumber",
            testData,
            targetApkConfigBundle,
            listOf("-additional_install_option"),
            listOf(mockHelperApk),
            uninstallIncompatibleApks = true,
            utpDependencies,
            "mockSdkDirPath",
            "mockAdbPath",
            "mockAaptPath",
            "mockDexdumpPath",
            mockOutputDir,
            mockTmpDir,
            mockEmulatorControlConfig,
            mockCoverageOutputDir,
            useOrchestrator,
            forceCompilation,
            additionalTestOutputDir,
            additionalTestOutputOnDeviceDir,
            installApkTimeout,
            mockDependencyApks,
            uninstallApksAfterTest = false,
            reinstallIncompatibleApksBeforeTest = true,
            shardConfig,
            enableUtpTestReportingForAndroidStudio = true,
            mockFile("xmlTestReportOutputDirectory"),
            mockFile("utpResultProtoOutputFile"),
        )
    }

    @Test
    fun createRunnerConfigProtoForLocalDevice() {
        val runnerConfigProto = createForLocalDevice()
        assertRunnerConfigProto(runnerConfigProto)
    }

    @Test
    fun createRunnerConfigProtoForLocalDeviceWithSplitApk() {
        val runnerConfigProto = createForLocalDevice(
            targetApkConfigBundle = TargetApkConfigBundle(
                appApks = listOf(mockAppApk, mockTestApk),
                isSplitApk = true
            )
        )
        assertRunnerConfigProto(
            runnerConfig = runnerConfigProto,
            isSplitApk = true
        )
    }
    @Test
    fun createRunnerConfigProtoForLocalDeviceUseOrchestrator() {
        val runnerConfigProto = createForLocalDevice(useOrchestrator = true)
        assertRunnerConfigProto(runnerConfigProto, useOrchestrator = true)
    }

    @Test
    fun createRunnerConfigProtoForLocalDeviceInstallApkTimeout() {
        val runnerConfigProto = createForLocalDevice(installApkTimeout = 5)
        assertRunnerConfigProto(runnerConfigProto, installApkTimeout = 5)
    }

    @Test
    fun createRunnerConfigProtoForLocalDeviceInstallApkWithFullForceCompilation() {
        val runnerConfigProto = createForLocalDevice(forceCompilation = true)
        assertRunnerConfigProto(runnerConfigProto, forceCompilation = true)
    }

    @Test
    fun createRunnerConfigProtoForManagedDeviceInstallApkWithFullForceCompilation() {
        val runnerConfigProto = createForManagedDevice(forceCompilation = true)
        assertRunnerConfigProto(
            runnerConfigProto,
            forceCompilation = true,
            isForceReinstallBeforeTest = true,
            uninstallIncompatibleApks = true,
        )
    }

    @Test
    fun createRunnerConfigProtoForLocalDeviceWithNoAnimation() {
        val runnerConfigProto = createForLocalDevice(
            testData = testData.copy(
                animationsDisabled = true
            )
        )

        assertRunnerConfigProto(
            runnerConfigProto,
            noWindowAnimation = true)
    }

    @Test
    fun createRunnerConfigProtoWithEmulatorAccess() {
        whenever(mockEmulatorControlConfig.enabled).thenReturn(true)
        whenever(mockEmulatorControlConfig.secondsValid).thenReturn(100)

        assertThat(mockEmulatorControlConfig.enabled).isTrue()

        val runnerConfigProto = createForLocalDevice(dependencyApks = mockDependencyApks)

        val (token, jwkfile) = extractJwkFileInfo(runnerConfigProto)

        assertRunnerConfigProto(
            runnerConfigProto,
            instrumentationArgs = mapOf("grpc.port" to "1234", "grpc.token" to token),
            emulatorControlConfig = """
                emulator_grpc_port: 1234
                token: "${token}"
                jwk_file: "${jwkfile}"
                seconds_valid: 100
            """
        )
    }

    private fun extractJwkFileInfo(runnerConfigProto: RunnerConfig): Pair<String, String> {
        // We extract the token and jkwfile as those are dynamically created.
        val printed = printProto(runnerConfigProto)
        val tokenRegex = "token: \"(.*)\""
        val jwkfileRegex = "jwk_file: \"(.*)\""

        // Both the token and the location where we wrote the file should be set.
        assertThat(printed).containsMatch(tokenRegex)
        assertThat(printed).containsMatch(jwkfileRegex)

        // Let's extract them.
        val token = tokenRegex.toRegex().find(printed)?.groupValues?.getOrNull(1) ?: "Not Found"
        val jwkfile = jwkfileRegex.toRegex().find(printed)?.groupValues?.getOrNull(1) ?: "Not found"

        return token to jwkfile
    }

    @Test
    fun createRunnerConfigProtoWithEmulatorAccessForManagedDevice() {
        whenever(mockEmulatorControlConfig.enabled).thenReturn(true)
        whenever(mockEmulatorControlConfig.secondsValid).thenReturn(100)
        whenever(mockEmulatorControlConfig.allowedEndpoints).thenReturn(setOf("a", "b"))

        assertThat(mockEmulatorControlConfig.enabled).isTrue()

        val runnerConfigProto = createForManagedDevice()

        val (token, jwkfile) = extractJwkFileInfo(runnerConfigProto)

        assertRunnerConfigProto(
            runnerConfigProto,
            isForceReinstallBeforeTest = true,
            uninstallIncompatibleApks = true,
            instrumentationArgs = mapOf("grpc.port" to "1234", "grpc.token" to token),
            emulatorControlConfig = """
                emulator_grpc_port: 1234
                token: "${token}"
                jwk_file: "${jwkfile}"
                seconds_valid: 100
                allowed_endpoints: "a"
                allowed_endpoints: "b"
            """
        )
    }

    @Test
    fun createRunnerConfigProtoForManagedDevice() {
        val runnerConfigProto = createForManagedDevice()

        assertRunnerConfigProto(
            runnerConfigProto,
            isForceReinstallBeforeTest = true,
            uninstallIncompatibleApks = true,
        )
    }

    @Test
    fun createRunnerConfigProtoForManagedDeviceWithSplitApk() {
        val runnerConfigProto = createForManagedDevice(
            targetApkConfigBundle = TargetApkConfigBundle(
                appApks = listOf(mockAppApk, mockTestApk),
                isSplitApk = true
            )
        )

        assertRunnerConfigProto(
            runnerConfigProto,
            isForceReinstallBeforeTest = true,
            isSplitApk = true,
            uninstallIncompatibleApks = true,
        )
    }

    @Test
    fun createRunnerConfigProtoForManagedDeviceUseOrchestrator() {
        val runnerConfigProto = createForManagedDevice(useOrchestrator = true)

        assertRunnerConfigProto(
            runnerConfigProto,
            useOrchestrator = true,
            isForceReinstallBeforeTest = true,
            uninstallIncompatibleApks = true,
        )
    }

    @Test
    fun createRunnerConfigProtoForManagedDeviceInstallApkTimeout() {
        val runnerConfigProto = createForManagedDevice(installApkTimeout = 5)
        assertRunnerConfigProto(
            runnerConfigProto,
            isForceReinstallBeforeTest = true,
            installApkTimeout = 5,
            uninstallIncompatibleApks = true,
        )
    }

    @Test
    fun createRunnerConfigProtoForLocalDeviceWithTestCoverage() {
        val runnerConfigProto = createForLocalDevice(
            testData = testData.copy(isTestCoverageEnabled = true),
            dependencyApks = mockDependencyApks
        )

        val outputOnHost = "mockCoverageOutputDir${File.separator}"
        assertRunnerConfigProto(
            runnerConfigProto,
            instrumentationArgs = mapOf(
                "coverage" to "true",
                "coverageFile" to "/data/data/com.example.application/coverage.ec",
            ),
            testCoverageConfig = """
                single_coverage_file: "/data/data/com.example.application/coverage.ec"
                run_as_package_name: "com.example.application"
                output_directory_on_host: "${escapeDoubleQuotesAndBackslashes(outputOnHost)}"
            """
        )
    }

    @Test
    fun createRunnerConfigProtoForLocalDeviceWithTestCoverageAndOrchestrator() {
        val runnerConfigProto = createForLocalDevice(
            testData = testData.copy(isTestCoverageEnabled = true),
            useOrchestrator = true,
            dependencyApks = mockDependencyApks
        )

        val outputOnHost = "mockCoverageOutputDir${File.separator}"
        assertRunnerConfigProto(
            runnerConfigProto,
            useOrchestrator = true,
            instrumentationArgs = mapOf(
                "coverage" to "true",
                "coverageFilePath" to "/data/data/com.example.application/coverage_data/",
            ),
            testCoverageConfig = """
                multiple_coverage_files_in_directory: "/data/data/com.example.application/coverage_data/"
                run_as_package_name: "com.example.application"
                output_directory_on_host: "${escapeDoubleQuotesAndBackslashes(outputOnHost)}"
            """
        )
    }

    @Test
    fun createRunnerConfigProtoForLocalDeviceWithTestCoverageAndTestStorageService() {
        val runnerConfigProto = createForLocalDevice(
            testData = testData.copy(
                isTestCoverageEnabled = true,
                instrumentationRunnerArguments = mapOf("useTestStorageService" to "true")),
            dependencyApks = mockDependencyApks
        )

        val outputOnHost = "mockCoverageOutputDir${File.separator}"
        assertRunnerConfigProto(
            runnerConfigProto,
            useTestStorageService = true,
            instrumentationArgs = mapOf(
                "coverage" to "true",
                "coverageFile" to "/data/data/com.example.application/coverage.ec",
                "useTestStorageService" to "true",
            ),
            testCoverageConfig = """
                single_coverage_file: "/data/data/com.example.application/coverage.ec"
                run_as_package_name: "com.example.application"
                output_directory_on_host: "${escapeDoubleQuotesAndBackslashes(outputOnHost)}"
                use_test_storage_service: true
            """
        )
    }

    @Test
    fun createRunnerConfigProtoForManagedDeviceWithTestCoverage() {
        val runnerConfigProto = createForManagedDevice(
            testData = testData.copy(isTestCoverageEnabled = true)
        )

        val outputOnHost = "mockCoverageOutputDir${File.separator}"
        assertRunnerConfigProto(
            runnerConfigProto,
            isForceReinstallBeforeTest = true,
            uninstallIncompatibleApks = true,
            instrumentationArgs = mapOf(
                "coverage" to "true",
                "coverageFile" to "/data/data/com.example.application/coverage.ec",
            ),
            testCoverageConfig = """
                single_coverage_file: "/data/data/com.example.application/coverage.ec"
                run_as_package_name: "com.example.application"
                output_directory_on_host: "${escapeDoubleQuotesAndBackslashes(outputOnHost)}"
            """
        )
    }

    @Test
    fun createRunnerConfigProtoForLocalDeviceWithShardConfig() {
        val runnerConfigProto = createForLocalDevice(
            shardConfig = ShardConfig(totalCount = 10, index = 2),
            dependencyApks = mockDependencyApks
        )
        assertRunnerConfigProto(
            runnerConfigProto,
            // TODO(b/201577913): remove
            instrumentationArgs = mapOf(
                "numShards" to "10",
                "shardIndex" to "2"
            ),
            shardingConfig = """
                shard_count: 10
                shard_index: 2
            """
        )
    }

    @Test
    fun createRunnerConfigProtoForLocalDeviceWithUninstallIncompatibleApks() {
        val runnerConfigProto = createForLocalDevice(
            uninstallIncompatibleApks = true,
            dependencyApks = mockDependencyApks
        )
        assertRunnerConfigProto(
            runnerConfigProto,
            uninstallIncompatibleApks = true,
        )
    }

    @Test
    fun createLocalDeviceRunnerConfigProtoToUninstallApksAfterTest() {
        val runnerConfigProto = createForLocalDevice(
            dependencyApks = mockDependencyApks,
            cleanTestArtifacts = true
        )
        assertRunnerConfigProto(
            runnerConfigProto,
            isUninstallAfterTest = true,
        )
    }

    @Test
    fun createRunnerConfigProtoForManagedDeviceWithShardConfig() {
        val runnerConfigProto = createForManagedDevice(
            shardConfig = ShardConfig(totalCount = 10, index = 2))
        assertRunnerConfigProto(
            runnerConfigProto,
            // TODO(b/201577913): remove
            instrumentationArgs = mapOf(
                "numShards" to "10",
                "shardIndex" to "2"
            ),
            isForceReinstallBeforeTest = true,
            uninstallIncompatibleApks = true,
            shardingConfig = """
                shard_count: 10
                shard_index: 2
            """
        )
    }

    @Test
    fun userSuppliedShardArgsAreNotSupportedWithShardConfig() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            createForManagedDevice(
                testData = testData.copy(instrumentationRunnerArguments = mapOf("numShards" to "2")),
                shardConfig = ShardConfig(totalCount = 10, index = 2))
        }
        assertThat(exception).hasMessageThat().contains(
            "testInstrumentationRunnerArguments.[numShards | shardIndex] is currently incompatible")
    }

    @Test
    fun createRunnerConfigProtoForLocalDeviceWithAdditionalTestOutput() {
        val onDeviceDir = "/sdcard/Android/media/com.example.application/additional_test_output"
        val runnerConfigProto = createForLocalDevice(
            additionalTestOutputDir = mockFile("additionalTestOutputDir"),
            additionalTestOutputOnDeviceDir = onDeviceDir,
            dependencyApks = mockDependencyApks,
        )

        val onHostDir = "additionalTestOutputDir${File.separator}"
        assertRunnerConfigProto(
            runnerConfigProto,
            instrumentationArgs = mapOf(
                "additionalTestOutputDir" to onDeviceDir,
            ),
            additionalTestOutputConfig = """
               additional_output_directory_on_device: "${onDeviceDir}"
               additional_output_directory_on_host: "${escapeDoubleQuotesAndBackslashes(onHostDir)}"
            """
        )
    }

    @Test
    fun createRunnerConfigProtoForManagedDeviceWithAdditionalTestOutput() {
        val onDeviceDir = "/sdcard/Android/media/com.example.application/additional_test_output"
        val runnerConfigProto = createForManagedDevice(
            additionalTestOutputDir = mockFile("additionalTestOutputDir"),
            additionalTestOutputOnDeviceDir = onDeviceDir,
        )

        val onHostDir = "additionalTestOutputDir${File.separator}"
        assertRunnerConfigProto(
            runnerConfigProto,
            isForceReinstallBeforeTest = true,
            uninstallIncompatibleApks = true,
            instrumentationArgs = mapOf(
                "additionalTestOutputDir" to onDeviceDir,
            ),
            additionalTestOutputConfig = """
               additional_output_directory_on_device: "${onDeviceDir}"
               additional_output_directory_on_host: "${escapeDoubleQuotesAndBackslashes(onHostDir)}"
            """
        )
    }

    @Test
    fun multipleDependencyApk() {
        val mockPath1 = mockPath("mockDependencyApkPath1")
        val mockPath2 = mockPath("mockDependencyApkPath2")
        val dependencyApksPaths = listOf(listOf(mockPath1, mockPath2))

        val runnerConfigProto = createForLocalDevice(dependencyApks = dependencyApksPaths)
        assertRunnerConfigProto(
            runnerConfigProto,
            isDependencyApkSplit = true)
    }
}
