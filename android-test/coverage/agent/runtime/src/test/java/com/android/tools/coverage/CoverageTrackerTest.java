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

import org.junit.Before;
import org.junit.Test;

/**
 * Host-side unit tests for the CoverageTracker runtime.
 */
public class CoverageTrackerTest {

    @Before
    public void setUp() {
        CoverageTracker.clear();
    }

    @Test
    public void testHitRecording() {
        boolean[] hits = CoverageTracker.getHits();
        assertNotNull(hits);
        assertFalse("Block 10 should not be hit yet", hits[10]);
        
        CoverageTracker.hit(10);
        assertTrue("Block 10 should be hit", hits[10]);
    }

    @Test
    public void testMultipleHits() {
        CoverageTracker.hit(100);
        CoverageTracker.hit(500);
        
        boolean[] hits = CoverageTracker.getHits();
        assertTrue("Block 100 should be hit", hits[100]);
        assertTrue("Block 500 should be hit", hits[500]);
        assertFalse("Block 200 should not be hit", hits[200]);
    }

    @Test
    public void testClear() {
        CoverageTracker.hit(42);
        assertTrue(CoverageTracker.getHits()[42]);
        
        CoverageTracker.clear();
        assertFalse("Array should be cleared", CoverageTracker.getHits()[42]);
    }

    @Test
    public void testOutOfBoundsHit() {
        // These calls should be silently ignored and not throw exceptions.
        CoverageTracker.hit(-1);
        CoverageTracker.hit(CoverageTracker.getHits().length);
        CoverageTracker.hit(Integer.MAX_VALUE);
    }
}
