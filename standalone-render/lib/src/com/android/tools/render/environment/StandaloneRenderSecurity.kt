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
package com.android.tools.render.environment

import com.android.tools.rendering.security.AllowAllRenderSandbox
import com.android.tools.rendering.security.RenderSandbox
import com.android.tools.rendering.security.RenderSecurity

/** Standalone rendering specific implementation of [RenderSecurity] that allows everything. */
@Suppress("VisibleForTests")
class StandaloneRenderSecurity : RenderSecurity {
  private var previousSandbox: RenderSandbox? = null

  override fun activate(credential: Any) {
    previousSandbox = RenderSandbox.setRenderSandbox(AllowAllRenderSandbox)
  }

  override fun deactivate(credential: Any) {
    RenderSandbox.setRenderSandbox(previousSandbox ?: AllowAllRenderSandbox)
  }
}
