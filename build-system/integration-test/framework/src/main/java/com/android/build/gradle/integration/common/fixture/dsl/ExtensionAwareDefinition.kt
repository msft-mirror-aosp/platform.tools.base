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

import java.lang.reflect.Proxy
import kotlin.reflect.KClass

/**
 * interface to provide support for extended DSL via extension.
 */
interface ExtensionAwareDefinition {
    /**
     * Adds a nested block to the DSL via a property that does not exist in the normal interface,
     * simulating Gradle's extension-aware mechanism.
     *
     * ```
     *   foo {
     *     viaExtension("bar", Bar::class) {
     *     }
     *   }
     * ```
     *
     * It is defined in a top level interface, but should really only be used on objects that
     * are proxied by the fixture.
     */
    fun <T: Any> Any.viaExtension(name: String, theClass: KClass<T>, action: T.() -> Unit) {
        // we need to get access to the [DslContentHolder] from the proxied interface
        val invocationHandler = Proxy.getInvocationHandler(this) as DslProxy

        invocationHandler.contentHolder.runNestedBlock(
            name = name,
            parameters = listOf(),
            theInterface = theClass.java,
            parentChain = listOf(),
            action = action
        )
    }
}
