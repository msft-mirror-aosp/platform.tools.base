/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.build.gradle.integration.dagger

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.BooleanOption
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests that the hilt plugin is able to resolve flavored dependencies correctly
 */
class DaggerHiltFlavoredTest {
    @get:Rule
    var project = GradleTestProject.builder()
        .fromTestProject("dagger-hilt-flavored-project")
        // We had a workaround specifically for dagger (which is now removed but is still needed in this case)
        // as no Dagger plugin is released yet which doesn't rely on this workaround.
        .addGradleProperty(BooleanOption.ENABLE_IDENTITY_TRANSFORMS_FOR_PROCESSED_ARTIFACTS, true)
        .create()

    @Before
    fun setAgpVersion() {
        TestFileUtils.searchAndReplace(project.buildFile,
            "version '+'",
            "version '" + GradleTestProject.ANDROID_GRADLE_PLUGIN_VERSION + "'")
    }

    @Test
    fun doBuild() {
        project.executor()
            .with(BooleanOption.BUILT_IN_KOTLIN, false)
            .with(BooleanOption.USE_NEW_DSL, false)
            .run(":app:assembleMinApi21DemoDebug")
    }
}
