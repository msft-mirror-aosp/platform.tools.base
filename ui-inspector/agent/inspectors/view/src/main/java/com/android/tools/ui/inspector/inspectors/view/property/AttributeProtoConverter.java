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

import android.view.View;

import com.android.tools.ui.inspector.inspectors.view.ResourceIds;
import com.android.tools.ui.inspector.inspectors.view.StringTable;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.Attribute;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts a resolved framework property value into a simplified {@link Attribute} proto message.
 */
final class AttributeProtoConverter {
    private AttributeProtoConverter() {}

    /**
     * Resolves the framework value described by {@code metadata} and builds an {@link Attribute}
     * proto message.
     */
    static Attribute toProtoAttribute(
            AttributeMetadata metadata,
            StringTable stringTable,
            View view,
            Object value,
            Map<Integer, Integer> sourceMap,
            boolean includeResolutionStack,
            PropertyType typeOverride) {
        Attribute.Builder builder =
                Attribute.newBuilder().setName(stringTable.put(metadata.getName()));
        String name = metadata.getName();
        boolean isLayoutSize = name.equals("layout_width") || name.equals("layout_height");
        boolean isNegativeLayoutSize =
                isLayoutSize && value instanceof Number && ((Number) value).intValue() < 0;

        Attribute.Type protoType;
        if (typeOverride != null) {
            protoType = toProtoType(typeOverride);
        } else if (isLayoutSize && value instanceof Number && !isNegativeLayoutSize) {
            // Platform companions map layout_width/height to INT_ENUM. For positive sizes, override
            // this to DIMENSION.
            protoType = Attribute.Type.DIMENSION;
        } else if (isNegativeLayoutSize) {
            // Keep negative sizes (e.g. -1, -2) mapped as INT_ENUM to support MATCH_PARENT and
            // WRAP_CONTENT, in case the platform's enum mapping
            // failed to resolve them to strings (e.g. due to custom layout parameters or companions
            // stripped by Proguard/R8).
            protoType = Attribute.Type.INT_ENUM;
        } else {
            protoType = toProtoType(metadata.getType());
        }
        builder.setType(protoType);

        // Populate value based on type
        switch (protoType) {
            case STRING:
                builder.setInt32Value(
                        stringTable.put(
                                value instanceof String ? (String) value : value.toString()));
                break;
            case BOOLEAN:
                {
                    boolean boolVal;
                    if (value instanceof Boolean) {
                        boolVal = (Boolean) value;
                    } else if (value instanceof Number) {
                        boolVal = ((Number) value).intValue() != 0;
                    } else {
                        boolVal = Boolean.parseBoolean(value.toString());
                    }
                    builder.setInt32Value(boolVal ? 1 : 0);
                    break;
                }
            case INT32:
            case INT16:
            case BYTE:
            case CHAR:
                {
                    Integer intVal;
                    if (value instanceof Number) {
                        intVal = ((Number) value).intValue();
                    } else if (value instanceof Character) {
                        intVal = (int) (char) (Character) value;
                    } else {
                        intVal = parseIntOrNull(value.toString());
                    }
                    builder.setInt32Value(intVal != null ? intVal : 0);
                    break;
                }
            case INT64:
                {
                    Long longVal =
                            value instanceof Number
                                    ? ((Number) value).longValue()
                                    : parseLongOrNull(value.toString());
                    builder.setInt64Value(longVal != null ? longVal : 0L);
                    break;
                }
            case DOUBLE:
                {
                    Double doubleVal =
                            value instanceof Number
                                    ? ((Number) value).doubleValue()
                                    : parseDoubleOrNull(value.toString());
                    builder.setDoubleValue(doubleVal != null ? doubleVal : 0.0);
                    break;
                }
            case FLOAT:
            case DIMENSION:
                {
                    Float floatVal =
                            value instanceof Number
                                    ? ((Number) value).floatValue()
                                    : parseFloatOrNull(value.toString());
                    builder.setFloatValue(floatVal != null ? floatVal : 0f);
                    break;
                }
            case COLOR:
                {
                    Integer colorVal =
                            value instanceof Number
                                    ? ((Number) value).intValue()
                                    : parseIntOrNull(value.toString());
                    builder.setInt32Value(colorVal != null ? colorVal : 0);
                    break;
                }
            case INT_ENUM:
                {
                    String stringVal;
                    if (value instanceof String) {
                        stringVal = (String) value;
                    } else if (isNegativeLayoutSize) {
                        int size = ((Number) value).intValue();
                        if (size == -1) {
                            stringVal = "MATCH_PARENT";
                        } else if (size == -2) {
                            stringVal = "WRAP_CONTENT";
                        } else {
                            stringVal = value.toString();
                        }
                    } else {
                        stringVal = value.toString();
                    }
                    builder.setInt32Value(stringTable.put(stringVal));
                    break;
                }
            case GRAVITY:
            case INT_FLAG:
                {
                    String flagsString =
                            value instanceof Set ? joinFlags((Set<?>) value) : value.toString();
                    builder.setInt32Value(stringTable.put(flagsString));
                    break;
                }
            case RESOURCE:
                {
                    String resourceString;
                    if (value instanceof Integer) {
                        String resolved =
                                ResourceIds.resolveResourceToString(view, (Integer) value);
                        resourceString =
                                resolved != null
                                        ? resolved
                                        : String.format(Locale.US, "0x%08X", (Integer) value);
                    } else {
                        resourceString = value.toString();
                    }
                    builder.setInt32Value(stringTable.put(resourceString));
                    break;
                }
            case DRAWABLE:
            case ANIM:
            case ANIMATOR:
            case INTERPOLATOR:
                builder.setInt32Value(stringTable.put(value.getClass().getName()));
                break;
            default:
                builder.setInt32Value(stringTable.put(value.toString()));
                break;
        }

        // Set direct source if present in the map
        Integer sourceResId = sourceMap.get(metadata.getAttributeId());
        if (sourceResId != null) {
            String resourceStr = ResourceIds.resolveResourceToString(view, sourceResId);
            if (resourceStr != null) {
                builder.setDirectSource(stringTable.put(resourceStr));
            }
        }

        if (includeResolutionStack) {
            // Requires debug_view_attributes flag to be enabled on the device to return non-empty
            // stacks.
            int[] stack = view.getAttributeResolutionStack(metadata.getAttributeId());
            for (int resId : stack) {
                String resourceStr = ResourceIds.resolveResourceToString(view, resId);
                if (resourceStr != null) {
                    builder.addStyleChain(stringTable.put(resourceStr));
                }
            }
        }

        return builder.build();
    }

    private static String joinFlags(Set<?> flags) {
        StringBuilder joined = new StringBuilder();
        for (Object flag : flags) {
            if (joined.length() > 0) {
                joined.append('|');
            }
            joined.append(flag);
        }
        return joined.toString();
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLongOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleOrNull(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Float parseFloatOrNull(String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Attribute.Type toProtoType(PropertyType type) {
        switch (type) {
            case UNSPECIFIED:
                return Attribute.Type.UNSPECIFIED;
            case STRING:
                return Attribute.Type.STRING;
            case BOOLEAN:
                return Attribute.Type.BOOLEAN;
            case BYTE:
                return Attribute.Type.BYTE;
            case CHAR:
                return Attribute.Type.CHAR;
            case DOUBLE:
                return Attribute.Type.DOUBLE;
            case FLOAT:
                return Attribute.Type.FLOAT;
            case INT16:
                return Attribute.Type.INT16;
            case INT32:
                return Attribute.Type.INT32;
            case INT64:
                return Attribute.Type.INT64;
            case OBJECT:
                return Attribute.Type.OBJECT;
            case COLOR:
                return Attribute.Type.COLOR;
            case GRAVITY:
                return Attribute.Type.GRAVITY;
            case INT_ENUM:
                return Attribute.Type.INT_ENUM;
            case INT_FLAG:
                return Attribute.Type.INT_FLAG;
            case RESOURCE:
                return Attribute.Type.RESOURCE;
            case DRAWABLE:
                return Attribute.Type.DRAWABLE;
            case ANIM:
                return Attribute.Type.ANIM;
            case ANIMATOR:
                return Attribute.Type.ANIMATOR;
            case INTERPOLATOR:
                return Attribute.Type.INTERPOLATOR;
            case DIMENSION:
                return Attribute.Type.DIMENSION;
        }
        throw new AssertionError(type);
    }
}
