/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ddmlib

import com.android.ddmlib.AndroidDebugBridge.IClientChangeListener
import com.android.ddmlib.AndroidDebugBridge.IDeviceChangeListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class AndroidDebugBridgeChangeEventsTest {

    @Test
    fun testBridgeChangeListenerCounts() { // Prepare
        val events = AndroidDebugBridgeChangeEvents()
        val bridgeChangeListener = FakeIDebugBridgeChangeListener()

        // Act / Assert
        assertEquals(0, events.debugBridgeChangeListenerCount())
        events.addDebugBridgeChangeListener(bridgeChangeListener)
        assertEquals(1, events.debugBridgeChangeListenerCount())
        events.removeDebugBridgeChangeListener(bridgeChangeListener)
        assertEquals(0, events.debugBridgeChangeListenerCount())
    }

    @Test
    fun testBridgeChangeListenerEvents() { // Prepare
        val events = AndroidDebugBridgeChangeEvents()
        val bridgeChangeListener = FakeIDebugBridgeChangeListener()
        events.addDebugBridgeChangeListener(bridgeChangeListener)
        val mockBridge = mock<AndroidDebugBridge>()

        // Act / Assert
        events.notifyBridgeChanged(mockBridge)
        assertEquals(mockBridge, bridgeChangeListener.lastBridge)
        events.notifyBridgeChanged(null)
        assertNull(bridgeChangeListener.lastBridge)
        events.notifyBridgeRestartInitiated()
        assertTrue(bridgeChangeListener.restartInitializedCalled)
        events.notifyBridgeRestartCompleted(true)
        assertEquals(true, bridgeChangeListener.restartCompletedWith)
        val exception = Exception("Test exception instance")
        events.notifyBridgeInitializationError(exception)
        assertEquals(exception, bridgeChangeListener.lastInitializationError)
    }

    @Test
    fun testDeviceChangeListenerCounts() { // Prepare
        val events = AndroidDebugBridgeChangeEvents()
        val deviceChangeListener = FakeIDeviceChangeListener()

        // Act / Assert
        assertEquals(0, events.deviceChangeListenerCount())
        events.addDeviceChangeListener(deviceChangeListener)
        assertEquals(1, events.deviceChangeListenerCount())
        events.removeDeviceChangeListener(deviceChangeListener)
        assertEquals(0, events.deviceChangeListenerCount())
    }

    @Test
    fun testDeviceChangeListenerEvents() { // Prepare
        val events = AndroidDebugBridgeChangeEvents()
        val deviceChangeListener = FakeIDeviceChangeListener()
        events.addDeviceChangeListener(deviceChangeListener)
        val mockDevice = mock<IDevice>()

        // Act / Assert
        events.notifyDeviceConnected(mockDevice)
        assertEquals(mockDevice, deviceChangeListener.lastConnectedDevice)
        events.notifyDeviceChanged(mockDevice, 10)
        assertEquals(mockDevice, deviceChangeListener.lastChangedDevice)
        assertEquals(10, deviceChangeListener.lastChangedDeviceChangeMask)
        events.notifyDeviceDisconnected(mockDevice)
        assertEquals(mockDevice, deviceChangeListener.lastDisconnectedDevice)
    }

    @Test
    fun testClientChangeListenerEvents() { // Prepare
        val events = AndroidDebugBridgeChangeEvents()
        val clientChangeListener = FakeIClientChangeListener()
        events.addClientChangeListener(clientChangeListener)
        val mockClient = mock<Client>()

        // Act / Assert
        events.notifyClientChanged(mockClient, 10)
        assertEquals(mockClient, clientChangeListener.lastChangedClient)
        assertEquals(10, clientChangeListener.lastChangedClientChangeMask)
    }

    private class FakeIDebugBridgeChangeListener : AndroidDebugBridge.IDebugBridgeChangeListener {

        var lastBridge: AndroidDebugBridge? = null
        var lastInitializationError: Exception? = null
        var restartInitializedCalled: Boolean = false
        var restartCompletedWith: Boolean? = null

        override fun bridgeChanged(bridge: AndroidDebugBridge?) {
            lastBridge = bridge
        }

        override fun restartInitiated() {
            restartInitializedCalled = true
        }

        override fun restartCompleted(isSuccessful: Boolean) {
            restartCompletedWith = isSuccessful
        }

        override fun initializationError(exception: Exception) {
            lastInitializationError = exception
        }
    }

    private class FakeIDeviceChangeListener : IDeviceChangeListener {

        var lastConnectedDevice: IDevice? = null
        var lastDisconnectedDevice: IDevice? = null
        var lastChangedDevice: IDevice? = null
        var lastChangedDeviceChangeMask: Int? = null

        override fun deviceConnected(device: IDevice) {
            lastConnectedDevice = device
        }

        override fun deviceDisconnected(device: IDevice) {
            lastDisconnectedDevice = device
        }

        override fun deviceChanged(device: IDevice, changeMask: Int) {
            lastChangedDevice = device
            lastChangedDeviceChangeMask = changeMask
        }
    }

    private class FakeIClientChangeListener : IClientChangeListener {

        var lastChangedClient: Client? = null
        var lastChangedClientChangeMask: Int? = null
        override fun clientChanged(client: Client, changeMask: Int) {
            lastChangedClient = client
            lastChangedClientChangeMask = changeMask
        }
    }
}
