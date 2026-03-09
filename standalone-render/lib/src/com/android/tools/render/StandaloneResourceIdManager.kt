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

import com.android.ide.common.rendering.api.ResourceReference
import com.android.tools.res.ids.ResourceIdManager
import com.android.tools.res.ids.ResourceIdManagerBase
import com.android.tools.res.ids.apk.ApkResourceIdManager
import java.util.function.Consumer

class StandaloneResourceIdManager(private val apkIdManager: ApkResourceIdManager, private val baseIdManager: ResourceIdManagerBase) :
  ResourceIdManager {
  override val finalIdsUsed: Boolean = true

  override fun findById(id: Int): ResourceReference? = apkIdManager.findById(id) ?: baseIdManager.findById(id)

  override fun getCompiledId(resource: ResourceReference): Int? =
    apkIdManager.getCompiledId(resource) ?: baseIdManager.getCompiledId(resource)

  override fun getOrGenerateId(resource: ResourceReference): Int = baseIdManager.getOrGenerateId(resource)

  override fun resetDynamicIds() = baseIdManager.resetDynamicIds()

  override fun getGeneration(): Long = baseIdManager.getGeneration()

  override fun resetCompiledIds(rClassProvider: Consumer<ResourceIdManager.RClassParser>) {
    baseIdManager.resetCompiledIds(rClassProvider)
  }
}
