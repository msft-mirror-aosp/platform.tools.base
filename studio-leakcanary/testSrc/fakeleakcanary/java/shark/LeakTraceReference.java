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

/** Test fake of Shark's LeakTraceReference: the reference from one object to the next. */
public final class LeakTraceReference {

    /** The 4 values of Shark 2.14, plus one value that Shark 2.14 does not have. */
    public enum ReferenceType {
        INSTANCE_FIELD,
        STATIC_FIELD,
        LOCAL,
        ARRAY_ENTRY,
        /** Stands for a value added in a later LeakCanary version. */
        NEW_IN_LATER_VERSION
    }

    private final LeakTraceObject originObject;
    private final ReferenceType referenceType;
    private final String owningClassSimpleName;
    private final String referenceName;

    public LeakTraceReference(
            LeakTraceObject originObject,
            ReferenceType referenceType,
            String owningClassSimpleName,
            String referenceName) {
        this.originObject = originObject;
        this.referenceType = referenceType;
        this.owningClassSimpleName = owningClassSimpleName;
        this.referenceName = referenceName;
    }

    public LeakTraceObject getOriginObject() {
        return originObject;
    }

    public ReferenceType getReferenceType() {
        return referenceType;
    }

    public String getOwningClassSimpleName() {
        return owningClassSimpleName;
    }

    public String getReferenceName() {
        return referenceName;
    }
}
