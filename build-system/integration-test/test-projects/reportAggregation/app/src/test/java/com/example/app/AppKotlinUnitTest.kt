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

package com.example.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.SyncResult
import android.content.SyncStats
import android.os.AsyncTask
import android.os.Debug
import android.os.PowerManager
import android.util.ArrayMap
import org.apache.commons.logging.LogFactory
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.nio.charset.StandardCharsets

class AppKotlinUnitTest {
    @Test
    fun referenceProductionCode() {
        assertEquals("AppJavaClass", AppJavaClass().name)
    }

    @Test
    fun referenceProductionKotlinCode() {
        assertEquals("AppKotlinClass", AppKotlinClass().name)
    }

    @Test
    fun testCalculate() {
        assertEquals(4, AppKotlinClass().calculate(2, 2, "add"))
    }

    @Test
    fun mockFinalMethod() {
        val activity = mock(Activity::class.java)
        val app = mock(Application::class.java)
        `when`(activity.application).thenReturn(app)

        assertSame(app, activity.application)

        verify(activity).application
        verifyNoMoreInteractions(activity)
    }
}
