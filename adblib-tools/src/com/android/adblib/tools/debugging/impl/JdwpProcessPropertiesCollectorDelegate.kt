/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.adblib.tools.debugging.impl

import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.JdwpProcessPropertiesCollector
import com.android.adblib.tools.debugging.jdwpPropertiesCollector
import com.android.adblib.tools.debugging.utils.StateFlowForwarder
import kotlinx.coroutines.flow.StateFlow

internal class JdwpProcessPropertiesCollectorDelegate(
  override val process: JdwpProcess,
  private val processProvider: AbstractJdwpProcessDelegateProvider,
) : JdwpProcessPropertiesCollector {

  private val mutableStateFlowForwarder =
    StateFlowForwarder(
      session = process.device.session,
      parentScope = process.scope,
      sourceStateFlowProvider = { processProvider.abstractJdwpProcess().jdwpPropertiesCollector.stateFlow },
      defaultValue = JdwpProcessProperties(pid = process.pid),
    )

  override val stateFlow: StateFlow<JdwpProcessProperties>
    get() = mutableStateFlowForwarder.stateFlow
}
