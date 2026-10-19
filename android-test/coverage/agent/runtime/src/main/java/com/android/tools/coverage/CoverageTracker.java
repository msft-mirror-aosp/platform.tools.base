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

/**
 * Runtime tracker for on-the-fly code coverage. This class is injected into the application's
 * Bootstrap ClassLoader by the native coverage agent, making it globally visible.
 */
public class CoverageTracker {

    private static final String TAG = "studio.coverage.rt";

    // Power-of-2 page division constants (65,536 elements per page)
    private static final int CHUNK_SHIFT = 16;
    private static final int CHUNK_MASK = 0xFFFF;
    private static final int PAGE_SIZE = 65_536;

    // Symmetrical starting directory size
    private static final int INITIAL_DIRECTORY_SIZE = 1_024;

    // Volatile directory holding pointers page arrays.
    private static volatile boolean[][] segments = new boolean[INITIAL_DIRECTORY_SIZE][];

    /**
     * Called by the instrumented bytecode every time a basic block is executed.
     *
     * @param blockId The unique identifier of the executed basic block.
     */
    public static void hit(int blockId) {
        int chunkIdx = blockId >>> CHUNK_SHIFT;
        int offset = blockId & CHUNK_MASK;

        boolean[][] localSegments = segments; // Read reference once locally for thread-safety
        if (chunkIdx >= 0) {
            // If the chunk ID exceeds our directory size, expand it dynamically!
            if (chunkIdx >= localSegments.length) {
                allocatePage(chunkIdx); // Slow-path synchronized expansion
                localSegments = segments; // Re-snap the newly expanded volatile reference
            }

            boolean[] segment = localSegments[chunkIdx]; // Non-volatile element read
            if (segment == null) {
                allocatePage(chunkIdx); // Slow-path synchronized page allocation
                localSegments = segments; // Re-snap the newly swapped volatile reference
                segment = localSegments[chunkIdx];
            }
            if (segment != null && offset >= 0 && offset < segment.length) {
                segment[offset] = true;
            }
        }
    }

    /**
     * Synchronized page allocation and directory resizing using the Copy-On-Write (COW) pattern.
     * This guarantees 100% memory visibility across all threads and CPU cores.
     *
     * @param chunkIdx Index of the page to allocate or resize for.
     */
    private static synchronized void allocatePage(int chunkIdx) {
        // 1. Double-checked Directory Expansion (Under Copy-On-Write)
        if (chunkIdx >= segments.length) {
            int newSize = Math.max(segments.length * 2, chunkIdx + 1);
            boolean[][] newSegments = new boolean[newSize][];
            System.arraycopy(segments, 0, newSegments, 0, segments.length);
            segments = newSegments; // Atomic volatile reference swap
            logInfo("Expanded CoverageTracker page directory dynamically to size: " + newSize);
        }

        // 2. Double-checked Page Allocation (Under Copy-On-Write)
        if (segments[chunkIdx] == null) {
            boolean[][] newSegments = new boolean[segments.length][];
            System.arraycopy(segments, 0, newSegments, 0, segments.length);
            newSegments[chunkIdx] = new boolean[PAGE_SIZE];
            segments = newSegments; // Atomic volatile reference swap
            logInfo("Allocated new coverage memory page: " + chunkIdx);
        }
    }

    /** Clear all segments and shrink directory back to the initial size to reclaim memory. */
    public static synchronized void clear() {
        segments = new boolean[INITIAL_DIRECTORY_SIZE][];
    }

    private static void logInfo(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable t) {
            // Fallback to standard console output when executing in host-side JVM unit tests.
            System.out.println(TAG + ": " + message);
        }
    }

    /**
     * Flatten and export the 2D paged segment store back to a single flat boolean array.
     *
     * @return A flat boolean array containing all basic block execution records.
     */
    public static boolean[] getHits() {
        boolean[][] localSegments = segments;

        // Dynamically find the highest active page index to allocate the absolute minimum flat size
        int highestPageIdx = -1;
        for (int i = 0; i < localSegments.length; i++) {
            if (localSegments[i] != null) {
                highestPageIdx = i;
            }
        }

        // If no pages were ever allocated, return an empty array
        if (highestPageIdx == -1) {
            return new boolean[0];
        }

        int flatSize = (highestPageIdx + 1) * PAGE_SIZE;
        boolean[] flatHits = new boolean[flatSize];

        for (int chunkIdx = 0; chunkIdx <= highestPageIdx; chunkIdx++) {
            boolean[] segment = localSegments[chunkIdx];
            if (segment != null) {
                int pageOffset = chunkIdx << CHUNK_SHIFT;
                for (int offset = 0; offset < segment.length; offset++) {
                    if (segment[offset]) {
                        int flatId = pageOffset | offset;
                        if (flatId >= 0 && flatId < flatHits.length) {
                            flatHits[flatId] = true;
                        }
                    }
                }
            }
        }
        return flatHits;
    }

    /** Signals the native agent to write captured coverage data to disk immediately. */
    public static native void dumpCoverageData();
}
