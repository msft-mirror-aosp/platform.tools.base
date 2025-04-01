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
package com.android.adblib.tools.debugging.utils

import com.android.adblib.utils.SuppressedExceptions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * This class acts as a container of [AutoCloseable] elements that will be closes when this
 * container is closed. This class is thread safe.
 *
 * Iterating over this collection will not reflect additions or removals from the collection
 * since this collection's iterator provides a snapshot of the state of the collection when the
 * iterator was constructed. No synchronization is needed while traversing the collection.
 */
internal class ConcurrentAutoCloseableCollection<T> : AutoCloseable, Iterable<T> {

    private val list = CopyOnWriteArrayList<T>()
    private val lock = ReentrantLock()
    private var isClosed = false

    fun add(element: T) {
        lock.withLock {
            if (!isClosed) {
                list.add(element)
            } else {
                // If the collection is closed, immediately close the added AutoCloseable
                runCatching {
                    (element as? AutoCloseable)?.close()
                }.onFailure {
                        val error =
                            Exception("Error closing element when adding it to a closed collection")
                        error.addSuppressed(it)
                        throw error
                    }
            }
        }
    }

    override fun close() {
        lock.withLock {
            if (!isClosed) {
                isClosed = true
                val toClose = list.filterIsInstance<AutoCloseable>()
                list.clear()
                closeAll(toClose)
            }
        }
    }

    /**
     * Returns an iterator over the elements in this collection.
     *
     * The returned iterator provides a snapshot of this collection, and it will not reflect
     * additions or removals from the collection. No synchronization is needed while
     * traversing the iterator.
     */
    override fun iterator(): Iterator<T> {
        return list.iterator()
    }

    companion object {
        private fun closeAll(toClose: List<AutoCloseable>) {
            var closeExceptions = SuppressedExceptions.init()
            toClose.forEach {
                runCatching {
                    it.close()
                }.onFailure {
                    closeExceptions = SuppressedExceptions.add(closeExceptions, it)
                }
            }
            if (closeExceptions.isNotEmpty()) {
                val error =
                    Exception("One or more errors closing elements of auto closable collection")
                closeExceptions.forEach { error.addSuppressed(it) }
                throw error
            }
        }
    }
}
