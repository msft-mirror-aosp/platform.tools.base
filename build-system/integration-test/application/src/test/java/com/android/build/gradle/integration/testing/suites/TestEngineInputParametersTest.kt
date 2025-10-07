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

package com.android.build.gradle.integration.testing.suites

import com.android.Version
import com.android.build.api.dsl.AgpTestSuite
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.api.testsuites.TestEngineInputProperty
import com.android.build.api.testsuites.TestSuiteExecutionClient
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.rules.TemporaryFolder
import java.io.File

class TestEngineInputParametersTest {

    @get:Rule
    private val temporaryFolder = TemporaryFolder().also {
        it.create()
    }

    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            jar("com.google.truth:truth:0.44")
            jar("org.junit.platform:junit-platform-engine:1.10.1")
            jar("org.junit.platform:junit-platform-launcher:1.10.1")
            jar("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
            jar("com.test:toy-junit-engine:1.0")
                .addClasses(
                    ToyJunitEngineForTestingInputProperties::class.java,
                    ToyTestDescriptorForTestingInputProperties::class.java,
                    TestEngineLogger::class.java,
                )
                .addTextFile(
                    "META-INF/services/org.junit.platform.engine.TestEngine",
                    ToyJunitEngineForTestingInputProperties::class.java.name
                )

        }.from {
            gradleProperties {
                add(BooleanOption.TEST_SUITE_SUPPORT, true)
            }
            androidApplication(":app") {
                android {
                    namespace = "com.example.app"
                    defaultConfig {
                        applicationId = "com.example.app"
                    }
                    testOptions.suites.create("first", AgpTestSuite::class.java) {
                        it.useJunitEngine.apply {
                            inputs.add(
                                AgpTestSuiteInputParameters.MERGED_MANIFEST
                            )
                            includeEngines.add(
                                "toy-junit-engine-for-system-properties"
                            )
                            enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                            enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                            enginesDependencies.add("com.test:toy-junit-engine:1.0")
                            enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
                        }
                        it.assets {}
                        it.targetVariants.add("debug")
                        it.targets.create("t1") { }
                    }
                }
                this.dependencies {
                    implementation("com.google.truth:truth:0.44")
                }
                pluginCallbacks += TestingInputProperties::class.java
            }
        }

    @Test
    fun testSystemProperties() {
        val project = rule.build
        var result = project
            .executor
            .run("testFirstT1DebugTestSuite")
        Truth.assertThat(result.didWorkTasks).contains(":app:testFirstT1DebugTestSuite")

        // execute it again and make sure it runs as it should never be up to date.
        result = project
            .executor
            .run("testFirstT1DebugTestSuite")
        Truth.assertThat(result.didWorkTasks).contains(":app:testFirstT1DebugTestSuite")
        Truth.assertThat(result.upToDateTasks).doesNotContain(":app:testFirstT1DebugTestSuite")
    }
}

class TestingInputProperties: ApplicationComponentCallback {
    override fun handleExtension(
        project: Project,
        androidComponents: ApplicationAndroidComponentsExtension
    ) {
        androidComponents.finalizeDsl { applicationExtension ->
            println("executing callback")
            project.tasks.whenTaskAdded { task ->
                if (task.name == "testFirstT1DebugTestSuite") {
                    println("Found test task !")
                    task.outputs.upToDateWhen { false }
                }
            }
        }
    }
}

class ToyJunitEngineForTestingInputProperties: TestEngine {

    // load my input properties as a json object, I am only using a handful of those so far.
    private val inputParams = TestSuiteExecutionClient.default()

    private val logger = TestEngineLogger(
        File(inputParams.getInputParameter(TestEngineInputProperty.LOGGING_FILE))
    )

    override fun getId(): String = "toy-junit-engine-for-system-properties"

    override fun discover(p0: EngineDiscoveryRequest?, p1: UniqueId?): TestDescriptor =
        ToyTestDescriptorForTestingInputProperties(UniqueId.parse("[method: some-test]"))

    override fun execute(p0: ExecutionRequest?) {
        p0?.let { executionRequest ->
            logger.info("Executing toy engine ! ${executionRequest.rootTestDescriptor}")
            val listener: EngineExecutionListener = executionRequest.engineExecutionListener
            val engineDescriptor = executionRequest.rootTestDescriptor
            listener.executionStarted(engineDescriptor)

            val resultsDirPassed = checkOutputFolder(TestEngineInputProperty.RESULTS_DIR)
            val coverageDirPassed = checkOutputFolder(TestEngineInputProperty.COVERAGE_DIR)

            val testSucceeded = resultsDirPassed && coverageDirPassed

            // Simulated test execution
            try {
                if (testSucceeded) {
                    logger.info("Test Passed !")
                    listener.executionFinished(engineDescriptor, TestExecutionResult.successful())
                } else {
                    logger.info("Test Failed !")
                    listener.executionFinished(
                        engineDescriptor,
                        TestExecutionResult.failed(
                            Exception(
                                "Test failed, some output folders were not empty," +
                                        " check test engine log")
                        )
                    )
                }
            } catch (t: Throwable) {
                listener.executionFinished(engineDescriptor, TestExecutionResult.failed(t))
            }
            logger.info("Finished $engineDescriptor test.")
        }
    }

    private fun checkOutputFolder(testEngineInputProperty: String): Boolean {
        val dirProperty = inputParams.inputParameters.first { it.name == testEngineInputProperty }
        val outFolder = File(dirProperty.value)

        val outFolderPresent = outFolder.exists()
        val success = if (outFolderPresent) {
            logger.info("output Folder is present with ${outFolder.listFiles().size} file(s)")
            outFolder.listFiles().forEach {
                logger.info("contains ${it.absolutePath}")
            }
            false
        } else {
            outFolder.mkdirs()
            true
        }
        // in amy case, write some file in the output.
        File(outFolder, "some_result.txt").writeText("random result file")
        logger.info("Result file written in ${dirProperty.value}")
        return success
    }
}

class ToyTestDescriptorForTestingInputProperties(uniqueId: UniqueId): AbstractTestDescriptor(uniqueId, "toy descriptor") {
    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST
}
