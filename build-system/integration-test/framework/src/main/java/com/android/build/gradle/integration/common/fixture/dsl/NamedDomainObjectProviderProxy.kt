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

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Transformer
import org.gradle.api.provider.Provider
import org.gradle.api.specs.Spec
import java.util.function.BiFunction

/**
 * A proxy for a [NamedDomainObjectProvider].
 *
 * This can only be used when we are sure that the provider is not dangling. This is mostly
 * going to be use for things like [com.android.build.gradle.integration.common.fixture.project.builder.kotlin.KotlinMultiplatformExtension.androidMain]
 */
class NamedDomainObjectProviderProxy<T>(
    private val theInterface: Class<T>,
    private val contentHolder: DslContentHolder
): NamedDomainObjectProvider<T> {

    override fun configure(action: Action<in T>) {
        contentHolder.runNestedBlock(
            "configure",
            listOf(),
            instanceProvider = { DslProxy.createProxy(theInterface, it) },
        ) {
           action.execute(this)
        }
    }

    override fun get(): T {
        throw RuntimeException("Do not use the return value of NamedDomainObjectProvider.get, use configure instead")
    }

    override fun getOrNull(): T? {
        throw RuntimeException("Do not use the return value of NamedDomainObjectProvider.getOrNull, use configure instead")
    }

    override fun isPresent(): Boolean {
        throw RuntimeException("Do not use NamedDomainObjectProvider.isPresent")
    }

    override fun forUseAtConfigurationTime(): Provider<T> {
        throw RuntimeException("Do not use NamedDomainObjectProvider.forUseAtConfigurationTime")
    }

    override fun getName(): String {
        throw RuntimeException("Do not use NamedDomainObjectProvider.getName")
    }


    override fun <U : Any?, R : Any?> zip(
        right: Provider<U>,
        combiner: BiFunction<in T, in U, out R?>
    ): Provider<R> {
        throw RuntimeException("Do not use NamedDomainObjectProvider.zip")
    }

    override fun orElse(provider: Provider<out T>): Provider<T> {
        throw RuntimeException("Do not use NamedDomainObjectProvider.orElse")
    }

    override fun orElse(value: T): Provider<T> {
        throw RuntimeException("Do not use NamedDomainObjectProvider.orElse")
    }

    override fun <S : Any?> flatMap(transformer: Transformer<out Provider<out S>?, in T>): Provider<S> {
        throw RuntimeException("Do not use NamedDomainObjectProvider.flatMap")
    }

    override fun filter(spec: Spec<in T>): Provider<T> {
        throw RuntimeException("Do not use NamedDomainObjectProvider.filter")
    }

    override fun <S : Any?> map(transformer: Transformer<out S?, in T>): Provider<S> {
        throw RuntimeException("Do not use NamedDomainObjectProvider.map")
    }

    override fun getOrElse(defaultValue: T): T {
        throw RuntimeException("Do not use NamedDomainObjectProvider.getOrElse")
    }
}
