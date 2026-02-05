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

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.DomainObjectCollection
import org.gradle.api.DomainObjectSet
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec

class DomainObjectSetProxy<T : Any>(private val dslRecorder: DslRecorder) : DomainObjectSet<T> {

  override fun addAll(elements: Collection<T>): Boolean {
    dslRecorder.collectionAddAll(elements)
    return true
  }

  override fun add(element: T): Boolean {
    dslRecorder.collectionAdd(element)
    return true
  }

  override fun clear() {
    dslRecorder.call("clear", listOf(), isVarArgs = false)
  }

  override fun removeAll(elements: Collection<T>): Boolean {
    dslRecorder.call("removeAll", listOf(elements), isVarArgs = false)
    return true
  }

  override fun remove(element: T): Boolean {
    dslRecorder.call("remove", listOf(element), isVarArgs = false)
    return true
  }

  override fun <S : T> withType(type: Class<S?>): DomainObjectSet<S> {
    throw RuntimeException("Not yet implemented")
  }

  override fun matching(spec: Spec<in T>): DomainObjectSet<T> {
    throw RuntimeException("Not yet implemented")
  }

  override fun matching(spec: Closure<*>): DomainObjectSet<T> {
    throw RuntimeException("Not yet implemented")
  }

  override fun findAll(spec: Closure<*>): Set<T?> {
    throw RuntimeException("Not yet implemented")
  }

  override fun addLater(provider: Provider<out T>) {
    throw RuntimeException("Not yet implemented")
  }

  override fun addAllLater(provider: Provider<out Iterable<T>>) {
    throw RuntimeException("Not yet implemented")
  }

  override fun <S : T> withType(type: Class<S?>, configureAction: Action<in S>): DomainObjectCollection<S> {
    throw RuntimeException("Not yet implemented")
  }

  override fun <S : T> withType(type: Class<S>, configureClosure: Closure<*>): DomainObjectCollection<S> {
    throw RuntimeException("Not yet implemented")
  }

  override fun whenObjectAdded(action: Action<in T>): Action<in T> {
    throw RuntimeException("Not yet implemented")
  }

  override fun whenObjectAdded(action: Closure<*>) {
    throw RuntimeException("Not yet implemented")
  }

  override fun whenObjectRemoved(action: Action<in T>): Action<in T> {
    throw RuntimeException("Not yet implemented")
  }

  override fun whenObjectRemoved(action: Closure<*>) {
    throw RuntimeException("Not yet implemented")
  }

  override fun all(action: Action<in T>) {
    throw RuntimeException("Not yet implemented")
  }

  override fun all(action: Closure<*>) {
    throw RuntimeException("Not yet implemented")
  }

  override fun configureEach(action: Action<in T>) {
    throw RuntimeException("Not yet implemented")
  }

  override fun iterator(): MutableIterator<T> {
    throw RuntimeException("Not yet implemented")
  }

  override fun retainAll(elements: Collection<T>): Boolean {
    throw RuntimeException("Not yet implemented")
  }

  override val size: Int
    get() = throw RuntimeException("Not yet implemented")

  override fun contains(element: T): Boolean {
    throw RuntimeException("Not yet implemented")
  }

  override fun containsAll(elements: Collection<T>): Boolean {
    throw RuntimeException("Not yet implemented")
  }

  override fun isEmpty(): Boolean {
    throw RuntimeException("Not yet implemented")
  }
}
