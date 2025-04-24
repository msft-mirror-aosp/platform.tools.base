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
package com.android.adblib.tools.debugging

import java.util.Objects

/**
 * A container object which may contain either
 * * nothing [OptionalValue.empty]
 * * or an error [OptionalValue.ofError]
 * * or a non-null value [OptionalValue.of]
 *
 * This class is similar to [java.util.Optional], except it may also contain an "error" with an associated
 * text message.
 */
class OptionalValue<T: Any> private constructor (private val obj: T) {

    /**
     * Whether this [OptionalValue] contains valid value. If `true`, [isError] and [isEmpty] are `false`.
     */
    val hasValue: Boolean
        get() = (obj !== EMPTY_SINGLETON) && !isError

    /**
     * Whether this [OptionalValue] contains the [empty] value. If `true`, [hasValue] and [isError] are `false`.
     */
    val isEmpty: Boolean
        get() = (obj === EMPTY_SINGLETON)

    /**
     * Whether this [OptionalValue] contains an error message. If `true`, [hasValue] and [isEmpty] are `false`.
     */
    val isError: Boolean
        get() = (obj is Error)

    /**
     * If a value is present (i.e. [hasValue] is `true`), returns the value, otherwise throws [NoSuchElementException].
     */
    fun getOrThrow(): T {
        if (!hasValue) {
            throw NoSuchElementException("No value present")
        }
        return obj
    }

    /**
     * If an error is present (i.e. [isError] is `true`), returns the error message, otherwise throws [NoSuchElementException].
     */
    fun getErrorMessageOrThrow(): String {
        return (obj as? Error)?.message ?: throw NoSuchElementException("No error present")
    }

    override fun hashCode(): Int {
        return Objects.hashCode(obj)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        return (other is OptionalValue<*>) && Objects.equals(this.obj, other.obj)
    }

    override fun toString(): String {
        return if (obj === EMPTY_SINGLETON) {
            "OptionalValue.empty"
        } else if (obj is Error) {
            "OptionalValue.error(\"${obj.message}\")"
        } else {
            "OptionalValue(${objToString()})"
        }
    }

    private fun objToString(): String {
        return if (obj is String) {
            "\"$obj\""
        } else {
            obj.toString()
        }
    }

    /**
     * The container class for [OptionalValue.ofError]
     */
    private data class Error(val message: String) {
        init {
            require(message.isNotEmpty()) { "Error message cannot be empty" }
        }
    }

    companion object {

        private val EMPTY_SINGLETON = Any()

        private val emptyValue = OptionalValue(EMPTY_SINGLETON)
        private val emptyStringValue = OptionalValue("")
        private val zeroIntValue = OptionalValue(0)
        private val trueValue = OptionalValue(true)
        private val falseValue = OptionalValue(false)

        /**
         * Returns an [OptionalValue] containing the given [value], which may not be `null`.
         */
        fun <T: Any> of(value: T): OptionalValue<T> {
            @Suppress("UNCHECKED_CAST", "KotlinConstantConditions")
            return when (value) {
                true -> trueValue as OptionalValue<T>
                false -> falseValue as OptionalValue<T>
                0 -> zeroIntValue as OptionalValue<T>
                "" -> emptyStringValue as OptionalValue<T>
                else -> {
                    OptionalValue(value)
                }
            }
        }

        /**
         * Returns an [OptionalValue] containing the given error [message]
         */
        fun <T: Any> ofError(message: String): OptionalValue<T> {
            @Suppress("UNCHECKED_CAST")
            val obj = Error(message) as T
            return OptionalValue(obj)
        }

        /**
         * Returns the empty [OptionalValue] instance. No value is present for this [OptionalValue].
         */
        fun <T: Any> empty(): OptionalValue<T> {
            @Suppress("UNCHECKED_CAST")
            return emptyValue as OptionalValue<T>
        }


        /**
         * Returns an [OptionalValue] a nullable [value], either [OptionalValue.empty] if [value]
         * is `null` or [OptionalValue.of] otherwise.
         */
        fun <T> ofNullable(value: T): OptionalValue<T & Any> {
            return if (value == null) {
                empty()
            } else {
                of(value)
            }
        }
    }
}

/**
 * Returns [OptionalValue.getOrThrow] or `null` if this [OptionalValue] does not contain valid value.
 */
fun <T: Any> OptionalValue<T>.getOrNull(): T? {
    return if (hasValue) getOrThrow() else null
}

/**
 * Returns [OptionalValue.getOrThrow] or [defaultValue] if this [OptionalValue] does not contain valid value.
 */
fun <T: Any> OptionalValue<T>.getOrDefault(defaultValue: T): T {
    return if (hasValue) {
        getOrThrow()
    } else {
        defaultValue
    }
}

/**
 * Executes [block] if [OptionalValue.hasValue] is `true`
 */
inline fun <T: Any> OptionalValue<T>.alsoIfValue(block: (T) -> Unit): OptionalValue<T> {
    if (hasValue) {
        block(getOrThrow())
    }
    return this
}

/**
 * Returns the most "precise" value of [this] and [other], where [OptionalValue.hasValue] is considered
 * "more precise" than [OptionalValue.isError], itself considered "more precise" than [OptionalValue.empty]
 */
fun <T: Any> OptionalValue<T>.orElse(other: OptionalValue<T>): OptionalValue<T> {
    return when {
        this.hasValue -> this
        other.hasValue -> other
        this.isError -> this
        else -> other
    }
}
