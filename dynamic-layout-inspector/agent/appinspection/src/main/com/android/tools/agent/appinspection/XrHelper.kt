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
private const val PANEL_ENTITY_IMPL_CLASS = "com.google.vr.realitycore.runtime.androidxr.PanelEntityImpl"
private const val MAIN_PANEL_ENTITY_CLASS = "com.google.vr.realitycore.runtime.androidxr.MainPanelEntityImpl"

// The com.google.vr classes will be migrated to androidx.xr in the future.
// Once the migration happens we can remove the com.google.vr names.
private const val PANEL_ENTITY_CLASS_ANDROIDX = "androidx.xr.scenecore.PanelEntity"
private const val SESSION_EXT_CLASS_ANDROIDX = "androidx.xr.scenecore.SessionExt"
private const val PANEL_ENTITY_IMPL_CLASS_ANDROIDX = "androidx.xr.scenecore.impl.PanelEntityImpl"
private const val MAIN_PANEL_ENTITY_CLASS_ANDROIDX = "androidx.xr.scenecore.impl.MainPanelEntityImpl"

private const val GET_ENTITIES_OF_TYPE_METHOD = "getEntitiesOfType"
private const val IS_HIDDEN_METHOD = "isHidden"

private const val SURFACE_CONTROL_VIEW_HOST_FIELD = "surfaceControlViewHost"
private const val M_SURFACE_CONTROL_VIEW_HOST_FIELD = "mSurfaceControlViewHost"
private const val RT_PANEL_ENTITY_FIELD = "rtPanelEntity"
private const val RUNTIME_ACTIVITY_FIELD = "runtimeActivity"

class XrHelper(private val environment: InspectorEnvironment) {
  var enabled = false

  /** Get all the views from XR. */
  fun getXrViews(): List<View> {
    if (!enabled || Build.VERSION.SDK_INT < 30) {
      return emptyList()
    }

    try {
      val xrSessions = getXrSessions()
      return xrSessions
        .mapNotNull { session -> runCatching { doGetXrViews(session) }.getOrNull() }
        .flatten()
    }
    catch (t: Throwable) {
        return emptyList()
    }
  }

  private fun getXrSessions(): List<Any> {
    val xrSessionsAndroix = try {
      // Try to load the AndroidX class first.
      environment.artTooling().findInstances(androidx.xr.scenecore.Session::class.java)
    } catch (t: Throwable) {
      emptyList()
    }

    if (xrSessionsAndroix.isNotEmpty()) {
      return xrSessionsAndroix
    }

    val xrSessions = try {
      // As a fallback try to load the class from com.google.vr.
      environment.artTooling().findInstances(com.google.vr.androidx.xr.core.Session::class.java)
    } catch (t: Throwable) {
      emptyList()
    }
    return xrSessions
  }

  /**
   * Uses reflection to get all instances of JXRCoreRuntime.Entity and get the view they contain.
   * This method will be replaced by calling an API in the XR extensions library that will give
   * access to all views.
   */
  private fun doGetXrViews(session: Any): List<View> {
    val panelEntityClass = try {
      loadClass(PANEL_ENTITY_CLASS)
    } catch (t: Throwable) {
      // As a fallback try to load the class from AndroidX.
      loadClass(PANEL_ENTITY_CLASS_ANDROIDX)
    }

    val panelEntities = getPanelEntities(session, panelEntityClass)
    val views = panelEntities.mapNotNull { entity -> entity?.let { getView(it) } }
    return views
  }

  private fun getView(entity: Any): View? {
    if (isHidden(entity)) {
        return null
    }

    return entity.mapAllFields { field ->
        if (field.name == RT_PANEL_ENTITY_FIELD) {
          val fieldInstance = field.get(entity)!!
          if (
            fieldInstance.javaClass.name == PANEL_ENTITY_IMPL_CLASS ||
            fieldInstance.javaClass.name == PANEL_ENTITY_IMPL_CLASS_ANDROIDX
          ) {
            getRuntimeEntityView(fieldInstance)
          }
          else if (
            fieldInstance.javaClass.name == MAIN_PANEL_ENTITY_CLASS ||
            fieldInstance.javaClass.name == MAIN_PANEL_ENTITY_CLASS_ANDROIDX
          ) {
            getMainPanelEntityImplView(fieldInstance)
          }
          else {
            null
          }
        }
        else {
          null
        }
    }.filterNotNull().firstOrNull()
  }

  private fun getRuntimeEntityView(instance: Any): View? {
    val clazz = instance.javaClass
    val surfaceControlViewHostField = runCatching { clazz.getDeclaredField(SURFACE_CONTROL_VIEW_HOST_FIELD) }.getOrNull()
        // surfaceControlViewHost was renamed to mSurfaceControlViewHost in newer versions of SceneCore.
        ?: runCatching { clazz.getDeclaredField(M_SURFACE_CONTROL_VIEW_HOST_FIELD) }.getOrNull()
    if (surfaceControlViewHostField != null) {
      surfaceControlViewHostField.isAccessible = true
      val surfaceControlViewHost = surfaceControlViewHostField.get(instance) as SurfaceControlViewHost
      return surfaceControlViewHost.view
    }
    else {
      return null
    }
  }

  private fun getMainPanelEntityImplView(instance: Any): View? {
    val clazz = instance.javaClass
    val runtimeActivityField = runCatching { clazz.getDeclaredField(RUNTIME_ACTIVITY_FIELD) }.getOrNull()
    return if (runtimeActivityField != null) {
      runtimeActivityField.isAccessible = true
      val runtimeActivityInstance = runtimeActivityField.get(instance) as Activity
      runtimeActivityInstance.window.decorView
    }
    else {
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
    // First try to get the panels by calling the method on the Session class
    val panels = runCatching { getPanelEntitiesUsingSessionMethod(session, panelEntityClass) }.getOrNull()

    return if (panels.isNullOrEmpty()) {
      // If no panel was found, get the panels by using the extension method on Session.
      // This is a change made on later versions of the SceneCore library.
      getPanelEntitiesUsingExtMethod(session, panelEntityClass)
    } else {
      panels
    }
  }

  private fun getPanelEntitiesUsingSessionMethod(session: Any, panelEntityClass: Class<out Any>): List<*> {
    val getEntitiesOfTypeMethod = loadMethod(session.javaClass, GET_ENTITIES_OF_TYPE_METHOD, Class::class.java)
    return getEntitiesOfTypeMethod.invoke(session, panelEntityClass) as List<*>
  }

  private fun getPanelEntitiesUsingExtMethod(session: Any, panelEntityClass: Class<out Any>): List<*> {
    // Get the synthetic class where Kotlin places extension functions
    val sessionExtClass = try {
      loadClass(SESSION_EXT_CLASS)
    } catch (_: Throwable) {
      // As a fallback try to load the class from AndroidX.
      loadClass(SESSION_EXT_CLASS_ANDROIDX)
    }

    // Get the static method representing the extension function
    val getEntitiesOfTypeMethod = loadMethod(
      clazz = sessionExtClass,
      name = GET_ENTITIES_OF_TYPE_METHOD,
      session.javaClass,
      Class::class.java
    )

    return getEntitiesOfTypeMethod.invoke(null, session, panelEntityClass) as List<*>
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

  private fun loadClass(name: String): Class<*> {
    return XrHelper::class.java.classLoader.loadClass(name)
  }

  private fun loadMethod(clazz: Class<*>, name: String, vararg args: Class<*>): Method {
    return clazz.getDeclaredMethod(name, *args).apply { isAccessible = true }
  }
}
