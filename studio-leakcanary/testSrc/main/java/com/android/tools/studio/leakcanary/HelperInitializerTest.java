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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/**
 * Unit tests for HelperInitializer.
 *
 * <p>Verifies that ContentProvider startup successfully bootstraps the library by initializing the
 * manager and registering the 4 core command-handling broadcast receivers with correct SDK-specific
 * permissions and flags.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class HelperInitializerTest {

    private Context mockContext;
    private Application mockApplication;

    @Before
    public void setUp() throws Exception {
        mockContext = mock(Context.class);
        mockApplication = mock(Application.class);
        when(mockContext.getApplicationContext()).thenReturn(mockApplication);
        when(mockContext.getPackageName()).thenReturn("com.test.app");

        // Reset the singleton instance of StudioLeakCanaryManager before each test
        Field instanceField = StudioLeakCanaryManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    /**
     * Verifies that when HelperInitializer.onCreate() is called on API >= TIRAMISU, it: 1.
     * Successfully initializes the StudioLeakCanaryManager to default MODE_ON_HOST. 2. Registers
     * all four broadcast receivers with RECEIVER_NOT_EXPORTED flags.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testOnCreateRegistersReceiversAndInitializesManager_postTiramisu() {
        HelperInitializer initializer = new HelperInitializer();
        injectMockContext(initializer, mockContext);

        boolean result = initializer.onCreate();
        assertThat(result).isTrue();

        // Verify StudioLeakCanaryManager was initialized to MODE_ON_HOST
        assertThat(StudioLeakCanaryManager.getInstance().getCurrentMode())
                .isEqualTo(StudioLeakCanaryManager.MODE_ON_HOST);

        // Verify all 4 receivers were registered with RECEIVER_NOT_EXPORTED
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        ArgumentCaptor<IntentFilter> filterCaptor = ArgumentCaptor.forClass(IntentFilter.class);

        verify(mockContext, atLeastOnce())
                .registerReceiver(
                        receiverCaptor.capture(),
                        filterCaptor.capture(),
                        eq(Context.RECEIVER_NOT_EXPORTED));

        assertThat(receiverCaptor.getAllValues()).hasSize(4);

        // Verify the registered intent actions match the expected ones
        java.util.List<String> registeredActions = new java.util.ArrayList<>();
        for (IntentFilter filter : filterCaptor.getAllValues()) {
            registeredActions.add(filter.getAction(0));
        }

        assertThat(registeredActions).contains(HelperConfig.HEAP_DUMP_COMPLETE_INTENT);
        assertThat(registeredActions).contains(HelperConfig.START_LISTENING_INTENT);
        assertThat(registeredActions).contains(HelperConfig.GET_THRESHOLD_INTENT);
        assertThat(registeredActions).contains(HelperConfig.FORCE_DUMP_ON_DEVICE_INTENT);
    }

    /**
     * Verifies that when HelperInitializer.onCreate() is called on API < TIRAMISU, it: 1.
     * Successfully initializes the StudioLeakCanaryManager to default MODE_ON_HOST. 2. Registers
     * all four broadcast receivers with the app-specific internal permission.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.R)
    public void testOnCreateRegistersReceiversAndInitializesManager_preTiramisu() {
        HelperInitializer initializer = new HelperInitializer();
        injectMockContext(initializer, mockContext);

        boolean result = initializer.onCreate();
        assertThat(result).isTrue();

        // Verify StudioLeakCanaryManager was initialized to MODE_ON_HOST
        assertThat(StudioLeakCanaryManager.getInstance().getCurrentMode())
                .isEqualTo(StudioLeakCanaryManager.MODE_ON_HOST);

        // Verify all 4 receivers were registered with the app internal permission
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        ArgumentCaptor<IntentFilter> filterCaptor = ArgumentCaptor.forClass(IntentFilter.class);

        String expectedPermission = "com.test.app.permission.LEAK_CANARY_INTERNAL";

        verify(mockContext, atLeastOnce())
                .registerReceiver(
                        receiverCaptor.capture(),
                        filterCaptor.capture(),
                        eq(expectedPermission),
                        eq(null));

        assertThat(receiverCaptor.getAllValues()).hasSize(4);

        java.util.List<String> registeredActions = new java.util.ArrayList<>();
        for (IntentFilter filter : filterCaptor.getAllValues()) {
            registeredActions.add(filter.getAction(0));
        }

        assertThat(registeredActions).contains(HelperConfig.HEAP_DUMP_COMPLETE_INTENT);
        assertThat(registeredActions).contains(HelperConfig.START_LISTENING_INTENT);
        assertThat(registeredActions).contains(HelperConfig.GET_THRESHOLD_INTENT);
        assertThat(registeredActions).contains(HelperConfig.FORCE_DUMP_ON_DEVICE_INTENT);
    }

    /** Injects the mock Context into the private mContext field of ContentProvider. */
    private void injectMockContext(HelperInitializer initializer, Context context) {
        try {
            Field contextField = ContentProvider.class.getDeclaredField("mContext");
            contextField.setAccessible(true);
            contextField.set(initializer, context);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mock context into ContentProvider", e);
        }
    }
}
