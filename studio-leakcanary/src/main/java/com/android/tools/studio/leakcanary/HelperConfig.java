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

    /** The broadcast action sent from Studio to the app to start listening for leaks. */
    static final String START_LISTENING_INTENT = "studio.leakcanary.START_LISTENING";

    /** The broadcast action sent from Studio to request the retained visible threshold. */
    static final String GET_THRESHOLD_INTENT = "studio.leakcanary.GET_THRESHOLD";

    /** The broadcast action sent from the app containing the threshold result. */
    static final String THRESHOLD_RESULT_INTENT = "studio.leakcanary.THRESHOLD_RESULT";

    /** The key for the threshold extra in the result intent. */
    static final String THRESHOLD_EXTRA = "threshold";

    /**
     * Suffix for the internal permission used to protect broadcast receivers on API < 33. The full
     * permission is usually "${applicationId}" + INTERNAL_PERMISSION_SUFFIX.
     */
    static final String INTERNAL_PERMISSION_SUFFIX = ".permission.LEAK_CANARY_INTERNAL";

    // Reflection Constants
    static final String LEAK_CANARY_CLASS = "leakcanary.LeakCanary";
    static final String INSTANCE_FIELD = "INSTANCE";
    static final String GET_CONFIG_METHOD = "getConfig";
    static final String DUMP_HEAP_FIELD = "dumpHeap";
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

    /** Private constructor to prevent instantiation of this utility class. */
    private HelperConfig() {}
}
