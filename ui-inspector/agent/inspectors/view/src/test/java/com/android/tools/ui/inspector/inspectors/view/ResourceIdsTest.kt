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
import android.view.View
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ResourceIdsTest {

  @Test
  fun testIsValidResourceId_validIds() {
    // System resource: Package 0x01, Type 0x02
    assertThat(ResourceIds.isValidResourceId(0x01020001)).isTrue()
    // App resource: Package 0x7f, Type 0x02
    assertThat(ResourceIds.isValidResourceId(0x7f020001)).isTrue()
  }

  @Test
  fun testIsValidResourceId_invalidPackageId() {
    // Package ID is zero
    assertThat(ResourceIds.isValidResourceId(0x00020001)).isFalse()
    // Package ID is 0xFF (disallowed)
    assertThat(ResourceIds.isValidResourceId(0xFF020001.toInt())).isFalse()
  }

  @Test
  fun testIsValidResourceId_invalidTypeId() {
    // Type ID is zero
    assertThat(ResourceIds.isValidResourceId(0x7f000001)).isFalse()
  }

  @Test
  fun testIsValidResourceId_negativeAndZero() {
    assertThat(ResourceIds.isValidResourceId(0)).isFalse()
    assertThat(ResourceIds.isValidResourceId(-1)).isFalse()
  }

  @Test
  fun testResolveResourceToString() {
    val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    val view = View(activity)

    // 1. Test valid platform system resource
    val systemResourceStr = ResourceIds.resolveResourceToString(view, android.R.layout.simple_list_item_1)
    assertThat(systemResourceStr).isEqualTo("@android:layout/simple_list_item_1")

    // 2. Test invalid resource ID (-1)
    val invalidResourceStr = ResourceIds.resolveResourceToString(view, -1)
    assertThat(invalidResourceStr).isNull()

    // 3. Test non-existent positive ID (triggers NotFoundException internally)
    val nonExistentResourceStr = ResourceIds.resolveResourceToString(view, 999999)
    assertThat(nonExistentResourceStr).isNull()
  }
}
