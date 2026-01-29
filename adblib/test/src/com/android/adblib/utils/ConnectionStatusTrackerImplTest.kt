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
ations under the License.
 */
package com.android.adblib.utils

import com.android.adblib.connectionStatusTracker
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProviderRule
import com.android.fakeadbserver.DeviceState
import com.android.sdklib.AndroidApiLevel
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionStatusTrackerImplTest {

  @JvmField @Rule val fakeAdbRule = FakeAdbServerProviderRule()

  private val fakeAdb
    get() = fakeAdbRule.fakeAdb

  private val session
    get() = fakeAdbRule.adbSession

  @Test
  fun trackingStartsLazily() = runBlockingWithTimeout {
    // Prepare
    val tracker = session.connectionStatusTracker

    // Act
    // Tracking starts lazily and so accessing `.value` for the first time
    // will return StateFlow's initial value
    delay(100)
    val initialStatus = tracker.connectionStatus.value

    // Assert
    assertFalse(initialStatus.isConnected)
    assertEquals(0, initialStatus.connectionId)
  }

  @Test
  fun collectingStartsTracking() = runBlockingWithTimeout {
    // Prepare
    val tracker = session.connectionStatusTracker

    // Act
    val status = tracker.connectionStatus.first { it.isConnected }

    // Assert
    assertTrue(status.isConnected)
    assertEquals(1, status.connectionId)
  }

  @Test
  fun statusIsDisconnectedWhenAdbStops() = runBlockingWithTimeout {
    // Prepare
    val tracker = session.connectionStatusTracker
    val initialStatus = tracker.connectionStatus.first { it.isConnected }
    assertTrue(initialStatus.isConnected)
    assertEquals(1, initialStatus.connectionId)

    // Act
    fakeAdb.stop()
    yieldUntil { !tracker.connectionStatus.value.isConnected }
    val finalStatus = tracker.connectionStatus.value

    // Assert
    assertFalse(finalStatus.isConnected)
    assertEquals(2, finalStatus.connectionId)
  }

  @Test
  fun closingSessionEndsStateFlow() = runBlockingWithTimeout {
    // Prepare
    val tracker = session.connectionStatusTracker
    val initialStatus = tracker.connectionStatus.first { it.isConnected }
    assertTrue(initialStatus.isConnected)
    assertEquals(1, initialStatus.connectionId)

    // Act
    session.close()
    yieldUntil { !tracker.connectionStatus.value.isConnected }
    val finalStatus = tracker.connectionStatus.value

    // Assert
    assertFalse(finalStatus.isConnected)
    assertEquals(2, finalStatus.connectionId)
  }

  @Test
  fun addingDeviceDoesNotEmitNewValue() = runBlockingWithTimeout {
    // Prepare
    val tracker = session.connectionStatusTracker
    var collectionCount = 0
    val job = launch { tracker.connectionStatus.collect { collectionCount++ } }

    // Wait for initial connection
    yieldUntil { tracker.connectionStatus.value.isConnected }
    val collectionCountBeforeDeviceAdd = collectionCount

    // Act
    fakeAdb.connectDevice("1234", "test1", "test2", "model", AndroidApiLevel(30), DeviceState.HostConnectionType.USB)
    // Wait a bit to ensure no new collection happens
    delay(100)

    // Assert
    assertTrue(tracker.connectionStatus.value.isConnected)
    assertEquals(collectionCountBeforeDeviceAdd, collectionCount)

    job.cancel()
  }
}
