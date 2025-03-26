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

package com.android.build.api.variant.impl

import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.internal.dsl.JUnitEngineSpecImpl
import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.android.build.gradle.internal.testsuites.impl.JUnitEngineSpecForTestSuiteVariantBuilder
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JUnitEngineSpecImplForTestSuiteVariantTest {

    private val objects = ProjectFactory.project.objects
    private val dslDefinedJUnitEngineSpec = object: JUnitEngineSpecImpl() { }

    @Test
    fun testInputParameters() {

        val variantBuilderJUnitEngineSpec =
            JUnitEngineSpecForTestSuiteVariantBuilder(objects, dslDefinedJUnitEngineSpec).also {
                it.inputs.add(AgpTestSuiteInputParameters.TESTED_APKS)
                it.inputs.add(AgpTestSuiteInputParameters.TESTING_APK)
            }
        val junitEngineSpec = JUnitEngineSpecImplForTestSuiteVariant(
            variantBuilderJUnitEngineSpec,
            { objects.mapProperty(String::class.java, String::class.java) }
        )
        assertThat(junitEngineSpec.inputs).containsExactly(
            AgpTestSuiteInputParameters.TESTED_APKS,
            AgpTestSuiteInputParameters.TESTING_APK,
        )
    }

    @Test
    fun testInputProperties() {
        val variantBuilderJUnitEngineSpec =
            JUnitEngineSpecForTestSuiteVariantBuilder(objects, dslDefinedJUnitEngineSpec).also {
                it.addInputProperty("foo", "fooValue")
                it.addInputProperty("bar", "barValue")
            }
        val junitEngineSpec = JUnitEngineSpecImplForTestSuiteVariant(
            variantBuilderJUnitEngineSpec,
            { objects.mapProperty(String::class.java, String::class.java) }
        ).also {
            it.addInputProperty("foo", "variantFooValue")
            it.addInputProperty("foobar", "foobarValue")
        }
        assertThat(junitEngineSpec.inputProperties.get()).containsExactlyEntriesIn(
            mapOf("foo" to "variantFooValue", "bar" to "barValue", "foobar" to "foobarValue")
        )
    }
}
