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

import org.gradle.api.Transformer
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import java.util.function.BiFunction

/**
 * Proxy class used to implement Gradle's Property. This wraps a [DslContentHolder] to record
 * the calls we care about.
 */
class PropertyProxy<T>(
    private val name: String,
    private val contentHolder: DslContentHolder
) : Property<T> {

    override fun set(value: T?) {
        contentHolder.call("$name.set", listOf(value), isVarArgs = false)
    }

    override fun get(): T {
        throw RuntimeException("Not yet implemented")
    }

    override fun getOrNull(): T? {
        throw RuntimeException("Not yet implemented")
    }

    override fun isPresent(): Boolean {
        throw RuntimeException("Not yet implemented")
    }

    override fun forUseAtConfigurationTime(): Provider<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun finalizeValue() {
        throw RuntimeException("Not yet implemented")
    }

    override fun finalizeValueOnRead() {
        throw RuntimeException("Not yet implemented")
    }

    override fun disallowChanges() {
        throw RuntimeException("Not yet implemented")
    }

    override fun disallowUnsafeRead() {
        throw RuntimeException("Not yet implemented")
    }

    override fun unset(): Property<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun unsetConvention(): Property<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun convention(provider: Provider<out T>): Property<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun convention(value: T?): Property<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun value(provider: Provider<out T>): Property<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun value(value: T?): Property<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun set(provider: Provider<out T>) {
        throw RuntimeException("Not yet implemented")
    }

    override fun <U : Any?, R : Any?> zip(
        right: Provider<U>,
        combiner: BiFunction<in T, in U, out R?>
    ): Provider<R> {
        throw RuntimeException("Not yet implemented")
    }

    override fun orElse(provider: Provider<out T>): Provider<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun orElse(value: T): Provider<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun <S : Any?> flatMap(transformer: Transformer<out Provider<out S>?, in T>): Provider<S> {
        throw RuntimeException("Not yet implemented")
    }

    override fun filter(spec: Spec<in T>): Provider<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun <S : Any?> map(transformer: Transformer<out S?, in T>): Provider<S> {
        throw RuntimeException("Not yet implemented")
    }

    override fun getOrElse(defaultValue: T): T {
        throw RuntimeException("Not yet implemented")
    }
}
