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

package com.android.tools.journeys

import com.android.build.api.AndroidPluginVersion
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.android.build.gradle.AppPlugin
import com.android.tools.journeys.tasks.JourneysValidationTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaBasePlugin
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

private val minAgpVersion = AndroidPluginVersion(8, 2, 1)
private val maxAgpVersion = AndroidPluginVersion(8, 11, 255)

/**
 * An entry point for Journeys plugin that adds support for Journeys testing.
 */
class JourneysGradlePlugin : Plugin<Project> {

    companion object {

        val JOURNEYS_TEST_PLUGIN_VERSION: String by lazy {
            requireNotNull(JourneysGradlePlugin::class.java.getResourceAsStream("/version.properties"))
                .buffered().use { stream ->
                    Properties().let { properties ->
                        properties.load(stream)
                        properties.getProperty("buildVersion")
                    }
                }
        }

        val APPCRAWLER_VERSION: String by lazy {
            requireNotNull(JourneysGradlePlugin::class.java.getResourceAsStream("/version.properties"))
                .buffered().use { stream ->
                    Properties().let { properties ->
                        properties.load(stream)
                        properties.getProperty("appcrawlerVersion")
                    }
                }
        }
    }

    override fun apply(project: Project) {
        project.plugins.withType(AppPlugin::class.java) {
            val componentsExtension =
                project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            val agpVersion = componentsExtension.pluginVersion
            if (agpVersion < minAgpVersion || (agpVersion >= maxAgpVersion && agpVersion.previewType != "dev")) {
                error(
                    """
                    Journeys plugin requires Android Gradle plugin version between ${minAgpVersion.toVersionString()} and ${maxAgpVersion.major}.${maxAgpVersion.minor}.
                    Current version is $agpVersion.
                    """.trimIndent()
                )
            }

            fun Variant.computePathSegments(): String {
                return buildType?.let { bt ->
                    flavorName?.let { fn ->
                        "$bt/$fn"
                    } ?: bt
                } ?: flavorName ?: ""
            }

            val buildDir = project.layout.buildDirectory

            val validateAllTask = project.tasks.register(
                "validateJourneysTest",
                Task::class.java
            ) { task ->
                task.description = "Run journeys tests for all variants."
                task.group = JavaBasePlugin.VERIFICATION_GROUP
            }

            maybeCreateJourneysTestConfiguration(project)
            maybeCreateCrawlerApkConfiguration(project)

            // TODO(saxenaankita): Remove the workaround to generate universal apk once
            // test suites is available.
            componentsExtension.finalizeDsl { extension ->
                if (extension.splits.abi.isEnable) {
                    extension.splits.abi.isUniversalApk = true
                }
            }

            componentsExtension.onVariants { variant ->
                val variantName = variant.name
                val variantSegments = variant.computePathSegments()

                val journeysValidationTask = project.tasks.register(
                    "validate${variantName.capitalized()}JourneysTest",
                    JourneysValidationTask::class.java
                ) { task ->
                    task.description = "Run journeys tests for $variantName build."
                    task.group = JavaBasePlugin.VERIFICATION_GROUP

                    task.journeysInputDir.set(
                        project.layout.projectDirectory.dir(
                            journeysInputDir
                        )
                    )
                    task.resultsDir.set(buildDir.dir("outputs/journeysTest/$variantSegments/results"))
                    task.apkDirectories.add(variant.artifacts.get(SingleArtifact.APK))
                    task.journeysFilter.set(project.providers.gradleProperty("journeysFilter"))
                    task.applicationId.set(variant.applicationId)
                    task.adbExecutable.set(componentsExtension.sdkComponents.adb)
                    task.accessTokenFilePath.set(project.providers.gradleProperty("JourneysTestEngineInput.Proxy.accessTokenPath"))

                    task.isScanForTestClasses = false

                    task.journeysCrawlerConfig.from(
                        project.configurations.getByName(crawlerApkConfigName)
                    )

                    task.useJUnitPlatform {
                        it.includeEngines("journeys-test-engine")
                        it.excludeEngines("junit-jupiter")
                    }
                    task.testLogging {
                        it.showStandardStreams = true
                    }
                    task.reports {
                        it.junitXml.required.set(true)
                        it.html.required.set(true)
                    }

                    val classesDir = buildDir.file(task.name)
                    generateJourneysClass(classesDir.get().asFile)

                    task.testClassesDirs = buildDir.files(classesDir)
                    task.classpath =
                        task.project.configurations.getByName(journeysEngineConfigName) + task.testClassesDirs
                }
                validateAllTask.configure { it.dependsOn(journeysValidationTask) }
            }
        }
    }

    private fun String.capitalized(): String {
        return replaceFirstChar { it.uppercase() }
    }

    private fun AndroidPluginVersion.toVersionString(): String {
        val builder = StringBuilder("$major.$minor.$micro")
        previewType?.let { builder.append("-$it") }
        if (preview > 0) {
            builder.append(preview.toString().padStart(2, '0'))
        }
        return builder.toString()
    }

    private fun generateJourneysClass(location: File) {
        val classWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        classWriter.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
            journeysClassName,
            null,
            "java/lang/Object",
            null
        )
        classWriter.visitEnd()

        val classBytes = classWriter.toByteArray()
        location.mkdirs()
        val file = FileOutputStream(File(location, "$journeysClassName.class"))
        file.write(classBytes)
        file.close()
    }

    private fun maybeCreateJourneysTestConfiguration(project: Project) {
        val container = project.configurations
        val dependencies = project.dependencies
        if (container.findByName(journeysEngineConfigName) == null) {
            container.create(journeysEngineConfigName).apply {
                isVisible = false
                isTransitive = true
                isCanBeConsumed = false
                description = "A configuration to resolve journeys test engine dependencies."
            }

            dependencies.add(journeysEngineConfigName, "org.junit.platform:junit-platform-launcher")
            dependencies.add(
                journeysEngineConfigName,
                "com.android.tools.journeys:journeys-junit-engine:$JOURNEYS_TEST_PLUGIN_VERSION"
            )
        }
    }

    private fun maybeCreateCrawlerApkConfiguration(project: Project) {
        val container = project.configurations
        val dependencies = project.dependencies
        if (container.findByName(crawlerApkConfigName) == null) {
            container.create(crawlerApkConfigName).apply {
                isVisible = false
                isTransitive = true
                isCanBeConsumed = false
                description = "A configuration to resolve crawler app dependency."
            }

            dependencies.add(
                crawlerApkConfigName,
                "com.google.android.appcrawler:appcrawler-app:${APPCRAWLER_VERSION}@apk"
            )
        }
    }
}

private const val journeysEngineConfigName = "_internal-journeys-validation-junit-engine"
private const val crawlerApkConfigName = "_internal-journeys-crawler-apk"
private const val journeysClassName = "JourneysEntryPoint"
private const val journeysInputDir = "src/journeysTest"
