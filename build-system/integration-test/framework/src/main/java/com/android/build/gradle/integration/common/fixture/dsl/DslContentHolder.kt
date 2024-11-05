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

import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter

/**
 * Class that contains the actual content of a DSL class that's generated on the fly.
 */
interface DslContentHolder {
    val name: String

    /**
     * Records a = b
     */
    fun set(name: String, value: Any?, parentChain: List<String> = listOf())

    /**
     * Records Collection.addAll
     */
    fun collectionAddAll(
        name: String,
        value: Collection<Any?>?,
        parentChain: List<String> = listOf()
    )
    /**
     * Records Collection.add
     */
    fun collectionAdd(name: String, value: Any?, parentChain: List<String> = listOf())

    /**
     * Returns a proxied [MutableList]
     */
    fun getList(name: String): MutableList<*>

    /**
     * Returns a proxied [MutableSet]
     */
    fun getSet(name: String): MutableSet<*>

    /**
     * records a nested block. The block must be run as part of `action`
     */
    fun <T> runNestedBlock(
        name: String,
        theInterface: Class<T>,
        parentChain: List<String> = listOf(),
        action: T.() -> Unit,
    )

    /**
     * Create an instance of T via a chained proxy.
     *
     * This method is used to handle getters on nested blocks, to handle things like
     *
     * ```
     * person {
     *   address.city = "Mountain View"
     * }
     * ```
     *
     * In this case the proxy for `person`, will call [chainedProxy] to instantiate the result
     * of `getAddress()`.
     *
     * The `Address` instance` is returned as a [DslProxy] using a [ChainedDslContentHolder]
     * that sends its event to the parent holder (ie the `person` object).
     *
     * @param name the name of the nested property in the parent object
     * @param theInterface the type of the nested property in the parent object.
     */
    fun <T> chainedProxy(name: String, theInterface: Class<T>): T

    // returns the content to write the
    fun writeContent(writer: BuildWriter)
}

internal class DefaultDslContentHolder(override val name: String = ""): DslContentHolder {

    enum class EventType {
        ASSIGNMENT,
        NESTED_BLOCK,
        COLLECTION_ADD_ALL,
        COLLECTION_ADD,
    }

    data class Event(
        val type: EventType,
        val payload: Any,
        val parentChain: List<String> = listOf()
    )

    data class NamedData(
        val name: String,
        val value: Any? = null
   )

    private val eventList = mutableListOf<Event>()

    override fun set(name: String, value: Any?, parentChain: List<String>) {
        eventList += Event(EventType.ASSIGNMENT, NamedData(name, value), parentChain)
    }

    override fun getList(name: String): MutableList<*> {
        return ListProxy<Any>(name, this)
    }

    override fun getSet(name: String): MutableSet<*> {
        return SetProxy<Any>(name, this)
    }

    override fun collectionAddAll(
        name: String,
        value: Collection<Any?>?,
        parentChain: List<String>
    ) {
        eventList += Event(EventType.COLLECTION_ADD_ALL, NamedData(name, value), parentChain)
    }

    override fun collectionAdd(name: String, value: Any?, parentChain: List<String>) {
        eventList += Event(EventType.COLLECTION_ADD, NamedData(name, value), parentChain)
    }

    override fun <T> runNestedBlock(
        name: String,
        theInterface: Class<T>,
        parentChain: List<String>,
        action: T.() -> Unit,
    ) {
        runNestedBlock(
            name = name,
            instanceProvider = {
                DslProxy.createProxy(theInterface, it)
            },
            parentChain = parentChain,
            action = action,
        )
    }

    // for testing
    internal fun <T> runNestedBlock(
        name: String,
        instanceProvider: (DslContentHolder) -> T,
        parentChain: List<String> = listOf(),
        action: T.() -> Unit,
    ): T {
        val contentHolder = DefaultDslContentHolder(name)

        val instance = instanceProvider(contentHolder)
        action(instance)

        eventList += Event(EventType.NESTED_BLOCK, contentHolder, parentChain)

        return instance
    }

    override fun <T> chainedProxy(name: String, theInterface: Class<T>): T =
        DslProxy.createProxy(theInterface, ChainedDslContentHolder(name, this))

    override fun writeContent(writer: BuildWriter) {
        for (event in eventList) {
            when (event.type) {
                EventType.ASSIGNMENT -> {
                    val info = event.payload as NamedData
                    writer.set(computeParentChain(info.name, event.parentChain), info.value)
                }
                EventType.NESTED_BLOCK -> {
                    val holder = event.payload as DslContentHolder
                    writer.block(computeParentChain(holder.name, event.parentChain), holder) {
                        holder.writeContent(this)
                    }
                }
                EventType.COLLECTION_ADD -> {
                    val info = event.payload as NamedData
                    writer.writeListAdd(computeParentChain(info.name, event.parentChain), info.value)
                }
                EventType.COLLECTION_ADD_ALL -> {
                    val info = event.payload as NamedData
                    writer.writeListAddAll(computeParentChain(info.name, event.parentChain), info.value as Collection<*>)
                }
                else -> throw RuntimeException("Unsupported EventType: ${event.type}")
            }
        }
    }

    private fun computeParentChain(name: String, parents: List<String>): String =
        if (parents.isEmpty()) {
            name
        } else {
            parents.joinToString(separator = ".") + "." + name
        }

}

/**
 * A [DslContentHolder] that does not actually hold content. It delegate all the operation
 * to its parent holder.
 *
 * This allows handling pattern like:
 *
 * ```
 * person {
 *   name = 'foo'
 *   address.city = "Mountain View"
 * }
 * ```
 *
 * In the example above, getAddress() will use a [ChainedDslContentHolder].
 *
 * setCity on the chained holder, will pass it back to its parent with parentChain containing
 * `address` which will be transformed to a `set("address.city", "value")
 */
internal class ChainedDslContentHolder(
    override val name: String,
    private val parent: DslContentHolder
): DslContentHolder {

    override fun set(name: String, value: Any?, parentChain: List<String>) {
        parent.set(name, value, parentChain + this.name)
    }

    override fun collectionAddAll(name: String, value: Collection<Any?>?, parentChain: List<String>) {
        parent.collectionAddAll(name, value, parentChain + this.name)
    }

    override fun collectionAdd(name: String, value: Any?, parentChain: List<String>) {
        parent.collectionAdd(name, value, parentChain + this.name)
    }

    override fun getList(name: String): MutableList<*> {
        return ListProxy<Any>(name, this)
    }

    override fun getSet(name: String): MutableSet<*> {
        return SetProxy<Any>(name, this)
    }

    override fun <T> runNestedBlock(
        name: String,
        theInterface: Class<T>,
        parentChain: List<String>,
        action: T.() -> Unit,
    ) {
        parent.runNestedBlock(name, theInterface, parentChain + this.name, action)
    }

    override fun <T> chainedProxy(name: String, theInterface: Class<T>): T =
        DslProxy.createProxy(theInterface, ChainedDslContentHolder(name, this))

    override fun writeContent(writer: BuildWriter) {
        throw RuntimeException("ChainedDslContentHolder do not write their own content")
    }
}
