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

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.inspector.PropertyReader;

import com.android.tools.ui.inspector.inspectors.view.StringTable;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.Attribute;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * An implementation of the Android SDK framework's {@link PropertyReader} interface.
 *
 * <p>Resolves and streams property values from framework companions directly to Attribute proto,
 * avoiding intermediate object allocations.
 */
public final class ProtoAttributeReader implements PropertyReader {
    private final View view;
    private final List<AttributeMetadata> properties;
    private final StringTable stringTable;
    private final boolean includeResolutionStack;
    private final Consumer<Attribute> onAttributeResolved;

    /**
     * Cached copy of the view's attribute source resource map. We cache it here to avoid calling
     * the expensive framework method {@code View.getAttributeSourceResourceMap()} (which creates a
     * new map on every call) for every property.
     */
    private final Map<Integer, Integer> resourceMap;

    /**
     * @param view The target {@link View} instance from which properties are being read.
     * @param properties The metadata list describing the properties being read.
     * @param stringTable The lookup table used to deduplicate and intern attribute names and
     *     values.
     * @param includeResolutionStack Whether to include the attribute resolution stack. Requires
     *     debug_view_attributes flag to be enabled on the device.
     * @param onAttributeResolved Callback invoked when a property is successfully resolved and
     *     converted to a {@link Attribute} proto.
     */
    public ProtoAttributeReader(
            View view,
            List<AttributeMetadata> properties,
            StringTable stringTable,
            boolean includeResolutionStack,
            Consumer<Attribute> onAttributeResolved) {
        this.view = view;
        this.properties = properties;
        this.stringTable = stringTable;
        this.includeResolutionStack = includeResolutionStack;
        this.onAttributeResolved = onAttributeResolved;
        this.resourceMap = view.getAttributeSourceResourceMap();
    }

    @Override
    public void readBoolean(int id, boolean b) {
        emit(id, b);
    }

    @Override
    public void readByte(int id, byte b) {
        emit(id, (int) b);
    }

    @Override
    public void readChar(int id, char c) {
        emit(id, (int) c);
    }

    @Override
    public void readDouble(int id, double d) {
        emit(id, d);
    }

    @Override
    public void readFloat(int id, float f) {
        emit(id, f);
    }

    @Override
    public void readInt(int id, int i) {
        emit(id, i);
    }

    @Override
    public void readLong(int id, long l) {
        emit(id, l);
    }

    @Override
    public void readShort(int id, short s) {
        emit(id, (int) s);
    }

    @Override
    public void readObject(int id, Object o) {
        if (o instanceof ColorStateList) {
            ColorStateList colorStateList = (ColorStateList) o;
            emit(
                    id,
                    colorStateList.getColorForState(
                            view.getDrawableState(), colorStateList.getDefaultColor()));
        } else if (o instanceof ColorDrawable) {
            emit(id, ((ColorDrawable) o).getColor());
        } else {
            emit(id, o);
        }
    }

    @Override
    public void readColor(int id, int color) {
        emit(id, color);
    }

    @Override
    public void readColor(int id, long color) {
        emit(id, Color.toArgb(color));
    }

    @Override
    public void readColor(int id, Color color) {
        emit(id, color != null ? color.toArgb() : null);
    }

    @Override
    public void readGravity(int id, int value) {
        readIntFlag(id, value);
    }

    @Override
    public void readIntEnum(int id, int value) {
        AttributeMetadata metadata = getMetadata(id);
        if (metadata == null) {
            return;
        }
        String mappedValue =
                metadata.getEnumMapping() != null ? metadata.getEnumMapping().apply(value) : null;
        emit(id, mappedValue != null ? mappedValue : value);
    }

    @Override
    public void readIntFlag(int id, int value) {
        AttributeMetadata metadata = getMetadata(id);
        if (metadata == null || metadata.getFlagMapping() == null) {
            return;
        }
        emit(id, metadata.getFlagMapping().apply(value));
    }

    @Override
    public void readResourceId(int id, int value) {
        emit(id, value);
    }

    private void emit(int id, Object value) {
        if (value == null) {
            return;
        }
        AttributeMetadata metadata = getMetadata(id);
        if (metadata == null) {
            return;
        }
        onAttributeResolved.accept(
                AttributeProtoConverter.toProtoAttribute(
                        metadata, stringTable, view, value, resourceMap, includeResolutionStack));
    }

    private AttributeMetadata getMetadata(int id) {
        return id >= 0 && id < properties.size() ? properties.get(id) : null;
    }
}
