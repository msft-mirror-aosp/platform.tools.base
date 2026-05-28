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

/** Replace all occurrences of a string expression with another string expression */
internal class Replace : StringInterpolationMethodHandler {
  override val name: String
    get() = "replace"

  override val argCount: Int
    get() = 2

  override fun evaluate(target: String, methodCall: MethodCallNode, evaluateArgument: (StringInterpolationNode) -> String): String {
    val oldVal = evaluateArgument(methodCall.arguments[0])
    val newVal = evaluateArgument(methodCall.arguments[1])
    return target.replace(oldVal, newVal)
  }
}
