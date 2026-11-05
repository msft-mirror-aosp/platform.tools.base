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

import com.android.tools.profiler.proto.LeakCanary.Leak;
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisData;
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisFailure;
import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisSuccess;
import com.android.tools.profiler.proto.LeakCanary.LeakTrace;
import com.android.tools.profiler.proto.LeakCanary.LeakTraceNode;
import com.android.tools.profiler.proto.LeakCanary.ReferencingField;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns LeakCanary's analysis result (a Shark {@code HeapAnalysisSuccess} or {@code
 * HeapAnalysisFailure}) into our {@link LeakCanaryAnalysisData} proto, with all leaks and all
 * traces.
 *
 * <p>The mapping matches Studio's {@code SharkToDataAdapter} and {@code LeakCanaryProtoAdapter}
 * (used in ON_HOST), so both modes give the same data. The {@code data} text field stays empty;
 * Studio only reads the structured fields.
 *
 * <p>Shark is read through reflection, so there is no compile-time dependency on LeakCanary. The
 * lookups are done once by {@link #loadReflection()}, which {@link
 * LeakCanaryReflectionHelper#ensureInitialized()} calls in its start-up check. If any lookup fails,
 * the whole library is marked as failed.
 */
final class SharkResultConverter {

    // Shark lookups, set once by loadReflection(). Other threads see them after they read
    // LeakCanaryReflectionHelper's volatile state (see convert()).
    private static Class<?> heapAnalysisSuccessClass;
    private static Class<?> heapAnalysisFailureClass;

    private static Method getCreatedAtTimeMillisMethod;
    private static Method getDumpDurationMillisMethod;
    private static Method getAnalysisDurationMillisMethod;

    private static Method getMetadataMethod;
    private static Method getApplicationLeaksMethod;
    private static Method getLibraryLeaksMethod;
    private static Method getExceptionMethod;

    private static Method getLeakTracesMethod;
    private static Method getSignatureMethod;
    private static Method getTotalRetainedHeapByteSizeMethod;

    private static Method getGcRootTypeMethod;
    private static Method getReferencePathMethod;
    private static Method getLeakingObjectMethod;
    private static Method referencePathElementIsSuspectMethod;
    private static Method getGcRootDescriptionMethod;

    private static Method getOriginObjectMethod;
    private static Method getReferenceTypeMethod;
    private static Method getOwningClassSimpleNameMethod;
    private static Method getReferenceNameMethod;

    private static Method getTypeMethod;
    private static Method getClassNameMethod;
    private static Method getLabelsMethod;
    private static Method getLeakingStatusMethod;
    private static Method getLeakingStatusReasonMethod;
    private static Method getRetainedHeapByteSizeMethod;
    private static Method getRetainedObjectCountMethod;

    private SharkResultConverter() {}

    /**
     * Looks up every Shark class and method the converter needs. Throws if one is missing. Only
     * called by {@link LeakCanaryReflectionHelper#ensureInitialized()}, under its lock.
     */
    static void loadReflection() throws ReflectiveOperationException {
        Class<?> heapAnalysisClass = Class.forName(HelperConfig.SHARK_HEAP_ANALYSIS_CLASS);
        getCreatedAtTimeMillisMethod =
                heapAnalysisClass.getMethod(HelperConfig.GET_CREATED_AT_TIME_MILLIS_METHOD);
        getDumpDurationMillisMethod =
                heapAnalysisClass.getMethod(HelperConfig.GET_DUMP_DURATION_MILLIS_METHOD);
        getAnalysisDurationMillisMethod =
                heapAnalysisClass.getMethod(HelperConfig.GET_ANALYSIS_DURATION_MILLIS_METHOD);

        heapAnalysisSuccessClass = Class.forName(HelperConfig.SHARK_HEAP_ANALYSIS_SUCCESS_CLASS);
        getMetadataMethod = heapAnalysisSuccessClass.getMethod(HelperConfig.GET_METADATA_METHOD);
        getApplicationLeaksMethod =
                heapAnalysisSuccessClass.getMethod(HelperConfig.GET_APPLICATION_LEAKS_METHOD);
        getLibraryLeaksMethod =
                heapAnalysisSuccessClass.getMethod(HelperConfig.GET_LIBRARY_LEAKS_METHOD);

        heapAnalysisFailureClass = Class.forName(HelperConfig.SHARK_HEAP_ANALYSIS_FAILURE_CLASS);
        getExceptionMethod = heapAnalysisFailureClass.getMethod(HelperConfig.GET_EXCEPTION_METHOD);

        Class<?> leakClass = Class.forName(HelperConfig.SHARK_LEAK_CLASS);
        getLeakTracesMethod = leakClass.getMethod(HelperConfig.GET_LEAK_TRACES_METHOD);
        getSignatureMethod = leakClass.getMethod(HelperConfig.GET_SIGNATURE_METHOD);
        getTotalRetainedHeapByteSizeMethod =
                leakClass.getMethod(HelperConfig.GET_TOTAL_RETAINED_HEAP_BYTE_SIZE_METHOD);

        Class<?> leakTraceClass = Class.forName(HelperConfig.SHARK_LEAK_TRACE_CLASS);
        getGcRootTypeMethod = leakTraceClass.getMethod(HelperConfig.GET_GC_ROOT_TYPE_METHOD);
        getReferencePathMethod = leakTraceClass.getMethod(HelperConfig.GET_REFERENCE_PATH_METHOD);
        getLeakingObjectMethod = leakTraceClass.getMethod(HelperConfig.GET_LEAKING_OBJECT_METHOD);
        referencePathElementIsSuspectMethod =
                leakTraceClass.getMethod(
                        HelperConfig.REFERENCE_PATH_ELEMENT_IS_SUSPECT_METHOD, int.class);

        Class<?> referenceClass = Class.forName(HelperConfig.SHARK_LEAK_TRACE_REFERENCE_CLASS);
        getOriginObjectMethod = referenceClass.getMethod(HelperConfig.GET_ORIGIN_OBJECT_METHOD);
        getReferenceTypeMethod = referenceClass.getMethod(HelperConfig.GET_REFERENCE_TYPE_METHOD);
        getOwningClassSimpleNameMethod =
                referenceClass.getMethod(HelperConfig.GET_OWNING_CLASS_SIMPLE_NAME_METHOD);
        getReferenceNameMethod = referenceClass.getMethod(HelperConfig.GET_REFERENCE_NAME_METHOD);

        Class<?> objectClass = Class.forName(HelperConfig.SHARK_LEAK_TRACE_OBJECT_CLASS);
        getTypeMethod = objectClass.getMethod(HelperConfig.GET_TYPE_METHOD);
        getClassNameMethod = objectClass.getMethod(HelperConfig.GET_CLASS_NAME_METHOD);
        getLabelsMethod = objectClass.getMethod(HelperConfig.GET_LABELS_METHOD);
        getLeakingStatusMethod = objectClass.getMethod(HelperConfig.GET_LEAKING_STATUS_METHOD);
        getLeakingStatusReasonMethod =
                objectClass.getMethod(HelperConfig.GET_LEAKING_STATUS_REASON_METHOD);
        getRetainedHeapByteSizeMethod =
                objectClass.getMethod(HelperConfig.GET_RETAINED_HEAP_BYTE_SIZE_METHOD);
        getRetainedObjectCountMethod =
                objectClass.getMethod(HelperConfig.GET_NODE_RETAINED_OBJECT_COUNT_METHOD);

        Class<?> gcRootTypeClass = Class.forName(HelperConfig.SHARK_GC_ROOT_TYPE_CLASS);
        getGcRootDescriptionMethod = gcRootTypeClass.getMethod(HelperConfig.GET_DESCRIPTION_METHOD);

        // Enum classes: values are read with Enum.name(), but a missing class must still fail.
        Class.forName(HelperConfig.SHARK_REFERENCE_TYPE_CLASS);
        Class.forName(HelperConfig.SHARK_OBJECT_TYPE_CLASS);
        Class.forName(HelperConfig.SHARK_LEAKING_STATUS_CLASS);
    }

    /**
     * Converts a Shark {@code HeapAnalysis} into the proto.
     *
     * @throws IllegalStateException if the reflection start-up check did not pass.
     * @throws UnsupportedLeakCanaryException if the result has a value this library does not know,
     *     or is of an unknown type. This means the app uses a LeakCanary version we don't support.
     * @throws ReflectiveOperationException if a Shark call fails.
     */
    static LeakCanaryAnalysisData convert(Object heapAnalysis) throws ReflectiveOperationException {
        // Also makes the lookups above visible to this thread (volatile read).
        if (!LeakCanaryReflectionHelper.isInitialized()) {
            throw new IllegalStateException("LeakCanary reflection is not ready.");
        }
        if (heapAnalysisSuccessClass.isInstance(heapAnalysis)) {
            return convertSuccess(heapAnalysis);
        }
        if (heapAnalysisFailureClass.isInstance(heapAnalysis)) {
            return convertFailure(heapAnalysis);
        }
        throw new UnsupportedLeakCanaryException(
                "Unknown heap analysis type: "
                        + (heapAnalysis == null ? "null" : heapAnalysis.getClass().getName()));
    }

    private static LeakCanaryAnalysisData convertSuccess(Object success)
            throws ReflectiveOperationException {
        LeakCanaryAnalysisSuccess.Builder successBuilder = LeakCanaryAnalysisSuccess.newBuilder();
        Map<?, ?> metadata = (Map<?, ?>) getMetadataMethod.invoke(success);
        for (Map.Entry<?, ?> entry : metadata.entrySet()) {
            successBuilder.putMetadata((String) entry.getKey(), (String) entry.getValue());
        }
        // Application leaks first, then library leaks, like Studio.
        for (Object leak : (List<?>) getApplicationLeaksMethod.invoke(success)) {
            successBuilder.addLeaks(convertLeak(leak, Leak.LeakType.LEAK_TYPE_APPLICATION_LEAK));
        }
        for (Object leak : (List<?>) getLibraryLeaksMethod.invoke(success)) {
            successBuilder.addLeaks(convertLeak(leak, Leak.LeakType.LEAK_TYPE_LIBRARY_LEAK));
        }
        return withTimes(LeakCanaryAnalysisData.newBuilder(), success)
                .setSuccess(successBuilder)
                .build();
    }

    private static LeakCanaryAnalysisData convertFailure(Object failure)
            throws ReflectiveOperationException {
        Throwable exception = (Throwable) getExceptionMethod.invoke(failure);
        String message = exception.getMessage();
        StringWriter stackTrace = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTrace));
        LeakCanaryAnalysisFailure failureProto =
                LeakCanaryAnalysisFailure.newBuilder()
                        .setExceptionMessage(message != null ? message : exception.toString())
                        .setExceptionStackTrace(stackTrace.toString())
                        .build();
        return withTimes(LeakCanaryAnalysisData.newBuilder(), failure)
                .setFailure(failureProto)
                .build();
    }

    /** Copies the 3 times that both success and failure results have. */
    private static LeakCanaryAnalysisData.Builder withTimes(
            LeakCanaryAnalysisData.Builder builder, Object heapAnalysis)
            throws ReflectiveOperationException {
        return builder.setCreatedAtTimeMillis(
                        (Long) getCreatedAtTimeMillisMethod.invoke(heapAnalysis))
                .setDumpDurationMillis((Long) getDumpDurationMillisMethod.invoke(heapAnalysis))
                .setAnalysisDurationMillis(
                        (Long) getAnalysisDurationMillisMethod.invoke(heapAnalysis));
    }

    private static Leak convertLeak(Object leak, Leak.LeakType type)
            throws ReflectiveOperationException {
        List<?> traces = (List<?>) getLeakTracesMethod.invoke(leak);
        Leak.Builder builder =
                Leak.newBuilder()
                        .setType(type)
                        .setSignature((String) getSignatureMethod.invoke(leak))
                        .setLeakTraceCount(traces.size());
        // Unknown sizes stay unset, like Studio.
        Integer retainedBytes = (Integer) getTotalRetainedHeapByteSizeMethod.invoke(leak);
        if (retainedBytes != null && retainedBytes >= 0) {
            builder.setRetainedByteSize(retainedBytes);
        }
        for (Object trace : traces) {
            builder.addLeakTraces(convertTrace(trace));
        }
        return builder.build();
    }

    private static LeakTrace convertTrace(Object trace) throws ReflectiveOperationException {
        LeakTrace.Builder builder =
                LeakTrace.newBuilder()
                        .setGcRootType(toGcRootType(getGcRootTypeMethod.invoke(trace)));
        // Each object on the path gets the reference that leaves it. The leaking object is last
        // and has no reference.
        List<?> referencePath = (List<?>) getReferencePathMethod.invoke(trace);
        for (int i = 0; i < referencePath.size(); i++) {
            Object reference = referencePath.get(i);
            boolean isSuspect = (Boolean) referencePathElementIsSuspectMethod.invoke(trace, i);
            builder.addNodes(
                    convertNode(getOriginObjectMethod.invoke(reference), reference, isSuspect));
        }
        builder.addNodes(convertNode(getLeakingObjectMethod.invoke(trace), null, false));
        return builder.build();
    }

    private static LeakTraceNode convertNode(
            Object leakTraceObject, Object outgoingReference, boolean isSuspect)
            throws ReflectiveOperationException {
        LeakTraceNode.Builder builder =
                LeakTraceNode.newBuilder()
                        .setNodeType(toNodeType(getTypeMethod.invoke(leakTraceObject)))
                        .setClassName((String) getClassNameMethod.invoke(leakTraceObject))
                        .setLeakingStatus(
                                toLeakingStatus(getLeakingStatusMethod.invoke(leakTraceObject)))
                        .setLeakingStatusReason(
                                (String) getLeakingStatusReasonMethod.invoke(leakTraceObject));
        for (Object label : (Collection<?>) getLabelsMethod.invoke(leakTraceObject)) {
            builder.addNotes((String) label);
        }
        Integer retainedBytes = (Integer) getRetainedHeapByteSizeMethod.invoke(leakTraceObject);
        if (retainedBytes != null) {
            builder.setRetainedHeapSize(formatByteCount(retainedBytes));
        }
        Integer retainedCount = (Integer) getRetainedObjectCountMethod.invoke(leakTraceObject);
        if (retainedCount != null) {
            builder.setRetainedObjectCount(retainedCount);
        }
        if (outgoingReference != null) {
            builder.setReferencingField(
                    ReferencingField.newBuilder()
                            .setClassName(
                                    (String)
                                            getOwningClassSimpleNameMethod.invoke(
                                                    outgoingReference))
                            .setReferenceName(
                                    (String) getReferenceNameMethod.invoke(outgoingReference))
                            .setType(
                                    toReferencingFieldType(
                                            getReferenceTypeMethod.invoke(outgoingReference)))
                            .setIsLikelyCause(isSuspect));
        }
        return builder.build();
    }

    /**
     * Maps a GC root like Studio's SharkToDataAdapter: first by name (the 9 values of Shark 2.14),
     * then, for a value we don't know, by its description text. If that doesn't match either, it is
     * UNKNOWN. It never fails the result.
     */
    private static LeakTrace.GcRootType toGcRootType(Object sharkValue)
            throws ReflectiveOperationException {
        switch (enumName(sharkValue)) {
            case "JNI_GLOBAL":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_JNI_GLOBAL;
            case "JNI_LOCAL":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_JNI_LOCAL;
            case "JAVA_FRAME":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_JAVA_FRAME;
            case "NATIVE_STACK":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_NATIVE_STACK;
            case "STICKY_CLASS":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_STICKY_CLASS;
            case "THREAD_BLOCK":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_THREAD_BLOCK;
            case "MONITOR_USED":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_MONITOR_USED;
            case "THREAD_OBJECT":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_THREAD_OBJECT;
            case "JNI_MONITOR":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_JNI_MONITOR;
            default:
                return gcRootTypeFromDescription(
                        (String) getGcRootDescriptionMethod.invoke(sharkValue));
        }
    }

    /**
     * Same texts as Studio's GcRootType (leakcanarylib) and its fromDescription(). Keep both lists
     * the same.
     */
    static LeakTrace.GcRootType gcRootTypeFromDescription(String description) {
        if (description == null) return LeakTrace.GcRootType.GC_ROOT_TYPE_UNKNOWN;
        switch (description) {
            case "Global variable in native code":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_JNI_GLOBAL;
            case "Local variable in native code":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_JNI_LOCAL;
            case "Java local variable":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_JAVA_FRAME;
            case "Input or output parameters in native code":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_NATIVE_STACK;
            case "System class":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_STICKY_CLASS;
            case "Thread block":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_THREAD_BLOCK;
            case "Monitor (anything that called the wait() or notify() methods, or that is"
                    + " synchronized.)":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_MONITOR_USED;
            case "Thread object":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_THREAD_OBJECT;
            case "Root JNI monitor":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_JNI_MONITOR;
            case "Interned string":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_INTERNED_STRING;
            case "Object awaiting finalization":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_FINALIZING;
            case "Debugger reference":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_DEBUGGER;
            case "Reference cleanup":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_REFERENCE_CLEANUP;
            case "VM internal reference":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_VM_INTERNAL;
            case "Unreachable object":
                return LeakTrace.GcRootType.GC_ROOT_TYPE_UNREACHABLE;
            default:
                return LeakTrace.GcRootType.GC_ROOT_TYPE_UNKNOWN;
        }
    }

    private static LeakTraceNode.NodeType toNodeType(Object sharkValue) {
        String name = enumName(sharkValue);
        switch (name) {
            case "INSTANCE":
                return LeakTraceNode.NodeType.NODE_TYPE_INSTANCE;
            case "CLASS":
                return LeakTraceNode.NodeType.NODE_TYPE_CLASS;
            case "ARRAY":
                return LeakTraceNode.NodeType.NODE_TYPE_ARRAY;
            default:
                throw new UnsupportedLeakCanaryException("Unknown Shark ObjectType: " + name);
        }
    }

    private static LeakTraceNode.LeakingStatus toLeakingStatus(Object sharkValue) {
        String name = enumName(sharkValue);
        switch (name) {
            case "LEAKING":
                return LeakTraceNode.LeakingStatus.LEAKING_STATUS_YES;
            case "NOT_LEAKING":
                return LeakTraceNode.LeakingStatus.LEAKING_STATUS_NO;
            case "UNKNOWN":
                return LeakTraceNode.LeakingStatus.LEAKING_STATUS_UNKNOWN;
            default:
                throw new UnsupportedLeakCanaryException("Unknown Shark LeakingStatus: " + name);
        }
    }

    private static ReferencingField.ReferencingFieldType toReferencingFieldType(Object sharkValue) {
        String name = enumName(sharkValue);
        switch (name) {
            case "INSTANCE_FIELD":
                return ReferencingField.ReferencingFieldType.REFERENCING_FIELD_TYPE_INSTANCE_FIELD;
            case "STATIC_FIELD":
                return ReferencingField.ReferencingFieldType.REFERENCING_FIELD_TYPE_STATIC_FIELD;
            case "LOCAL":
                return ReferencingField.ReferencingFieldType.REFERENCING_FIELD_TYPE_LOCAL;
            case "ARRAY_ENTRY":
                return ReferencingField.ReferencingFieldType.REFERENCING_FIELD_TYPE_ARRAY_ENTRY;
            default:
                throw new UnsupportedLeakCanaryException("Unknown Shark ReferenceType: " + name);
        }
    }

    /** Returns the name of a Shark enum value. Throws if the value is not an enum. */
    private static String enumName(Object sharkValue) {
        if (!(sharkValue instanceof Enum)) {
            throw new UnsupportedLeakCanaryException("Expected a Shark enum value: " + sharkValue);
        }
        return ((Enum<?>) sharkValue).name();
    }

    /**
     * Formats a byte count like Studio does (e.g. "400 B", "1.2 kB"). Uses Locale.US, so the dot is
     * the same on every device.
     */
    static String formatByteCount(long bytes) {
        int unit = 1000;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        char prefix = "kMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(unit, exp), prefix);
    }

    /** Thrown when the result has a type or value this library does not know. */
    static final class UnsupportedLeakCanaryException extends RuntimeException {
        UnsupportedLeakCanaryException(String message) {
            super(message);
        }
    }
}
