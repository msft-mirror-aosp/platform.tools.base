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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.android.tools.bazel.avd.Emulator;
import org.junit.Test;

/**
 * A test to verify that multiple emulator instances can be launched and managed concurrently. This
 * serves as a regression test for the concurrency-safe {@code emulator_launcher.sh} script.
 */
public class MultipleEmulatorTest {
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
        final int repeatStartAndStop = 10;

        ExecutorService executor = Executors.newFixedThreadPool(concurrentAvdNum);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentAvdNum; i++) {
                final int workerIdx = i;
                final int portNumber = PORT + 2 * i;
                futures.add(executor.submit(() -> {
                    for (int j = 0; j < repeatStartAndStop; j++) {
                        Emulator emulator = new Emulator(DEVICE, portNumber);
                        try {
                            System.out.println(
                                "[" + workerIdx +"] Starting Emulator " +
                                (j + 1) + "/" + repeatStartAndStop);
                            emulator.before();
                        } catch (Throwable e) {
                            throw new RuntimeException(e);
                        } finally {
                            System.out.println(
                                "[" + workerIdx + "] Killing Emulator " +
                                (j + 1) + "/" + repeatStartAndStop);
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
