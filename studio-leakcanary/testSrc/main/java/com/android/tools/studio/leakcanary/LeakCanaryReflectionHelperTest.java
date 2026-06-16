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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/**
 * Unit tests for LeakCanaryReflectionHelper.
 *
 * <p>Since the tests run without the actual leakcanary-android library in the classpath, these
 * tests primarily verify the "graceful failure" paths. It ensures that if the app is compiled
 * without LeakCanary, the reflection helper does not throw exceptions and handles state properly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LeakCanaryReflectionHelperTest {

    @Before
    public void setUp() throws Exception {
        resetReflectionState();
    }

    /** Helper to reset the internal static state of LeakCanaryReflectionHelper between tests. */
    private void resetReflectionState() throws Exception {
        Field stateField = LeakCanaryReflectionHelper.class.getDeclaredField("state");
        stateField.setAccessible(true);
        stateField.set(null, LeakCanaryReflectionHelper.ReflectionState.UNINITIALIZED);
    }

    /**
     * Verifies that when LeakCanary classes are not present in the classpath,
     * LeakCanaryReflectionHelper#ensureInitialized() safely catches the ClassNotFoundException and
     * sets the state to FAILED, without crashing.
     */
    @Test
    public void testEnsureInitializedFailsGracefully() throws Exception {
        // This should not throw any exception
        LeakCanaryReflectionHelper.ensureInitialized();

        // Verify that internal state is marked as FAILED
        Field stateField = LeakCanaryReflectionHelper.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object state = stateField.get(null);

        assertThat(state).isEqualTo(LeakCanaryReflectionHelper.ReflectionState.FAILED);
    }

    /**
     * Verifies that modifying the dump heap configuration fails gracefully without throwing
     * exceptions when LeakCanary is missing.
     */
    @Test
    public void testSetDumpHeapEnabledFailsGracefully() {
        // Should not throw
        LeakCanaryReflectionHelper.setDumpHeapEnabled(true);
    }

    /**
     * Verifies that swapping the object retained listener returns false and doesn't crash when
     * LeakCanary is missing.
     */
    @Test
    public void testSwapOnObjectRetainedListenerFailsGracefully() {
        // Should return false and not throw
        boolean result =
                LeakCanaryReflectionHelper.swapOnObjectRetainedListener(new Object(), true);
        assertThat(result).isFalse();
    }

    /** Verifies that querying the retained object count returns 0 when LeakCanary is missing. */
    @Test
    public void testGetRetainedObjectCountFailsGracefully() {
        int count = LeakCanaryReflectionHelper.getRetainedObjectCount();
        assertThat(count).isEqualTo(0);
    }

    /**
     * Verifies that getting the threshold returns the REFLECTION_FAILED_THRESHOLD when LeakCanary
     * is missing, to properly notify the profiler backend.
     */
    @Test
    public void testGetRetainedVisibleThresholdFailsGracefully() {
        int threshold = LeakCanaryReflectionHelper.getRetainedVisibleThreshold();
        assertThat(threshold).isEqualTo(HelperConfig.REFLECTION_FAILED_THRESHOLD);
    }
}
