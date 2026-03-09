/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.render

import com.android.ide.common.rendering.api.ResourceNamespace
import com.android.ide.common.rendering.api.ResourceReference
import com.android.resources.ResourceType
import com.android.tools.res.ids.ResourceIdManagerBase
import com.android.tools.res.ids.apk.ApkResourceIdManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandaloneResourceIdManagerTest {

  @Test
  fun testFindById_fallsBackToBaseWhenApkEmpty() {
    val apkManager = ApkResourceIdManager() // Empty
    val baseManager = ResourceIdManagerBase(com.android.tools.res.ids.ResourceIdManagerModelModule.noNamespacingApp(true), true)
    val manager = StandaloneResourceIdManager(apkManager, baseManager)

    // Setup a dynamic ID inside the base manager
    val resRef = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STRING, "test_string")
    val id = baseManager.getOrGenerateId(resRef)

    // Since Apk is empty, findById should fall back to Base and return the resRef
    assertEquals(resRef, manager.findById(id))
  }

  @Test
  fun testGetCompiledId_fallsBackToBaseWhenApkEmpty() {
    val apkManager = ApkResourceIdManager()
    val baseManager = ResourceIdManagerBase(com.android.tools.res.ids.ResourceIdManagerModelModule.noNamespacingApp(true), true)
    val manager = StandaloneResourceIdManager(apkManager, baseManager)

    val resRef = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STRING, "test_string")

    // Both are empty of compiled IDs, so both return null
    assertNull(manager.getCompiledId(resRef))
  }

  @Test
  fun testGetOrGenerateId_delegatesToBase() {
    val apkManager = ApkResourceIdManager()
    val baseManager = ResourceIdManagerBase(com.android.tools.res.ids.ResourceIdManagerModelModule.noNamespacingApp(true), true)
    val manager = StandaloneResourceIdManager(apkManager, baseManager)

    val resRef = ResourceReference(ResourceNamespace.RES_AUTO, ResourceType.STRING, "test_string")

    val id = manager.getOrGenerateId(resRef)
    assertEquals(id, baseManager.getOrGenerateId(resRef)) // Must match
  }
}
