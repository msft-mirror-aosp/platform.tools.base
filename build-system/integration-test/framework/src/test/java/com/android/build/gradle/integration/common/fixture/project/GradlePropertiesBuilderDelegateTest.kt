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

package com.android.build.gradle.integration.common.fixture.project

import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

class GradlePropertiesBuilderDelegateTest {

    @get:Rule
    val exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun testBooleanAdd() {
        val delegate = GradlePropertiesBuilderDelegate()

        delegate.add(BooleanOption.ENABLE_TEST_FIXTURES, true)

        Truth.assertThat(delegate.properties).containsExactly("android.experimental.enableTestFixtures=true")
    }

    @Test
    fun testStringAdd() {
        val delegate = GradlePropertiesBuilderDelegate()

        delegate.add(StringOption.LINT_HEAP_SIZE, "twelve")

        Truth.assertThat(delegate.properties).containsExactly("android.experimental.lint.heapSize=twelve")
    }

    @Test
    fun testConficts() {
        val delegate = GradlePropertiesBuilderDelegate()

        delegate.add(BooleanOption.ENABLE_TEST_FIXTURES, true)
        delegate.add("android.experimental.enableTestFixtures","true")

        exceptionRule.expect(RuntimeException::class.java)
        delegate.properties
    }
}
