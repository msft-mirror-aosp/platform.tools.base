package com.android.adblib.ddmlibcompatibility

import com.android.adblib.ddmlibcompatibility.AdbLibIDeviceManagerTest.TestIDeviceManagerListener.EventType
import com.android.adblib.ddmlibcompatibility.testutils.InitAndroidDebugBridgeRule
import com.android.adblib.ddmlibcompatibility.testutils.UseAdbLibAndroidDebugBridgeRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.annotations.concurrency.WorkerThread
import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.ddmlib.idevicemanager.IDeviceManagerListener
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.DeviceState.HostConnectionType
import com.android.sdklib.AndroidApiLevel
import kotlin.math.max
import kotlinx.coroutines.delay
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class AdbLibIDeviceManagerTest {

  private val fakeAdbRule = FakeAdbServerProviderRule()
  private val fakeAdb
    get() = fakeAdbRule.fakeAdb

  private val useAdbLibAndroidDebugBridgeRule = UseAdbLibAndroidDebugBridgeRule { fakeAdbRule.adbSession }
  private val initAndroidDebugBridgeRule = InitAndroidDebugBridgeRule { fakeAdbRule.fakeAdb.port }

  @get:Rule val ruleChain = RuleChain.outerRule(fakeAdbRule).around(useAdbLibAndroidDebugBridgeRule).around(initAndroidDebugBridgeRule)!!

  private lateinit var bridge: AndroidDebugBridge

  @Before
  fun setUp() {
    bridge = AndroidDebugBridge.createBridge() ?: error("Couldn't create a bridge")
  }

  @Test
  fun hasInitialDeviceList_returnsFalse_whenAdbCannotBeQueried() = runBlockingWithTimeout {
    // Prepare
    fakeAdbRule.fakeAdb.stop()
    val deviceManager = AdbLibIDeviceManager(fakeAdbRule.adbSession, bridge, TestIDeviceManagerListener())

    // Act: Wait a little to give AdbLibIDeviceManager a chance to query devices
    delay(100)

    // Assert
    assertFalse(deviceManager.hasInitialDeviceList())
  }

  @Test
  fun hasInitialDeviceList_returnsTrue_whenSuccessfullyQueryingEmptyListOfDevices() = runBlockingWithTimeout {
    // Prepare
    val deviceManager = AdbLibIDeviceManager(fakeAdbRule.adbSession, bridge, TestIDeviceManagerListener())

    // Act / Assert
    yieldUntil { deviceManager.hasInitialDeviceList() }
    assertTrue(deviceManager.devices.isEmpty())
  }

  @Test
  fun hasInitialDeviceList_true_impliesDeviceListIsPopulated() = runBlockingWithTimeout {
    // Prepare
    fakeAdbRule.fakeAdb.connectDevice("1234", "Google", "Pixel 9", "Baklava", AndroidApiLevel(36), HostConnectionType.USB)
    val deviceManager = AdbLibIDeviceManager(fakeAdbRule.adbSession, bridge, TestIDeviceManagerListener())

    // Act / Assert
    yieldUntil { deviceManager.hasInitialDeviceList() }
    assertEquals(1, deviceManager.devices.size)
  }

  @Test
  fun getDevices() = runBlockingWithTimeout {
    // Prepare
    val deviceManager = AdbLibIDeviceManager(fakeAdbRule.adbSession, bridge, TestIDeviceManagerListener())
    val fakeDevice =
      fakeAdb.connectDevice("dev1234", "test1", "test2", "model", sdk = AndroidApiLevel(23), DeviceState.HostConnectionType.USB)
    fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

    // Act / Assert
    yieldUntil { deviceManager.devices.size == 1 }
    assertEquals("dev1234", deviceManager.devices[0].serialNumber)
  }

  @Test
  fun tracksDeviceStateChanges() = runBlockingWithTimeout {
    // Prepare
    val iDeviceManagerListener = TestIDeviceManagerListener()
    val fakeDevice =
      fakeAdb.connectDevice("dev1234", "test1", "test2", "model", sdk = AndroidApiLevel(23), DeviceState.HostConnectionType.USB)
    fakeDevice.deviceStatus = DeviceState.DeviceStatus.BOOTLOADER

    // Act / Assert
    val deviceManager = AdbLibIDeviceManager(fakeAdbRule.adbSession, bridge, iDeviceManagerListener)
    // Wait until receiving `IDeviceManagerListener.addedDevices` event
    yieldUntil { iDeviceManagerListener.events.size == 1 }
    assertEquals(1, deviceManager.devices.size)
    assertFalse(deviceManager.devices[0].isOnline)
    assertArrayEquals(arrayOf(TestIDeviceManagerListener.EventType.Added), iDeviceManagerListener.events.toTypedArray())

    fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE
    // Wait until also receiving `IDeviceManagerListener.deviceStateChanged` event
    yieldUntil { iDeviceManagerListener.events.size == 2 }
    assertTrue(deviceManager.devices[0].isOnline)
    assertArrayEquals(
      arrayOf(TestIDeviceManagerListener.EventType.Added, TestIDeviceManagerListener.EventType.StateChanged),
      iDeviceManagerListener.events.toTypedArray(),
    )
    assertArrayEquals(arrayOf(IDevice.DeviceState.BOOTLOADER), iDeviceManagerListener.addedDevicesStateValues.toTypedArray())
    assertArrayEquals(arrayOf(IDevice.DeviceState.ONLINE), iDeviceManagerListener.deviceStateChangedValues.toTypedArray())

    // Wait a little longer to ensure `IDeviceManagerListener` doesn't get any additional unexpected
    // events
    delay(200)
    assertEquals(2, iDeviceManagerListener.events.size)
  }

  @Test
  fun removingOnlineDevice_doesNotTriggerDeviceChangeEventForDisconnectedValue() = runBlockingWithTimeout {
    // Prepare
    val deviceId = "dev1234"
    val iDeviceManagerListener = TestIDeviceManagerListener()
    val fakeDevice =
      fakeAdb.connectDevice(deviceId, "test1", "test2", "model", sdk = AndroidApiLevel(23), DeviceState.HostConnectionType.USB)
    fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

    // Act / Assert
    val deviceManager = AdbLibIDeviceManager(fakeAdbRule.adbSession, bridge, iDeviceManagerListener)
    // Wait until receiving `IDeviceManagerListener.addedDevices` event
    yieldUntil { iDeviceManagerListener.events.size == 1 }
    assertArrayEquals(arrayOf(TestIDeviceManagerListener.EventType.Added), iDeviceManagerListener.events.toTypedArray())
    assertEquals(1, deviceManager.devices.size)
    val device = deviceManager.devices[0]
    assertTrue(device.isOnline)

    fakeAdb.disconnectDevice(deviceId)
    // Wait until also receiving `IDeviceManagerListener.removedDevices` event
    yieldUntil { iDeviceManagerListener.events.size == 2 }
    assertArrayEquals(
      arrayOf(TestIDeviceManagerListener.EventType.Added, TestIDeviceManagerListener.EventType.Removed),
      iDeviceManagerListener.events.toTypedArray(),
    )
    assertArrayEquals(arrayOf(IDevice.DeviceState.DISCONNECTED), iDeviceManagerListener.removedDevicesStateValues.toTypedArray())
    assertTrue(deviceManager.devices.isEmpty())

    // Wait a little longer to ensure that the `DISCONNECTED` deviceStateChanged event
    // wasn't triggered, and neither did any other events.
    delay(200)
    assertEquals(2, iDeviceManagerListener.events.size)
  }

  @Test
  fun testStateChangeUpdatesAreSerialized() = runBlockingWithTimeout {
    // Prepare
    val iDeviceManagerListener =
      object : IDeviceManagerListener {
        val lock = Any()

        @Volatile var totalCalls = 0

        @Volatile var concurrentCalls = 0

        @Volatile var maxConcurrentCalls = 0

        @WorkerThread override fun addedDevices(deviceList: MutableList<IDevice>) {}

        @WorkerThread override fun removedDevices(deviceList: MutableList<IDevice>) {}

        @WorkerThread
        override fun deviceStateChanged(device: IDevice) {
          Thread.sleep(1)
          synchronized(lock) {
            totalCalls++
            concurrentCalls++
            maxConcurrentCalls = max(concurrentCalls, maxConcurrentCalls)
          }
          Thread.sleep(1)
          synchronized(lock) { concurrentCalls-- }
        }
      }
    val deviceManager = AdbLibIDeviceManager(fakeAdbRule.adbSession, bridge, iDeviceManagerListener)
    val fakeDevice1 =
      fakeAdb.connectDevice("dev1234", "test1", "test2", "model", sdk = AndroidApiLevel(23), DeviceState.HostConnectionType.USB)
    val fakeDevice2 =
      fakeAdb.connectDevice("dev87878", "test1", "test2", "model", sdk = AndroidApiLevel(23), DeviceState.HostConnectionType.USB)

    // Act
    yieldUntil { deviceManager.devices.size == 2 }
    while (iDeviceManagerListener.totalCalls < 100) {
      fakeDevice1.deviceStatus = DeviceState.DeviceStatus.ONLINE
      fakeDevice2.deviceStatus = DeviceState.DeviceStatus.ONLINE
      delay(5)
      fakeDevice1.deviceStatus = DeviceState.DeviceStatus.OFFLINE
      fakeDevice2.deviceStatus = DeviceState.DeviceStatus.OFFLINE
      delay(5)
    }

    // Assert
    assertEquals(
      "There were more than one concurrent call to the listener, meaning calls were not serialized as expected",
      1,
      iDeviceManagerListener.maxConcurrentCalls,
    )
  }

  @Test
  fun testAndroidDebugBridgeRemovedEventIsTriggered_onShutdown() = runBlockingWithTimeout {
    // Prepare
    val iDeviceManagerListener = TestIDeviceManagerListener()
    val deviceManager = AdbLibIDeviceManager(fakeAdbRule.adbSession, bridge, iDeviceManagerListener)
    val fakeDevice =
      fakeAdb.connectDevice("dev1234", "test1", "test2", "model", sdk = AndroidApiLevel(23), DeviceState.HostConnectionType.USB)
    fakeDevice.deviceStatus = DeviceState.DeviceStatus.ONLINE

    // Act / Assert
    yieldUntil { deviceManager.devices.size == 1 }
    delay(50)
    assertEquals("dev1234", deviceManager.devices[0].serialNumber)
    assertArrayEquals(arrayOf(EventType.Added), iDeviceManagerListener.events.toTypedArray())

    // Act / Assert
    deviceManager.shutdown()
    assertTrue(deviceManager.devices.isEmpty())
    assertArrayEquals(arrayOf(EventType.Added, EventType.Removed), iDeviceManagerListener.events.toTypedArray())
  }

  private class TestIDeviceManagerListener : IDeviceManagerListener {

    enum class EventType {
      Added,
      Removed,
      StateChanged,
    }

    val events = mutableListOf<EventType>()

    val addedDevicesStateValues = mutableListOf<IDevice.DeviceState?>()
    val removedDevicesStateValues = mutableListOf<IDevice.DeviceState?>()
    val deviceStateChangedValues = mutableListOf<IDevice.DeviceState?>()

    @WorkerThread
    override fun addedDevices(deviceList: MutableList<IDevice>) {
      deviceList.forEach { addedDevicesStateValues.add(it.state) }
      events.add(EventType.Added)
    }

    @WorkerThread
    override fun removedDevices(deviceList: MutableList<IDevice>) {
      deviceList.forEach { removedDevicesStateValues.add(it.state) }
      events.add(EventType.Removed)
    }

    @WorkerThread
    override fun deviceStateChanged(device: IDevice) {
      deviceStateChangedValues.add(device.state)
      events.add(EventType.StateChanged)
    }
  }
}
