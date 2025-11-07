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

package com.android.build.gradle.integration.api

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionContainer
import org.junit.Rule
import org.junit.Test

/**
 * Smoke tests for the flag to enable new DSL implementations
 */
class NewDslImplementationSmokeTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication { }
        androidLibrary { }
        androidFeature { }
        androidTest {
            android {
                targetProjectPath = ":app"
            }
        }
    }


    @Test
    fun smokeTest() {
        rule.build.executor
            .with(BooleanOption.USE_NEW_DSL, true)
            .run(":app:tasks", ":lib:tasks", ":feature:tasks", ":test:tasks")
    }
}

class CheckDslAccessibility: GenericCallback {
    override fun handleProject(
        project: Project,
    ) {
        project.extensions.getByType(LibraryExtension::class.java)
        project.extensions.checkNotRegistered(com.android.build.gradle.LibraryExtension::class.java)
        project.extensions.checkNotRegistered(com.android.build.gradle.BaseExtension::class.java)
        println("CheckDslAccessibility checks done")
    }

    private fun ExtensionContainer.checkNotRegistered(type: Class<*>) {
        var failure: Throwable? = null
        try {
            getByType(type)
        } catch (e: Throwable) {
            failure = e
        }
        checkNotNull(failure) { "${type.name} should not be registered" }
    }
}

/**
 * Smoke tests that the previous DSL interfaces are not exposed.
 */
class OldDslNotRegisteredTest {

    @get:Rule
    val rule = GradleRule.from {
        androidLibrary {
            pluginCallbacks += CheckDslAccessibility::class.java
        }
        gradleProperties {
            add(BooleanOption.USE_NEW_DSL, true)
        }
    }

    @Test
    fun smokeTest() {
        val result = rule.build.executor
            .run(":lib:tasks")
        result.assertOutputContains("CheckDslAccessibility checks done")
    }
}
