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

package com.android.tools.ui.inspector.inspectors.view.property

import android.app.Activity
import android.content.Context
import android.view.View
import com.android.tools.ui.inspector.inspectors.view.StringTable
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.Attribute
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AttributeProtoConverterTest {

  @Test
  fun testToProtoAttribute() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()

    // 1. Test String type
    val stringMetadata = AttributeMetadata("text", 0, PropertyType.STRING, null, null)
    val stringProto = AttributeProtoConverter.toProtoAttribute(stringMetadata, stringTable, view, "Hello World", emptyMap(), false)
    assertThat(stringProto.type).isEqualTo(Attribute.Type.STRING)
    assertThat(stringTable.getString(stringProto.name)).isEqualTo("text")
    assertThat(stringTable.getString(stringProto.int32Value)).isEqualTo("Hello World")

    // 2. Test Boolean type (Boolean true)
    val boolMetadata = AttributeMetadata("clickable", 1, PropertyType.BOOLEAN, null, null)
    val boolProto = AttributeProtoConverter.toProtoAttribute(boolMetadata, stringTable, view, true, emptyMap(), false)
    assertThat(boolProto.type).isEqualTo(Attribute.Type.BOOLEAN)
    assertThat(boolProto.int32Value).isEqualTo(1)

    // 3. Test Boolean type (Int true)
    val boolIntProto = AttributeProtoConverter.toProtoAttribute(boolMetadata, stringTable, view, 1, emptyMap(), false)
    assertThat(boolIntProto.type).isEqualTo(Attribute.Type.BOOLEAN)
    assertThat(boolIntProto.int32Value).isEqualTo(1)

    // 4. Test Color type
    val colorMetadata = AttributeMetadata("textColor", 2, PropertyType.COLOR, null, null)
    val colorProto = AttributeProtoConverter.toProtoAttribute(colorMetadata, stringTable, view, 0xFFFFFFFF.toInt(), emptyMap(), false)
    assertThat(colorProto.type).isEqualTo(Attribute.Type.COLOR)
    assertThat(colorProto.int32Value).isEqualTo(0xFFFFFFFF.toInt())

    // 5. Test Resource type (Valid System Resource)
    val resMetadata = AttributeMetadata("background", 3, PropertyType.RESOURCE, null, null)
    val resProto =
      AttributeProtoConverter.toProtoAttribute(resMetadata, stringTable, view, android.R.layout.simple_list_item_1, emptyMap(), false)
    assertThat(resProto.type).isEqualTo(Attribute.Type.RESOURCE)
    assertThat(stringTable.getString(resProto.int32Value)).isEqualTo("@android:layout/simple_list_item_1")

    // 6. Test Enum type (Passes post-resolved String)
    val enumMetadata = AttributeMetadata("visibility", 4, PropertyType.INT_ENUM, null, null)
    val enumProto = AttributeProtoConverter.toProtoAttribute(enumMetadata, stringTable, view, "GONE", emptyMap(), false)
    assertThat(enumProto.type).isEqualTo(Attribute.Type.INT_ENUM)
    assertThat(stringTable.getString(enumProto.int32Value)).isEqualTo("GONE")

    // 7. Test Flag type (Passes post-resolved Set of Strings)
    val flagMetadata = AttributeMetadata("gravity", 5, PropertyType.INT_FLAG, null, null)
    val flagProto = AttributeProtoConverter.toProtoAttribute(flagMetadata, stringTable, view, setOf("left", "top"), emptyMap(), false)
    assertThat(flagProto.type).isEqualTo(Attribute.Type.INT_FLAG)
    assertThat(stringTable.getString(flagProto.int32Value)).isEqualTo("left|top")

    // 8. Test Float type
    val floatMetadata = AttributeMetadata("alpha", 6, PropertyType.FLOAT, null, null)
    val floatProto = AttributeProtoConverter.toProtoAttribute(floatMetadata, stringTable, view, 1.0f, emptyMap(), false)
    assertThat(floatProto.type).isEqualTo(Attribute.Type.FLOAT)
    assertThat(floatProto.floatValue).isEqualTo(1.0f)

    // 9. Test Double type
    val doubleMetadata = AttributeMetadata("scale", 7, PropertyType.DOUBLE, null, null)
    val doubleProto = AttributeProtoConverter.toProtoAttribute(doubleMetadata, stringTable, view, 3.0, emptyMap(), false)
    assertThat(doubleProto.type).isEqualTo(Attribute.Type.DOUBLE)
    assertThat(doubleProto.doubleValue).isEqualTo(3.0)

    // 10. Test Char type
    val charMetadata = AttributeMetadata("character", 8, PropertyType.CHAR, null, null)
    val charProto = AttributeProtoConverter.toProtoAttribute(charMetadata, stringTable, view, 'A', emptyMap(), false)
    assertThat(charProto.type).isEqualTo(Attribute.Type.CHAR)
    assertThat(charProto.int32Value).isEqualTo(65)
  }

  private class TestViewWithStack(context: Context) : View(context) {
    override fun getAttributeResolutionStack(attributeId: Int): IntArray {
      return intArrayOf(android.R.layout.simple_list_item_1)
    }
  }

  @Test
  fun testToProtoAttribute_withResolutionStack() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = TestViewWithStack(activity)
    val stringTable = StringTable()
    val metadata = AttributeMetadata("text", 0, PropertyType.STRING, null, null)
    val sourceMap = mapOf(0 to android.R.id.content)

    val proto = AttributeProtoConverter.toProtoAttribute(metadata, stringTable, view, "Hello World", sourceMap, true)

    // Verify direct source
    assertThat(stringTable.getString(proto.directSource)).isEqualTo("@android:id/content")
    // Verify style chain
    assertThat(proto.styleChainCount).isEqualTo(1)
    assertThat(stringTable.getString(proto.getStyleChain(0))).isEqualTo("@android:layout/simple_list_item_1")
  }

  @Test
  fun testToProtoAttribute_layoutWidthHeightDimensions() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()

    // Positive layout_width should be DIMENSION
    val widthMetadata = AttributeMetadata("layout_width", 0, PropertyType.INT_ENUM, null, null)
    val positiveWidthProto = AttributeProtoConverter.toProtoAttribute(widthMetadata, stringTable, view, 100, emptyMap(), false)
    assertThat(positiveWidthProto.type).isEqualTo(Attribute.Type.DIMENSION)
    assertThat(positiveWidthProto.floatValue).isEqualTo(100f)

    // Negative -1 layout_width should be INT_ENUM containing "MATCH_PARENT"
    val matchParentProto = AttributeProtoConverter.toProtoAttribute(widthMetadata, stringTable, view, -1, emptyMap(), false)
    assertThat(matchParentProto.type).isEqualTo(Attribute.Type.INT_ENUM)
    assertThat(stringTable.getString(matchParentProto.int32Value)).isEqualTo("MATCH_PARENT")

    // Negative -2 layout_height should be INT_ENUM containing "WRAP_CONTENT"
    val heightMetadata = AttributeMetadata("layout_height", 1, PropertyType.INT_ENUM, null, null)
    val wrapContentProto = AttributeProtoConverter.toProtoAttribute(heightMetadata, stringTable, view, -2, emptyMap(), false)
    assertThat(wrapContentProto.type).isEqualTo(Attribute.Type.INT_ENUM)
    assertThat(stringTable.getString(wrapContentProto.int32Value)).isEqualTo("WRAP_CONTENT")
  }

  @Test
  fun testToProtoAttribute_robustCasts() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()

    // 1. String metadata with non-string value (Int)
    val stringMetadata = AttributeMetadata("text", 0, PropertyType.STRING, null, null)
    val stringProto = AttributeProtoConverter.toProtoAttribute(stringMetadata, stringTable, view, 12345, emptyMap(), false)
    assertThat(stringProto.type).isEqualTo(Attribute.Type.STRING)
    assertThat(stringTable.getString(stringProto.int32Value)).isEqualTo("12345")

    // 2. Boolean metadata with non-boolean value (String)
    val boolMetadata = AttributeMetadata("clickable", 1, PropertyType.BOOLEAN, null, null)
    val boolProto = AttributeProtoConverter.toProtoAttribute(boolMetadata, stringTable, view, "true", emptyMap(), false)
    assertThat(boolProto.type).isEqualTo(Attribute.Type.BOOLEAN)
    assertThat(boolProto.int32Value).isEqualTo(1)

    // 3. Gravity metadata with non-set value (String)
    val gravityMetadata = AttributeMetadata("gravity", 2, PropertyType.GRAVITY, null, null)
    val gravityProto = AttributeProtoConverter.toProtoAttribute(gravityMetadata, stringTable, view, "center", emptyMap(), false)
    assertThat(gravityProto.type).isEqualTo(Attribute.Type.GRAVITY)
    assertThat(stringTable.getString(gravityProto.int32Value)).isEqualTo("center")
  }
}
