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
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AttributeMetadataProtoConversionTest {

  @Test
  fun testToProtoAttribute() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)
    val stringTable = StringTable()

    // 1. Test String type
    val stringMetadata = AttributeMetadata("text", 0, PropertyType.STRING)
    val stringProto = stringMetadata.toProtoAttribute(stringTable, view, "Hello World", emptyMap(), false)
    assertThat(stringProto).isNotNull()
    assertThat(stringTable.getString(stringProto!!.name)).isEqualTo("text")
    assertThat(stringTable.getString(stringProto.value)).isEqualTo("Hello World")

    // 2. Test Boolean type (Boolean true)
    val boolMetadata = AttributeMetadata("clickable", 1, PropertyType.BOOLEAN)
    val boolProto = boolMetadata.toProtoAttribute(stringTable, view, true, emptyMap(), false)
    assertThat(boolProto).isNotNull()
    assertThat(stringTable.getString(boolProto!!.value)).isEqualTo("true")

    // 3. Test Boolean type (Int true)
    val boolIntProto = boolMetadata.toProtoAttribute(stringTable, view, 1, emptyMap(), false)
    assertThat(boolIntProto).isNotNull()
    assertThat(stringTable.getString(boolIntProto!!.value)).isEqualTo("true")

    // 4. Test Color type
    val colorMetadata = AttributeMetadata("textColor", 2, PropertyType.COLOR)
    val colorProto = colorMetadata.toProtoAttribute(stringTable, view, 0xFFFFFFFF.toInt(), emptyMap(), false)
    assertThat(colorProto).isNotNull()
    assertThat(stringTable.getString(colorProto!!.value)).isEqualTo("#FFFFFFFF")

    // 5. Test Resource type (Valid System Resource)
    val resMetadata = AttributeMetadata("background", 3, PropertyType.RESOURCE)
    val resProto = resMetadata.toProtoAttribute(stringTable, view, android.R.layout.simple_list_item_1, emptyMap(), false)
    assertThat(resProto).isNotNull()
    assertThat(stringTable.getString(resProto!!.value)).isEqualTo("@android:layout/simple_list_item_1")

    // 6. Test Enum type (Passes post-resolved String)
    val enumMetadata = AttributeMetadata("visibility", 4, PropertyType.INT_ENUM)
    val enumProto = enumMetadata.toProtoAttribute(stringTable, view, "GONE", emptyMap(), false)
    assertThat(enumProto).isNotNull()
    assertThat(stringTable.getString(enumProto!!.value)).isEqualTo("GONE")

    // 7. Test Flag type (Passes post-resolved Set of Strings)
    val flagMetadata = AttributeMetadata("gravity", 5, PropertyType.INT_FLAG)
    val flagProto = flagMetadata.toProtoAttribute(stringTable, view, setOf("left", "top"), emptyMap(), false)
    assertThat(flagProto).isNotNull()
    assertThat(stringTable.getString(flagProto!!.value)).isEqualTo("left|top")

    // 8. Test Float type
    val floatMetadata = AttributeMetadata("alpha", 6, PropertyType.FLOAT)
    val floatProto = floatMetadata.toProtoAttribute(stringTable, view, 1.0f, emptyMap(), false)
    assertThat(floatProto).isNotNull()
    assertThat(stringTable.getString(floatProto!!.value)).isEqualTo("1.0")

    // 9. Test Double type
    val doubleMetadata = AttributeMetadata("scale", 7, PropertyType.DOUBLE)
    val doubleProto = doubleMetadata.toProtoAttribute(stringTable, view, 3.0, emptyMap(), false)
    assertThat(doubleProto).isNotNull()
    assertThat(stringTable.getString(doubleProto!!.value)).isEqualTo("3.0")
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
    val metadata = AttributeMetadata("text", 0, PropertyType.STRING)
    val sourceMap = mapOf(0 to android.R.id.content)

    val proto = metadata.toProtoAttribute(stringTable, view, "Hello World", sourceMap, includeResolutionStack = true)

    assertThat(proto).isNotNull()
    // Verify direct source
    assertThat(stringTable.getString(proto!!.directSource)).isEqualTo("@android:id/content")
    // Verify style chain
    assertThat(proto.styleChainCount).isEqualTo(1)
    assertThat(stringTable.getString(proto.getStyleChain(0))).isEqualTo("@android:layout/simple_list_item_1")
  }
}
