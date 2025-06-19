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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.plugins.AndroidKotlinMultiplatformLibraryComponentCallback
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformGeneratedResourcesTest {
    @get:Rule
    val rule = GradleRule.from {
        androidKotlinMultiplatformLibrary(":library", createMinimumProject = false) {
            android {
                namespace = "com.mylibrary.foo"
                compileSdk = DEFAULT_COMPILE_SDK_VERSION
                androidResources.enable = true
            }

            pluginCallbacks += Callback::class.java
        }
    }

    class Callback: AndroidKotlinMultiplatformLibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            extension: KotlinMultiplatformAndroidComponentsExtension
        ) {
            val generateRes = project.tasks.register(
                "generateResources",
                KMP_GenerateResources::class.java
            )
            generateRes.configure {
                it.outputDir.set(project.layout.buildDirectory.dir("generated/generateResTask"))
            }

            extension.onVariants { variant ->
                variant.sources.res?.addGeneratedSourceDirectory(generateRes, KMP_GenerateResources::outputDir)
            }
        }
    }

    @Test
    fun testGeneratedResources() {
        val build = rule.build
        build.executor.run(":library:bundleAndroidMainAar")

        build.kotlinMultiplatformLibrary(":library").assertAar(AarSelector.NO_BUILD_TYPE) {
            publicResFile().contains("""
                attr commentTextColor
                id page1
            """.trimIndent())
            textSymbolFile().contains("""
                int string some_text 0x0
            """.trimIndent())
        }
    }
}

abstract class KMP_GenerateResources : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun taskAction() {
        val valuesFolder = outputDir.get().dir("values")
        val publicXml = valuesFolder.file("public.xml").asFile
        publicXml.parentFile.mkdirs()
        publicXml.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <public type="attr" name="commentTextColor" id="0xAA010007" />
                <public type="id" name="page1" id="0xAA0d0015" />
            </resources>
        """.trimIndent())

        val stringsXml = valuesFolder.file("strings.xml").asFile
        stringsXml.parentFile.mkdirs()
        stringsXml.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="some_text">abc</string>
            </resources>
        """.trimIndent())
    }
}
