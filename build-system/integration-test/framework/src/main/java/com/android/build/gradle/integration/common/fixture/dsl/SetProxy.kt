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

class SetProxy<T>(
    private val name: String,
    private val contentHolder: DslContentHolder
): MutableSet<T> {

    override fun addAll(elements: Collection<T>): Boolean {
        contentHolder.collectionAddAll(name, elements)
        return true
    }

    override fun add(element: T): Boolean {
        contentHolder.collectionAdd(name, element)
        return true
    }

    // ------
    // below here are all the method not related to adding and are therefore
    // not supported by the proxy.
    // We'll adapt as we see usage.


    override val size: Int
        get() = throw UnsupportedOperationException("SetProxy does not support this. Add support if needed")

    override fun clear() {
        throw UnsupportedOperationException("SetProxy does not support this. Add support if needed")
    }

    override fun isEmpty(): Boolean {
        throw UnsupportedOperationException("SetProxy does not support this. Add support if needed")
    }

    override fun iterator(): MutableIterator<T> {
        throw UnsupportedOperationException("SetProxy does not support this. Add support if needed")
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        throw UnsupportedOperationException("SetProxy does not support this. Add support if needed")
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        throw UnsupportedOperationException("SetProxy does not support this. Add support if needed")
    }

    override fun remove(element: T): Boolean {
        throw UnsupportedOperationException("SetProxy does not support this. Add support if needed")
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        throw UnsupportedOperationException("SetProxy does not support this. Add support if needed")
    }

    override fun contains(element: T): Boolean {
        throw UnsupportedOperationException("SetProxy does not support this. Add support if needed")
    }
}
