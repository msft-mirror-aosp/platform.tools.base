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

import java.util.Set;

/** Test fake of Shark's LeakTraceObject: one object on a leak trace. */
public final class LeakTraceObject {

    /** The 3 values of Shark 2.14, plus one value that Shark 2.14 does not have. */
    public enum ObjectType {
        CLASS,
        ARRAY,
        INSTANCE,
        /** Stands for a value added in a later LeakCanary version. */
        NEW_IN_LATER_VERSION
    }

    /** The 3 values of Shark 2.14, plus one value that Shark 2.14 does not have. */
    public enum LeakingStatus {
        NOT_LEAKING,
        LEAKING,
        UNKNOWN,
        /** Stands for a value added in a later LeakCanary version. */
        NEW_IN_LATER_VERSION
    }

    private final ObjectType type;
    private final String className;
    private final Set<String> labels;
    private final LeakingStatus leakingStatus;
    private final String leakingStatusReason;
    private final Integer retainedHeapByteSize;
    private final Integer retainedObjectCount;

    /** retainedHeapByteSize and retainedObjectCount are null when not known. */
    public LeakTraceObject(
            ObjectType type,
            String className,
            Set<String> labels,
            LeakingStatus leakingStatus,
            String leakingStatusReason,
            Integer retainedHeapByteSize,
            Integer retainedObjectCount) {
        this.type = type;
        this.className = className;
        this.labels = labels;
        this.leakingStatus = leakingStatus;
        this.leakingStatusReason = leakingStatusReason;
        this.retainedHeapByteSize = retainedHeapByteSize;
        this.retainedObjectCount = retainedObjectCount;
    }

    public ObjectType getType() {
        return type;
    }

    public String getClassName() {
        return className;
    }

    public Set<String> getLabels() {
        return labels;
    }

    public LeakingStatus getLeakingStatus() {
        return leakingStatus;
    }

    public String getLeakingStatusReason() {
        return leakingStatusReason;
    }

    public Integer getRetainedHeapByteSize() {
        return retainedHeapByteSize;
    }

    public Integer getRetainedObjectCount() {
        return retainedObjectCount;
    }
}
