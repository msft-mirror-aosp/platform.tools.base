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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Utility class for interacting with Android emulators during tests.
 *
 * <p>This class provides methods to execute Android Debug Bridge (ADB) commands against a specific
 * emulator instance. It abstracts the underlying process execution and handles command output.
 */
public class EmulatorTestUtils {

    /**
     * The path to the prebuilt Android Debug Bridge (ADB) executable. The path is determined
     * dynamically based on the host operating system.
     */
    private static final String ADB =
            String.format("prebuilts/studio/sdk/%s/platform-tools/adb", getOsDirName());

    /**
     * Executes an ADB command against a specific emulator instance.
     *
     * @param port The port number of the target emulator (e.g., 5554).
     * @param cmd The ADB command and its arguments to execute (e.g., "shell", "getprop",
     *     "ro.boot.completed").
     * @return The standard output from the executed command, with leading/trailing whitespace
     *     removed.
     * @throws Exception if the command fails to execute or returns a non-zero exit code.
     */
    public static String adb(int port, String... cmd) throws Exception {
        String[] adbCommands =
                Stream.concat(Stream.of(ADB, "-s", "emulator-" + port), Arrays.stream(cmd))
                        .toArray(String[]::new);
        return exec(adbCommands);
    }

    /**
     * Executes a system command using {@link ProcessBuilder}.
     *
     * <p>This method creates a new process, captures its standard output, and waits for completion.
     * The process's standard error is merged with its standard output. Output is read on a separate
     * thread to prevent buffer-related deadlocks.
     *
     * @param cmd The command and its arguments to execute.
     * @return The combined standard output and standard error from the command, with
     *     leading/trailing whitespace removed.
     * @throws Exception if the process returns a non-zero exit code, containing the exit code and
     *     the captured output.
     */
    private static String exec(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder().command(cmd);
        pb.redirectErrorStream(true); // Merge stdout and stderr into a single stream.

        StringBuilder returnValue = new StringBuilder();

        Process process = pb.start();

        // A dedicated thread reads the process's output stream.
        // This prevents the process buffer from filling up, which could cause a deadlock.
        Thread readerThread =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(process.getInputStream()))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    returnValue.append(line).append("\n");
                                }
                            } catch (IOException e) {
                                // This can happen if the process is terminated abruptly. It's
                                // generally
                                // safe to ignore as the process is ending anyway.
                            }
                        });

        readerThread.start();

        try {
            // Wait for the external process to complete.
            int exitCode = process.waitFor();

            // Wait for the reader thread to complete.
            readerThread.join();

            if (exitCode != 0) {
                throw new Exception(
                        String.format(
                                "%s exited with code: %d, output = %s",
                                String.join(" ", cmd), exitCode, returnValue.toString().trim()));
            }
        } finally {
            // Ensure the reader thread is cleaned up if it's still running.
            readerThread.interrupt();
        }

        return returnValue.toString().trim();
    }

    /**
     * Determines the appropriate directory name for the SDK path based on the host operating
     * system.
     *
     * @return The OS-specific directory name ("darwin", "windows", or "linux").
     * @throws RuntimeException if the operating system is not Mac, Windows, or Linux.
     */
    private static String getOsDirName() {
        String os = System.getProperty("os.name");
        if (os.startsWith("Mac OS")) {
            return "darwin";
        } else if (os.startsWith("Windows")) {
            return "windows";
        } else if (os.startsWith("Linux")) {
            return "linux";
        } else {
            throw new RuntimeException("Unsupported os.name: " + os);
        }
    }
}
