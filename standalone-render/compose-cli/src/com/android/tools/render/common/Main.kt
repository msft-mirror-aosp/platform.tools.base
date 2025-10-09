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

import com.android.tools.render.Renderer
import com.android.tools.render.framework.IJFramework
import com.intellij.openapi.util.Disposer
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Path to the preview rendering settings file is missing.")
        return
    }
    try {
        renderPreview(File(args[0]))
    } finally {
        Disposer.dispose(IJFramework)
    }
}

private fun renderPreview(previewRenderingJson: File) {
    val previewRendering = readPreviewRenderingJson(previewRenderingJson.reader())
    val previewRenderingResult = try {
        Renderer(
            previewRendering.fontsPath,
            previewRendering.resourceApkPath,
            previewRendering.namespace,
            previewRendering.classPath,
            previewRendering.projectClassPath,
            previewRendering.layoutlibPath,
        ).use { renderer ->
            val screenshotResults = previewRendering.screenshots.flatMap {
                renderer.render(it, previewRendering.outputFolder)
            }.sortedBy { it.imagePath }
            PreviewRenderingResult(globalError = null, screenshotResults)
        }
    } catch (t: Throwable) {
        PreviewRenderingResult(t.stackTraceToString(), emptyList())
    }

    writePreviewRenderingResult(
        File(previewRendering.resultsFilePath).writer(),
        previewRenderingResult,
    )
}
