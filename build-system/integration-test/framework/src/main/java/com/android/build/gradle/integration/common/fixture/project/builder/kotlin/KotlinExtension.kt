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

package com.android.build.gradle.integration.common.fixture.project.builder.kotlin

import net.bytebuddy.ByteBuddy
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions

/**
 * Top level interface for the `kotlin {}` in test projects.
 *
 * The actual Kotlin top level type is an abstract class so we cannot use it with
 * [com.android.build.gradle.integration.common.fixture.dsl.DslProxy], and it also fails with
 * [ByteBuddy]
 *
 * Therefore, this is used as an entry point. This exposes only what we need. This is implemented
 * via the proxy so that we don't have to bother with the implementation and the writing into
 * build files.
 */
interface KotlinExtension {

    val compilerOptions: KotlinJvmCompilerOptions /* compiled code */
    fun compilerOptions(configure: KotlinJvmCompilerOptions.() -> Unit)

    fun explicitApi()

    fun explicitApiWarning()

    fun jvmToolchain(jdkVersion: Int)

    fun jvmToolchain(action: org.gradle.api.Action<org.gradle.jvm.toolchain.JavaToolchainSpec>)

    var coreLibrariesVersion: String

    var explicitApi: org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode?
}
