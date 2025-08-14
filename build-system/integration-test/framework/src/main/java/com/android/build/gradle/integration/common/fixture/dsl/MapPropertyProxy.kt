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

import org.gradle.api.Transformer
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import java.util.function.BiFunction

/**
 * Proxy class used to implement Gradle's Property. This wraps a [DslRecorder] to record
 * the calls we care about.
 */
class MapPropertyProxy<K : Any, V : Any>(
    private val dslRecorder: DslRecorder
): MapProperty<K, V> {

    override fun empty(): MapProperty<K, V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun getting(key: K): Provider<V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun set(entries: Map<out K, V>?) {
        throw RuntimeException("Not yet implemented")
    }

    override fun set(provider: Provider<out Map<out K, V>>) {
        throw RuntimeException("Not yet implemented")
    }

    override fun value(entries: Map<out K, V>?): MapProperty<K, V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun value(provider: Provider<out Map<out K, V>>): MapProperty<K, V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun put(key: K, value: V) {
        dslRecorder.mapPut(key as Any, value)
    }

    override fun put(key: K, providerOfValue: Provider<out V>
    ) {
        throw RuntimeException("Not yet implemented")
    }

    override fun putAll(entries: Map<out K, V>) {
        throw RuntimeException("Not yet implemented")
    }

    override fun putAll(provider: Provider<out Map<out K, V>>) {
        throw RuntimeException("Not yet implemented")
    }

    override fun keySet(): Provider<Set<K>> {
        throw RuntimeException("Not yet implemented")
    }

    override fun convention(value: Map<out K, V>?): MapProperty<K, V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun convention(valueProvider: Provider<out Map<out K, V>>): MapProperty<K, V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun unset(): MapProperty<K, V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun unsetConvention(): MapProperty<K, V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun finalizeValue() {
        throw RuntimeException("Not yet implemented")
    }

    override fun get(): Map<K, V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun getOrNull(): Map<K, V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun getOrElse(defaultValue: Map<K, V>): Map<K, V> {
        throw RuntimeException("Not yet implemented")
    }

    override fun <S : Any> map(transformer: Transformer<out S?, in MutableMap<K, V>>): Provider<S> {
        throw RuntimeException("Not yet implemented")
    }

    override fun filter(spec: Spec<in MutableMap<K, V>>): Provider<Map<K, V>> {
        throw RuntimeException("Not yet implemented")
    }

    override fun <S : Any> flatMap(transformer: Transformer<out Provider<out S>?, in MutableMap<K, V>>): Provider<S> {
        throw RuntimeException("Not yet implemented")
    }

    override fun isPresent(): Boolean {
        throw RuntimeException("Not yet implemented")
    }

    override fun orElse(value: Map<K, V>): Provider<Map<K, V>> {
        throw RuntimeException("Not yet implemented")
    }

    override fun orElse(provider: Provider<out Map<K, V>>): Provider<Map<K, V>> {
        throw RuntimeException("Not yet implemented")
    }

    override fun <U : Any, R : Any> zip(
        right: Provider<U>,
        combiner: BiFunction<in MutableMap<K, V>, in U, out R?>
    ): Provider<R> {
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
}
