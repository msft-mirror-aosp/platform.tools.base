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
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.ModelBuilderV2
import com.android.build.gradle.integration.common.fixture.ProjectPropertiesWorkingCopy
import com.android.build.gradle.integration.common.fixture.TestProjectPaths
import com.android.build.gradle.integration.common.fixture.debugGradleConnectionExceptionThenRethrow
import com.android.build.gradle.integration.common.fixture.gradle_project.BuildSystem
import com.android.build.gradle.integration.common.fixture.gradle_project.TestLocation
import com.android.build.gradle.integration.common.fixture.gradle_project.initializeProjectLocation
import com.android.build.gradle.integration.common.fixture.project.builder.GlobalDefinitionStateImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.options.DefaultRuleOptionBuilder
import com.android.build.gradle.integration.common.fixture.project.options.LocalRuleOptionBuilder
import com.android.build.gradle.integration.common.truth.forEachLine
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.BooleanOption.DEFAULT_TARGET_SDK_TO_COMPILE_SDK_IF_UNSET
import com.android.build.gradle.options.BooleanOption.ENABLE_APP_COMPILE_TIME_R_CLASS
import com.android.build.gradle.options.BooleanOption.ENABLE_LEGACY_VARIANT_API
import com.android.build.gradle.options.BooleanOption.USE_NEW_DSL
import com.android.sdklib.internal.project.ProjectProperties
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.TestUtils
import com.android.utils.FileUtils
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.internal.consumer.DefaultGradleConnector
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.PathWalkOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.walk
import kotlin.io.path.writeBytes

internal class GradleRuleImpl internal constructor(
    private val buildDefinition: GradleBuildDefinitionImpl,
    private val ruleOptionBuilder: DefaultRuleOptionBuilder,
    private val externalLibraries: List<MavenRepoGenerator.Library>,
    private val enableProfileOutput: Boolean,
    private val testProjectName: String?,
): GradleRule {

    companion object {
        internal val AGP_9_OPT_OUTS = mapOf(
            DEFAULT_TARGET_SDK_TO_COMPILE_SDK_IF_UNSET to false,
        )
    }

    private var status = Status.PENDING

    private lateinit var locations: GradleRuleLocation

    private val openConnections = mutableListOf<ProjectConnection>()

    /** The last build result. this is only used to log it in case of a test failure */
    private var lastBuildResult: GradleBuildResult? = null

    override val build: GradleBuild by lazy {
        status = Status.WRITTEN

        doWriteBuild()

        val profileDirectory = if (enableProfileOutput)
            locations.testFiles.resolve(GradleTestProjectBuilder.DEFAULT_PROFILE_DIR)
        else
            null

        computeGradleBuild(
            buildDefinition = buildDefinition,
            destinationPath = locations.testFiles,
            testSupportLocations = locations.testSupportLocations,
            profileDirectory = profileDirectory
        )
    }

    override fun build(action: GradleBuildDefinition.() -> Unit): GradleBuild {
        // cannot reconfigure the build since static rules creates a single build for all test methods.
        if (status == Status.WRITTEN) {
            throw RuntimeException("Build was already reconfigured and written. Cannot be configured twice.")
        }

        action(buildDefinition)

        return build
    }

    override fun configure(): LocalRuleOptionBuilder =
        LocalRuleOptionBuilder(this, this.ruleOptionBuilder)

    override fun getMainBuildDirectory(): Path {
        return locations.testFiles.resolve(buildDefinition.rootFolderName)
    }

    private fun doWriteBuild() {
        val rootBuildPath = locations.testFiles.resolve(buildDefinition.rootFolderName)
        FileUtils.deleteRecursivelyIfExists(rootBuildPath.toFile())
        rootBuildPath.createDirectories()

        // if we have a test Project, make a copy of it, without the build files
        if (testProjectName != null) {
            val sourceProject = TestProjectPaths.getTestProjectPath(testProjectName)
            copyProjectWithoutBuildFiles(sourceProject, rootBuildPath)
        }

        // always create the maven repo as new items can be added during reconfiguration
        val repoPath = computeMavenRepoLocation()

        val localRepositories = buildList {
            addAll(BuildSystem.get().localRepositories)
            add(repoPath)
        }

        // Libraries can also be added inline during dependencies. We need to go through all
        // the build definitions to query their projects for libraries added in such way.
        val allLibraries = buildDefinition.gatherInlineLibraries() + externalLibraries

        if (allLibraries.isNotEmpty()) {
            MavenRepoGenerator(allLibraries).generate(repoPath)
        }

        val globalState = GlobalDefinitionStateImpl(
            getDefaultProperties(),
            localRepositories,
            buildDefinition.handleCustomBuildLogic(rootBuildPath)
        )

        buildDefinition.write(
            rootBuildPath,
            globalState,
            ruleOptionBuilder.disableBrokenBuiltInKotlinOptOutChecks,
            ruleOptionBuilder.disableBrokenNewDslOptOutChecks
        )

        createAncillaryBuildFiles()
    }

    @OptIn(ExperimentalPathApi::class)
    private fun copyProjectWithoutBuildFiles(
        source: Path,
        destination: Path,
    ) {
        source.walk(PathWalkOption.INCLUDE_DIRECTORIES).forEach { sourceFile ->
            val name = sourceFile.name
            if (name.endsWith(".gradle") || name.endsWith(".gradle.kts"))
                return@forEach

            val relativePath = source.relativize(sourceFile)
            val destinationFile = destination.resolve(relativePath)
            if (sourceFile.isDirectory()) {
                destinationFile.createDirectories()
            } else {
                destinationFile.writeBytes(sourceFile.readBytes())
            }
        }
    }

    private fun getDefaultProperties(): List<String> {
        return buildList {
            // This is necessary when setting jvmToolchain to 17
            // This must be injected here to not impact unit tests of the fixture as they do not
            // have access to this injected value
            add("org.gradle.java.installations.paths=${TestUtils.getJava17Jdk().toString().replace("\\", "/")}")

            val jdkVersionForGradle = System.getProperty("gradle.java.version")
            if (jdkVersionForGradle == "17") {
                add("org.gradle.java.home=${TestUtils.getJava17Jdk().toString().replace("\\", "/")}")
            }
        }
    }

    private fun computeMavenRepoLocation(): Path =
        locations.testFiles.resolve(buildDefinition.rootFolderName).resolve("_maven_repo")

    /**
     * compute a [GradleBuild].
     *
     * For included builds, the location is the modified [locations] so that `projectDir` is updated to be
     * the included directory.
     */
    private fun computeGradleBuild(
        buildDefinition: GradleBuildDefinitionImpl,
        destinationPath: Path,
        testSupportLocations: TestLocation,
        profileDirectory: Path?
    ): GradleBuild {
        // this is the location for the actual build
        val buildPath = destinationPath.resolve(buildDefinition.rootFolderName)

        val includedBuilds = buildDefinition.includedBuilds.values.associate {
            it.name to computeGradleBuild(it, buildPath, testSupportLocations, profileDirectory)
        }

        val subProjects = buildDefinition.subProjects.values.associate { definition ->
            val subProjectLocation = computeSubProjectPath(buildPath, definition.path)

            definition.path to when (definition) {
                is AndroidApplicationDefinitionImpl -> AndroidApplicationImpl(
                    subProjectLocation,
                    definition,
                    definition.namespace,
                )

                is AndroidLibraryDefinitionImpl -> AndroidLibraryImpl(
                    subProjectLocation,
                    definition,
                    definition.namespace,
                )

                is AndroidDynamicFeatureDefinitionImpl -> AndroidFeatureImpl(
                    subProjectLocation,
                    definition,
                    definition.namespace,
                )

                is AndroidTestDefinitionImpl -> AndroidTestImpl(
                    subProjectLocation,
                    definition,
                    definition.namespace,
                )

                is PrivacySandboxSdkDefinitionImpl -> PrivacySandboxSdkImpl(
                    subProjectLocation,
                    definition,
                )

                is AndroidXPrivacySandboxLibraryDefinitionImpl -> AndroidLibraryImpl(
                    subProjectLocation,
                    definition,
                    definition.namespace
                )

                is AssetPackDefinitionImpl -> AssetPackImpl(
                    subProjectLocation,
                    definition,
                )

                is AssetPackBundleDefinitionImpl -> AssetPackBundleImpl(
                    subProjectLocation,
                    definition,
                )

                is AiPackDefinitionImpl -> AiPackImpl(
                    subProjectLocation,
                    definition,
                )

                is FusedLibraryDefinitionImpl -> FusedLibraryImpl(
                    subProjectLocation,
                    definition,
                )

                is JavaLibraryProjectDefinition -> JavaLibraryProjectImpl(
                    subProjectLocation,
                    definition
                )

                is KotlinMultiplatformDefinitionImpl -> KotlinMultiplatformProjectImpl(
                    subProjectLocation,
                    definition
                )

                is GenericProjectDefinitionImpl -> GenericProjectImpl(
                    subProjectLocation,
                    definition,
                )

                else -> throw RuntimeException("Unsupported GradleProjectDefinition type: ${definition::class.java}")
            }
        } + mapOf(
            ":" to GenericProjectImpl(
                computeSubProjectPath(buildPath, ":"),
                buildDefinition.rootProject,
            )
        )

        return GradleBuildImpl(
            buildPath,
            subProjects = subProjects,
            includedBuilds = includedBuilds,
            definition = buildDefinition,
            executorProvider = { instantiateExecutor() },
            modelBuilderProvider = { instantiateModelBuilder() },
            mavenRepoPath = computeMavenRepoLocation(),
            profileDirectory = profileDirectory
        ).also { build ->
            subProjects.values.forEach {
                it.build = build
            }
        }
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

    private fun instantiateExecutor(): GradleTaskExecutor =
        GradleTaskExecutor(
            locations.toProjectLocation(buildDefinition),
            getTestInfo(),
            ruleOptionBuilder.gradleOptions,
            projectConnection
        ) {
            lastBuildResult = it
        }

    private fun instantiateModelBuilder(): ModelBuilderV2 =
        ModelBuilderV2(
            locations.toProjectLocation(buildDefinition),
            getTestInfo(),
            ruleOptionBuilder.gradleOptions,
            projectConnection
        ) {
            lastBuildResult = it
        }.withPerTestPrefsRoot(true).also {
            for (entry in AGP_9_OPT_OUTS) {
                it.suppressOptionWarning(entry.key)
            }
        }

    private fun getTestInfo(): GradleTestInfo = object : GradleTestInfo {
        override val androidSdkDir: File?
            get() = ruleOptionBuilder.sdkConfiguration.sdkDir?.toFile()
        override val androidNdkSxSRootSymlink: File
            get() = locations.testSupportLocations.buildDir.resolve(".").canonicalFile.resolve(SdkConstants.FD_NDK_SIDE_BY_SIDE) // FIXME
        override val additionalMavenRepoDir: Path
            get() = computeMavenRepoLocation()
        override val profileDirectory: Path?
            get() = build.profileDirectory
    }

    private fun createAncillaryBuildFiles() {
        buildDefinition.createAncillaryBuildFiles(locations.testFiles) { path ->
            createLocalProp(path)
        }
    }

    private fun createLocalProp(destinationDir: Path) {
        val localProp = ProjectPropertiesWorkingCopy.create(
            destinationDir.toString(), ProjectPropertiesWorkingCopy.PropertyType.LOCAL
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
    ): Statement {
        validateWithAllowList(description.testClass)

        return object: Statement() {
            override fun evaluate() {
                // We should not support class level application of this rule as it does not make
                // any sense.
                // This would mean a single version of the on-disk project which means
                // every test will write into the same output older which can be problematic
                // there's also no (easy) way to write the project just once as each test
                // gets its own instance of the test class (and therefore rule instance as well)
                // so using in-instance caching does not work (even though our previous fixtures
                // used this mechanism)
                if (description.methodName == null)
                    throw RuntimeException("Class level rule application is not supported.")

                locations = GradleRuleLocation(initializeProjectLocation(
                        description.testClass,
                        description.methodName,
                        // this is supposed to be the name of the project, but we really want the main folder
                        // that will contain the build and other elements like the maven repo. therefore we
                        // pass null instead
                        projectName = null
                    )
                )

                // log the location to help with debugging if needed
                println("Project location for ${description}: ${locations.testFiles}")

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

    private fun validateWithAllowList(testClass: Class<*>) {
        // make sure the allow list for test classes using the old fixture
        // does not contain a converted test.
        if (GradleTestProject.allowedTests.contains(testClass.name)) {
            throw RuntimeException(
                """
                    Test class ${testClass.name} was found in the allow list for the old test fixture.
                    Remove it from:
                    tools/base/build-system/integration-test/framework/src/main/resources/allow-listed-test-classes.txt
                """.trimIndent()
            )
        }
    }

    private val projectConnection: ProjectConnection by lazy {

        val connector = GradleConnector.newConnector()
        (connector as DefaultGradleConnector)
            .daemonMaxIdleTime(
                GradleTestProject.GRADLE_DEAMON_IDLE_TIME_IN_SECONDS,
                TimeUnit.SECONDS
            )

        connector
            .useGradleUserHomeDir(locations.testSupportLocations.gradleUserHome.toFile())
            .forProjectDirectory(locations.testFiles.resolve(buildDefinition.rootFolderName).toFile())

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
        (build as GradleBuildImpl).subProject(":")
            .resolve("build/reports")
            .toFile()
            .walk()
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

    private enum class Status {
        PENDING, WRITTEN
    }
}
