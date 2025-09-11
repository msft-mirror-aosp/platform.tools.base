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
package com.android.sdklib.deviceprovisioner.testing

import com.android.adblib.ConnectedDevice
import com.android.adblib.serialNumber
import com.android.sdklib.deviceprovisioner.ActivationAction
import com.android.sdklib.deviceprovisioner.ColdBootAction
import com.android.sdklib.deviceprovisioner.DeactivationAction
import com.android.sdklib.deviceprovisioner.DeleteAction
import com.android.sdklib.deviceprovisioner.DeviceHandle
import com.android.sdklib.deviceprovisioner.DeviceId
import com.android.sdklib.deviceprovisioner.DeviceProperties
import com.android.sdklib.deviceprovisioner.DeviceProvisionerPlugin
import com.android.sdklib.deviceprovisioner.DeviceState
import com.android.sdklib.deviceprovisioner.DeviceTemplate
import com.android.sdklib.deviceprovisioner.DuplicateAction
import com.android.sdklib.deviceprovisioner.EditTemplateAction
import com.android.sdklib.deviceprovisioner.EmptyIcon
import com.android.sdklib.deviceprovisioner.RepairDeviceAction
import com.android.sdklib.deviceprovisioner.ShowAction
import com.android.sdklib.deviceprovisioner.TemplateActivationAction
import com.android.sdklib.deviceprovisioner.TemplateState
import com.android.sdklib.deviceprovisioner.TestDefaultDeviceActionPresentation
import com.android.sdklib.deviceprovisioner.WipeDataAction
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A DeviceProvisionerPlugin for testing which simply holds whatever you give it. In contrast to
 * [FakeAdbDeviceProvisionerPlugin], it does not require FakeAdb.
 */
class FakeDeviceProvisionerPlugin(val scope: CoroutineScope, override val priority: Int = 1) :
  DeviceProvisionerPlugin {

  override suspend fun claim(device: ConnectedDevice): DeviceHandle? {
    return devices.value.find { it.serialNumber == device.serialNumber }
  }

  override val devices = MutableStateFlow<List<FakeDeviceHandle>>(emptyList())
  override val templates = MutableStateFlow<List<FakeDeviceTemplate>>(emptyList())

  open class FakeDeviceHandle(
    val serialNumber: String,
    override val scope: CoroutineScope,
    initialState: DeviceState =
      DeviceState.Disconnected(DeviceProperties.buildForTest { icon = EmptyIcon.DEFAULT }),
  ) : DeviceHandle {

    override val id = DeviceId("Fake", false, serialNumber)
    override val stateFlow = MutableStateFlow(initialState)

    override var activationAction: ActivationAction? = null
    override var deactivationAction: DeactivationAction? = null
    override var repairDeviceAction: RepairDeviceAction? = null
    override var showAction: ShowAction? = null
    override var duplicateAction: DuplicateAction? = null
    override var wipeDataAction: WipeDataAction? = null
    override var deleteAction: DeleteAction? = null
    override var coldBootAction: ColdBootAction? = null
  }

  open class FakeDeviceTemplate(
    val modelNumber: String,
    override val properties: DeviceProperties,
  ) : DeviceTemplate {

    override val id = DeviceId("Fake", true, modelNumber)
    override val stateFlow = MutableStateFlow(TemplateState())

    override var activationAction: TemplateActivationAction = FakeTemplateActivationAction()
    override var editAction: EditTemplateAction? = null
  }

  // Create these as needed...
  open class FakeActivationAction : ActivationAction {
    override suspend fun activate() {}

    override val presentation = MutableStateFlow(TestDefaultDeviceActionPresentation.fromContext())
  }

  open class FakeTemplateActivationAction : TemplateActivationAction {
    override suspend fun activate(duration: Duration?): DeviceHandle =
      throw UnsupportedOperationException()

    override val durationUsed = false
    override val presentation = MutableStateFlow(TestDefaultDeviceActionPresentation.fromContext())
  }
}
