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

import com.android.ddmlib.IDevice

/**
 * This interface provides abstraction on how the Deployment pipeline terminate an application.
 *
 * Historically, Deployer does not perform any application terminations. Instead, we rely on the
 * IDE for termination. However, doing some results in many race conditions between deployer,
 * IDE, package manager and knowing issues in the package manager.
 *
 * Starting with AP33+, all race conditions with the package manager should be fixed. Most normal
 * installation will rely completely on package manager to terminate the application correctly.
 *
 * IWI is the only case post API33+ where this is not true. Since we are not interacting with
 * the package manager by definition, we will need to terminate the application within deployment.
 * Instead of relying on interactions with the IDE, the IDE will provide us with a single callback
 * to terminate the running application with this API.
 *
 * @param devices: List of all devices where deployment and application termination will take place.
 * @param appId: Application ID.
 * @param killFunction: A synchronous function that terminate the running application of the given
 *                      ID. Upon return, the application should contain no running process on that
 *                      device.
 */
open class DeployerApplicationTerminator (
    devices: Collection<IDevice>,
    private val appId: String,
    private val killFunction: (IDevice, String) -> Unit) {

    private val devices: HashSet<IDevice> = HashSet(devices)

    fun terminate(device: IDevice) {
        if (devices.remove(device))  {
            killFunction(device, appId)
        }
    }
}
