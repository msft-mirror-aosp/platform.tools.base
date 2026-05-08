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
    val stringProto = stringMetadata.toProtoAttribute(stringTable, view, "Hello World")
    assertThat(stringProto).isNotNull()
    assertThat(stringTable.getString(stringProto!!.name)).isEqualTo("text")
    assertThat(stringTable.getString(stringProto.value)).isEqualTo("Hello World")

    // 2. Test Boolean type (Boolean true)
    val boolMetadata = AttributeMetadata("clickable", 1, PropertyType.BOOLEAN)
    val boolProto = boolMetadata.toProtoAttribute(stringTable, view, true)
    assertThat(boolProto).isNotNull()
    assertThat(stringTable.getString(boolProto!!.value)).isEqualTo("true")

    // 3. Test Boolean type (Int true)
    val boolIntProto = boolMetadata.toProtoAttribute(stringTable, view, 1)
    assertThat(boolIntProto).isNotNull()
    assertThat(stringTable.getString(boolIntProto!!.value)).isEqualTo("true")

    // 4. Test Color type
    val colorMetadata = AttributeMetadata("textColor", 2, PropertyType.COLOR)
    val colorProto = colorMetadata.toProtoAttribute(stringTable, view, 0xFFFFFFFF.toInt())
    assertThat(colorProto).isNotNull()
    assertThat(stringTable.getString(colorProto!!.value)).isEqualTo("#FFFFFFFF")

    // 5. Test Resource type (Valid System Resource)
    val resMetadata = AttributeMetadata("background", 3, PropertyType.RESOURCE)
    val resProto = resMetadata.toProtoAttribute(stringTable, view, android.R.layout.simple_list_item_1)
    assertThat(resProto).isNotNull()
    assertThat(stringTable.getString(resProto!!.value)).isEqualTo("@android:layout/simple_list_item_1")

    // 6. Test Enum type (Passes post-resolved String)
    val enumMetadata = AttributeMetadata("visibility", 4, PropertyType.INT_ENUM)
    val enumProto = enumMetadata.toProtoAttribute(stringTable, view, "GONE")
    assertThat(enumProto).isNotNull()
    assertThat(stringTable.getString(enumProto!!.value)).isEqualTo("GONE")

    // 7. Test Flag type (Passes post-resolved Set of Strings)
    val flagMetadata = AttributeMetadata("gravity", 5, PropertyType.INT_FLAG)
    val flagProto = flagMetadata.toProtoAttribute(stringTable, view, setOf("left", "top"))
    assertThat(flagProto).isNotNull()
    assertThat(stringTable.getString(flagProto!!.value)).isEqualTo("left|top")
  }
}
