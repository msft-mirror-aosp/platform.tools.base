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
package com.android.tools.tracer.agent;

import java.util.List;

/**
 * Interface used to bridge tracing calls between the system classloader (which loads TraceAgent)
 * and the child URLClassLoader (which loads trace_agent_impl_deploy.jar and Tracer). This prevents
 * the need to use reflection on every instrumented function call.
 */
public interface Tracer {
    void begin(String text);

    void end();

    void flush();

    void begin(long pid, long tid, long ns, String text);

    void end(long pid, long tid, long ns);

    void start();

    void addVmArgs(List<String> args);
}
