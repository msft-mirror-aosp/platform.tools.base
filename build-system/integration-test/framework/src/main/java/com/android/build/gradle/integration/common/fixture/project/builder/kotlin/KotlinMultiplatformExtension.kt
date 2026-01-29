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

package com.android.build.gradle.integration.common.fixture.project.builder.kotlin

import com.android.build.gradle.integration.common.fixture.dsl.NamedDomainObjectContainerProxy
import com.android.build.gradle.integration.common.fixture.dsl.NamedDomainObjectProviderProxy
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.NamedDomainObjectProvider
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

/**
 * Top level interface for the `kotlin {}` in multi-platform test projects.
 *
 * The actual Kotlin top level type is an abstract class so we cannot use it with
 * [com.android.build.gradle.integration.common.fixture.dsl.DslProxy], and it also fails with ByteBuddy
 *
 * Therefore, this is used as an entry point. This exposes only what we need. This is implemented via the proxy so that we don't have to
 * bother with the implementation and the writing into build files.
 *
 * The normal Kotlin extension is [org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension]
 */
interface KotlinMultiplatformExtension : KotlinExtension {
  // we cannot put the androidLibrary() function here as we need to make this dynamic based on
  // the plugin being applied

  fun jvm()
}

// This is already an extension method in the original class from KMP, so we need to reimplement it the same
// way except we directly handle the proxy/dslRecorder
val NamedDomainObjectContainer<KotlinSourceSet>.androidMain: NamedDomainObjectProvider<KotlinSourceSet>
  get() {
    return sourceSetGetterFor("androidMain")
  }

private fun NamedDomainObjectContainer<KotlinSourceSet>.sourceSetGetterFor(name: String): NamedDomainObjectProviderProxy<KotlinSourceSet> {
  // we need to get access to the [DslRecorder] from the proxied interface
  val invocationHandler = this as NamedDomainObjectContainerProxy<KotlinSourceSet>

  return NamedDomainObjectProviderProxy(KotlinSourceSet::class.java, invocationHandler.dslRecorder.createChainedRecorder(name))
}
