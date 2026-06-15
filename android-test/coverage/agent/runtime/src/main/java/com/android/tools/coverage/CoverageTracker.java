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

package com.android.tools.coverage;

import android.util.Log;

import java.util.Arrays;

/**
 * Runtime tracker for on-the-fly code coverage. This class is injected into the application's
 * Bootstrap ClassLoader by the native coverage agent, making it globally visible.
 */
public class CoverageTracker {

    private static final String TAG = "studio.coverage.rt";
    // A large enough array to hold block hits.
    // TODO: Implement dynamic initialization from the native agent to allocate
    // exactly the required number of blocks for the specific project.
    private static final int MAX_BLOCKS = 10_000_000;

    // Using a final boolean array for maximum performance and lock-free thread safety.
    private static final boolean[] hits = new boolean[MAX_BLOCKS];

    /**
     * Called by the instrumented bytecode every time a basic block is executed.
     *
     * @param blockId The unique identifier of the executed basic block.
     */
    public static void hit(int blockId) {
        // Bounds checking ensures misbehaving or corrupted instrumentation
        // cannot crash the running application.
        if (blockId >= 0 && blockId < hits.length) {
            hits[blockId] = true;
        }
    }

    /** Clears all recorded hits. */
    public static void clear() {
        Arrays.fill(hits, false);
    }

    /** @return The internal boolean array tracking block hits. */
    public static boolean[] getHits() {
        Log.i(TAG, "getHits() called.");
        return hits;
    }

    /** Signals the native agent to write captured coverage data to disk immediately. */
    public static native void dumpCoverageData();
}
