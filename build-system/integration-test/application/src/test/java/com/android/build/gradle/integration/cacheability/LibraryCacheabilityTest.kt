/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.build.gradle.integration.cacheability

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldLibraryApp
import com.android.build.gradle.integration.common.truth.TaskStateList.ExecutionState.DID_WORK
import com.android.build.gradle.integration.common.truth.TaskStateList.ExecutionState.FAILED
import com.android.build.gradle.integration.common.truth.TaskStateList.ExecutionState.FROM_CACHE
import com.android.build.gradle.integration.common.truth.TaskStateList.ExecutionState.SKIPPED
import com.android.build.gradle.integration.common.truth.TaskStateList.ExecutionState.UP_TO_DATE
import com.android.build.gradle.integration.common.utils.CacheabilityTestHelper
import com.android.build.gradle.integration.common.utils.TestFileUtils
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Similar to [CacheabilityTest], but  focusing on tasks invoked from a library module to verify a
 * different set of tasks.
 */
class LibraryCacheabilityTest {

    companion object {

        private const val GRADLE_BUILD_CACHE_DIR = "gradle-build-cache"

        /**
         * The expected states of tasks when running a second build with the Gradle build cache
         * enabled from an identical project at a different location.
         */
        private val EXPECTED_TASK_STATES =
            mapOf(
                // Sort by alphabetical order for easier searching
                UP_TO_DATE to setOf(
                    ":app:clean",
                    ":app:generateReleaseAssets",
                    ":app:preBuild",
                    ":app:preReleaseBuild",
                    ":lib:clean",
                    ":lib:generateReleaseAssets",
                    ":lib:preBuild",
                    ":lib:preReleaseBuild"
                ),
                FROM_CACHE to setOf(
                    ":app:compileReleaseArtProfile",
                    ":app:compileReleaseJavaWithJavac",
                    ":app:compileReleaseNavigationResources",
                    ":app:compressReleaseAssets",
                    ":app:desugarReleaseFileDependencies",
                    ":app:dexBuilderRelease",
                    ":app:extractDeepLinksRelease",
                    ":app:generateReleaseRFile",
                    ":app:generateReleaseResValues",
                    ":app:generateReleaseResources",
                    ":app:javaPreCompileRelease",
                    ":app:lintVitalAnalyzeRelease",
                    ":app:mergeDexRelease",
                    ":app:mergeExtDexRelease",
                    ":app:mergeReleaseGlobalSynthetics",
                    ":app:mergeReleaseResources",
                    ":app:optimizeReleaseResources",
                    ":app:packageReleaseResources",
                    ":app:parseReleaseLocalResources",
                    ":app:processReleaseMainManifest",
                    ":app:processReleaseManifest",
                    ":app:processReleaseManifestForPackage",
                    ":app:processReleaseNavigationResources",
                    ":app:processReleaseResources",
                    ":lib:compileReleaseJavaWithJavac",
                    ":lib:compileReleaseLibraryResources",
                    ":lib:extractDeepLinksForAarRelease",
                    ":lib:extractDeepLinksRelease",
                    ":lib:extractReleaseAnnotations",
                    ":lib:generateReleaseRFile",
                    ":lib:generateReleaseResValues",
                    ":lib:generateReleaseResources",
                    ":lib:javaPreCompileRelease",
                    ":lib:lintVitalAnalyzeRelease",
                    ":lib:mergeReleaseResources",
                    ":lib:packageReleaseResources",
                    ":lib:parseReleaseLocalResources",
                    ":lib:processReleaseManifest",
                    ":lib:processReleaseNavigationResources",
                    ":lib:syncReleaseLibJars",
                    ":lib:verifyReleaseResources"
                ),
                /*
                 * Tasks that should be cacheable but are not yet cacheable.
                 *
                 * If you add a task to this list, remember to file a bug for it.
                 */
                DID_WORK to setOf(
                    ":app:checkReleaseAarMetadata",
                    ":app:checkReleaseDuplicateClasses",
                    ":app:collectReleaseDependencies",
                    ":app:createReleaseApkListingFileRedirect",
                    ":app:createReleaseCompatibleScreenManifests",
                    ":app:extractProguardFiles",
                    ":app:extractReleaseVersionControlInfo",
                    ":app:generateReleaseLintVitalReportModel",
                    ":app:lintVitalRelease",
                    ":app:lintVitalReportRelease",
                    ":app:mapReleaseSourceSetPaths",
                    ":app:mergeReleaseArtProfile",
                    ":app:mergeReleaseAssets",
                    ":app:mergeReleaseJavaResource",
                    ":app:mergeReleaseJniLibFolders",
                    ":app:mergeReleaseStartupProfile",
                    ":app:packageRelease",
                    ":app:sdkReleaseDependencyData",
                    ":app:writeReleaseAppMetadata",
                    ":app:writeReleaseSigningConfigVersions",
                    ":lib:bundleLibCompileToJarRelease",
                    ":lib:bundleLibRuntimeToJarRelease",
                    ":lib:bundleReleaseAar", /*Bug 121275773 */
                    ":lib:bundleReleaseLocalLintAar",/*Bug 121275773 */
                    ":lib:copyReleaseJniLibsProjectAndLocalJars", /** Intentionally not cacheable. See [com.android.build.gradle.internal.tasks.LibraryJniLibsTask] */
                    ":lib:copyReleaseJniLibsProjectOnly",
                    ":lib:checkReleaseAarMetadata", /** Intentionally not cacheable. See [com.android.build.gradle.internal.tasks.CheckAarMetadataTask] */
                    ":lib:createFullJarRelease",
                    ":lib:extractProguardFiles",
                    ":lib:generateReleaseLintModel",
                    ":lib:generateReleaseLintVitalModel",
                    ":lib:mapReleaseSourceSetPaths", /* Intentionally not cacheable */
                    ":lib:mergeReleaseAssets",
                    ":lib:mergeReleaseConsumerProguardFiles", /** Intentionally not cacheable. See [com.android.build.gradle.internal.tasks.MergeConsumerProguardFilesTask] */
                    ":lib:mergeReleaseGeneratedProguardFiles", /** Intentionally not cacheable. See [com.android.build.gradle.internal.tasks.MergeGeneratedProguardFilesCreationAction] */
                    ":lib:mergeReleaseJavaResource", /* Bug 181142260 */
                    ":lib:mergeReleaseJniLibFolders",
                    ":lib:prepareLintJarForPublish", /* b/8120413672 */
                    ":lib:prepareReleaseArtProfile", /* No Bug, task is just file copy */
                    ":lib:writeReleaseAarMetadata", /** Intentionally not cacheable. See [com.android.build.gradle.internal.tasks.AarMetadataTask] */
                    ":lib:writeReleaseLintModelMetadata"
                ),
                SKIPPED to setOf(
                    ":app:assembleRelease",
                    ":app:extractReleaseNativeSymbolTables",
                    ":app:mergeReleaseNativeDebugMetadata",
                    ":app:mergeReleaseNativeLibs",
                    ":app:processReleaseJavaRes",
                    ":app:stripReleaseDebugSymbols",
                    ":lib:assembleRelease",
                    ":lib:mergeReleaseNativeLibs",
                    ":lib:processReleaseJavaRes",
                    ":lib:stripReleaseDebugSymbols"
                ),
                FAILED to setOf()
            )
    }

    @get:Rule
    val projectCopy1 = setUpTestProject("projectCopy1")

    @get:Rule
    val projectCopy2 = setUpTestProject("projectCopy2")

    @get:Rule
    val buildCacheDirRoot = TemporaryFolder()

    private fun setUpTestProject(projectName: String): GradleTestProject {
        return GradleTestProject.builder()
            .fromTestApp(HelloWorldLibraryApp())
            .withName(projectName)
            .dontOutputLogOnFailure()
            .disableBuiltInKotlin()
            .create()
    }

    @Before
    fun setUp() {
        for (project in listOf(projectCopy1, projectCopy2)) {
            TestFileUtils.appendToFile(
                project.getSubproject("app").buildFile,
                """
                    android {
                        buildFeatures { resValues = true }
                    }
                """.trimIndent()
            )
            TestFileUtils.appendToFile(
                project.getSubproject("lib").buildFile,
                """
                    android {
                        buildFeatures { resValues = true }
                    }
                """.trimIndent()
            )
        }
    }

    @Test
    fun testRelocatability() {
        val buildCacheDir = buildCacheDirRoot.root.resolve(GRADLE_BUILD_CACHE_DIR)

        CacheabilityTestHelper(projectCopy1, projectCopy2, buildCacheDir)
            // Runs :app:assembleRelease module to invoke additional :lib tasks.
            .runTasks("clean", ":lib:assembleRelease", ":app:assembleRelease")
            .assertTaskStatesByGroups(EXPECTED_TASK_STATES, exhaustive = true)
    }
}
