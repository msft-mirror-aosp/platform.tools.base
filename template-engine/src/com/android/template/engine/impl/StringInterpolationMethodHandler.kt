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
package com.android.template.engine.impl

import com.android.template.engine.impl.methodhandlers.Replace
import com.android.template.engine.impl.methodhandlers.ToAndroidPackageSegment
import com.android.template.engine.impl.methodhandlers.ToJavaClassName
import com.android.template.engine.impl.methodhandlers.ToJavaPackageSegment
import com.android.template.engine.impl.methodhandlers.ToJavaPropertyValue
import com.android.template.engine.impl.methodhandlers.ToLower

internal interface StringInterpolationMethodHandler {
  /** The method name, as used in expressions */
  val name: String

  /** The # of arguments this [StringInterpolationMethodHandler] expects */
  val argCount: Int

  /** Evaluates this [StringInterpolationMethodHandler] by applying it to [target]. */
  fun evaluate(target: String, methodCall: MethodCallNode, evaluateArgument: (StringInterpolationNode) -> String): String

  companion object {
    val allKnownHandlers =
      listOf(Replace(), ToLower(), ToJavaPropertyValue(), ToAndroidPackageSegment(), ToJavaPackageSegment(), ToJavaClassName())

    /** Array of all [StringInterpolationMethodHandler] names */
    private val allKnownHandlersSortedNames by lazy { allKnownHandlers.map { it.name }.sorted().toTypedArray() }

    /**
     * Array of all [StringInterpolationMethodHandler] sorted by name, so that
     *
     *      for 0 <= index < handlerCount
     *        allKnownHandlersSortedByName[index].name == allKnownHandlersSortedNames[index]
     */
    private val allKnownHandlersSortedByName by lazy { allKnownHandlers.sortedBy { it.name }.toTypedArray() }

    fun findMethodHandler(name: String): StringInterpolationMethodHandler? {
      val index = allKnownHandlersSortedNames.binarySearch(name)
      return if (index >= 0) {
        allKnownHandlersSortedByName[index]
      } else {
        null
      }
    }
  }
}
