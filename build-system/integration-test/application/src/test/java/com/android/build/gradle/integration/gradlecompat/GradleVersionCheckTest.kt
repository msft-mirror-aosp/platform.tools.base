/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.build.gradle.integration.gradlecompat

import com.android.SdkConstants.GRADLE_LATEST_VERSION
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test

/** Tests whether the Gradle version check takes effect. */
class GradleVersionCheckTest {

    @get:Rule
    val rule = GradleRule
        .configure().withGradleLocation { version(OLD_GRADLE_VERSION) }
        .from { androidApplication {} }

    @Test
    fun testGradleVersionCheck() {
        // Run the build twice, it should fail with the same message (regression test for b/265296706)
        repeat(2) {
            val result = rule.build.executor.expectFailure().run("help")
            result.assertErrorContains("Minimum supported Gradle version is $GRADLE_LATEST_VERSION. Current version is $OLD_GRADLE_VERSION.")
        }
    }
}

/**
 * An old version of Gradle to use in this test.
 *
 * (This can't be lower than 8.4 as those Gradle versions do not support JDK 21, similar to
 * b/243592738.)
 */
private const val OLD_GRADLE_VERSION = "8.4"
