/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tools.tracer.agent;

import com.android.tools.tracer.PerfettoTracer;
import com.android.tools.tracer.Tracing;
import com.android.tools.tracer.TracingConfigProvider;

import java.io.File;
import java.util.List;

public class TracerImpl implements Tracer {
    public static TraceProfile profile;
    public static final Tracer INSTANCE = new TracerImpl();

    private TracerImpl() {}

    @Override
    public void begin(String text) {
        PerfettoTracer.beginSectionWithMetadata("trace_agent", text, null);
    }

    @Override
    public void end() {
        PerfettoTracer.endSection();
    }

    @Override
    public void flush() {
        Tracing.flush();
    }

    @Override
    public void begin(long pid, long tid, long ns, String text) {
        PerfettoTracer.beginSectionWithMetadata("trace_agent", text, null);
    }

    @Override
    public void end(long pid, long tid, long ns) {
        PerfettoTracer.endSection();
    }

    @Override
    public void start() {
        if (profile == null) return;
        Tracing.initialize(
                new TracingConfigProvider() {
                    @Override
                    public boolean isTracingEnabled() {
                        return true;
                    }

                    @Override
                    public File getTraceDirectory() {
                        String dir = profile.getTraceOutputDirectory();
                        return dir != null ? new File(dir) : new File("/tmp/");
                    }

                    @Override
                    public long getRingBufferCapacity() {
                        return 0;
                    }
                });
    }

    @Override
    public void addVmArgs(List<String> args) {
        String jvmArgs = profile.getJvmArgs();
        if (!jvmArgs.isEmpty()) {
            args.add(jvmArgs);
        }
    }
}
