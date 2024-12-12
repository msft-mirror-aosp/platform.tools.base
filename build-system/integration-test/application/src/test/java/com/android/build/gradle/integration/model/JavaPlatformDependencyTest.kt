/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.model

import com.android.build.gradle.integration.common.fixture.ModelContainerV2
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.builder.model.v2.ide.SyncIssue
import com.android.testutils.MavenRepoGenerator
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlatformExtension
import org.junit.Rule
import org.junit.Test

class JavaPlatformDependencyTest : ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies {
                implementation(platform(project(":lib")))
            }
        }
        genericProject(":lib") {
            applyPlugin(PluginType.JAVA_PLATFORM)
            pluginCallback = PlatformCallback::class.java
            dependencies {
                api(MavenRepoGenerator.Library("com.bar:foo:1.0"))
            }
        }
    }

    class PlatformCallback: GenericCallback {
        override fun handleProject(project: Project) {
            val javaPlatform = project.extensions.findByType(JavaPlatformExtension::class.java)
                ?: throw RuntimeException("Unable to find JavaPlatformExtension")
            javaPlatform.apply {
                allowDependencies()
            }
        }
    }

    @Test
    fun `test models`() {
        val result = rule.build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        val appModelAction: ModelContainerV2.() -> ModelContainerV2.ModelInfo =
            { getProject(":app") }

        with(result).compareVariantDependencies(
            projectAction = appModelAction,
            goldenFile = "_VariantDependencies"
        )
    }
}
