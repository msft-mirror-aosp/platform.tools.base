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

package com.android.build.gradle.integration.fixture

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.fixture.testprojects.BuildFileType.KTS
import org.junit.Rule
import org.junit.Test

/**
 * Currently, all tests use Groovy build file, so this test uses KTS as a basic smoke test
 */
class KtsTest {
    @get:Rule
    val rule = GradleRule.configure()
        .withCreationOptions {
            buildFileType = KTS
        }.from {
            androidApplication(":app") {
                HelloWorldAndroid.setupJava(files)
            }
        }

    @Test
    fun test() {
        rule.build.executor.run(":app:assembleDebug")
    }
}
