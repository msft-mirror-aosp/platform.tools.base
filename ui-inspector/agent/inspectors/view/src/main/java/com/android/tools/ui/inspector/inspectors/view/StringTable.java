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

package com.android.tools.ui.inspector.inspectors.view;

import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A class which associates Strings with integers, where duplicate strings all share the same
 * numeric value.
 *
 * <p>This class exists to allow us to significantly shrink payloads that get sent to the host, as
 * lots of text is across the layout tree will be the same.
 */
public final class StringTable {
    private final Map<String, Integer> innerMap = new LinkedHashMap<>();

    public int put(String str) {
        if (str == null || str.isEmpty()) {
            return 0;
        }
        Integer id = innerMap.get(str);
        if (id != null) {
            return id;
        }
        int newId = innerMap.size() + 1;
        innerMap.put(str, newId);
        return newId;
    }

    /** Returns the string associated with {@code id}, or null when the table holds no such id. */
    public String getString(int id) {
        if (id == 0) {
            return "";
        }
        for (Map.Entry<String, Integer> entry : innerMap.entrySet()) {
            if (entry.getValue() == id) {
                return entry.getKey();
            }
        }
        return null;
    }

    public List<StringEntry> toStringEntries() {
        List<StringEntry> entries = new ArrayList<>(innerMap.size());
        for (Map.Entry<String, Integer> entry : innerMap.entrySet()) {
            entries.add(
                    StringEntry.newBuilder()
                            .setValue(entry.getKey())
                            .setId(entry.getValue())
                            .build());
        }
        return entries;
    }
}
