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

package com.android.tools.screenshot.descriptor

import com.android.tools.screenshot.PreviewScreenshotExecutionContext
import com.android.tools.screenshot.renderer.Renderer
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.hierarchical.Node

class PreviewScreenshotTestEngineDescriptor(uniqueId: UniqueId, displayName: String) :
  EngineDescriptor(uniqueId, displayName), Node<PreviewScreenshotExecutionContext> {
  override fun around(context: PreviewScreenshotExecutionContext, invocation: Node.Invocation<PreviewScreenshotExecutionContext>) {
    context.executionListener.reportingEntryPublished(this, ReportEntry.from("deviceId", "Preview"))
    context.executionListener.reportingEntryPublished(this, ReportEntry.from("deviceDisplayName", "Preview"))

    Renderer().use { renderer -> invocation(context.copy(renderer = renderer)) }
  }
}
