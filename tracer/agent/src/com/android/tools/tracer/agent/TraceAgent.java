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

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class TraceAgent {

    public static Tracer delegate;
    private static URLClassLoader cl;

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            File tempJar = File.createTempFile("trace_agent_impl", ".jar");
            tempJar.deleteOnExit();
            try (InputStream is =
                    TraceAgent.class.getResourceAsStream("/trace_agent_impl_deploy.jar")) {
                if (is == null) {
                    throw new RuntimeException(
                            "Could not find trace_agent_impl_deploy.jar as a resource.");
                }
                Files.copy(is, tempJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            cl = new URLClassLoader(new URL[] {tempJar.toURI().toURL()});
            cl.loadClass("com.android.tools.tracer.agent.TraceAgentImpl")
                    .getDeclaredMethod("run", String.class, Instrumentation.class)
                    .invoke(null, agentArgs, inst);
            Class<?> tracer = cl.loadClass("com.android.tools.tracer.agent.TracerImpl");
            delegate = (Tracer) tracer.getField("INSTANCE").get(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
