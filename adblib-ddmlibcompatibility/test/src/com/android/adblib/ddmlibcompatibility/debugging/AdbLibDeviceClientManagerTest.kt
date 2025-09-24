/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.adblib.ddmlibcompatibility.debugging

import com.android.adblib.ddmlibcompatibility.testutils.FakeIDevice
import com.android.adblib.ddmlibcompatibility.testutils.InitAndroidDebugBridgeRule
import com.android.adblib.ddmlibcompatibility.testutils.TestDeviceClientManagerListener
import com.android.adblib.ddmlibcompatibility.testutils.TestDeviceClientManagerListener.EventKind.PROCESS_LIST_UPDATED
import com.android.adblib.ddmlibcompatibility.testutils.TestDeviceClientManagerListener.EventKind.PROCESS_NAME_UPDATED
import com.android.adblib.ddmlibcompatibility.testutils.UseAdbLibAndroidDebugBridgeRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.adblib.tools.debugging.DdmsProtocolKind
import com.android.adblib.tools.debugging.JdwpProcessHolder
import com.android.adblib.tools.debugging.ddmsProtocolKind
import com.android.adblib.tools.debugging.packets.ddms.DdmsPacketConstants
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.AndroidDebugBridge.IDeviceChangeListener
import com.android.ddmlib.Client
import com.android.ddmlib.ClientData
import com.android.ddmlib.DebugViewDumpHandler
import com.android.ddmlib.IDevice
import com.android.ddmlib.clientmanager.DeviceClientManager
import com.android.ddmlib.clientmanager.DeviceClientManagerListener
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.devicecommandhandlers.ddmsHandlers.readLengthPrefixedString
import com.android.sdklib.AndroidApiLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import org.junit.Before
import org.junit.rules.RuleChain

class AdbLibDeviceClientManagerTest {

    private val fakeAdb = FakeAdbServerProviderRule()
    private val useAdbLibAndroidDebugBridgeRule =
        UseAdbLibAndroidDebugBridgeRule { fakeAdb.adbSession }
    private val initAndroidDebugBridgeRule =
        InitAndroidDebugBridgeRule { fakeAdb.fakeAdb.port }
    private val exceptionRule: ExpectedException = ExpectedException.none()

    private lateinit var bridge: AndroidDebugBridge

    @Before
    fun setUp() {
        bridge = AndroidDebugBridge.createBridge() ?: error("Couldn't create a bridge")
    }

    @JvmField
    @Rule
    val ruleChain: RuleChain = RuleChain.outerRule(fakeAdb)
        .around(useAdbLibAndroidDebugBridgeRule)
        .around(initAndroidDebugBridgeRule)
        .around(exceptionRule)

    @Test
    fun testTrackingWaitsUntilDeviceIsTracked() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()

        // Act
        val deviceSerial = "1234"
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Delay a little, to make sure deviceClientManager has to wait a little
        // before it starts tracking the device. Note the delay here should be less than
        // the DEVICE_TRACKER_WAIT_TIMEOUT timeout used by AdbLibDeviceClientManager.
        delay(500)
        val clientSizeBeforeTracking = deviceClientManager.clients.size
        val deviceState = connectTestDevice(deviceSerial)
        deviceState.startClient(10, 0, "foo.bar", false)

        // If things work as expected, the client list should be updated
        yieldUntil {
            deviceClientManager.clients.size == 1
        }

        // Assert
        Assert.assertEquals(0, clientSizeBeforeTracking)
    }

    @Test
    fun testScopeIsCancelledWhenDeviceDisconnects() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )
        deviceState.startClient(10, 0, "foo.bar", false)
        deviceState.startClient(12, 0, "foo.bar.baz", false)
        yieldUntil {
            deviceClientManager.clients.size == 2
        }

        // Act
        fakeAdb.fakeAdb.disconnectDevice(fakeIDevice.serialNumber)
        yieldUntil {
            listener.filterEvents { events -> events.any { it.kind == PROCESS_LIST_UPDATED } }
                    && deviceClientManager.clients.size == 0
        }

        // Assert
        listener.events()
            .lastOrNull { it.kind == PROCESS_LIST_UPDATED }
            ?.also { lastEvent ->
                Assert.assertSame(deviceClientManager, lastEvent.deviceClientManager)
                Assert.assertSame(bridge, lastEvent.bridge)
            } ?: Assert.fail("No PROCESS_LIST_UPDATED event")
        Unit
    }

    @Test
    fun testCoroutinesAreCancelledAfterDeviceDisconnects() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val clientManagerCount = 20
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        deviceState.startClient(10, 0, "foo.bar", false)
        deviceState.startClient(12, 0, "foo.bar.baz", false)

        // Act
        val jobsBefore = fakeAdb.adbSession.scope.collectJobs()

        // Create and start `clientManagerCount` device client managers concurrently
        coroutineScope {
            (1..clientManagerCount).map {
                async {
                    val deviceClientManager =
                        clientManager.createDeviceClientManager(
                            bridge,
                            fakeIDevice,
                            listener
                        )
                    yieldUntil { deviceClientManager.clients.size == 2 }
                }
            }.awaitAll()
        }

        // Disconnecting the device should lead to all device client manager instances to stop
        // all their coroutines and release their resources (we add a little delay, because this
        // is asynchronous)
        fakeAdb.fakeAdb.disconnectDevice(deviceSerial)
        delay(100)

        val jobsAfter = fakeAdb.adbSession.scope.collectJobs()

        // Assert
        // There are typically 1 or 2 coroutines more in `jobsAfter` because
        // some coroutines are lazily created. In order for this test to be somewhat reliable,
        // we merely check that the number of leftover coroutines is less than a pre-defined
        // constant, i.e. O(1), as opposed to proportional to the number of device client
        // manager instances created, i.e. O(n).
        val maxJobCountDelta = 5
        assert(maxJobCountDelta < clientManagerCount)
        val message = "Given that $clientManagerCount \"${DeviceClientManager::class.simpleName}\" " +
                "instances were created and the device has been disconnected, the number " +
                "of active coroutines in the session scope has not decreased enough.\n\n" +
                "There were $clientManagerCount \"${DeviceClientManager::class.simpleName}\" " +
                "instances created.\n" +
                "There were ${jobsBefore.size} coroutines before creating these instances.\n" +
                "There were ${jobsAfter.size} coroutines left after disconnecting the device.\n" +
                "There should be fewer than ${jobsBefore.size + maxJobCountDelta} " +
                "coroutines left after disconnecting the device.\n"
        Assert.assertTrue(
            message,
            abs(jobsAfter.size - jobsBefore.size) <= maxJobCountDelta
        )
    }

    @Test
    fun testClientListIsUpdatedWhenProcessesStart() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        deviceState.startClient(10, 0, "foo.bar", false)
        deviceState.startClient(12, 0, "foo.bar.baz", false)
        yieldUntil {
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            deviceClientManager.clients.size == 2 &&
                    deviceClientManager.clients.all {
                        it.debuggerListenPort > 0 &&
                                it.clientData.vmIdentifier != null &&
                                it.clientData.hasFeature("method-trace-profiling-streaming")
                    }
        }

        // Assert
        val clients = deviceClientManager.clients
        Assert.assertEquals(2, clients.size)
        Assert.assertNotNull(clients.find { it.clientData.pid == 10 })
        Assert.assertNull(clients.find { it.clientData.pid == 11 })
        Assert.assertNotNull(clients.find { it.clientData.pid == 12 })

        val client = clients.first { it.clientData.pid == 10 }
        Assert.assertSame(fakeIDevice, client.device)
        Assert.assertNotNull(client.clientData)
        Assert.assertEquals(10, client.clientData.pid)
        Assert.assertTrue(client.clientData.hasFeature("view-hierarchy"))
        Assert.assertTrue(client.isDdmAware)
        Assert.assertTrue(client.isValid)
        Assert.assertTrue(client.debuggerListenPort > 0)
        Assert.assertFalse(client.isDebuggerAttached)
        //TODO(b/266699981): Add unit test
        //assertThrows { client.startMethodTracer() }
        //assertThrows { client.stopMethodTracer() }
        //assertThrows { client.startSamplingProfiler(10, TimeUnit.SECONDS) }
        //assertThrows { client.stopSamplingProfiler() }
        //TODO(b/266699981): Add unit test
        //assertThrows { client.requestAllocationDetails() }
        //assertThrows { client.enableAllocationTracker(false) }
        Assert.assertEquals(Unit, client.notifyVmMirrorExited())
        assertThrows { client.dumpDisplayList("v", "v1") }
    }

    @Test
    fun testClientListUpdatesAreSerialized() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val ddmlibListener = object : IDeviceChangeListener {
            val lock = Any()

            @Volatile
            var totalCalls = 0

            @Volatile
            var concurrentCalls = 0

            @Volatile
            var maxConcurrentCalls = 0

            override fun deviceConnected(device: IDevice) {
            }

            override fun deviceDisconnected(device: IDevice) {
            }

            override fun deviceChanged(device: IDevice, changeMask: Int) {
                // We are interested in keeping track of Client/ProfileableClient changes and not
                // changes like `IDevice.CHANGE_STATE` which is triggered from `startOnlineDeviceTracking`
                if (changeMask != IDevice.CHANGE_CLIENT_LIST && changeMask != IDevice.CHANGE_PROFILEABLE_CLIENT_LIST) {
                    return
                }
                synchronized(lock) {
                    totalCalls++
                    concurrentCalls++
                    maxConcurrentCalls = max(concurrentCalls, maxConcurrentCalls)
                }
                Thread.sleep(1)
                synchronized(lock) {
                    concurrentCalls--
                }
            }
        }
        val listener = AndroidDebugBridgeListener(ddmlibListener)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        while (ddmlibListener.totalCalls < 100) {
            deviceState.startClient(10, 0, "foo.bar", false)
            deviceState.startClient(12, 0, "foo.bar.baz", false)
            delay(5)
            deviceState.startClient(14, 0, "foo.bar.baz.blah", false)
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            yieldUntil { deviceClientManager.clients.size == 3 }
            deviceState.stopClient(14)
            delay(1)
            deviceState.stopClient(12)
            deviceState.stopClient(10)
        }

        // Assert
        Assert.assertEquals(
            "There were more than one concurrent call to the listener, meaning calls were not serialized as expected",
            1,
            ddmlibListener.maxConcurrentCalls
        )
    }

    @Test
    fun testClientListIsUpdatedWhenProcessesStop() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )
        deviceState.startClient(10, 0, "foo.bar", false)
        deviceState.startClient(12, 0, "foo.bar.baz", false)
        yieldUntil {
            deviceClientManager.clients.size == 2
        }

        // Act
        deviceState.stopClient(10)
        yieldUntil {
            deviceClientManager.clients.size == 1
        }

        // Assert
        Assert.assertEquals(12, deviceClientManager.clients.last().clientData.pid)
    }

    @Test
    fun testListenerIsCalledWhenProcessesStart() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        deviceState.startClient(10, 0, "foo.bar", false)
        deviceState.startClient(12, 0, "foo.bar.baz", false)
        yieldUntil {
            listener.filterEvents { events -> events.any { it.kind == PROCESS_LIST_UPDATED } }
        }

        // Assert
        val firstEvent = listener.events().first { it.kind == PROCESS_LIST_UPDATED }
        Assert.assertSame(deviceClientManager, firstEvent.deviceClientManager)
        Assert.assertSame(bridge, firstEvent.bridge)
        Assert.assertEquals(PROCESS_LIST_UPDATED, firstEvent.kind)
        Assert.assertNull(firstEvent.client)
    }

    @Test
    fun testListenerIsCalledWhenProcessesEnd() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        deviceState.startClient(10, 0, "foo.bar", false)
        deviceState.startClient(12, 0, "foo.bar.baz", false)
        yieldUntil {
            listener.events().isNotEmpty()
        }
        listener.clearEvents()

        deviceState.stopClient(10)
        deviceState.stopClient(12)
        yieldUntil {
            listener.filterEvents { events -> events.any { it.kind == PROCESS_LIST_UPDATED } } &&
                deviceClientManager.clients.isEmpty()
        }

        // Assert
        val processListUpdatedEvent = listener.events().last { it.kind == PROCESS_LIST_UPDATED }
        Assert.assertSame(deviceClientManager, processListUpdatedEvent.deviceClientManager)
        Assert.assertSame(bridge, processListUpdatedEvent.bridge)
        Assert.assertNull(processListUpdatedEvent.client)
    }

    @Test
    fun testListenerIsCalledWhenProcessPropertiesChange() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        deviceState.startClient(10, 0, "foo.bar", false)
        yieldUntil {
            listener.filterEvents { events -> events.any { it.kind == PROCESS_NAME_UPDATED } }
                    && listener.filterEvents { events -> events.any { it.kind == PROCESS_LIST_UPDATED } }
                    && deviceClientManager.clients.any { it.clientData.processName == "foo.bar" }
        }

        // Assert
        Assert.assertTrue(
            "Should have received a process list changed event",
            listener.filterEvents { events ->
                events.any { it.kind == TestDeviceClientManagerListener.EventKind.PROCESS_LIST_UPDATED } })

        Assert.assertTrue(
            "Should have received at least one process name changed event",
            listener.filterEvents { events ->
                events.any { it.kind == TestDeviceClientManagerListener.EventKind.PROCESS_NAME_UPDATED } })

        val event = listener.filterEvents { events -> events.last { it.kind == TestDeviceClientManagerListener.EventKind.PROCESS_NAME_UPDATED } }
        Assert.assertSame(event.deviceClientManager, deviceClientManager)
        Assert.assertNotNull(event.client)
        Assert.assertEquals("FakeVM", event.client!!.clientData.vmIdentifier)
    }

    @Test
    fun testClientIsJdwpProcessHolder() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        deviceState.startClient(10, 0, "foo.bar", false)
        yieldUntil { deviceClientManager.clients.size == 1 }
        val client = deviceClientManager.clients.first { it.clientData.pid == 10 }

        // Assert
        Assert.assertTrue(client is JdwpProcessHolder)
        val jdwpProcess = (client as JdwpProcessHolder).jdwpProcessValue
        Assert.assertEquals(client.clientData.pid, jdwpProcess.pid)
    }

    @Test
    fun testClientKillWorks() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        deviceState.startClient(10, 0, "foo.bar", false)
        yieldUntil {
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            deviceClientManager.clients.size == 1 &&
                    deviceClientManager.clients.all {
                        it.debuggerListenPort > 0 && it.clientData.vmIdentifier != null
                    }
        }
        val client = deviceClientManager.clients.first { it.clientData.pid == 10 }
        client.kill()

        // Assert: Wait until process has disappeared from the list of Clients
        yieldUntil { deviceClientManager.clients.isEmpty() }
    }

    @Test
    fun testListViewRootsWorks() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        val clientState = deviceState.startClient(10, 0, "foo.bar", false)
        listOf("view1", "view2").forEach { clientState.viewsState.addViewRoot(it) }
        yieldUntil {
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            deviceClientManager.clients.size == 1 &&
                    deviceClientManager.clients.all {
                        it.debuggerListenPort > 0 && it.clientData.vmIdentifier != null
                    }
        }
        val client = deviceClientManager.clients.first { it.clientData.pid == 10 }
        val handler = ListViewRootsHandler()
        val viewRoots = handler.getWindows(client)

        // Assert
        Assert.assertEquals(2, viewRoots.size)
        Assert.assertEquals("view1", viewRoots[0])
        Assert.assertEquals("view2", viewRoots[1])
    }

    @Test
    fun testCaptureViewWorks() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        val clientState = deviceState.startClient(10, 0, "foo.bar", false)

        val data1  = createFakeViewData("view1", 100)
        val data2  = createFakeViewData("view2", 255)
        clientState.viewsState.addViewCapture("rootView", "view1", data1)
        clientState.viewsState.addViewCapture("rootView", "view2", data2)
        yieldUntil {
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            deviceClientManager.clients.size == 1 &&
                    deviceClientManager.clients.all {
                        it.debuggerListenPort > 0 && it.clientData.vmIdentifier != null
                    }
        }
        val client = deviceClientManager.clients.first { it.clientData.pid == 10 }

        val buffer1 = ByteBufferDebugViewHandler().run {
            client.captureView("rootView", "view1", this)
        }

        val buffer2 = ByteBufferDebugViewHandler().run {
            client.captureView("rootView", "view2", this)
        }

        // Assert
        assertEqualsByteBuffer(data1, buffer1)
        assertEqualsByteBuffer(data2, buffer2)
    }

    @Test
    fun testCaptureViewTimesOutOnInvalidArgs() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )
        val clientState = deviceState.startClient(10, 0, "foo.bar", false)
        yieldUntil {
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            deviceClientManager.clients.size == 1 &&
                    deviceClientManager.clients.all {
                        it.debuggerListenPort > 0 && it.clientData.vmIdentifier != null
                    }
        }
        val client = deviceClientManager.clients.first { it.clientData.pid == 10 }

        // Act: The ddmlib "ViewHandler" APIs do not allow propagating errors to the caller,
        // but we can observe the side effect of the call never terminating.
        exceptionRule.expect(TimeoutCancellationException::class.java)
        withTimeout(1_000) {
            ByteBufferDebugViewHandler().run {
                client.captureView("rootView", "view1", this)
            }
        }

        // Assert
        Assert.fail("Should not reach")
    }

    @Test
    fun testDumpViewHierarchyWorks() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        val clientState = deviceState.startClient(10, 0, "foo.bar", false)

        val data1  = createFakeViewData("view1", 100)
        val data2  = createFakeViewData("view2", 255)
        clientState.viewsState.addViewHierarchy("view1",
                                                skipChildren = false,
                                                includeProperties = true,
                                                useV2 = true,
                                                data = data1
        )
        clientState.viewsState.addViewHierarchy("view2",
                                                skipChildren = false,
                                                includeProperties = false,
                                                useV2 = true,
                                                data = data2
        )
        yieldUntil {
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            deviceClientManager.clients.size == 1 &&
                    deviceClientManager.clients.all {
                        it.debuggerListenPort > 0 && it.clientData.vmIdentifier != null
                    }
        }
        val client = deviceClientManager.clients.first { it.clientData.pid == 10 }

        val buffer1 = ByteBufferDebugViewHandler().run {
            client.dumpViewHierarchy("view1",
                                     false,
                                     true,
                                     true,
                                     this)
        }

        val buffer2 = ByteBufferDebugViewHandler().run {
            client.dumpViewHierarchy("view2",
                                     false,
                                     false,
                                     true,
                                     this)
        }

        // Assert
        assertEqualsByteBuffer(data1, buffer1)
        assertEqualsByteBuffer(data2, buffer2)
    }

    @Test
    fun testExecuteGarbageCollectorWorks() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        val clientState = deviceState.startClient(10, 0, "foo.bar", false)

        yieldUntil {
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            deviceClientManager.clients.size == 1 &&
                    deviceClientManager.clients.all {
                        it.debuggerListenPort > 0 && it.clientData.vmIdentifier != null
                    }
        }
        val client = deviceClientManager.getClientWrapper(10)

        client.executeGarbageCollector()
        client.awaitLegacyOperations()

        // Assert
        Assert.assertEquals(
            DdmsProtocolKind.EmptyRepliesDiscarded,
            client.jdwpProcess.device.ddmsProtocolKind()
        )
        Assert.assertEquals(1, clientState.getHgpcRequestsCount())
    }

    @Test
    fun testExecuteGarbageCollectorOnPreApi28DeviceWorks() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial, sdk = AndroidApiLevel(27))
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        val clientState = deviceState.startClient(10, 0, "foo.bar", false)

        yieldUntil {
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            deviceClientManager.clients.size == 1 &&
                    deviceClientManager.clients.all {
                        it.debuggerListenPort > 0 && it.clientData.vmIdentifier != null
                    }
        }
        val client = deviceClientManager.getClientWrapper(10)
        client.executeGarbageCollector()
        client.awaitLegacyOperations()

        // Assert
        Assert.assertEquals(
            DdmsProtocolKind.EmptyRepliesAllowed,
            client.jdwpProcess.device.ddmsProtocolKind()
        )
        Assert.assertEquals(1, clientState.getHgpcRequestsCount())
    }

    @Test
    fun testRequestAllocationDetails() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )
        val expectedAllocationTrackerDetails = "sample allocation tracker details"

        // Act
        val clientState = deviceState.startClient(10, 0, "foo.bar", false)
        clientState.allocationTrackerDetails = expectedAllocationTrackerDetails

        yieldUntil {
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            deviceClientManager.clients.size == 1 &&
                    deviceClientManager.clients.all {
                        it.debuggerListenPort > 0 && it.clientData.vmIdentifier != null
                    }
        }
        val client = deviceClientManager.getClientWrapper(10)

        var allocationDetailsResult = ""
        ClientData.setAllocationTrackingHandler { data, _ ->
            val dataBuffer = ByteBuffer.wrap(data).order(DdmsPacketConstants.DDMS_CHUNK_BYTE_ORDER)
            allocationDetailsResult = dataBuffer.readLengthPrefixedString()
        }

        client.requestAllocationDetails()
        client.awaitLegacyOperations()
        ClientData.setAllocationTrackingHandler(null)

        // Assert
        Assert.assertEquals(expectedAllocationTrackerDetails, allocationDetailsResult)
    }

    @Test
    fun testEnableAllocationTrackerWorks() = runBlockingWithTimeout {
        // Prepare
        val clientManager = AdbLibClientManager(fakeAdb.adbSession)
        val listener = TestDeviceClientManagerListener()
        val deviceSerial = "1234"
        val deviceState = connectTestDevice(deviceSerial)
        val fakeIDevice = FakeIDevice(deviceSerial)
        val deviceClientManager =
            clientManager.createDeviceClientManager(
                bridge,
                fakeIDevice,
                listener
            )

        // Act
        val clientState = deviceState.startClient(10, 0, "foo.bar", false)

        yieldUntil {
            // Wait for both processes to show up and for both the JDWP proxy
            // and process properties to be initialized.
            deviceClientManager.clients.size == 1 &&
                    deviceClientManager.clients.all {
                        it.debuggerListenPort > 0 && it.clientData.vmIdentifier != null
                    }
        }
        val client = deviceClientManager.getClientWrapper(10)

        client.enableAllocationTracker(true)
        client.awaitLegacyOperations()

        // Assert
        Assert.assertEquals(true, clientState.isAllocationTrackerEnabled)
    }

    private fun connectTestDevice(
        deviceSerial: String,
        sdk: AndroidApiLevel = AndroidApiLevel(31)
    ): DeviceState {
        return fakeAdb.fakeAdb.connectDevice(
            deviceSerial,
            "test1",
            "test2",
            "model",
            sdk,
            DeviceState.HostConnectionType.USB
        ).also { it.deviceStatus = DeviceState.DeviceStatus.ONLINE }
    }

    private fun assertThrows(block: () -> Unit) {
        runCatching(block).onSuccess {
            Assert.fail("Block should throw an exception")
        }
    }

    /**
     * Returns the [List] of [jobs][Job] from the job hierarchy of this [CoroutineScope]
     */
    private fun CoroutineScope.collectJobs(): List<Job> {
        return mutableListOf<Job>().also {
            collectJobs(this.coroutineContext.job, it)
        }
    }

    private fun collectJobs(job: Job, jobs: MutableList<Job>) {
        jobs.add(job)
        job.children.forEach {
            collectJobs(it, jobs)
        }
    }

    private class ListViewRootsHandler : DebugViewDumpHandler() {

        private val viewRootsState = MutableStateFlow<List<String>?>(null)

        override fun handleViewDebugResult(data: ByteBuffer) {
            val viewRoots = mutableListOf<String>()
            val nWindows = data.int
            repeat(nWindows) {
                val len = data.int
                viewRoots.add(getString(data, len))
            }
            viewRootsState.value = viewRoots
        }

        suspend fun getWindows(client: Client): List<String> {
            client.listViewRoots(this)
            return viewRootsState.filterNotNull().first()
        }
    }

    private class ByteBufferDebugViewHandler : DebugViewDumpHandler() {
        private val resultState = MutableStateFlow<ByteBuffer?>(null)

        override fun handleViewDebugResult(data: ByteBuffer) {
            resultState.value = data
        }

        suspend fun run(block: DebugViewDumpHandler.() -> Unit): ByteBuffer {
            this.block()
            return resultState.filterNotNull().first()
        }
    }


    private fun createFakeViewData(view: String, size: Int): ByteBuffer {
        val result = ByteBuffer.allocate(4 + 2 * view.length + size)
        result.putInt(view.length)
        view.forEach { result.putChar(it) }
        repeat(size) {
            result.put(5)
        }
        result.flip()
        return result
    }

    private fun assertEqualsByteBuffer(expected: ByteBuffer, value: ByteBuffer) {
        Assert.assertEquals(expected.remaining(), value.remaining())
        for(index in 0 until expected.remaining()) {
            Assert.assertEquals("Bytes at offset $index should be equal", expected.get(index), value.get(index))
        }
    }

    class AndroidDebugBridgeListener(private val ddmlibListener: IDeviceChangeListener) : DeviceClientManagerListener {
        override fun processListUpdated(
            bridge: AndroidDebugBridge,
            deviceClientManager: DeviceClientManager
        ) {
            if (bridge === AndroidDebugBridge.getBridge()) {
                ddmlibListener.deviceChanged(
                    deviceClientManager.device, IDevice.CHANGE_CLIENT_LIST
                )
            }
        }

        override fun profileableProcessListUpdated(
            bridge: AndroidDebugBridge,
            deviceClientManager: DeviceClientManager
        ) {
            if (bridge === AndroidDebugBridge.getBridge()) {
                ddmlibListener.deviceChanged(
                    deviceClientManager.device, IDevice.CHANGE_PROFILEABLE_CLIENT_LIST
                )
            }
        }

        override fun processNameUpdated(
            bridge: AndroidDebugBridge,
            deviceClientManager: DeviceClientManager,
            client: Client
        ) {
            throw NotImplementedError("processNameUpdated")
        }

        override fun processDebuggerStatusUpdated(
            bridge: AndroidDebugBridge,
            deviceClientManager: DeviceClientManager,
            client: Client
        ) {
            throw NotImplementedError("processDebuggerStatusUpdated")
        }

        override fun processHeapAllocationsUpdated(
            bridge: AndroidDebugBridge,
            deviceClientManager: DeviceClientManager,
            client: Client
        ) {
            throw NotImplementedError("processHeapAllocationsUpdated")
        }

        override fun processMethodProfilingStatusUpdated(
            bridge: AndroidDebugBridge,
            deviceClientManager: DeviceClientManager,
            client: Client
        ) {
            throw NotImplementedError("processMethodProfilingStatusUpdated")
        }
    }
}
