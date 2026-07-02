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

package com.android.tools.journeys.testengine.descriptor

import com.android.tools.journeys.testengine.JourneysExecutionContext
import com.android.tools.journeys.testengine.robo.platform.Proxy
import com.google.cloud.test.appcrawler.proto.Artifact
import com.google.protobuf.ByteString
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.hierarchical.Node
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq

class JourneyFileDescriptorTest {

  companion object {
    private val tempResultsDir = Files.createTempDirectory("journeys_results").toFile().absoluteFile

    init {
      System.setProperty("JourneysTestEngineInput.resultsDir", tempResultsDir.absolutePath)
      System.setProperty("JourneysTestEngineInput.journeysInputDir", tempResultsDir.absolutePath)
      System.setProperty("JourneysTestEngineInput.testDeviceId", "device123")
    }
  }

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var mockProxy: Proxy
  private lateinit var mockListener: EngineExecutionListener
  private lateinit var mockExecutor: Node.DynamicTestExecutor
  private lateinit var journeyFile: File

  @Before
  fun setUp() {
    mockProxy = mock(Proxy::class.java)
    mockListener = mock(EngineExecutionListener::class.java)
    mockExecutor = mock(Node.DynamicTestExecutor::class.java)

    journeyFile = tempFolder.newFile("test_journey.journey.xml")
    journeyFile.writeText(
      """
      <journey name="test">
        <actions>
          <action>Some action</action>
        </actions>
      </journey>
      """
        .trimIndent()
    )
  }

  @Test
  fun testExecute_normalArtifact_writesSuccessfully() {
    val parentId = UniqueId.forEngine("journeys")
    val descriptor = JourneyFileDescriptor(parentId, journeyFile, "test_journey.journey.xml")

    val context =
      JourneysExecutionContext(journeyRunId = "run-123", executionListener = mockListener, proxy = mockProxy, targetDeviceId = "device-abc")

    // Execute the descriptor. Since proxy is mocked, it won't run a real crawler.
    descriptor.execute(context, mockExecutor)

    // Capture the artifactProcessor lambda passed to proxy.executeJourney
    val artifactProcessorCaptor = argumentCaptor<(Artifact) -> Unit>()
    verify(mockProxy).executeJourney(eq("run-123"), eq("device-abc"), eq(journeyFile.toPath()), artifactProcessorCaptor.capture())

    val artifactProcessor = artifactProcessorCaptor.firstValue

    // Call the processor with a normal artifact name
    val artifactData = "hello world"
    val artifact = Artifact.newBuilder().setName("my_result.txt").setData(ByteString.copyFromUtf8(artifactData)).build()

    artifactProcessor(artifact)

    // Verify file is written at the expected location under the results directory
    val expectedFile = tempResultsDir.toPath().resolve("device-abc").resolve("test_journey").resolve("my_result.txt")

    assertTrue(Files.exists(expectedFile))
    assertEquals(artifactData, expectedFile.readText())
  }

  @Test
  fun testExecute_subfolderArtifactName_throwsExceptionAndDoesNotWrite() {
    val parentId = UniqueId.forEngine("journeys")
    val descriptor = JourneyFileDescriptor(parentId, journeyFile, "test_journey.journey.xml")

    val context =
      JourneysExecutionContext(journeyRunId = "run-123", executionListener = mockListener, proxy = mockProxy, targetDeviceId = "device-abc")

    descriptor.execute(context, mockExecutor)

    val artifactProcessorCaptor = argumentCaptor<(Artifact) -> Unit>()
    verify(mockProxy).executeJourney(eq("run-123"), eq("device-abc"), eq(journeyFile.toPath()), artifactProcessorCaptor.capture())

    val artifactProcessor = artifactProcessorCaptor.firstValue

    // Artifact with a subfolder structure is rejected under the stricter leaf-only check
    val artifact = Artifact.newBuilder().setName("subfolder/my_result.txt").setData(ByteString.copyFromUtf8("content")).build()

    assertThrows(IllegalArgumentException::class.java) { artifactProcessor(artifact) }

    // Verify the file was NOT written
    val notExpectedFile = tempResultsDir.toPath().resolve("device-abc").resolve("test_journey").resolve("subfolder/my_result.txt")
    assertFalse(Files.exists(notExpectedFile))
  }

  @Test
  fun testExecute_maliciousArtifactNamePathTraversal_throwsExceptionAndDoesNotWrite() {
    val parentId = UniqueId.forEngine("journeys")
    val descriptor = JourneyFileDescriptor(parentId, journeyFile, "test_journey.journey.xml")

    val context =
      JourneysExecutionContext(journeyRunId = "run-123", executionListener = mockListener, proxy = mockProxy, targetDeviceId = "device-abc")

    descriptor.execute(context, mockExecutor)

    val artifactProcessorCaptor = argumentCaptor<(Artifact) -> Unit>()
    verify(mockProxy).executeJourney(eq("run-123"), eq("device-abc"), eq(journeyFile.toPath()), artifactProcessorCaptor.capture())

    val artifactProcessor = artifactProcessorCaptor.firstValue

    // Malicious artifact with path traversal
    val artifact = Artifact.newBuilder().setName("../../../traversal.txt").setData(ByteString.copyFromUtf8("dangerous content")).build()

    assertThrows(IllegalArgumentException::class.java) { artifactProcessor(artifact) }

    // Verify the file was NOT written outside the output directory
    val escapedFile = tempResultsDir.toPath().resolve("traversal.txt")
    assertFalse(Files.exists(escapedFile))
  }

  @Test
  fun testExecute_maliciousArtifactAbsoluteName_throwsExceptionAndDoesNotWrite() {
    val parentId = UniqueId.forEngine("journeys")
    val descriptor = JourneyFileDescriptor(parentId, journeyFile, "test_journey.journey.xml")

    val context =
      JourneysExecutionContext(journeyRunId = "run-123", executionListener = mockListener, proxy = mockProxy, targetDeviceId = "device-abc")

    descriptor.execute(context, mockExecutor)

    val artifactProcessorCaptor = argumentCaptor<(Artifact) -> Unit>()
    verify(mockProxy).executeJourney(eq("run-123"), eq("device-abc"), eq(journeyFile.toPath()), artifactProcessorCaptor.capture())

    val artifactProcessor = artifactProcessorCaptor.firstValue

    // Malicious artifact with absolute path
    val absolutePathStr =
      if (System.getProperty("os.name").lowercase().contains("win")) {
        "C:\\escaped_abs.txt"
      } else {
        "/tmp/escaped_abs.txt"
      }

    val artifact = Artifact.newBuilder().setName(absolutePathStr).setData(ByteString.copyFromUtf8("dangerous absolute content")).build()

    assertThrows(IllegalArgumentException::class.java) { artifactProcessor(artifact) }

    // Verify the file was NOT written at the absolute path
    assertFalse(Files.exists(Paths.get(absolutePathStr)))
  }

  @Test
  fun testExecute_maliciousDeviceIdPathTraversal_isSanitizedSafely() {
    val parentId = UniqueId.forEngine("journeys")
    val descriptor = JourneyFileDescriptor(parentId, journeyFile, "test_journey.journey.xml")

    val context =
      JourneysExecutionContext(
        journeyRunId = "run-123",
        executionListener = mockListener,
        proxy = mockProxy,
        targetDeviceId = "../../malicious-device-id",
      )

    descriptor.execute(context, mockExecutor)

    val artifactProcessorCaptor = argumentCaptor<(Artifact) -> Unit>()
    // Note: context.targetDeviceId is passed as-is to the proxy
    verify(mockProxy)
      .executeJourney(eq("run-123"), eq("../../malicious-device-id"), eq(journeyFile.toPath()), artifactProcessorCaptor.capture())

    val artifactProcessor = artifactProcessorCaptor.firstValue

    val artifactData = "hello device traversal"
    val artifact = Artifact.newBuilder().setName("device_test.txt").setData(ByteString.copyFromUtf8(artifactData)).build()

    artifactProcessor(artifact)

    // The malicious device ID "../../malicious-device-id" should be sanitized to ".._.._malicious-device-id"
    // and thus written safely within resultsDir.
    val expectedFile = tempResultsDir.toPath().resolve(".._.._malicious-device-id").resolve("test_journey").resolve("device_test.txt")

    assertTrue(Files.exists(expectedFile))
    assertEquals(artifactData, expectedFile.readText())

    // Verify it did not write to the actual parent path of resultsDir
    val escapedFile = tempResultsDir.toPath().parent.resolve("malicious-device-id")
    assertFalse(Files.exists(escapedFile))
  }

  @Test
  fun testExecute_maliciousArtifactNameBackslashOnUnix_throwsExceptionAndDoesNotWrite() {
    val parentId = UniqueId.forEngine("journeys")
    val descriptor = JourneyFileDescriptor(parentId, journeyFile, "test_journey.journey.xml")

    val context =
      JourneysExecutionContext(journeyRunId = "run-123", executionListener = mockListener, proxy = mockProxy, targetDeviceId = "device-abc")

    descriptor.execute(context, mockExecutor)

    val artifactProcessorCaptor = argumentCaptor<(Artifact) -> Unit>()
    verify(mockProxy).executeJourney(eq("run-123"), eq("device-abc"), eq(journeyFile.toPath()), artifactProcessorCaptor.capture())

    val artifactProcessor = artifactProcessorCaptor.firstValue

    // Malicious artifact with backslashes (representing path traversal on Windows/cross-platform)
    val artifact =
      Artifact.newBuilder().setName("..\\..\\malicious.txt").setData(ByteString.copyFromUtf8("dangerous backslash content")).build()

    assertThrows(IllegalArgumentException::class.java) { artifactProcessor(artifact) }

    // Verify the file was NOT written
    val escapedFile = tempResultsDir.toPath().resolve("device-abc").resolve("test_journey").resolve("..\\..\\malicious.txt")
    assertFalse(Files.exists(escapedFile))
  }
}
