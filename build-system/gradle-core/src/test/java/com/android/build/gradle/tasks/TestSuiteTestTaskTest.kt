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

package com.android.build.gradle.tasks

import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.api.testsuites.TestEngineInputProperty
import com.android.builder.testing.api.DeviceConnector
import com.android.builder.testing.api.DeviceException
import com.google.common.truth.Truth.assertThat
import java.util.Properties
import java.util.concurrent.ExecutionException
import kotlin.test.assertFailsWith
import org.gradle.api.tasks.VerificationException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class TestSuiteTestTaskTest {

  @get:Rule val folder = TemporaryFolder()

  @Test
  fun testSerializer() {
    val outputFile = folder.newFile()
    val testedApksProp = AgpTestSuiteInputParameters.TESTED_APKS.propertyName
    TestSuiteTestTask.AgpTestSuiteInputsSerializer.serialize(
      engineInputParameters =
        listOf(
          TestEngineInputProperty(testedApksProp, "universal.apk"),
          TestEngineInputProperty("$testedApksProp[serial1]", "device1.apk"),
          TestEngineInputProperty("$testedApksProp[serial2]", "device2.apk"),
        ),
      engineInputProperties = mapOf("foo" to "fooValue"),
      outputFile,
    )
    assertThat(outputFile.exists()).isTrue()
    val serializedInputs = Properties().also { it.load(outputFile.reader(Charsets.UTF_8)) }
    assertThat(serializedInputs).hasSize(4)
    assertThat(serializedInputs.getProperty(testedApksProp)).isEqualTo("universal.apk")
    assertThat(serializedInputs.getProperty("$testedApksProp[serial1]")).isEqualTo("device1.apk")
    assertThat(serializedInputs.getProperty("$testedApksProp[serial2]")).isEqualTo("device2.apk")
  }

  @Test
  fun testSerializer_withOrchestrator() {
    val outputFile = folder.newFile()
    val testUtilApksProp = AgpTestSuiteInputParameters.TEST_UTIL_APKS.propertyName
    val executionModeProp = AgpTestSuiteInputParameters.ANDROID_TEST_EXECUTION_MODE.propertyName
    TestSuiteTestTask.AgpTestSuiteInputsSerializer.serialize(
      engineInputParameters =
        listOf(
          TestEngineInputProperty(testUtilApksProp, "orchestrator.apk,services.apk"),
          TestEngineInputProperty(executionModeProp, "ANDROIDX_TEST_ORCHESTRATOR"),
        ),
      engineInputProperties = emptyMap(),
      outputFile,
    )
    assertThat(outputFile.exists()).isTrue()
    val serializedInputs = Properties().also { it.load(outputFile.reader(Charsets.UTF_8)) }
    assertThat(serializedInputs).hasSize(2)
    assertThat(serializedInputs.getProperty(testUtilApksProp)).isEqualTo("orchestrator.apk,services.apk")
    assertThat(serializedInputs.getProperty(executionModeProp)).isEqualTo("ANDROIDX_TEST_ORCHESTRATOR")
  }

  @Test
  fun testSerializer_withOnTheFlyCoverage() {
    val outputFile = folder.newFile()
    TestSuiteTestTask.AgpTestSuiteInputsSerializer.serialize(
      engineInputParameters = emptyList(),
      engineInputProperties = mapOf("com.android.agp.test.COVERAGE_TYPE" to "ON_THE_FLY"),
      outputFile,
    )
    assertThat(outputFile.exists()).isTrue()
    val serializedInputs = Properties().also { it.load(outputFile.reader(Charsets.UTF_8)) }
    assertThat(serializedInputs).hasSize(1)
    assertThat(serializedInputs.getProperty("com.android.agp.test.COVERAGE_TYPE")).isEqualTo("ON_THE_FLY")
  }

  @Test
  fun runUnwrappingVerificationFailures_connectedDeviceTestFailure_rethrowsVerificationException() {
    val testFailure = VerificationException("There were failing tests.")

    // DeviceProvider.use wraps the failure once.
    val thrown = assertFailsWith<VerificationException> { runUnwrappingVerificationFailures { throw ExecutionException(testFailure) } }

    assertThat(thrown).isSameInstanceAs(testFailure)
  }

  @Test
  fun runUnwrappingVerificationFailures_managedDeviceTestFailure_rethrowsVerificationException() {
    val testFailure = VerificationException("There were failing tests.")

    // The managed device lock and DeviceProvider.use each wrap the failure.
    val thrown =
      assertFailsWith<VerificationException> {
        runUnwrappingVerificationFailures { throw ExecutionException(ExecutionException(testFailure)) }
      }

    assertThat(thrown).isSameInstanceAs(testFailure)
  }

  @Test
  fun runUnwrappingVerificationFailures_otherFailure_rethrowsUnchanged() {
    val otherFailure = ExecutionException(IllegalStateException("Device went offline."))

    val thrown = assertFailsWith<ExecutionException> { runUnwrappingVerificationFailures { throw otherFailure } }

    assertThat(thrown).isSameInstanceAs(otherFailure)
  }

  @Test
  fun connectedDeviceTargets_offlineDevice_failsOnlyWhenItsConfigIsRead() {
    val onlineDevice = mock<DeviceConnector>()
    whenever(onlineDevice.serialNumber).thenReturn("emulator-5554")
    val offlineDevice = mock<DeviceConnector>()
    whenever(offlineDevice.serialNumber).thenReturn("emulator-5560")
    whenever(offlineDevice.deviceConfig).thenThrow(DeviceException("device offline"))

    // Only app bundle tests read the configuration, so a device that went offline doesn't fail other test suites.
    val targets = TestSuiteTestTask.connectedDeviceTargets(listOf(onlineDevice, offlineDevice))

    assertThat(targets.map { it.serialNumber }).containsExactly("emulator-5554", "emulator-5560").inOrder()
    assertFailsWith<DeviceException> { targets.last().deviceConfigProvider }
  }
}
