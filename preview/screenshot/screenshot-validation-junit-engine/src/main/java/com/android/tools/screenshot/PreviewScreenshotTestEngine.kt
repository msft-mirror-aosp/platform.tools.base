/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.screenshot

import com.android.tools.render.compose.readComposeScreenshotsJson
import com.android.tools.render.compose.readComposeRenderingResultJson
import com.android.tools.render.compose.ComposeScreenshot
import com.android.tools.render.compose.ComposeScreenshotResult
import com.android.tools.render.compose.ImagePathOrMessage
import java.io.File
import javax.imageio.ImageIO
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolver
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.Path
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult
import com.google.testing.platform.proto.api.core.TestStatusProto
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult


class PreviewScreenshotTestEngine : TestEngine {

    private data class Parameters(
        val diffImageDirPath: String,
        val previewsDiscovered: String,
        val referenceImageDirPath: String,
        val renderResultsFilePath: String,
        val renderTaskOutputDir: String?,
        val resultsDirPath: String?,
        val threshold: String?
    )

    private val parameters: Parameters by lazy {
        Parameters(
            requireNotNull(getParam("diffImageDirPath")) { "diffImageDirPath param must not be null" },
            requireNotNull(getParam("previews-discovered")) { "previews-discovered param must not be null" },
            requireNotNull(getParam("referenceImageDirPath")) { "referenceImageDirPath param must not be null" },
            requireNotNull(getParam("renderResultsFilePath")) { "renderResultsFilePath param must not be null" },
            getParam("renderTaskOutputDir"),
            getParam("resultsDirPath"),
            getParam("threshold"),
        )
    }

    override fun getId(): String {
        return "preview-screenshot-test-engine"
    }

    override fun discover(discoveryRequest: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor {
        val engineDescriptor = EngineDescriptor(uniqueId, "Preview Screenshot Test Engine")
        val screenshots: List<ComposeScreenshot> = readComposeScreenshotsJson(File(parameters.previewsDiscovered).reader())
        val screenshotResults = readComposeRenderingResultJson(File(parameters.renderResultsFilePath).reader()).screenshotResults
        val testMap = mutableMapOf<String, MutableSet<Tests.TestMethod>>()
        for (screenshot in screenshots) {
            val methodName = screenshot.methodFQN.split(".").last()
            val className = screenshot.methodFQN.substring(0, screenshot.methodFQN.lastIndexOf("."))
            val previewName = screenshot.previewParams["name"]
            val previewNameList = if (previewName != null) mutableListOf(previewName) else mutableListOf()
            if (testMap.contains(className)) {
                val testMethodSet = testMap[className]!!
                val existingEntry = testMethodSet.find { it.methodName == methodName}
                if (existingEntry != null) {
                    if (previewName != null) {
                        existingEntry.previewNames.add(previewName)
                    }
                } else {
                    testMethodSet.add(
                        Tests.TestMethod(methodName,
                            screenshotResults.filter {
                                it.methodFQN == "$className.${methodName}"
                            }.size > 1,
                            previewNameList))
                }
            } else {
                testMap[className] = mutableSetOf(
                    Tests.TestMethod(methodName,
                        screenshotResults.filter {
                            it.methodFQN == "$className.${methodName}"
                        }.size > 1,
                        previewNameList))
            }
        }
        val tests = Tests(testMap)

        EngineDiscoveryRequestResolver.builder<EngineDescriptor>()
            .addClassContainerSelectorResolver { testClass ->
                tests.classes.contains(testClass.getName())
            }
            .addSelectorResolver { ctx -> ClassSelectorResolver(ctx.classNameFilter, tests) }
            .addSelectorResolver(MethodSelectorResolver(tests))
            .build()
            .resolve(discoveryRequest, engineDescriptor)
        return engineDescriptor
    }

    override fun execute(request: ExecutionRequest) {
        if (request.rootTestDescriptor.children.isEmpty()) return

        val resultFile = File(parameters.renderResultsFilePath)
        val screenshotResults = readComposeRenderingResultJson(resultFile.reader()).screenshotResults
        val composeScreenshots: List<ComposeScreenshot> = readComposeScreenshotsJson(File(parameters.previewsDiscovered).reader())
        val listener = request.engineExecutionListener
        val resultsToSave = mutableListOf<PreviewResult>()
        val testSuiteResultsToSave = mutableListOf<TestSuiteResult>()

        for (classDescriptor in request.rootTestDescriptor.children) {
            val testSuiteResult = TestSuiteResult.newBuilder()
            var testCaseCount = 0

            listener.executionStarted(classDescriptor)
            for (methodDescriptor in classDescriptor.children) {
                if (methodDescriptor is TestMethodTestDescriptor) {
                    val previewResult = runTestMethodThatGeneratesASingleScreenshotTest(
                        methodDescriptor,
                        listener,
                        screenshotResults)
                    resultsToSave.add(previewResult)
                    testSuiteResult.apply {
                        addTestResult(previewResult.testResult)
                    }
                    testCaseCount++
                } else if (methodDescriptor is TestMethodDescriptor) {
                    val previewResults = runTestMethodThatGeneratesMultipleScreenshotTests(
                        methodDescriptor,
                        listener,
                        composeScreenshots,
                        screenshotResults
                    )
                    resultsToSave.addAll(previewResults)
                    previewResults.forEach {
                        testSuiteResult.apply {
                            addTestResult(it.testResult)
                        }
                    }
                    testCaseCount += previewResults.size
                }
            }
            testSuiteResult.apply {
                testSuiteMetaData = createTestSuiteMetadata((classDescriptor as TestClassDescriptor).className, testCaseCount)
                testStatus = if (testResultList.any { it.testStatus != TestStatusProto.TestStatus.PASSED}) TestStatusProto.TestStatus.FAILED else TestStatusProto.TestStatus.PASSED
            }

            listener.executionFinished(classDescriptor, TestExecutionResult.successful())
            testSuiteResultsToSave.add(testSuiteResult.build())
        }
        if (resultsToSave.isNotEmpty()) {
            saveResults(resultsToSave, "${parameters.resultsDirPath}/${TEST_RESULT_XML_FILE_NAME}")
            saveTestSuiteResults(testSuiteResultsToSave, "${parameters.resultsDirPath}/${TEST_RESULT_PB_FILE_NAME}")
        }
    }

    private fun compareImages(
        composeScreenshot: ComposeScreenshotResult,
        testDisplayName: String,
        testStartTime: Long
    ): PreviewResult {
        val referencePath = Path(parameters.referenceImageDirPath).resolve(composeScreenshot.imagePath)
        val actualPath = Path("${parameters.renderTaskOutputDir}").resolve(composeScreenshot.imagePath)
        val diffPath = Paths.get(parameters.diffImageDirPath, composeScreenshot.imagePath)

        val referenceImage = ImagePathOrMessage.ImagePath(referencePath.toString())
        val actualImage = ImagePathOrMessage.ImagePath(actualPath.toString())
        val diffImage = ImagePathOrMessage.ImagePath(diffPath.toString())

        Files.createDirectories(diffPath.parent)
        val testEndTime = System.currentTimeMillis()
        val duration = getDurationInSeconds(testStartTime, testEndTime)

        val testResult = TestResult.newBuilder().apply {
            testCase = createTestCase(composeScreenshot, testDisplayName, testStartTime, testEndTime)
        }

        //renderer failed to generate images
        if (!actualPath.toFile().exists()) {
            val errorMessage = getFirstError(composeScreenshot.error)
            testResult.setTestStatus(TestStatusProto.TestStatus.ERROR)
            testResult.setError(createError(errorMessage))
            return PreviewResult(2,
                composeScreenshot.previewId,
                duration,
                errorMessage,
                referenceImage = if (referencePath.toFile().exists()) referenceImage else ImagePathOrMessage.ErrorMessage("Reference image missing"),
                actualImage = ImagePathOrMessage.ErrorMessage(errorMessage),
                diffImage = ImagePathOrMessage.ErrorMessage("No diff available"),
                testResult.build())
        }

        //Image comparison
        val threshold = parameters.threshold?.toFloat()
        val imageDiffer = if (threshold != null) ImageDiffer.PixelPerfect(threshold) else ImageDiffer.PixelPerfect()
        val verifier = Verify(imageDiffer, diffPath)

        return when (val result = verifier.assertMatchReference(referencePath, ImageIO.read(actualPath.toFile()))) {
            is Verify.AnalysisResult.Failed -> {
                testResult.setTestStatus(TestStatusProto.TestStatus.FAILED)
                result.toPreviewResponse(1,
                    testDisplayName,
                    duration,
                    referenceImage,
                    actualImage,
                    diffImage,
                    testResult.build())
            }

            is Verify.AnalysisResult.Passed -> {
                val diff = if (result.imageDiff.highlights == null) ImagePathOrMessage.ErrorMessage("Images match!") else diffImage
                testResult.setTestStatus(TestStatusProto.TestStatus.PASSED)
                result.toPreviewResponse(0,
                    testDisplayName,
                    duration,
                    referenceImage,
                    actualImage,
                    diff,
                    testResult.build())
            }

            is Verify.AnalysisResult.MissingReference -> {
                val errorMessage = "Reference image missing"
                testResult.setTestStatus(TestStatusProto.TestStatus.FAILED)
                testResult.setError(createError(errorMessage))
                result.toPreviewResponse(1,
                    testDisplayName,
                    duration,
                    ImagePathOrMessage.ErrorMessage(errorMessage),
                    actualImage,
                    ImagePathOrMessage.ErrorMessage("No diff available"),
                    testResult.build())
            }

            is Verify.AnalysisResult.SizeMismatch -> {
                testResult.setTestStatus(TestStatusProto.TestStatus.FAILED)
                testResult.setError(createError(result.message))
                result.toPreviewResponse(1,
                    testDisplayName,
                    duration,
                    referenceImage,
                    actualImage,
                    ImagePathOrMessage.ErrorMessage(result.message),
                    testResult.build())
            }
        }
    }

    private fun getParam(key: String): String? {
        return System.getProperty("com.android.tools.preview.screenshot.junit.engine.${key}")
    }

    private fun getDurationInSeconds(startTimeMillis: Long, endTimeMillis: Long): Float {
        return (endTimeMillis - startTimeMillis) / 1000F
    }

    private fun reportResult(
        listener: EngineExecutionListener,
        screenshot: ComposeScreenshotResult,
        testDescriptor: TestDescriptor,
        testDisplayName: String
    ): PreviewResult {
        val startTime = System.currentTimeMillis()
        listener.executionStarted(testDescriptor)
        val imageComparison = compareImages(screenshot, testDisplayName, startTime)
        val result = if (imageComparison.responseCode != 0) {
            TestExecutionResult.failed(AssertionError(imageComparison.message))
        } else TestExecutionResult.successful()
        listener.executionFinished(testDescriptor, result)
        return imageComparison
    }

    private fun runTestMethodThatGeneratesASingleScreenshotTest(methodDescriptor: TestMethodTestDescriptor,
        listener: EngineExecutionListener,
        screenshotResults: List<ComposeScreenshotResult>): PreviewResult {
        val className: String = methodDescriptor.className
        val methodName: String = methodDescriptor.methodName
        val previewName: String? = methodDescriptor.previewName
        val screenshots =
            screenshotResults.filter {
                it.methodFQN == "$className.${methodName}"
            }
        var displayName = "$className.${methodName}"
        if (!previewName.isNullOrEmpty()) { displayName += "_$previewName"}
        return reportResult(listener, screenshots.single(), methodDescriptor, displayName)
    }

    private fun runTestMethodThatGeneratesMultipleScreenshotTests(
        methodDescriptor: TestMethodDescriptor,
        listener: EngineExecutionListener,
        composeScreenshots: List<ComposeScreenshot>,
        screenshotResults: List<ComposeScreenshotResult>): List<PreviewResult> {
        val results = mutableListOf<PreviewResult>()
        listener.executionStarted(methodDescriptor)
        val className: String = methodDescriptor.className
        val methodName: String = methodDescriptor.methodName
        val screenshots =
            screenshotResults.filter {
                it.methodFQN == "$className.${methodName}"
            }
        for ((run, screenshot) in screenshots.withIndex()) {
            val currentComposePreview = composeScreenshots.single {
                it.methodFQN == "$className.$methodName" && screenshot.previewId == it.previewId
            }
            var suffix = ""
            if (currentComposePreview.previewParams.containsKey("name")) {
                suffix += "_${currentComposePreview.previewParams["name"]}"
            }
            val previewParamsSuffix = currentComposePreview.previewParams.filter { it.key != "name" }
            if (previewParamsSuffix.isNotEmpty()) {
                // Skip "name" parameter because it is added to the suffix above
                suffix += "_${previewParamsSuffix}"
            }
            if (currentComposePreview.methodParams.isNotEmpty()) {
                // Method parameters can generate multiple screenshots from one preview,
                // add the method parameters and the count indicated by the previewId
                suffix += "_${currentComposePreview.methodParams}"
                val paramIndex = screenshot.imagePath.substringBeforeLast(".").substringAfterLast("_")
                suffix += "_$paramIndex"
            }
            val previewTestDescriptor = PreviewTestDescriptor(methodDescriptor, methodName, run, suffix)
            methodDescriptor.addChild(previewTestDescriptor)
            listener.dynamicTestRegistered(previewTestDescriptor)
            listener.executionStarted(previewTestDescriptor)
            results.add(reportResult(listener, screenshot, previewTestDescriptor,"${className}.${methodName}$suffix"))
        }
        listener.executionFinished(methodDescriptor, TestExecutionResult.successful())
        return results
    }
}
