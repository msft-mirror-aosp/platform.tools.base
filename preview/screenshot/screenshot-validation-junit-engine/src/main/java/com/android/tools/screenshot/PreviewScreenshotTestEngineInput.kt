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

  val previewImageOutputDir: File = getFileFromSystemProperty("previewImageOutputDir")
  val previewDiffImageOutputDir: File = getFileFromSystemProperty("previewDiffImageOutputDir")
  val referenceImageDir: File = getFileFromSystemProperty("referenceImageDir")

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

private fun getSystemProperty(propertyName: String, defaultValue: String = ""): String {
  return System.getProperty("PreviewScreenshotTestEngineInput.$propertyName", defaultValue)
}

private fun getFileFromSystemProperty(propertyName: String): File {
  return File(getSystemProperty(propertyName))
}

private fun getFilesFromSystemProperty(propertyName: String): List<File> {
  return getSystemProperty(propertyName).splitToSequence(File.pathSeparator).filter(String::isNotBlank).map { File(it) }.toList()
}
