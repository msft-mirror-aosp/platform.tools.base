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

package com.android.build.gradle.integration.internal

import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

class ArtifactsLocationsReportTest {
    @get:Rule
    val rule = GradleRule.from {
        gradleProperties {
            add(BooleanOption.DUMP_ARTIFACTS_LOCATIONS.propertyName, "true")
        }
        androidApplication {
            android {
            }
        }
    }

    @Test
    fun testDumping() {
        val gradleBuild: GradleBuild = rule.build
        val result = gradleBuild.executor.run("assembleDebug")
        Truth.assertThat(result.didWorkTasks).contains(":app:dumpDebugArtifactsLocations")
        val artifactsLocations = gradleBuild.androidApplication().intermediatesDir
            .resolve("agp-debug/debug/dump-artifacts-locations.json")
        Truth.assertThat(artifactsLocations.toFile().exists()).isTrue()
        val data: Map<String, List<Map<String, String>>> = loadJsonFromFile(artifactsLocations.toFile())
            ?: throw RuntimeException("Cannot load the artifacts locations json file")
        Truth.assertThat(data).isNotEmpty()

        // extract one record and verify that the location points to an existing file.
        val artifactData = data["single"]?.single {
            it["artifact"] == "COMPILE_AND_RUNTIME_R_CLASS_JAR"
        }
        Truth.assertThat(artifactData).isNotNull()
        val artifactFile = artifactData!!["location"]
        Truth.assertThat(artifactFile).isNotNull()
        Truth.assertThat(File(artifactFile!!).exists()).isTrue()
    }

    private inline fun <reified T> loadJsonFromFile(file: File): T? {
        return try {
            val gson = Gson()
            val json = file.readText()
            gson.fromJson(json, object : TypeToken<T>() {}.type)
        } catch (e: JsonParseException) {
            // Handle JSON parsing errors
            println("Error parsing JSON: ${e.message}")
            null // Or throw an exception depending on your needs
        } catch (e: IOException) {
            // Handle file reading errors
            println("Error reading file: ${e.message}")
            null // Or throw an exception
        }
    }
}
