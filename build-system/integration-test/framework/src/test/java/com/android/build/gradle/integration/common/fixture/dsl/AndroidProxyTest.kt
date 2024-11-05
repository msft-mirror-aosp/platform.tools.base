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

import com.android.build.api.dsl.ApplicationExtension
import org.junit.Test

/**
 * Class to validate that we support all the methods from the Android extensions,
 *
 * This does not test the content, this is handled by [BasicDslProxyTest] and [DslContentHolderTest]
 */
class AndroidProxyTest {

    @Test
    fun testApplication() {
        val contentHolder = DefaultDslContentHolder()
        contentHolder.runNestedBlock("android", ApplicationExtension::class.java) {
            namespace = "foo"

            androidResources {
                generateLocaleConfig = true
            }

            compileOptions {
                isCoreLibraryDesugaringEnabled = true
            }
        }
    }
}
