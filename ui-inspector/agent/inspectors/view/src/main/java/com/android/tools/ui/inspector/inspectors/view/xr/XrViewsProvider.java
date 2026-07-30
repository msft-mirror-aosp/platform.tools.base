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

package com.android.tools.ui.inspector.inspectors.view.xr;

import android.util.Log;
import android.view.View;
import android.view.inspector.WindowInspector;

import androidx.inspection.InspectorEnvironment;

import com.android.tools.ui.inspector.common.ProtocolConstants;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Uses XrExtensions to get the views associated with each panel in the XR app. */
public final class XrViewsProvider {
    private static final String LOG_TAG = ProtocolConstants.LOG_TAG_PREFIX + ".XrViewsProvider";
    private static final String GET_CURRENT_EXTENSIONS_METHOD = "getCurrentExtensions";

    /**
     * The XrExtensions class, or null when the library is absent. Non XR apps pay the lookup price
     * only once, at class initialization.
     */
    private static final Class<?> XR_EXTENSIONS_CLASS = findXrExtensionsClass();

    /**
     * The discovered XrExtensions instance; null until discovery succeeds, after which it is reused
     * for every call.
     */
    private static volatile Object cachedXrExtensions = null;

    private XrViewsProvider() {}

    public static List<View> getXrViews(InspectorEnvironment environment)
            throws ReflectiveOperationException {
        Class<?> xrClass = XR_EXTENSIONS_CLASS;
        if (xrClass == null) {
            return Collections.emptyList();
        }
        Object xrExtensions = getXrExtensionsInstance(environment, xrClass);

        if (xrExtensions == null) {
            Log.d(LOG_TAG, "Failed to get XrExtensions instance");
            return Collections.emptyList();
        }

        List<View> xrViews = getXrViewsFromExtensions(xrExtensions, xrClass);
        List<View> windowViews = WindowInspector.getGlobalWindowViews();

        // Views for ActivityPanelNodes are not returned by the XrExtensions API. But can be
        // obtained
        // through the WindowInspector, since they are in the main panel. For this reason we merge
        // the views obtained with XrExtensions with the views from the WindowInspector.
        Set<View> merged = new LinkedHashSet<>(xrViews);
        merged.addAll(windowViews);
        return new ArrayList<>(merged);
    }

    private static Class<?> findXrExtensionsClass() {
        try {
            return Class.forName("com.android.extensions.xr.XrExtensions");
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<View> getXrViewsFromExtensions(
            Object xrExtensions, Class<?> xrExtensionsClass) throws ReflectiveOperationException {
        Method listNodesMethod = xrExtensionsClass.getMethod("listNodesWithSurfacePackages");
        Object nodes = listNodesMethod.invoke(xrExtensions);
        if (!(nodes instanceof List)) {
            return Collections.emptyList();
        }
        List<View> views = new ArrayList<>();
        for (Object node : (List<?>) nodes) {
            if (node == null) {
                continue;
            }
            Object rootView = node.getClass().getMethod("getRootView").invoke(node);
            if (rootView instanceof View) {
                views.add((View) rootView);
            }
        }
        return views;
    }

    private static Object getXrExtensionsUsingReflection(Class<?> xrExtensionsClass) {
        try {
            Log.d(LOG_TAG, "Getting XrExtensions using reflection");
            // In agreement with the Xr team, the `getCurrentExtensions` method has been provided as
            // a
            // temporary hack to allow Layout Inspector to get the instance of XrExtensions without
            // having
            // to go through scenecore (which is currently the non hacky-way of doing this, using
            // XrExtensionsProvider).
            // This hack will be removed once the Xr team decides how to expose the instance from
            // the
            // xr extensions library directly. They will notify us before removing the
            // getCurrentExtensions
            // method. See b/411291368.
            Method getCurrentExtensionsMethod =
                    xrExtensionsClass.getDeclaredMethod(GET_CURRENT_EXTENSIONS_METHOD);
            getCurrentExtensionsMethod.setAccessible(true);
            return getCurrentExtensionsMethod.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getXrExtensionsInstance(
            InspectorEnvironment environment, Class<?> xrClass) {
        Object instance = cachedXrExtensions;
        if (instance != null) {
            return instance;
        }
        synchronized (XrViewsProvider.class) {
            instance = cachedXrExtensions;
            if (instance != null) {
                return instance;
            }
            // Try reflection first as a fast-path to avoid the expensive ART tooling heap sweep in
            // findInstances.
            instance = getXrExtensionsUsingReflection(xrClass);
            if (instance == null) {
                List<?> instances = environment.artTooling().findInstances(xrClass);
                instance = instances.isEmpty() ? null : instances.get(0);
            }
            if (instance != null) {
                cachedXrExtensions = instance;
            }
            return instance;
        }
    }
}
