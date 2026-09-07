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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Host-side unit tests and high-concurrency stress tests for CoverageTracker. */
public class CoverageTrackerTest {

    @Before
    public void setUp() {
        CoverageTracker.clear();
    }

    @Test
    public void testHitRecording() {
        boolean[] initialHits = CoverageTracker.getHits();
        assertNotNull(initialHits);
        assertFalse("Block 10 should not be hit yet", 10 < initialHits.length && initialHits[10]);

        CoverageTracker.hit(10);

        boolean[] hits = CoverageTracker.getHits();
        assertTrue("Block 10 should be hit", hits[10]);
        assertFalse("Block 11 should not be hit yet", hits[11]);
    }

    @Test
    public void testMultipleHitsAndLazyAllocation() {
        // Hits on different pages to verify dynamic, lazy page allocations.
        int block1 = 42; // Page 0
        int block2 = 100_000; // Page 1 (100,000 / 65,536 = 1)
        int block3 = 500_000; // Page 7 (500,000 / 65,536 = 7)

        CoverageTracker.hit(block1);
        CoverageTracker.hit(block2);
        CoverageTracker.hit(block3);

        boolean[] hits = CoverageTracker.getHits();
        assertTrue("Block 42 should be hit", hits[block1]);
        assertTrue("Block 100,000 should be hit", hits[block2]);
        assertTrue("Block 500,000 should be hit", hits[block3]);
        assertFalse("Block 200 should not be hit", hits[200]);
    }

    @Test
    public void testClear() {
        CoverageTracker.hit(42);
        assertTrue(CoverageTracker.getHits()[42]);

        CoverageTracker.clear();
        boolean[] clearedHits = CoverageTracker.getHits();
        assertFalse("Array should be cleared", 42 < clearedHits.length && clearedHits[42]);
    }

    @Test
    public void testOutOfBoundsHit() {
        // These calls should be silently ignored and not throw exceptions.
        CoverageTracker.hit(-1);
    }

    @Test
    public void testDirectoryExpansion() {
        // Lands on Page 1,068, forcing dynamic directory resizing.
        int hugeBlockId = 70_000_000;

        CoverageTracker.hit(hugeBlockId);

        boolean[] hits = CoverageTracker.getHits();
        assertTrue("Block 70,000,000 should be hit", hits[hugeBlockId]);
    }

    @Test
    public void testConcurrencyStressTest() throws Exception {
        final int numThreads = 16;
        final int maxBlockId = 1_500_000; // Addresses up to Page 22
        final int numProbes = 500_000;

        ExecutorService threadPool = Executors.newFixedThreadPool(numThreads);
        List<Callable<Void>> tasks = new ArrayList<>();

        // Run 16 concurrent threads rapidly hitting widely varying block ranges in parallel.
        // This forces multiple background threads to concurrently race and trigger dynamic,
        // lazy Copy-On-Write allocations for different pages.
        for (int i = 0; i < numThreads; ++i) {
            final int threadId = i;
            tasks.add(
                    () -> {
                        for (int j = 0; j < numProbes; ++j) {
                            // Generate block ID spreading across multiple pages
                            int blockId = (j * numThreads + threadId) % maxBlockId;
                            CoverageTracker.hit(blockId);
                        }
                        return null;
                    });
        }

        try {
            // Execute all tasks in parallel
            List<Future<Void>> futures = threadPool.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get(); // Propagates any JMM, race, or index crashes
            }
        } catch (Exception e) {
            fail("High-concurrency paged stress test failed with exception: " + e.getMessage());
        } finally {
            threadPool.shutdown();
        }

        // Assert full state consistency and accuracy
        boolean[] finalHits = CoverageTracker.getHits();
        assertNotNull(finalHits);

        // Verify that every single thread's hit was captured with perfect precision
        for (int i = 0; i < numThreads; ++i) {
            for (int j = 0; j < 1000; ++j) {
                int blockId = (j * numThreads + i) % maxBlockId;
                assertTrue("Hit on Block " + blockId + " was lost!", finalHits[blockId]);
            }
        }
    }

    @Test
    public void testClearResetsDimensions() {
        // Assert that hitting a block on page 1 expands flat Hits output
        CoverageTracker.hit(100_000);
        assertTrue(CoverageTracker.getHits().length > 65536);

        // Assert that clear() successfully shrinks segments back to size 0
        CoverageTracker.clear();
        boolean[] hits = CoverageTracker.getHits();
        assertTrue("Clearing should reset active directory size to 0", hits.length == 0);
    }
}
