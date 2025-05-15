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

package com.android.build.gradle.tasks

import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.internal.fixtures.FakeGradleRegularFile
import com.android.build.gradle.internal.testsuites.impl.TestEngineInputProperties
import com.android.build.gradle.internal.testsuites.impl.TestEngineInputProperty
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

class TestSuiteTestTaskTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun testSerializer() {
        val outputFile = folder.newFile()
        TestSuiteTestTask.AgpTestSuiteInputsSerializer.serialize(
            engineInputParameters = listOf(
                TestEngineInputProperty(
                    AgpTestSuiteInputParameters.TESTED_APKS.propertyName,
                    "some/random/location"
                )),
            engineInputProperties = mapOf("foo" to "fooValue"),
            outputFile
        )
        assertThat(outputFile.exists()).isTrue()
        val serializedInputs = TestEngineInputProperties.read(outputFile)
        assertThat(serializedInputs.properties).hasSize(2)
    }
 }
