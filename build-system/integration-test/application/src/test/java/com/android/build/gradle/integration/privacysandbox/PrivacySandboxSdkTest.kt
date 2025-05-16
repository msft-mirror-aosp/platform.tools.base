/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.privacysandbox

import com.android.SdkConstants
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox.privacySandboxSampleProject
import com.android.build.gradle.integration.common.output.ZipSubject
import com.android.build.gradle.internal.dsl.ModulePropertyKey
import com.android.build.gradle.options.BooleanOption
import com.android.ide.common.signing.KeystoreHelper
import com.android.testutils.apk.Dex
import com.android.testutils.apk.Zip
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** Integration tests for the privacy sandbox SDK */
class PrivacySandboxSdkTest {

    @get:Rule
    val rule = privacySandboxSampleProject()

    private fun GradleBuild.configuredExecutor() = executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
            .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
            .withPerTestPrefsRoot(true)
            .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679

    @Test
    fun testDexingWithR8() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk") {
                android {
                    experimentalProperties["android.experimental.privacysandboxsdk.optimize"] = false
                }
            }
        }

        val dexLocation = build.privacySandboxSdk(":privacy-sandbox-sdk")
            .intermediatesDir
            .resolve("dex/single/minifyBundleWithR8/classes.dex")

        build.configuredExecutor().run(":privacy-sandbox-sdk:minifyBundleWithR8")

        Dex(dexLocation).also { dex ->
            assertThat(dex.classes.keys).containsAtLeast(
                "Lcom/example/sdkImplA/Example;",
                "Lcom/example/androidlib/Example;",
                "Lcom/externaldep/externaljar/ExternalClass;"
            )
            assertThat(dex.classes["Lcom/example/sdkImplA/Example;"]!!.methods.map { it.name }).contains(
                "f1")
            assertThat(dex.classes["Lcom/example/androidlib/Example;"]!!.methods.map { it.name }).contains(
                "f2")
            assertThat(dex.classes["Lcom/example/sdkImplA/R\$string;"]!!.fields.map { it.name }).containsExactly(
                "string_from_sdk_impl_a")
        }

        // Check incremental changes are handled
        build.androidLibrary(":sdk-impl-a")
            .files
            .update("src/main/java/com/example/sdkImplA/Example.kt")
            .searchAndReplace("fun f1() {}", "fun g() {}")

        build.configuredExecutor().run(":privacy-sandbox-sdk:minifyBundleWithR8")

        Dex(dexLocation).also { dex ->
            assertThat(dex.classes["Lcom/example/sdkImplA/Example;"]!!.methods.map { it.name }).contains(
                "g")
            assertThat(dex.classes["Lcom/example/androidlib/Example;"]!!.methods.map { it.name }).contains(
                "f2")
        }
    }

    @Test
    fun testDexingWithR8optimization() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk") {
                android {
                    experimentalProperties["android.experimental.privacysandboxsdk.optimize"] = true
                    optimization.keepRules.files.add(projectDotFile("proguard-rules.pro"))
                }
                files.add(
                    "proguard-rules.pro",
                    """-keep class com.example.androidlib.Example { *; }
                """.trimMargin())
            }
        }

        val dexLocation = build.privacySandboxSdk(":privacy-sandbox-sdk")
            .intermediatesDir
            .resolve("dex/single/minifyBundleWithR8/classes.dex")

        build.configuredExecutor().run(":privacy-sandbox-sdk:minifyBundleWithR8")

        Dex(dexLocation).also { dex ->
            assertThat(dex.classes.keys).doesNotContain(
                "Lcom/example/sdkImplA/Example;"
            )
            assertThat(dex.classes.keys).contains(
                "Lcom/example/androidlib/Example;",
            )
            assertThat(dex.classes["Lcom/example/androidlib/Example;"]!!.methods.map { it.name }).contains(
                "f2")
            // none of the resources should be removed
            assertThat(dex.classes["Lcom/example/sdkImplA/R\$string;"]!!.fields.map { it.name }).containsExactly(
                "string_from_sdk_impl_a")
        }

        // Check incremental changes are handled
        build.androidLibrary(":android-lib")
            .files
            .update("src/main/java/com/example/androidlib/Example.java")
            .searchAndReplace("public void f2() {}", "public void g() {}")

        build.configuredExecutor().run(":privacy-sandbox-sdk:minifyBundleWithR8")

        Dex(dexLocation).also { dex ->
            assertThat(dex.classes["Lcom/example/androidlib/Example;"]!!.methods.map { it.name }).contains(
                "g")
        }
    }

    /** Regression test for b/389890488
     * Checks that there is no R8 warning when there is an SDK (privacy-sandbox-sdk) has a library
     * dependency (android-lib) that defines donotwarn consumer proguard rules on a library missing
     * classes at runtime with a compileOnly dependency (stub).
     */
    @Test
    fun testAndroidLibraryKeepRuleConsumption() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk") {
                android {
                    experimentalProperties["android.experimental.privacysandboxsdk.optimize"] =
                        false
                }
            }
            androidLibrary(":android-lib") {
                android {
                    buildTypes {
                        named("debug") {
                            it.consumerProguardFiles("proguard-rules.pro")
                        }
                        named("release") {
                            it.consumerProguardFiles("proguard-rules.pro")
                        }
                    }
                }
                files.add(
                    "proguard-rules.pro",
                    """
                        |-dontwarn some.clazz.that.doesnt.Exist
                        |-keep class com.example.androidlib.UnusedClass { *; }
                """.trimMargin()
                )
                files.add(
                    "src/main/java/com/example/androidlib/UnusedClass.java",
                    // language=java
                    """
                package com.example.androidlib;

                import some.clazz.that.doesnt.Exist;

                public class UnusedClass {

                    public UnusedClass() {}

                    public void unusedMethod() {
                        new Exist();
                    }
                }
            """.trimIndent()
                )
                dependencies {
                    compileOnly(project(":stub"))
                }
            }
            androidLibrary(":stub") {
                pluginCallbacks += DisableDebugVariantsCallback::class.java
                android {
                    namespace = "some.clazz.that.doesnt"
                    defaultConfig.minSdk = 23
                }
                files {
                    add(
                        "src/main/java/some/clazz/that/doesnt/Exist.java",
                        """
                            package some.clazz.that.doesnt;

                            public class Exist {

                                public Exist() {}
                        }
                        """.trimIndent()
                    )
                }
            }
        }

        val dexLocation = build.privacySandboxSdk(":privacy-sandbox-sdk")
            .intermediatesDir
            .resolve("dex/single/minifyBundleWithR8/classes.dex")

        var run = build.configuredExecutor().withArgument("--rerun-tasks")
            .run(":privacy-sandbox-sdk:minifyBundleWithR8")

        run.assertOutputDoesNotContain("R8: Missing class some.clazz.that.doesnt.Exist")
        Dex(dexLocation).also { dex ->
            assertThat(dex.classes.keys).contains(
                "Lcom/example/androidlib/UnusedClass;",
            )
            assertThat(dex.classes["Lcom/example/androidlib/UnusedClass;"]!!.methods.map { it.name }).contains(
                "unusedMethod"
            )
        }

        build.androidLibrary(":android-lib").files.remove("proguard-rules.pro")
        run = build.configuredExecutor().withArgument("--rerun-tasks")
            .run(":privacy-sandbox-sdk:minifyBundleWithR8")
        run.assertOutputContains("R8: Missing class some.clazz.that.doesnt.Exist")
    }

    // Regression test for b/389890488
    @Test
    fun testKmpProguardConsumptionWithPrivacySandbox() {
        val build = rule.build {
            privacySandboxSdk(":sandbox-sdk") {
                android {
                    minSdk = 24
                    bundle {
                        applicationId = "com.example.sandboxsdk"
                        sdkProviderClassName = "Test"
                        compatSdkProviderClassName = "Test"
                        setVersion(1, 2, 3)
                    }
                    experimentalProperties["android.experimental.privacysandboxsdk.optimize"] =
                        false
                }
                dependencies {
                    include(project(":kmplib"))
                }
            }
            androidLibrary(":kmplib") {
                applyPlugin(PluginType.KOTLIN_MPP)
                pluginCallbacks += ConfigureKmpCallback::class.java
                android {
                    defaultConfig {
                        minSdk = 24
                    }
                    buildTypes {
                        named("debug") {
                            it.consumerProguardFiles("proguard-rules.pro")
                        }
                        named("release") {
                            it.consumerProguardFiles("proguard-rules.pro")
                        }
                    }
                }
                files.add(
                    "proguard-rules.pro",
                    """
                        |-dontwarn some.clazz.that.doesnt.Exist
                        |-keep class pkg.name.kmplib.UnusedClass { *; }
                """.trimMargin()
                )
                files.add(
                    "src/androidMain/kotlin/pkg/name/kmplib/UnusedClass.kt",
                    // language=kotlin
                    """
                package pkg.name.kmplib

                import some.clazz.that.doesnt.Exist

                class UnusedClass {
                    fun unusedMethod() {
                        Exist()
                    }
                }
            """.trimIndent()
                )
                // KMP source-set dependencies set in the callback.
            }
            androidLibrary(":stub") {
                pluginCallbacks += DisableDebugVariantsCallback::class.java
                android {
                    namespace = "some.clazz.that.doesnt"
                    defaultConfig.minSdk = 23
                }
                files {
                    add(
                        "src/main/java/some/clazz/that/doesnt/Exist.java",
                        """
                            package some.clazz.that.doesnt;

                            public class Exist {

                                public Exist() {}
                        }
                        """.trimIndent()
                    )
                }
            }
        }

        val dexLocation = build.privacySandboxSdk(":sandbox-sdk")
            .intermediatesDir
            .resolve("dex/single/minifyBundleWithR8/classes.dex")

        var run = build.configuredExecutor().withArgument("--rerun-tasks")
            .run(":sandbox-sdk:minifyBundleWithR8")

        run.assertOutputDoesNotContain("R8: Missing class some.clazz.that.doesnt.Exist")
        Dex(dexLocation).also { dex ->
            assertThat(dex.classes.keys).contains(
                "Lpkg/name/kmplib/UnusedClass;",
            )
        }

        build.androidLibrary(":kmplib").files.remove("proguard-rules.pro")
        run = build.configuredExecutor().withArgument("--rerun-tasks")
            .run(":sandbox-sdk:minifyBundleWithR8")
        run.assertOutputContains("R8: Missing class some.clazz.that.doesnt.Exist")
    }

    @Test
    fun testAsb() {
        val build = rule.build

        build.configuredExecutor().run(":privacy-sandbox-sdk:assemble")

        val sdkProject = build.privacySandboxSdk(":privacy-sandbox-sdk")
        val asbFile = sdkProject.outputsDir.resolve("asb/single/privacy-sandbox-sdk.asb")

        val asbManifest = sdkProject.intermediatesDir
            .resolve("merged_manifest/single/mergeManifest/AndroidManifest.xml")
        val asbManifestBlameReport = sdkProject.outputsDir
            .resolve("${SdkConstants.FD_LOGS}/manifest-merger-mergeManifest-report.txt")
        assertThat(asbManifest).hasContents(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:tools="http://schemas.android.com/tools"
                    package="com.example.privacysandboxsdk" >

                    <uses-sdk
                        android:minSdkVersion="23"
                        android:targetSdkVersion="$DEFAULT_COMPILE_SDK_VERSION" />

                    <uses-permission android:name="android.permission.INTERNET" />
                    <uses-permission
                        android:name="com.example.privacysandboxsdkb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                        tools:requiredByPrivacySandboxSdk="true" />

                    <permission
                        android:name="com.example.privacysandboxsdk.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                        android:protectionLevel="signature" />

                    <uses-permission android:name="com.example.privacysandboxsdk.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />

                    <application android:appComponentFactory="androidx.core.app.CoreComponentFactory" >
                        <activity
                            android:name="androidx.privacysandbox.sdkruntime.client.activity.SdkActivity"
                            android:exported="true" />

                        <provider
                            android:name="androidx.startup.InitializationProvider"
                            android:authorities="com.example.privacysandboxsdk.androidx-startup"
                            android:exported="false" >
                            <meta-data
                                android:name="androidx.profileinstaller.ProfileInstallerInitializer"
                                android:value="androidx.startup" />
                        </provider>

                        <receiver
                            android:name="androidx.profileinstaller.ProfileInstallReceiver"
                            android:directBootAware="false"
                            android:enabled="true"
                            android:exported="true"
                            android:permission="android.permission.DUMP" >
                            <intent-filter>
                                <action android:name="androidx.profileinstaller.action.INSTALL_PROFILE" />
                            </intent-filter>
                            <intent-filter>
                                <action android:name="androidx.profileinstaller.action.SKIP_FILE" />
                            </intent-filter>
                            <intent-filter>
                                <action android:name="androidx.profileinstaller.action.SAVE_PROFILE" />
                            </intent-filter>
                            <intent-filter>
                                <action android:name="androidx.profileinstaller.action.BENCHMARK_OPERATION" />
                            </intent-filter>
                        </receiver>
                    </application>

                </manifest>
        """.trimIndent())
        assertThat(asbManifestBlameReport.isRegularFile()).isTrue()
        assertThat(asbFile.isRegularFile()).isTrue()

        ZipSubject.assertThat(asbFile) {
            textFile("BUNDLE-METADATA/com.android.tools.build.gradle/app-metadata.properties").apply {
                contains("appMetadataVersion=")
                contains("androidGradlePluginVersion=")
            }

            entries().contains("SdkBundleConfig.pb")
            innerZip("modules.resm") {
                entries().containsAtLeast(
                    "base/dex/classes.dex",
                    "base/assets/asset_from_sdkImplA.txt",
                    "base/manifest/AndroidManifest.xml",
                    "base/resources.pb",
                    "base/root/my_java_resource.txt",
                    "SdkModulesConfig.pb"
                )
            }
        }
    }

    @Test
    fun testAsbSigning() {
        val storeType = "jks"
        val storeFile = "privacysandboxsdk.jks"
        val storePassword = "rbStore123"
        val keyPassword = "rbKey123"
        val keyAlias = "privacysandboxsdkkey"

        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk") {
                android {
                    signingConfig {
                        this?.storeFile = projectDotFile(storeFile)
                        this?.keyAlias = keyAlias
                        this?.keyPassword = keyPassword
                        this?.storeType = storeType
                        this?.storePassword = storePassword
                    }
                }
            }
        }

        KeystoreHelper.createNewStore(
                storeType,
                build.privacySandboxSdk(":privacy-sandbox-sdk").resolve(storeFile).toFile(),
                storePassword,
                keyPassword,
                keyAlias,
                "CN=Privacy Sandbox Sdk test",
                100
        )

        build.configuredExecutor().run(":privacy-sandbox-sdk:assemble")

        val privacySandboxSdkProject = build.privacySandboxSdk(":privacy-sandbox-sdk")

        val asbFile =
                privacySandboxSdkProject.outputsDir.resolve("asb/single/privacy-sandbox-sdk.asb")
        Zip(asbFile).use {
            assertThat(it.getEntry("/META-INF/MANIFEST.MF")).isNotNull()
            assertThat(it.getEntry("/META-INF/PRIVACYS.RSA")).isNotNull()
            assertThat(it.getEntry("/META-INF/PRIVACYS.SF")).isNotNull()
        }
    }

    @Test
    fun checkKsp() {
        val mySdkFile = "src/main/java/com/example/sdkImplA/MySdk.kt"
        val build = rule.build {
            androidLibrary(":sdk-impl-a") {
                // Invalid usage of @PrivacySandboxSdk as interface contains two methods with the same name.
                files.add(
                    mySdkFile,
                    //language=kotlin
                    """
                        package com.example.sdkImplA
                        import androidx.privacysandbox.tools.PrivacySandboxService
                        @PrivacySandboxService
                        public interface MySdk {
                            suspend fun doStuff(x: Int, y: Int): String
                            suspend fun doStuff(x: Int, y: Int)
                        }
                    """.trimIndent()
                )
            }
        }

        build.configuredExecutor().expectFailure().run(":sdk-impl-a:build")

        build.androidLibrary(":sdk-impl-a").files.update(mySdkFile).replaceWith(
            //language=kotlin
            """
                package com.example.sdkImplA
                import androidx.privacysandbox.tools.PrivacySandboxService
                @PrivacySandboxService
                public interface MySdk {
                    suspend fun doStuff(x: Int, y: Int): String
                }
            """.trimIndent()
        )

        build.configuredExecutor().run(":sdk-impl-a:build")

        val kspDir = build.androidLibrary(":sdk-impl-a").generatedDir.resolve("ksp")
        assertThat(kspDir.isDirectory()).isTrue()
    }

    @Test
    fun testNoServiceDefinedInModuleUsedBySdk() {
        rule.build.configuredExecutor()
            .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_REQUIRE_SERVICES, true)
            .expectFailure()
            .run(":example-app:assembleDebug")
            .also {
                assertThat(it.failureMessage).contains(
                        "Unable to proceed generating shim with no provided sdk descriptor entries in:")
            }

        rule.build.configuredExecutor()
            .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_REQUIRE_SERVICES, false)
            .run(":example-app:assembleDebug")
    }

    @Test
    fun testProguardRulesGeneration() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk") {
                android {
                    bundle {
                        compatSdkProviderClassName = null
                        sdkProviderClassName = null
                    }
                }
            }
        }

        build.configuredExecutor().run(":privacy-sandbox-sdk:generatePrivacySandboxProguardRules")
    }

    @Test
    fun testTargetSdkVersion() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk") {
                android {
                    targetSdk = 34
                }
            }
        }

        build.configuredExecutor().run(":privacy-sandbox-sdk:assemble")

        val sdkProject = build.privacySandboxSdk(":privacy-sandbox-sdk")
        val asbManifest = sdkProject.intermediatesDir
            .resolve("merged_manifest/single/mergeManifest/AndroidManifest.xml")
            .toFile()

        val manifestLines = asbManifest.readLines()
        assertThat(manifestLines).containsAtLeastElementsIn(
            listOf(
                "    <uses-sdk",
                "        android:minSdkVersion=\"23\"",
                "        android:targetSdkVersion=\"34\" />"
            )
        )
    }

    @Test
    fun testInvalidApiGeneratorDependenciesUsingExperimentalPropertiesInSdk() {
        val build = rule.build {
            androidApplication(":example-app") {
                android {
                    android {
                        experimentalProperties[ModulePropertyKey.Dependencies.ANDROID_PRIVACY_SANDBOX_SDK_API_GENERATOR.key] =
                            listOf("project.dependencies.create(\"com.not:existing-dependency:2.0\")")
                    }
                }
            }
            privacySandboxSdk(":privacy-sandbox-sdk") {
                android {
                    android {
                        experimentalProperties[ModulePropertyKey.Dependencies.ANDROID_PRIVACY_SANDBOX_SDK_API_GENERATOR.key] =
                            listOf("project.dependencies.create(\"com.not:existing-dependency:1.0\")")
                    }
                }
            }
        }
        build.configuredExecutor()
            .expectFailure()
            .run(":example-app:assemble")
            .assertErrorContains("Could not find com.not:existing-dependency:2.0.")
        build.configuredExecutor()
            .expectFailure()
            .run(":privacy-sandbox-sdk:assemble")
            .assertErrorContains("Could not find com.not:existing-dependency:1.0.")
    }

    // Used to simulate Androidx behavior of not publishing 'debug' build variants
    private class DisableDebugVariantsCallback : GenericCallback {

        override fun handleProject(project: Project) {
            val libraryAndroidComponentsExtension =
                project.extensions.findByType(LibraryAndroidComponentsExtension::class.java)
                    ?: error("Cannot find LibraryAndroidComponentsExtension")
            libraryAndroidComponentsExtension.apply {
                beforeVariants(selector().withBuildType("debug")) { variant ->
                    variant.enable = false
                }
            }
        }
    }

    private class ConfigureKmpCallback: GenericCallback {
        override fun handleProject(project: Project) {
            val kotlin = project.extensions.findByType(KotlinMultiplatformExtension::class.java)
                ?: error("Cannot find KotlinMultiplatformExtension")
            kotlin.apply {
                androidTarget()
                sourceSets.maybeCreate("androidMain")
                sourceSets.getByName("androidMain") {
                    it.dependencies {
                        compileOnly(project(":stub"))
                    }
                }
            }
        }
    }
}
