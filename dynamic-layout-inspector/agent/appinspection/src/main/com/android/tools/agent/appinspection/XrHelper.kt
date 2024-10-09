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
import androidx.annotation.RequiresApi
import androidx.inspection.InspectorEnvironment
import java.lang.reflect.Field
import java.lang.reflect.Method
import com.google.vr.androidx.xr.core.Session

private const val MAIN_PANEL_ENTITY_CLASS = "com.google.vr.realitycore.runtime.androidxr.MainPanelEntityImpl"
private const val JXR_CORE_RUNTIME_CLASS = "com.google.vr.androidx.xr.core.JXRCoreRuntime"
private const val PANEL_ENTITY_CLASS = "com.google.vr.androidx.xr.core.PanelEntity"
private const val JXR_CORE_RUNTIME_ENTITY = "com.google.vr.androidx.xr.core.JXRCoreRuntime\$Entity"

private const val GET_ENTITIES_OF_TYPE_METHOD = "getEntitiesOfType"
private const val CREATE_METHOD = "create"
private const val IS_HIDDEN_METHOD = "isHidden"

private const val SURFACE_CONTROL_VIEW_HOST_FIELD = "surfaceControlViewHost"
private const val RT_PANEL_ENTITY_FIELD = "rtPanelEntity"

class XrHelper(private val environment: InspectorEnvironment) {
  var enabled = false

  /** Get all the views from XR. */
  fun getXrViews(): List<View> {
    if (!enabled || Build.VERSION.SDK_INT < 30) {
      return emptyList()
    }

    try {
        val xrSessions = environment.artTooling().findInstances(Session::class.java)
        return xrSessions
          .mapNotNull { session -> runCatching { doGetXrViews(session) }.getOrNull() }
          .flatten()
    }
    catch (t: Throwable) {
        return emptyList()
    }
  }

  /**
   * Uses reflection to get all instances of JXRCoreRuntime.Entity and get the view they contain.
   * This method will be replaced by calling an API in the XR extensions library that will give
   * access to all views.
   */
  private fun doGetXrViews(session: Session): List<View> {
    val panelEntityClass = loadClass(PANEL_ENTITY_CLASS)
    val getEntitiesOfTypeMethod = loadMethod(session.javaClass, GET_ENTITIES_OF_TYPE_METHOD, Class::class.java)
    val panelEntities = getEntitiesOfTypeMethod.invoke(session, panelEntityClass) as List<*>
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
          if (fieldInstance.javaClass.name == MAIN_PANEL_ENTITY_CLASS) {
            getMainPanelEntityImplView(fieldInstance)
          }
          else {
            null
          }
        }
        else if (field.type.name == JXR_CORE_RUNTIME_ENTITY) {
          val fieldInstance = field.get(entity)!!
          getRuntimeEntityView(fieldInstance)
        }
        else {
          null
        }
    }.filterNotNull().firstOrNull()
  }

  fun getMainPanelEntityImplView(instance: Any): View? {
    val clazz = instance.javaClass
    val runtimeActivityField = runCatching { clazz.getDeclaredField("runtimeActivity") }.getOrNull()
    return if (runtimeActivityField != null) {
        runtimeActivityField.isAccessible = true
        val runtimeActivityInstance = runtimeActivityField.get(instance) as Activity
        runtimeActivityInstance.window.decorView
    }
    else {
        null
    }
}

  private fun getRuntimeEntityView(instance: Any): View? {
    val clazz = instance.javaClass
    val surfaceControlViewHostField = runCatching { clazz.getDeclaredField(SURFACE_CONTROL_VIEW_HOST_FIELD) }.getOrNull()
    if (surfaceControlViewHostField != null) {
      surfaceControlViewHostField.isAccessible = true
      val surfaceControlViewHost = surfaceControlViewHostField.get(instance) as SurfaceControlViewHost
      return surfaceControlViewHost.view
    }
    else {
      return null
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