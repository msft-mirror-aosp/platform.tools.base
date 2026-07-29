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

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.InspectionCompanion;

import com.android.tools.ui.inspector.inspectors.view.property.PropertyCache;
import com.android.tools.ui.inspector.inspectors.view.property.ProtoAttributeReader;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Display;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.WindowInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Converts a view hierarchy and its display context into their proto representations. */
final class ViewNodes {
    private ViewNodes() {}

    /** Models the configuration for attribute extraction; null wherever extraction is disabled. */
    private static final class AttributeExtraction {
        final PropertyCache<View> propertyCache;
        final PropertyCache<ViewGroup.LayoutParams> layoutParamsPropertyCache;
        final boolean includeResolutionStack;

        AttributeExtraction(
                PropertyCache<View> propertyCache,
                PropertyCache<ViewGroup.LayoutParams> layoutParamsPropertyCache,
                boolean includeResolutionStack) {
            this.propertyCache = propertyCache;
            this.layoutParamsPropertyCache = layoutParamsPropertyCache;
            this.includeResolutionStack = includeResolutionStack;
        }
    }

    /** Flattens a view hierarchy into a {@link ViewNode} proto. */
    static ViewNode toViewNode(
            View view,
            StringTable stringTable,
            boolean includeAttributes,
            boolean includeResolutionStack,
            PropertyCache<View> viewPropertyCache,
            PropertyCache<ViewGroup.LayoutParams> layoutParamsPropertyCache) {
        AttributeExtraction attributeExtraction =
                includeAttributes || includeResolutionStack
                        ? new AttributeExtraction(
                                viewPropertyCache,
                                layoutParamsPropertyCache,
                                includeResolutionStack)
                        : null;
        return createViewNode(view, stringTable, attributeExtraction).build();
    }

    /** Captures a view hierarchy and the metadata associated with its root context. */
    static WindowInfo toWindowInfo(
            View root,
            StringTable stringTable,
            boolean includeAttributes,
            boolean includeResolutionStack,
            PropertyCache<View> viewPropertyCache,
            PropertyCache<ViewGroup.LayoutParams> layoutParamsPropertyCache) {
        Context context = root.getContext();
        WindowInfo.Builder builder =
                WindowInfo.newBuilder()
                        .setRoot(
                                toViewNode(
                                        root,
                                        stringTable,
                                        includeAttributes,
                                        includeResolutionStack,
                                        viewPropertyCache,
                                        layoutParamsPropertyCache))
                        .setConfiguration(
                                ConfigurationProtoConverter.convert(
                                        context.getResources().getConfiguration(), stringTable));
        Integer themeResId = getThemeResIdReflective(context);
        if (themeResId != null) {
            String theme = ResourceIds.resolveResourceToString(root, themeResId);
            if (theme != null) {
                builder.setTheme(stringTable.put(theme));
            }
        }
        return builder.build();
    }

    /**
     * Internal recursive implementation to flatten the view hierarchy.
     *
     * <p>Returns a {@link ViewNode.Builder} to allow the parent to add it directly to its children
     * list without eager building, optimizing memory allocations during traversal.
     */
    private static ViewNode.Builder createViewNode(
            View view, StringTable stringTable, AttributeExtraction attributeExtraction) {
        Class<?> viewClass = view.getClass();

        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int absPosX = location[0];
        int absPosY = location[1];

        ViewNode.Builder builder = ViewNode.newBuilder();
        builder.setId(view.getUniqueDrawingId());
        String simpleName = viewClass.getSimpleName();
        String name =
                simpleName.isEmpty()
                        ? viewClass.getName().substring(viewClass.getName().lastIndexOf('.') + 1)
                        : simpleName;
        builder.setClassName(stringTable.put(name));
        Package pkg = viewClass.getPackage();
        if (pkg != null) {
            builder.setPackageName(stringTable.put(pkg.getName()));
        }

        builder.setBounds(
                Rect.newBuilder()
                        .setX(absPosX)
                        .setY(absPosY)
                        .setWidth(view.getWidth())
                        .setHeight(view.getHeight())
                        .build());

        // Create and set view id resource
        String res = ResourceIds.resolveResourceToString(view, view.getId());
        if (res != null) {
            builder.setIdResource(stringTable.put(res));
        }

        // Create and set source layout id
        String layoutRes = ResourceIds.resolveResourceToString(view, view.getSourceLayoutResId());
        if (layoutRes != null) {
            builder.setLayoutResource(stringTable.put(layoutRes));
        }

        if (attributeExtraction != null) {
            populateAttributes(builder, view, stringTable, attributeExtraction);
        }

        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                builder.addChildren(
                        createViewNode(viewGroup.getChildAt(i), stringTable, attributeExtraction));
            }
        }
        return builder;
    }

    /**
     * Resolves and populates attributes into the {@link ViewNode.Builder}.
     *
     * <p>Reads the view's own properties and its layout params properties through the cached {@link
     * PropertyCache} companions and writes mapped values as simplified {@code Attribute} key-value
     * string pairs.
     */
    private static void populateAttributes(
            ViewNode.Builder viewNodeBuilder,
            View view,
            StringTable stringTable,
            AttributeExtraction attributeExtraction) {
        PropertyCache.PropertyData<View> viewPropertyData =
                attributeExtraction.propertyCache.getOrResolve(view);
        ProtoAttributeReader viewReader =
                new ProtoAttributeReader(
                        view,
                        viewPropertyData.getProperties(),
                        stringTable,
                        attributeExtraction.includeResolutionStack,
                        viewNodeBuilder::addAttributes);
        for (InspectionCompanion<View> companion : viewPropertyData.getCompanions()) {
            companion.readProperties(view, viewReader);
        }

        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            PropertyCache.PropertyData<ViewGroup.LayoutParams> layoutParamsPropertyData =
                    attributeExtraction.layoutParamsPropertyCache.getOrResolve(layoutParams);
            ProtoAttributeReader layoutParamsReader =
                    new ProtoAttributeReader(
                            view,
                            layoutParamsPropertyData.getProperties(),
                            stringTable,
                            attributeExtraction.includeResolutionStack,
                            viewNodeBuilder::addAttributes);
            for (InspectionCompanion<ViewGroup.LayoutParams> companion :
                    layoutParamsPropertyData.getCompanions()) {
                companion.readProperties(layoutParams, layoutParamsReader);
            }
        }
    }

    /**
     * Reflectively retrieves the theme resource ID from the {@link Context}. Accesses the hidden
     * {@code Context.getThemeResId()} framework API reflectively to avoid adding a compile time
     * dependency to fake-android like layout inspector does.
     */
    private static Integer getThemeResIdReflective(Context context) {
        try {
            // Fast-path: Search the runtime class (e.g. Activity, ContextThemeWrapper) which
            // exposes it as public in API 29+.
            Method method = context.getClass().getMethod("getThemeResId");
            return (Integer) method.invoke(context);
        } catch (Exception e) {
            try {
                // Fallback: Search the base Context class for the hidden method (safely silenced by
                // our native JVMTI agent at runtime).
                Method method = Context.class.getDeclaredMethod("getThemeResId");
                method.setAccessible(true);
                return (Integer) method.invoke(context);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    static List<Display> buildDisplayInfo(Context context) {
        DisplayManager displayManager = context.getSystemService(DisplayManager.class);
        android.view.Display[] displays =
                displayManager != null ? displayManager.getDisplays() : null;
        if (displays == null) {
            return Collections.emptyList();
        }
        List<Display> displayInfo = new ArrayList<>(displays.length);
        for (android.view.Display display : displays) {
            android.view.Display.Mode mode = display.getMode();
            Integer orientation;
            switch (display.getRotation()) {
                case Surface.ROTATION_0:
                    orientation = 0;
                    break;
                case Surface.ROTATION_90:
                    orientation = 90;
                    break;
                case Surface.ROTATION_180:
                    orientation = 180;
                    break;
                case Surface.ROTATION_270:
                    orientation = 270;
                    break;
                default:
                    orientation = null;
                    break;
            }
            Display.Builder builder =
                    Display.newBuilder()
                            .setId(display.getDisplayId())
                            .setWidthPx(mode.getPhysicalWidth())
                            .setHeightPx(mode.getPhysicalHeight());
            if (orientation != null) {
                builder.setOrientation(orientation);
            }
            displayInfo.add(builder.build());
        }
        return displayInfo;
    }
}
