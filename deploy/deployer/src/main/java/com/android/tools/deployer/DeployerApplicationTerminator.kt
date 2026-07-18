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
package com.android.tools.deployer

/**
 * This class provides an abstraction on how the Deployment pipeline terminates an application.
 *
 * Historically, Deployer did not perform any application terminations. Instead, we relied on the IDE for termination. However, doing so
 * resulted in many race conditions between deployer, IDE, package manager and known issues in the package manager.
 *
 * Starting with API 33+, all race conditions with the package manager should be fixed. Most normal installations will rely completely on
 * the package manager to terminate the application correctly.
 *
 * IWI is the only case post-API 33+ where this is not true. Since we are not interacting with the package manager by definition, we need to
 * terminate the application within deployment. Instead of relying on complex interactions with the IDE, the IDE provides a single callback
 * (`killFunction`) to terminate the running application with this API.
 *
 * @param devices List of all target devices where deployment and application termination will take place.
 * @param appId Application ID.
 * @param killFunction A synchronous function that terminates the running application of the given ID. Upon return, the application should
 *   contain no running process on that device.
 */
open class DeployerApplicationTerminator(
  devices: Collection<DeployerDevice>,
  private val appId: String,
  private val killFunction: (DeployerDevice, String) -> Unit,
) {

  private val devices: HashSet<DeployerDevice> = HashSet(devices)

  fun terminate(device: DeployerDevice) {
    if (devices.remove(device)) {
      killFunction(device, appId)
    }
  }
}
