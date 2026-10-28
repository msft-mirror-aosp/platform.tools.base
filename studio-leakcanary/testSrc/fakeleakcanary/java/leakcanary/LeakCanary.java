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

/** Test fake of LeakCanary's LeakCanary object. */
public final class LeakCanary {
    public static final LeakCanary INSTANCE = new LeakCanary();

    private Config config = new Config();

    private LeakCanary() {}

    public Config getConfig() {
        return config;
    }

    /** Needed because our reflection looks it up. Does nothing. */
    public void dumpHeap() {}

    /** Puts the fake back to its first state. */
    public void reset() {
        config = new Config();
    }

    /** Test fake of LeakCanary.Config. Like the real one, dumpHeap is private and final. */
    public static final class Config {
        private final boolean dumpHeap;

        Config() {
            // Set here, not inline, so reads are not inlined by the compiler.
            dumpHeap = true;
        }

        public boolean getDumpHeap() {
            return dumpHeap;
        }

        public int getRetainedVisibleThreshold() {
            return 5;
        }
    }
}
