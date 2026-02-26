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

package com.android.tools.androidtest.testengine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.hierarchical.Node
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AndroidTestEngineDescriptorTest {

  @Test
  fun `execute creates AndroidDevicesContainer`() {
    val uniqueId = UniqueId.forEngine("android-test-engine")
    val descriptor = AndroidTestEngineDescriptor(uniqueId)

    val context = mock<AndroidTestExecutionContext>()
    val configuration = mock<AndroidTestConfiguration>()
    whenever(context.configuration).thenReturn(configuration)
    whenever(configuration.deviceSerials).thenReturn(listOf("serial1", "serial2"))

    val dynamicTestExecutor = mock<Node.DynamicTestExecutor>()

    descriptor.execute(context, dynamicTestExecutor)

    val captor = argumentCaptor<AndroidDevicesContainer>()
    verify(dynamicTestExecutor).execute(captor.capture())

    val container = captor.firstValue
    assertThat(container.displayName).isEqualTo("Devices")
    assertThat(container.parent.get()).isSameInstanceAs(descriptor)
  }
}
