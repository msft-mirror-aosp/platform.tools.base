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
package com.android.template.engine.impl.methodhandlers

import com.android.template.engine.impl.MethodCallNode
import com.android.template.engine.impl.StringInterpolationMethodHandler
import com.android.template.engine.impl.StringInterpolationNode
import com.android.template.engine.impl.methodhandlers.ToJavaPackageSegment.Companion.stringToJavaPackageSegment

/**
 * Converts a string to a valid android package name (or application id) segment (e.g. `com.example.xxx`). Basically, the rules are to only
 * allow [0-9a-z_].
 */
internal class ToAndroidPackageSegment : StringInterpolationMethodHandler {
  override val name: String
    get() = "toAndroidPackageSegment"

  override val argCount: Int
    get() = 0

  override fun evaluate(target: String, methodCall: MethodCallNode, evaluateArgument: (StringInterpolationNode) -> String): String {
    return stringToJavaPackageSegment(target)
  }
}
