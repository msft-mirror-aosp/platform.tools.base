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

/** Test fake of LeakCanary's EventListener, with only the events our tests use. */
public interface EventListener {

    void onEvent(Event event);

    /** Test fake of EventListener.Event. */
    abstract class Event {

        /** Progress is from 0.0 to 1.0. Real values are step / steps, so always below 1. */
        public static final class HeapAnalysisProgress extends Event {
            private final double progressPercent;

            public HeapAnalysisProgress(double progressPercent) {
                this.progressPercent = progressPercent;
            }

            public double getProgressPercent() {
                return progressPercent;
            }
        }

        /** An event that is not a progress event. */
        public static final class DumpingHeap extends Event {}
    }
}
