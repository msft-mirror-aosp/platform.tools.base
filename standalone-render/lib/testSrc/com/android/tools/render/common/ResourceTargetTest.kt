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
package com.android.tools.render.common

import com.android.resources.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResourceTargetTest {

  @Test
  fun parsesRClassFields() {
    assertEquals(ResourceTarget.Valid(ResourceType.RAW, "watchface"), ResourceTarget.parse("R.raw.watchface"))
    assertEquals(ResourceTarget.Valid(ResourceType.LAYOUT, "activity_main"), ResourceTarget.parse("R.layout.activity_main"))
    assertEquals(
      ResourceTarget.Valid(ResourceType.DRAWABLE, "ic_logo", "com.example.lib"),
      ResourceTarget.parse("com.example.lib.R.drawable.ic_logo"),
    )
    assertEquals(
      ResourceTarget.Valid(ResourceType.DRAWABLE, "ic_menu_add", "android"),
      ResourceTarget.parse("android.R.drawable.ic_menu_add"),
    )
  }

  @Test
  fun parsesResourceReferences() {
    assertEquals(ResourceTarget.Valid(ResourceType.RAW, "watchface"), ResourceTarget.parse("@raw/watchface"))
    assertEquals(ResourceTarget.Valid(ResourceType.RAW, "watchface"), ResourceTarget.parse("@raw/watchface.xml"))
    assertEquals(
      ResourceTarget.Valid(ResourceType.DRAWABLE, "ic_menu_add", "android"),
      ResourceTarget.parse("@android:drawable/ic_menu_add"),
    )
  }

  @Test
  fun explainsTargetsWrittenLikeResourcesThatAreInvalid() {
    assertEquals(ResourceTarget.Invalid("unknown resource type 'layot'"), ResourceTarget.parse("R.layot.activity_main"))
    assertEquals(ResourceTarget.Invalid("unknown resource type 'layot'"), ResourceTarget.parse("com.example.R.layot.activity_main"))
    assertEquals(ResourceTarget.Invalid("unknown resource type 'unknowntype'"), ResourceTarget.parse("@unknowntype/name"))
    assertEquals(ResourceTarget.Invalid("unknown resource type 'drawble'"), ResourceTarget.parse("@drawble/ic_logo.xml"))
    assertEquals(ResourceTarget.Invalid("unknown resource type 'drawble'"), ResourceTarget.parse("@android:drawble/ic_menu_add"))
    val notPreviewable = ResourceTarget.Invalid("not a resource reference that can be previewed")
    assertEquals(notPreviewable, ResourceTarget.parse("@raw/"))
    assertEquals(notPreviewable, ResourceTarget.parse("@layout/.xml"))
    assertEquals(notPreviewable, ResourceTarget.parse("@+id/title"))
    assertEquals(notPreviewable, ResourceTarget.parse("R.layout."))
  }

  @Test
  fun returnsNullForTargetsNotWrittenLikeResources() {
    assertNull(ResourceTarget.parse("com.example.ui.HomeScreenKt.HomePreview"))
    assertNull(ResourceTarget.parse("com.example.R.raw"))
    assertNull(ResourceTarget.parse(""))
  }
}
