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
package com.android.build.gradle.internal.scope

import com.android.build.api.variant.ComponentIdentity
import com.android.build.gradle.internal.dsl.AgpTestSuiteImpl
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.Mockito

class ProjectDslInfoTest {

    @Test
    fun `empty test suites returns empty list`() {
        val testSuites = emptyList<AgpTestSuiteImpl>()
        val componentIdentity = mockComponentIdentity("variantOne", "debug)")
        val filteredSuites = ProjectDslInfo(testSuites).suitesApplyingToComponent(componentIdentity)
        assertThat(filteredSuites).isEmpty()
    }

    @Test
    fun `test suites without selectors`() {
        val testSuites = listOf(
            mockAgpTestSuite("first"),
            mockAgpTestSuite("second"),
            mockAgpTestSuite("third")
        )
        val projectDslInfo  = ProjectDslInfo(testSuites)
        val variant = mockComponentIdentity("variantOne", "debug")

        val suitesApplyingToComponent = projectDslInfo.suitesApplyingToComponent(variant)
        assertThat(suitesApplyingToComponent.size).isEqualTo(3)
        assertThat(suitesApplyingToComponent.map { it.name }).containsExactly(
            "first", "second", "third"
        )
    }

    @Test
    fun `test suites with variant based selectors`() {
        val testSuites = listOf(
            mockAgpTestSuite("first", listOf("variantX", "variantOne")),
            mockAgpTestSuite("second", listOf("variantOne")),
            mockAgpTestSuite("third", listOf("variantX", "variantY")),
            mockAgpTestSuite("fourth")
        )
        val projectDslInfo  = ProjectDslInfo(testSuites)
        val variant = mockComponentIdentity("variantOne", "debug")

        val suitesApplyingToComponent = projectDslInfo.suitesApplyingToComponent(variant)
        assertThat(suitesApplyingToComponent.size).isEqualTo(3)
        assertThat(suitesApplyingToComponent.map { it.name }).containsExactly(
            "first", "second", "fourth"
        )
    }

    @Test
    fun `test suites with single variant selectors`() {
        val testSuites = listOf(
            mockAgpTestSuiteWithFlavors("first", listOf(Pair("version", "demo"))),
            mockAgpTestSuiteWithFlavors("second", listOf(Pair("version", "full"))),
            mockAgpTestSuiteWithFlavors("third", listOf(Pair("version", "fee"))),
            mockAgpTestSuite("fourth"),
        )
        val projectDslInfo  = ProjectDslInfo(testSuites)
        val variant = mockComponentIdentity(
            name = "variantOne",
            buildType = "debug",
            productFlavors = listOf(Pair("version", "demo"))
        )

        val suitesApplyingToComponent = projectDslInfo.suitesApplyingToComponent(variant)
        assertThat(suitesApplyingToComponent.size).isEqualTo(2)
        assertThat(suitesApplyingToComponent.map { it.name }).containsExactly(
            "first", "fourth"
        )
    }

    @Test
    fun `test suites with multiple variant selectors`() {
        val testSuites = listOf(
            mockAgpTestSuiteWithFlavors("first", listOf(Pair("version", "demo"))),
            mockAgpTestSuiteWithFlavors("second", listOf(Pair("version", "full"))),
            mockAgpTestSuiteWithFlavors("third", listOf(Pair("region", "EMEA"))),
            mockAgpTestSuiteWithFlavors("fourth", listOf(Pair("region", "APAC"))),
            mockAgpTestSuite("fifth")
        )
        val projectDslInfo  = ProjectDslInfo(testSuites)
        val variant = mockComponentIdentity(
            name = "variantOne",
            buildType = "debug",
            productFlavors = listOf(Pair("version", "demo"), Pair("region", "EMEA"))
        )

        val suitesApplyingToComponent = projectDslInfo.suitesApplyingToComponent(variant)
        assertThat(suitesApplyingToComponent.size).isEqualTo(3)
        assertThat(suitesApplyingToComponent.map { it.name }).containsExactly(
            "first", "third", "fifth"
        )
    }

    @Test
    fun `test suites with product flavors and variant selectors`() {
        val testSuites = listOf(
            mockAgpTestSuiteWithFlavors("first", listOf(Pair("version", "demo"))),
            mockAgpTestSuiteWithFlavors("second", listOf(Pair("version", "full"))),
            mockAgpTestSuiteWithFlavors("third", listOf(Pair("region", "EMEA"))),
            mockAgpTestSuiteWithFlavors("fourth", listOf(Pair("region", "APAC"))),
            mockAgpTestSuite("fifth"),
            mockAgpTestSuite("sixth", listOf("variantX", "variantY")),
            mockAgpTestSuite("seventh", listOf("variantX", "variantZ")),
            mockAgpTestSuite("eighth", listOf("variantX", "variantOne"))
        )
        val projectDslInfo  = ProjectDslInfo(testSuites)
        val variant = mockComponentIdentity(
            name = "variantOne",
            buildType = "debug",
            productFlavors = listOf(Pair("version", "demo"), Pair("region", "EMEA"))
        )

        val suitesApplyingToComponent = projectDslInfo.suitesApplyingToComponent(variant)
        assertThat(suitesApplyingToComponent.size).isEqualTo(4)
        assertThat(suitesApplyingToComponent.map { it.name }).containsExactly(
            "first", "third", "fifth", "eighth"
        )
    }

    private fun mockComponentIdentity(
        name: String,
        buildType: String,
        productFlavors: List<Pair<String, String>>? = null
    ) =
        Mockito.mock(ComponentIdentity::class.java).also {
            Mockito.`when`(it.name).thenReturn(name)
            Mockito.`when`(it.buildType).thenReturn(buildType)
            if (productFlavors != null) {
                Mockito.`when`(it.productFlavors).thenReturn(productFlavors)
            }
        }

    private fun mockAgpTestSuite(name: String, targetVariants: List<String>? = null) =
        Mockito.mock(AgpTestSuiteImpl::class.java).also {
            Mockito.`when`(it.name).thenReturn(name)
            if (targetVariants != null) {
                Mockito.`when`(it.targetVariants).thenReturn(targetVariants.toMutableList())
            }
        }

    private fun mockAgpTestSuiteWithFlavors(name: String, productFlavors: List<Pair<String, String>>) =
        Mockito.mock(AgpTestSuiteImpl::class.java).also {
            Mockito.`when`(it.name).thenReturn(name)
            Mockito.`when`(it.targetProductFlavors).thenReturn(productFlavors.toMutableList())
        }
}
