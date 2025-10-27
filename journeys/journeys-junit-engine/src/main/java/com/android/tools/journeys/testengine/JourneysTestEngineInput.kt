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
package com.android.tools.journeys.testengine

import java.io.File
import java.io.FileReader
import java.util.Properties

object JourneysTestEngineInput {

    val journeysInputDir: File
    val testDeviceIds: String
    val testDeviceDisplayNames: String
    val journeysFilter: List<String> =
        getStringListFromSystemPropertyOrEnvVar("journeysFilter", "JOURNEYS_FILTER")
    val resultsDir: File

    // Enabled by default. Can be disabled by setting to "false".
    val enableStdoutReport: Boolean =
        getSystemPropertyOrEnvVar("", "JOURNEYS_ENABLE_STDOUT_REPORT").ifBlank { "true" }
            .toBoolean()

    init {
        if (System.getenv("com.android.junit.engine.input.parameters") != null) {
            val inputProperties = Properties().also {
                it.load(FileReader(System.getenv("com.android.junit.engine.input.parameters")))
            }
            journeysInputDir =
                File(inputProperties["com.android.junit.engine.source.folders"].toString())
            testDeviceIds = inputProperties["com.android.junit.engine.serial.ids"].toString()
            testDeviceDisplayNames = ""
            resultsDir = File(inputProperties["com.android.junit.engine.results.dir"].toString())
        } else {
            journeysInputDir = getFileFromSystemProperty("journeysInputDir")
            testDeviceIds = getSystemProperty("testDeviceId")
            testDeviceDisplayNames = getSystemProperty("testDeviceDisplayName")
            resultsDir = getFileFromSystemProperty("resultsDir")
        }
    }

    object ProxyInput {

        val applicationId: String
        val appApkPath: File
        val adbPath: File
        val accessTokenPath: String =
            getSystemPropertyOrEnvVar("Proxy.accessTokenPath", "GEMINI_ACCESS_TOKEN_PATH")

        // For internal testing only.
        // Overrides the application id provided by the project open in studio and is to allow
        // testing on any pre-installed apps by opening a dummy project in studio.
        val customAppId: String = getSystemPropertyOrEnvVar("", "JOURNEYS_CUSTOM_APP_ID")

        init {
            if (System.getenv("com.android.junit.engine.input.parameters") != null) {
                val inputProperties = Properties().also {
                    it.load(FileReader(System.getenv("com.android.junit.engine.input.parameters")))
                }
                applicationId =
                    inputProperties["com.android.junit.engine.tested.application.id"].toString()
                appApkPath = File(inputProperties["com.android.agp.test.TESTED_APKS"].toString())
                adbPath = File(inputProperties["com.android.agp.test.ADB_EXECUTABLE"].toString())
            } else {
                applicationId = getSystemProperty("Proxy.applicationId")
                appApkPath = getFileFromSystemProperty("Proxy.appApkPath")
                adbPath = getFileFromSystemProperty("Proxy.adbPath")
            }
        }
    }

    object RoboConverterInput {

        // For internal testing only.
        // To allow experimenting with different robo agents.
        val agentName: String = getSystemPropertyOrEnvVar("", "JOURNEYS_AGENT_NAME")

        // For internal testing only.
        // To allow enabling experimental features on robo agents.
        val enableExperimentalMode: Boolean =
            getSystemPropertyOrEnvVar("", "JOURNEYS_ENABLE_EXPERIMENTAL_MODE").toBoolean()
    }
}

private fun getSystemProperty(propertyName: String): String {
    return System.getProperty("JourneysTestEngineInput.$propertyName", "")
}

private fun getSystemPropertyOrEnvVar(propertyName: String, envVarName: String): String {
    val property = getSystemProperty(propertyName)
    if (!property.isNullOrBlank()) {
        return property
    }
    return System.getenv(envVarName) ?: ""
}

private fun getStringListFromSystemProperty(propertyName: String): List<String> {
    val property = getSystemProperty(propertyName)
    if (property.isBlank()) {
        return emptyList()
    }
    return property.split(",").map { it.trim() }
}

private fun getStringListFromSystemPropertyOrEnvVar(
    propertyName: String,
    envVarName: String
): List<String> {
    val property = getStringListFromSystemProperty(propertyName)
    if (property.isNotEmpty()) {
        return property
    }
    val envVar = System.getenv(envVarName)
    if (envVar.isNullOrBlank()) {
        return emptyList()
    }
    return envVar.split(",").map { it.trim() }
}

private fun getFileFromSystemProperty(propertyName: String): File {
    return File(getSystemProperty(propertyName))
}
