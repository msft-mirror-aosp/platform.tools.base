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

package com.android.tools.uicheck.checks

import com.android.tools.uicheck.ComponentBounds
import com.android.tools.uicheck.ComponentSourceLocation
import com.android.tools.uicheck.ComponentTreeNode
import com.android.tools.uicheck.PreviewComponentTree
import java.awt.image.BufferedImage

/** Builds a [ComponentBounds] rectangle from pixel coordinates. */
internal fun bounds(left: Int, top: Int, right: Int, bottom: Int): ComponentBounds = ComponentBounds(left, top, right, bottom)

/** Builds a [ComponentSourceLocation] for [fileName] at [lineNumber], hashing [packageName] when provided. */
internal fun sourceLoc(
  fileName: String,
  lineNumber: Int,
  packageName: String? = null,
): ComponentSourceLocation = ComponentSourceLocation(fileName, lineNumber, packageName?.let(UserCodeFilter::packageNameHash))

/** Builds a [ComponentTreeNode] for tests. */
internal fun node(
  name: String,
  bounds: ComponentBounds,
  sourceLocation: ComponentSourceLocation? = null,
  children: List<ComponentTreeNode>? = null,
  semantics: Map<String, String>? = null,
  modifiers: Map<String, String>? = null,
): ComponentTreeNode =
  ComponentTreeNode(
    name = name,
    bounds = bounds,
    sourceLocation = sourceLocation,
    children = children,
    semantics = semantics,
    modifiers = modifiers,
  )

/** Builds a [PreviewComponentTree] wrapping [components] at the given pixel dimensions and density. */
internal fun tree(
  methodFqn: String,
  width: Int,
  height: Int,
  densityDpi: Int,
  deviceId: String? = null,
  components: List<ComponentTreeNode>,
): PreviewComponentTree =
  PreviewComponentTree(
    previewId = "${methodFqn}_0",
    methodFqn = methodFqn,
    width = width,
    height = height,
    densityDpi = densityDpi,
    fontScale = 1.0f,
    deviceId = deviceId,
    components = components,
  )

/** Builds a [CheckContext] for [roots] on a 1080x2340 phone at 420dpi, unless overridden. */
internal fun context(
  vararg roots: ComponentTreeNode,
  screenWidthPx: Int = 1080,
  screenHeightPx: Int = 2340,
  densityDpi: Int = 420,
  fontScale: Float = 1.0f,
  isRoundDevice: Boolean = false,
  showSystemUi: Boolean = false,
  renderedImage: BufferedImage? = null,
  sourceLocations: SourceLocations = SourceLocations.EMPTY,
): CheckContext =
  CheckContext(
    roots = roots.toList(),
    screenWidthPx = screenWidthPx,
    screenHeightPx = screenHeightPx,
    densityDpi = densityDpi,
    fontScale = fontScale,
    isRoundDevice = isRoundDevice,
    showSystemUi = showSystemUi,
    renderedImage = renderedImage,
    sourceLocations = sourceLocations,
  )
