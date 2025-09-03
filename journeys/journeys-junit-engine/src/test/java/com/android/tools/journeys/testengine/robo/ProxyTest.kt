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

import com.google.appcrawler.platform.client.GrpcClient
import com.google.auth.oauth2.ImpersonatedCredentials
import io.grpc.ClientInterceptor
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.argThat
import org.mockito.Mockito.`when`
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ProxyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockAdb: Adb
    private lateinit var mockProcess: Process
    private lateinit var validJourneyPath: Path
    private lateinit var proxy: Proxy
    private val channelProvider = ProductionChannelProviderFactory().createChannelProvider()

    @Before
    fun setUp() {
        mockAdb = mock(Adb::class.java)
        mockProcess = mock(Process::class.java)
        proxy = Proxy(
            adb = mockAdb,
            applicationId = "com.example.app",
            appApkPath = "app.apk",
            accessTokenPath = "token.txt",
            channelProvider = channelProvider
        )
        validJourneyPath = tempFolder.newFile("valid_journey.xml").toPath()
        validJourneyPath.toFile()
            .writeText("<journey name=\"test\"><actions><action>Some action</action></actions></journey>")
        `when`(mockProcess.inputStream).thenReturn(ByteArrayInputStream("".toByteArray()))
        `when`(mockProcess.errorStream).thenReturn(ByteArrayInputStream("".toByteArray()))
    }

    @Test
    fun testExecuteJourney_journeyReadFailed_missingFile() {
        val journeyPath = Paths.get("non_existent_journey.xml")

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", journeyPath) { artifact -> }
        }.apply {
            assertEquals(JourneyFailureReason.JOURNEY_READ_FAILED, reason)
            assertContains(message!!, "Failed to read journey: non_existent_journey.xml")
        }
    }

    @Test
    fun testExecuteJourney_journeyReadFailed_invalidXml() {
        val invalidJourneyPath = tempFolder.newFile("invalid_journey.xml").toPath()
        invalidJourneyPath.toFile().writeText("<journey><actions></journey>")

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", invalidJourneyPath) { artifact -> }
        }.apply {
            assertEquals(JourneyFailureReason.JOURNEY_READ_FAILED, reason)
            assertContains(
                message!!,
                "Failed to read journey: The element type \"actions\" must be terminated by the matching end-tag \"</actions>\""
            )
        }
    }

    @Test
    fun testExecuteJourney_usesCorrectAdbCommands_api34() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(34)
        `when`(mockAdb.install(anyString(), anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenThrow(RuntimeException("Test failure"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", validJourneyPath) { }
        }

        verify(mockAdb).setGlobalSettingsValue("device-123", "verifier_verify_adb_installs", "0")

        val installFlagsCaptor = argumentCaptor<List<String>>()
        verify(mockAdb).install(
            eq("device-123"),
            argThat { contains("extracted_apk") && endsWith(".apk") },
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(
            listOf("-r", "-d", "-g", "--bypass-low-target-sdk-block"),
            installFlagsCaptor.firstValue
        )

        verify(mockAdb).install(
            eq("device-123"),
            eq("app.apk"),
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(listOf("-r", "-d", "-g"), installFlagsCaptor.secondValue)
    }

    @Test
    fun testExecuteJourney_usesCorrectAdbCommands_api30() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenThrow(RuntimeException("Test failure"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", validJourneyPath) { }
        }

        verify(mockAdb, never()).setGlobalSettingsValue(
            anyString(),
            anyString(),
            anyString(),
            anyLong()
        )

        val installFlagsCaptor = argumentCaptor<List<String>>()
        verify(mockAdb).install(
            eq("device-123"),
            argThat { contains("extracted_apk") && endsWith(".apk") },
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(listOf("-r", "-d", "-g"), installFlagsCaptor.firstValue)

        verify(mockAdb).install(
            eq("device-123"),
            eq("app.apk"),
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(listOf("-r", "-d", "-g"), installFlagsCaptor.secondValue)
    }

    @Test
    fun testExecuteJourney_usesCorrectAdbCommands_api22() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(22)
        `when`(mockAdb.install(anyString(), anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenThrow(RuntimeException("Test failure"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", validJourneyPath) { }
        }

        verify(mockAdb, never()).setGlobalSettingsValue(
            anyString(),
            anyString(),
            anyString(),
            anyLong()
        )

        val installFlagsCaptor = argumentCaptor<List<String>>()
        verify(mockAdb).install(
            eq("device-123"),
            argThat { contains("extracted_apk") && endsWith(".apk") },
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(listOf("-r", "-d"), installFlagsCaptor.firstValue)

        verify(mockAdb).install(
            eq("device-123"),
            eq("app.apk"),
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(listOf("-r", "-d"), installFlagsCaptor.secondValue)
    }

    @Test
    fun testExecuteJourney_adbInstallFailed() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(30)
        `when`(
            mockAdb.install(
                anyString(),
                anyString(),
                anyList(),
                anyLong()
            )
        ).thenThrow(RuntimeException("Install failed"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", validJourneyPath) { artifact -> }
        }.apply {
            assertEquals(JourneyFailureReason.ADB_INSTALL_FAILED, reason)
            assertContains(message!!, "Installation failure: Install failed")
        }
    }

    @Test
    fun testExecuteJourney_usesCorrectInstrumentationArgs() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenReturn(
            mockProcess
        )
        `when`(mockAdb.dumpsys(anyString(), anyString(), anyLong())).thenReturn("random port")

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", validJourneyPath) { }
        }

        val instrumentationArgsCaptor = argumentCaptor<Map<String, String>>()
        verify(mockAdb).runInstrumentation(
            eq("device-123"),
            eq(RoboConfigConstants.CRAWLER_PACKAGE_ID),
            eq(RoboConfigConstants.TEST_RUNNER_CLASS),
            instrumentationArgsCaptor.capture()
        )
        val expectedArgs = mapOf(
            RoboConfigConstants.ROBO_V2_APP_PACKAGE_FLAG to "com.example.app",
            RoboConfigConstants.ROBO_V2_UI_AUTOMATOR_ONLY_MODE to "true",
            RoboConfigConstants.ROBO_ADB_FORWARD to "true",
            RoboConfigConstants.ROBO_PICK_OPEN_PORT to "true"
        )
        assertEquals(expectedArgs, instrumentationArgsCaptor.firstValue)
    }

    @Test
    fun testExecuteJourney_instrumentationFailed() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenThrow(RuntimeException("Instrumentation failed"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", validJourneyPath) { }
        }.apply {
            assertEquals(JourneyFailureReason.INSTRUMENTATION_FAILED, reason)
            assertContains(message!!, "Failed to run instrumentation: Instrumentation failed")
        }
    }

    @Test
    fun testExecuteJourney_verifyCorrectPortCaptured() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenReturn(
            mockProcess
        )
        `when`(
            mockAdb.dumpsys(
                anyString(),
                anyString(),
                anyLong()
            )
        ).thenReturn("port_is_bound 12345")
        `when`(
            mockAdb.forward(
                anyString(),
                anyInt(),
                anyInt()
            )
        ).thenThrow(RuntimeException("Test failure"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", validJourneyPath) { }
        }
        val devicePortCaptor = ArgumentCaptor.forClass(Int::class.java)
        verify(mockAdb).forward(eq("device-123"), anyInt(), devicePortCaptor.capture())
        assertEquals(12345, devicePortCaptor.value)
    }

    @Test
    fun testExecuteJourney_roboPortExtractionFailed() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenReturn(
            mockProcess
        )
        `when`(mockAdb.dumpsys(anyString(), anyString(), anyLong())).thenReturn("some other output")

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", validJourneyPath) { }
        }.apply {
            assertEquals(JourneyFailureReason.ROBO_PORT_EXTRACTION_FAILED, reason)
            assertContains(message!!, "Failed to obtain device port after 10 attempts.")
        }
    }

    @Test
    fun testExecuteJourney_adbForwardingFailed() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenReturn(
            mockProcess
        )
        `when`(
            mockAdb.dumpsys(
                anyString(),
                anyString(),
                anyLong()
            )
        ).thenReturn("port_is_bound 12345")
        `when`(
            mockAdb.forward(
                anyString(),
                anyInt(),
                anyInt()
            )
        ).thenThrow(RuntimeException("Forwarding failed"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", validJourneyPath) { }
        }.apply {
            assertEquals(JourneyFailureReason.ADB_FORWARDING_FAILED, reason)
            assertContains(message!!, "Forwarding failed")
        }
    }

    @Test
    fun testExecuteJourney_authenticationFailed() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenReturn(
            mockProcess
        )
        `when`(
            mockAdb.dumpsys(
                anyString(),
                anyString(),
                anyLong()
            )
        ).thenReturn("port_is_bound 12345")
        `when`(mockAdb.forward(anyString(), anyInt(), anyInt())).thenAnswer { }

        val tempDir = tempFolder.newFolder()

        val proxyWithMissingToken = Proxy(
            adb = mockAdb,
            applicationId = "com.example.app",
            appApkPath = "app.apk",
            accessTokenPath = tempDir.absolutePath,
            channelProvider = channelProvider
        )

        assertThrows(JourneyExecutionException::class.java) {
            proxyWithMissingToken.executeJourney("device-123", validJourneyPath) { }
        }.apply {
            assertEquals(JourneyFailureReason.AUTHENTICATION_FAILED, reason)
            assertContains(message!!, "Failed to obtain credentials")
        }
    }

    @Test
    fun testExecuteJourney_cleanupCalledOnSuccess() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenReturn(
            mockProcess
        )
        `when`(
            mockAdb.dumpsys(
                anyString(),
                anyString(),
                anyLong()
            )
        ).thenReturn("port_is_bound 12345")
        `when`(mockAdb.forward(anyString(), anyInt(), anyInt())).thenAnswer { }

        val fakeTokenFile = tempFolder.newFile("fake_token.json")
        fakeTokenFile.writeText(
            """
              {
                "access_token": "fake_token",
                "expires_in": 3600
              }
            """.trimIndent()
        )

        val proxyWithFakeToken = Proxy(
            adb = mockAdb,
            applicationId = "com.example.app",
            appApkPath = "app.apk",
            accessTokenPath = fakeTokenFile.absolutePath,
            channelProvider = channelProvider
        )

        mockStatic(ImpersonatedCredentials::class.java).use { mockedImpersonatedCredentials ->
            val mockBuilder = mock(ImpersonatedCredentials.Builder::class.java, RETURNS_DEEP_STUBS)
            mockedImpersonatedCredentials.`when`<Any> { ImpersonatedCredentials.newBuilder() }
                .thenReturn(mockBuilder)

            mockStatic(NettyChannelBuilder::class.java).use { mockedNettyChannelBuilder ->
                val mockChannelBuilder = mock(NettyChannelBuilder::class.java)
                `when`(mockChannelBuilder.intercept(any<ClientInterceptor>())).thenReturn(
                    mockChannelBuilder
                )
                `when`(mockChannelBuilder.build()).thenReturn(mock(ManagedChannel::class.java))

                mockedNettyChannelBuilder.`when`<Any> { NettyChannelBuilder.forTarget(anyString()) }
                    .thenReturn(mockChannelBuilder)

                mockConstruction(
                    GrpcClient::class.java
                ) { mockGrpcClient, context ->
                    val mockResult = mock(GrpcClient.Result::class.java)
                    `when`(mockResult.outcome()).thenReturn(GrpcClient.Result.Outcome.SUCCESS)
                    `when`(
                        mockGrpcClient.startForward(
                            any(),
                            any(),
                            any(),
                            any(),
                            any()
                        )
                    ).thenReturn(mockResult)
                }.use {
                    proxyWithFakeToken.executeJourney("device-123", validJourneyPath) {}
                }
            }
        }

        verify(mockAdb).removeForward(eq("device-123"), anyInt())
        verify(mockAdb).uninstall(eq("device-123"), eq("com.example.app"))
        verify(mockAdb).uninstall(eq("device-123"), eq(RoboConfigConstants.CRAWLER_PACKAGE_ID))
    }

    @Test
    fun testExecuteJourney_cleanupCalledOnFailure() {
        `when`(mockAdb.getDeviceApiLevel(anyString())).thenThrow(RuntimeException("Test failure"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney("device-123", validJourneyPath) { }
        }

        verify(mockAdb).removeForward(eq("device-123"), anyInt())
        verify(mockAdb).uninstall(eq("device-123"), eq("com.example.app"))
        verify(mockAdb).uninstall(eq("device-123"), eq(RoboConfigConstants.CRAWLER_PACKAGE_ID))
    }
}
