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

package com.android.build.gradle.internal.test.report

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Test

class TestReportDataModelTest {

    private val gson: Gson = GsonBuilder().create()

    @Test
    fun `test Function serialization`() {
        val function = Function(
            name = "testSomething",
            results = mapOf(
                "debug" to TestResults("pass"),
                "release" to TestResults("fail")
            )
        )

        val jsonString = gson.toJson(function)

        assertThat(jsonString).contains("\"name\":\"testSomething\"")
        assertThat(jsonString).contains("\"debug\":{\"status\":\"pass\"}")
        assertThat(jsonString).contains("\"release\":{\"status\":\"fail\"}")
    }

    @Test
    fun `test Function deserialization`() {
        val jsonString = """{"name":"testSomething","results":{"debug":{"status":"pass"},"release":{"status":"fail"}}}"""
        val function = gson.fromJson(jsonString, Function::class.java)

        assertThat(function.name).isEqualTo("testSomething")
        assertThat(function.results).containsKey("debug")
        assertThat(function.results["debug"]?.status).isEqualTo("pass")
        assertThat(function.results).containsKey("release")
        assertThat(function.results["release"]?.status).isEqualTo("fail")
    }

    @Test
    fun `test RootReport serialization`() {
        val function = Function(
            name = "test1",
            results = mapOf("debug" to TestResults("pass"))
        )
        val classType = ClassType(
            name = "MyTest",
            functions = listOf(function)
        )
        val pkg = Package(
            name = "com.example",
            classes = listOf(classType)
        )
        val testSuite = TestSuite(
            name = "suite1",
            packages = listOf(pkg)
        )
        val module = Module(
            name = ":app",
            testSuites = listOf(testSuite)
        )
        val rootReport = RootReport(
            variants = listOf("debug", "release"),
            modules = listOf(module)
        )

        val jsonString = gson.toJson(rootReport)

        assertThat(jsonString).contains(""""variants":["debug","release"]""")
        assertThat(jsonString).contains(""""name":":app"""")
        assertThat(jsonString).contains(""""name":"test1"""")
        assertThat(jsonString).contains(""""debug":{"status":"pass"}""")
    }
}

