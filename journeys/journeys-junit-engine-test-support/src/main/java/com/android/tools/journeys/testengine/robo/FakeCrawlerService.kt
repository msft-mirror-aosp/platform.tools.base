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

import androidx.test.tools.crawler.output.Crawl
import androidx.test.tools.crawler.proto.RemotePlatformRequest
import androidx.test.tools.crawler.proto.RemotePlatformResponse
import com.google.cloud.test.appcrawler.proto.Artifact
import com.google.cloud.test.appcrawler.proto.CrawlResult
import com.google.cloud.test.appcrawler.proto.CrawlSetup
import com.google.cloud.test.appcrawler.proto.CrawlSetupResponse
import com.google.cloud.test.appcrawler.proto.CrawlerRequest
import com.google.cloud.test.appcrawler.proto.CrawlerResponse
import com.google.cloud.test.appcrawler.proto.CrawlerServiceGrpc
import com.google.cloud.test.appcrawler.proto.EchoRequest
import com.google.cloud.test.appcrawler.proto.EchoResponse
import com.google.common.base.VerifyException
import com.google.protobuf.Empty
import com.google.protobuf.TextFormat
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.io.File
import java.io.IOException

class FakeCrawlerService private constructor(
    private val shouldInduceServerError: Boolean,
    private val shouldCompleteNormally: Boolean
) : CrawlerServiceGrpc.CrawlerServiceImplBase() {

    constructor() : this(false, true) {
        val roboResultsPath = System.getProperty("FakeCrawlerServiceInput.roboResultsPath", null)
        if (!roboResultsPath.isNullOrEmpty()) {
            val content = File(roboResultsPath).readText()
            masterCrawl = TextFormat.parse(content, Crawl::class.java)
        }
    }

    private var crawlSetup: CrawlSetup = CrawlSetup.getDefaultInstance()
    private var masterCrawl = Crawl.getDefaultInstance()

    companion object {
        @JvmStatic
        fun withServerError(): FakeCrawlerService {
            return FakeCrawlerService(true, true)
        }

        @JvmStatic
        fun withInCompleteCrawl(): FakeCrawlerService {
            return FakeCrawlerService(false, false)
        }
    }

    override fun echo(request: EchoRequest, responseObserver: StreamObserver<EchoResponse>) {
        responseObserver.onNext(EchoResponse.getDefaultInstance())
        responseObserver.onCompleted()
    }

    override fun echoStream(responseObserver: StreamObserver<EchoResponse>): StreamObserver<EchoRequest> {
        responseObserver.onNext(EchoResponse.getDefaultInstance())
        responseObserver.onCompleted()
        return object : StreamObserver<EchoRequest> {
            override fun onNext(value: EchoRequest) {}
            override fun onError(t: Throwable) {}
            override fun onCompleted() {}
        }
    }

    override fun crawl(responseObserver: StreamObserver<CrawlerResponse>): StreamObserver<CrawlerRequest> {
        return object : StreamObserver<CrawlerRequest> {
            var finished = false

            override fun onNext(value: CrawlerRequest) {
                try {
                    if (shouldInduceServerError) {
                        throw VerifyException("Intentionally throwing an error.")
                    }

                    when (value.requestCase) {
                        CrawlerRequest.RequestCase.CRAWL_SETUP -> {
                            crawlSetup = value.crawlSetup
                            responseObserver.onNext(
                                CrawlerResponse.newBuilder()
                                    .setCrawlSetupResponse(CrawlSetupResponse.getDefaultInstance())
                                    .build()
                            )
                        }

                        CrawlerRequest.RequestCase.PLATFORM_RESPONSE -> {
                            if (value.platformResponse.responseCase == RemotePlatformResponse.ResponseCase.RESPONSE_NOT_SET) {
                                throw IllegalStateException("PlatformResponse was not set for test purposes.")
                            }

                            streamCrawlUpdates(responseObserver)
                            if (shouldCompleteNormally) {
                                responseObserver.onNext(
                                    CrawlerResponse.newBuilder()
                                        .setPlatformRequest(
                                            RemotePlatformRequest.newBuilder()
                                                .setCrawlOver(Empty.getDefaultInstance())
                                        )
                                        .build()
                                )
                            }
                            responseObserver.onNext(
                                CrawlerResponse.newBuilder()
                                    .setCrawlResult(CrawlResult.newBuilder().setOutcome(CrawlResult.Outcome.COMPLETED))
                                    .build()
                            )
                            finished = true
                            responseObserver.onCompleted()
                        }

                        CrawlerRequest.RequestCase.PLATFORM_UNREACHABLE -> {
                            if (!finished) {
                                responseObserver.onNext(
                                    CrawlerResponse.newBuilder()
                                        .setCrawlResult(CrawlResult.newBuilder().setOutcome(CrawlResult.Outcome.INCOMPLETE))
                                        .build()
                                )
                            }
                        }

                        else -> {
                        }
                    }
                } catch (e: IOException) {
                    throw IllegalStateException(e)
                }
            }

            private fun streamCrawlUpdates(responseObserver: StreamObserver<CrawlerResponse>) {
                val currentMasterCrawl = masterCrawl ?: run {
                    responseObserver.onError(
                        Status.INTERNAL.withDescription("No master Crawl proto provided to FakeCrawlerService.")
                            .asRuntimeException()
                    )
                    return
                }

                for (actionIndex in currentMasterCrawl.actionsList.indices) {
                    val currentAction = currentMasterCrawl.getActions(actionIndex)
                    val currentDisplayState = currentMasterCrawl.displayStatesList.find {
                        it.displayStateId == currentAction.displayStateId
                    }

                    currentDisplayState?.let {
                        val displayStatePartialCrawl = Crawl.newBuilder()
                            .setCrawlIdentifier(currentMasterCrawl.crawlIdentifier)
                            .setAppPackageId(currentMasterCrawl.appPackageId)
                            .addDisplayStates(it)
                            .setCrawlResult(Crawl.CrawlResult.UNDEFINED_CRAWL_RESULT)
                            .build()
                            .toByteString()
                        responseObserver.onNext(
                            CrawlerResponse.newBuilder()
                                .setArtifact(
                                    Artifact.newBuilder()
                                        .setName(RoboConfigConstants.ROBO_RESULTS_FILE_NAME)
                                        .setAppend(true)

                                        .setData(displayStatePartialCrawl)
                                )
                                .build()
                        )
                    }

                    val actionPartialCrawl = Crawl.newBuilder()
                        .setCrawlIdentifier(currentMasterCrawl.crawlIdentifier)
                        .setAppPackageId(currentMasterCrawl.appPackageId)
                        .addActions(currentAction)
                        .setCrawlResult(Crawl.CrawlResult.UNDEFINED_CRAWL_RESULT)
                        .build()
                        .toByteString()

                    responseObserver.onNext(
                        CrawlerResponse.newBuilder()
                            .setArtifact(
                                Artifact.newBuilder()
                                    .setName(RoboConfigConstants.ROBO_RESULTS_FILE_NAME)
                                    .setAppend(true)
                                    .setData(actionPartialCrawl)
                            )
                            .build()
                    )
                }
            }

            override fun onError(t: Throwable) {
                System.err.println("FakeCrawlerService: Error in crawl stream from client: ${t.message}")
                t.printStackTrace()
            }

            override fun onCompleted() {
                println("FakeCrawlerService: Client completed its request stream.")
            }
        }
    }
}
