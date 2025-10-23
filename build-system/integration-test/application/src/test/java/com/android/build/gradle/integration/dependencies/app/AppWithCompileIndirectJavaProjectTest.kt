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

package com.android.build.gradle.integration.dependencies.app

import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.utils.disableBuiltInKotlin
import com.android.builder.model.v2.ide.SyncIssue
import org.junit.Rule
import org.junit.Test

class AppWithCompileIndirectJavaProjectTest : ModelComparator() {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies {
                implementation(project(DEFAULT_LIB_PATH))
                runtimeOnly("com.google.guava:guava:19.0")
            }
        }
        androidLibrary {
            dependencies {
                api(project(":jar"))
            }
            files.add(
                "src/main/java/com/example/android/multiproject/library/PersonView.java",
                //language=java
                """
                    package com.example.android.multiproject.library;
                    public class PersonView {}
                """.trimIndent()
            )
        }
        genericProject(":jar") {
            applyPlugin(PluginType.JAVA_LIBRARY)
            dependencies {
                api("com.google.guava:guava:19.0")
            }
            files.add(
                "src/main/java/com/example/android/multiproject/person/People.java",
                //language=java
                """
                    package com.example.android.multiproject.person;
                    public class People {}
                """.trimIndent()
            )
        }
        disableBuiltInKotlin()
    }

    @Test
    fun `test VariantDependencies model`() {
        val result = rule.build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        with(result).compareVariantDependencies(
            projectAction = { getProject(DEFAULT_APP_PATH) },
            goldenFile = "app_VariantDependencies"
        )
        with(result).compareVariantDependencies(
            projectAction = { getProject(DEFAULT_LIB_PATH) },
            goldenFile = "library_VariantDependencies"
        )
    }

    @Test
    fun checkPackagedJar() {
        val build = rule.build
        build.executor.run(":app:assembleDebug")

        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().containsExactly(
                "com/example/android/multiproject/person/People",
                "com/example/android/multiproject/library/PersonView",
                "pkg/name/app/R",
                "pkg/name/lib/R",
                "com/google/common/",
                "com/google/thirdparty/"
            )
        }
    }
}
