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

package com.android.compose.screenshot.tasks

import java.io.File
import kotlin.collections.filterNot
import kotlin.collections.joinToString
import kotlin.collections.map
import kotlin.collections.plus
import org.gradle.api.GradleException
import org.gradle.api.NonExtensible
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/** Provides all the input properties needed to run the Screenshot test engine. */
@NonExtensible
interface PreviewScreenshotTestEngineInput {
  @get:InputFiles @get:Classpath val mainProjectClassDirs: ListProperty<Directory>

  @get:InputFiles @get:Classpath val mainProjectJars: ListProperty<RegularFile>

  @get:InputFiles @get:Classpath val mainRuntimeClassDirs: ListProperty<Directory>

  @get:InputFiles @get:Classpath val mainRuntimeJars: ListProperty<RegularFile>

  @get:InputFiles @get:Classpath val testProjectClassDirs: ListProperty<Directory>

  @get:InputFiles @get:Classpath val testProjectJars: ListProperty<RegularFile>

  @get:InputFiles @get:Classpath val testRuntimeClassDirs: ListProperty<Directory>

  @get:InputFiles @get:Classpath val testRuntimeJars: ListProperty<RegularFile>

  @get:InputFiles // Using InputFiles to allow nonexistent reference image directory.
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:Optional
  val referenceImageDir: DirectoryProperty

  @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) @get:Optional val sdkFontsDir: DirectoryProperty

  @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NAME_ONLY) val resourceApkFile: RegularFileProperty

  @get:Input val namespace: Property<String>

  @get:InputFiles @get:Classpath val layoutlibDataDir: ConfigurableFileCollection

  @get:InputFiles @get:Classpath val layoutlibClassPath: ConfigurableFileCollection

  @get:Input val threshold: Property<Float>

  @get:OutputDirectory val previewImageOutputDir: DirectoryProperty

  @get:OutputDirectory val diffImageOutputDir: DirectoryProperty

  @get:Optional @get:OutputDirectory val junitXmlOutputDirectory: DirectoryProperty

  @get:Input val recordingModeEnabled: Property<Boolean>
}

fun PreviewScreenshotTestEngineInput.configureCommonJvmArgs(addJvmArgFunc: (String) -> Unit) {
  // Force the locale to en-US to ensure consistent resource parsing.
  addJvmArgFunc("-Duser.language=en")
  addJvmArgFunc("-Duser.country=US")
  addJvmArgFunc("-Dlayoutlib.thread.profile.slow-rendering.enable=false")
}

fun PreviewScreenshotTestEngineInput.saveToPropertiesFile(outputFile: File) {
  val properties = java.util.Properties()
  fun addProp(key: String, value: String) {
    properties.setProperty("PreviewScreenshotTestEngineInput.$key", value)
  }

  addProp("screenshotTestDirectory", testProjectClassDirs.get().joinToString(File.pathSeparator) { it.asFile.absolutePath })
  addProp("screenshotTestJars", testProjectJars.get().joinToString(File.pathSeparator) { it.asFile.absolutePath })
  addProp("mainDirectory", mainProjectClassDirs.get().joinToString(File.pathSeparator) { it.asFile.absolutePath })
  addProp("mainJars", mainProjectJars.get().joinToString(File.pathSeparator) { it.asFile.absolutePath })

  val testProjectJarSet = setOf(*testProjectJars.get().map { it.asFile.absolutePath }.toTypedArray())
  addProp(
    "dependencyJars",
    testRuntimeJars
      .get()
      .filterNot { it.asFile.absolutePath in testProjectJarSet }
      .joinToString(File.pathSeparator) { it.asFile.absolutePath },
  )

  addProp("previewImageOutputDir", previewImageOutputDir.get().asFile.absolutePath)
  addProp("previewDiffImageOutputDir", diffImageOutputDir.get().asFile.absolutePath)
  addProp("referenceImageDir", referenceImageDir.get().asFile.absolutePath)
  addProp("Renderer.fontsPath", sdkFontsDir.orNull?.asFile?.absolutePath ?: "")
  addProp("Renderer.resourceApkPath", resourceApkFile.orNull?.asFile?.absolutePath ?: "")
  addProp("Renderer.namespace", namespace.get())
  addProp(
    "Renderer.mainAllClassPath",
    (mainRuntimeClassDirs.get() + mainRuntimeJars.get()).joinToString(File.pathSeparator) { it.asFile.absolutePath },
  )
  addProp(
    "Renderer.mainProjectClassPath",
    (mainProjectClassDirs.get() + mainProjectJars.get()).joinToString(File.pathSeparator) { it.asFile.absolutePath },
  )
  addProp(
    "Renderer.screenshotAllClassPath",
    (testRuntimeClassDirs.get() + testRuntimeJars.get()).joinToString(File.pathSeparator) { it.asFile.absolutePath },
  )
  addProp(
    "Renderer.screenshotProjectClassPath",
    (testProjectClassDirs.get() + testProjectJars.get()).joinToString(File.pathSeparator) { it.asFile.absolutePath },
  )
  addProp("Renderer.layoutlibDataDir", layoutlibDataDir.singleFile.absolutePath)
  addProp("Renderer.layoutlibClassPath", layoutlibClassPath.files.joinToString(File.pathSeparator) { it.absolutePath })
  addProp("TestOption.recordingModeEnabled", recordingModeEnabled.get().toString())

  if (junitXmlOutputDirectory.isPresent) {
    addProp("XmlReportInput.isEnabled", "true")
    addProp("XmlReportInput.outputDirectory", junitXmlOutputDirectory.get().asFile.absolutePath)
  } else {
    addProp("XmlReportInput.isEnabled", "false")
  }

  threshold.orNull?.let {
    validateFloat(it)
    addProp("ImageDiffer.threshold", it.toString())
  }

  outputFile.writer().use { properties.store(it, "Generated by PreviewScreenshotGradlePlugin") }
}

private fun validateFloat(value: Float) {
  if (value < 0 || value > 1) {
    throw GradleException("Invalid threshold provided. Please provide a float value between 0.0 and 1.0")
  }
}
