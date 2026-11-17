/*
 * Copyright (C) 2026 The Android Open Source Project
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

import com.android.tools.preview.PreviewConfiguration
import com.android.tools.preview.SingleComposePreviewElementInstance
import com.android.tools.preview.config.DimUnit
import com.android.tools.preview.config.ExpandAxis
import com.android.tools.preview.config.ExpandSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComposeScreenshotPreviewElementTest {
  private fun previewElement(device: String? = null) =
    SingleComposePreviewElementInstance.forTesting<Unit>(
      "com.example.MyClassKt.MyPreview",
      configuration = PreviewConfiguration.cleanAndGet(device = device),
    )

  @Test
  fun testLayoutsAreNotExpandedByDefault() {
    val previewElement = previewElement()
    val element = ComposeScreenshotPreviewElement(previewElement)

    val resolvedLayout = element.resolveLayouts().single()

    assertThat(resolvedLayout.xmlLayout).isEqualTo(previewElement.toPreviewXml().buildString())
    assertThat(element.resolveXmlLayouts().toList()).containsExactly(resolvedLayout.xmlLayout)
    assertThat(resolvedLayout.expandSettings).isNull()
  }

  @Test
  fun testLayoutsAreNotExpandedWithoutExpandInDeviceSpec() {
    val element = ComposeScreenshotPreviewElement(previewElement("spec:width=411dp,height=891dp,maxHeight=2000dp"))

    assertThat(element.resolveLayouts().single().expandSettings).isNull()
  }

  @Test
  fun testExpandIsParsedFromDeviceSpec() {
    val device = "spec:width=411dp,height=891dp,expand=vertical,maxHeight=2000dp"
    val element = ComposeScreenshotPreviewElement(previewElement(device))

    val resolvedLayout = element.resolveLayouts().single()

    assertThat(resolvedLayout.expandSettings).isEqualTo(ExpandSettings(ExpandAxis.vertical, 2000f, DimUnit.dp))
    assertThat(resolvedLayout.deviceSpec).isEqualTo(device)
    // The device spec is forwarded to ComposeViewAdapter, which reads the max size from it
    assertThat(resolvedLayout.xmlLayout).contains("tools:device=\"$device\"")
  }

  @Test
  fun testHorizontalExpandIsParsedFromDeviceSpec() {
    val element = ComposeScreenshotPreviewElement(previewElement("spec:parent=pixel_7,expand=horizontal"))

    assertThat(element.resolveLayouts().single().expandSettings).isEqualTo(ExpandSettings(ExpandAxis.horizontal))
  }

  @Test
  fun testScrollToIsForwardedWithoutExpanding() {
    val device = "spec:parent=pixel_7,scrollTo=end"
    val element = ComposeScreenshotPreviewElement(previewElement(device))

    val resolvedLayout = element.resolveLayouts().single()

    assertThat(resolvedLayout.expandSettings).isNull()
    assertThat(resolvedLayout.deviceSpec).isEqualTo(device)
    assertThat(resolvedLayout.xmlLayout).contains("tools:device=\"$device\"")
  }
}
