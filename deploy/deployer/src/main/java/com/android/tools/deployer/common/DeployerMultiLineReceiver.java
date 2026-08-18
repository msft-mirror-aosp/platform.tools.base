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
package com.android.tools.deployer.common;

import com.android.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Base implementation of {@link DeployerIShellOutputReceiver}, that takes the raw data coming from
 * the socket, and converts it into {@link String} objects.
 *
 * <p>Additionally, it splits the string by lines.
 *
 * <p>Classes extending it must implement {@link #processNewLines(String[])} which receives new
 * parsed lines as they become available.
 *
 * <p>This class is a copy of {@link com.android.ddmlib.MultiLineReceiver} adapted for use within
 * deployer without a dependency on ddmlib.
 */
public abstract class DeployerMultiLineReceiver implements DeployerIShellOutputReceiver {

    private boolean trimLines = true;

    /** unfinished message line, stored for next packet */
    private String unfinishedLine = null;

    private final Collection<String> lineAccumulator = new ArrayList<>();

    /**
     * Set the trim lines flag.
     *
     * @param trim whether the lines are trimmed, or not.
     */
    public void setTrimLine(boolean trim) {
        trimLines = trim;
    }

    @Override
    public final void addOutput(@NonNull byte[] data, int offset, int length) {
        if (!isCancelled()) {
            String s = new String(data, offset, length, StandardCharsets.UTF_8);

            // ok we've got a string
            // if we had an unfinished line we add it.
            if (unfinishedLine != null) {
                s = unfinishedLine + s;
                unfinishedLine = null;
            }

            // now we split the lines
            lineAccumulator.clear();
            int start = 0;
            do {
                int index = s.indexOf('\n', start);

                // if \n was not found, this is an unfinished line
                // and we store it to be processed for the next packet
                if (index == -1) {
                    unfinishedLine = s.substring(start);
                    break;
                }

                // we found a \n, in older devices, this is preceded by a \r
                int newlineLength = 1;
                if (index > 0 && s.charAt(index - 1) == '\r') {
                    index--;
                    newlineLength = 2;
                }

                // extract the line
                String line = s.substring(start, index);
                if (trimLines) {
                    line = line.trim();
                }
                lineAccumulator.add(line);

                // move start to after the \r\n we found
                start = index + newlineLength;
            } while (true);

            if (!lineAccumulator.isEmpty()) {
                // at this point we've split all the lines.
                // make the array
                String[] lines = lineAccumulator.toArray(new String[0]);

                // send it for final processing
                processNewLines(lines);
            }
        }
    }

    @Override
    public void flush() {
        if (unfinishedLine != null) {
            processNewLines(new String[] {unfinishedLine});
        }
    }

    /**
     * Called when new lines are being received by the remote process.
     *
     * <p>It is guaranteed that the lines are complete when they are given to this method.
     *
     * @param lines The array containing the new lines.
     */
    public abstract void processNewLines(@NonNull String[] lines);
}
