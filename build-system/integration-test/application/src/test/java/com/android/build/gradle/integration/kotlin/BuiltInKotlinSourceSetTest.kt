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

package com.android.build.gradle.integration.kotlin

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Tests that built-in Kotlin support works when custom source sets are used. */
class BuiltInKotlinSourceSetTest {

    @get:Rule
    val rule = GradleRule.from {
        buildFileType = BuildFileType.KTS
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        }
    }

    /** Regression test for b/423864097. */
    @Test
    fun `test extra source set`() {
        val build = rule.build {
            androidApplication {
                pluginCallbacks += AddExtraSourceSetCallback::class.java
            }
        }
        val result = build.executor.run(":app:help")
        result.assertOutputContains(
            """
            Contents of Android and Kotlin 'main' source set:
            mainSourceSet.java.srcDirs = [src/main/java, src/extraMain/java]
            mainSourceSet.kotlin.srcDirs = [src/main/kotlin, src/main/java, src/extraMain/java]
            kotlinSourceSetForMain.kotlin.srcDirs = [src/main/kotlin, src/main/java, src/extraMain/java]
            """.trimIndent()
        )
    }

    private class AddExtraSourceSetCallback: GenericCallback {

        override fun handleProject(project: Project) {
            val androidExtension = project.extensions.getByType(CommonExtension::class.java)
            val mainSourceSet = androidExtension.sourceSets.getByName("main")

            mainSourceSet.java.srcDirs("src/extraMain/java")

            fun Collection<File>.toRelativePaths() = map { (if (it.isAbsolute) it.relativeTo(project.projectDir) else it).invariantSeparatorsPath }
            fun Collection<String>.toRelativePaths() = map { File(it) }.toRelativePaths()

            project.afterEvaluate {
                val kotlinExtension = project.extensions.getByType(KotlinAndroidProjectExtension::class.java)
                val kotlinSourceSetForMain = kotlinExtension.sourceSets.getByName("main")

                println("Contents of Android and Kotlin 'main' source set:")
                println("mainSourceSet.java.srcDirs = ${mainSourceSet.java.directories.toRelativePaths()}")
                println("mainSourceSet.kotlin.srcDirs = ${mainSourceSet.kotlin.directories.toRelativePaths()}")
                println("kotlinSourceSetForMain.kotlin.srcDirs = ${kotlinSourceSetForMain.kotlin.srcDirs.toRelativePaths()}")
            }
        }
    }

}
