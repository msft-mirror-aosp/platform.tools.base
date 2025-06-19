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

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.deploy.asm.Label;
import com.android.deploy.asm.tree.AbstractInsnNode;
import com.android.deploy.asm.tree.LabelNode;
import com.android.deploy.asm.tree.LineNumberNode;
import com.android.deploy.asm.tree.LocalVariableNode;
import com.android.deploy.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ProxySourceLocation {
    private static final String FORMAT = "lambda=%s, file=%s, startLine=%d, endLine=%d";

    private final String internalName;
    private final String fileName;
    private final int startLine;
    private final int endLine;

    ProxySourceLocation(
            @NonNull String internalName, @NonNull String fileName, int startLine, int endLine) {
        this.internalName = internalName;
        this.fileName = fileName;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    Map<String, Object> asMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("lambda", internalName);
        map.put("file", fileName);
        map.put("startLine", startLine);
        map.put("endLine", endLine);
        return map;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(FORMAT, internalName, fileName, startLine, endLine);
    }

    @Nullable
    static ProxySourceLocation findSourceLocation(@NonNull Interpretable bytecode) {
        String internalName = bytecode.getInternalName().replace('/', '.');
        String fileName = bytecode.getFilename();
        int startLine = Integer.MAX_VALUE;
        int endLine = -1;
        for (MethodNode method : bytecode.getMethods()) {
            List<LocalVariableNode> localVariables = method.localVariables;
            Set<Label> startLabels = new HashSet<>();
            Set<Label> endLabels = new HashSet<>();
            for (LocalVariableNode variable : method.localVariables) {
                if (variable.name.startsWith("$i$f$")) {
                    // Any inlined methods in the lambda body will show up in the line
                    // number table as lines after the end of the source file.
                    // The Kotlin compiler insert place-holder local variables with
                    // names starting with "$i$f" in the inlined code sections. Use
                    // this to exclude the inlined line numbers from the lambda line
                    // range.
                    startLabels.add(variable.start.getLabel());
                    endLabels.add(variable.end.getLabel());
                }
            }
            int isInlined = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LabelNode) {
                    LabelNode node = (LabelNode) instruction;
                    if (endLabels.contains(node.getLabel())) {
                        isInlined++;
                    }
                    if (startLabels.contains(node.getLabel())) {
                        isInlined--;
                    }
                }
                if (instruction instanceof LineNumberNode && isInlined == 0) {
                    LineNumberNode lineNode = (LineNumberNode) instruction;
                    int line = lineNode.line;
                    startLine = Math.min(startLine, line);
                    endLine = Math.max(endLine, line);
                }
            }
        }
        if (startLine > endLine) {
            return null;
        }
        return new ProxySourceLocation(internalName, fileName, startLine, endLine);
    }
}
