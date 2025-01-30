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

/**
 * Utility method for test that always want to use DslProxy to run blocks
 */
fun <T> DslRecorder.runNestedBlock(
    name: String,
    parameters: List<Any>,
    theInterface: Class<T>,
    action: T.() -> Unit,
) {
    runNestedBlock(
        name = name,
        parameters = parameters,
        instanceProvider = {
            DslProxy.createProxy(theInterface, it)
        },
        action = action,
    )
}
