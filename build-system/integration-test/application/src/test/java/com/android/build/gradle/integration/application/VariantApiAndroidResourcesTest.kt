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

package com.android.build.gradle.integration.application

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.plugins.AndroidKotlinMultiplatformLibraryComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test
import java.io.File

class VariantApiAndroidResourcesTest {
    @get:Rule
    val rule = GradleRule.from {
        androidKotlinMultiplatformLibrary(":kmplibrary", createMinimumProject = false) {
            android {
                namespace = "com.kmplib.foo"
                compileSdk = DEFAULT_COMPILE_SDK_VERSION
                androidResources.enable = true
            }

            pluginCallbacks += KmpCallback::class.java
        }

        androidLibrary(":androidlibrary", createMinimumProject = false) {
            android {
                namespace = "com.androidlib.foo"
                compileSdk = DEFAULT_COMPILE_SDK_VERSION
                androidResources.enable = true
            }
            pluginCallbacks += LibraryCallback::class.java
        }
    }

    class KmpCallback: AndroidKotlinMultiplatformLibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            extension: KotlinMultiplatformAndroidComponentsExtension
        ) {
            extension.onVariants { variant ->
                configureGeneratedResourcesTask(project, variant)
                configureStaticResourcesTask(project, variant)
            }
        }
    }

    class LibraryCallback: LibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: LibraryAndroidComponentsExtension
        ) {
            androidComponents.apply {
                onVariants(selector().all(), { variant ->
                    configureGeneratedResourcesTask(project, variant)
                    configureStaticResourcesTask(project, variant)
                })
            }
        }
    }

    companion object {
        private fun configureGeneratedResourcesTask(project: Project, variant: Variant) {
            val generateRes = project.tasks.register(
                "generate${variant.name}Res",
                GenerateResourcesTask::class.java,
            )
            generateRes.configure {
                it.outputDir.set(project.layout.buildDirectory.dir("generated/generate${variant.name}Res"))
            }

            // use addGeneratedSourceDirectory to add generated directories
            variant.sources.res?.addGeneratedSourceDirectory(generateRes, GenerateResourcesTask::outputDir)
        }

        private fun configureStaticResourcesTask(project: Project, variant: Variant) {
            val staticResourcesPath = "src/${variant.name}/staticRes"
            val stringXmlFile = File(
                File(project.projectDir, staticResourcesPath),
                "values/strings.xml"
            )
            stringXmlFile.parentFile.mkdirs()
            stringXmlFile.writeText("""
                    <?xml version="1.0" encoding="utf-8"?>
                    <resources>
                        <string name="static_string">foobar</string>
                    </resources>
                    """.trimIndent())

            // use addStaticSourceDirectory to add static directories
            variant.sources.res?.addStaticSourceDirectory(staticResourcesPath)
        }
    }

    @Test
    fun testResourcesAddedViaVariantAPI() {
        val build = rule.build
        build.executor
            .withFailOnWarning(false) // b/455891987
            .run(":kmplibrary:bundleAndroidMainAar", ":androidlibrary:bundleDebugAar")

        build.kotlinMultiplatformLibrary(":kmplibrary").assertAar(AarSelector.NO_BUILD_TYPE) {
            publicResFile().isEqualTo("""
                attr commentTextColor
                id page1
            """.trimIndent())
            textSymbolFile().isEqualTo("""
                int string generated_string 0x0
                int string static_string 0x0
            """.trimIndent())
        }

        build.androidLibrary(":androidlibrary").assertAar(AarSelector.DEBUG) {
            textSymbolFile().isEqualTo("""
                int string generated_string 0x0
                int string static_string 0x0
            """.trimIndent())
        }
    }
}

abstract class GenerateResourcesTask : DefaultTask() {
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
                <string name="generated_string">abc</string>
            </resources>
        """.trimIndent())
    }
}
