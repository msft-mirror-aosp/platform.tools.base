/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.compose.screenshot.configureInput
import com.android.tools.render.common.PreviewRendering
import com.android.tools.render.common.readPreviewRenderingResultJson
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.nio.file.Paths
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.exists

abstract class PreviewRenderWorkAction: WorkAction<PreviewRenderWorkAction.RenderWorkActionParameters> {
    companion object {
        private const val MAIN_CLASS = "com.android.tools.render.common.MainKt"
        private val logger: Logger = Logger.getLogger(PreviewRenderWorkAction::class.qualifiedName)
    }
    abstract class RenderWorkActionParameters : WorkParameters {
        abstract val classpathJars: ListProperty<String>
        abstract val projectClassPath: ListProperty<String>
        abstract val sdkFontsDir: RegularFileProperty
        abstract val layoutlibDataDir: RegularFileProperty
        abstract val outputDir: RegularFileProperty
        abstract val metaDataDir: RegularFileProperty
        abstract val namespace: Property<String>
        abstract val resourceFile: RegularFileProperty
        abstract val previewsDiscovered: RegularFileProperty
        abstract val resultsFile: RegularFileProperty
    }

    override fun execute() {
        val previewRendering = configureInput(
            parameters.classpathJars.get(),
            parameters.projectClassPath.get(),
            parameters.sdkFontsDir.orNull?.asFile?.absolutePath,
            parameters.layoutlibDataDir.get().asFile.absolutePath + "/",
            parameters.outputDir.get().asFile.absolutePath,
            parameters.metaDataDir.get().asFile.absolutePath,
            parameters.namespace.get(),
            parameters.resourceFile.get().asFile.absolutePath,
            parameters.previewsDiscovered.get().asFile,
            parameters.resultsFile.get().asFile.absolutePath
        )
        render(previewRendering)
        verifyRender(previewRendering)
    }

    private fun render(previewRendering: PreviewRendering) {

        Class.forName(MAIN_CLASS).getMethod("renderPreview", PreviewRendering::class.java)(
            null, previewRendering)
    }

    private fun verifyRender(previewRendering: PreviewRendering) {
        val resultFile = parameters.resultsFile.get().asFile
        if (!resultFile.exists()) {
            throw GradleException(
                "There was an error with the rendering process. " +
                    "Unable to open the rendering result file from ${resultFile.absolutePath}")
        }

        val previewRenderingResult = readPreviewRenderingResultJson(resultFile.reader())
        val outputFolder = previewRendering.outputFolder

        val hasAtLeastOneRenderingWithoutErrors = previewRenderingResult.screenshotResults.any {
            Paths.get(outputFolder, it.imagePath).exists() && it.error == null
        }

        if (previewRenderingResult.globalError != null || !hasAtLeastOneRenderingWithoutErrors) {
            val errorMessage = if (previewRenderingResult.globalError != null) {
                "Rendering failed with error: ${previewRenderingResult.globalError}"
            } else {
                "Rendering failed. For more details, check ${resultFile.absolutePath}"
            }
            throw GradleException(errorMessage)
        }

        val hasRenderingErrors = previewRenderingResult.screenshotResults.any { it.error != null }
        if (hasRenderingErrors) {
            logger.log(
                Level.WARNING,
                "There were some issues with rendering one or more previews. " +
                    "For more details, check ${resultFile.absolutePath}"
            )
        }
    }
}
