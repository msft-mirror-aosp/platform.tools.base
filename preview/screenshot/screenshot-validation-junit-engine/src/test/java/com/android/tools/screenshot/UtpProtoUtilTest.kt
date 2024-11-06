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

import org.junit.Test
import com.android.tools.render.compose.ComposeScreenshotResult
import kotlin.test.assertEquals

class UtpProtoUtilTest {
    @Test
    fun testCreateTestCase() {
        val packageName = "packageName"
        val className = "className"
        val displayName = "myTestMethod_myPreviewName_{showBackground=true}"
        val start = 2000L
        val end = 4000L

        val testCase = createTestCase(
            ComposeScreenshotResult(
                "${packageName}.${className}.$displayName",
                "${packageName}.${className}.myTestMethod",
                "imagePath", null),
            "${packageName}.${className}.$displayName",
            start,
            end
        )

        assertEquals(testCase.testPackage, packageName)
        assertEquals(testCase.testClass, className)
        assertEquals(testCase.testMethod, "myTestMethod_myPreviewName_[showBackground=true]")
        assertEquals(testCase.startTime, createTimestampFromMillis(start))
        assertEquals(testCase.endTime, createTimestampFromMillis(end))
    }

    @Test
    fun testCreateTestSuiteMetadata() {
        val testSuiteMetaData = createTestSuiteMetadata("className", 5)

        assertEquals(testSuiteMetaData.testSuiteName, "className")
        assertEquals(testSuiteMetaData.scheduledTestCaseCount, 5)
    }

    @Test
    fun testError() {
        val errorMessage = "Test error"
        val error = createError(errorMessage)

        assertEquals(error.errorMessage, errorMessage)
    }
}
