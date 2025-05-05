/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.deploy.service

import com.android.tools.deploy.proto.Deploy
import com.android.tools.deploy.proto.Deploy.InstallerRequest
import com.android.tools.deploy.proto.Deploy.InstallerResponse
import com.android.tools.deployer.Installer
import java.io.IOException

class FakeInstaller : Installer() {

    var capturedNetworkRequest = mutableListOf<Deploy.NetworkTestRequest>()

    override fun sendInstallerRequest(
        request: InstallerRequest?, timeOutMs: Long
    ): InstallerResponse {
        throw IllegalStateException("Not implemented")
    }

    override fun onAsymetry(req: InstallerRequest?, resp: InstallerResponse?) {
        throw IllegalStateException("Not implemented")
    }

    @Throws(IOException::class)
    override fun networkTest(request: Deploy.NetworkTestRequest): Deploy.NetworkTestResponse {
        capturedNetworkRequest.add(request)
        return Deploy.NetworkTestResponse.newBuilder().setProcessingDurationNs(1L).build()
    }
}
