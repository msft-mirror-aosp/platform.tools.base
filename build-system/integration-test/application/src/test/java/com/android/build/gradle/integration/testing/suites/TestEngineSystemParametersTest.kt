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
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import com.android.testutils.truth.PathSubject
import com.google.common.truth.Truth
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
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.Properties

    class TestEngineSystemParametersTest {

    @get:Rule
    private val temporaryFolder = org.junit.rules.TemporaryFolder().also {
        it.create()
    }
    private val temporaryFile = temporaryFolder.newFile("junit_engines_additional_inputs.txt").also {
        Properties().also { properties ->
            properties.setProperty("com.android.build.test.token", "_random_token_")
            properties.store(FileWriter(it), "Input properties for test engine")
        }
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
                    ToyJunitEngineForTestingSystemProperties::class.java,
                    ToyTestDescriptorForTestingSystemProperties::class.java,
                    TestEngineLogger::class.java,
                )
                .addTextFile(
                    "META-INF/services/org.junit.platform.engine.TestEngine",
                    ToyJunitEngineForTestingSystemProperties::class.java.name
                )

        }.from {
            gradleProperties {
                add(BooleanOption.TEST_SUITE_SUPPORT, true)
                add(StringOption.TEST_SUITE_TEST_TASK_ADDITIONAL_INPUTS_FILE,
                    // make path windows friendly by escaping the separator
                    temporaryFile.absolutePath.replace("\\", "\\\\"))
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
            }
        }

    @Test
    fun testSystemProperties() {
        val project = rule.build
        val result = project
            .executor
            .run("testFirstT1DebugTestSuite")
        Truth.assertThat(result.didWorkTasks).contains(":app:testFirstT1DebugTestSuite")

        // lookup the test engine logging file.
        val loggingFile = File(project.subProject(":app").buildDir.toFile(),
            "intermediates/debug/testFirstT1DebugTestSuite/junit_engines_logging.txt")

        PathSubject.assertThat(loggingFile).exists()
        PathSubject.assertThat(loggingFile).contains("token = _random_token_")
    }
}

class ToyJunitEngineForTestingSystemProperties: TestEngine {

    // load my input properties as a json object, I am only using a handful of those so far.
    private val inputParams = TestSuiteExecutionClient.default()

    private val logger = TestEngineLogger(
        File(inputParams.getInputParameter(TestEngineInputProperty.LOGGING_FILE))
    )

    override fun getId(): String = "toy-junit-engine-for-system-properties"

    override fun discover(p0: EngineDiscoveryRequest?, p1: UniqueId?): TestDescriptor =
        ToyTestDescriptorForTestingSystemProperties(UniqueId.parse("[method: some-test]"))

    override fun execute(p0: ExecutionRequest?) {
        p0?.let { executionRequest ->
            logger.info("Executing toy engine ! ${executionRequest.rootTestDescriptor}")
            val listener: EngineExecutionListener = executionRequest.engineExecutionListener
            val engineDescriptor = executionRequest.rootTestDescriptor
            listener.executionStarted(engineDescriptor)

            val additionalInputsPath = System.getProperty("android.testSuite.testTaskAdditionalInputsFile")
            if (additionalInputsPath == null) {
                throw RuntimeException("No additional input file provided")
            }
            val additionalInputs = Properties().also {
                it.load(FileReader(additionalInputsPath))
            }

            val token = additionalInputs.getProperty("com.android.build.test.token")

            logger.info("token = $token")

            // Simulated test execution
            try {
                val testSucceeded = token == "_random_token_"
                if (testSucceeded) {
                    logger.info("Test Passed !")
                    listener.executionFinished(engineDescriptor, TestExecutionResult.successful())
                } else {
                    logger.info("Test Failed !")
                    listener.executionFinished(
                        engineDescriptor,
                        TestExecutionResult.failed(Exception("Test failed, token = $token"))
                    )
                }
            } catch (t: Throwable) {
                listener.executionFinished(engineDescriptor, TestExecutionResult.failed(t))
            }
            logger.info("Finished $engineDescriptor test.")
        }
    }
}

class ToyTestDescriptorForTestingSystemProperties(uniqueId: UniqueId): AbstractTestDescriptor(uniqueId, "toy descriptor") {
    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST
}
