/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tools.agent.appinspection

import android.util.Log
import android.view.View
import android.view.inspector.WindowInspector
import androidx.inspection.InspectorEnvironment
import com.android.tools.agent.appinspection.xr.getXrViewsUsingReflection

/**
 * Handle with care: this class is accessed through reflection by the compose inspector,
 * so any changes to either package name, class name or signature of [getXrViews] will break
 * the compose inspector.
 */
class XrHelper(private val environment: InspectorEnvironment) {
    var enabled = false

    /** Get all the views from XR. */
    fun getXrViews(): List<View> {
        if (!enabled) {
            return emptyList()
        }

        val xrViewsFromApi = runCatching { com.android.tools.agent.appinspection.xr.getXrViews(environment) }.getOrNull()
        val xrViews = if (xrViewsFromApi.isNullOrEmpty()) {
            Log.w(SPAM_LOG_TAG, "Getting XR views using reflection.")
            runCatching { getXrViewsUsingReflection(environment) }.getOrNull() ?: emptyList()
        }
        else {
            xrViewsFromApi
        }

        // TODO(b/418942993): Move this code back to `XrViewsProvider` once we remove the reflection code
        //  and therefore abandon support for scenecore alpha03 and lower.
        val windowViews = WindowInspector.getGlobalWindowViews()

        // Views for ActivityPanelNodes are not returned but the XrExtensions API. But can be obtained
        // through the WindowInspector, since they are in the main panel. For this reason we merge
        // the views obtained with XrExtensions with the views from the WindowInspector.
        return xrViews.union(windowViews).toList()
    }
}
