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

package leakcanary;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Test fake of LeakCanary's LeakCanary object. */
public final class LeakCanary {
    public static final LeakCanary INSTANCE = new LeakCanary();

    private Config config = new Config();

    /** How many times dumpHeap() was called. */
    public int dumpHeapCount;

    private LeakCanary() {}

    public Config getConfig() {
        return config;
    }

    /** Only counts the calls. */
    public void dumpHeap() {
        dumpHeapCount++;
    }

    /** Puts the fake back to its first state. */
    public void reset() {
        config = new Config();
        dumpHeapCount = 0;
    }

    /**
     * Test fake of LeakCanary.Config. Like the real one, dumpHeap and eventListeners are private
     * and final, and the list can't be changed.
     */
    public static final class Config {
        /** Stand in for LeakCanary's own listeners. One of them starts the heap analysis. */
        public static final EventListener LEAK_CANARY_LISTENER_1 = event -> {};

        public static final EventListener LEAK_CANARY_LISTENER_2 = event -> {};

        private final boolean dumpHeap;
        private final List<EventListener> eventListeners;

        Config() {
            // Set here, not inline, so reads are not inlined by the compiler.
            dumpHeap = true;
            eventListeners =
                    Collections.unmodifiableList(
                            Arrays.asList(LEAK_CANARY_LISTENER_1, LEAK_CANARY_LISTENER_2));
        }

        public boolean getDumpHeap() {
            return dumpHeap;
        }

        public List<EventListener> getEventListeners() {
            return eventListeners;
        }

        public int getRetainedVisibleThreshold() {
            return 5;
        }
    }
}
