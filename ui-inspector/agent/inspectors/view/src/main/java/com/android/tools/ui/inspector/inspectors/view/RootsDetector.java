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

import android.view.View;
import android.view.inspector.WindowInspector;

import com.android.tools.agent.appinspection.XrHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RootsDetector {
    private RootsDetector() {}

    /**
     * Returns all root views currently attached to the window.
     *
     * <p>Note: This relies on {@link WindowInspector#getGlobalWindowViews()}, which requires API
     * 29+. This is intentional as the UI Inspector targets modern Android versions.
     */
    static List<View> getRootViews(XrHelper xrHelper) {
        List<View> xrViews = xrHelper.getXrViews();
        // If there are xr panels, xrViews already contains both XR panel views and regular
        // window views merged without duplicates.
        List<View> views = !xrViews.isEmpty() ? xrViews : getAndroidViews();
        List<View> roots = new ArrayList<>();
        for (View view : views) {
            if (view.getVisibility() == View.VISIBLE && view.isAttachedToWindow()) {
                roots.add(view);
            }
        }
        roots.sort(Comparator.comparingDouble(View::getZ));
        return roots;
    }

    private static List<View> getAndroidViews() {
        return WindowInspector.getGlobalWindowViews();
    }
}
