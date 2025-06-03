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

package com.android.tools.binaries;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/** A tool to filter out jar contents and keep only specific packages. */
public class JarFilter {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println(
                    "Usage: JarFilter <input.jar> <output.jar> <package1> [package2] ...");
            System.exit(1);
        }

        String inputJar = args[0];
        String outputJar = args[1];
        Set<String> keepPackages = new HashSet<>(Collections.singleton("META-INF"));
        for (int i = 2; i < args.length; i++) {
            keepPackages.add(args[i].replace('.', '/'));
        }

        try (JarInputStream in = new JarInputStream(new FileInputStream(inputJar));
                JarOutputStream out = new JarOutputStream(new FileOutputStream(outputJar))) {

            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                String name = entry.getName();

                boolean shouldKeep = false;
                for (String pkg : keepPackages) {
                    if (name.startsWith(pkg + "/")) {
                        shouldKeep = true;
                        break;
                    }
                }

                if (shouldKeep) {
                    out.putNextEntry(new JarEntry(entry.getName()));
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    out.closeEntry();
                }
                in.closeEntry();
            }
        }
    }
}
