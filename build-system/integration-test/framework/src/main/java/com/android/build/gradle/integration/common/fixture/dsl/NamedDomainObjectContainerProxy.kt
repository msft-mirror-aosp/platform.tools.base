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

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.DomainObjectCollection
import org.gradle.api.NamedDomainObjectCollectionSchema
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.NamedDomainObjectSet
import org.gradle.api.Namer
import org.gradle.api.Rule
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import java.util.SortedMap
import java.util.SortedSet

class NamedDomainObjectContainerProxy<T>(
    private val theInterface: Class<T>,
    private val contentHolder: DslContentHolder,
): NamedDomainObjectContainer<T> {

    override fun named(
        name: String,
        configurationAction: Action<in T>
    ): NamedDomainObjectProvider<T> {
        val item = contentHolder.runNestedBlock("named", listOf(name), theInterface) {
            configurationAction.execute(this)
        }

        return NamedDomainObjectProviderProxy(item)
    }

    override fun create(name: String, configureAction: Action<in T>): T {
        return contentHolder.runNestedBlock("create", listOf(name), theInterface) {
            configureAction.execute(this)
        }
    }

    // -------

    override fun add(element: T): Boolean {
        throw RuntimeException("Not Supported")
    }

    override fun addAll(elements: Collection<T>): Boolean {
        throw RuntimeException("Not Supported")
    }

    override fun clear() {
        throw RuntimeException("Not Supported")
    }

    override fun iterator(): MutableIterator<T> {
        throw RuntimeException("Not Supported")
    }

    override fun isEmpty(): Boolean {
        throw RuntimeException("Not Supported")
    }

    override fun matching(spec: Closure<*>): NamedDomainObjectSet<T> {
        throw RuntimeException("Not Supported")
    }

    override fun whenObjectAdded(action: Closure<*>) {
        throw RuntimeException("Not Supported")
    }

    override fun whenObjectRemoved(action: Closure<*>) {
        throw RuntimeException("Not Supported")
    }

    override fun all(action: Closure<*>) {
        throw RuntimeException("Not Supported")
    }

    override fun findAll(spec: Closure<*>): MutableSet<T> {
        throw RuntimeException("Not Supported")
    }

    override fun getNamer(): Namer<T> {
        throw RuntimeException("Not Supported")
    }

    override fun getAsMap(): SortedMap<String, T> {
        throw RuntimeException("Not Supported")
    }

    override fun getNames(): SortedSet<String> {
        throw RuntimeException("Not Supported")
    }

    override fun findByName(name: String): T? {
        throw RuntimeException("Not Supported")
    }

    override fun getByName(name: String): T {
        throw RuntimeException("Not Supported")
    }

    override fun getByName(name: String, configureClosure: Closure<*>): T {
        throw RuntimeException("Not Supported")
    }

    override fun getAt(name: String): T {
        throw RuntimeException("Not Supported")
    }

    override fun addRule(rule: Rule): Rule {
        throw RuntimeException("Not Supported")
    }

    override fun addRule(description: String, ruleAction: Closure<*>): Rule {
        throw RuntimeException("Not Supported")
    }

    override fun addRule(description: String, ruleAction: Action<String>): Rule {
        throw RuntimeException("Not Supported")
    }

    override fun getRules(): MutableList<Rule> {
        throw RuntimeException("Not Supported")
    }

    override fun named(nameFilter: Spec<String>): NamedDomainObjectSet<T> {
        throw RuntimeException("Not Supported")
    }

    override fun named(name: String): NamedDomainObjectProvider<T> {
        throw RuntimeException("Not Supported")
    }

    override fun getCollectionSchema(): NamedDomainObjectCollectionSchema {
        throw RuntimeException("Not Supported")
    }

    override fun configure(configureClosure: Closure<*>): NamedDomainObjectContainer<T> {
        throw RuntimeException("Not Supported")
    }

    override fun create(name: String): T {
        throw RuntimeException("Not Supported")
    }

    override fun create(name: String, configureClosure: Closure<*>): T {
        throw RuntimeException("Not Supported")
    }

    override fun maybeCreate(name: String): T {
        throw RuntimeException("Not Supported")
    }

    override fun register(name: String): NamedDomainObjectProvider<T> {
        throw RuntimeException("Not Supported")
    }

    override val size: Int
        get() = throw RuntimeException("Not Supported")

    override fun register(
        name: String,
        configurationAction: Action<in T>
    ): NamedDomainObjectProvider<T> {
        throw RuntimeException("Not Supported")
    }

    override fun <S : T> named(
        name: String,
        type: Class<S>,
        configurationAction: Action<in S>
    ): NamedDomainObjectProvider<S> {
        throw RuntimeException("Not Supported")
    }

    override fun <S : T> named(name: String, type: Class<S>): NamedDomainObjectProvider<S> {
        throw RuntimeException("Not Supported")
    }

    override fun getByName(name: String, configureAction: Action<in T>): T {
        throw RuntimeException("Not Supported")
    }

    override fun configureEach(action: Action<in T>) {
        throw RuntimeException("Not Supported")
    }

    override fun all(action: Action<in T>) {
        throw RuntimeException("Not Supported")
    }

    override fun whenObjectRemoved(action: Action<in T>): Action<in T> {
        throw RuntimeException("Not Supported")
    }

    override fun whenObjectAdded(action: Action<in T>): Action<in T> {
        throw RuntimeException("Not Supported")
    }

    override fun matching(spec: Spec<in T>): NamedDomainObjectSet<T> {
        throw RuntimeException("Not Supported")
    }

    override fun <S : T> withType(
        type: Class<S>,
        configureClosure: Closure<*>
    ): DomainObjectCollection<S> {
        throw RuntimeException("Not Supported")
    }

    override fun <S : T> withType(
        type: Class<S>,
        configureAction: Action<in S>
    ): DomainObjectCollection<S> {
        throw RuntimeException("Not Supported")
    }

    override fun <S : T> withType(type: Class<S>): NamedDomainObjectSet<S> {
        throw RuntimeException("Not Supported")
    }

    override fun addAllLater(provider: Provider<out MutableIterable<T>>) {
        throw RuntimeException("Not Supported")
    }

    override fun addLater(provider: Provider<out T>) {
        throw RuntimeException("Not Supported")
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        throw RuntimeException("Not Supported")
    }

    override fun contains(element: T): Boolean {
        throw RuntimeException("Not Supported")
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        throw RuntimeException("Not Supported")
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        throw RuntimeException("Not Supported")
    }

    override fun remove(element: T): Boolean {
        throw RuntimeException("Not Supported")
    }
}
