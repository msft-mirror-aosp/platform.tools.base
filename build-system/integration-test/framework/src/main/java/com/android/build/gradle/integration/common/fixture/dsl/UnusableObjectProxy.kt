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

package com.android.build.gradle.integration.common.fixture.dsl

import com.android.build.api.dsl.ApplicationProductFlavor
import com.android.build.api.dsl.DynamicFeatureProductFlavor
import com.android.build.api.dsl.LibraryProductFlavor
import com.android.build.api.dsl.TestProductFlavor
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * A proxy that allows us to make an [Object] non usable.
 *
 * Usage is disallowed when it's impossible to record the usage and reconstruct it into the build file. Here, the Object is dangling
 * (returned from another method) and it's impossible to know what is done with it, so we cannot allow usage in our proxied DSL.
 */
class UnusableObjectProxy private constructor() : InvocationHandler {

  companion object {

    /** Creates a Java proxy for type `theClass` */
    @Suppress("UNCHECKED_CAST")
    fun <T> createProxy(theClass: Class<T>): T =
      when (theClass) {
        ApplicationProductFlavor::class.java -> UnusableApplicationProductFlavorProxy() as T
        LibraryProductFlavor::class.java -> UnusableLibraryProductFlavorProxy() as T
        DynamicFeatureProductFlavor::class.java -> UnusableDynamicFeatureProductFlavorProxy() as T
        TestProductFlavor::class.java -> UnusableTestProductFlavorProxy() as T
        else -> Proxy.newProxyInstance(UnusableObjectProxy::class.java.classLoader, arrayOf(theClass), UnusableObjectProxy()) as T
      }
  }

  override fun invoke(proxy: Any, method: Method, args: Array<out Any?>): Any? {
    throwUnusableError("${method.declaringClass}.${method.name}")
  }
}

fun throwUnusableError(method: String): Nothing {
  throw RuntimeException(
    """
            Calls into this instance is not permitted ($method)
            The instance was returned by the DSL proxy feature to satisfy the proxied interface API but cannot be used directly.
            This is generally because usage of this object cannot be properly recorded to rewrite the build file."""
      .trimIndent()
  )
}
