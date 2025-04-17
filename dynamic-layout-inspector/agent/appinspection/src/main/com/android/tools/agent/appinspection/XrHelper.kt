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

import android.app.Activity
import android.os.Build
import android.view.SurfaceControlViewHost
import android.view.View
import androidx.inspection.InspectorEnvironment
import java.lang.reflect.Field
import java.lang.reflect.Method

private const val PANEL_ENTITY_CLASS = "com.google.vr.androidx.xr.core.PanelEntity"
private const val SESSION_EXT_CLASS = "com.google.vr.androidx.xr.core.SessionExt"
private const val PANEL_ENTITY_IMPL_CLASS =
    "com.google.vr.realitycore.runtime.androidxr.PanelEntityImpl"
private const val MAIN_PANEL_ENTITY_CLASS =
    "com.google.vr.realitycore.runtime.androidxr.MainPanelEntityImpl"
private const val JXR_CORE_SESSION_CLASS = "com.google.vr.androidx.xr.core.Session"

// The com.google.vr classes will be migrated to androidx.xr in the future.
// Once the migration happens we can remove the com.google.vr names.
private const val PANEL_ENTITY_CLASS_ANDROIDX = "androidx.xr.scenecore.PanelEntity"
private const val SESSION_EXT_CLASS_ANDROIDX = "androidx.xr.scenecore.SessionExt"
private const val PANEL_ENTITY_IMPL_CLASS_ANDROIDX = "androidx.xr.scenecore.impl.PanelEntityImpl"
private const val MAIN_PANEL_ENTITY_CLASS_ANDROIDX =
    "androidx.xr.scenecore.impl.MainPanelEntityImpl"
private const val JXR_CORE_SESSION_CLASS_ANDROIDX = "androidx.xr.scenecore.Session"

private const val JXR_CORE_SESSION_CLASS_ANDROIDX_RUNTIME = "androidx.xr.runtime.Session"

private const val GET_ENTITIES_OF_TYPE_METHOD = "getEntitiesOfType"
private const val IS_HIDDEN_METHOD = "isHidden"
private const val GET_SCENE_METHOD = "getScene"

private const val SURFACE_CONTROL_VIEW_HOST_FIELD = "surfaceControlViewHost"
private const val M_SURFACE_CONTROL_VIEW_HOST_FIELD = "mSurfaceControlViewHost"
private const val RT_PANEL_ENTITY_FIELD = "rtPanelEntity"
private const val RUNTIME_ACTIVITY_FIELD = "mRuntimeActivity"

class XrHelper(private val environment: InspectorEnvironment) {
    var enabled = false

    /** Get all the views from XR. */
    fun getXrViews(): List<View> {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptyList()
        }

        try {
            val xrSessions = getXrSessions()
            return xrSessions
                .mapNotNull { session -> runCatching { doGetXrViews(session) }.getOrNull() }
                .flatten()
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    private fun getXrSessions(): List<Any> {
        val sessionClass = loadClass(
            JXR_CORE_SESSION_CLASS_ANDROIDX,
            JXR_CORE_SESSION_CLASS,
            JXR_CORE_SESSION_CLASS_ANDROIDX_RUNTIME
        )
        return environment.artTooling().findInstances(sessionClass)
    }

    /**
     * Uses reflection to get all instances of JXRCoreRuntime.Entity and get the view they contain.
     * This method will be replaced by calling an API in the XR extensions library that will give
     * access to all views.
     */
    private fun doGetXrViews(session: Any): List<View> {
        val panelEntityClass = loadClass(PANEL_ENTITY_CLASS_ANDROIDX, PANEL_ENTITY_CLASS)
        val panelEntities = getPanelEntities(session, panelEntityClass)
        val views = panelEntities.mapNotNull { entity -> entity?.let { getView(it) } }
        return views
    }

    private fun getView(entity: Any): View? {
        if (isHidden(entity)) {
            return null
        }

        return entity
            .mapAllFields { field ->
                if (field.name == RT_PANEL_ENTITY_FIELD) {
                    val fieldInstance = field.get(entity)!!
                    when (fieldInstance.javaClass.name) {
                        PANEL_ENTITY_IMPL_CLASS_ANDROIDX,
                        PANEL_ENTITY_IMPL_CLASS -> getRuntimeEntityView(fieldInstance)

                        MAIN_PANEL_ENTITY_CLASS_ANDROIDX,
                        MAIN_PANEL_ENTITY_CLASS -> getMainPanelEntityImplView(fieldInstance)

                        else -> null
                    }
                } else {
                    null
                }
            }
            .filterNotNull()
            .firstOrNull()
    }

    private fun getRuntimeEntityView(instance: Any): View? {
        val clazz = instance.javaClass
        val surfaceControlViewHostField =
            runCatching { clazz.getDeclaredField(SURFACE_CONTROL_VIEW_HOST_FIELD) }.getOrNull()
                // SurfaceControlViewHost was renamed to mSurfaceControlViewHost in newer versions
                // of SceneCore.
                ?: runCatching { clazz.getDeclaredField(M_SURFACE_CONTROL_VIEW_HOST_FIELD) }
                    .getOrNull()
        if (surfaceControlViewHostField != null) {
            surfaceControlViewHostField.isAccessible = true
            val surfaceControlViewHost =
                surfaceControlViewHostField.get(instance) as SurfaceControlViewHost
            return surfaceControlViewHost.view
        } else {
            return null
        }
    }

    private fun getMainPanelEntityImplView(instance: Any): View? {
        val clazz = instance.javaClass
        val runtimeActivityField =
            runCatching { clazz.getDeclaredField(RUNTIME_ACTIVITY_FIELD) }.getOrNull()
        return if (runtimeActivityField != null) {
            runtimeActivityField.isAccessible = true
            val runtimeActivityInstance = runtimeActivityField.get(instance) as Activity
            runtimeActivityInstance.window.decorView
        } else {
            null
        }
    }

    fun isHidden(instance: Any): Boolean {
        var isHidden = false

        runCatching {
            instance.mapAllMethods { method ->
                if (method.name == IS_HIDDEN_METHOD) {
                    isHidden = method.invoke(instance, true) as Boolean
                    return@mapAllMethods
                }

            }
        }

        return isHidden
    }

    private fun getPanelEntities(session: Any, panelEntityClass: Class<out Any>): List<*> {
        // Attempt 1: Try to get the panels using a method defined on the Session class.
        val sessionMethodEntities = runCatching {
            getPanelEntitiesUsingSessionMethod(session, panelEntityClass)
        }.getOrDefault(emptyList<Any>())

        if (sessionMethodEntities.isNotEmpty()) {
            return sessionMethodEntities
        }

        // Attempt 2: Try to get the panels using the extension method on Session.
        val extensionMethodEntities = runCatching {
            getPanelEntitiesUsingExtMethod(session, panelEntityClass)
        }.getOrDefault(emptyList<Any>())

        if (extensionMethodEntities.isNotEmpty()) {
            return extensionMethodEntities
        }

        // Attempt 3: If extension method returned a non-empty list, get the panels using the scene method.
        return getPanelEntitiesUsingScene(session, panelEntityClass)
    }

    private fun getPanelEntitiesUsingSessionMethod(
        session: Any,
        panelEntityClass: Class<out Any>
    ): List<*> {
        val getEntitiesOfTypeMethod =
            loadMethod(session.javaClass, GET_ENTITIES_OF_TYPE_METHOD, Class::class.java)
        return getEntitiesOfTypeMethod.invoke(session, panelEntityClass) as List<*>
    }

    private fun getPanelEntitiesUsingExtMethod(
        session: Any,
        panelEntityClass: Class<out Any>
    ): List<*> {
        // Get the synthetic class where Kotlin places extension functions
        val sessionExtClass = loadClass(SESSION_EXT_CLASS_ANDROIDX, SESSION_EXT_CLASS)

        // Get the static method representing the extension function
        val getEntitiesOfTypeMethod =
            loadMethod(
                clazz = sessionExtClass,
                name = GET_ENTITIES_OF_TYPE_METHOD,
                session.javaClass,
                Class::class.java
            )

        return getEntitiesOfTypeMethod.invoke(null, session, panelEntityClass) as List<*>
    }

    private fun getPanelEntitiesUsingScene(
        session: Any,
        panelEntityClass: Class<out Any>
    ): List<*> {
        // Get the synthetic class where Kotlin places extension functions
        val sessionExtClass = loadClass(SESSION_EXT_CLASS_ANDROIDX, SESSION_EXT_CLASS)

        // Get the static method representing the extension function
        val getSceneMethod = sessionExtClass.getDeclaredMethod(GET_SCENE_METHOD, session.javaClass)
        getSceneMethod.isAccessible = true

        // Invoke the static method to get the Scene object.
        val scene = getSceneMethod.invoke(null, session)

        // Find the method "getEntitiesOfType" on the scene instance.
        val sceneClass = scene.javaClass
        val getEntitiesMethod = sceneClass.getDeclaredMethod(GET_ENTITIES_OF_TYPE_METHOD, Class::class.java)
        getEntitiesMethod.isAccessible = true

        return getEntitiesMethod.invoke(scene, panelEntityClass) as? List<*> ?: emptyList<Any>()
    }

    private fun <T> Any.mapAllFields(block: (filed: Field) -> T): List<T> {
        var clazz: Class<*>? = javaClass
        val results = mutableListOf<T>()

        while (clazz != Any::class.java && clazz != null) {
            clazz.declaredFields.forEach { field ->
                field.isAccessible = true

                results.add(block(field))
            }

            // Move to the superclass
            clazz = clazz.superclass
        }

        return results
    }

    fun <T> Any.mapAllMethods(block: (method: Method) -> T): List<T> {
        var clazz: Class<*>? = javaClass
        val results = mutableListOf<T>()

        while (clazz != Any::class.java && clazz != null) {
            clazz.declaredMethods.forEach { method ->
                method.isAccessible = true
                results.add(block(method))
            }

            // Move to the superclass
            clazz = clazz.superclass
        }

        return results
    }

    /**
     * Attempts to load a class using three possible class name variations.
     */
    private fun loadClass(vararg classNames: String): Class<*> {
        val classLoader = XrHelper::class.java.classLoader
            ?: throw IllegalStateException("ClassLoader not available.")

        for (name in classNames) {
            try {
                return classLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }

        // None of the classes could be loaded.
        throw ClassNotFoundException("Unable to load any of the provided classes: $classNames")
    }

    private fun loadMethod(clazz: Class<*>, name: String, vararg args: Class<*>): Method {
        return clazz.getDeclaredMethod(name, *args).apply { isAccessible = true }
    }
}
