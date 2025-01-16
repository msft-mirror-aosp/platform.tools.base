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
package com.android.backup.testing

import com.android.backup.AdbServices
import com.android.backup.AdbServicesFactory
import com.android.backup.BackupProgressListener
import com.android.backup.testing.FakeAdbServices.CommandOverride.Output

class FakeAdbServicesFactory(
  private val appId: String,
  private val configure: (FakeAdbServices) -> Unit = {},
) : AdbServicesFactory {

  lateinit var adbServices: FakeAdbServices

  override fun createAdbServices(
    serialNumber: String,
    listener: BackupProgressListener?,
    steps: Int,
  ): AdbServices {
    adbServices = FakeAdbServices(serialNumber, steps)
    adbServices.addCommandOverride(Output("pm list packages $appId", "package:$appId"))
    configure(adbServices)
    return adbServices
  }
}
