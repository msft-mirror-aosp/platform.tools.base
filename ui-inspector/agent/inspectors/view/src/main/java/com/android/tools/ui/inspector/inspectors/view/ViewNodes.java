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
import android.graphics.Matrix;
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
        // Seed the transform chain with the window's position on screen. The recursion extends it
        // with each child's offset and matrix, so mapping a view's corners through its accumulated
        // matrix yields bounds directly in screen coordinates.
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        Matrix rootToScreen = new Matrix();
        rootToScreen.setTranslate(location[0], location[1]);
        return createViewNode(view, rootToScreen, stringTable, attributeExtraction).build();
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
     *
     * @param view The view to convert; its subtree is converted recursively.
     * @param viewToScreen The accumulated transform mapping {@code view}'s local coordinates to
     *     screen coordinates; used to compute the node's bounds.
     * @param stringTable The lookup table used to deduplicate and intern strings.
     * @param attributeExtraction The attribute extraction configuration; null when attributes are
     *     not requested.
     */
    private static ViewNode.Builder createViewNode(
            View view,
            Matrix viewToScreen,
            StringTable stringTable,
            AttributeExtraction attributeExtraction) {
        Class<?> viewClass = view.getClass();

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

        builder.setBounds(screenBounds(view, viewToScreen));

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
            // Extends the parent's screen transform to each child: the child sits at (left, top)
            // inside the parent, shifted by the parent's scrolling, and getMatrix() adds the
            // child's own visual transform (scale/rotation/translation). This is the composition
            // the framework's hidden View.transformMatrixToGlobal performs, inlined. The scratch
            // matrix is reset from the parent's transform for every sibling.
            Matrix childToScreen = new Matrix();
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                childToScreen.set(viewToScreen);
                childToScreen.preTranslate(
                        child.getLeft() - viewGroup.getScrollX(),
                        child.getTop() - viewGroup.getScrollY());
                childToScreen.preConcat(child.getMatrix());
                builder.addChildren(
                        createViewNode(child, childToScreen, stringTable, attributeExtraction));
            }
        }
        return builder;
    }

    /**
     * Computes the box that {@code view} occupies on screen. Layout position and size alone
     * misstate views carrying a visual transform (scaled, rotated, or translated through their
     * transform properties), so the view's four corners are mapped through {@code viewToScreen} and
     * the box spans the extremes of the mapped corners. For an untransformed view this is exactly
     * its position on screen and its layout size; fractional edges produced by a transform are
     * rounded outward so the box always covers the rendered pixels.
     */
    private static Rect screenBounds(View view, Matrix viewToScreen) {
        int width = view.getWidth();
        int height = view.getHeight();
        // The view's corners in its own coordinates, as (x, y) pairs: top-left, top-right,
        // bottom-right, bottom-left.
        float[] corners = {0, 0, width, 0, width, height, 0, height};
        viewToScreen.mapPoints(corners);
        // A transform can move any corner to any side of the box, so each edge is the min/max over
        // all four corners. Matrix.mapRect would compute the same box on a device, but the
        // Robolectric fake used by the agent tests gets it wrong; this way production and tests
        // run the same math.
        float minX = Math.min(Math.min(corners[0], corners[2]), Math.min(corners[4], corners[6]));
        float maxX = Math.max(Math.max(corners[0], corners[2]), Math.max(corners[4], corners[6]));
        float minY = Math.min(Math.min(corners[1], corners[3]), Math.min(corners[5], corners[7]));
        float maxY = Math.max(Math.max(corners[1], corners[3]), Math.max(corners[5], corners[7]));
        int left = (int) Math.floor(minX);
        int top = (int) Math.floor(minY);
        int right = (int) Math.ceil(maxX);
        int bottom = (int) Math.ceil(maxY);
        return Rect.newBuilder()
                .setX(left)
                .setY(top)
                .setWidth(right - left)
                .setHeight(bottom - top)
                .build();
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
