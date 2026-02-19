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


private fun killFunction(device: IDevice, appId: String) : Unit {
    throw UnsupportedOperationException(
        "Commandline deployer does not support application termination without package manager.")
}

internal class DeployerRunnerApplicationTerminator(device: IDevice, appId: String) :
    DeployerApplicationTerminator(listOf(device), appId, ::killFunction) {
}
