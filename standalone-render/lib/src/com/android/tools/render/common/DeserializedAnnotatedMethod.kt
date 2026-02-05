/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.tools.render.common

import com.android.tools.preview.AnnotatedMethod
import com.android.tools.preview.AnnotationAttributesProvider

/**
 * [AnnotatedMethod] for the method represented by its FQN and parameters each of which is represented by the mapping between
 * its @ParameterProvider annotation parameters names and values.
 */
class DeserializedAnnotatedMethod(methodFqn: String, methodParams: List<Map<String, String>>) : AnnotatedMethod<Unit> {
  override val name: String = methodFqn.substringAfterLast(".")
  override val qualifiedName: String = methodFqn
  override val methodBody: Unit? = null
  override val parameterAnnotations: List<Pair<String, AnnotationAttributesProvider>> =
    methodParams.mapIndexed { i, param -> ("param$i" to DeserializedAnnotationAttributesProvider(param)) }
}
