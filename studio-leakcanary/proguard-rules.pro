# Copyright (C) 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Consumer R8/ProGuard rules for studio-leakcanary.
#
# These rules are packaged into the AAR and applied to every app that uses this library.
# studio-leakcanary reaches LeakCanary internals only through reflection (see HelperConfig and
# LeakCanaryReflectionHelper). If an app minifies its debug build, R8 would otherwise remove or
# rename these classes and members, and the reflection would fail (b/512795977).
#
# LeakCanary is expected to be debug-only (debugImplementation), so these rules don't affect
# release size.
#
# When adding new reflection in this library, add a *_CLASS constant to HelperConfig and a
# matching -keep rule here. ProguardRulesTest checks that the two stay in sync.

# LeakCanary.INSTANCE, getConfig(), dumpHeap()
-keep class leakcanary.LeakCanary { *; }

# Config.dumpHeap (field), getRetainedVisibleThreshold()
-keep class leakcanary.LeakCanary$Config { *; }

# InternalLeakCanary.INSTANCE (LeakCanary's default OnObjectRetainedListener)
-keep class leakcanary.internal.InternalLeakCanary { *; }

# AppWatcher.INSTANCE, getObjectWatcher()
-keep class leakcanary.AppWatcher { *; }

# getRetainedObjectCount(), clearObjectsWatchedBefore(long),
# addOnObjectRetainedListener(...), removeOnObjectRetainedListener(...)
-keep class leakcanary.ObjectWatcher { *; }

# Implemented by StudioLeakCanaryListener through java.lang.reflect.Proxy (onObjectRetained()).
-keep interface leakcanary.OnObjectRetainedListener { *; }

# GcTrigger.Default.INSTANCE, runGc()
-keep class leakcanary.GcTrigger$Default { *; }

# Proto classes made from leakcanary_data.proto. Protobuf reads their fields by name, so R8 must not
# remove or rename those fields. protobuf-javalite has no such rule of its own. Only our classes
# are kept; the app's own protos are not affected.
-keepclassmembers class com.android.tools.profiler.proto.LeakCanary$*
        extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
