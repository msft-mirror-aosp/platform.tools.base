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

package com.android.tools.utp.gradle

import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerProto.RecordTestResultEventResponse
import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerProto.TestResultEvent
import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerProto.TestResultEvent.TestSuiteStarted
import com.android.tools.utp.plugins.result.listener.gradle.proto.GradleAndroidTestResultListenerServiceGrpc
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors
import com.google.protobuf.Any
import com.google.testing.platform.proto.api.core.TestStatusProto
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.grpc.testing.GrpcCleanupRule
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import java.io.File
import java.io.IOException

/**
 * Unit tests for [UtpTestResultListenerServer].
 */
class UtpTestResultListenerServerTest {

    @get:Rule
    val grpcCleanup = GrpcCleanupRule()

    private val mockResultListenerClientPrivateKey: File = mock()
    private val mockTrustCertCollection: File = mock()
    private val mockTestResultListener: UtpTestResultListener = mock()

    @Test
    fun startServer() {
        val serverName = InProcessServerBuilder.generateName()

        var capturedPort: Int? = null

        val server = UtpTestResultListenerServer.startServer(
            mockTrustCertCollection,
            mockResultListenerClientPrivateKey,
            mockTrustCertCollection,
            mockTestResultListener,
            defaultPort = 1234,
            maxRetryAttempt = 1,
            executorService = MoreExecutors.newDirectExecutorService(),
        ) { port ->
            capturedPort = port
            InProcessServerBuilder.forName(serverName)
        }

        requireNotNull(server)
        grpcCleanup.register(server.server)

        assertThat(capturedPort).isEqualTo(1234)

        server.close()

        assertThat(server.server.isTerminated).isTrue()
        assertThat(server.server.isShutdown).isTrue()
        assertThat(server.executorService.isTerminated).isTrue()
        assertThat(server.executorService.isShutdown).isTrue()
    }

    @Test
    fun availablePortNotFound() {
        val server = UtpTestResultListenerServer.startServer(
            mockTrustCertCollection,
            mockResultListenerClientPrivateKey,
            mockTrustCertCollection,
            mockTestResultListener,
            defaultPort = 1234,
            maxRetryAttempt = 1,
            executorService = MoreExecutors.newDirectExecutorService(),
        ) { port ->
            throw IOException("port: ${port} is not available")
        }

        assertThat(server).isNull()
    }

    @Test
    fun availablePortFoundAfterRetry() {
        val serverName = InProcessServerBuilder.generateName()

        var capturedPort: Int? = null

        val server = UtpTestResultListenerServer.startServer(
            mockTrustCertCollection,
            mockResultListenerClientPrivateKey,
            mockTrustCertCollection,
            mockTestResultListener,
            defaultPort = 1234,
            maxRetryAttempt = 2,
            executorService = MoreExecutors.newDirectExecutorService(),
        ) { port ->
            if (port == 1234) {
                throw IOException("port: ${port} is not available")
            }
            capturedPort = port
            InProcessServerBuilder.forName(serverName)
        }

        requireNotNull(server)
        grpcCleanup.register(server.server)

        assertThat(capturedPort).isEqualTo(1235)

        server.close()
    }

    @Test
    fun recordTestResultEvent() {
        val serverName = InProcessServerBuilder.generateName()

        val server = UtpTestResultListenerServer.startServer(
            mockTrustCertCollection,
            mockResultListenerClientPrivateKey,
            mockTrustCertCollection,
            mockTestResultListener,
            defaultPort = 1234,
            maxRetryAttempt = 1,
            executorService = MoreExecutors.newDirectExecutorService(),
        ) {
            InProcessServerBuilder.forName(serverName)
        }

        requireNotNull(server)
        grpcCleanup.register(server.server)

        val stub = GradleAndroidTestResultListenerServiceGrpc.newStub(
            grpcCleanup.register(
                InProcessChannelBuilder
                    .forName(serverName)
                    .directExecutor()
                    .build()))

        lateinit var response: RecordTestResultEventResponse
        var completed = false
        val requestObserver = stub.recordTestResultEvent(
            object: StreamObserver<RecordTestResultEventResponse>{
                override fun onNext(res: RecordTestResultEventResponse) {
                    response = res
                }

                override fun onError(error: Throwable) {}

                override fun onCompleted() {
                    completed = true
                }
            })

        requestObserver.onNext(
            TestResultEvent.newBuilder().apply {
                testSuiteStarted = TestSuiteStarted.newBuilder().apply {
                    deviceId = "testDeviceId"
                }.build()
            }.build()
        )
        requestObserver.onCompleted()

        assertThat(completed).isTrue()
        assertThat(response).isEqualTo(RecordTestResultEventResponse.getDefaultInstance())

        inOrder(mockTestResultListener).apply {
            verify(mockTestResultListener).onTestResultEvent(
                eq(TestResultEvent.newBuilder().apply {
                    testSuiteStarted = TestSuiteStarted.newBuilder().apply {
                        deviceId = "testDeviceId"
                    }.build()
                }.build()))
        }

        server.close()
    }

    @Test
    fun recordTestResultEvent_CancelledError() {
        val serverName = InProcessServerBuilder.generateName()
        val server = UtpTestResultListenerServer.startServer(
            mockTrustCertCollection,
            mockResultListenerClientPrivateKey,
            mockTrustCertCollection,
            mockTestResultListener,
            defaultPort = 1234,
            maxRetryAttempt = 1,
            executorService = MoreExecutors.newDirectExecutorService(),
        ) {
            InProcessServerBuilder.forName(serverName)
        }
        requireNotNull(server)
        grpcCleanup.register(server.server)

        val stub = GradleAndroidTestResultListenerServiceGrpc.newStub(
            grpcCleanup.register(
                InProcessChannelBuilder
                    .forName(serverName)
                    .directExecutor()
                    .build()))

        val requestObserver = stub.recordTestResultEvent(
            object: StreamObserver<RecordTestResultEventResponse> {
                override fun onNext(res: RecordTestResultEventResponse) {}
                override fun onError(error: Throwable) {}
                override fun onCompleted() {}
            })

        val initialEvent = TestResultEvent.newBuilder().apply {
            deviceId = "testDeviceId"
        }.build()
        requestObserver.onNext(initialEvent)

        val cancelError = Status.CANCELLED.asRuntimeException()
        requestObserver.onError(cancelError)

        val expectedErrorEvent = TestResultEvent.newBuilder().apply {
            deviceId = "testDeviceId"
            testSuiteFinishedBuilder.apply {
                testSuiteResult = Any.pack(TestSuiteResult.newBuilder().apply {
                    testStatus = TestStatusProto.TestStatus.CANCELLED
                }.build())
            }
        }.build()

        inOrder(mockTestResultListener).apply {
            verify(mockTestResultListener).onTestResultEvent(eq(initialEvent))
            verify(mockTestResultListener).onTestResultEvent(eq(expectedErrorEvent))
        }

        server.close()
    }
}
