/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tools.bazel.avd.Emulator;

import org.junit.ClassRule;
import org.junit.Test;

public class EmulatorTest {

    /**
     * Path to the executable that the avd rule generates.
     *
     * <p>The executable is the script that starts and stops emulators and must be used to launch
     * the emulator.
     */
    private static final String DEVICE = "tools/base/bazel/avd/default_avd";

    /**
     * Port at which to open the emulator.
     *
     * <p>On RBE, bazel launches the emulator in a sandbox, so you can use any port you want. If you
     * launch multiple emulators from the same test, then use different ports for each of those
     * emulators.
     */
    private static final int PORT = 5554;

    @ClassRule public static final Emulator emulator = new Emulator(DEVICE, PORT);

    @Test
    public void sampleTestRequiringEmulator() throws Exception {
        // Sample assertions against the running emulator.
        // Note: This test does not have access to ddmlib, so it resorts to
        // issuing ADB calls.
        assertTrue(EmulatorTestUtils.adb(PORT, "devices").contains("emulator-" + PORT));
    }

    @Test
    public void sampleTestForEmulatorBoot() throws Exception {
        assertEquals(EmulatorTestUtils.adb(PORT, "shell", "getprop sys.boot_completed"), "1");
        assertEquals(EmulatorTestUtils.adb(PORT, "shell", "pwd"), "/");
    }
}
