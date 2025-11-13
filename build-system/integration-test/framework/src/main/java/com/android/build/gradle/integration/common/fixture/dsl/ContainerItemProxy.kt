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

import com.android.build.gradle.integration.common.fixture.project.builder.StringHandler
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * A proxy for a gradle container item.
 *
 * This is only to be used with [NamedDomainObjectContainerProxy.pathToInstance]
 */
class ContainerItemProxy(
    private val itemName: String,
    private val pathToParent: String) : InvocationHandler {
    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any?>
    ): Any = if (method.name == "toString" && args.size == 1) {
        // this must be a call to toString(StringHandler
        val stringHandler = args.first() as StringHandler
        """$pathToParent.getByName(${stringHandler.quoteString(itemName)})"""
    } else {
        throw RuntimeException("Normal Method calls not Supported on ItemProxy")
    }
}
