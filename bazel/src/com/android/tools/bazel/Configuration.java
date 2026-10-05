/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.bazel;

import com.android.tools.bazel.ir.IrModule;
import com.android.tools.bazel.model.BazelRule;

import java.nio.file.Path;
import java.util.List;

public class Configuration {

    /**
     * Directories (relative to the workspace root) containing JPS modules that are still allowed
     * to have both production and test source roots. All other modules must have either
     * production roots or test roots, but not both (b/565759009).
     */
    private static final List<String> MIXED_SOURCE_ROOTS_ALLOWLIST = List.of("tools/vendor/google");

    public boolean dryRun;
    public String imlGraph;

    public String nameRule(String pkg, String rel, String name) {
        String prefix = "";
        if (rel.startsWith("tools/base")) {
            prefix = "studio.";
        } else if (rel.startsWith("tools/data-binding")) {
            prefix = "studio.";
            if (name.startsWith("db-")) {
                name = name.replace("db-", "");
            }
        }

        return prefix + name;
    }

    public boolean ignoreModule(Path workspace, IrModule module) {
        Path rel = workspace.relativize(module.getBaseDir());
        return rel.startsWith("tools/vendor/google3/blaze/");
    }

    public boolean allowMixedSourceRoots(Path workspace, IrModule module) {
        Path rel = workspace.relativize(module.getBaseDir());
        return MIXED_SOURCE_ROOTS_ALLOWLIST.stream().anyMatch(rel::startsWith);
    }
}
