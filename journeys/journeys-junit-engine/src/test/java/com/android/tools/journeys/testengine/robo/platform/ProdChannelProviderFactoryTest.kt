/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-20.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.journeys.testengine.robo.platform

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ImpersonatedCredentials
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.mockito.kotlin.doThrow
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals

class ProdChannelProviderFactoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var factory: ProductionChannelProviderFactory
    private lateinit var accessTokenFile: File

    @Before
    fun setUp() {
        factory = ProductionChannelProviderFactory()
        accessTokenFile = tempFolder.newFile("fake_token.json").apply {
            writeText(
                """
              {
                "access_token": "fake_token_value",
                "expires_in": 3600
              }
            """.trimIndent()
            )
        }
    }

    @Test
    fun testCreateCredentials_adcFailure_accessTokenPathIsBlank() {
        mockStatic(GoogleCredentials::class.java).use { mockedGoogleCredentials ->
            mockedGoogleCredentials.`when`<GoogleCredentials> { GoogleCredentials.getApplicationDefault() }
                .thenThrow(IOException("ADC failed"))

            val createChannel = factory.createChannelProvider()

            val exception = assertThrows(JourneyExecutionException::class.java) {
                createChannel("someTarget", "")
            }

            assertEquals(JourneyFailureReason.AUTHENTICATION_FAILED, exception.reason)
            assertEquals(
                "Failed to obtain Application Default Credentials (ADC). Please check " +
                        "your network connection and ensure ADC is configured correctly. You can " +
                        "configure ADC by running 'gcloud auth application-default login' or by " +
                        "setting the GOOGLE_APPLICATION_CREDENTIALS environment variable. " +
                        "[Reason=AUTHENTICATION_FAILED]",
                exception.message!!
            )
        }
    }

    @Test
    fun testCreateCredentials_tokenPathFailure_accessTokenPathIsNotBlank() {
        runWithMockedImpersonatedCredentials({ mockCredentials ->
            // Ensure refresh throws an exception
            doThrow(IOException("Impersonation refresh failed")).`when`(mockCredentials).refresh()
        }) {
            val createChannel = factory.createChannelProvider()
            val exception = assertThrows(JourneyExecutionException::class.java) {
                createChannel("someTarget", accessTokenFile.absolutePath)
            }
            assertEquals(JourneyFailureReason.AUTHENTICATION_FAILED, exception.reason)
            assertEquals(
                "Failed to obtain credentials for establishing connection " +
                    "with backend. Please check your network connection and ensure you are " +
                    "logged in to Gemini in Android Studio. [Reason=AUTHENTICATION_FAILED]",
                exception.message!!
            )
        }
    }

    @Test
    fun testCreateCredentials_success_validAccessTokenPath() {
        runWithMockedImpersonatedCredentials({ mockCredentials ->
            // Ensure refresh does not throw an exception for this success case
            doNothing().`when`(mockCredentials).refresh()
        }) {
            val createChannel = factory.createChannelProvider()
            // Should not throw an exception
            createChannel("someTarget", accessTokenFile.absolutePath)
        }
    }

    private fun runWithMockedImpersonatedCredentials(
        mockCredentialsConfiguration: (ImpersonatedCredentials) -> Unit,
        block: () -> Unit
    ) {
        mockStatic(ImpersonatedCredentials::class.java).use { mockedImpersonatedCredentials ->
            val mockBuilder = mock(ImpersonatedCredentials.Builder::class.java)
            val mockCredentials = mock(ImpersonatedCredentials::class.java)
            mockedImpersonatedCredentials.`when`<Any> { ImpersonatedCredentials.newBuilder() }
                .thenReturn(mockBuilder)

            // Explicitly stub each method in the builder chain to return the mockBuilder
            `when`(mockBuilder.setSourceCredentials(any())).thenReturn(mockBuilder)
            `when`(mockBuilder.setScopes(anyList())).thenReturn(mockBuilder)
            `when`(mockBuilder.setTargetPrincipal(anyString())).thenReturn(mockBuilder)
            `when`(mockBuilder.setQuotaProjectId(anyString())).thenReturn(mockBuilder)

            // Stub the final build() call to return the mockCredentials instance
            `when`(mockBuilder.build()).thenReturn(mockCredentials)

            mockCredentialsConfiguration(mockCredentials)
            block()
        }
    }
}
