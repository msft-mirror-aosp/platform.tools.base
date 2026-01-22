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
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.TestSuiteSourceSet
import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.getIntermediateOutputDir
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import com.android.builder.model.v2.models.SourceType
import com.google.common.truth.Truth
import junit.framework.AssertionFailedError
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.junit.Rule
import org.junit.Test

class TestSuiteWithAddedSourcesViaVariantAPITest {

    @get:Rule
    val rule = GradleRule.configure()
        .from {
            gradleProperties {
                add(BooleanOption.TEST_SUITE_SUPPORT, true)
            }
            androidApplication {
                android {
                    namespace = "com.example.test"
                    testOptions.suites.create("first", AgpTestSuite::class.java) {
                        it.useJunitEngine.apply {
                            includeEngines.add(
                                "[engine:toy-junit-engine-for-tests]"
                            )
                        }
                        it.hostJar {  }
                        it.targetVariants.add("debug")
                        it.targetVariants.add("release")
                        it.targets.apply {
                            create("t1") { }
                        }
                    }
                    testOptions.suites.create("second", AgpTestSuite::class.java) {
                        it.useJunitEngine.apply {
                            includeEngines.add(
                                "[engine:toy-junit-engine-for-tests]"
                            )
                        }
                        it.testApk {  }
                        it.targetVariants.add("debug")
                        it.targets.apply {
                            create("t1") { }
                        }
                    }
                }
                dependencies {
                    implementation("com.google.truth:truth:0.44")
                }
                files {
                        add("src/configuration", "1")
                        add("src/test/shared/shared_file.txt", "shared content")
                }
                pluginCallbacks += AddGeneratedSourceSetToTestSuiteCallback::class.java
                pluginCallbacks += AddStaticDirectoryToTestSuiteCallback::class.java
            }
        }

    @Test
    fun upToDateCheck() {

        val project = rule.build
        var result: GradleBuildResult = project.executor
            .expectFailure() // TODO: it fails because Gradle complains I have no tests.
            .run("testFirstT1DebugTestSuite")

        Truth.assertThat(result.didWorkTasks).contains(":app:processFirstDebugJavaRes")
        val javaRes = getJavaRes(project)
        Truth.assertThat(javaRes.exists()).isTrue()
        Truth.assertThat(javaRes.listFiles().map { it.name })
            .containsExactly("shared_file.txt", "random_text_0.txt")

        // Run it again to check that we are up to date.
        result = project.executor.expectFailure().run("testFirstT1DebugTestSuite")
        Truth.assertThat(result.upToDateTasks).contains(":app:processFirstDebugJavaRes")
    }

    @Test
    fun fileAddedCheck() {

        val project = rule.build
        var result: GradleBuildResult = project.executor
            .expectFailure() // TODO: it fails because Gradle complains I have no tests.
            .run("testFirstT1DebugTestSuite")

        Truth.assertThat(result.didWorkTasks).contains(":app:processFirstDebugJavaRes")
        val javaRes = getJavaRes(project)
        Truth.assertThat(javaRes.exists()).isTrue()
        Truth.assertThat(javaRes.listFiles().map { it.name }).containsExactly(
            "shared_file.txt", "random_text_0.txt"
        )
        
        project.subProject(":app").files.run {
            update("src/configuration") {
                replaceWith("2")
            }
        }

        // Run it again to check that we are not up to date.
        result = project.executor.expectFailure().run("testFirstT1DebugTestSuite")
        Truth.assertThat(result.didWorkTasks).contains(":app:processFirstDebugJavaRes")
        Truth.assertThat(javaRes.listFiles().map { it.name }).containsExactly(
            "shared_file.txt", "random_text_0.txt", "random_text_1.txt"
        )
    }

    // Check that the "first" test suite which is targeting "debug" and "release" variants can
    // see the added folder (which is added only to the debug's test suite variant object).
    @Test
    fun testAddedFoldersAreImpactingAllTargetedVariants() {
        val project = rule.build
        var result: GradleBuildResult = project.executor
            .expectFailure() // TODO: it fails because Gradle complains I have no tests.
            .run("testFirstT1ReleaseTestSuite")

        Truth.assertThat(result.didWorkTasks).contains(":app:processFirstReleaseJavaRes")
        val javaRes = getJavaRes(project, "Release")
        Truth.assertThat(javaRes.exists()).isTrue()
        Truth.assertThat(javaRes.listFiles().map { it.name }).containsExactly(
            "shared_file.txt", "random_text_0.txt"
        )
    }

    @Test
    fun testModel() {
        val project = rule.build
        val result = project.modelBuilder.ignoreSyncIssues(SyncIssue.SEVERITY_WARNING).fetchModels()
        Truth.assertThat(result).isNotNull()
        val models = result.container.getProject(":app")

        // verify the test suite model for static folders.
        val basicTestSuites = models.basicAndroidProject?.testSuites
            ?: throw AssertionFailedError("no test suites defined in the BasicAndroidProject")
        Truth.assertThat(basicTestSuites).hasSize(2)
        val basicFirstTestSuite = basicTestSuites.first { it.name == "first" }
        val basicFirstSources = basicFirstTestSuite.hostJars.single()
        Truth.assertThat(basicFirstSources.type).isEqualTo(
            SourceType.HOST_JAR
        )
        val basicFirstTestSuiteResources = basicFirstSources.resources

        Truth.assertThat(basicFirstTestSuiteResources).containsExactly(
            project.subProject(":app").resolve("src/first/resources").toFile(),
            project.subProject(":app").resolve("src/test/shared").toFile(),
        )

        // and verify the test suite model for generated folders.
        val testSuites = models.androidProject?.testSuites
            ?: throw AssertionFailedError("no test suites defined in the AndroidProject")
        Truth.assertThat(testSuites).hasSize(2)
        val firstTestSuite = testSuites.first { it.name == "first" }
        val firstSources = firstTestSuite.generatedHostJars.single()
        Truth.assertThat(firstSources.type).isEqualTo(
            SourceType.HOST_JAR
        )
        val firstTestSuiteResources = firstSources.resources

        Truth.assertThat(firstTestSuiteResources).containsExactly(
            project.subProject(":app").resolve("build/generated/first/firstTestSuiteGeneratorTask").toFile()
        )

        // repeat both static and generated checks for the testApk test suite.
        val basicSecondTestSuite = basicTestSuites.first { it.name == "second" }
        val basicSecondSources = basicSecondTestSuite.testApks.single()
        Truth.assertThat(basicSecondSources.type).isEqualTo(
            SourceType.TEST_APK
        )
        val basicSecondTestSuiteResources = basicSecondSources.sourceProvider.resourcesDirectories

        Truth.assertThat(basicSecondTestSuiteResources).containsExactly(
            project.subProject(":app").resolve("src/second/resources").toFile(),
            project.subProject(":app").resolve("src/test/shared").toFile(),
        )

        val secondTestSuite = testSuites.first { it.name == "second" }
        val secondSources = secondTestSuite.generatedTestApks.single()
        Truth.assertThat(secondSources.type).isEqualTo(
            SourceType.TEST_APK
        )
        val secondTestSuiteResources = secondSources.sourceProvider.resourcesDirectories

        Truth.assertThat(secondTestSuiteResources).containsExactly(
            project.subProject(":app").resolve("build/generated/second/secondTestSuiteGeneratorTask").toFile()
        )
    }

    private fun getJavaRes(project: GradleBuild, capitalizedVariantName: String = "Debug") =
        InternalArtifactType.JAVA_RES
            .getIntermediateOutputDir(project.subProject(":app").buildDir.toFile())
            .resolve("first${capitalizedVariantName}")
            .resolve("processFirst${capitalizedVariantName}JavaRes")
            .resolve("out")
}

abstract class AddGeneratedSourceSetTaskProducer: DefaultTask() {
    @get: OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputFile
    abstract val configuration: RegularFileProperty

    @TaskAction
    fun taskAction() {
        outputDir.get().asFile.mkdirs()
        val numberOfFilesToGenerate = configuration.get().asFile.readText().toInt()
        repeat(numberOfFilesToGenerate) { iteration ->
            outputDir.get().file("random_text_$iteration.txt").asFile.writeText(
                "random text"
            )
        }
    }
}

open class AddGeneratedSourceSetToTestSuiteCallback: ApplicationComponentCallback {

    override fun handleExtension(
        project: Project,
        androidComponents: ApplicationAndroidComponentsExtension
    ) {
        val firstTaskProducer = project.tasks.register<AddGeneratedSourceSetTaskProducer>(
            "firstTestSuiteGeneratorTask",
            AddGeneratedSourceSetTaskProducer::class.java) { task ->

            task.configuration.set(project.layout.projectDirectory.file("src/configuration"))
        }

        val secondTaskProducer = project.tasks.register<AddGeneratedSourceSetTaskProducer>(
            "secondTestSuiteGeneratorTask",
            AddGeneratedSourceSetTaskProducer::class.java) { task ->

            task.configuration.set(project.layout.projectDirectory.file("src/configuration"))
        }

        androidComponents.onVariants { variant ->
            variant.suites.forEach { (suiteName, suite) ->
                suite.sources.forEach { sourceSet ->
                    if (sourceSet is TestSuiteSourceSet.HostJar) {
                        sourceSet.resources.addGeneratedSourceDirectory(firstTaskProducer,
                            AddGeneratedSourceSetTaskProducer::outputDir)
                    }
                    if (sourceSet is TestSuiteSourceSet.TestApk) {
                        sourceSet.resources.addGeneratedSourceDirectory(secondTaskProducer,
                            AddGeneratedSourceSetTaskProducer::outputDir)
                    }
                }
            }
        }
    }
}

open class AddStaticDirectoryToTestSuiteCallback : ApplicationComponentCallback {

    override fun handleExtension(
        project: Project,
        androidComponents: ApplicationAndroidComponentsExtension
    ) {
        androidComponents.onVariants { variant ->
            variant.suites.forEach { (suiteName, suite) ->
                suite.sources.forEach { sourceSet ->
                    if (sourceSet is TestSuiteSourceSet.HostJar) {
                        sourceSet.resources.addStaticSourceDirectory("src/test/shared")
                    }
                    if (sourceSet is TestSuiteSourceSet.TestApk) {
                        sourceSet.resources.addStaticSourceDirectory("src/test/shared")
                    }
                }
            }
        }
    }
}
