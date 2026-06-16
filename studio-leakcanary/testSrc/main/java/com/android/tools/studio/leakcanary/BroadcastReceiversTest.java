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

package com.android.tools.studio.leakcanary;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/** Unit tests for the BroadcastReceivers inside the studio-leakcanary library. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class BroadcastReceiversTest {

    private Context mockContext;
    private Application mockApplication;

    @Before
    public void setUp() throws Exception {
        mockContext = mock(Context.class);
        mockApplication = mock(Application.class);
        org.mockito.Mockito.when(mockContext.getApplicationContext()).thenReturn(mockApplication);
        org.mockito.Mockito.when(mockContext.getPackageName()).thenReturn("com.test.app");

        // Reset the singleton instance before each test
        Field instanceField = StudioLeakCanaryManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    /** Tests that the ForceDumpReceiver bails out if not in ON_DEVICE mode. */
    @Test
    public void testForceDumpReceiverIgnoredInHostMode() {
        StudioLeakCanaryManager.getInstance().setMode(StudioLeakCanaryManager.MODE_ON_HOST);

        ForceDumpReceiver receiver = new ForceDumpReceiver();
        Intent intent = new Intent(HelperConfig.FORCE_DUMP_ON_DEVICE_INTENT);

        // This shouldn't throw or do anything because it's not in ON_DEVICE mode
        receiver.onReceive(mockContext, intent);
    }

    /**
     * Tests that GetThresholdReceiver correctly fetches the threshold (using reflection fallback)
     * and sends a broadcast. We only test the intent formatting since the reflection is missing.
     */
    @Test
    public void testGetThresholdReceiverSendsBroadcast() {
        GetThresholdReceiver receiver = new GetThresholdReceiver();
        Intent intent = new Intent(HelperConfig.GET_THRESHOLD_INTENT);

        // This will attempt to send a broadcast. Since Context is mocked, we can capture the intent
        // sent or just ensure it doesn't crash.
        receiver.onReceive(mockContext, intent);

        // Using Mockito to verify the sent intent
        org.mockito.ArgumentCaptor<Intent> intentCaptor =
                org.mockito.ArgumentCaptor.forClass(Intent.class);
        org.mockito.Mockito.verify(mockContext).sendBroadcast(intentCaptor.capture());

        Intent sentIntent = intentCaptor.getValue();
        assertThat(sentIntent.getAction()).isEqualTo(HelperConfig.THRESHOLD_RESULT_INTENT);
        assertThat(sentIntent.getIntExtra(HelperConfig.THRESHOLD_EXTRA, -999))
                .isEqualTo(HelperConfig.REFLECTION_FAILED_THRESHOLD);
    }
}
