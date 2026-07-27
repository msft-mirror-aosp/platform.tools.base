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

import android.content.res.Resources;
import android.view.View;

import androidx.annotation.VisibleForTesting;

/** Resolves Android framework resource IDs into their string representation. */
public final class ResourceIds {
    private ResourceIds() {}

    /**
     * Resolves a framework resource ID directly into its string representation (e.g., {@code
     * "@id/my_view"}), or null when the ID is invalid or unknown to the view's resources.
     */
    public static String resolveResourceToString(View view, int resourceId) {
        if (!isValidResourceId(resourceId)) {
            return null;
        }
        try {
            Resources resources = view.getResources();
            String type = resources.getResourceTypeName(resourceId);
            String pkg = resources.getResourcePackageName(resourceId);
            String name = resources.getResourceEntryName(resourceId);
            if (pkg.equals("android")) {
                return "@android:" + type + "/" + name;
            } else if (pkg.equals(view.getContext().getPackageName())) {
                return "@" + type + "/" + name;
            } else {
                return "@" + pkg + ":" + type + "/" + name;
            }
        } catch (Resources.NotFoundException ex) {
            return null;
        }
    }

    /**
     * Performs a fast check to determine if a resource ID is potentially valid.
     *
     * <p>This is a performance optimization to avoid calling expensive resource resolution APIs
     * (which throw {@link Resources.NotFoundException} and spam logcat for invalid IDs) for views
     * that don't have IDs.
     *
     * <p>All valid Android resource IDs are positive integers.
     *
     * <p>Note: We use strict bitwise checks similar to Layout Inspector to filter out positive
     * integers that are not valid resource IDs, preventing logcat spam (see b/299309384 and
     * b/311414906).
     */
    @VisibleForTesting
    static boolean isValidResourceId(int resourceId) {
        if (resourceId <= 0) {
            return false;
        }

        // A valid resource ID is structured as 0xPPTTEEEE where:
        // PP: Package ID, TT: Type ID, EEEE: Entry ID.
        int packageId = (resourceId >> 24) & 0xFF;
        int typeId = (resourceId >> 16) & 0xFF;

        // Both package and type must be non-zero.
        // Package ID 0xFF is disallowed in AssetManager2.
        return packageId != 0 && packageId != 0xFF && typeId != 0;
    }
}
