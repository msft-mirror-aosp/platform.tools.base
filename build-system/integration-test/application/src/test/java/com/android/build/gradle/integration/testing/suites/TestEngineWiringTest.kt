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
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.utils.getDebugVariant
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.BasicVariant
import com.android.builder.model.v2.ide.Library
import com.android.builder.model.v2.ide.SyncIssue
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.BasicTestSuite
import com.android.builder.model.v2.models.SourceType
import com.google.common.truth.Truth
import junit.framework.AssertionFailedError
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.collections.forEach

@RunWith(Parameterized::class)
class TestEngineWiringTest(
    private val modulePath: String,
    baseAppCustomizer: (projectDef: ApplicationExtension) -> Unit,
    configuration: (
        buildDefinition: GradleBuildDefinition,
        modulePath: String,
        action: AndroidProjectDefinition<out CommonExtension>.() -> Unit
    ) -> Unit
) {
    companion object {

        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun parameters() = listOf(
            arrayOf(
                ":lib",
                { projectDefinition: ApplicationExtension -> },
                { buildDefinition : GradleBuildDefinition, modulePath: String, action: AndroidProjectDefinition<out CommonExtension>.() -> Unit ->
                    buildDefinition.androidLibrary(modulePath, action = action)

                },

            ),
            arrayOf(
                ":app",
                { projectDefinition: ApplicationExtension -> },
                { buildDefinition : GradleBuildDefinition, modulePath: String, action: AndroidProjectDefinition<out CommonExtension>.() -> Unit ->
                    buildDefinition.androidApplication(modulePath, action = action)
                },
            ),
            arrayOf(
                ":feature",
                { projectDef: ApplicationExtension ->
                    projectDef.dynamicFeatures.add(":feature")
                },
                { buildDefinition : GradleBuildDefinition, modulePath: String, action: AndroidProjectDefinition<out CommonExtension>.() -> Unit ->
                    buildDefinition.androidFeature(modulePath) {
                        action()
                        dependencies {
                            implementation(project(":baseApp"))
                        }
                    }
                },
            ),
        )
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
                    ToyJunitEngineForTesting::class.java,
                    ToyTestDescriptor::class.java,
                    TestEngineLogger::class.java,
                )
                .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine",
                    ToyJunitEngineForTesting::class.java.name)

        }.from {
            gradleProperties {
                add(BooleanOption.TEST_SUITE_SUPPORT, true)
            }
            androidApplication(":baseApp") {
                android {
                    namespace = "com.example.baseApp"
                    defaultConfig {
                        applicationId = "com.example.baseApp"
                    }
                    baseAppCustomizer(this)
                }
            }
            configuration(this, modulePath) {
                android {
                    namespace = "com.example.test"
                    testOptions.suites.create("first", AgpTestSuite::class.java) {
                        it.useJunitEngine.apply {
                            inputs.add(
                                AgpTestSuiteInputParameters.MERGED_MANIFEST
                            )
                            includeEngines.add(
                                "[engine:toy-junit-engine-for-tests]"
                            )
                            enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                            enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                            enginesDependencies.add("com.test:toy-junit-engine:1.0")
                            enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
                        }
                        it.hostJar {
                            dependencies.apply {
                                implementation.add("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
                                implementation.add("com.google.code.gson:gson:2.11.0")
                            }
                        }
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
    fun testJunitWiringThroughDSL() {
        val result = rule.build
            .executor
            .expectFailure() // TODO: it fails because Gradle complains I have no tests.
            .run("testFirstT1DebugTestSuite")
        Truth.assertThat(result.failedTasks).contains("$modulePath:testFirstT1DebugTestSuite")
        result.assertFailureMessage().contains(
            "There are test sources present and no filters are applied, but the test task did not discover any tests to execute.")
    }

    @Test
    fun testBasicModel() {
        val project = rule.build
        val result = project.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels()
        Truth.assertThat(result).isNotNull()
        val models = result.container.getProject(modulePath)

        // verify the test suite model
        val testSuites = models.basicAndroidProject?.testSuites
            ?: throw AssertionFailedError("no test suites defined in the project")
        Truth.assertThat(testSuites).hasSize(1)
        val firstTestSuite: BasicTestSuite = testSuites.single()
        Truth.assertThat(firstTestSuite.name).isEqualTo("first")
        val firstTestSuiteSources = firstTestSuite.hostJars.single()
        Truth.assertThat(firstTestSuiteSources.type).isEqualTo(
            SourceType.HOST_JAR
        )
        Truth.assertThat(firstTestSuiteSources.kotlin).containsExactly(
            project.subProject(modulePath).resolve("src/first").toFile()
        )

        // verify the variant specific model
        val variantTestSuites = getVariant(models.basicAndroidProject, "debug")
            .testSuiteArtifacts

        Truth.assertThat(variantTestSuites).hasSize(1)
        val variantTestSuite = variantTestSuites.values.single()
        Truth.assertThat(variantTestSuite.testSuiteName).isEqualTo("first")

    }

    @Test
    fun testModel() {
        val result = rule.build.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels()
        Truth.assertThat(result).isNotNull()
        val testSuites = result.container.getProject(modulePath).androidProject?.getDebugVariant()?.testSuiteArtifacts
        Truth.assertThat(testSuites).isNotNull()
        val firstTestSuite = testSuites?.get("first")
        Truth.assertThat(firstTestSuite).isNotNull()
        val targets = firstTestSuite!!.testInfo.targets
        Truth.assertThat(targets.size).isEqualTo(1)
        Truth.assertThat(targets.values.single().name).isEqualTo("t1")
        Truth.assertThat(targets.values.single().testTaskName).isEqualTo("testFirstT1DebugTestSuite")
        Truth.assertThat(firstTestSuite.testInfo.junitInfo.includedEngines.single()).isEqualTo("[engine:toy-junit-engine-for-tests]")
    }

    @Test
    fun testDependenciesModel() {
        val project = rule.build
        val result = project.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchVariantDependencies("debug")
        Truth.assertThat(result).isNotNull()
        val models = result.container.getProject(modulePath)
        val libraries = models.variantDependencies?.libraries
        val resolvedLibraries = models.variantDependencies?.testSuiteArtifacts["first"]
            ?.sourcesDependencies?.single()?.artifactDependencies?.compileDependencies?.map { graphItem ->
            libraries!![graphItem.key]
        }
        resolvedLibraries!!.forEach { library: Library? ->
            Truth.assertThat(library).isNotNull()
            //Truth.assertThat(library!!.artifact!!.exists()).isTrue()
        }
        Truth.assertThat(
            resolvedLibraries
                .filter { it!!.libraryInfo != null }
                .map { it!!.libraryInfo!!.name }
        ).containsAtLeastElementsIn(listOf("truth", "gson", "kotlin-stdlib"))
    }

    private fun getVariant(basicAndroidProject: BasicAndroidProject?, variantName: String): BasicVariant {
        if (basicAndroidProject == null)
            throw AssertionFailedError("BasicAndroidProject is null")

        return basicAndroidProject.variants.first { variant ->
            variant.name == "debug"
        }
    }
}
