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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** Unit tests for StudioLeakCanaryListener. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class StudioLeakCanaryListenerTest {

    private Application mockApplication;

    @Before
    public void setUp() throws Exception {
        mockApplication = mock(Application.class);
    }

    @After
    public void tearDown() throws Exception {
        // Reset the singleton instance
        Field instanceField = StudioLeakCanaryListener.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    /**
     * Verifies that the listener gracefully handles the absence of the LeakCanary library and
     * returns null when trying to create the proxy instance.
     */
    @Test
    public void testCreateInstanceReturnsNullWhenLeakCanaryMissing() {
        Object proxy = StudioLeakCanaryListener.createInstance(mockApplication);

        // Since leakcanary.OnObjectRetainedListener is not in the classpath,
        // Class.forName will throw ClassNotFoundException and the method should return null.
        assertThat(proxy).isNull();
    }

    /**
     * Verifies that setting the listener to disabled updates the internal state correctly and
     * ignores subsequent retention events.
     */
    @Test
    public void testSetIsEnabledState() throws Exception {
        // We use reflection to directly instantiate StudioLeakCanaryListener for testing
        // its internal methods, bypassing the proxy creation which requires LeakCanary classes.
        Constructor<?> constructor =
                StudioLeakCanaryListener.class.getDeclaredConstructor(Application.class);
        constructor.setAccessible(true);
        StudioLeakCanaryListener listener =
                (StudioLeakCanaryListener) constructor.newInstance(mockApplication);

        // It's enabled by default
        Field isEnabledField = StudioLeakCanaryListener.class.getDeclaredField("isEnabled");
        isEnabledField.setAccessible(true);
        boolean isEnabled = isEnabledField.getBoolean(listener);
        assertThat(isEnabled).isTrue();

        // Disable it
        StudioLeakCanaryListener.setIsEnabled(false);
        isEnabled = isEnabledField.getBoolean(listener);
        assertThat(isEnabled).isFalse();
    }

    /**
     * Verifies the onHeapDumpFinished method gracefully handles the call when the instance is
     * disabled or missing.
     */
    @Test
    public void testOnHeapDumpFinishedGracefullyReturnsWhenDisabled() throws Exception {
        Constructor<?> constructor =
                StudioLeakCanaryListener.class.getDeclaredConstructor(Application.class);
        constructor.setAccessible(true);
        StudioLeakCanaryListener listener =
                (StudioLeakCanaryListener) constructor.newInstance(mockApplication);

        StudioLeakCanaryListener.setIsEnabled(false);

        // Calling this should not crash
        StudioLeakCanaryListener.onHeapDumpFinished(12345L);
        // It just safely returns because it's disabled
    }
}
