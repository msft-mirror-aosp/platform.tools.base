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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import com.android.tools.profiler.proto.LeakCanary;
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisData;
import com.android.tools.profiler.proto.LeakCanary.LeakTraceNode;
import com.android.tools.profiler.proto.LeakCanary.ReferencingField;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import shark.ApplicationLeak;
import shark.HeapAnalysisException;
import shark.HeapAnalysisFailure;
import shark.HeapAnalysisSuccess;
import shark.LeakTrace;
import shark.LeakTraceObject;
import shark.LeakTraceReference;
import shark.LibraryLeak;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tests that SharkResultConverter turns a Shark result into the same proto Studio makes in ON_HOST
 * (SharkToDataAdapter and LeakCanaryProtoAdapter), using small fakes of Shark (see the shark
 * package in this folder).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SharkResultConverterTest {

    @Test
    public void testSuccessCopiesTimesAndMetadata() throws Exception {
        LeakCanaryAnalysisData data = SharkResultConverter.convert(FakeSharkResults.success());

        assertThat(data.hasSuccess()).isTrue();
        assertThat(data.getCreatedAtTimeMillis()).isEqualTo(1_000L);
        assertThat(data.getDumpDurationMillis()).isEqualTo(20L);
        assertThat(data.getAnalysisDurationMillis()).isEqualTo(300L);
        assertThat(data.getSuccess().getMetadataMap())
                .containsExactly("LeakCanary version", "2.14", "App process name", "com.example");
        // Studio reads only the structured fields, so the old text field stays empty.
        assertThat(data.getData()).isEmpty();
    }

    /** Application leaks come first, then library leaks, like Studio. */
    @Test
    public void testLeakTypesAndOrder() throws Exception {
        LeakCanaryAnalysisData data = SharkResultConverter.convert(FakeSharkResults.success());

        assertThat(data.getSuccess().getLeaksCount()).isEqualTo(2);
        LeakCanary.Leak appLeak = data.getSuccess().getLeaks(0);
        LeakCanary.Leak libraryLeak = data.getSuccess().getLeaks(1);
        assertThat(appLeak.getType())
                .isEqualTo(LeakCanary.Leak.LeakType.LEAK_TYPE_APPLICATION_LEAK);
        assertThat(appLeak.getSignature()).isEqualTo("app-signature");
        assertThat(libraryLeak.getType())
                .isEqualTo(LeakCanary.Leak.LeakType.LEAK_TYPE_LIBRARY_LEAK);
        assertThat(libraryLeak.getSignature()).isEqualTo("library-signature");
    }

    /** All traces of a leak are kept, not only the first one. */
    @Test
    public void testAllTracesAreKept() throws Exception {
        LeakCanary.Leak appLeak =
                SharkResultConverter.convert(FakeSharkResults.success()).getSuccess().getLeaks(0);

        assertThat(appLeak.getLeakTraceCount()).isEqualTo(2);
        assertThat(appLeak.getLeakTracesCount()).isEqualTo(2);
    }

    @Test
    public void testRetainedByteSizeIsSetOnlyWhenKnown() throws Exception {
        LeakCanaryAnalysisData data = SharkResultConverter.convert(FakeSharkResults.success());

        assertThat(data.getSuccess().getLeaks(0).hasRetainedByteSize()).isTrue();
        assertThat(data.getSuccess().getLeaks(0).getRetainedByteSize()).isEqualTo(5000L);
        assertThat(data.getSuccess().getLeaks(1).hasRetainedByteSize()).isFalse(); // null size
    }

    /** A negative size means unknown in Studio, so it stays unset too. */
    @Test
    public void testNegativeRetainedByteSizeStaysUnset() throws Exception {
        HeapAnalysisSuccess success =
                successWithLeak(
                        new ApplicationLeak(
                                "s", -1, Collections.singletonList(FakeSharkResults.trace())));

        assertThat(
                        SharkResultConverter.convert(success)
                                .getSuccess()
                                .getLeaks(0)
                                .hasRetainedByteSize())
                .isFalse();
    }

    /**
     * Each object on the path gets the reference that leaves it. The leaking object is last and has
     * no reference.
     */
    @Test
    public void testTraceNodesAndReferences() throws Exception {
        LeakCanary.LeakTrace trace = firstTrace(FakeSharkResults.success());

        assertThat(trace.getGcRootType())
                .isEqualTo(LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_STICKY_CLASS);
        assertThat(trace.getNodesCount()).isEqualTo(3);

        LeakTraceNode root = trace.getNodes(0);
        assertThat(root.getNodeType()).isEqualTo(LeakTraceNode.NodeType.NODE_TYPE_CLASS);
        assertThat(root.getClassName()).isEqualTo("com.example.Holder");
        assertThat(root.getLeakingStatus())
                .isEqualTo(LeakTraceNode.LeakingStatus.LEAKING_STATUS_NO);
        assertThat(root.getLeakingStatusReason()).isEqualTo("a class is never leaking");
        assertThat(root.getReferencingField().getClassName()).isEqualTo("Holder");
        assertThat(root.getReferencingField().getReferenceName()).isEqualTo("items");
        assertThat(root.getReferencingField().getType())
                .isEqualTo(
                        ReferencingField.ReferencingFieldType.REFERENCING_FIELD_TYPE_STATIC_FIELD);
        assertThat(root.getReferencingField().getIsLikelyCause()).isFalse();

        LeakTraceNode middle = trace.getNodes(1);
        assertThat(middle.getNodeType()).isEqualTo(LeakTraceNode.NodeType.NODE_TYPE_ARRAY);
        assertThat(middle.getLeakingStatus())
                .isEqualTo(LeakTraceNode.LeakingStatus.LEAKING_STATUS_UNKNOWN);
        assertThat(middle.getReferencingField().getType())
                .isEqualTo(
                        ReferencingField.ReferencingFieldType.REFERENCING_FIELD_TYPE_ARRAY_ENTRY);
        assertThat(middle.getReferencingField().getIsLikelyCause()).isTrue(); // index 1 is suspect

        LeakTraceNode leaking = trace.getNodes(2);
        assertThat(leaking.getNodeType()).isEqualTo(LeakTraceNode.NodeType.NODE_TYPE_INSTANCE);
        assertThat(leaking.getClassName()).isEqualTo("com.example.MainActivity");
        assertThat(leaking.getLeakingStatus())
                .isEqualTo(LeakTraceNode.LeakingStatus.LEAKING_STATUS_YES);
        assertThat(leaking.getNotesList())
                .containsExactly("mDestroyed = true", "second note")
                .inOrder();
        assertThat(leaking.getRetainedHeapSize()).isEqualTo("1.2 kB");
        assertThat(leaking.getRetainedObjectCount()).isEqualTo(7);
        assertThat(leaking.hasReferencingField()).isFalse();
    }

    /** Unknown sizes and counts on a node stay unset. */
    @Test
    public void testNodeOptionalValuesStayUnsetWhenNull() throws Exception {
        LeakTraceNode root = firstTrace(FakeSharkResults.success()).getNodes(0);

        assertThat(root.hasRetainedHeapSize()).isFalse();
        assertThat(root.hasRetainedObjectCount()).isFalse();
    }

    @Test
    public void testAllReferenceTypes() throws Exception {
        assertThat(referenceTypeOf(LeakTraceReference.ReferenceType.INSTANCE_FIELD))
                .isEqualTo(
                        ReferencingField.ReferencingFieldType
                                .REFERENCING_FIELD_TYPE_INSTANCE_FIELD);
        assertThat(referenceTypeOf(LeakTraceReference.ReferenceType.STATIC_FIELD))
                .isEqualTo(
                        ReferencingField.ReferencingFieldType.REFERENCING_FIELD_TYPE_STATIC_FIELD);
        assertThat(referenceTypeOf(LeakTraceReference.ReferenceType.LOCAL))
                .isEqualTo(ReferencingField.ReferencingFieldType.REFERENCING_FIELD_TYPE_LOCAL);
        assertThat(referenceTypeOf(LeakTraceReference.ReferenceType.ARRAY_ENTRY))
                .isEqualTo(
                        ReferencingField.ReferencingFieldType.REFERENCING_FIELD_TYPE_ARRAY_ENTRY);
    }

    /** Each of the 9 Shark 2.14 GC root types maps to the proto value with the same name. */
    @Test
    public void testAllKnownGcRootTypes() throws Exception {
        for (LeakTrace.GcRootType gcRoot : LeakTrace.GcRootType.values()) {
            if (gcRoot == LeakTrace.GcRootType.NEW_IN_LATER_VERSION
                    || gcRoot == LeakTrace.GcRootType.NEW_INTERNED_STRING) {
                continue;
            }
            LeakCanary.LeakTrace trace =
                    firstTrace(
                            FakeSharkResults.success(
                                    FakeSharkResults.trace(
                                            gcRoot, LeakTraceObject.ObjectType.INSTANCE)));
            assertThat(trace.getGcRootType().name()).isEqualTo("GC_ROOT_TYPE_" + gcRoot.name());
        }
    }

    /** A new GC root with a description text Studio knows is matched by that text, like Studio. */
    @Test
    public void testNewGcRootTypeIsMatchedByDescription() throws Exception {
        LeakCanary.LeakTrace trace =
                firstTrace(
                        FakeSharkResults.success(
                                FakeSharkResults.trace(
                                        LeakTrace.GcRootType.NEW_INTERNED_STRING,
                                        LeakTraceObject.ObjectType.INSTANCE)));

        assertThat(trace.getGcRootType())
                .isEqualTo(LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_INTERNED_STRING);
    }

    /**
     * Every description text maps like Studio's GcRootType.fromDescription(). The texts are copied
     * by hand from tools/base/leakcanarylib/.../data/GcRootType.kt (this target can't depend on
     * leakcanarylib), so a typo on either side fails here.
     */
    @Test
    public void testAllGcRootDescriptionsMatchStudio() {
        Map<String, LeakCanary.LeakTrace.GcRootType> studio = new LinkedHashMap<>();
        studio.put(
                "Global variable in native code",
                LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_JNI_GLOBAL);
        studio.put(
                "Local variable in native code",
                LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_JNI_LOCAL);
        studio.put("Java local variable", LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_JAVA_FRAME);
        studio.put(
                "Input or output parameters in native code",
                LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_NATIVE_STACK);
        studio.put("System class", LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_STICKY_CLASS);
        studio.put("Thread block", LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_THREAD_BLOCK);
        studio.put(
                "Monitor (anything that called the wait() or notify() methods, or that is"
                        + " synchronized.)",
                LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_MONITOR_USED);
        studio.put("Thread object", LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_THREAD_OBJECT);
        studio.put("Root JNI monitor", LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_JNI_MONITOR);
        studio.put("Interned string", LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_INTERNED_STRING);
        studio.put(
                "Object awaiting finalization",
                LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_FINALIZING);
        studio.put("Debugger reference", LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_DEBUGGER);
        studio.put(
                "Reference cleanup",
                LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_REFERENCE_CLEANUP);
        studio.put(
                "VM internal reference", LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_VM_INTERNAL);
        studio.put("Unreachable object", LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_UNREACHABLE);
        studio.put("Unknown GC root type", LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_UNKNOWN);

        for (Map.Entry<String, LeakCanary.LeakTrace.GcRootType> entry : studio.entrySet()) {
            assertThat(SharkResultConverter.gcRootTypeFromDescription(entry.getKey()))
                    .isEqualTo(entry.getValue());
        }
        assertThat(SharkResultConverter.gcRootTypeFromDescription(null))
                .isEqualTo(LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_UNKNOWN);
    }

    /** A new GC root with an unknown text maps to UNKNOWN, like Studio. It does not fail. */
    @Test
    public void testUnknownGcRootTypeMapsToUnknown() throws Exception {
        LeakCanary.LeakTrace trace =
                firstTrace(
                        FakeSharkResults.success(
                                FakeSharkResults.trace(
                                        LeakTrace.GcRootType.NEW_IN_LATER_VERSION,
                                        LeakTraceObject.ObjectType.INSTANCE)));

        assertThat(trace.getGcRootType())
                .isEqualTo(LeakCanary.LeakTrace.GcRootType.GC_ROOT_TYPE_UNKNOWN);
    }

    /** A new object type means a LeakCanary version we don't support: the whole result fails. */
    @Test
    public void testUnknownObjectTypeFails() {
        HeapAnalysisSuccess success =
                FakeSharkResults.success(
                        FakeSharkResults.trace(
                                LeakTrace.GcRootType.STICKY_CLASS,
                                LeakTraceObject.ObjectType.NEW_IN_LATER_VERSION));

        assertThrows(
                SharkResultConverter.UnsupportedLeakCanaryException.class,
                () -> SharkResultConverter.convert(success));
    }

    @Test
    public void testUnknownLeakingStatusFails() {
        LeakTraceObject leaking =
                new LeakTraceObject(
                        LeakTraceObject.ObjectType.INSTANCE,
                        "A",
                        Collections.<String>emptySet(),
                        LeakTraceObject.LeakingStatus.NEW_IN_LATER_VERSION,
                        "",
                        null,
                        null);
        HeapAnalysisSuccess success =
                FakeSharkResults.success(
                        new LeakTrace(
                                LeakTrace.GcRootType.JAVA_FRAME,
                                Collections.<LeakTraceReference>emptyList(),
                                leaking,
                                Collections.<Integer>emptySet()));

        assertThrows(
                SharkResultConverter.UnsupportedLeakCanaryException.class,
                () -> SharkResultConverter.convert(success));
    }

    @Test
    public void testUnknownReferenceTypeFails() {
        assertThrows(
                SharkResultConverter.UnsupportedLeakCanaryException.class,
                () -> referenceTypeOf(LeakTraceReference.ReferenceType.NEW_IN_LATER_VERSION));
    }

    @Test
    public void testFailureCopiesTimesAndException() throws Exception {
        HeapAnalysisFailure failure =
                new HeapAnalysisFailure(
                        2_000L,
                        30L,
                        400L,
                        new HeapAnalysisException(new IllegalStateException("out of memory")));

        LeakCanaryAnalysisData data = SharkResultConverter.convert(failure);

        assertThat(data.hasFailure()).isTrue();
        assertThat(data.getCreatedAtTimeMillis()).isEqualTo(2_000L);
        assertThat(data.getDumpDurationMillis()).isEqualTo(30L);
        assertThat(data.getAnalysisDurationMillis()).isEqualTo(400L);
        assertThat(data.getFailure().getExceptionMessage())
                .isEqualTo("java.lang.IllegalStateException: out of memory");
        assertThat(data.getFailure().getExceptionStackTrace())
                .contains("Caused by: java.lang.IllegalStateException: out of memory");
        assertThat(data.getData()).isEmpty();
    }

    /** With no message, the exception's toString() is used, like Studio. */
    @Test
    public void testFailureWithoutMessageUsesToString() throws Exception {
        HeapAnalysisFailure failure =
                new HeapAnalysisFailure(0L, 0L, 0L, new HeapAnalysisException(null));

        assertThat(SharkResultConverter.convert(failure).getFailure().getExceptionMessage())
                .isEqualTo("shark.HeapAnalysisException");
    }

    @Test
    public void testUnknownAnalysisTypeFails() {
        assertThrows(
                SharkResultConverter.UnsupportedLeakCanaryException.class,
                () -> SharkResultConverter.convert(new Object()));
        assertThrows(
                SharkResultConverter.UnsupportedLeakCanaryException.class,
                () -> SharkResultConverter.convert(null));
    }

    /** Same text as Studio's humanReadableByteCount (SI units, dot as decimal mark). */
    @Test
    public void testFormatByteCount() {
        assertThat(SharkResultConverter.formatByteCount(0)).isEqualTo("0 B");
        assertThat(SharkResultConverter.formatByteCount(999)).isEqualTo("999 B");
        assertThat(SharkResultConverter.formatByteCount(1000)).isEqualTo("1.0 kB");
        assertThat(SharkResultConverter.formatByteCount(1234)).isEqualTo("1.2 kB");
        assertThat(SharkResultConverter.formatByteCount(1_550_000)).isEqualTo("1.6 MB");
        assertThat(SharkResultConverter.formatByteCount(2_000_000_000L)).isEqualTo("2.0 GB");
    }

    private static LeakCanary.LeakTrace firstTrace(HeapAnalysisSuccess success) throws Exception {
        return SharkResultConverter.convert(success).getSuccess().getLeaks(0).getLeakTraces(0);
    }

    /** Converts a trace whose only reference has the given type, and returns that type. */
    private static ReferencingField.ReferencingFieldType referenceTypeOf(
            LeakTraceReference.ReferenceType type) throws Exception {
        LeakTraceObject object =
                new LeakTraceObject(
                        LeakTraceObject.ObjectType.INSTANCE,
                        "A",
                        Collections.<String>emptySet(),
                        LeakTraceObject.LeakingStatus.UNKNOWN,
                        "",
                        null,
                        null);
        LeakTrace trace =
                new LeakTrace(
                        LeakTrace.GcRootType.JAVA_FRAME,
                        Collections.singletonList(new LeakTraceReference(object, type, "A", "f")),
                        object,
                        Collections.<Integer>emptySet());
        return firstTrace(FakeSharkResults.success(trace))
                .getNodes(0)
                .getReferencingField()
                .getType();
    }

    private static HeapAnalysisSuccess successWithLeak(ApplicationLeak leak) {
        Map<String, String> metadata = new LinkedHashMap<>();
        return new HeapAnalysisSuccess(
                0L,
                0L,
                0L,
                metadata,
                Collections.singletonList(leak),
                Collections.<LibraryLeak>emptyList());
    }
}
