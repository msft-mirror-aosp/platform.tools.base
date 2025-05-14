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

package com.android.build.gradle.tasks

import android.databinding.tool.ext.toCamelCase
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.component.impl.computeTaskName
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.internal.component.TestSuiteCreationConfig
import com.android.build.gradle.internal.tasks.BuildAnalyzer
import com.android.build.gradle.internal.tasks.GlobalTask
import com.android.build.gradle.internal.tasks.factory.GlobalTaskCreationAction
import com.android.buildanalyzer.common.TaskCategory
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test

@CacheableTask
@BuildAnalyzer(primaryTaskCategory = TaskCategory.TEST)
abstract class TestSuiteTestTask: Test(), GlobalTask {

    @get:Nested
    abstract val engineInputParameters: ListProperty<AgpTestSuiteInputParameter>

    @TaskAction
    override fun executeTests() {
        println("Executing task $name")
        engineInputParameters.get().forEach { parameter ->
            println("Engine wants ${parameter.type}")
            val location = parameter.value
            println("At location ${location.get().asFile.absolutePath}")
        }
        super.executeTests()
    }

    class CreationAction(
        val creationConfig: TestSuiteCreationConfig
    ): GlobalTaskCreationAction<TestSuiteTestTask>() {

        override val name: String
            get() = computeTaskName(creationConfig.testedVariant.name, "test${creationConfig.name.toCamelCase()}","TestSuite" )

        override val type: Class<TestSuiteTestTask> = TestSuiteTestTask::class.java

        override fun configure(task: TestSuiteTestTask) {
            super.configure(task)
            val classesDir = task.project.layout.buildDirectory.file(task.name)
            UniqueClassGenerator().generateSimpleClass(classesDir.get().asFile)
            task.testClassesDirs =  creationConfig.services.fileCollection().also {
                it.from(classesDir)
            }
            creationConfig.junitEngineSpec?.let { junitEngineSpec ->
                junitEngineSpec.inputs.forEach { inputParameter: AgpTestSuiteInputParameters ->
                    when (inputParameter) {
                        AgpTestSuiteInputParameters.MERGED_MANIFEST -> {
                            task.engineInputParameters.add(
                                AgpTestSuiteInputParameter(
                                    AgpTestSuiteInputParameters.MERGED_MANIFEST,
                                    creationConfig.testedVariant.artifacts.get(
                                        SingleArtifact.MERGED_MANIFEST
                                    )
                                )
                            )
                        }
                        AgpTestSuiteInputParameters.TESTED_APKS -> {
                            task.engineInputParameters.add(
                                AgpTestSuiteInputParameter(
                                    AgpTestSuiteInputParameters.TESTED_APKS,
                                    creationConfig.testedVariant.artifacts.get(
                                        SingleArtifact.APK
                                    )
                                )
                            )
                        }
                        else -> {
                            println("I don't know of this parameter $inputParameter")
                        }
                    }
                }
                task.engineInputParameters.disallowChanges()
            }
        }
    }

    class AgpTestSuiteInputParameter(
        @get:Input
        val type: AgpTestSuiteInputParameters,

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        val value: Provider<out FileSystemLocation>
    )
}
