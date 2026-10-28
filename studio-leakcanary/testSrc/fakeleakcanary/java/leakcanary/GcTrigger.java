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

/** Test fake of LeakCanary's GcTrigger. */
public interface GcTrigger {
    void runGc();

    /** Fake of GcTrigger.Default. A "GC" sets the retained count to countAfterGc. */
    final class Default implements GcTrigger {
        public static final Default INSTANCE = new Default();

        public int runCount;

        /** Retained count after a GC, or -1 to leave the count as it is. */
        public int countAfterGc = -1;

        private Default() {}

        @Override
        public void runGc() {
            runCount++;
            if (countAfterGc >= 0) {
                AppWatcher.INSTANCE.getObjectWatcher().retainedObjectCount = countAfterGc;
            }
        }

        /** Puts the fake back to its first state. */
        public void reset() {
            runCount = 0;
            countAfterGc = -1;
        }
    }
}
