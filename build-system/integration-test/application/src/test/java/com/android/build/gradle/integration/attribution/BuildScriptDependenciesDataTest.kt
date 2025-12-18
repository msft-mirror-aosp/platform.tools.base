/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.attribution

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor.ConfigurationCaching
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.options.StringOption
import com.android.buildanalyzer.common.AndroidGradlePluginAttributionData
import com.android.testutils.TestUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests the contents of [AndroidGradlePluginAttributionData.buildscriptDependenciesInfo]. */
class BuildScriptDependenciesDataTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            HelloWorldAndroid.setupKotlin(files)
        }
        // Setting useLatestKgpVersion = true will add
        // org.jetbrains.kotlin:kotlin-gradle-plugin:<KOTLIN_VERSION_FOR_TESTS>
        // to the build script classpath
        useLatestKgpVersion = true
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `test Kotlin Gradle plugin is present in buildscriptDependenciesInfo`() {
        val attributionDir = temporaryFolder.newFolder()
        rule.build.executor
            .with(StringOption.IDE_ATTRIBUTION_FILE_LOCATION, attributionDir.path)
            // buildscriptDependenciesInfo can't be collected when Isolated Projects is enabled
            .withConfigurationCaching(ConfigurationCaching.ON)
            .run("help")

        val attributionData = AndroidGradlePluginAttributionData.load(attributionDir)!!
        assertThat(attributionData.buildscriptDependenciesInfo)
            .contains("org.jetbrains.kotlin:kotlin-gradle-plugin:${TestUtils.KOTLIN_VERSION_FOR_TESTS}")
    }

}
