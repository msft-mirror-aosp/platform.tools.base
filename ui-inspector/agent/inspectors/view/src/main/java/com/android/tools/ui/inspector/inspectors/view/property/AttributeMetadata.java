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

package com.android.tools.ui.inspector.inspectors.view.property;

import java.util.Set;
import java.util.function.IntFunction;

/** Metadata associated with an attribute. */
public final class AttributeMetadata {
    private final String name;
    private final int attributeId;
    private final PropertyType type;
    private final IntFunction<String> enumMapping;
    private final IntFunction<Set<String>> flagMapping;

    public AttributeMetadata(
            String name,
            int attributeId,
            PropertyType type,
            IntFunction<String> enumMapping,
            IntFunction<Set<String>> flagMapping) {
        this.name = name;
        this.attributeId = attributeId;
        this.type = type;
        this.enumMapping = enumMapping;
        this.flagMapping = flagMapping;
    }

    /** The name of the property (e.g., {@code "visibility"}). */
    public String getName() {
        return name;
    }

    /** The sequential index ID assigned by the mapper to this property. */
    public int getAttributeId() {
        return attributeId;
    }

    /** The categorized type of the property, guiding its formatting. */
    public PropertyType getType() {
        return type;
    }

    /**
     * Mapping function used to resolve raw integer enum values into human-readable strings, or null
     * when the property has none.
     */
    public IntFunction<String> getEnumMapping() {
        return enumMapping;
    }

    /**
     * Mapping function used to resolve bitmask integer values into sets of string flags, or null
     * when the property has none.
     */
    public IntFunction<Set<String>> getFlagMapping() {
        return flagMapping;
    }
}
