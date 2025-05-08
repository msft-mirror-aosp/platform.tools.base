/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.tools.deploy.liveedit;

import com.google.common.io.ByteStreams;

import java.io.File;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

class LambdaUtils {

    static String loadClassAndLambdaClasses(Class<?> klass) throws Exception {
        String testClass = klass.getName().replace('.', '/');
        LiveEditStubs.init(klass.getClassLoader());
        JarFile jar =
                new JarFile(
                        new File(
                                klass.getProtectionDomain().getCodeSource().getLocation().toURI()));
        List<JarEntry> files =
                jar.stream()
                        .filter(entry -> entry.getName().endsWith(".class"))
                        .collect(Collectors.toList());
        for (JarEntry entry : files) {
            if (entry.getName().equals(testClass + ".class")) {
                byte[] classData = ByteStreams.toByteArray(jar.getInputStream(entry));
                LiveEditStubs.addClass(testClass, new Interpretable(classData), false);
            }
            if (entry.getName().startsWith(testClass + "$")) {
                String internalName =
                        entry.getName().substring(0, entry.getName().length() - ".class".length());
                byte[] classData = ByteStreams.toByteArray(jar.getInputStream(entry));
                LiveEditStubs.addClass(internalName, new Interpretable(classData), true);
            }
        }
        return testClass;
    }
}
