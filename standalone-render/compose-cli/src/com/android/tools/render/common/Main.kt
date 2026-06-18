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

package com.android.tools.render.common

import com.android.tools.render.RenderEnvironmentBootstrapper
import com.android.tools.render.framework.IJFramework
import com.intellij.openapi.util.Disposer
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
  if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
    printUsage()
    return
  }
  var exitCode = 0
  try {
    renderPreview(File(args[0]))
  } catch (t: Throwable) {
    System.err.println("Error: ${t.message}")
    exitCode = 1
  } finally {
    Disposer.dispose(IJFramework)
  }
  exitProcess(exitCode)
}

private fun printUsage() {
  println("Usage: compose-preview-renderer <path-to-rendering-settings-json>")
  println()
  println("Renders Compose previews as screenshots based on the provided JSON settings file.")
  println()
  println("Options:")
  println("  -h, --help  Show this help message")
  println()
  println("JSON Settings File Format:")
  println("  The JSON file must contain the following fields:")
  println("    layoutlibPath:    Path to layoutlib installation directory")
  println("    outputFolder:     Directory where screenshots will be saved")
  println("    metaDataFolder:   Directory where metadata is stored")
  println("    classPath:        List of classpath entries (project & dependencies)")
  println("    projectClassPath: List of project-only classpath entries")
  println("    namespace:        Application package name")
  println("    resourceApkPath:  Path to resource APK")
  println("    resultsFilePath:  Path to write the results JSON file")
  println("    screenshots:      List of screenshot configurations to render")
  println()
  println("  Each screenshot in the 'screenshots' list needs:")
  println("    methodFQN:        Fully qualified name of Composable function")
  println("    previewId:        Unique ID for the preview (used in filename)")
  println("    previewParams:    (Optional) Map of preview parameters")
  println()
  println("Example JSON:")
  println(
    """  {
    "layoutlibPath": "/path/to/layoutlib",
    "outputFolder": "/path/to/output",
    "metaDataFolder": "/path/to/metadata",
    "classPath": ["/path/to/classes", "/path/to/dependencies.jar"],
    "projectClassPath": ["/path/to/classes"],
    "namespace": "com.example.myapp",
    "resourceApkPath": "/path/to/app-debug.apk",
    "resultsFilePath": "/path/to/results.json",
    "screenshots": [
      {
        "methodFQN": "com.example.myapp.MainActivityKt.DefaultPreview",
        "previewId": "com.example.myapp.MainActivityKt.DefaultPreview_screenshot",
        "previewParams": { "showBackground": "true" }
      }
    ]
  }"""
  )
}

private fun renderPreview(previewRenderingJson: File) {
  val previewRendering = readPreviewRenderingJson(previewRenderingJson.reader())
  val previewRenderingResult =
    try {
      val bootstrapper =
        RenderEnvironmentBootstrapper(
          previewRendering.fontsPath,
          previewRendering.resourceApkPath,
          previewRendering.namespace,
          previewRendering.classPath,
          previewRendering.projectClassPath,
          previewRendering.layoutlibPath,
        )
      bootstrapper.bootstrap().use { renderer ->
        val screenshotResults =
          previewRendering.screenshots.flatMap { renderer.render(it, previewRendering.outputFolder) }.sortedBy { it.imagePath }
        PreviewRenderingResult(globalError = null, screenshotResults)
      }
    } catch (t: Throwable) {
      PreviewRenderingResult(t.stackTraceToString(), emptyList())
    }

  writePreviewRenderingResult(File(previewRendering.resultsFilePath).writer(), previewRenderingResult)

  var hasErrors = false
  if (previewRenderingResult.globalError != null) {
    System.err.println("Global error: ${previewRenderingResult.globalError}")
    hasErrors = true
  }

  val outputFolder = File(previewRendering.outputFolder)
  previewRenderingResult.screenshotResults.forEach { result ->
    val error = result.error
    if (error != null) {
      System.err.println("Error rendering ${result.previewId}: ${error.message}")
      if (error.stackTrace.isNotEmpty()) {
        System.err.println(error.stackTrace)
      }
      error.problems.forEach { problem ->
        System.err.println("  Problem: ${problem.html}")
        if (problem.stackTrace != null) {
          System.err.println(problem.stackTrace)
        }
      }
      hasErrors = true
    } else {
      val absolutePath = File(outputFolder, result.imagePath).absolutePath
      println(absolutePath)
    }
  }

  if (hasErrors) {
    throw RuntimeException("Rendering failed with errors")
  }
}
