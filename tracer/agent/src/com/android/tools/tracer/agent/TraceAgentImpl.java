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

import com.android.tools.tracer.Tracing;

import java.lang.instrument.Instrumentation;

public class TraceAgentImpl {
    public static void main(String args[]) {}

    public static void run(String agentArgs, Instrumentation inst) {
        TraceProfile profile = new TraceProfile(agentArgs);
        inst.addTransformer(new TraceTransformer(profile));
        TracerImpl.profile = profile;

        if (!profile.hasStartHooks() || profile.traceAgent()) {
            TracerImpl.INSTANCE.start();
        }

        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    if (TracerImpl.profile.traceAgent()) {
                                        TracerImpl.INSTANCE.end();
                                    }
                                    Tracing.close(true);
                                }));

        if (profile.traceAgent()) {
            TracerImpl.INSTANCE.begin("TraceAgent");
        }
    }
}
