/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.tools.agent.appinspection.xr

import android.util.Log
import android.view.View
import android.view.inspector.WindowInspector
import androidx.inspection.InspectorEnvironment
import com.android.extensions.xr.XrExtensions
import com.android.tools.agent.appinspection.SPAM_LOG_TAG

private const val GET_CURRENT_EXTENSIONS_METHOD = "getCurrentExtensions"

/**
 * Uses [XrExtensions] to get the views associated with each panel in the XR app.
 * [XrExtensions] is in the system image of android XR.
 */
internal fun getXrViews(environment: InspectorEnvironment): List<View> {
    val instances = environment.artTooling().findInstances(XrExtensions::class.java)
    val xrExtensions = instances.firstOrNull() ?: getXrExtensionsUsingReflection()

    if (xrExtensions == null) {
        Log.w(SPAM_LOG_TAG, "Failed to get XrExtensions instance")
        return emptyList()
    }

    val xrViews = xrExtensions.listNodesWithSurfacePackages().map { it.rootView }
    val windowViews = WindowInspector.getGlobalWindowViews()

    // Views for ActivityPanelNodes are not returned but the XrExtensions API. But can be obtained
    // through the WindowInspector, since they are in the main panel. For this reason we merge
    // the views obtained with XrExtensions with the views from the WindowInspector.
    return xrViews.union(windowViews).toList()
}

private fun getXrExtensionsUsingReflection(): XrExtensions? {
    Log.w(SPAM_LOG_TAG, "Getting XrExtensions using reflection")
    // In agreement with the Xr team, the `getCurrentExtensions` method has been provided as a
    // temporary hack to allow Layout Inspector to get the instance of XrExtensions without having
    // to go through scenecore (which is currently the non hacky-way of doing this, using
    // XrExtensionsProvider).
    // This hack will be removed once the Xr team decides how to expose the instance from the
    // xr extensions library directly. They will notify us before removing the getCurrentExtensions
    // method. See b/411291368.
    val getCurrentExtensionsMethod = XrExtensions::class.java.getDeclaredMethod(GET_CURRENT_EXTENSIONS_METHOD)
    return getCurrentExtensionsMethod.invoke(null) as? XrExtensions
}
