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

import java.util.Stack
import kotlin.reflect.KClass

/**
 * interface to provide support for extended DSL via extension.
 */
interface ExtensionAwareDefinition {
    /**
     * Adds a nested block to the DSL via a property that does not exist in the normal interface,
     * simulating Gradle's extension-aware mechanism.
     *
     * **CAVEAT -- HERE BE DRAGONS**
     *
     * This API is quite tricky to use and can break if used improperly. Hopefully this is a niche
     * enough feature that this won't be a problem.
     *
     * The method is declared on the definition because it needs to be somewhere easy to find,
     * without requiring importing it as a top-level function, but it's only meant to be called on
     * an interface manipulated via Proxy.
     *
     * Unfortunately, we can't ensure type safety, because we have to apply it on `Any` so that it
     * applies to all possible DSL interfaces, so it can technically be called from anywhere, but
     * this should only be called inside a block that manipulate a DSL via the proxy. Calling at
     * any other time will still place this nested block inside the last used DSL elements. This
     * is particularly important when there are 2 DSLs (e.g. android + kotlin).
     *
     * This must be used inside a block, rather than on an object directly.
     * Use the following pattern:
     * ```
     *   foo {
     *     viaExtension("bar", Bar::class) {
     *     }
     *   }
     * ```
     *
     * Do **NOT** do this:
     * ```
     *   foo.viaExtension("bar", Bar::class) {
     *     // this will NOT work as expected!
     *   }
     * ```
     */
    fun <T: Any> Any.viaExtension(name: String, theClass: KClass<T>, action: T.() -> Unit)
}

/**
 * Object that provides support for extension. This is to be used by classes that directly
 * provide DSLs via proxy.
 *
 * This is provided by [ExtensionAwareDefinitionImpl]
 */
interface ExtensionSupport {
    /**
     * Execute the provided action while recording the current holder.
     *
     * This must be called every time a nested block is executed, even the root one.
     */
    fun handleNestedBlock(holder: DslContentHolder, action: () -> Unit)
}

/**
 * Base implementation for classes extending [ExtensionAwareDefinition]
 */
open class ExtensionAwareDefinitionImpl: ExtensionAwareDefinition, ExtensionSupport {
    private val holderStack = Stack<DslContentHolder>()

    override fun <T : Any> Any.viaExtension(
        name: String,
        theClass: KClass<T>,
        action: T.() -> Unit
    ) {
        val currentHolder = holderStack.peek()

        currentHolder.runNestedBlock(
            name = name,
            parameters = listOf(),
            theInterface = theClass.java,
            parentChain = listOf(),
            action = action
        )
    }

    override fun handleNestedBlock(holder: DslContentHolder, action: () -> Unit) {
        holderStack.push(holder)
        action()
        holderStack.pop()
    }
}
