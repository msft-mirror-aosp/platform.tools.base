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

package com.android.build.gradle.integration.common.fixture.project

import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.GRADLE_TEST_VERSION
import com.android.testutils.TestUtils
import com.android.testutils.truth.PathSubject.assertThat
import java.io.File

/*
 * Support for targeting specific Gradle version/installation in [GradleRule]
 */

interface GradleLocationBuilder {
    fun customInstallation(value: File): GradleLocationBuilder
    fun version(value: String): GradleLocationBuilder
    fun distributionDirectory(value: File): GradleLocationBuilder
}

data class GradleLocation(
    val customGradleInstallation: File? = null, // FIXME is this needed?
    val gradleVersion: String,
    val gradleDistributionDirectory: File,
    ) {

    fun getDistributionZip(): File {
        if (customGradleInstallation != null) {
            throw RuntimeException("Use targetDistributionInstallation as it's not null")
        }

        val distributionName = String.format("gradle-%s-bin.zip", gradleVersion)
        val distributionZip = File(gradleDistributionDirectory, distributionName)
        assertThat(distributionZip).isFile()

        return distributionZip
    }
}

class GradleLocationDelegate(): GradleLocationBuilder {
    private var customGradleInstallation: File? = null // FIXME is this needed?
    private var gradleVersion: String? = null
    private var gradleDistributionDirectory: File? = null

    override fun customInstallation(value: File): GradleLocationBuilder {
        customGradleInstallation = value
        return this
    }

    override fun version(value: String): GradleLocationBuilder {
        customGradleInstallation = null
        gradleVersion = value
        return this
    }

    override fun distributionDirectory(value: File): GradleLocationBuilder {
        gradleDistributionDirectory = value
        return this
    }

    val asGradleLocation: GradleLocation
        get() = GradleLocation(
            customGradleInstallation,
            gradleVersion ?: GRADLE_TEST_VERSION,
            gradleDistributionDirectory ?: TestUtils.resolveWorkspacePath("tools/external/gradle").toFile()
        )
}
