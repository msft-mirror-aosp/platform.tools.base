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

import java.util.LinkedHashSet;
import java.util.Set;

/** Test fake of LeakCanary's ObjectWatcher. Public fields let tests set and check its state. */
public class ObjectWatcher {
    public final Set<OnObjectRetainedListener> listeners = new LinkedHashSet<>();
    public int retainedObjectCount;

    /** Last value passed to clearObjectsWatchedBefore, or -1 if never called. */
    public long clearedBefore = -1;

    public int getRetainedObjectCount() {
        return retainedObjectCount;
    }

    public void clearObjectsWatchedBefore(long heapDumpUptimeMillis) {
        clearedBefore = heapDumpUptimeMillis;
    }

    public void addOnObjectRetainedListener(OnObjectRetainedListener listener) {
        listeners.add(listener);
    }

    public void removeOnObjectRetainedListener(OnObjectRetainedListener listener) {
        listeners.remove(listener);
    }

    /** Puts the fake back to its first state. */
    public void reset() {
        listeners.clear();
        retainedObjectCount = 0;
        clearedBefore = -1;
    }
}
