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
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.specs.Spec
import java.util.function.BiFunction

class SetPropertyProxy<T>(
    private val dslRecorder: DslRecorder
) : SetProperty<T> {

    override fun addAll(elements: MutableIterable<T>) {
        dslRecorder.call("addAll", elements.toList(), isVarArgs = false)
    }

    override fun addAll(vararg elements: T) {
        dslRecorder.call("addAll", elements.toList(), isVarArgs = true)
    }

    override fun add(element: T) {
        dslRecorder.call("add", listOf(element), isVarArgs = false)
    }

    override fun set(elements: MutableIterable<T>?) {
        dslRecorder.call("set", elements?.let { listOf(elements) } ?: listOf(), isVarArgs = false)
    }

    override fun get(): MutableSet<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun getOrNull(): MutableSet<T>? {
        throw RuntimeException("Not yet implemented")
    }

    override fun isPresent(): Boolean {
        throw RuntimeException("Not yet implemented")
    }

    override fun forUseAtConfigurationTime(): Provider<MutableSet<T>> {
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

    override fun unset(): SetProperty<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun unsetConvention(): SetProperty<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun empty(): SetProperty<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun convention(provider: Provider<out MutableIterable<T>>): SetProperty<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun convention(elements: MutableIterable<T>?): SetProperty<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun addAll(provider: Provider<out MutableIterable<T>>) {
        throw RuntimeException("Not yet implemented")
    }

    override fun add(provider: Provider<out T>) {
        throw RuntimeException("Not yet implemented")
    }

    override fun value(provider: Provider<out MutableIterable<T>>): SetProperty<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun value(elements: MutableIterable<T>?): SetProperty<T> {
        throw RuntimeException("Not yet implemented")
    }

    override fun set(provider: Provider<out MutableIterable<T>>) {
        throw RuntimeException("Not yet implemented")
    }

    override fun <U : Any?, R : Any?> zip(
        right: Provider<U>,
        combiner: BiFunction<in MutableSet<T>, in U, out R?>
    ): Provider<R> {
        throw RuntimeException("Not yet implemented")
    }

    override fun orElse(provider: Provider<out MutableSet<T>>): Provider<MutableSet<T>> {
        throw RuntimeException("Not yet implemented")
    }

    override fun orElse(value: MutableSet<T>): Provider<MutableSet<T>> {
        throw RuntimeException("Not yet implemented")
    }

    override fun <S : Any?> flatMap(transformer: Transformer<out Provider<out S>?, in MutableSet<T>>): Provider<S> {
        throw RuntimeException("Not yet implemented")
    }

    override fun filter(spec: Spec<in MutableSet<T>>): Provider<MutableSet<T>> {
        throw RuntimeException("Not yet implemented")
    }

    override fun <S : Any?> map(transformer: Transformer<out S?, in MutableSet<T>>): Provider<S> {
        throw RuntimeException("Not yet implemented")
    }

    override fun getOrElse(defaultValue: MutableSet<T>): MutableSet<T> {
        throw RuntimeException("Not yet implemented")
    }
}
