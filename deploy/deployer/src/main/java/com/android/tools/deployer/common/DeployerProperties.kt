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
package com.android.tools.deployer.common

import com.android.adblib.AdbSession
import com.android.adblib.AdbSessionHost

object DeployerProperties {
  const val USE_CONNECTED_DEVICE_PROPERTY_NAME = "com.android.tools.deployer.use.connected.device"
  const val USE_CONNECTED_DEVICE_DEFAULT_VALUE = false

  /**
   * Property controlling whether deployment uses the adblib `ConnectedDevice` abstraction instead of legacy ddmlib `IDevice`.
   *
   * Callers should always prefer reading this property via `AdbSession.property(DeployerProperties.USE_CONNECTED_DEVICE)`) or
   * [AdbSessionHost.getPropertyValue], as the host environment may delegate or override this value (such as via Studio flags).
   *
   * [USE_CONNECTED_DEVICE_PROPERTY_NAME] should only be read directly via [System.getProperty] during early runner bootstrapping before an
   * [AdbSession] or host has been created.
   */
  val USE_CONNECTED_DEVICE = AdbSessionHost.BooleanProperty(USE_CONNECTED_DEVICE_PROPERTY_NAME, USE_CONNECTED_DEVICE_DEFAULT_VALUE)
}
