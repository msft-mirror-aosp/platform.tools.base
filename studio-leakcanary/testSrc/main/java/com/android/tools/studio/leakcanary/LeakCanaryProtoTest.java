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

import com.android.tools.profiler.proto.LeakCanary.Leak;
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisData;
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisFailure;
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisSuccess;
import com.android.tools.profiler.proto.LeakCanary.LeakTrace;
import com.android.tools.profiler.proto.LeakCanary.LeakTraceNode;
import com.android.tools.profiler.proto.LeakCanary.ReferencingField;
import com.android.tools.profiler.proto.LeakCanary.ReferencingField.ReferencingFieldType;

import com.google.protobuf.GeneratedMessageLite;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;

/**
 * Checks that the proto classes made from leakcanary_data.proto are in this library and work: a
 * report turned into bytes reads back the same.
 */
@RunWith(JUnit4.class)
public class LeakCanaryProtoTest {

    /** The classes must be the small "lite" kind. Full classes would need full protobuf-java. */
    @Test
    public void generatedClassesUseLiteRuntime() {
        assertThat(GeneratedMessageLite.class.isAssignableFrom(LeakCanaryAnalysisData.class))
                .isTrue();
    }

    @Test
    public void successfulAnalysisReadsBackTheSame() throws Exception {
        LeakCanaryAnalysisData original =
                LeakCanaryAnalysisData.newBuilder()
                        .setSuccess(
                                LeakCanaryAnalysisSuccess.newBuilder()
                                        .putMetadata("LeakCanary version", "2.14")
                                        .putMetadata("App process name", "com.example.app")
                                        .addLeaks(applicationLeakWithTwoOccurrences())
                                        .addLeaks(libraryLeakWithoutRetainedSize()))
                        .setCreatedAtTimeMillis(1_000L)
                        .setDumpDurationMillis(200L)
                        .setAnalysisDurationMillis(300L)
                        .build();

        LeakCanaryAnalysisData parsed = LeakCanaryAnalysisData.parseFrom(original.toByteArray());

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.getStructuredResultCase())
                .isEqualTo(LeakCanaryAnalysisData.StructuredResultCase.SUCCESS);
        LeakCanaryAnalysisSuccess success = parsed.getSuccess();
        assertThat(success.getMetadataMap()).containsEntry("LeakCanary version", "2.14");
        assertThat(success.getLeaksCount()).isEqualTo(2);

        // Every occurrence of a leak is kept, not just the first.
        Leak applicationLeak = success.getLeaks(0);
        assertThat(applicationLeak.getLeakTracesCount()).isEqualTo(2);
        assertThat(applicationLeak.getLeakTraces(1).getNodes(0).getClassName())
                .isEqualTo("com.example.SecondActivity");

        // Optional fields remember whether they were set.
        assertThat(applicationLeak.hasRetainedByteSize()).isTrue();
        assertThat(success.getLeaks(1).hasRetainedByteSize()).isFalse();
    }

    @Test
    public void failedAnalysisReadsBackTheSame() throws Exception {
        LeakCanaryAnalysisData original =
                LeakCanaryAnalysisData.newBuilder()
                        .setFailure(
                                LeakCanaryAnalysisFailure.newBuilder()
                                        .setExceptionMessage("Heap analysis failed")
                                        .setExceptionStackTrace("java.lang.OutOfMemoryError\n\tat"))
                        .setCreatedAtTimeMillis(1_000L)
                        .build();

        LeakCanaryAnalysisData parsed = LeakCanaryAnalysisData.parseFrom(original.toByteArray());

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.getStructuredResultCase())
                .isEqualTo(LeakCanaryAnalysisData.StructuredResultCase.FAILURE);
        assertThat(parsed.getFailure().getExceptionMessage()).isEqualTo("Heap analysis failed");
    }

    @Test
    public void unknownFieldsFromNewerWritersDoNotBreakParsing() throws Exception {
        // A newer library may add fields an older reader doesn't know. Reading must still work,
        // and the unknown field must be kept.
        LeakCanaryAnalysisData original =
                LeakCanaryAnalysisData.newBuilder()
                        .setSuccess(
                                LeakCanaryAnalysisSuccess.newBuilder()
                                        .addLeaks(libraryLeakWithoutRetainedSize()))
                        .setCreatedAtTimeMillis(1_000L)
                        .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(original.toByteArray());
        // An extra field (number 100, value 1) that leakcanary_data.proto doesn't have.
        bytes.write(new byte[] {(byte) 0xA0, 0x06, 0x01});

        LeakCanaryAnalysisData parsed = LeakCanaryAnalysisData.parseFrom(bytes.toByteArray());

        assertThat(parsed.getSuccess()).isEqualTo(original.getSuccess());
        assertThat(parsed.getCreatedAtTimeMillis()).isEqualTo(1_000L);
        assertThat(parsed.toByteArray()).isEqualTo(bytes.toByteArray());
    }

    private static Leak applicationLeakWithTwoOccurrences() {
        return Leak.newBuilder()
                .setType(Leak.LeakType.LEAK_TYPE_APPLICATION_LEAK)
                .setRetainedByteSize(4_096L)
                .setSignature("app-leak-signature")
                .setLeakTraceCount(2)
                .addLeakTraces(leakTrace("com.example.MainActivity"))
                .addLeakTraces(leakTrace("com.example.SecondActivity"))
                .build();
    }

    private static Leak libraryLeakWithoutRetainedSize() {
        return Leak.newBuilder()
                .setType(Leak.LeakType.LEAK_TYPE_LIBRARY_LEAK)
                .setSignature("library-leak-signature")
                .setLeakTraceCount(1)
                .addLeakTraces(leakTrace("android.view.inputmethod.InputMethodManager"))
                .build();
    }

    private static LeakTrace leakTrace(String leakingClassName) {
        return LeakTrace.newBuilder()
                .setGcRootType(LeakTrace.GcRootType.GC_ROOT_TYPE_STICKY_CLASS)
                .addNodes(
                        LeakTraceNode.newBuilder()
                                .setNodeType(LeakTraceNode.NodeType.NODE_TYPE_INSTANCE)
                                .setClassName(leakingClassName)
                                .setLeakingStatus(LeakTraceNode.LeakingStatus.LEAKING_STATUS_YES)
                                .setLeakingStatusReason("Activity#mDestroyed is true")
                                .setReferencingField(likelyCauseField(leakingClassName))
                                .addNotes("Activity is destroyed")
                                .setRetainedHeapSize("1.2 kB")
                                .setRetainedObjectCount(3))
                .addNodes(
                        LeakTraceNode.newBuilder()
                                .setNodeType(LeakTraceNode.NodeType.NODE_TYPE_CLASS)
                                .setClassName("com.example.Singleton")
                                .setLeakingStatus(LeakTraceNode.LeakingStatus.LEAKING_STATUS_NO))
                .build();
    }

    private static ReferencingField likelyCauseField(String className) {
        return ReferencingField.newBuilder()
                .setClassName(className)
                .setReferenceName("listener")
                .setType(ReferencingFieldType.REFERENCING_FIELD_TYPE_INSTANCE_FIELD)
                .setIsLikelyCause(true)
                .build();
    }
}
