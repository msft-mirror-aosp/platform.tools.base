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

object JourneysTestEngineInput {
    val journeysInputDir: File = getFileFromSystemProperty("journeysInputDir")
    val testDeviceId: String = getSystemProperty("testDeviceId")
    val testDeviceDisplayName: String = getSystemProperty("testDeviceDisplayName")
    val journeysFilter: List<String> = getStringListFromSystemProperty("journeysFilter")
    val resultsDir: File = getFileFromSystemProperty("resultsDir")

    object ProxyInput {
        val applicationId: String = getSystemProperty("Proxy.applicationId")
        val appApkPath: File = getFileFromSystemProperty("Proxy.appApkPath")
        val crawlerApkPath: File = getFileFromSystemProperty("Proxy.crawlerApkPath")
        val adbPath: File = getFileFromSystemProperty("Proxy.adbPath")
        val accessTokenPath: String = getSystemProperty("Proxy.accessTokenPath")
    }
}

private fun getSystemProperty(propertyName: String): String {
    return System.getProperty("JourneysTestEngineInput.$propertyName", "")
}

private fun getStringListFromSystemProperty(propertyName: String): List<String> {
    val property = getSystemProperty(propertyName)
    if (property.isBlank()) { return emptyList() }
    return property.split(",").map { it.trim() }
}

private fun getFileFromSystemProperty(propertyName: String): File {
    return File(getSystemProperty(propertyName))
}
