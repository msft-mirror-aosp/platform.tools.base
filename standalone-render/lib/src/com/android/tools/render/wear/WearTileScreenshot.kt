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

package com.android.tools.render.wear

import com.android.tools.preview.ConfigurablePreviewElement
import com.android.tools.render.common.DefaultAnnotationValuesAnnotationAttributesProvider
import com.android.tools.render.common.DeserializedAnnotatedMethod
import com.android.tools.render.common.DeserializedAnnotationAttributesProvider
import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.ScreenshotPreviewElement
import com.android.tools.render.common.WithFallbackAnnotationAttributesProvider.Companion.withFallback
import com.android.tools.render.StandaloneRenderModelModule
import com.android.tools.rendering.classloading.useWithClassLoader
import com.android.tools.wear.preview.previewAnnotationToWearTilePreviewElement
import com.android.tools.wear.preview.WearTilePreviewElement

/** Information required to render a screenshot of a wear tile preview. */
data class WearTileScreenshot(
    override val methodFQN: String,
    override val previewParams: Map<String, String>,
    override val previewId: String
) : PreviewScreenshot {

    override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement {
        val previewAnnotationClass = module.environment.moduleClassLoaderManager
            .getPrivate(WearTilePreviewElement::class.java.classLoader)
            .useWithClassLoader { classLoader ->
                classLoader.loadClass("androidx.wear.tiles.tooling.preview.Preview")
            }
        val attributesProvider = DeserializedAnnotationAttributesProvider(this.previewParams)
            .withFallback(DefaultAnnotationValuesAnnotationAttributesProvider(previewAnnotationClass))
        val annotatedMethod = DeserializedAnnotatedMethod(this.methodFQN, emptyList())

        val buildPreviewName = { nameParameter: String? ->
            if (nameParameter != null) "${annotatedMethod.name} - $nameParameter"
            else annotatedMethod.name
        }

        return WearTileScreenshotPreviewElement(previewAnnotationToWearTilePreviewElement(
            attributesProvider = attributesProvider,
            annotatedMethod = annotatedMethod,
            previewElementDefinition = null,
            buildPreviewName = buildPreviewName,
        ))
    }
}

private data class WearTileScreenshotPreviewElement(
    private val wearTilePreviewElement: WearTilePreviewElement<Unit>
) :
    ScreenshotPreviewElement,
    ConfigurablePreviewElement<Unit> by wearTilePreviewElement {

    override fun resolveXmlLayouts() =
        sequenceOf(wearTilePreviewElement.toPreviewXml().buildString())
}
