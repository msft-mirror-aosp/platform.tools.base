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

package com.android.build.gradle.integration.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.junit.Rule
import org.junit.Test

class JavaCompileTaskConfigurationTest {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                namespace = "com.example.api.use"
                defaultConfig.applicationId = "com.example.api.use"
            }
            pluginCallbacks += VariantApiCallback::class.java
            pluginCallbacks += JavaComplierArgsCallback::class.java
        }
    }

    @Test
    fun `test setting java compiler options via Variant API`() {
        val build = rule.build {
            androidApplication {
                files.add(
                    "src/debug/java/Foo.java",
                    //language=kotlin
                    """
                        package com.example.api.use;
                        class Foo {}
                    """.trimIndent()
                )
            }
        }
        val result = build.executor.run(":app:assembleDebug")
        ScannerSubject.assertThat(result.stdout).contains("compilerArgs=[-XDstringConcat=inline, -Werror]")
    }

    class VariantApiCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            extension: ApplicationAndroidComponentsExtension
        ) {
            extension.onVariants { variant ->
                variant.configureJavaCompileTask { task ->
                    task.options.compilerArgs.add("-Werror")
                }
            }
        }
    }

    class JavaComplierArgsCallback: GenericCallback {
        override fun handleProject(project: Project) {
            project.afterEvaluate {
                project.tasks.named("compileDebugJavaWithJavac") {
                    it.doLast { task ->
                        task as JavaCompile
                        val compilerArgs = task.options.compilerArgs
                        assert(compilerArgs.contains("-Werror"))
                        println("compilerArgs=$compilerArgs")
                    }
                }
            }
        }
    }
}
