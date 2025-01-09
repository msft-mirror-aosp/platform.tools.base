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

package com.android.tools.render.compose

import com.android.tools.render.common.PreviewScreenshot
import com.android.tools.render.common.ScreenshotPreviewElement
import com.android.tools.preview.ConfigurablePreviewElement
import com.android.tools.preview.ComposePreviewElement
import com.android.tools.preview.ParametrizedComposePreviewElementTemplate
import com.android.tools.preview.PreviewParameter
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.preview.previewAnnotationToPreviewElement
import com.android.tools.render.StandaloneRenderModelModule
import com.android.tools.render.common.DeserializedAnnotatedMethod
import com.android.tools.render.common.DeserializedAnnotationAttributesProvider
import com.android.tools.rendering.api.RenderModelModule
import com.android.tools.rendering.classloading.ClassTransform
import com.android.tools.rendering.classloading.ModuleClassLoaderManager

/** Information required to render a screenshot of a compose preview.  */
data class ComposeScreenshot(
    override val methodFQN: String,
    val methodParams: List<Map<String, String>>,
    override val previewParams: Map<String, String>,
    override val previewId: String
) : PreviewScreenshot {

    override fun toPreviewElement(module: StandaloneRenderModelModule): ScreenshotPreviewElement {
        val attrProvider = DeserializedAnnotationAttributesProvider(this.previewParams)
        val annotatedMethod = DeserializedAnnotatedMethod(this.methodFQN, this.methodParams)
        return ComposeScreenshotPreviewElement(previewAnnotationToPreviewElement(
            attrProvider,
            annotatedMethod,
            null,
            { basePreviewElement, parameters ->
                parameterizedElementConstructor(
                    module,
                    basePreviewElement,
                    parameters
                )
            },
            buildPreviewName = { nameParameter ->
                if (nameParameter != null) "${annotatedMethod.name} - $nameParameter"
                else annotatedMethod.name
            }
        ))
    }
}

data class ComposeScreenshotPreviewElement(
    private val composePreviewElement: ComposePreviewElement<Unit>
) :
    ScreenshotPreviewElement,
    ConfigurablePreviewElement<Unit> by composePreviewElement {

    override fun resolveXmlLayouts() =
        composePreviewElement.resolve().map { it.toPreviewXml().buildString() }
}

private fun parameterizedElementConstructor(
    module: StandaloneRenderModelModule,
    basePreviewElement: SingleComposePreviewElementInstance<Unit>,
    parameters: Collection<PreviewParameter>,
): ComposePreviewElement<Unit> {
    return ParametrizedComposePreviewElementTemplate(basePreviewElement, parameters) { element ->
        RenderModelModule.ClassLoaderProvider {
                parent: ClassLoader?,
                additionalProjectTransform: ClassTransform,
                additionalNonProjectTransform: ClassTransform,
                onNewModuleClassLoader: Runnable,
            ->
            module.environment.moduleClassLoaderManager.getPrivate(parent)
                .also { onNewModuleClassLoader.run() }
        }
    }
}
