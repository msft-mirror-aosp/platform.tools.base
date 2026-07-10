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

package com.android.tools.screenshot

import java.io.File

object PreviewScreenshotTestEngineInput {
  val screenshotTestDirectory: List<File> = getFilesFromSystemProperty("screenshotTestDirectory")
  val screenshotTestJars: List<File> = getFilesFromSystemProperty("screenshotTestJars")
  val mainDirectory: List<File> = getFilesFromSystemProperty("mainDirectory")
  val mainJars: List<File> = getFilesFromSystemProperty("mainJars")
  val dependencyJars: List<File> = getFilesFromSystemProperty("dependencyJars")

  val resultsDir: File? =
    (System.getProperty("com.android.junit.engine.results.dir") ?: properties.getProperty("com.android.junit.engine.results.dir"))
      ?.takeIf { it.isNotEmpty() }
      ?.let { File(it) }
  val previewImageOutputDir: File = resultsDir?.resolve("rendered") ?: getFileFromSystemProperty("previewImageOutputDir")
  val previewDiffImageOutputDir: File = resultsDir?.resolve("diffs") ?: getFileFromSystemProperty("previewDiffImageOutputDir")
  val referenceImageDir: File = getFileFromSystemProperty("referenceImageDir")
  val projectRoot: File = getFileFromSystemProperty("projectRoot")

  object TestOption {
    val recordingModeEnabled: Boolean = getSystemProperty("TestOption.recordingModeEnabled", "false").toBoolean()
  }

  object RendererInput {
    val fontsPath: File = getFileFromSystemProperty("Renderer.fontsPath")
    val resourceApkPath: File = getFileFromSystemProperty("Renderer.resourceApkPath")
    val namespace: String = getSystemProperty("Renderer.namespace")
    val mainAllClassPath: List<File> = getFilesFromSystemProperty("Renderer.mainAllClassPath")
    val mainProjectClassPath: List<File> = getFilesFromSystemProperty("Renderer.mainProjectClassPath")
    val screenshotAllClassPath: List<File> = getFilesFromSystemProperty("Renderer.screenshotAllClassPath")
    val screenshotProjectClassPath: List<File> = getFilesFromSystemProperty("Renderer.screenshotProjectClassPath")
    val layoutlibDataDir: File = getFileFromSystemProperty("Renderer.layoutlibDataDir")
    val layoutlibClassPath: List<File> = getFilesFromSystemProperty("Renderer.layoutlibClassPath")
    val testRuntimeResourceDirs: List<File> = getFilesFromSystemProperty("Renderer.testRuntimeResourceDirs")
    val testRuntimeRClassJars: List<File> = getFilesFromSystemProperty("Renderer.testRuntimeRClassJars")
  }

  object ImageDifferInput {
    val threshold: Float = getSystemProperty("ImageDiffer.threshold").toFloatOrNull() ?: 0.0f
  }

  object XmlReportInput {
    val isEnabled: Boolean = getSystemProperty("XmlReportInput.isEnabled").toBoolean()
    val outputDirectory: File = getFileFromSystemProperty("XmlReportInput.outputDirectory")
  }

  object ReportEntrySetting {
    // Redirect ReportEntry to stdout when enabled.
    // This is a short-term workaround until Gradle supports ReportEntry.
    // https://github.com/gradle/gradle/issues/4605
    val redirectToStdout: Boolean = getSystemProperty("ReportEntrySetting.redirectToStdout").toBoolean()
  }
}

private val properties: java.util.Properties =
  java.util.Properties().apply {
    val configFile =
      System.getProperty("PreviewScreenshotTestEngineInput.configFile") ?: System.getenv("com.android.junit.engine.input.parameters")
    if (!configFile.isNullOrEmpty()) {
      val file = File(configFile)
      if (file.exists()) {
        file.inputStream().use { load(it) }
      }
    }
  }

private fun getSystemProperty(propertyName: String, defaultValue: String = ""): String {
  val standalonePluginProperty = "PreviewScreenshotTestEngineInput.$propertyName"
  val agpSuiteProperty = getAgpSuitePropertyMap(propertyName) ?: standalonePluginProperty

  return System.getProperty(standalonePluginProperty)
    ?: properties.getProperty(standalonePluginProperty)
    ?: System.getProperty(agpSuiteProperty)
    ?: properties.getProperty(agpSuiteProperty)
    ?: defaultValue
}

/**
 * Maps legacy PreviewScreenshotTestEngineInput system property names to the newer AgpTestSuiteInputParameters expected by AGP's native
 * screenshot test suite.
 */
private fun getAgpSuitePropertyMap(propertyName: String): String? {
  return when (propertyName) {
    "screenshotTestDirectory" -> "com.android.agp.test.TEST_CLASSES"
    "screenshotTestJars" -> "com.android.agp.test.TEST_CLASSES"
    "mainDirectory" -> "com.android.agp.test.MAIN_CLASSES"
    "mainJars" -> "com.android.agp.test.MAIN_CLASSES"
    "dependencyJars" -> "com.android.agp.test.TEST_CLASSPATH"
    "Renderer.fontsPath" -> "com.android.agp.test.SDK_FONTS_DIR"
    "Renderer.resourceApkPath" -> "com.android.agp.test.RESOURCES_AP_ARCHIVE"
    "Renderer.namespace" -> "com.android.junit.engine.tested.application.id"
    "Renderer.mainAllClassPath" -> "com.android.agp.test.MAIN_CLASSPATH"
    "Renderer.mainProjectClassPath" -> "com.android.agp.test.MAIN_CLASSES"
    "Renderer.screenshotAllClassPath" -> "com.android.agp.test.TEST_CLASSPATH"
    "Renderer.screenshotProjectClassPath" -> "com.android.agp.test.TEST_CLASSES"
    "Renderer.layoutlibDataDir" -> "com.android.agp.test.LAYOUTLIB_DATA_DIR"
    "Renderer.layoutlibClassPath" -> "com.android.agp.test.LAYOUTLIB_CLASSPATH"
    "Renderer.testRuntimeResourceDirs" -> "com.android.agp.test.ANDROID_RES_DIRS"
    "Renderer.testRuntimeRClassJars" -> "com.android.agp.test.R_CLASS_JARS"
    else -> null
  }
}

private fun getFileFromSystemProperty(propertyName: String): File {
  return File(getSystemProperty(propertyName))
}

private fun getFilesFromSystemProperty(propertyName: String): List<File> {
  return getSystemProperty(propertyName).splitToSequence(File.pathSeparator).filter(String::isNotBlank).map { File(it) }.toList()
}
