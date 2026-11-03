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

import com.android.tools.uicheck.ProjectSourceFile
import com.android.tools.uicheck.snapshot.VisualLintAnalyzerIssueData
import com.android.tools.uicheck.snapshot.VisualLintRectData
import com.android.tools.uicheck.snapshot.VisualLintSourceLocationData
import com.android.tools.visuallint.VisualLintErrorType
import java.awt.image.BufferedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentTreeCheckTest {

  /**
   * Verifies that [CheckContext.sourceLocationOf] and [ComponentTreeCheck.buildIssueData] attach resolved user source locations and
   * properties.
   */
  @Test
  fun wiresSourceLocationsThroughCheckContextAndBuildIssueData() {
    val innerSurface = node("Surface", bounds(10, 20, 410, 100), sourceLoc("Button.kt", 125, "androidx.compose.material3"))
    val userButton =
      node(
        "com.google.android.material.button.MaterialButton",
        bounds(10, 20, 410, 100),
        sourceLoc("HomeScreen.kt", 34, "com.example.home"),
        children = listOf(innerSurface),
      )
    val locations =
      SourceLocations.resolve(
        listOf(userButton),
        UserCodeFilter(listOf(ProjectSourceFile("HomeScreen.kt", "com.example.home"))),
      )
    val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
    val ctx =
      CheckContext(
        roots = listOf(userButton),
        screenWidthPx = 1080,
        screenHeightPx = 2340,
        densityDpi = 420,
        fontScale = 1.3f,
        isRoundDevice = true,
        showSystemUi = true,
        renderedImage = image,
        sourceLocations = locations,
      )

    assertEquals(listOf(userButton), ctx.roots)
    assertEquals(1080, ctx.screenWidthPx)
    assertEquals(2340, ctx.screenHeightPx)
    assertEquals(420, ctx.densityDpi)
    assertEquals(1.3f, ctx.fontScale)
    assertTrue(ctx.isRoundDevice)
    assertTrue(ctx.showSystemUi)
    assertSame(image, ctx.renderedImage)

    val expectedLoc = VisualLintSourceLocationData("HomeScreen.kt", 34, "MaterialButton")
    assertEquals(expectedLoc, ctx.sourceLocationOf(innerSurface))

    val customBounds = VisualLintRectData(12, 22, 400, 90)
    val check =
      object : ComponentTreeCheck {
        override fun check(context: CheckContext): List<VisualLintAnalyzerIssueData> =
          listOf(
            buildIssueData(
              node = innerSurface,
              context = context,
              errorType = VisualLintErrorType.BUTTON_SIZE,
              message = "Too wide",
              description = "Details",
            ),
            buildIssueData(
              node = userButton,
              context = context,
              errorType = VisualLintErrorType.BOUNDS,
              message = "Clipped",
              bounds = customBounds,
            ),
          )
      }

    val issues = check.check(ctx)
    assertEquals(2, issues.size)

    val issue = issues[0]
    assertEquals(VisualLintErrorType.BUTTON_SIZE, issue.errorType)
    assertEquals("Too wide", issue.message)
    assertEquals("Details", issue.description)
    assertEquals(expectedLoc, issue.sourceLocation)
    assertEquals(VisualLintRectData(10, 20, 410, 100), issue.viewBounds)
    assertEquals("Surface", issue.viewClassName)

    val secondIssue = issues[1]
    assertEquals(VisualLintErrorType.BOUNDS, secondIssue.errorType)
    assertNull(secondIssue.description)
    assertEquals(customBounds, secondIssue.viewBounds)
    assertEquals("MaterialButton", secondIssue.viewClassName)
  }

  /** Verifies default property values on [CheckContext]. */
  @Test
  fun checkContextDefaults() {
    val box = node("Box", bounds(0, 0, 100, 100))
    val ctx = CheckContext(roots = listOf(box), screenWidthPx = 400, screenHeightPx = 800, densityDpi = 160)

    assertEquals(1.0f, ctx.fontScale)
    assertEquals(false, ctx.isRoundDevice)
    assertEquals(false, ctx.showSystemUi)
    assertNull(ctx.renderedImage)
    assertNull(ctx.sourceLocationOf(box))
  }

  /** Verifies simple component name extraction for Compose semantics nodes, qualified View classes, and blank names. */
  @Test
  fun extractsSimpleComponentName() {
    assertEquals("Composable", node("android.view.View", bounds(0, 0, 10, 10), semantics = emptyMap()).simpleComponentName())
    assertEquals("View", node("android.view.View", bounds(0, 0, 10, 10)).simpleComponentName())
    assertEquals("MaterialButton", node("com.google.android.material.button.MaterialButton", bounds(0, 0, 10, 10)).simpleComponentName())
    assertEquals("View", node("", bounds(0, 0, 10, 10)).simpleComponentName())
  }
}
