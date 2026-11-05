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

package shark;

import java.util.List;
import java.util.Set;

/** Test fake of Shark's LeakTrace: one path from a GC root to the leaking object. */
public final class LeakTrace {

    /**
     * The 9 values of Shark 2.14 with their real description texts, plus two values that Shark 2.14
     * does not have.
     */
    public enum GcRootType {
        JNI_GLOBAL("Global variable in native code"),
        JNI_LOCAL("Local variable in native code"),
        JAVA_FRAME("Java local variable"),
        NATIVE_STACK("Input or output parameters in native code"),
        STICKY_CLASS("System class"),
        THREAD_BLOCK("Thread block"),
        MONITOR_USED(
                "Monitor (anything that called the wait() or notify() methods, or that is"
                        + " synchronized.)"),
        THREAD_OBJECT("Thread object"),
        JNI_MONITOR("Root JNI monitor"),
        /** Stands for a value added in a later version, with a text Studio knows. */
        NEW_INTERNED_STRING("Interned string"),
        /** Stands for a value added in a later version, with a text nobody knows. */
        NEW_IN_LATER_VERSION("Something new");

        private final String description;

        GcRootType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private final GcRootType gcRootType;
    private final List<LeakTraceReference> referencePath;
    private final LeakTraceObject leakingObject;
    private final Set<Integer> suspectIndexes;

    /** suspectIndexes: the indexes in referencePath that are likely causes of the leak. */
    public LeakTrace(
            GcRootType gcRootType,
            List<LeakTraceReference> referencePath,
            LeakTraceObject leakingObject,
            Set<Integer> suspectIndexes) {
        this.gcRootType = gcRootType;
        this.referencePath = referencePath;
        this.leakingObject = leakingObject;
        this.suspectIndexes = suspectIndexes;
    }

    public GcRootType getGcRootType() {
        return gcRootType;
    }

    public List<LeakTraceReference> getReferencePath() {
        return referencePath;
    }

    public LeakTraceObject getLeakingObject() {
        return leakingObject;
    }

    public boolean referencePathElementIsSuspect(int index) {
        return suspectIndexes.contains(index);
    }
}
