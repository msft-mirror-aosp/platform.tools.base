/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.adblib

import com.android.adblib.CoroutineScopeCache.Key
import com.android.adblib.impl.CoroutineScopeCacheImpl
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentMap

/**
 * A thread safe in-memory cache of [Key&lt;T&gt;][Key] to `T` values whose lifetime is tied
 * to a [CoroutineScope].
 *
 * Values can optionally implement [AutoCloseable], in which case these values are
 * [closed][java.lang.AutoCloseable.close] when removed from the cache or when the cache is
 * closed.
 */
@IsThreadSafe
abstract class CoroutineScopeCache : AutoCloseable {

    /**
     * The scope that defines the lifecycle of this cache, i.e. if the [scope] is
     * cancelled, the cache is cleared and [java.lang.AutoCloseable.close] is called
     * on all values implementing [AutoCloseable].
     */
    abstract val scope: CoroutineScope

    /**
     * Returns the value for the given [key]. If the key is not found in the map,
     * calls the [defaultValue] function, puts its result into the map under the
     * given key and returns it.
     *
     * This method guarantees not to put the value into the map if the key is
     * already there, but the [defaultValue] function may be invoked even if
     * the key is already in the map.
     *
     * **Note**: [getOrPut] and [getOrPutSuspending] use separate in-memory caches
     * internally to prevent conflicting behavior between suspending and
     * non-suspending computations.
     *
     * @see [ConcurrentMap.getOrPut]
     */
    inline fun <T> getOrPut(
        key: Key<T>,
        crossinline defaultValue: () -> T
    ): T {
        val valueNotPresent = noValueSingleton<T>()
        val value = getOrDefault(key, valueNotPresent)
        return if (value === valueNotPresent) {
            getOrPutWorker(key) { defaultValue() }
        } else {
            value
        }
    }

    /**
     * Suspending version of [getOrPut]: returns the value for the given [key].
     * If the key is not found in the map, calls the [defaultValue] coroutine,
     * puts its result into the map under the given key and returns it.
     *
     * Unlike [getOrPut], this method guarantees that [defaultValue] is invoked
     * at most once if the key is not already present in the map. This implies that,
     * for a given [key], the first caller gets to compute the value stored in the
     * map, and follow-up callers are suspended until the value is computed.
     *
     * If [defaultValue] throws an exception for a given [key], a new computation will be
     * triggered for subsequent callers of this method.
     *
     * **Note**: [getOrPut] and [getOrPutSuspending] use separate in-memory caches
     * internally to prevent conflicting behavior between suspending and
     * non-suspending computations.
     */
    suspend inline fun <T> getOrPutSuspending(
        key: Key<T>,
        crossinline defaultValue: suspend CoroutineScope.() -> T
    ): T {
        val noValue = noValueSingleton<T>()
        val value = getOrSuspendingDefault(key, noValue)
        return if (value === noValue) {
            getOrPutSuspendingWorker(key) { defaultValue() }
        } else {
            value
        }
    }

    /**
     * Suspending version of [getOrPut]: returns the value for the given [key].
     * If the key is not found in the map, asynchronously starts evaluating the
     * [defaultValue] coroutine, then immediately returns [fastDefaultValue].
     * Once the [defaultValue] coroutine completes, if successful, the resulting value
     * is stored in the map.
     *
     * Unlike [getOrPut], this method guarantees that [defaultValue] is invoked
     * at most once if the key is not already present in the map. This implies that,
     * for a given [key], the first caller gets to compute the value stored in the
     * map, and follow-up callers get [fastDefaultValue] until the value is computed.
     *
     * If a computation fails it will be retried the next time this method is called.
     *
     * **Note**: [getOrPut] and [getOrPutSuspending] use separate in-memory caches
     * internally to prevent conflicting behavior between suspending and
     * non-suspending computations.
     */
    inline fun <T> getOrPutSuspending(
        key: Key<T>,
        crossinline fastDefaultValue: () -> T,
        crossinline defaultValue: suspend CoroutineScope.() -> T
    ): T {
        val noValue = noValueSingleton<T>()
        val value = getOrSuspendingDefault(key, noValue)
        return if (value === noValue) {
            getOrPutSuspendingWorker(key, { fastDefaultValue() }, { defaultValue() })
        } else {
            value
        }
    }

    /**
     * Helper method for the [getOrPut] inline extension function
     */
    @PublishedApi
    internal abstract fun <T> getOrPutWorker(key: Key<T>, defaultValue: () -> T): T

    /**
     * Helper method for the [getOrPutSuspending] inline extension function
     */
    @PublishedApi
    internal abstract suspend fun <T> getOrPutSuspendingWorker(
        key: Key<T>,
        defaultValue: suspend CoroutineScope.() -> T
    ): T

    /**
     * Helper method for the [getOrPutSuspending] inline extension function
     */
    @PublishedApi
    internal abstract fun <T> getOrPutSuspendingWorker(
        key: Key<T>,
        fastDefaultValue: () -> T,
        defaultValue: suspend CoroutineScope.() -> T
    ): T

    /**
     * Helper method for the [getOrPut] inline extension function
     */
    @PublishedApi
    internal abstract fun <T> getOrDefault(key: Key<T>, defaultValue: T): T

    /**
     * Helper method for the [getOrPutSuspending] inline extension function
     */
    @PublishedApi
    internal abstract fun <T> getOrSuspendingDefault(key: Key<T>, defaultValue: T): T

    /**
     * Key type for the [CoroutineScopeCache]. Keys should implement [equals] and [hashCode].
     */
    open class Key<T>(
        /**
         * Friendly name of the key, does not need to be an identifier.
         */
        val name: String
    ) {

        override fun toString(): String {
            return "${Key::class.simpleName}(\"$name\")"
        }
    }

    companion object {
        fun create(parentScope: CoroutineScope, description: String): CoroutineScopeCache {
            return CoroutineScopeCacheImpl(parentScope, description)
        }
    }
}

/**
 * Same as [getOrPut], but guarantees [defaultValue] is executed only once
 */
inline fun <T: Any> CoroutineScopeCache.getOrPutSynchronized(
    key: Key<T>,
    crossinline defaultValue: () -> T
): T {
    // Note: Using unsafe cast for the "key" is ok, as the "Key" never stores any "T" value, as
    //  "T" is only used as a "marker" to make the "getOrPut" API type safe.
    @Suppress("UNCHECKED_CAST")
    val uncheckedKey = key as Key<RunOnlyOnce<T>>
    return getOrPut(uncheckedKey) {
        // Note: "getOrPut" may run this block multiple times (in case of concurrent access),
        // but will always a single unique instance of "RunOnlyOnce" (the other ones are
        // discarded).
        RunOnlyOnce()
    }.runOnlyOnce(defaultValue)
}

private val NO_VALUE = Any()

@PublishedApi
internal fun <T> noValueSingleton(): T {
    // Note: This is a "safe" cast in the sense the default value is just used
    // as a custom object reference to check if there is an actual value for `key`
    // in the case. The only requirement is for the default value to never be
    // present as an actual value.
    @Suppress("UNCHECKED_CAST")
    return NO_VALUE as T
}

@PublishedApi
internal class RunOnlyOnce<T: Any>: AutoCloseable {
    @Volatile
    @PublishedApi
    internal var lazyValue: T? = null

    inline fun runOnlyOnce(crossinline block: () -> T): T {
        return lazyValue ?: runOnlyOnceSlow { block() }
    }

    @PublishedApi
    internal fun runOnlyOnceSlow(block: () -> T): T {
        // Use "double check locking" to ensure the code is run only once
        // See https://en.wikipedia.org/wiki/Double-checked_locking#Usage_in_Java
        var localValue = lazyValue
        if (localValue == null) {
            synchronized(this) {
                localValue = lazyValue
                if (localValue == null) {
                    block().also {
                        lazyValue = it
                        localValue = it
                    }
                }
            }
        }
        return localValue!!
    }

    override fun close() {
        synchronized(this) {
            (lazyValue as? AutoCloseable)?.close()
        }
    }
}
