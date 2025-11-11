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

package com.android.build.gradle.integration.api

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class AddProviderToProguardFilesTest {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            android {
                namespace = "com.example.api.java_res"
                defaultConfig.applicationId = "com.example.api.java_res"
                pluginCallbacks += AddProviderToProguardFiles::class.java
            }
        }
    }

    @Test
    fun expectFailure() {
        val buildResult = rule.build.executor.expectFailure().run("assembleDebug")
        Truth.assertThat(buildResult.stderrAsText).contains(
            BooleanOption.DISALLOW_PROVIDER_IN_ANDROID_SOURCE_SET.propertyName
        )
    }

    @Test
    fun allowProviderTest() {
        val buildResult = rule.build.executor
            .with(BooleanOption.DISALLOW_PROVIDER_IN_ANDROID_SOURCE_SET, false)
            .run("assembleDebug")
        Truth.assertThat(buildResult.exception).isNull()
    }
}

abstract class CreateConsumerProguardFile : DefaultTask() {
    @get:OutputFile
    abstract val proguardFile: RegularFileProperty
    @TaskAction
    fun create() {
        proguardFile.asFile.get().apply {
            parentFile.mkdirs()
            writeText("#generated file")
        }
    }
}

class AddProviderToProguardFiles: ApplicationComponentCallback {

    override fun handleExtension(
        project: Project,
        androidComponents: ApplicationAndroidComponentsExtension
    ) {
        val generateTask = project.tasks.register("GenerateProguardFile", CreateConsumerProguardFile::class.java) {
                it.proguardFile.set(project.layout.buildDirectory.file("generated/proguardFile.pro"))
        }

        androidComponents.finalizeDsl {
            it.defaultConfig.proguardFile(generateTask.flatMap { task -> task.proguardFile })
        }
    }

}
