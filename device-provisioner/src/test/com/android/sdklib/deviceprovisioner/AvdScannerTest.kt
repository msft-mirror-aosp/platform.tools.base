/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.sdklib.deviceprovisioner

import com.android.sdklib.internal.avd.AvdInfo
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AvdScannerTest {

  private val avdRoot = createInMemoryFileSystemAndFolder("avds")

  private class FakeAvdScanner(coroutineScope: CoroutineScope, rescanPeriod: Duration) :
    AbstractAvdScanner(coroutineScope, rescanPeriod = rescanPeriod) {
    var avds = listOf<AvdInfo>()
    val scanCount = MutableStateFlow(0)

    override fun scanAvds(): List<AvdInfo> {
      scanCount.update { it + 1 }
      return avds
    }

    override fun logError(message: String, exception: Throwable) {}
  }

  @Test
  fun rescan() = runTest {
    val scanner = FakeAvdScanner(backgroundScope, rescanPeriod = 10.minutes)
    val avd1 = makeAvdInfo(avdRoot, 1)
    val avd2 = makeAvdInfo(avdRoot, 2)
    scanner.avds = listOf(avd1)

    val flowResults = scanner.avdFlow.stateIn(backgroundScope)

    scanner.scanCount.first { it == 1 }
    flowResults.first { it == listOf(avd1) }

    scanner.avds = listOf(avd1, avd2)
    val scanResult = scanner.rescan()

    assertThat(scanResult).containsExactly(avd1, avd2)
    assertThat(scanner.scanCount.value).isEqualTo(2)
    flowResults.first { it == listOf(avd1, avd2) }
  }

  @Test
  fun rescanWithNoCollectors() = runTest {
    val scanner = FakeAvdScanner(backgroundScope, rescanPeriod = 10.minutes)
    val avd1 = makeAvdInfo(avdRoot, 1)

    scanner.avds = listOf(avd1)

    assertThat(scanner.rescan()).containsExactly(avd1)
  }

  @Test
  fun avdFlow() = runTest {
    val scanner = FakeAvdScanner(backgroundScope, rescanPeriod = 10.milliseconds)
    val avd1 = makeAvdInfo(avdRoot, 1)
    val avd2 = makeAvdInfo(avdRoot, 2)
    val flowResults = scanner.avdFlow.stateIn(backgroundScope)

    assertThat(flowResults.value).isEmpty()
    scanner.avds = listOf(avd1)
    testScheduler.advanceUntilIdle()

    flowResults.first { it == listOf(avd1) }

    scanner.avds = listOf(avd2)
    testScheduler.advanceUntilIdle()

    flowResults.first { it == listOf(avd2) }
  }

  @Test
  fun rescanAsync() = runTest {
    val scanner = FakeAvdScanner(backgroundScope, rescanPeriod = 10.minutes)
    val avd1 = makeAvdInfo(avdRoot, 1)
    val avd2 = makeAvdInfo(avdRoot, 2)
    scanner.avds = listOf(avd1)

    val flowResults = scanner.avdFlow.stateIn(backgroundScope)

    flowResults.first { it == listOf(avd1) }

    // With 10 minute rescan period, this will never update on its own. rescanAsync should cause an update.
    scanner.avds = listOf(avd1, avd2)
    scanner.rescanAsync()

    flowResults.first { it == listOf(avd1, avd2) }
  }
}
