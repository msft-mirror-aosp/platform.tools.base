/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.compose.screenshot.tasks

import com.android.compose.screenshot.report.TestReport
import com.android.compose.screenshot.services.AnalyticsService
import com.android.utils.FileUtils
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import java.io.File

/**
 * Runs screenshot tests of a variant.
 */
@CacheableTask
abstract class PreviewScreenshotValidationTask : Test() {

    @get:Nested
    abstract val testEngineInput: PreviewScreenshotTestEngineInput

    @get:Internal
    abstract val analyticsService: Property<AnalyticsService>

    init {
        classpath = objectFactory.fileCollection().apply {
            from(
                testEngineInput.testRuntimeClassDirs, testEngineInput.testRuntimeJars,
                testEngineInput.mainRuntimeClassDirs, testEngineInput.mainRuntimeJars
            )
        }
        testClassesDirs = objectFactory.fileCollection().apply {
            from(testEngineInput.testProjectJars, testEngineInput.testProjectClassDirs)
        }

        if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
            // Required by LayoutLib.
            jvmArgs("-Djava.security.manager=allow")
        }
        jvmArgs("-Dlayoutlib.thread.profile.slow-rendering.enable=false")
    }

    override fun getClasspath(): ConfigurableFileCollection {
        return super.getClasspath() as ConfigurableFileCollection
    }

    @TaskAction
    override fun executeTests() {
        analyticsService.get().recordTaskAction(path) {
            var testCount = 0
            addTestListener(object : TestListener {
                override fun beforeSuite(suite: TestDescriptor) {}
                override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
                override fun beforeTest(testDescriptor: TestDescriptor?) {
                    testCount++
                }

                override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
            })

            setTestEngineParam(
                "screenshotTestDirectory",
                testEngineInput.testProjectClassDirs.get()
                    .joinToString(File.pathSeparator) { it.asFile.absolutePath })
            setTestEngineParam(
                "screenshotTestJars",
                testEngineInput.testProjectJars.get()
                    .joinToString(File.pathSeparator) { it.asFile.absolutePath })
            setTestEngineParam(
                "mainDirectory",
                testEngineInput.mainProjectClassDirs.get()
                    .joinToString(File.pathSeparator) { it.asFile.absolutePath })
            setTestEngineParam(
                "mainJars",
                testEngineInput.mainProjectJars.get()
                    .joinToString(File.pathSeparator) { it.asFile.absolutePath })
            val testProjectJarSet =
                setOf(
                    *testEngineInput.testProjectJars.get()
                        .map { it.asFile.absolutePath }
                        .toTypedArray()
                )
            setTestEngineParam(
                "dependencyJars",
                testEngineInput.testRuntimeJars.get()
                    .filterNot { it.asFile.absolutePath in testProjectJarSet }
                    .joinToString(File.pathSeparator) { it.asFile.absolutePath })
            setTestEngineParam(
                "previewImageOutputDir",
                testEngineInput.previewImageOutputDir.get().asFile.absolutePath
            )
            setTestEngineParam(
                "previewDiffImageOutputDir",
                testEngineInput.diffImageOutputDir.get().asFile.absolutePath
            )
            setTestEngineParam(
                "referenceImageDir",
                testEngineInput.referenceImageDir.get().asFile.absolutePath
            )
            setTestEngineParam(
                "Renderer.fontsPath",
                testEngineInput.sdkFontsDir.orNull?.asFile?.absolutePath ?: ""
            )
            setTestEngineParam(
                "Renderer.resourceApkPath",
                testEngineInput.resourceApkFile.orNull?.asFile?.absolutePath ?: ""
            )
            setTestEngineParam("Renderer.namespace", testEngineInput.namespace.get())
            setTestEngineParam(
                "Renderer.mainAllClassPath",
                (testEngineInput.mainRuntimeClassDirs.get() + testEngineInput.mainRuntimeJars.get())
                    .joinToString(File.pathSeparator) { it.asFile.absolutePath })
            setTestEngineParam(
                "Renderer.mainProjectClassPath",
                (testEngineInput.mainProjectClassDirs.get() + testEngineInput.mainProjectJars.get())
                    .joinToString(File.pathSeparator) { it.asFile.absolutePath })
            setTestEngineParam(
                "Renderer.screenshotAllClassPath",
                (testEngineInput.testRuntimeClassDirs.get() + testEngineInput.testRuntimeJars.get())
                    .joinToString(File.pathSeparator) { it.asFile.absolutePath })
            setTestEngineParam(
                "Renderer.screenshotProjectClassPath",
                (testEngineInput.testProjectClassDirs.get() + testEngineInput.testProjectJars.get())
                    .joinToString(File.pathSeparator) { it.asFile.absolutePath })
            setTestEngineParam(
                "Renderer.layoutlibDataDir",
                testEngineInput.layoutlibDataDir.singleFile.absolutePath
            )
            setTestEngineParam(
                "XmlReportInput.outputDirectory",
                reports.junitXml.outputLocation.get().asFile.absolutePath
            )

            FileUtils.cleanOutputDir(reports.junitXml.outputLocation.get().asFile)

            testEngineInput.threshold.orNull?.let {
                validateFloat(it)
                setTestEngineParam("ImageDiffer.threshold", it.toString())
            }

            try {
                super.executeTests()
            } finally {
                analyticsService.get().recordPreviewScreenshotTestRun(
                    totalTestCount = testCount,
                )

                // Delete html files which Gradle's Test task generates.
                FileUtils.cleanOutputDir(reports.html.outputLocation.get().asFile)
                TestReport(
                    reports.junitXml.outputLocation.get().asFile,
                    reports.html.outputLocation.get().asFile
                ).generateScreenshotTestReport()
            }
        }
    }

    private fun validateFloat(value: Float) {
        if (value < 0 || value > 1) {
            throw GradleException("Invalid threshold provided. Please provide a float value between 0.0 and 1.0")
        }
    }

    private fun setTestEngineParam(key: String, value: String) {
        jvmArgs("-DPreviewScreenshotTestEngineInput.${key}=${value}")
    }
}
