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

import java.io.File
import java.util.function.BiFunction
import org.gradle.api.Transformer
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec

/** Proxy class used to implement Gradle's Property. This wraps a [DslRecorder] to record the calls we care about. */
class RegularFilePropertyProxy(private val dslRecorder: DslRecorder) : RegularFileProperty {
  override fun set(value: RegularFile?) {
    dslRecorder.call("set", listOf(value), isVarArgs = false)
  }

  override fun get(): RegularFile {
    throw RuntimeException("Not yet implemented")
  }

  override fun getOrNull(): RegularFile? {
    throw RuntimeException("Not yet implemented")
  }

  override fun isPresent(): Boolean {
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

  override fun unset(): Property<RegularFile> {
    throw RuntimeException("Not yet implemented")
  }

  override fun unsetConvention(): Property<RegularFile> {
    throw RuntimeException("Not yet implemented")
  }

  override fun convention(provider: Provider<out RegularFile>): RegularFileProperty {
    throw RuntimeException("Not yet implemented")
  }

  override fun convention(value: RegularFile?): RegularFileProperty {
    throw RuntimeException("Not yet implemented")
  }

  override fun value(provider: Provider<out RegularFile>): RegularFileProperty {
    throw RuntimeException("Not yet implemented")
  }

  override fun fileValue(file: File?): RegularFileProperty {
    throw RuntimeException("Not yet implemented")
  }

  override fun fileProvider(provider: Provider<File>): RegularFileProperty {
    throw RuntimeException("Not yet implemented")
  }

  override fun value(value: RegularFile?): RegularFileProperty {
    throw RuntimeException("Not yet implemented")
  }

  override fun set(provider: Provider<out RegularFile>) {
    throw RuntimeException("Not yet implemented")
  }

  override fun <U : Any, R : Any> zip(right: Provider<U>, combiner: BiFunction<in RegularFile, in U, out R?>): Provider<R> {
    throw RuntimeException("Not yet implemented")
  }

  override fun orElse(provider: Provider<out RegularFile>): Provider<RegularFile> {
    throw RuntimeException("Not yet implemented")
  }

  override fun orElse(value: RegularFile): Provider<RegularFile> {
    throw RuntimeException("Not yet implemented")
  }

  override fun <S : Any> flatMap(transformer: Transformer<out Provider<out S>?, in RegularFile>): Provider<S> {
    throw RuntimeException("Not yet implemented")
  }

  override fun filter(spec: Spec<in RegularFile>): Provider<RegularFile> {
    throw RuntimeException("Not yet implemented")
  }

  override fun <S : Any> map(transformer: Transformer<out S?, in RegularFile>): Provider<S> {
    throw RuntimeException("Not yet implemented")
  }

  override fun getOrElse(defaultValue: RegularFile): RegularFile {
    throw RuntimeException("Not yet implemented")
  }

  override fun getAsFile(): Provider<File> {
    throw RuntimeException("Not yet implemented")
  }

  override fun set(file: File?) {
    dslRecorder.call("set", listOf(file), isVarArgs = false)
  }

  override fun getLocationOnly(): Provider<RegularFile> {
    throw RuntimeException("Not yet implemented")
  }
}
