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
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/** Unit tests for StudioLeakCanaryManager. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class StudioLeakCanaryManagerTest {

    private StudioLeakCanaryManager manager;
    private Application mockApplication;

    @Before
    public void setUp() throws Exception {
        mockApplication = mock(Application.class);

        // Reset the singleton instance before each test
        Field instanceField = StudioLeakCanaryManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        manager = StudioLeakCanaryManager.getInstance();
    }

    /**
     * Verifies that the manager starts in an uninitialized state and correctly transitions to the
     * default ON_HOST mode when initialized with a valid context.
     */
    @Test
    public void testInitializationSetsDefaultModeToOnHost() {
        assertThat(manager.getCurrentMode()).isEqualTo(-1); // Uninitialized

        Context mockContext = mock(Context.class);
        when(mockContext.getApplicationContext()).thenReturn(mockApplication);

        manager.initialize(mockContext);

        // Default mode is ON_HOST
        assertThat(manager.getCurrentMode()).isEqualTo(StudioLeakCanaryManager.MODE_ON_HOST);
    }

    /**
     * Verifies that the manager correctly extracts and stores the Application instance from the
     * provided Context during initialization.
     */
    @Test
    public void testInitializationResolvesApplicationContext() throws Exception {
        Context mockContext = mock(Context.class);
        when(mockContext.getApplicationContext()).thenReturn(mockApplication);

        manager.initialize(mockContext);

        Field appField = StudioLeakCanaryManager.class.getDeclaredField("application");
        appField.setAccessible(true);
        assertThat(appField.get(manager)).isSameInstanceAs(mockApplication);
    }

    /**
     * Verifies that if the manager is initialized with a context that does not resolve to an
     * Application instance, it handles it gracefully by leaving the internal application field null
     * instead of throwing an exception.
     */
    @Test
    public void testInitializationFailsToResolveNonApplicationContext() throws Exception {
        Context mockContext = mock(Context.class);
        // Returns a plain Context mock, which is NOT an instance of Application
        when(mockContext.getApplicationContext()).thenReturn(mock(Context.class));

        manager.initialize(mockContext);

        Field appField = StudioLeakCanaryManager.class.getDeclaredField("application");
        appField.setAccessible(true);
        assertThat(appField.get(manager)).isNull();
    }

    /** Verifies the state transition from ON_HOST to ON_DEVICE mode. */
    @Test
    public void testSetModeTransitionsToOnDevice() {
        manager.initialize(mockApplication);

        // Initial state is ON_HOST
        assertThat(manager.getCurrentMode()).isEqualTo(StudioLeakCanaryManager.MODE_ON_HOST);

        // Transition to ON_DEVICE
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);

        assertThat(manager.getCurrentMode()).isEqualTo(StudioLeakCanaryManager.MODE_ON_DEVICE);
    }
}
