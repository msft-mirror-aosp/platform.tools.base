/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.build.gradle.internal.dexing

import com.android.build.gradle.internal.dependency.GenericTransformParameters
import com.android.build.gradle.internal.r8.isolatedR8Classpath
import com.android.build.gradle.internal.services.R8ClassloaderBuildService
import com.android.build.gradle.internal.services.TaskCreationServices
import com.android.build.gradle.internal.utils.setDisallowChanges
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Internal

/**
 * Inputs of a task (or a `@Nested` type of a task) that runs R8, D8 or L8 through the isolated R8 [ClassLoader] (see [withR8ClassLoader]).
 *
 * Set them with [configureIsolatedR8].
 */
interface IsolatedR8TaskInputs {

  /** Maven-resolved R8 to run, or empty to use the R8 bundled with AGP. */
  @get:Classpath val r8Classpath: ConfigurableFileCollection

  /** Caches the isolated R8 [ClassLoader] for [r8Classpath] across the build. */
  @get:ServiceReference val r8ClassloaderBuildService: Property<R8ClassloaderBuildService>
}

/**
 * Parameters of an artifact transform that runs R8, D8 or L8 through the isolated R8 [ClassLoader] (see [withR8ClassLoader]).
 *
 * Set them with [configureIsolatedR8].
 */
interface IsolatedR8TransformParameters : GenericTransformParameters {

  /** Maven-resolved R8 to run, or empty to use the R8 bundled with AGP. */
  @get:Classpath val r8Classpath: ConfigurableFileCollection

  /** Caches the isolated R8 [ClassLoader] for [r8Classpath] across the build. */
  @get:Internal val r8ClassloaderBuildService: Property<R8ClassloaderBuildService>
}

/** Sets the [IsolatedR8TaskInputs] from [isolatedR8Classpath] and the registered [R8ClassloaderBuildService]. */
fun IsolatedR8TaskInputs.configureIsolatedR8(project: Project, services: TaskCreationServices) {
  isolatedR8Classpath(services.projectOptions, services.r8FromMaven)?.let { r8Classpath.from(it) }
  r8Classpath.disallowChanges()
  r8ClassloaderBuildService.setDisallowChanges(R8ClassloaderBuildService.RegistrationAction(project).execute())
}

/**
 * Sets the [IsolatedR8TransformParameters] from the given [r8Classpath] (`null` means the R8 bundled with AGP, see [isolatedR8Classpath])
 * and [r8ClassloaderBuildService].
 */
fun IsolatedR8TransformParameters.configureIsolatedR8(
  r8Classpath: FileCollection?,
  r8ClassloaderBuildService: Provider<R8ClassloaderBuildService>,
) {
  r8Classpath?.let { this.r8Classpath.from(it) }
  this.r8ClassloaderBuildService.set(r8ClassloaderBuildService)
}
