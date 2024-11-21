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

internal class MapProxy<K, V>(
    private val name: String,
    private val contentHolder: DslContentHolder
): MutableMap<K, V> {

    override fun put(key: K, value: V): V? {
        contentHolder.call("$name.put", listOf(key, value), isVarArgs = false)
        return value
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = throw RuntimeException("Not yet implemented")
    override val keys: MutableSet<K>
        get() = throw RuntimeException("Not yet implemented")
    override val size: Int
        get() = throw RuntimeException("Not yet implemented")
    override val values: MutableCollection<V>
        get() = throw RuntimeException("Not yet implemented")

    override fun clear() {
        throw RuntimeException("Not yet implemented")
    }

    override fun isEmpty(): Boolean {
        throw RuntimeException("Not yet implemented")
    }

    override fun remove(key: K): V? {
        throw RuntimeException("Not yet implemented")
    }

    override fun putAll(from: Map<out K, V>) {
        throw RuntimeException("Not yet implemented")
    }

    override fun get(key: K): V? {
        throw RuntimeException("Not yet implemented")
    }

    override fun containsValue(value: V): Boolean {
        throw RuntimeException("Not yet implemented")
    }

    override fun containsKey(key: K): Boolean {
        throw RuntimeException("Not yet implemented")
    }
}
