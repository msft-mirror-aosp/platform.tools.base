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
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Tests that built-in Kotlin works correctly when source sets are modified. */
class BuiltInKotlinSourceSetTest {

    @get:Rule
    val rule = GradleRule.from { }

    /** Regression test for b/423864097. */
    @Test
    fun `test extra source sets are added`() {
        val build = rule.build {
            androidApplication {
                pluginCallbacks += AddExtraSourceSetsCallback::class.java
                pluginCallbacks += PrintSourceSetsCallback::class.java
            }
        }
        val result = build.executor.run(":app:help")
        result.assertOutputContains(
            """
            Contents of Android and Kotlin 'main' source set:
            androidMainSourceSet.java.directories = [src/main/java, src/extraAndroidSourceSet/java]
            androidMainSourceSet.kotlin.directories = [src/main/java, src/main/kotlin, src/extraAndroidSourceSet/kotlin]
            kotlinMainSourceSet.kotlin.srcDirs = [src/main/kotlin, src/extraKotlinSourceSet/kotlin]
            """.trimIndent()
        )
    }
}

class AddExtraSourceSetsCallback: GenericCallback {

    override fun handleProject(project: Project) {
        val androidExtension = project.extensions.getByType(CommonExtension::class.java)
        val androidMainSourceSet = androidExtension.sourceSets.getByName("main")
        val kotlinExtension = project.extensions.getByType(KotlinAndroidProjectExtension::class.java)
        val kotlinMainSourceSet = kotlinExtension.sourceSets.create("main")

        androidMainSourceSet.java.directories += "src/extraAndroidSourceSet/java"
        androidMainSourceSet.kotlin.directories += "src/extraAndroidSourceSet/kotlin"
        // Kotlin source sets added through the `kotlin.sourceSets` DSL should not be synced with
        // AGP source sets (b/386221070)
        kotlinMainSourceSet.kotlin.srcDir("src/extraKotlinSourceSet/kotlin")
    }
}

class PrintSourceSetsCallback: GenericCallback {

    override fun handleProject(project: Project) {
        val androidExtension = project.extensions.getByType(CommonExtension::class.java)
        val androidMainSourceSet = androidExtension.sourceSets.getByName("main")
        val kotlinExtension = project.extensions.getByType(KotlinAndroidProjectExtension::class.java)
        val kotlinMainSourceSet = kotlinExtension.sourceSets.maybeCreate("main")

        fun Collection<File>.toRelativePaths() = map { (if (it.isAbsolute) it.relativeTo(project.projectDir) else it).invariantSeparatorsPath }
        fun Collection<String>.toRelativePaths() = map { File(it) }.toRelativePaths()

        project.afterEvaluate {
            println("Contents of Android and Kotlin 'main' source set:")
            println("androidMainSourceSet.java.directories = ${androidMainSourceSet.java.directories.toRelativePaths()}")
            println("androidMainSourceSet.kotlin.directories = ${androidMainSourceSet.kotlin.directories.toRelativePaths()}")
            println("kotlinMainSourceSet.kotlin.srcDirs = ${kotlinMainSourceSet.kotlin.srcDirs.toRelativePaths()}")
        }
    }
}
