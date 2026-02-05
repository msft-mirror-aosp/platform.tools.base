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
package com.android.testutils.annotation

import com.android.sdklib.AndroidVersion
import java.lang.reflect.Method
import org.junit.rules.MethodRule
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement

/**
 * Annotation for specifying the Min API level for a test.
 *
 * The minApi can be read via [AnnotationRule.minApi].
 */
@Retention(AnnotationRetention.RUNTIME) annotation class MinApi(val level: Int)

/** Rule for reading the API level for the [MinApi] annotation. */
class AnnotationRule(private val defaultMinApi: Int = AndroidVersion.MIN_RECOMMENDED_API) : MethodRule {
  private var lastMethod: Method? = null

  val minApi: Int
    get() = lastMethod?.annotations?.filterIsInstance<MinApi>()?.singleOrNull()?.level ?: defaultMinApi

  override fun apply(base: Statement, method: FrameworkMethod, target: Any): Statement {
    lastMethod = method.method
    return base
  }
}
