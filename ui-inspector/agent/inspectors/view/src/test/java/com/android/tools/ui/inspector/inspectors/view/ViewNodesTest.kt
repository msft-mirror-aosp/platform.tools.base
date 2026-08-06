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

package com.android.tools.ui.inspector.inspectors.view

import android.app.Activity
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import com.android.tools.ui.inspector.inspectors.view.property.PropertyCache
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ViewNodesTest {

  @Test
  fun testScaleProducesAxisAlignedBounds() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root = FrameLayout(activity)
    val child = View(activity)
    root.addView(child)
    root.layout(0, 0, 400, 400)
    child.layout(50, 60, 150, 160)
    child.scaleX = 2f
    child.scaleY = 2f

    val node = toViewNode(root).childrenList.single()

    assertBounds(node.bounds, 0, 10, 200, 200)
  }

  @Test
  fun testFractionalTranslationRoundsOutward() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root = FrameLayout(activity)
    val child = View(activity)
    root.addView(child)
    root.layout(0, 0, 200, 200)
    child.layout(10, 20, 110, 120)
    child.translationX = 0.25f
    child.translationY = -0.75f

    val node = toViewNode(root).childrenList.single()

    assertBounds(node.bounds, 10, 19, 101, 101)
  }

  @Test
  fun testRotationProducesAxisAlignedBounds() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root = FrameLayout(activity)
    val rightAngle = View(activity)
    val diagonal = View(activity)
    root.addView(rightAngle)
    root.addView(diagonal)
    root.layout(0, 0, 300, 300)
    rightAngle.layout(100, 100, 180, 140)
    rightAngle.rotation = 90f
    diagonal.layout(100, 100, 180, 140)
    diagonal.rotation = 45f

    val nodes = toViewNode(root).childrenList

    assertBounds(nodes[0].bounds, 120, 80, 40, 80)
    assertBounds(nodes[1].bounds, 97, 77, 86, 86)
  }

  @Test
  fun testNestedTransformsAccumulate() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root = FrameLayout(activity)
    val parent = FrameLayout(activity)
    val child = View(activity)
    root.addView(parent)
    parent.addView(child)
    root.layout(0, 0, 500, 500)
    parent.layout(100, 100, 300, 300)
    parent.pivotX = 0f
    parent.pivotY = 0f
    parent.scaleX = 2f
    parent.scaleY = 2f
    parent.translationX = 10f
    parent.translationY = 20f
    child.layout(10, 20, 40, 60)
    child.pivotX = 0f
    child.pivotY = 0f
    child.scaleX = 0.5f
    child.scaleY = 0.5f
    child.translationX = 5f
    child.translationY = 7f

    val node = toViewNode(root).childrenList.single().childrenList.single()

    assertBounds(node.bounds, 140, 174, 30, 40)
  }

  @Test
  fun testScrollIsAppliedBeforeParentTransform() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root = FrameLayout(activity)
    val parent = FrameLayout(activity)
    val child = View(activity)
    root.addView(parent)
    parent.addView(child)
    root.layout(0, 0, 500, 500)
    parent.layout(100, 100, 300, 300)
    parent.pivotX = 0f
    parent.pivotY = 0f
    parent.scaleX = 2f
    parent.scaleY = 2f
    parent.scrollTo(10, 20)
    child.layout(30, 40, 50, 70)
    child.translationX = 5f
    child.translationY = 7f

    val node = toViewNode(root).childrenList.single().childrenList.single()

    assertBounds(node.bounds, 150, 154, 40, 60)
  }

  @Test
  fun testAttachedRootUsesWindowScreenOffset() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val container = FrameLayout(activity)
    val root = FrameLayout(activity)
    val child = View(activity)
    container.addView(root)
    root.addView(child)
    activity.setContentView(container)
    container.layout(0, 0, 500, 500)
    root.layout(37, 53, 137, 153)
    child.layout(11, 13, 31, 43)
    val location = IntArray(2)
    root.getLocationOnScreen(location)
    assertThat(location[0] != 0 || location[1] != 0).isTrue()

    val node = toViewNode(root)

    assertBounds(node.bounds, location[0], location[1], 100, 100)
    assertBounds(node.childrenList.single().bounds, location[0] + 11, location[1] + 13, 20, 30)
  }

  @Test
  fun testIdentityHierarchyIsByteStable() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root = FrameLayout(activity)
    val child = View(activity)
    val grandchild = View(activity)
    val childGroup = FrameLayout(activity)
    root.addView(child)
    root.addView(childGroup)
    childGroup.addView(grandchild)
    root.layout(0, 0, 300, 400)
    child.layout(10, 20, 40, 60)
    childGroup.layout(50, 70, 150, 190)
    grandchild.layout(7, 9, 27, 39)
    val stringTable = StringTable()

    val actual = toViewNode(root, stringTable)
    val expected =
      ViewInspectorProtocol.ViewNode.newBuilder()
        .setId(root.uniqueDrawingId)
        .setClassName(stringTable.put("FrameLayout"))
        .setPackageName(stringTable.put("android.widget"))
        .setBounds(rect(0, 0, 300, 400))
        .addChildren(
          ViewInspectorProtocol.ViewNode.newBuilder()
            .setId(child.uniqueDrawingId)
            .setClassName(stringTable.put("View"))
            .setPackageName(stringTable.put("android.view"))
            .setBounds(rect(10, 20, 30, 40))
        )
        .addChildren(
          ViewInspectorProtocol.ViewNode.newBuilder()
            .setId(childGroup.uniqueDrawingId)
            .setClassName(stringTable.put("FrameLayout"))
            .setPackageName(stringTable.put("android.widget"))
            .setBounds(rect(50, 70, 100, 120))
            .addChildren(
              ViewInspectorProtocol.ViewNode.newBuilder()
                .setId(grandchild.uniqueDrawingId)
                .setClassName(stringTable.put("View"))
                .setPackageName(stringTable.put("android.view"))
                .setBounds(rect(57, 79, 20, 30))
            )
        )
        .build()

    assertThat(actual.toByteArray()).isEqualTo(expected.toByteArray())
  }

  @Test
  fun testZeroSizeViewRemainsZeroSized() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val root = FrameLayout(activity)
    val child = View(activity)
    root.addView(child)
    root.layout(0, 0, 100, 100)
    child.layout(25, 30, 25, 30)

    val node = toViewNode(root).childrenList.single()

    assertBounds(node.bounds, 25, 30, 0, 0)
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun testToWindowInfo() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    activity.setTheme(android.R.style.Theme_Material)
    val view = View(activity)
    val stringTable = StringTable()
    val window =
      ViewNodes.toWindowInfo(
        view,
        stringTable,
        false,
        false,
        PropertyCache.createViewPropertyCache(),
        PropertyCache.createLayoutParamsPropertyCache(),
      )

    val stringMap = stringTable.toStringEntries().associate { it.id to it.value }
    assertThat(window.hasRoot()).isTrue()
    assertThat(window.root.id).isEqualTo(view.uniqueDrawingId)
    assertThat(window.hasConfiguration()).isTrue()
    assertThat(window.configuration.density).isEqualTo(activity.resources.configuration.densityDpi)
    assertThat(stringMap[window.theme]).isEqualTo("@android:style/Theme.Material")
  }

  @Test
  @Config(qualifiers = "w400dp-h800dp-port")
  fun testBuildDisplayInfo() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val displays = ViewNodes.buildDisplayInfo(activity)

    assertThat(displays).isNotEmpty()
    val display = displays.first()
    val displayManager = activity.getSystemService(DisplayManager::class.java)
    val expectedDisplayId = displayManager?.displays?.firstOrNull()?.displayId
    assertThat(display.id).isEqualTo(expectedDisplayId)
    assertThat(display.widthPx).isGreaterThan(0)
    assertThat(display.heightPx).isGreaterThan(0)
    assertThat(display.hasOrientation()).isTrue()
    assertThat(display.orientation).isEqualTo(0)
  }

  @Test
  fun testToWindowInfo_usesEachRootContext() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val density160Context =
      ContextThemeWrapper(
        activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { densityDpi = 160 }),
        android.R.style.Theme_Material,
      )
    val density420Context =
      ContextThemeWrapper(
        activity.createConfigurationContext(Configuration(activity.resources.configuration).apply { densityDpi = 420 }),
        android.R.style.Theme_Holo,
      )
    val stringTable = StringTable()

    val density160Window =
      ViewNodes.toWindowInfo(
        View(density160Context),
        stringTable,
        false,
        false,
        PropertyCache.createViewPropertyCache(),
        PropertyCache.createLayoutParamsPropertyCache(),
      )
    val density420Window =
      ViewNodes.toWindowInfo(
        View(density420Context),
        stringTable,
        false,
        false,
        PropertyCache.createViewPropertyCache(),
        PropertyCache.createLayoutParamsPropertyCache(),
      )

    val strings = stringTable.toStringEntries().associate { it.id to it.value }
    assertThat(density160Window.configuration.density).isEqualTo(160)
    assertThat(density420Window.configuration.density).isEqualTo(420)
    assertThat(strings[density160Window.theme]).isEqualTo("@android:style/Theme.Material")
    assertThat(strings[density420Window.theme]).isEqualTo("@android:style/Theme.Holo")
  }

  private fun toViewNode(view: View, stringTable: StringTable = StringTable()): ViewInspectorProtocol.ViewNode =
    ViewNodes.toViewNode(
      view,
      stringTable,
      false,
      false,
      PropertyCache.createViewPropertyCache(),
      PropertyCache.createLayoutParamsPropertyCache(),
    )

  private fun rect(x: Int, y: Int, width: Int, height: Int): ViewInspectorProtocol.Rect =
    ViewInspectorProtocol.Rect.newBuilder().setX(x).setY(y).setWidth(width).setHeight(height).build()

  private fun assertBounds(bounds: ViewInspectorProtocol.Rect, x: Int, y: Int, width: Int, height: Int) {
    assertThat(bounds).isEqualTo(rect(x, y, width, height))
  }
}
