/*
 * Copyright (C) 2025 The Android Open Source Project
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

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A test to verify that multiple emulator instances can be launched and managed concurrently. This
 * serves as a regression test for the concurrency-safe {@code emulator_launcher.sh} script.
 */
public class MultipleEmulatorTest {
    /**
     * Paths to the executable that the avd rule generates.
     *
     * <p>The executable is the script that starts and stops emulators and must be used to launch
     * the emulator.
     */
    private static final String[] DEVICES =
            new String[] {
                "tools/base/bazel/avd/default_avd",
                "tools/base/bazel/avd/default_avd",
                "tools/base/bazel/avd/avd_34"
            };

    private static final int[] DEVICE_API_LEVELS = new int[] {33, 33, 34};

    /**
     * Port at which to open the emulator.
     *
     * <p>On RBE, bazel launches the emulator in a sandbox, so you can use any port you want. If you
     * launch multiple emulators from the same test, then use different ports for each of those
     * emulators.
     */
    private static final int PORT = 5554;

    @Before
    public void createAndroidUserHomeDirectory() {
        // Workaround for concurrency issue in adb b/461509099
        File androidUserHome = new File(System.getenv("TEST_TMPDIR"), ".android");
        if (!androidUserHome.exists()) {
            System.out.println("Creating " + androidUserHome.getAbsolutePath());
            androidUserHome.mkdirs();
        }
        assertTrue(
                androidUserHome.getAbsolutePath() + " does not exist.", androidUserHome.exists());
        assertTrue(
                androidUserHome.getAbsolutePath() + " exists but is not a directory.",
                androidUserHome.isDirectory());
    }

    /**
     * Launches several emulators in parallel to test for race conditions in the launcher script.
     *
     * <p>This test uses a thread pool to concurrently start and stop multiple emulator instances
     * in a loop. It passes if all operations complete without exceptions, ensuring that the
     * underlying script handles concurrent setup and teardown correctly.
     *
     * @throws Exception if any of the underlying emulator operations fail.
     */
    @Test
    public void bootMultipleEmulators() throws Exception {
        final int concurrentAvdNum = 3;
        final int repeatStartAndStop = 5;

        ExecutorService executor = Executors.newFixedThreadPool(concurrentAvdNum);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentAvdNum; i++) {
                final int workerIdx = i;
                final int portNumber = PORT + 2 * i;
                final String device = DEVICES[i];
                final int deviceApiLevel = DEVICE_API_LEVELS[i];
                futures.add(
                        executor.submit(
                                () -> {
                                    for (int j = 0; j < repeatStartAndStop; j++) {
                                        String prefix =
                                                "[worker="
                                                        + workerIdx
                                                        + ", "
                                                        + (j + 1)
                                                        + "/"
                                                        + repeatStartAndStop
                                                        + "] "
                                                        + "(port="
                                                        + portNumber
                                                        + ", API="
                                                        + deviceApiLevel
                                                        + "): ";
                                        Emulator emulator = new Emulator(device, portNumber);
                                        try {
                                            System.out.println(prefix + "Starting Emulator");

                                            emulator.before();

                                            assertEquals(
                                                    prefix + deviceApiLevel,
                                                    prefix
                                                            + EmulatorTestUtils.adb(
                                                                    portNumber,
                                                                    "shell",
                                                                    "getprop",
                                                                    "ro.build.version.sdk"));
                                        } catch (Throwable e) {
                                            throw new RuntimeException(e);
                                        } finally {
                                            System.out.println(prefix + "Killing Emulator");
                                            emulator.after();
                                        }
                                    }
                                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdown();
        }
    }
}
