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
package com.android.sdklib;

import static com.android.sdklib.SdkVersionInfo.HIGHEST_KNOWN_API;
import static com.android.sdklib.SdkVersionUtil.getVersion;

import junit.framework.TestCase;

public class SdkVersionUtilTest extends TestCase {
    @SuppressWarnings("ConstantConditions")
    public void testGetAndroidVersion() {
        assertNull(getVersion("", null));
        assertNull(getVersion("4H", null));
        assertNull(getVersion("invalid codename", null));
        assertEquals(4, getVersion("4", null).getApiLevel());
        assertNull(getVersion("4", null).getCodename());
        assertEquals("4", getVersion("4", null).getApiString());
        assertEquals(19, getVersion("19", null).getApiLevel());
        // ICS is API 14, but when expressed as a preview platform, it's not yet 14
        assertEquals(13, getVersion("IceCreamSandwich", null).getApiLevel());
        assertEquals("IceCreamSandwich", getVersion("IceCreamSandwich", null).getCodename());
        assertEquals(HIGHEST_KNOWN_API, getVersion("BackToTheFuture", null).getApiLevel());
        assertEquals("BackToTheFuture", getVersion("BackToTheFuture", null).getCodename());

        assertEquals(37, getVersion("37.0-beta1", null).getApiLevel());
        assertEquals(1, (int) getVersion("37.0-beta1", null).getBetaNumber());
        assertEquals(36, getVersion("canary-20251201", null).getApiLevel());
        assertEquals(20251201, (int) getVersion("canary-20251201", null).getCanaryNumber());
    }
}
