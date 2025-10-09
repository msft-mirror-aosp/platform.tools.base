/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.internal.plugins

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.internal.dsl.AgpDslLockedException
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.internal.dsl.CompileSdkVersionImpl
import com.android.build.gradle.internal.fixture.TestConstants
import com.android.build.gradle.internal.fixture.TestProjects
import com.android.build.gradle.internal.packaging.defaultExcludes
import com.android.build.gradle.internal.packaging.defaultMerges
import com.android.build.gradle.internal.utils.importOfflineMavenRepo
import com.android.build.gradle.options.BooleanOption
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.truth.StringSubject
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.TruthJUnit.assume
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFailsWith

/** Functional tests for the new Kotlin DSL. */
class KotlinDslTest {

    @get:Rule
    val projectDirectory = TemporaryFolder()

    private lateinit var plugin: AppPlugin
    private lateinit var android: ApplicationExtension
    private lateinit var project: Project

    init {
        importOfflineMavenRepo()
    }

    @Before
    fun setUp() {
        project = TestProjects.builder(projectDirectory.newFolder("project").toPath())
            .withPlugin(TestProjects.Plugin.APP)
            .build()

        initFieldsFromProject()
    }

    private fun initFieldsFromProject() {
        android =
            project.extensions.getByType(ApplicationExtension::class.java)
        android.compileSdk = TestConstants.COMPILE_SDK_VERSION
        android.namespace = "com.example.namespace"
        plugin = project.plugins.getPlugin(AppPlugin::class.java)
    }

    private val ApplicationExtension.compileSdkHash: String? get() {
        var result: String? = null
        compileSdk {
             version?.let { result = (it as CompileSdkVersionImpl).toHash() }
        }
        return result;
    }

    @Test
    fun testCompileSdk() {

        android.compileSdk = 28
        assertThat(android.compileSdkHash).isEqualTo("android-28")

        android.compileSdkExtension = 2
        assertThat(android.compileSdkHash).isEqualTo("android-28-ext2")

        android.compileSdk = null
        assertThat(android.compileSdkHash).isNull()

        android.compileSdk {
            version = preview("S")
            assertThat(version?.codeName).isEqualTo("S")
        }
        assertThat(android.compileSdkPreview).isEqualTo("S")

        android.compileSdk {
            version = release(36)
            assertThat(version?.apiLevel).isEqualTo(36)
        }

        android.compileSdk {
            version = release(36) {
                minorApiLevel = 1
                sdkExtension = 18
            }
            assertThat(version?.sdkExtension).isEqualTo(18)
        }
        // Ensure invoking compileSdk {} won't cause reset
        android.compileSdk {}

        assertThat(android.compileSdkHash).isEqualTo("android-36.1-ext18")
    }

    @Test
    fun testCompileSdkPreview() {
        android.compileSdk = 28
        assertThat(android.compileSdkPreview).isNull()

        assertThat(attempt { android.compileSdkPreview = "29" })
            .isEqualTo("Invalid integer value for compileSdkPreview (29). Use compileSdk instead")

        assertThat(attempt { android.compileSdkPreview = "android-R" })
            .isEqualTo("Invalid value for compileSdkPreview (\"android-R\"). Value must be a platform preview name (e.g. \"R\")")

        assertThat(attempt { android.compileSdkPreview = "foo" })
            .isEqualTo("Invalid value for compileSdkPreview (\"foo\"). Value must be a platform preview name (e.g. \"S\")")

        android.compileSdkPreview = "S"
        assertThat(android.compileSdkPreview).isEqualTo("S")

        android.compileSdkPreview = "S"
        assertThat(android.compileSdkPreview).isEqualTo("S")

        android.compileSdkAddon(
            "Google Inc.",
            "Google APIs",
            TestConstants.COMPILE_SDK_VERSION_WITH_GOOGLE_APIS
        )
        assertThat(android.compileSdkPreview).isNull()

        android.compileSdkPreview = null
        assertThat(android.compileSdk).isNull()
        assertThat(android.compileSdkPreview).isNull()

        android.compileSdkVersion(29)
        assertThat(android.compileSdk).isEqualTo(29)

        android.compileSdkVersion("android-31")
        assertThat(android.compileSdk).isEqualTo(31)

        android.compileSdkVersion("android-S")
        assertThat(android.compileSdkPreview).isEqualTo("S")

        assertThat(attempt { android.compileSdkVersion("MadeUp") }).isEqualTo(
            """
                Unsupported value: MadeUp. Format must be one of:
                - android-31
                - android-36.2
                - android-31-ext2
                - android-36.2-ext2
                - android-T
                - vendorName:addonName:31
            """.trimIndent()
        )
        assertThat(android.compileSdk).isNull()
        assertThat(android.compileSdkPreview).isNull()
    }

    private inline fun attempt(action: () -> Unit): String? {
        return try {
            action()
            null
        } catch (t: Throwable) {
            t.message
        }
    }

    @Test
    fun testDslLocking() {
        plugin.createAndroidTasks(project)
        val exception = assertFailsWith(AgpDslLockedException::class) {
            android.compileSdk = 28
        }
        assertThat(exception).hasMessageThat().isEqualTo(
            """
                It is too late to set compileSdk
                It has already been read to configure this project.
                Consider either moving this call to be during evaluation,
                or using the variant API.""".trimIndent()
        )
    }

    @Test
    fun testNewDslLocking() {
        plugin.createAndroidTasks(project)
        // check no exception when no assignment to version
        android.compileSdk {
            release(32)
        }
        android.defaultConfig {
            maxSdk {
                release(28)
            }
            targetSdk {
                preview("S")
            }
            minSdk {}
        }
        val exception = assertFailsWith(AgpDslLockedException::class) {
            android.defaultConfig.targetSdk {
                version = release(28)
            }
        }
        assertThat(exception).hasMessageThat().isEqualTo(
            """
                It is too late to set targetSdk
                It has already been read to configure this project.
                Consider either moving this call to be during evaluation,
                or using the variant API.""".trimIndent()
        )
    }

    @Test
    fun testDslLockingLists() {
        plugin.createAndroidTasks(project)
        val exception = assertFailsWith(AgpDslLockedException::class) {
            android.flavorDimensions += "test"
        }
        assertThat(exception).hasMessageThat().isEqualTo(
            """
                It is too late to modify flavorDimensions
                It has already been read to configure this project.
                Consider either moving this call to be during evaluation,
                or using the variant API.""".trimIndent()
        )
    }

    @Test
    fun testDslLockingCollections() {
        plugin.createAndroidTasks(project)
        assertFailsWith(AgpDslLockedException::class) {
            android.buildTypes.create("customBuildType")
        }.also { exception ->
            assertThat(exception).hasMessageThat().isEqualTo(
                """
                    It is too late to add new build types
                    They have already been used to configure this project.
                    Consider moving this call to finalizeDsl or during evaluation.
                """.trimIndent()
            )
        }
        assertFailsWith(AgpDslLockedException::class) {
            android.productFlavors.create("orange")
        }.also { exception ->
            assertThat(exception).hasMessageThat().isEqualTo(
                """
                    It is too late to add new product flavors
                    They have already been used to configure this project.
                    Consider moving this call to finalizeDsl or during evaluation.
                """.trimIndent()
            )
        }
        assertFailsWith(AgpDslLockedException::class) {
            android.signingConfigs.create("qa")
        }.also { exception ->
            assertThat(exception).hasMessageThat().isEqualTo(
                """
                    It is too late to add new signing configs
                    They have already been used to configure this project.
                    Consider moving this call to finalizeDsl or during evaluation.
                """.trimIndent()
            )
        }
    }

    @Test
    fun testDslLockingWithinCollections() {
        val debug = android.buildTypes.getByName("debug")
        android.flavorDimensions += "fruit"
        val orange = android.productFlavors.create("orange")
        orange.dimension = "fruit"
        val qa = android.signingConfigs.create("qa")
        plugin.createAndroidTasks(project)

        assertFailsWith(AgpDslLockedException::class) {
            debug.isCrunchPngs = true
        }.also { exception ->
            assertThat(exception).hasMessageThat().isEqualTo(
                """
                    It is too late to set crunchPngs
                    It has already been read to configure this project.
                    Consider either moving this call to be during evaluation,
                    or using the variant API.
                """.trimIndent()
            )
        }

        assertFailsWith(AgpDslLockedException::class) {
            android.defaultConfig.applicationId = "com.example.fruit"
        }.also { exception ->
            assertThat(exception).hasMessageThat().isEqualTo(
                """
                    It is too late to set applicationId
                    It has already been read to configure this project.
                    Consider either moving this call to be during evaluation,
                    or using the variant API.
                """.trimIndent()
            )
        }

        assertFailsWith(AgpDslLockedException::class) {
            orange.applicationId = "com.example.orange"
        }.also { exception ->
            assertThat(exception).hasMessageThat().isEqualTo(
                """
                    It is too late to set applicationId
                    It has already been read to configure this project.
                    Consider either moving this call to be during evaluation,
                    or using the variant API.
                """.trimIndent()
            )
        }

        assertFailsWith(AgpDslLockedException::class) {
            qa.enableV1Signing = true
        }.also { exception ->
            assertThat(exception).hasMessageThat().isEqualTo(
                """
                    It is too late to set enableV1Signing
                    It has already been read to configure this project.
                    Consider either moving this call to be during evaluation,
                    or using the variant API.
                """.trimIndent()
            )
        }
    }

    @Test
    fun `compileAgainst externalNativeBuild ndkBuild ImplClass`() {

        val externalNativeBuild: com.android.build.gradle.internal.dsl.ExternalNativeBuild =
            android.externalNativeBuild as com.android.build.gradle.internal.dsl.ExternalNativeBuild

        // Using apply as made the intentionally not source compatible change here: Ib3e58c50c4a5af2ebc11882fc48d75b1e4f410fe
        externalNativeBuild.ndkBuild.apply {

            assertThat(path).isNull()
            path = File("path1")
            assertThatPath(path).endsWith("path1")
            path("path2")
            assertThatPath(path).endsWith("path2")
            setPath("path3")
            assertThatPath(path).endsWith("path3")

            assertThat(buildStagingDirectory).isNull()
            buildStagingDirectory = File("buildStagingDirectory1")
            assertThatPath(buildStagingDirectory).endsWith("buildStagingDirectory1")
            buildStagingDirectory("buildStagingDirectory2")
            assertThatPath(buildStagingDirectory).endsWith("buildStagingDirectory2")
            setBuildStagingDirectory("buildStagingDirectory3")
            assertThatPath(buildStagingDirectory).endsWith("buildStagingDirectory3")
        }

        // Using apply as made the intentionally not source compatible change here: Ib3e58c50c4a5af2ebc11882fc48d75b1e4f410fe
        externalNativeBuild.cmake.apply {
            assertThat(path).isNull()
            path = File("path1")
            assertThatPath(path).endsWith("path1")
            path("path2")
            assertThatPath(path).endsWith("path2")
            setPath("path3")
            assertThatPath(path).endsWith("path3")

            assertThat(buildStagingDirectory).isNull()
            buildStagingDirectory = File("buildStagingDirectory1")
            assertThatPath(buildStagingDirectory).endsWith("buildStagingDirectory1")
            buildStagingDirectory("buildStagingDirectory2")
            assertThatPath(buildStagingDirectory).endsWith("buildStagingDirectory2")
            setBuildStagingDirectory("buildStagingDirectory3")
            assertThatPath(buildStagingDirectory).endsWith("buildStagingDirectory3")

            assertThat(version).isNull()
            version = "version1"
            assertThat(version).isEqualTo("version1")
        }
    }

    /** Regression test for b/146488072 */
    @Test
    fun `compile against variant specific external native build class`() {
        android.defaultConfig.apply {
            // Check the getters return the more specific type
            externalNativeBuild.ndkBuild.arguments("a")
            externalNativeBuild.cmake.arguments("x")

            // Check the action methods use the more specific type
            externalNativeBuild {
                ndkBuild {
                    arguments("b")
                }
            }
            externalNativeBuild {
                cmake {
                    arguments("y")
                }
            }

            assertThat(externalNativeBuild.ndkBuild.arguments)
                .containsExactly("a", "b").inOrder()
            assertThat(externalNativeBuild.cmake.arguments)
                .containsExactly("x", "y").inOrder()
        }
    }

    @Test
    fun `manifest placeholders source compatibility`() {
        android.defaultConfig.apply {
            // Check can accept mapOf with string to string
            setManifestPlaceholders(mapOf("a" to "A"))
            assertThat(manifestPlaceholders).containsExactly("a", "A")
            // Check can add items when setter called with an immutable collection
            // (i.e. setter copies)
            setManifestPlaceholders(ImmutableMap.of())
            manifestPlaceholders["c"] = 3
            assertThat(manifestPlaceholders).containsExactly("c", 3)
            // Check use of overloaded +=
            manifestPlaceholders += mapOf("d" to "D")
            assertThat(manifestPlaceholders).containsExactly("c", 3,"d", "D")
        }
    }

    @Test
    fun `test minSdk in app`() {
        android.defaultConfig.apply {
            minSdk = 24
            assertThat(minSdk).isEqualTo(24)

            minSdk {
                version = release(36)
                assertThat(version?.apiLevel).isEqualTo(36)
            }
            assertThat(minSdk).isEqualTo(36)

            minSdk {
                version = preview("S")
                assertThat(version?.codeName).isEqualTo("S")
            }
            assertThat(minSdkPreview).isEqualTo("S")
        }
    }

    @Test
    fun `test maxSdk in app`() {
        android.defaultConfig.apply {
            maxSdk = 24
            assertThat(maxSdk).isEqualTo(24)

            maxSdk {
                version = release(36)
                assertThat(version?.apiLevel).isEqualTo(36)
            }
            assertThat(maxSdk).isEqualTo(36)
        }
    }

    @Test
    fun `test targetSdk in app`() {
        android.defaultConfig.apply {
            targetSdk = 24
            assertThat(targetSdk).isEqualTo(24)

            targetSdk {
                version = release(36)
                assertThat(version?.apiLevel).isEqualTo(36)
            }
            assertThat(targetSdk).isEqualTo(36)

            targetSdk {
                version = preview("S")
                assertThat(version?.codeName).isEqualTo("S")
            }
            assertThat(targetSdkPreview).isEqualTo("S")
        }
    }

    @Test
    fun `baseFlavor source compatibility`() {
        android.defaultConfig {
            setTestFunctionalTest(true)
            assertThat(testFunctionalTest).isTrue()
            setTestHandleProfiling(true)
            assertThat(testHandleProfiling).isTrue()
            resConfig("one")
            resConfigs("two", "three")
            resConfigs(listOf("four"))
            assertThat(resourceConfigurations).containsExactly("one", "two", "three", "four")
            assertFailsWith<Exception> {
                resConfigs("")
            }
        }
    }

    /** Regression test for https://b.corp.google.com/issues/155318103 */
    @Test
    fun `mergedFlavor source compatibility`() {
        // TODO: Likely delete this test or migrate to an integration test(b/418804641)
        assume().that(BooleanOption.USE_NEW_DSL.defaultValue).isFalse()
        val applicationVariants = (android as BaseAppModuleExtension).applicationVariants
        val fileF = File("f")
        val fileG = File("g")
        val fileH = File("h")
        applicationVariants.all { variant ->
            variant.mergedFlavor.manifestPlaceholders += mapOf("a" to "b")
            variant.mergedFlavor.testInstrumentationRunnerArguments += mapOf("c" to "d")
            variant.mergedFlavor.resourceConfigurations += "e"
            variant.mergedFlavor.proguardFiles += fileF
            variant.mergedFlavor.consumerProguardFiles += fileG // While not applicable to apps, the same objects are used for libraries
            variant.mergedFlavor.testProguardFiles += fileH
        }
        plugin.createAndroidTasks(project)
        assertThat(applicationVariants).hasSize(2)
        applicationVariants.first().also { variant ->
            assertThat(variant.mergedFlavor.manifestPlaceholders).containsExactly("a", "b")
            assertThat(variant.mergedFlavor.testInstrumentationRunnerArguments).containsExactly("c", "d")
            assertThat(variant.mergedFlavor.resourceConfigurations).containsExactly("e")
            assertThat(variant.mergedFlavor.proguardFiles).containsExactly(fileF)
            assertThat(variant.mergedFlavor.consumerProguardFiles).containsExactly(fileG)
            assertThat(variant.mergedFlavor.testProguardFiles).containsExactly(fileH)
        }
    }

    @Test
    fun `testInstrumentationRunnerArguments source compatibility`() {
        android.defaultConfig.testInstrumentationRunnerArguments.put("a", "b")
        assertThat(android.defaultConfig.testInstrumentationRunnerArguments).containsExactly("a", "b")

        android.defaultConfig.testInstrumentationRunnerArguments += "c" to "d"
        assertThat(android.defaultConfig.testInstrumentationRunnerArguments).containsExactly("a", "b", "c", "d")

        android.defaultConfig.setTestInstrumentationRunnerArguments(mutableMapOf("x" to "y"))
        assertThat(android.defaultConfig.testInstrumentationRunnerArguments).containsExactly("x", "y")
    }

    @Test
    fun `AnnotationProcessorOptions arguments source compatibility`() {
        android.defaultConfig.javaCompileOptions.annotationProcessorOptions {
            arguments["a"] = "b"

            assertThat(arguments).containsExactly("a", "b")
            arguments += mapOf("c" to "d")
            assertThat(arguments).containsExactly("a", "b", "c", "d")
        }
    }

    @Test
    fun `LintOptions source compatibility`() {
        android.lintOptions {
            enable += "a"
            assertThat(enable).containsExactly("a")
            disable += "b"
            assertThat(disable).containsExactly("b")
            checkOnly += "c"
            assertThat(checkOnly).containsExactly("c")
        }
    }

    @Test
    fun `matchingFallbacks source compatibility`() {
        android.productFlavors.create("example").apply {
            // Check the list can be mutated
            matchingFallbacks += "a"
            matchingFallbacks.add("b")
            assertThat(matchingFallbacks).containsExactly("a", "b")
            // Check the single value setter
            setMatchingFallbacks("c")
            assertThat(matchingFallbacks).containsExactly( "c")
            // Check the vararg setter
            setMatchingFallbacks("d", "e")
            assertThat(matchingFallbacks).containsExactly("d", "e")
            // Check the list setter
            setMatchingFallbacks(ImmutableList.of("f"))
            assertThat(matchingFallbacks).containsExactly("f")
            // Check the setter copies before clearing
            setMatchingFallbacks(matchingFallbacks)
            assertThat(matchingFallbacks).containsExactly("f")
        }
        android.buildTypes.create("qa").apply {
            // Check the list can be mutated
            matchingFallbacks += "a"
            matchingFallbacks.add("b")
            assertThat(matchingFallbacks).containsExactly("a", "b")
            // Check the single value setter
            setMatchingFallbacks("c")
            assertThat(matchingFallbacks).containsExactly( "c")
            // Check the vararg setter
            setMatchingFallbacks("d", "e")
            assertThat(matchingFallbacks).containsExactly("d", "e")
            // Check the list setter
            setMatchingFallbacks(ImmutableList.of("f"))
            assertThat(matchingFallbacks).containsExactly("f")
            // Check the setter copies before clearing
            setMatchingFallbacks(matchingFallbacks)
            assertThat(matchingFallbacks).containsExactly("f")
        }
    }

    interface CustomExtension {
        var customSetting: Boolean
    }

    val ExtensionAware.customExtension: CustomExtension
        get() = extensions.getByType(CustomExtension::class.java)

    fun ExtensionAware.customExtension(action: CustomExtension.() -> Unit) {
        extensions.configure(CustomExtension::class.java, action)
    }

    @Test
    fun `extension aware build types`() {
        android.buildTypes.all {
            it.extensions.add("custom", CustomExtension::class.java)
        }
        android.buildTypes.getByName("release").apply {
            customExtension {
                customSetting = true
            }
        }

        assertThat(android.buildTypes.getByName("debug").customExtension.customSetting).isFalse()
        assertThat(android.buildTypes.getByName("release").customExtension.customSetting).isTrue()
    }

    @Test
    fun `extension aware product flavors`() {
        android.productFlavors.all {
            it.extensions.add("custom", CustomExtension::class.java)
        }
        android.productFlavors.create("one")
        android.productFlavors.create("two").apply {
            customExtension {
                customSetting = true
            }
        }

        assertThat(android.productFlavors.getByName("one").customExtension.customSetting).isFalse()
        assertThat(android.productFlavors.getByName("two").customExtension.customSetting).isTrue()
    }

    @Test
    fun `java resource packaging options`() {
        android.packagingOptions {
            resources {
                excludes += "a"
                assertThat(excludes).containsExactlyElementsIn(defaultExcludes.plus("a"))
                pickFirsts += "b"
                assertThat(pickFirsts).containsExactly("b")
                merges += "c"
                assertThat(merges).containsExactlyElementsIn(defaultMerges.plus("c"))
            }
        }
    }

    @Test
    fun `native libs packaging options`() {
        android.packagingOptions {
            jniLibs {
                excludes += "a"
                assertThat(excludes).containsExactly("a")
                pickFirsts += "b"
                assertThat(pickFirsts).containsExactly("b")
                keepDebugSymbols += "c"
                assertThat(keepDebugSymbols).containsExactly("c")
            }
        }
    }

    private fun assertThatPath(file: File?): StringSubject {
        return assertThat(file?.path)
    }

    @Test
    fun `compatibility for compile sdk`() {
        android.apply {
            compileSdkVersion(TestConstants.COMPILE_SDK_VERSION)
            compileSdkVersion("android-${TestConstants.COMPILE_SDK_VERSION}")
        }
    }

    @Test
    fun `compatibility for enabling shrinking`() {
        android.buildTypes.forEach {
            it.isMinifyEnabled = true
            it.isShrinkResources = true
        }
    }

    @Test
    fun `compatibility for missingDimensionStrategy on productFlavor`() {
        android.productFlavors.create("example").apply {
            missingDimensionStrategy("minApi", "minApi18", "minApi23")
        }
    }

    @Test
    fun `compatibility for missingDimensionStrategy on defaultConfig`() {
        android.defaultConfig.apply {
            missingDimensionStrategy("minApi", "minApi18", "minApi23")
        }
    }

    @Test
    fun `whitespace handling in names`() {
        assertFailsWith(RuntimeException::class) {
            android.productFlavors.create("fla1 ")
        }.also { exception ->
            assertThat(exception).hasMessageThat()
                    .isEqualTo("ProductFlavor names cannot contain whitespace")
        }

        assertFailsWith(RuntimeException::class) {
            android.buildTypes.create("buildType ")
        }.also { exception ->
            assertThat(exception).hasMessageThat()
                    .isEqualTo("BuildType names cannot contain whitespace")
        }
    }
}
