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

package com.android.tools.studio.leakcanary;

import shark.ApplicationLeak;
import shark.HeapAnalysisSuccess;
import shark.LeakTrace;
import shark.LeakTraceObject;
import shark.LeakTraceReference;
import shark.LibraryLeak;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Builds small fake Shark results for tests. */
final class FakeSharkResults {

    private FakeSharkResults() {}

    /** A path of 2 references (the second is suspect) and a leaking object: 3 nodes in total. */
    static LeakTrace trace() {
        return trace(LeakTrace.GcRootType.STICKY_CLASS, LeakTraceObject.ObjectType.INSTANCE);
    }

    /** Like {@link #trace()}, with the given GC root and leaking object type. */
    static LeakTrace trace(LeakTrace.GcRootType gcRoot, LeakTraceObject.ObjectType leakingType) {
        LeakTraceObject root =
                new LeakTraceObject(
                        LeakTraceObject.ObjectType.CLASS,
                        "com.example.Holder",
                        Collections.<String>emptySet(),
                        LeakTraceObject.LeakingStatus.NOT_LEAKING,
                        "a class is never leaking",
                        null,
                        null);
        LeakTraceObject middle =
                new LeakTraceObject(
                        LeakTraceObject.ObjectType.ARRAY,
                        "java.lang.Object[]",
                        Collections.<String>emptySet(),
                        LeakTraceObject.LeakingStatus.UNKNOWN,
                        "",
                        null,
                        null);
        LeakTraceObject leaking =
                new LeakTraceObject(
                        leakingType,
                        "com.example.MainActivity",
                        new LinkedHashSet<>(Arrays.asList("mDestroyed = true", "second note")),
                        LeakTraceObject.LeakingStatus.LEAKING,
                        "Activity#mDestroyed is true",
                        1234,
                        7);
        List<LeakTraceReference> path =
                Arrays.asList(
                        new LeakTraceReference(
                                root,
                                LeakTraceReference.ReferenceType.STATIC_FIELD,
                                "Holder",
                                "items"),
                        new LeakTraceReference(
                                middle,
                                LeakTraceReference.ReferenceType.ARRAY_ENTRY,
                                "Object[]",
                                "[0]"));
        return new LeakTrace(gcRoot, path, leaking, Collections.singleton(1));
    }

    /** One application leak with 2 traces and one library leak with an unknown size. */
    static HeapAnalysisSuccess success() {
        return success(trace());
    }

    /** Like {@link #success()}, with the given trace in the application leak. */
    static HeapAnalysisSuccess success(LeakTrace applicationTrace) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("LeakCanary version", "2.14");
        metadata.put("App process name", "com.example");
        return new HeapAnalysisSuccess(
                1_000L,
                20L,
                300L,
                metadata,
                Collections.singletonList(
                        new ApplicationLeak(
                                "app-signature", 5000, Arrays.asList(applicationTrace, trace()))),
                Collections.singletonList(
                        new LibraryLeak(
                                "library-signature", null, Collections.singletonList(trace()))));
    }
}
