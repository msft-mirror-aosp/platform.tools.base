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

/**
 * Internal constants for the helper library.
 *
 * <p>This object holds configuration values, intent actions, and extra keys used for communication
 * between the Android Studio Profiler, the app, and the internal LeakCanary instance.
 */
final class HelperConfig {

    /**
     * The Logcat tag to use. We use "StudioLeakCanary" tag so the message appears in context with
     * other LeakCanary logs.
     */
    static final String LOG_TAG = "StudioLeakCanary";

    /**
     * This library's version, made from release_version.bzl. Studio and this library ship
     * separately, so Studio uses it to ask users with an old library to update.
     */
    static final String LIBRARY_VERSION = LibraryVersion.VERSION;

    /**
     * The fully qualified class name of LeakCanary's internal listener. Used for reflection to swap
     * the default listener with our custom one.
     */
    static final String LEAK_CANARY_INTERNAL_CLASS = "leakcanary.internal.InternalLeakCanary";

    /**
     * The broadcast action sent from ProfilerService when the host-side analysis of a heap dump is
     * complete.
     */
    static final String HEAP_DUMP_COMPLETE_INTENT = "studio.leakcanary.HEAP_DUMP_FINISHED";

    /** The key for the timestamp extra in the heap dump complete intent. */
    static final String HEAP_DUMP_COMPLETE_EXTRA = "heap_dump_timestamp";

    /**
     * The broadcast action sent by the app to notify Studio of an update in the retained object
     * count.
     */
    static final String OBJECT_COUNT_UPDATE_INTENT = "studio.leakcanary.OBJECT_COUNT_UPDATE";

    /** The key for the count extra in the object count update intent. */
    static final String OBJECT_COUNT_UPDATE_EXTRA = "count";

    /** The broadcast action sent by the app with LeakCanary's analysis progress (ON_DEVICE). */
    static final String ANALYSIS_PROGRESS_INTENT = "studio.leakcanary.ANALYSIS_PROGRESS";

    /** The key for the progress extra (0 to 100) in the analysis progress intent. */
    static final String ANALYSIS_PROGRESS_EXTRA = "percent";

    /** The broadcast action sent from Studio to the app to start listening for leaks. */
    static final String START_LISTENING_INTENT = "studio.leakcanary.START_LISTENING";

    /** The broadcast action sent from Studio to request the retained visible threshold. */
    static final String GET_THRESHOLD_INTENT = "studio.leakcanary.GET_THRESHOLD";

    /** The broadcast action sent from the app containing the threshold result. */
    static final String THRESHOLD_RESULT_INTENT = "studio.leakcanary.THRESHOLD_RESULT";

    /** The broadcast action sent from Studio to trigger a force dump on device. */
    static final String FORCE_DUMP_ON_DEVICE_INTENT = "studio.leakcanary.FORCE_DUMP_ON_DEVICE";

    /** The key for the threshold extra in the result intent. */
    static final String THRESHOLD_EXTRA = "threshold";

    /** The key for the library version extra in the threshold result intent. */
    static final String VERSION_EXTRA = "studio_leakcanary_version";

    /**
     * The broadcast action sent by the app when LeakCanary's analysis result is saved to a file
     * (ON_DEVICE).
     */
    static final String ANALYSIS_RESULT_INTENT = "studio.leakcanary.ANALYSIS_RESULT";

    /** The key for the full file path extra in the analysis result intent. */
    static final String ANALYSIS_RESULT_FILE_PATH_EXTRA = "file_path";

    /**
     * The file in the app's cache folder that holds the latest ON_DEVICE analysis result, as a
     * LeakCanaryAnalysisData proto. Always the same name, so each result replaces the last one.
     */
    static final String ON_DEVICE_ANALYSIS_FILE_NAME = "studio_leakcanary_on_device_analysis.pb";

    /** The broadcast action sent by the app when something fails on the device (ON_DEVICE). */
    static final String DEVICE_ERROR_INTENT = "studio.leakcanary.DEVICE_ERROR";

    /** The key for the error code extra in the device error intent. */
    static final String DEVICE_ERROR_CODE_EXTRA = "error_code";

    // Device error codes. They must match LeakCanaryDeviceError.ErrorType in common.proto. This
    // library does not include common.proto, so it sends the plain numbers.

    /** The analysis result could not be converted: the LeakCanary version is not supported. */
    static final int ERROR_CODE_UNSUPPORTED_VERSION = 4;

    /** LeakCanary failed to dump the heap on the device, and will not try again. */
    static final int ERROR_CODE_ON_DEVICE_HEAP_DUMP_FAILED = 5;

    /**
     * The analysis result could not be saved to a file on the device (for example, the storage is
     * full).
     */
    static final int ERROR_CODE_ON_DEVICE_SAVE_FAILED = 6;

    /**
     * Suffix for the internal permission used to protect broadcast receivers on API < 33. The full
     * permission is usually "${applicationId}" + INTERNAL_PERMISSION_SUFFIX.
     */
    static final String INTERNAL_PERMISSION_SUFFIX = ".permission.LEAK_CANARY_INTERNAL";

    // Reflection Constants
    //
    // Every LeakCanary class this library reaches through reflection must have a *_CLASS constant
    // here, including classes only reached through getClass() (e.g. ObjectWatcher,
    // LeakCanary$Config), and a matching -keep rule in proguard-rules.pro. ProguardRulesTest reads
    // these constants and fails if a rule is missing, so apps that minify their debug build keep
    // working (b/512795977).

    /** Runtime class of LeakCanary.getConfig(); reached through getClass(). */
    static final String LEAK_CANARY_CONFIG_CLASS = "leakcanary.LeakCanary$Config";

    /** Runtime class of AppWatcher.getObjectWatcher(); reached through getClass(). */
    static final String OBJECT_WATCHER_CLASS = "leakcanary.ObjectWatcher";

    static final String LEAK_CANARY_CLASS = "leakcanary.LeakCanary";
    static final String INSTANCE_FIELD = "INSTANCE";
    static final String GET_CONFIG_METHOD = "getConfig";
    static final String DUMP_HEAP_FIELD = "dumpHeap";
    static final String DUMP_HEAP_METHOD = "dumpHeap";
    static final String ON_OBJECT_RETAINED_LISTENER_CLASS = "leakcanary.OnObjectRetainedListener";
    static final String APP_WATCHER_CLASS = "leakcanary.AppWatcher";
    static final String GET_OBJECT_WATCHER_METHOD = "getObjectWatcher";
    static final String REMOVE_ON_OBJECT_RETAINED_LISTENER_METHOD =
            "removeOnObjectRetainedListener";
    static final String ADD_ON_OBJECT_RETAINED_LISTENER_METHOD = "addOnObjectRetainedListener";
    static final String ON_OBJECT_RETAINED_METHOD = "onObjectRetained";
    static final String TO_STRING_METHOD = "toString";
    static final String HASH_CODE_METHOD = "hashCode";
    static final String EQUALS_METHOD = "equals";
    static final String GET_RETAINED_OBJECT_COUNT_METHOD = "getRetainedObjectCount";
    static final String CLEAR_OBJECTS_WATCHED_BEFORE_METHOD = "clearObjectsWatchedBefore";
    static final String GC_TRIGGER_DEFAULT_CLASS = "leakcanary.GcTrigger$Default";
    static final String RUN_GC_METHOD = "runGc";
    static final String GET_RETAINED_VISIBLE_THRESHOLD_METHOD = "getRetainedVisibleThreshold";
    static final String EVENT_LISTENER_CLASS = "leakcanary.EventListener";
    static final String HEAP_ANALYSIS_PROGRESS_CLASS =
            "leakcanary.EventListener$Event$HeapAnalysisProgress";
    static final String EVENT_LISTENERS_FIELD = "eventListeners";
    static final String ON_EVENT_METHOD = "onEvent";
    static final String GET_PROGRESS_PERCENT_METHOD = "getProgressPercent";

    // LeakCanary events for the analysis result and a failed heap dump (ON_DEVICE).
    static final String HEAP_ANALYSIS_DONE_CLASS =
            "leakcanary.EventListener$Event$HeapAnalysisDone";
    static final String GET_HEAP_ANALYSIS_METHOD = "getHeapAnalysis";
    static final String HEAP_DUMP_FAILED_CLASS = "leakcanary.EventListener$Event$HeapDumpFailed";
    static final String GET_WILL_RETRY_LATER_METHOD = "getWillRetryLater";
    static final String GET_HEAP_DUMP_FAILED_EXCEPTION_METHOD = "getException";

    // Shark classes in LeakCanary's analysis result, read by SharkResultConverter. Enum values are
    // read with Enum.name(). GcRootType also uses getDescription() for values we don't know.
    static final String SHARK_HEAP_ANALYSIS_CLASS = "shark.HeapAnalysis";
    static final String GET_CREATED_AT_TIME_MILLIS_METHOD = "getCreatedAtTimeMillis";
    static final String GET_DUMP_DURATION_MILLIS_METHOD = "getDumpDurationMillis";
    static final String GET_ANALYSIS_DURATION_MILLIS_METHOD = "getAnalysisDurationMillis";

    static final String SHARK_HEAP_ANALYSIS_SUCCESS_CLASS = "shark.HeapAnalysisSuccess";
    static final String GET_METADATA_METHOD = "getMetadata";
    static final String GET_APPLICATION_LEAKS_METHOD = "getApplicationLeaks";
    static final String GET_LIBRARY_LEAKS_METHOD = "getLibraryLeaks";

    static final String SHARK_HEAP_ANALYSIS_FAILURE_CLASS = "shark.HeapAnalysisFailure";
    static final String GET_EXCEPTION_METHOD = "getException";

    static final String SHARK_LEAK_CLASS = "shark.Leak";
    static final String GET_LEAK_TRACES_METHOD = "getLeakTraces";
    static final String GET_SIGNATURE_METHOD = "getSignature";
    static final String GET_TOTAL_RETAINED_HEAP_BYTE_SIZE_METHOD = "getTotalRetainedHeapByteSize";

    static final String SHARK_LEAK_TRACE_CLASS = "shark.LeakTrace";
    static final String GET_GC_ROOT_TYPE_METHOD = "getGcRootType";
    static final String GET_REFERENCE_PATH_METHOD = "getReferencePath";
    static final String GET_LEAKING_OBJECT_METHOD = "getLeakingObject";
    static final String REFERENCE_PATH_ELEMENT_IS_SUSPECT_METHOD = "referencePathElementIsSuspect";
    static final String SHARK_GC_ROOT_TYPE_CLASS = "shark.LeakTrace$GcRootType";
    static final String GET_DESCRIPTION_METHOD = "getDescription";

    static final String SHARK_LEAK_TRACE_REFERENCE_CLASS = "shark.LeakTraceReference";
    static final String GET_ORIGIN_OBJECT_METHOD = "getOriginObject";
    static final String GET_REFERENCE_TYPE_METHOD = "getReferenceType";
    static final String GET_OWNING_CLASS_SIMPLE_NAME_METHOD = "getOwningClassSimpleName";
    static final String GET_REFERENCE_NAME_METHOD = "getReferenceName";
    static final String SHARK_REFERENCE_TYPE_CLASS = "shark.LeakTraceReference$ReferenceType";

    static final String SHARK_LEAK_TRACE_OBJECT_CLASS = "shark.LeakTraceObject";
    static final String GET_TYPE_METHOD = "getType";
    static final String GET_CLASS_NAME_METHOD = "getClassName";
    static final String GET_LABELS_METHOD = "getLabels";
    static final String GET_LEAKING_STATUS_METHOD = "getLeakingStatus";
    static final String GET_LEAKING_STATUS_REASON_METHOD = "getLeakingStatusReason";
    static final String GET_RETAINED_HEAP_BYTE_SIZE_METHOD = "getRetainedHeapByteSize";
    static final String GET_NODE_RETAINED_OBJECT_COUNT_METHOD = "getRetainedObjectCount";
    static final String SHARK_OBJECT_TYPE_CLASS = "shark.LeakTraceObject$ObjectType";
    static final String SHARK_LEAKING_STATUS_CLASS = "shark.LeakTraceObject$LeakingStatus";

    /** Default fallback value for the retained visible threshold. */
    static final int DEFAULT_THRESHOLD = 5;

    /** Value returned to Studio when LeakCanary reflection fails (e.g., due to minification). */
    static final int REFLECTION_FAILED_THRESHOLD = -1;

    /** Returned by getHeapAnalysisProgressPercent() for other events or on reflection errors. */
    static final int NO_ANALYSIS_PROGRESS = -1;

    /** Private constructor to prevent instantiation of this utility class. */
    private HelperConfig() {}
}
