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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Unit tests for StudioLeakCanaryEventListener. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class StudioLeakCanaryEventListenerTest {

    /** Without LeakCanary, the event listener proxy is not created. */
    @Test
    public void testCreateInstanceReturnsNullWhenLeakCanaryMissing() {
        assertThat(StudioLeakCanaryEventListener.createInstance(mock(Application.class))).isNull();
    }
}
