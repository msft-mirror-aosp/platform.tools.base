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

import com.android.build.api.dsl.AgpTestSuite
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.internal.testsuites.impl.TestEngineInputProperties
import com.android.build.gradle.internal.testsuites.impl.TestEngineInputProperty
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.utils.getDebugVariant
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.Library
import com.android.builder.model.v2.ide.SyncIssue
import com.google.common.truth.Truth
import java.time.LocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor

import java.io.File
import java.time.format.DateTimeFormatter

class TestEngineWiringInApplicationModuleTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            jar("com.google.truth:truth:0.44")
            jar("org.junit.platform:junit-platform-engine:1.10.1")
            jar("org.junit.platform:junit-platform-launcher:1.10.1")
            jar("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
            jar("com.test:toy-junit-engine:1.0")
                .addClasses(
                    ToyJunitEngineForTesting::class.java,
                    ToyTestDescriptor::class.java,
                    TestEngineInputProperties::class.java,
                    TestEngineInputProperties.Companion::class.java,
                    TestEngineInputProperty::class.java,
                    TestEngineInputProperty.Companion::class.java
                    )
                .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine",
                    ToyJunitEngineForTesting::class.java.name)

        }.from {
            gradleProperties {
                add(BooleanOption.TEST_SUITE_SUPPORT, true)
            }
            androidApplication {
                android {
                    testOptions.suites.create("first", AgpTestSuite::class.java) {
                        it.useJunitEngine.apply {
                            inputs.add(
                                AgpTestSuiteInputParameters.MERGED_MANIFEST
                            )
                            includeEngines.add(
                                "[engine:toy-junit-engine-for-tests]"
                            )
                        }
                        it.dependencies.implementation.add("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
                        it.dependencies.implementation.add("com.google.code.gson:gson:2.11.0")
                        it.dependencies.runtimeOnly.add("org.junit.platform:junit-platform-launcher")
                        it.dependencies.runtimeOnly.add("com.test:toy-junit-engine:1.0")
                        it.dependencies.runtimeOnly.add("org.junit.platform:junit-platform-engine:1.12.0")
                    }
                }
                dependencies {
                    implementation("com.google.truth:truth:0.44")
                }
            }
        }

    @Test
    fun testJunitWiringThroughDSL() {
        val result = rule.build
            .executor
            .expectFailure() // TODO: it fails because Gradle complains I have no tests.
            .run(":app:testFirstDebugTestSuite")
        Truth.assertThat(result.didWorkTasks).contains(":app:testFirstDebugTestSuite")
        result.assertFailureMessage().contains("Deprecated Gradle features were used in this build")
    }

    @Test
    fun testBasicModel() {
        val project = rule.build
        val result = project.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels()
        Truth.assertThat(result).isNotNull()
        val models = result.container.getProject(":app")
        val testSuiteArtifacts = models.basicAndroidProject?.variants?.first { variant ->
            variant.name == "debug"
        }?.testSuiteArtifacts

        Truth.assertThat(testSuiteArtifacts).isNotNull()
        val firstTestSuite = testSuiteArtifacts?.get("first")
        Truth.assertThat(firstTestSuite).isNotNull()
        val sources = firstTestSuite!!.sources
        Truth.assertThat(sources).hasSize(1)
        Truth.assertThat(sources.single()).isEqualTo(
            project.androidApplication(":app").resolve("src/first").toFile()
        )
    }

    @Test
    fun testModel() {
        val result = rule.build.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels()
        Truth.assertThat(result).isNotNull()
        val testSuites = result.container.getProject(":app").androidProject?.getDebugVariant()?.testSuiteArtifacts
        Truth.assertThat(testSuites).isNotNull()
        val firstTestSuite = testSuites?.get("first")
        Truth.assertThat(firstTestSuite).isNotNull()
        Truth.assertThat(firstTestSuite!!.testInfo.testTaskName).isEqualTo("testFirstDebugTestSuite")
        Truth.assertThat(firstTestSuite.testInfo.junitInfo.includedEngines.single()).isEqualTo("[engine:toy-junit-engine-for-tests]")
    }

    @Test
    fun testDependenciesModel() {
        val project = rule.build
        val result = project.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchVariantDependencies("debug")
        Truth.assertThat(result).isNotNull()
        val models = result.container.getProject(":app")
        val libraries = models.variantDependencies?.libraries
        val resolvedLibraries = models.variantDependencies?.testSuiteArtifacts["first"]?.compileDependencies?.map { graphItem ->
            libraries!![graphItem.key]
        }
        Truth.assertThat(resolvedLibraries).hasSize(3)
        resolvedLibraries!!.forEach { library: Library? ->
            Truth.assertThat(library).isNotNull()
            Truth.assertThat(library!!.artifact!!.exists()).isTrue()
        }
        Truth.assertThat(
            resolvedLibraries.map { it!!.libraryInfo!!.name }
        ).containsExactly("truth", "gson", "kotlin-stdlib")
    }
}

class ToyJunitEngineForTesting: TestEngine {

    // load my input properties as a json object, I am only using a handful of those so far.
    private val inputParameters: TestEngineInputProperties = TestEngineInputProperties.read()
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private val loggerFile = File(inputParameters.get(TestEngineInputProperty.LOGGING_FILE))

    // Bare minimum logger, we should move this to a Service class.
    private fun log(level: String, message: String) {
        val timestamp = LocalDateTime.now().format(dateTimeFormatter)
        val logEntry = "[$timestamp] [$level] $message\n"

        try {
            loggerFile.appendText(logEntry)
        } catch (e: Exception) {
            System.err.println("Error writing to log file '${loggerFile.absolutePath}': ${e.message}")
        }
    }

    fun info(message: String) = log("INFO", message)
    fun debug(message: String) = log("DEBUG", message)
    fun warn(message: String) = log("WARN", message)
    fun error(message: String) = log("ERROR", message)

    override fun getId(): String {
        info("getId::called\n")
        return "[engine:toy-junit-engine-for-tests]"
    }

    override fun discover(p0: EngineDiscoveryRequest?, p1: UniqueId?): TestDescriptor {
        info("Test discovery !\n")
        return ToyTestDescriptor(UniqueId.parse("[method: some-test]"))
    }

    override fun execute(p0: ExecutionRequest?) {
        p0?.let { executionRequest ->
            info("Executing toy engine ! ${executionRequest.rootTestDescriptor}")
            inputParameters.properties.forEach {
                info("Input : $it")
            }
            val listener: EngineExecutionListener = executionRequest.engineExecutionListener

            val engineDescriptor = executionRequest.rootTestDescriptor
            info("Starting $engineDescriptor test.")
            listener.executionStarted(engineDescriptor)

            // Simulated test execution
            try {
                val testSucceeded = true // Replace with actual test outcome.
                if (testSucceeded) {
                    listener.executionFinished(engineDescriptor, TestExecutionResult.successful())
                } else {
                    listener.executionFinished(
                        engineDescriptor,
                        TestExecutionResult.failed(Exception("Test failed"))
                    )
                }
            } catch (t: Throwable) {
                listener.executionFinished(engineDescriptor, TestExecutionResult.failed(t))
            }
            info("Finished $engineDescriptor test.")
        }
    }
}

class ToyTestDescriptor(uniqueId: UniqueId): AbstractTestDescriptor(uniqueId, "toy descriptor") {
    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST
}
