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

import com.android.tools.render.compose.BrokenClass
import com.android.tools.render.compose.ImagePathOrMessage
import com.android.tools.render.compose.RenderProblem
import com.android.tools.render.compose.ScreenshotError
import com.google.common.truth.Truth.assertThat
import com.google.testing.platform.proto.api.core.ErrorProto
import com.google.testing.platform.proto.api.core.TestCaseProto
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import java.io.File
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

class SaveResultsUtilTest {
    @get:Rule
    val tempDirRule = TemporaryFolder()
    private val referenceImagePath = "referencePath"
    private val actualPath = "actualPath"
    private val diffPath = "diffPath"

    private lateinit var previewResults: List<PreviewResult>

    private fun createPreviewResultSuccess(): PreviewResult {
        return PreviewResult(0, "package.previewTest1", 2F,"Images match!", ImagePathOrMessage.ImagePath(referenceImagePath), ImagePathOrMessage.ImagePath(actualPath), ImagePathOrMessage.ErrorMessage("Images match!"), TestResult.newBuilder().build())
    }

    private fun createPreviewResultFailed(): PreviewResult {
        return PreviewResult(1, "package.previewTest2", 2F, "Images don't match", ImagePathOrMessage.ImagePath(referenceImagePath),
            ImagePathOrMessage.ImagePath(actualPath), ImagePathOrMessage.ImagePath(diffPath), TestResult.newBuilder().build())
    }

    private fun createPreviewResultError(): PreviewResult {
        return PreviewResult(
            2,
            "package.previewTest3",
            0F,
            "Missing class XYZ",
            ImagePathOrMessage.ImagePath(referenceImagePath),
            ImagePathOrMessage.ErrorMessage("Render error: Class XYZ not found"),
            ImagePathOrMessage.ErrorMessage("No diff available"),
            TestResult.newBuilder().build()
        )
    }

    @Test
    fun testSaveResults() {
        previewResults = listOf(createPreviewResultSuccess(), createPreviewResultSuccess(),
            createPreviewResultError(), createPreviewResultFailed())
        val outputFilePath = tempDirRule.newFile().absolutePath
        saveResults(previewResults, outputFilePath)
        val file = File(outputFilePath)
        assertTrue(file.exists())
        val fileContent =
            javaClass.getResourceAsStream("results.xml")?.readBytes()?.toString(Charsets.UTF_8)!!.trimEnd()
        assertThat(file.readText()).isEqualTo(fileContent)
    }

    @Test
    fun testGetFirstError() {
        assertEquals("errorMessage", getFirstError(ScreenshotError("STATUS", "errorMessage", "", emptyList(), emptyList(), emptyList())))
        assertEquals("stackTrace", getFirstError(ScreenshotError("STATUS", "", "stackTrace", emptyList(), emptyList(), emptyList())))
        assertEquals("stackTrace", getFirstError(ScreenshotError("STATUS", "", "", listOf(RenderProblem("html", "stackTrace"), RenderProblem("html2", "stacktrace2")), emptyList(), emptyList())))
        assertEquals("stackTrace", getFirstError(ScreenshotError("STATUS", "", "", listOf(RenderProblem("html", "stackTrace"), RenderProblem("html2", "stacktrace2")), emptyList(), emptyList())))
        assertEquals("Rendering failed with issue. Broken class name: stackTrace", getFirstError(ScreenshotError("STATUS", "", "", emptyList(), listOf(BrokenClass("name", "stackTrace"), BrokenClass("name2", "stackTrace2")), emptyList())))
        assertEquals("Rendering failed with issue: Missing class(es): name1, name2", getFirstError(ScreenshotError("STATUS", "", "", emptyList(), emptyList(), listOf("name1", "name2"))))
        assertEquals("Rendering failed", getFirstError(ScreenshotError("STATUS", "", "", emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun testSaveUtpResults() {
        val testSuiteResults = listOf(
            createTestSuiteResult()
        )
        val outputFilePath = tempDirRule.newFile().absolutePath
        saveTestSuiteResults(testSuiteResults, outputFilePath)
        val file = File(outputFilePath)

        assertTrue(file.exists())
        val testResult = file.inputStream().use { input ->
            TestSuiteResult.parseFrom(input)
        }

        assertThat(testResult.testResultCount).isEqualTo(3)
        assertThat(testResult.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(testResult.testResultList.single { it.testCase.testMethod == "errorTest" }.testStatus).isEqualTo(
            TestStatus.ERROR)
        assertThat(testResult.testResultList.single { it.testCase.testMethod == "failingTest" }.testStatus).isEqualTo(
            TestStatus.FAILED)
        val passingTest = testResult.testResultList.single { it.testCase.testMethod == "passingTest" }
        assertThat(passingTest.testStatus).isEqualTo(TestStatus.PASSED)
        val thresholdDetailsEntry = passingTest.detailsList.single()
        assertThat(thresholdDetailsEntry.key).isEqualTo("threshold")
        assertThat(thresholdDetailsEntry.value).isEqualTo("100%")
    }

    private fun createTestSuiteResult(): TestSuiteResult {
        return TestSuiteResult.newBuilder().apply {
            addTestResult(createPassedTestResult())
            addTestResult(createFailedTestResult())
            addTestResult(createErrorTestResult())
            testStatus = TestStatus.FAILED
        }.build()
    }

    private fun createErrorTestResult(): TestResult {
        return TestResult.newBuilder().apply {
            testCase = TestCaseProto.TestCase.newBuilder().apply {
                testPackage = "package"
                testClass = "class"
                testMethod = "errorTest"
            }.build()
            testStatus = TestStatus.ERROR
            error = ErrorProto.Error.newBuilder().apply { errorMessage = "Error: Test Failed" }.build()
        }.build()
    }

    private fun createFailedTestResult(): TestResult {
        return TestResult.newBuilder().apply {
            testCase = TestCaseProto.TestCase.newBuilder().apply {
                testPackage = "package"
                testClass = "class"
                testMethod = "failingTest"
            }.build()
            testStatus = TestStatus.FAILED
            error = ErrorProto.Error.newBuilder().apply { errorMessage = "Test Failed" }.build()
        }.build()
    }

    private fun createPassedTestResult(): TestResult {
        return TestResult.newBuilder().apply {
            testCase = TestCaseProto.TestCase.newBuilder().apply {
                testPackage = "package"
                testClass = "class"
                testMethod = "passingTest"
            }.build()
            testStatus = TestStatus.PASSED
            addDetails(createTestDetailsEntry("threshold", "100%"))
        }.build()
    }
}


