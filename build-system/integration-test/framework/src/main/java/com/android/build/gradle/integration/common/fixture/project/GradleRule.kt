/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.project

import com.android.SdkConstants
import com.android.build.gradle.integration.common.fixture.ConfigurationCacheReportChecker
import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.GradleTestInfo
import com.android.build.gradle.integration.common.fixture.GradleTestProject.Companion.GRADLE_DEAMON_IDLE_TIME_IN_SECONDS
import com.android.build.gradle.integration.common.fixture.ModelBuilderV2
import com.android.build.gradle.integration.common.fixture.ProjectPropertiesWorkingCopy
import com.android.build.gradle.integration.common.fixture.debugGradleConnectionExceptionThenRethrow
import com.android.build.gradle.integration.common.fixture.gradle_project.BuildSystem
import com.android.build.gradle.integration.common.fixture.gradle_project.ProjectLocation
import com.android.build.gradle.integration.common.fixture.gradle_project.initializeProjectLocation
import com.android.build.gradle.integration.common.fixture.project.GradleRule.Companion.configure
import com.android.build.gradle.integration.common.fixture.project.GradleRule.Companion.from
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidApplicationDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidDynamicFeatureDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidLibraryDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GroovyBuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.KtsBuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.PrivacySandboxSdkDefinitionImpl
import com.android.build.gradle.integration.common.fixture.testprojects.TestProjectBuilder
import com.android.build.gradle.integration.common.truth.forEachLine
import com.android.sdklib.internal.project.ProjectProperties
import com.android.testutils.TestUtils
import com.android.utils.FileUtils
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * JUnit Rule to automatically set up Gradle projects for tests via a DSL.
 *
 * Entry point to create instances via [from] and [configure]
 */
class GradleRule internal constructor(
    val name: String,
    private val gradleBuild: GradleBuildDefinitionImpl,
    private val ruleOptionBuilder: DefaultRuleOptionBuilder,
): TestRule {
    private var status = Status.PENDING

    /** Project location, computed by the Statement at test execution */
    private var mutableProjectLocation: ProjectLocation? = null

    private val openConnections = mutableListOf<ProjectConnection>()

    /** The last build result. this is only used to log it in case of a test failure */
    private var lastBuildResult: GradleBuildResult? = null

    companion object {
        /**
         * Returns a [GradleRule] for a project configured with the [TestProjectBuilder].
         *
         * To configure the rule, use [configure] instead
         */
        fun from(
            action: GradleBuildDefinition.() -> Unit
        ): GradleRule {
            val builder = GradleBuildDefinitionImpl("project")
            action(builder)

            return GradleRuleBuilder().create(builder)
        }

        /**
         * Returns a [GradleRuleBuilder] that can be configured before calling [GradleRuleBuilder.from]
         */
        fun configure(
        ): GradleRuleBuilder {
            return GradleRuleBuilder()
        }
    }

    /**
     * The generated [GradleBuild].
     *
     * Once this is called the project is written on disk and it's not possible to change
     * its structure.
     *
     * It is possible after the fact to add more source files can be added via [GradleProject.files]
     * and it's possible to amend the build file with [AndroidProject.reconfigure]
     */
    val build: GradleBuild by lazy {
        if (!status.written) {
            doWriteBuild()
        }

        computeGradleBuild(
            gradleBuild,
            mutableProjectLocation ?: throw RuntimeException("Location not set!")
        )
    }

    /**
     * Configures the build with one final action and returns the [GradleBuild]
     *
     * Once this is called the project is written on disk and it's not possible to change
     * its structure.
     *
     * It is possible after the fact to add more source files can be added via [GradleProject.files]
     * and it's possible to amend the build file with [AndroidProject.reconfigure]
     */
    fun build(action: GradleBuildDefinition.() -> Unit): GradleBuild {
        // cannot reconfigure the build since static rules creates a single build for all test methods.
        if (status == Status.WRITTEN_STATIC) {
            throw RuntimeException("Build from static GradleRule cannot be reconfigured")
        } else if (status == Status.WRITTEN_USER) {
            throw RuntimeException("Build was already reconfigured and written. Cannot be configured twice.")
        }

        action(gradleBuild)

        return build
    }

    /**
     * Configures the build with multi-step actions before returning the [GradleBuild]
     *
     * The returned instance of [LocalRuleOptionBuilder] allows configuring many Gradle options.
     *
     * The gradle build must be queried at the end with either versions of
     * [LocalRuleOptionBuilder.build]
     *
     * Once this is called the project is written on disk and it's not possible to change
     * its structure.
     *
     * It is possible after the fact to add more source files can be added via [GradleProject.files]
     * and it's possible to amend the build file with [AndroidProject.reconfigure]
     */
    fun configure(): LocalRuleOptionBuilder = LocalRuleOptionBuilderImpl(this, this.ruleOptionBuilder)

    private val buildWriter: () -> BuildWriter by lazy {
        if (true) {
            { GroovyBuildWriter() }
        } else {
            { KtsBuildWriter() }
        }
    }

    private fun doWriteBuild() {
        val location = mutableProjectLocation ?: throw RuntimeException("Location not set before writing!")

        val projectDir = location.projectDir
        FileUtils.deleteRecursivelyIfExists(projectDir)
        FileUtils.mkdirs(projectDir)

        val localRepositories = BuildSystem.get().localRepositories

        gradleBuild.write(projectDir.toPath(), localRepositories, buildWriter)

        createLocalProp()
        createGradleProp()

        status = Status.WRITTEN_USER
    }

    /**
     * compute a [GradleBuild].
     *
     * For included builds, the location is the modified [location] so that `projectDir` is updated to be
     * the included directory.
     */
    private fun computeGradleBuild(
        build: GradleBuildDefinitionImpl,
        location: ProjectLocation
    ): GradleBuild {
        val rootFolder = location.projectDir.toPath()

        val includedBuilds = build.includedBuilds.values.associate {
            it.name to computeGradleBuild(
                it,
                ProjectLocation(rootFolder.resolve(it.name).toFile(), location.testLocation)
            )
        }

        val subProjects = build.subProjects.values.associate { definition ->
            when (definition) {
                is AndroidApplicationDefinitionImpl -> definition.path to AndroidApplicationImpl(
                    computeSubProjectPath(rootFolder, definition.path),
                    definition,
                    definition.namespace,
                    buildWriter,
                    build
                )

                is AndroidLibraryDefinitionImpl -> definition.path to AndroidLibraryImpl(
                    computeSubProjectPath(rootFolder, definition.path),
                    definition,
                    definition.namespace,
                    buildWriter,
                    build
                )

                is AndroidDynamicFeatureDefinitionImpl -> definition.path to AndroidFeatureImpl(
                    computeSubProjectPath(rootFolder, definition.path),
                    definition,
                    definition.namespace,
                    buildWriter,
                    build
                )

                is PrivacySandboxSdkDefinitionImpl -> definition.path to PrivacySandboxSdkImpl(
                    computeSubProjectPath(rootFolder, definition.path),
                    definition,
                    definition.namespace,
                    buildWriter,
                    build
                )

                else -> definition.path to GradleProjectImpl(
                    computeSubProjectPath(rootFolder, definition.path),
                    definition,
                    build
                )
            }
        }

        return GradleBuildImpl(
            rootFolder,
            subProjects = subProjects + mapOf(
                ":" to GradleProjectImpl(
                    computeSubProjectPath(
                        rootFolder,
                        ":"
                    ), build.rootProject, build
                )
            ),
            includedBuilds = includedBuilds,
            executorProvider = { instantiateExecutor(location) },
            modelBuilderProvider = { instantiateModelBuilder(location) },
        )
    }

    /**
     * computes a project location from the root directory and the gradle path
     */
    private fun computeSubProjectPath(rootDir: Path, gradlePath: String): Path {
        // the root project is the same location as the build.
        if (gradlePath == ":") return rootDir

        val newPath = if (gradlePath.startsWith(':')) gradlePath.substring(1) else gradlePath
        return rootDir.resolve(newPath.replace(':', '/'))
    }

    private fun instantiateExecutor(location: ProjectLocation): GradleTaskExecutor =
        GradleTaskExecutor(location, getTestInfo(), ruleOptionBuilder.gradleOptions, projectConnection) {
            lastBuildResult = it
        }

    private fun instantiateModelBuilder(location: ProjectLocation): ModelBuilderV2 =
        ModelBuilderV2(location, getTestInfo(), ruleOptionBuilder.gradleOptions, projectConnection) {
            lastBuildResult = it
        }.withPerTestPrefsRoot(true)

    private fun getTestInfo(): GradleTestInfo = object : GradleTestInfo {
        override val androidSdkDir: File?
            get() = ruleOptionBuilder.sdkConfiguration.sdkDir?.toFile()
        override val androidNdkSxSRootSymlink: File?
            get() = location.testLocation.buildDir.resolve(".").canonicalFile.resolve(SdkConstants.FD_NDK_SIDE_BY_SIDE) // FIXME
        override val additionalMavenRepoDir: Path?
            get() = null
        override val profileDirectory: Path?
            get() = null
    }

    private fun createLocalProp() {
        createLocalProp(location.projectDir)

        for (includedBuild in gradleBuild.includedBuilds.values) {
            createLocalProp(File(location.projectDir, includedBuild.name))
        }
    }

    private fun createGradleProp() {
        // Use a specific Jdk to run Gradle, which might be different from the one running the test
        // class
        val jdkVersionForGradle = System.getProperty("gradle.java.version");
        val propList = if (jdkVersionForGradle != null && jdkVersionForGradle == "17") {
            ruleOptionBuilder.gradleProperties + "org.gradle.java.home=${TestUtils.getJava17Jdk().toString().replace("\\", "/")}"
        } else {
            ruleOptionBuilder.gradleProperties
        }

        if (propList.isEmpty()) {
            return
        }

        val file = File(location.projectDir, "gradle.properties")

        file.appendText(
            propList.joinToString(separator = System.lineSeparator(), prefix = System.lineSeparator(), postfix = System.lineSeparator())
        )
    }

    private fun createLocalProp(destinationDir: File) {
        val localProp = ProjectPropertiesWorkingCopy.create(
            destinationDir.absolutePath, ProjectPropertiesWorkingCopy.PropertyType.LOCAL
        )

        ruleOptionBuilder.sdkConfiguration.sdkDir?.let {
            localProp.setProperty(ProjectProperties.PROPERTY_SDK, it.toString())
        }

//        if (withCmakeDirInLocalProp && cmakeVersion != null && cmakeVersion.isNotEmpty()) {
//            localProp.setProperty(
//                ProjectProperties.PROPERTY_CMAKE,
//                getCmakeVersionFolder(cmakeVersion).absolutePath
//            )
//        }

        localProp.save()
    }


    override fun apply(
        base: Statement,
        description: Description
    ): Statement? {
        return object: Statement() {
            override fun evaluate() {
                val staticRule = description.methodName == null

                if (mutableProjectLocation == null) {
                    mutableProjectLocation = initializeProjectLocation(
                        description.testClass,
                        description.methodName,
                        name
                    )
                }

                // log the location to help with debugging if needed
                println("Project location for ${description}: ${mutableProjectLocation!!.projectDir}")

                // the rule is static, then it's created just once for all the test methods and therefore
                // we write it now.
                if (staticRule) {
                    doWriteBuild()
                    status = Status.WRITTEN_STATIC
                }

                var testFailed = false
                try {
                    base.evaluate()
                } catch (e: Throwable) {
                    testFailed = true
                    if (e is GradleConnectionException) {
                        debugGradleConnectionExceptionThenRethrow(e, TestUtils.getTestOutputDir().toFile())
                    } else {
                        throw e
                    }
                } finally {
                    openConnections.forEach(ProjectConnection::close)

                    if (!System.getProperty("os.name").contains("Windows")) {
                        checkConfigurationCache()
                    }

                    if (testFailed) {
                        logBuildResult(description)
                    }
                }
            }
        }
    }

    private val location: ProjectLocation
        get() = mutableProjectLocation ?: error("Project location has not been initialized yet")

    private val projectConnection: ProjectConnection by lazy {

        val connector = GradleConnector.newConnector()
        (connector as DefaultGradleConnector)
            .daemonMaxIdleTime(
                GRADLE_DEAMON_IDLE_TIME_IN_SECONDS,
                TimeUnit.SECONDS
            )

        connector
            .useGradleUserHomeDir(location.testLocation.gradleUserHome.toFile())
            .forProjectDirectory(location.projectDir)

        val gradleLocation = ruleOptionBuilder.gradleLocation

        if (gradleLocation.customGradleInstallation != null) {
            connector.useInstallation(gradleLocation.customGradleInstallation)
        } else {
            connector.useDistribution(gradleLocation.getDistributionZip().toURI())
        }

        connector.connect().also { connection ->
            openConnections.add(connection)
        }
    }

    private fun checkConfigurationCache() {
        val checker = ConfigurationCacheReportChecker()
        build.subProject(":").location.resolve("build/reports").toFile().walk()
            .filter { it.isFile }
            .filter { it.name != "configuration-cache.html" }
            .forEach(checker::checkReport)
    }

    private fun logBuildResult(description: Description) {
        lastBuildResult?.let {
            System.err
                .println("""
                    ==============================================
                    = Test $description failed. Last build:
                    ==============================================
                    =================== Stderr ===================
                """.trimIndent())
            // All output produced during build execution is written to the standard
            // output file handle since Gradle 4.7. This should be empty.
            it.stderr.forEachLine { System.err.println(it) }
            System.err
                .println("=================== Stdout ===================")
            it.stdout.forEachLine { System.err.println(it) }
            System.err
                .println("""
                    ==============================================
                    =============== End last build ===============
                    ==============================================
                """.trimIndent())
        }
    }

    private enum class Status(
        val written: Boolean = false,
    ) {
        PENDING, WRITTEN_STATIC(true), WRITTEN_USER(true)
    }
}
