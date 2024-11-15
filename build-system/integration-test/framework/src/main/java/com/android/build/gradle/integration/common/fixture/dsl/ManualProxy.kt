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

package com.android.build.gradle.integration.common.fixture.dsl

import net.bytebuddy.ByteBuddy
import net.bytebuddy.implementation.MethodDelegation
import net.bytebuddy.implementation.bind.annotation.AllArguments
import net.bytebuddy.implementation.bind.annotation.Origin
import net.bytebuddy.implementation.bind.annotation.RuntimeType
import net.bytebuddy.matcher.ElementMatchers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * Due to b/377720361 we cannot use a normal Java proxy to implement [ProductFlavor].
 *
 * This class uses ByteBuddy to implement a proxy from the flavor interfaces to the [DslProxy] which
 * implements [InvocationHandler]
 */
class ManualProxy {
    companion object {
        fun <T> createManualProxy(
            theInterface: Class<T>,
            proxy: DslProxy
        ): T {
            val instance = ByteBuddy().subclass(theInterface)
                .method(ElementMatchers.any())
                .intercept(MethodDelegation.to(GeneralInterceptor(proxy)))
                .make()
                .load(ManualProxy::class.java.classLoader)
                .loaded

            return instance.getDeclaredConstructor().newInstance()
        }
    }

    class GeneralInterceptor(
        private val proxy: DslProxy
    ) {
        @RuntimeType
        fun intercept(
            @AllArguments allArguments: Array<Any>,
            @Origin method: Method
        ): Any {
            return proxy.invoke(this, method, allArguments) ?: Unit
        }
    }
}

