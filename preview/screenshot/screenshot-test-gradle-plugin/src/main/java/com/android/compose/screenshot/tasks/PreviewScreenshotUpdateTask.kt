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

package com.android.compose.screenshot.tasks

import com.android.compose.screenshot.services.AnalyticsService
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault
abstract class PreviewScreenshotUpdateTask : JavaExec() {
    @get:Nested
    abstract val testEngineInput: PreviewScreenshotTestEngineInput

    @get:Internal
    abstract val analyticsService: Property<AnalyticsService>

    init {
        classpath = objectFactory.fileCollection().apply {
            from(
                testEngineInput.testRuntimeClassDirs, testEngineInput.testRuntimeJars,
                testEngineInput.mainRuntimeClassDirs, testEngineInput.mainRuntimeJars
            )
        }
        testEngineInput.recordingModeEnabled.set(true)
    }

    override fun getClasspath(): ConfigurableFileCollection {
        return super.getClasspath() as ConfigurableFileCollection
    }

    @TaskAction
    override fun exec() = analyticsService.get().recordTaskAction(path) {
        if (testEngineInput.testProjectJars.get().isEmpty() &&
            testEngineInput.testProjectClassDirs.get().isEmpty()) {
            return@recordTaskAction
        }

        testEngineInput.testProjectJars.get().forEach {
            args("--scan-class-path=${it.asFile.absolutePath}")
        }
        testEngineInput.testProjectClassDirs.get().forEach {
            args("--scan-class-path=${it.asFile.absolutePath}")
        }
        testEngineInput.copyJvmArgsTo(::jvmArgs)
        super.exec()
    }
}
