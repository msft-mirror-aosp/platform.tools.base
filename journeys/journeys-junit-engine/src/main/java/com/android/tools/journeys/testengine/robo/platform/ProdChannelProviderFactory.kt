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

package com.android.tools.journeys.testengine.robo.platform

import com.google.api.client.auth.oauth2.TokenResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ImpersonatedCredentials
import io.grpc.ManagedChannel
import io.grpc.auth.MoreCallCredentials
import io.grpc.netty.NettyChannelBuilder
import java.io.File
import java.time.Instant
import java.util.Date

class ProductionChannelProviderFactory : ChannelProviderFactory {

    override fun createChannelProvider(): (targetEndpoint: String, accessTokenPath: String) -> ManagedChannel {
        return { targetEndpoint, accessTokenPath ->
            NettyChannelBuilder.forTarget(targetEndpoint).intercept(
                Proxy.CallCredentialsInterceptor(
                    MoreCallCredentials.from(createCredentials(accessTokenPath))
                )
            ).build()
        }
    }

    private fun createCredentials(accessTokenPath: String): GoogleCredentials {
        try {
            val impersonated =
                ImpersonatedCredentials.newBuilder()
                    .setSourceCredentials(createSourceCredentials(accessTokenPath))
                    .setScopes(listOf(RoboConfigConstants.AUTH_SCOPE))
                    .setTargetPrincipal(RoboConfigConstants.AUTH_PRINCIPAL)
                    .setQuotaProjectId(RoboConfigConstants.QUOTA_PROJECT_ID)
                    .build()
            impersonated.refresh()
            return impersonated
        } catch (e: Exception) {
            throw JourneyExecutionException(
                "Failed to obtain credentials for establishing connection with backend. "
                        + "Make sure you are logged in to Android Studio and are "
                        + "connected to a network before re-trying.",
                cause = e,
                reason = JourneyFailureReason.AUTHENTICATION_FAILED);
        }
    }

    private fun createSourceCredentials(accessTokenPath: String): GoogleCredentials {
        return if (accessTokenPath.isNotBlank() && File(accessTokenPath).exists()) {
            val token =
                File(accessTokenPath).reader()
                    .use { fileReader ->
                        GsonFactory().fromReader<TokenResponse>(
                            fileReader,
                            TokenResponse::class.java
                        )
                    }
            val userCreds = GoogleCredentials.create(AccessToken.newBuilder().apply {
                tokenValue = token.accessToken
                scopes = listOf(RoboConfigConstants.AUTH_SCOPE)
                expirationTime = Date.from(Instant.now().plusSeconds(token.expiresInSeconds))
            }.build())
            userCreds
        } else {
            GoogleCredentials.getApplicationDefault()
        }
    }
}
