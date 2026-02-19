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

import org.gradle.api.Action
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyConstraint
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.FileCollectionDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyCollector
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderConvertible

class DependencyCollectorProxy(private val dslRecorder: DslRecorder) : DependencyCollector {

  override fun add(dependencyNotation: CharSequence) {
    dslRecorder.call("add", listOf(dependencyNotation), isVarArgs = false)
  }

  override fun add(dependencyNotation: CharSequence, configuration: Action<in ExternalModuleDependency>) {
    throw RuntimeException("Implement as needed")
  }

  override fun add(files: FileCollection) {
    throw RuntimeException("Implement as needed")
  }

  override fun add(files: FileCollection, configuration: Action<in FileCollectionDependency>) {
    throw RuntimeException("Implement as needed")
  }

  override fun add(externalModule: ProviderConvertible<out MinimalExternalModuleDependency>) {
    throw RuntimeException("Implement as needed")
  }

  override fun add(
    externalModule: ProviderConvertible<out MinimalExternalModuleDependency>,
    configuration: Action<in ExternalModuleDependency>,
  ) {
    throw RuntimeException("Implement as needed")
  }

  override fun add(dependency: Dependency) {
    throw RuntimeException("Implement as needed")
  }

  override fun <D : Dependency?> add(dependency: D, configuration: Action<in D>) {
    throw RuntimeException("Implement as needed")
  }

  override fun add(dependency: Provider<out Dependency>) {
    throw RuntimeException("Implement as needed")
  }

  override fun <D : Dependency?> add(dependency: Provider<out D>, configuration: Action<in D>) {
    throw RuntimeException("Implement as needed")
  }

  override fun addConstraint(dependencyConstraint: DependencyConstraint) {
    throw RuntimeException("Implement as needed")
  }

  override fun addConstraint(dependencyConstraint: DependencyConstraint, configuration: Action<in DependencyConstraint>) {
    throw RuntimeException("Implement as needed")
  }

  override fun addConstraint(dependencyConstraint: Provider<out DependencyConstraint>) {
    throw RuntimeException("Implement as needed")
  }

  override fun addConstraint(dependencyConstraint: Provider<out DependencyConstraint>, configuration: Action<in DependencyConstraint>) {
    throw RuntimeException("Implement as needed")
  }

  override fun <D : Dependency?> bundle(bundle: MutableIterable<D>) {
    throw RuntimeException("Implement as needed")
  }

  override fun <D : Dependency?> bundle(bundle: MutableIterable<D>, configuration: Action<in D>) {
    throw RuntimeException("Implement as needed")
  }

  override fun <D : Dependency?> bundle(bundle: Provider<out MutableIterable<D>>) {
    throw RuntimeException("Implement as needed")
  }

  override fun <D : Dependency?> bundle(bundle: Provider<out MutableIterable<D>>, configuration: Action<in D>) {
    throw RuntimeException("Implement as needed")
  }

  override fun <D : Dependency?> bundle(bundle: ProviderConvertible<out MutableIterable<D>>) {
    throw RuntimeException("Implement as needed")
  }

  override fun <D : Dependency?> bundle(bundle: ProviderConvertible<out MutableIterable<D>>, configuration: Action<in D>) {
    throw RuntimeException("Implement as needed")
  }

  override fun getDependencies(): Provider<MutableSet<Dependency>> {
    throw RuntimeException("Implement as needed")
  }

  override fun getDependencyConstraints(): Provider<MutableSet<DependencyConstraint>> {
    throw RuntimeException("Implement as needed")
  }
}
