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
package com.android.tools.deployer.model

import com.android.ddmlib.IDevice

class DeploymentPlan(val app: App, val appState: AppState) {
  // The first ABI is always the most preferable on the device.
  constructor(device: IDevice, app: App) : this(app, AppState(device.abis[0]))

  // We can only IWI things that we can dump. Which basically limit ourselves to package
  // manager APKs... for now.
  fun canIwi() = app.allStrategies.all { it is PackageManagerApk }

  fun getApksForPackageManager(): List<Apk> {
    return app.getApksForPackageManager(appState.abi)
  }
}
