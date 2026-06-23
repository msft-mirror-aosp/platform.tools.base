/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tools.utp.plugins.host.coverage.com.android.tools.utp.plugins.host.coverage

import com.android.tools.utp.plugins.host.coverage.AndroidTestCoveragePlugin
import com.android.tools.utp.plugins.host.coverage.proto.AndroidTestCoverageConfigProto.AndroidTestCoverageConfig
import com.google.protobuf.Any
import com.google.testing.platform.api.config.ProtoConfig
import com.google.testing.platform.api.context.Context
import com.google.testing.platform.api.device.CommandResult
import com.google.testing.platform.api.device.Device
import com.google.testing.platform.api.device.DeviceController
import com.google.testing.platform.proto.api.core.ExtensionProto
import com.google.testing.platform.proto.api.core.TestArtifactProto.Artifact
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import com.google.testing.platform.runtime.android.device.AndroidDeviceProperties
import java.util.function.Supplier
import java.util.logging.Logger
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.lenient
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.quality.Strictness

/** Unit tests for [AndroidTestCoveragePlugin] */
@RunWith(JUnit4::class)
class AndroidTestCoveragePluginTest {

  companion object {
    private const val TESTED_APP = "com.example.application"
    private const val TEST_STORAGE_SERVICE_OUTPUT_DIR = "/sdcard/googletest/internal_use/"
  }

  @get:Rule var mockitoJUnitRule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

  @Mock private lateinit var mockDeviceController: DeviceController
  @Mock private lateinit var mockLogger: Logger

  private var commandResult: CommandResult = CommandResult(0, listOf())

  @Before
  fun setUpMocks() {
    lenient().`when`(mockDeviceController.execute(anyList(), anyOrNull())).then { commandResult }
  }

  private fun installTestStorageService() {
    `when`(mockDeviceController.execute(eq(listOf("shell", "pm", "list", "packages", "androidx.test.services")), anyOrNull()))
      .thenReturn(CommandResult(0, listOf("package:androidx.test.services")))

    val mockDevice = mock<Device>()
    `when`(mockDevice.properties).thenReturn(AndroidDeviceProperties(deviceApiLevel = "30"))
    `when`(mockDeviceController.getDevice()).thenReturn(mockDevice)
  }

  private fun createAndroidTestCoveragePlugin(config: AndroidTestCoverageConfig): AndroidTestCoveragePlugin {
    val packedConfig = Any.pack(config)
    val protoConfig =
      object : ProtoConfig {
        override val configProto: Any
          get() = packedConfig

        override val configResource: ExtensionProto.ConfigResource?
          get() = null
      }
    val context = mock<Context>()
    `when`(context[Context.CONFIG_KEY]).thenReturn(protoConfig)
    return AndroidTestCoveragePlugin(mockLogger) { "UUID" }.apply { configure(context) }
  }

  private fun runAndroidTestCoveragePlugin(emptyTestResults: Boolean = false, configFunc: AndroidTestCoverageConfig.Builder.() -> Unit) {
    val config = AndroidTestCoverageConfig.newBuilder().apply { configFunc(this) }.build()
    createAndroidTestCoveragePlugin(config).apply {
      beforeAll(mockDeviceController)
      beforeEach(null, mockDeviceController)
      afterEach(TestResult.getDefaultInstance(), mockDeviceController)
      afterAll(
        TestSuiteResult.newBuilder()
          .apply {
            if (!emptyTestResults) {
              addTestResultBuilder()
            }
          }
          .build(),
        mockDeviceController,
      )
    }
  }

  private fun createTestArtifact(filePathOnDevice: String, filePathOnHost: String): Artifact {
    return Artifact.newBuilder()
      .apply {
        sourcePathBuilder.path = filePathOnHost
        destinationPathBuilder.path = filePathOnDevice
      }
      .build()
  }

  @Test
  fun runWithSingleCoverageFile() {
    val coverageFile = "/data/data/${TESTED_APP}/coverage.ec"
    val tmpDir = "/data/local/tmp/UUID-coverage_data"
    val outputDir = "coverageOutputDir/deviceName/"

    runAndroidTestCoveragePlugin() {
      runAsPackageName = TESTED_APP
      singleCoverageFile = coverageFile
      outputDirectoryOnHost = outputDir
    }

    inOrder(mockDeviceController).apply {
      verify(mockDeviceController).execute(eq(listOf("shell", "run-as", TESTED_APP, "rm", "-f", "\"${coverageFile}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "mkdir", "-p", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "chmod", "777", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController)
        .execute(eq(listOf("shell", "run-as", TESTED_APP, "cat", "\"${coverageFile}\"", ">", "\"${tmpDir}/coverage.ec\"")), anyOrNull())
      verify(mockDeviceController).pull(createTestArtifact("${tmpDir}/coverage.ec", "${outputDir}/coverage.ec"))
      verify(mockDeviceController).execute(eq(listOf("shell", "rm", "-rf", "\"${tmpDir}\"")), anyOrNull())
      verifyNoMoreInteractions()
    }

    verifyNoMoreInteractions(mockLogger)
  }

  @Test
  fun runWithSingleCoverageFileAndTestStorageService() {
    val coverageFile = "/data/data/${TESTED_APP}/coverage.ec"
    val tmpDir = "/data/local/tmp/UUID-coverage_data"
    val outputDir = "coverageOutputDir/deviceName/"

    installTestStorageService()

    runAndroidTestCoveragePlugin() {
      runAsPackageName = TESTED_APP
      singleCoverageFile = coverageFile
      outputDirectoryOnHost = outputDir
      useTestStorageService = true
    }

    val storageDir = TEST_STORAGE_SERVICE_OUTPUT_DIR.removeSuffix("/")
    val prefixedPath = "${storageDir}/${coverageFile.removePrefix("/")}"

    inOrder(mockDeviceController).apply {
      verify(mockDeviceController).execute(eq(listOf("shell", "pm", "list", "packages", "androidx.test.services")), anyOrNull())
      verify(mockDeviceController).getDevice()
      verify(mockDeviceController)
        .execute(eq(listOf("shell", "appops", "set", "androidx.test.services", "MANAGE_EXTERNAL_STORAGE", "allow")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "rm", "-f", "\"${prefixedPath}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "mkdir", "-p", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "chmod", "777", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "cat", "\"${prefixedPath}\"", ">", "\"${tmpDir}/coverage.ec\"")), anyOrNull())
      verify(mockDeviceController).pull(createTestArtifact("${tmpDir}/coverage.ec", "${outputDir}/coverage.ec"))
      verify(mockDeviceController).execute(eq(listOf("shell", "rm", "-rf", "\"${tmpDir}\"")), anyOrNull())
      verifyNoMoreInteractions()
    }

    verifyNoMoreInteractions(mockLogger)
  }

  @Test
  fun runWithMultipleCoverageFilesInDirectory() {
    val coverageDir = "/data/data/${TESTED_APP}/"
    val tmpDir = "/data/local/tmp/UUID-coverage_data"
    val outputDir = "coverageOutputDir/deviceName/"

    `when`(mockDeviceController.execute(eq(listOf("shell", "run-as", TESTED_APP, "ls", "\"${coverageDir}\"", "|", "cat")), anyOrNull()))
      .thenReturn(CommandResult(0, listOf("coverage1.ec", "coverage2.ec", "non_cov_file")))

    runAndroidTestCoveragePlugin() {
      runAsPackageName = TESTED_APP
      multipleCoverageFilesInDirectory = coverageDir
      outputDirectoryOnHost = outputDir
    }

    inOrder(mockDeviceController).apply {
      verify(mockDeviceController).execute(eq(listOf("shell", "run-as", TESTED_APP, "rm", "-rf", "\"${coverageDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "run-as", TESTED_APP, "mkdir", "-p", "\"${coverageDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "mkdir", "-p", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "chmod", "777", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "run-as", TESTED_APP, "ls", "\"${coverageDir}\"", "|", "cat")), anyOrNull())
      for (i in 1..2) {
        verify(mockDeviceController)
          .execute(
            eq(listOf("shell", "run-as", TESTED_APP, "cat", "\"${coverageDir}/coverage$i.ec\"", ">", "\"${tmpDir}/coverage$i.ec\"")),
            anyOrNull(),
          )
        verify(mockDeviceController).pull(createTestArtifact("${tmpDir}/coverage$i.ec", "${outputDir}/coverage$i.ec"))
      }
      verify(mockDeviceController).execute(eq(listOf("shell", "rm", "-rf", "\"${tmpDir}\"")), anyOrNull())
      verifyNoMoreInteractions()
    }

    verifyNoMoreInteractions(mockLogger)
  }

  @Test
  fun runWithMultipleCoverageFilesInDirectoryAndStorageService() {
    val coverageDir = "/data/data/${TESTED_APP}/"
    val tmpDir = "/data/local/tmp/UUID-coverage_data"
    val outputDir = "coverageOutputDir/deviceName/"

    installTestStorageService()

    val storageDir = TEST_STORAGE_SERVICE_OUTPUT_DIR.removeSuffix("/")
    val prefixedPath = "${storageDir}/${coverageDir.removePrefix("/")}"

    `when`(mockDeviceController.execute(eq(listOf("shell", "ls", "\"${prefixedPath}\"", "|", "cat")), anyOrNull()))
      .thenReturn(CommandResult(0, listOf("coverage1.ec", "coverage2.ec", "non_cov_file")))

    runAndroidTestCoveragePlugin() {
      runAsPackageName = TESTED_APP
      multipleCoverageFilesInDirectory = coverageDir
      outputDirectoryOnHost = outputDir
      useTestStorageService = true
    }

    inOrder(mockDeviceController).apply {
      verify(mockDeviceController).execute(eq(listOf("shell", "pm", "list", "packages", "androidx.test.services")), anyOrNull())
      verify(mockDeviceController).getDevice()
      verify(mockDeviceController)
        .execute(eq(listOf("shell", "appops", "set", "androidx.test.services", "MANAGE_EXTERNAL_STORAGE", "allow")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "rm", "-rf", "\"${prefixedPath}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "mkdir", "-p", "\"${prefixedPath}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "mkdir", "-p", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "chmod", "777", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "ls", "\"${prefixedPath}\"", "|", "cat")), anyOrNull())
      for (i in 1..2) {
        verify(mockDeviceController)
          .execute(eq(listOf("shell", "cat", "\"${prefixedPath}/coverage$i.ec\"", ">", "\"${tmpDir}/coverage$i.ec\"")), anyOrNull())
        verify(mockDeviceController).pull(createTestArtifact("${tmpDir}/coverage$i.ec", "${outputDir}/coverage$i.ec"))
      }
      verify(mockDeviceController).execute(eq(listOf("shell", "rm", "-rf", "\"${tmpDir}\"")), anyOrNull())
      verifyNoMoreInteractions()
    }

    verifyNoMoreInteractions(mockLogger)
  }

  @Test
  fun commandFailed() {
    val coverageFile = "/data/data/${TESTED_APP}/coverage.ec"
    val outputDir = "coverageOutputDir/deviceName/"

    commandResult = CommandResult(-1, listOf())

    runAndroidTestCoveragePlugin() {
      runAsPackageName = TESTED_APP
      singleCoverageFile = coverageFile
      outputDirectoryOnHost = outputDir
    }

    verify(mockLogger, atLeastOnce()).warning(argThat<Supplier<String>> { get().contains("Shell command failed (-1)") })
  }

  @Test
  fun useTestStorageServiceIsRequestedButNotInstalled() {
    val coverageFile = "/data/data/${TESTED_APP}/coverage.ec"
    val tmpDir = "/data/local/tmp/UUID-coverage_data"
    val outputDir = "coverageOutputDir/deviceName/"

    runAndroidTestCoveragePlugin() {
      runAsPackageName = TESTED_APP
      singleCoverageFile = coverageFile
      outputDirectoryOnHost = outputDir
      useTestStorageService = true
    }

    inOrder(mockDeviceController, mockLogger).apply {
      verify(mockDeviceController).execute(eq(listOf("shell", "pm", "list", "packages", "androidx.test.services")), anyOrNull())
      verify(mockLogger).warning(contains("TestStorageService is not installed"))
      verify(mockDeviceController).execute(eq(listOf("shell", "run-as", TESTED_APP, "rm", "-f", "\"${coverageFile}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "mkdir", "-p", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "chmod", "777", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController)
        .execute(eq(listOf("shell", "run-as", TESTED_APP, "cat", "\"${coverageFile}\"", ">", "\"${tmpDir}/coverage.ec\"")), anyOrNull())
      verify(mockDeviceController).pull(createTestArtifact("${tmpDir}/coverage.ec", "${outputDir}/coverage.ec"))
      verify(mockDeviceController).execute(eq(listOf("shell", "rm", "-rf", "\"${tmpDir}\"")), anyOrNull())
      verifyNoMoreInteractions()
    }

    verifyNoMoreInteractions(mockLogger)
  }

  @Test
  fun noWarningsWhenTestResultsAreEmpty() {
    val coverageFile = "/data/data/${TESTED_APP}/coverage.ec"
    val outputDir = "coverageOutputDir/deviceName/"

    runAndroidTestCoveragePlugin(emptyTestResults = true) {
      runAsPackageName = TESTED_APP
      singleCoverageFile = coverageFile
      outputDirectoryOnHost = outputDir
    }

    inOrder(mockDeviceController).apply {
      verify(mockDeviceController).execute(eq(listOf("shell", "run-as", TESTED_APP, "rm", "-f", "\"${coverageFile}\"")), anyOrNull())
      verifyNoMoreInteractions()
    }

    verifyNoMoreInteractions(mockLogger)
  }

  @Test(expected = IllegalArgumentException::class)
  fun beforeAllThrowsIfHostPathNotNormalized() {
    runAndroidTestCoveragePlugin { outputDirectoryOnHost = "/path/to/../dir" }
  }

  @Test
  fun runWithMultipleCoverageFilesInDirectorySkipsUnsafeFiles() {
    val coverageDir = "/data/data/${TESTED_APP}/"
    val tmpDir = "/data/local/tmp/UUID-coverage_data"
    val outputDir = "coverageOutputDir/deviceName/"

    `when`(mockDeviceController.execute(eq(listOf("shell", "run-as", TESTED_APP, "ls", "\"${coverageDir}\"", "|", "cat")), anyOrNull()))
      .thenReturn(CommandResult(0, listOf("coverage1.ec", "..", "../unsafe.ec", "coverage2.ec")))

    runAndroidTestCoveragePlugin {
      runAsPackageName = TESTED_APP
      multipleCoverageFilesInDirectory = coverageDir
      outputDirectoryOnHost = outputDir
    }

    inOrder(mockDeviceController).apply {
      verify(mockDeviceController).execute(eq(listOf("shell", "run-as", TESTED_APP, "rm", "-rf", "\"${coverageDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "run-as", TESTED_APP, "mkdir", "-p", "\"${coverageDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "mkdir", "-p", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "chmod", "777", "\"${tmpDir}\"")), anyOrNull())
      verify(mockDeviceController).execute(eq(listOf("shell", "run-as", TESTED_APP, "ls", "\"${coverageDir}\"", "|", "cat")), anyOrNull())

      verify(mockDeviceController)
        .execute(
          eq(listOf("shell", "run-as", TESTED_APP, "cat", "\"${coverageDir}/coverage1.ec\"", ">", "\"${tmpDir}/coverage1.ec\"")),
          anyOrNull(),
        )
      verify(mockDeviceController).pull(createTestArtifact("${tmpDir}/coverage1.ec", "${outputDir}/coverage1.ec"))

      verify(mockDeviceController)
        .execute(
          eq(listOf("shell", "run-as", TESTED_APP, "cat", "\"${coverageDir}/coverage2.ec\"", ">", "\"${tmpDir}/coverage2.ec\"")),
          anyOrNull(),
        )
      verify(mockDeviceController).pull(createTestArtifact("${tmpDir}/coverage2.ec", "${outputDir}/coverage2.ec"))

      verify(mockDeviceController).execute(eq(listOf("shell", "rm", "-rf", "\"${tmpDir}\"")), anyOrNull())
      verifyNoMoreInteractions()
    }
  }
}
