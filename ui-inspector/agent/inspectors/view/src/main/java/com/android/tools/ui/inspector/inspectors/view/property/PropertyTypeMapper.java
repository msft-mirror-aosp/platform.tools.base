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

import android.view.inspector.PropertyMapper;

import com.android.tools.ui.inspector.inspectors.view.GravityIntMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * An implementation of the Android SDK framework's {@link PropertyMapper} interface.
 *
 * <p>Used by framework inspection companions to map property names to sequential integer IDs. It
 * accumulates property metadata (types, enums, and flags) into a list of {@link AttributeMetadata}.
 */
final class PropertyTypeMapper implements PropertyMapper {

    private final List<AttributeMetadata> mutableProperties;

    PropertyTypeMapper(List<AttributeMetadata> existingProperties) {
        mutableProperties = new ArrayList<>(existingProperties);
    }

    List<AttributeMetadata> getProperties() {
        return mutableProperties;
    }

    @Override
    public int mapBoolean(String name, int attributeId) {
        return map(name, attributeId, PropertyType.BOOLEAN, null, null);
    }

    @Override
    public int mapByte(String name, int attributeId) {
        return map(name, attributeId, PropertyType.BYTE, null, null);
    }

    @Override
    public int mapChar(String name, int attributeId) {
        return map(name, attributeId, PropertyType.CHAR, null, null);
    }

    @Override
    public int mapDouble(String name, int attributeId) {
        return map(name, attributeId, PropertyType.DOUBLE, null, null);
    }

    @Override
    public int mapFloat(String name, int attributeId) {
        return map(name, attributeId, PropertyType.FLOAT, null, null);
    }

    @Override
    public int mapInt(String name, int attributeId) {
        return map(name, attributeId, PropertyType.INT32, null, null);
    }

    @Override
    public int mapLong(String name, int attributeId) {
        return map(name, attributeId, PropertyType.INT64, null, null);
    }

    @Override
    public int mapShort(String name, int attributeId) {
        return map(name, attributeId, PropertyType.INT16, null, null);
    }

    @Override
    public int mapObject(String name, int attributeId) {
        return map(name, attributeId, PropertyType.OBJECT, null, null);
    }

    @Override
    public int mapColor(String name, int attributeId) {
        return map(name, attributeId, PropertyType.COLOR, null, null);
    }

    @Override
    public int mapResourceId(String name, int attributeId) {
        return map(name, attributeId, PropertyType.RESOURCE, null, null);
    }

    @Override
    public int mapGravity(String name, int attributeId) {
        return map(name, attributeId, PropertyType.GRAVITY, null, new GravityIntMapping());
    }

    @Override
    public int mapIntEnum(String name, int attributeId, IntFunction<String> mapping) {
        return map(name, attributeId, PropertyType.INT_ENUM, mapping, null);
    }

    @Override
    public int mapIntFlag(String name, int attributeId, IntFunction<Set<String>> mapping) {
        return map(name, attributeId, PropertyType.INT_FLAG, null, mapping);
    }

    /**
     * Internal helper that instantiates {@link AttributeMetadata}, registers it in the accumulated
     * list, and returns its sequential index position representing its mapped unique ID.
     */
    private int map(
            String name,
            int attributeId,
            PropertyType type,
            IntFunction<String> enumMapping,
            IntFunction<Set<String>> flagMapping) {
        int id = mutableProperties.size();
        mutableProperties.add(
                new AttributeMetadata(name, attributeId, type, enumMapping, flagMapping));
        return id;
    }
}
