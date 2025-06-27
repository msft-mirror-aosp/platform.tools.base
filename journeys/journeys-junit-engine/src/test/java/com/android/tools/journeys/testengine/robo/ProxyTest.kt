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
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import org.mockito.kotlin.argumentCaptor
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

    @Before
    fun setUp() {
        mockAdb = mock(Adb::class.java)
        mockProcess = mock(Process::class.java)
        proxy = Proxy(
            adb = mockAdb,
            crawlerAppApkPath = "crawler.apk",
            applicationId = "com.example.app",
            appApkPath = "app.apk",
            accessTokenPath = "token.txt"
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
            proxy.executeJourney(journeyPath) { artifact -> }
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
            proxy.executeJourney(invalidJourneyPath) { artifact -> }
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
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(34)
        `when`(mockAdb.install(anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenThrow(RuntimeException("Test failure"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney(validJourneyPath) { }
        }

        verify(mockAdb).setGlobalSettingsValue("verifier_verify_adb_installs", "0")

        val installFlagsCaptor = argumentCaptor<List<String>>()
        verify(mockAdb).install(
            org.mockito.kotlin.eq("crawler.apk"),
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(
            listOf("-r", "-d", "-g", "--bypass-low-target-sdk-block"),
            installFlagsCaptor.firstValue
        )

        verify(mockAdb).install(
            org.mockito.kotlin.eq("app.apk"),
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(listOf("-r", "-d", "-g"), installFlagsCaptor.secondValue)
    }

    @Test
    fun testExecuteJourney_usesCorrectAdbCommands_api30() {
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenThrow(RuntimeException("Test failure"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney(validJourneyPath) { }
        }

        verify(mockAdb, never()).setGlobalSettingsValue(anyString(), anyString(), anyLong())

        val installFlagsCaptor = argumentCaptor<List<String>>()
        verify(mockAdb).install(
            org.mockito.kotlin.eq("crawler.apk"),
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(listOf("-r", "-d", "-g"), installFlagsCaptor.firstValue)

        verify(mockAdb).install(
            org.mockito.kotlin.eq("app.apk"),
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(listOf("-r", "-d", "-g"), installFlagsCaptor.secondValue)
    }

    @Test
    fun testExecuteJourney_usesCorrectAdbCommands_api22() {
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(22)
        `when`(mockAdb.install(anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenThrow(RuntimeException("Test failure"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney(validJourneyPath) { }
        }

        verify(mockAdb, never()).setGlobalSettingsValue(anyString(), anyString(), anyLong())

        val installFlagsCaptor = argumentCaptor<List<String>>()
        verify(mockAdb).install(
            org.mockito.kotlin.eq("crawler.apk"),
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(listOf("-r", "-d"), installFlagsCaptor.firstValue)

        verify(mockAdb).install(
            org.mockito.kotlin.eq("app.apk"),
            installFlagsCaptor.capture(),
            anyLong()
        )
        assertEquals(listOf("-r", "-d"), installFlagsCaptor.secondValue)
    }

    @Test
    fun testExecuteJourney_adbInstallFailed() {
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(30)
        `when`(
            mockAdb.install(
                anyString(),
                anyList(),
                anyLong()
            )
        ).thenThrow(RuntimeException("Install failed"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney(validJourneyPath) { artifact -> }
        }.apply {
            assertEquals(JourneyFailureReason.ADB_INSTALL_FAILED, reason)
            assertContains(message!!, "Installation failure: Install failed")
        }
    }

    @Test
    fun testExecuteJourney_usesCorrectInstrumentationArgs() {
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(mockAdb.runInstrumentation(anyString(), anyString(), anyMap())).thenReturn(
            mockProcess
        )
        `when`(mockAdb.dumpsys(anyString(), anyLong())).thenReturn("random port")

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney(validJourneyPath) { }
        }

        val instrumentationArgsCaptor = argumentCaptor<Map<String, String>>()
        verify(mockAdb).runInstrumentation(
            org.mockito.kotlin.eq(RoboConfigConstants.CRAWLER_PACKAGE_ID),
            org.mockito.kotlin.eq(RoboConfigConstants.TEST_RUNNER_CLASS),
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
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(
            mockAdb.runInstrumentation(
                anyString(),
                anyString(),
                anyMap()
            )
        ).thenThrow(RuntimeException("Instrumentation failed"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney(validJourneyPath) { }
        }.apply {
            assertEquals(JourneyFailureReason.INSTRUMENTATION_FAILED, reason)
            assertContains(message!!, "Failed to run instrumentation: Instrumentation failed")
        }
    }

    @Test
    fun testExecuteJourney_verifyCorrectPortCaptured() {
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(mockAdb.runInstrumentation(anyString(), anyString(), anyMap())).thenReturn(
            mockProcess
        )
        `when`(mockAdb.dumpsys(anyString(), anyLong())).thenReturn("port_is_bound 12345")
        `when`(mockAdb.forward(anyInt(), anyInt())).thenThrow(RuntimeException("Test failure"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney(validJourneyPath) { }
        }
        val devicePortCaptor = ArgumentCaptor.forClass(Int::class.java)
        verify(mockAdb).forward(anyInt(), devicePortCaptor.capture())
        assertEquals(12345, devicePortCaptor.value)
    }

    @Test
    fun testExecuteJourney_roboPortExtractionFailed() {
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(mockAdb.runInstrumentation(anyString(), anyString(), anyMap())).thenReturn(
            mockProcess
        )
        `when`(mockAdb.dumpsys(anyString(), anyLong())).thenReturn("some other output")

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney(validJourneyPath) { }
        }.apply {
            assertEquals(JourneyFailureReason.ROBO_PORT_EXTRACTION_FAILED, reason)
            assertContains(message!!, "Failed to obtain device port after 10 attempts.")
        }
    }

    @Test
    fun testExecuteJourney_adbForwardingFailed() {
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(mockAdb.runInstrumentation(anyString(), anyString(), anyMap())).thenReturn(
            mockProcess
        )
        `when`(mockAdb.dumpsys(anyString(), anyLong())).thenReturn("port_is_bound 12345")
        `when`(mockAdb.forward(anyInt(), anyInt())).thenThrow(RuntimeException("Forwarding failed"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney(validJourneyPath) { }
        }.apply {
            assertEquals(JourneyFailureReason.ADB_FORWARDING_FAILED, reason)
            assertContains(message!!, "Forwarding failed")
        }
    }

    @Test
    fun testExecuteJourney_authenticationFailed() {
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(mockAdb.runInstrumentation(anyString(), anyString(), anyMap())).thenReturn(
            mockProcess
        )
        `when`(mockAdb.dumpsys(anyString(), anyLong())).thenReturn("port_is_bound 12345")
        `when`(mockAdb.forward(anyInt(), anyInt())).thenAnswer { }

        val tempDir = tempFolder.newFolder()

        val proxyWithMissingToken = Proxy(
            adb = mockAdb,
            crawlerAppApkPath = "crawler.apk",
            applicationId = "com.example.app",
            appApkPath = "app.apk",
            accessTokenPath = tempDir.absolutePath
        )

        assertThrows(JourneyExecutionException::class.java) {
            proxyWithMissingToken.executeJourney(validJourneyPath) { }
        }.apply {
            assertEquals(JourneyFailureReason.AUTHENTICATION_FAILED, reason)
            assertContains(message!!, "Failed to obtain credentials")
        }
    }

    @Test
    fun testExecuteJourney_cleanupCalledOnSuccess() {
        `when`(mockAdb.getDeviceApiLevel()).thenReturn(30)
        `when`(mockAdb.install(anyString(), anyList(), anyLong())).thenAnswer { }
        `when`(mockAdb.runInstrumentation(anyString(), anyString(), anyMap())).thenReturn(mockProcess)
        `when`(mockAdb.dumpsys(anyString(), anyLong())).thenReturn("port_is_bound 12345")
        `when`(mockAdb.forward(anyInt(), anyInt())).thenAnswer { }

        val fakeTokenFile = tempFolder.newFile("fake_token.json")
        fakeTokenFile.writeText("""
              {
                "access_token": "fake_token",
                "expires_in": 3600
              }
            """.trimIndent()
        )

        val proxyWithFakeToken = Proxy(
            adb = mockAdb,
            crawlerAppApkPath = "crawler.apk",
            applicationId = "com.example.app",
            appApkPath = "app.apk",
            accessTokenPath = fakeTokenFile.absolutePath
        )

        mockStatic(ImpersonatedCredentials::class.java).use { mockedImpersonatedCredentials ->
            val mockBuilder = mock(ImpersonatedCredentials.Builder::class.java, RETURNS_DEEP_STUBS)
            mockedImpersonatedCredentials.`when`<Any> { ImpersonatedCredentials.newBuilder() }
                .thenReturn(mockBuilder)

            mockStatic(NettyChannelBuilder::class.java).use { mockedNettyChannelBuilder ->
                val mockChannelBuilder = mock(NettyChannelBuilder::class.java)
                `when`(mockChannelBuilder.intercept(any<ClientInterceptor>())).thenReturn(mockChannelBuilder)
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
                    proxyWithFakeToken.executeJourney(validJourneyPath) {}
                }
            }
        }

        verify(mockAdb).removeForward(anyInt())
        verify(mockAdb).uninstall("com.example.app")
        verify(mockAdb).uninstall(RoboConfigConstants.CRAWLER_PACKAGE_ID)
    }

    @Test
    fun testExecuteJourney_cleanupCalledOnFailure() {
        `when`(mockAdb.getDeviceApiLevel()).thenThrow(RuntimeException("Test failure"))

        assertThrows(JourneyExecutionException::class.java) {
            proxy.executeJourney(validJourneyPath) { }
        }

        verify(mockAdb).removeForward(anyInt())
        verify(mockAdb).uninstall("com.example.app")
        verify(mockAdb).uninstall(RoboConfigConstants.CRAWLER_PACKAGE_ID)
    }
}
