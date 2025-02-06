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

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox.privacySandboxSdkLibraryProject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.options.BooleanOption
import com.android.bundle.SdkBundleConfigProto
import com.android.bundle.SdkBundleConfigProto.SdkBundleConfig
import com.android.bundle.SdkBundleConfigProto.SdkDependencyType
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.TestInputsGenerator
import com.google.common.collect.ImmutableList
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

/*
 Test to cover complex SDK dependencies such as a 'mediator' SDK that depends on multiple other SDKs.
 */
class PrivacySandboxMediatorSdkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val rule: GradleRule = GradleRule.configure()
        .withMavenRepository {
            library(MavenRepoGenerator.Library("com.externaldep:externaljar:1",
                "jar",
                TestInputsGenerator.jarWithEmptyClasses(
                    ImmutableList.of("com/externaldep/externaljar/ExternalClass")
                )
            ))
        }
        .from {

            /*
            R-SDK = Required Privacy Sandbox Sdk
            O-SDK = Optional Privacy Sandbox Sdk
            LIB = Android Library
            APP = Android Application
            ┌───────────────────────────────────────────┐
            │               app (APP)                   │
            │                  ▲                        │
            │                  │                        │
            │     privacy-sandbox-sdk-mediator (SDK)    │
            │                  ▲                        │
            │                  │                        │
            │ privacy-sandbox-sdk-mediator-impl (LIB)   │
            │     ▲            ▲            ▲           │
            │     │            │            │           │
            │ sdk-a (R-SDK) sdk-b (O-SDK) lib (LIB)     │
            │     ▲                 ▲                   │
            │     │                 │                   │
            │ sdk-a-impl (LIB) sdk-b-impl (LIB)         │
            │                                           │
            └───────────────────────────────────────────┘
             */
            androidApplication(":app") {
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
                android {
                    namespace = "com.example.privacysandboxsdk.consumer"
                    defaultConfig {
                        minSdk = 14
                        versionCode = 1
                    }
                }
                files.add(
                    "src/main/java/com/example/privacysandboxsdk/consumer/Main.kt",
                    //language=kotlin
                    """
                        package com.example.privacysandboxsdk.consumer

                        class SdkAServiceImpl : com.example.sdkAImpl.SdkAService {
                            override suspend fun f1(p1: Int): Int {
                                return 0
                            }
                        }
                        class SdkBServiceImpl : com.example.sdkBImpl.SdkBService {
                            override suspend fun f1(p1: Int): Int {
                                return 0
                            }
                        }
                    """.trimIndent())
                dependencies {
                    implementation(project(":privacy-sandbox-sdk-mediator"))
                }
            }
            privacySandboxSdk(":privacy-sandbox-sdk-mediator") {
                android {
                    minSdk = 23
                    bundle {
                        applicationId = "com.example.privacysandboxsdk"
                        sdkProviderClassName = "androidx.privacysandbox.sdkruntime.provider.SandboxedSdkProviderAdapter"
                        compatSdkProviderClassName = "androidx.privacysandbox.sdkruntime.client.SdkSandboxManagerCompat"
                        setVersion(1, 2, 3)
                    }
                }
                dependencies {
                    include(project(":privacy-sandbox-sdk-mediator-impl"))
                    requiredSdk(project(":sdk-a"))
                    optionalSdk(project(":sdk-b"))
                }
            }
            privacySandboxSdkLibraryProject(":privacy-sandbox-sdk-mediator-impl") {
                android {
                    namespace = "com.example.sdkmediator"
                    defaultConfig.minSdk = 23
                }
                files.add(
                    "src/main/java/com/example/sdkmediator/Mediation.kt",
                    //language=kotlin
                    """
                        package com.example.sdkmediator

                        class SdkAServiceImpl : com.example.sdkAImpl.SdkAService {
                            override suspend fun f1(p1: Int): Int {
                                return 0
                            }
                        }
                        class SdkBServiceImpl : com.example.sdkBImpl.SdkBService {
                            override suspend fun f1(p1: Int): Int {
                                return 0
                            }
                        }
                    """.trimIndent())

                dependencies {
                    implementation(project(":sdk-a"))
                    implementation(project(":sdk-b"))
                    implementation("com.externaldep:externaljar:1")
                }
            }
            privacySandboxSdk(":sdk-a") {
                android {
                    minSdk = 23
                    bundle {
                        applicationId = "com.example.sdka"
                        sdkProviderClassName = "Test"
                        compatSdkProviderClassName = "Test"
                        setVersion(1, 2, 3)
                    }
                }

                dependencies {
                    include(project(":sdk-a-impl"))
                }
            }
            privacySandboxSdkLibraryProject(":sdk-a-impl") {
                android {
                    namespace = "com.example.sdkAImpl"
                    defaultConfig.minSdk = 23
                }
                files.add(
                    "src/main/kotlin/com/example/sdkAImpl/SdkAService.kt",
                    //language=kotlin
                    """
                        package com.example.sdkAImpl

                        import androidx.privacysandbox.tools.PrivacySandboxService

                        @PrivacySandboxService
                        interface SdkAService {
                            suspend fun f1(p1: Int): Int
                        }
                    """.trimIndent())
            }
            privacySandboxSdk(":sdk-b") {
                applyPlugin(PluginType.MAVEN_PUBLISH)
                android {
                    minSdk = 23
                    bundle {
                        applicationId = "com.example.sdkb"
                        sdkProviderClassName = "Test"
                        compatSdkProviderClassName = "Test"
                        setVersion(1, 2, 3)
                    }
                }

                pluginCallbacks += PublishingCallback::class.java

                dependencies {
                    include(project(":sdk-b-impl"))
                }
            }
            privacySandboxSdkLibraryProject(":sdk-b-impl") {
                android {
                    namespace = "com.example.sdkBImpl"
                    defaultConfig.minSdk = 23
                }
                files.add(
                    "src/main/kotlin/com/example/sdkBImpl/SdkBService.kt",
                    //language=kotlin
                    """
                        package com.example.sdkBImpl

                        import androidx.privacysandbox.tools.PrivacySandboxService

                        @PrivacySandboxService
                        interface SdkBService{
                            suspend fun f1(p1: Int): Int
                        }
                    """.trimIndent())
            }
            androidLibrary(":android-lib", createMinimumProject = false) {
                applyPlugin(PluginType.KOTLIN_ANDROID)
                android {
                    namespace = "com.example.androidlib"
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                    defaultConfig.minSdk = 23
                }
            }

            gradleProperties {
                add(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
                add(BooleanOption.USE_ANDROID_X, true)
            }
        }

    class PublishingCallback: GenericCallback {
        override fun handleProject(project: Project) {
            val publishing = project.extensions.findByType(PublishingExtension::class.java)
                ?: throw RuntimeException("Could not find extension of type PublishingExtension")

            publishing.apply {
                publications.create("debug", MavenPublication::class.java) {
                    it.groupId = "com.example"
                    it.artifactId = "sdkb"
                    it.version = "1.0.0"
                }
                repositories {
                    it.maven {
                        it.url = project.uri(project.layout.projectDirectory.dir("../additional_maven_repo"))
                    }
                }
            }
        }
    }

    private fun GradleBuild.configuredExecutor() = executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
            .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
            .withPerTestPrefsRoot(true)
            .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679

    @Test
    fun missingRequiredDependency() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk-mediator") {
                dependencies {
                    // :sdk-a is an SDK dependency of :privacy-sandbox-sdk-mediator-impl
                    remove("requiredSdk", project(":sdk-a"))
                }
            }
        }

        // Building the SDK should fail since configurations are invalid.
        val execution = build
            .configuredExecutor()
            .expectFailure()
            .run(":privacy-sandbox-sdk-mediator:validatePrivacySandboxSdkConfiguration")
        execution.assertErrorContains("project :sdk-a must also be defined in 'optionalSdk' or 'requiredSdk' configurations.")
    }

    @Test
    fun failWhenAddingDirectSdkDependencyInIncludes() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk-mediator") {
                dependencies {
                    remove("include", project(":privacy-sandbox-sdk-mediator-impl"))
                    include(project(":sdk-a"))
                    remove("requiredSdk", project(":sdk-a"))
                    remove("optionalSdk", project(":sdk-b"))
                }
            }

        }
        // Building the SDK should fail since configurations are invalid.
        val execution = build
            .configuredExecutor()
            .expectFailure()
            .run(":privacy-sandbox-sdk-mediator:validatePrivacySandboxSdkConfiguration")
        execution.assertErrorContains("project :sdk-a must be defined in 'optionalSdk' or 'requiredSdk' configurations only.")
    }

    @Test
    fun failWhenAddingAnSdkDependencyInIncludeAndRequired() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk-mediator") {
                dependencies {
                    include(project(":sdk-a"))
                }
            }
        }

        val execution = build
            .configuredExecutor()
            .expectFailure()
            .withArgument("--dry-run") // Skip execution.
            .run(":privacy-sandbox-sdk-mediator:validatePrivacySandboxSdkConfiguration")
        execution.assertErrorContains("'include' configuration can not contains dependencies found in 'requiredSdk' or 'optionalSdk'. " +
                "Recommended Action: Remove the following dependency from the 'include' configuration: sdk-a")
    }

    @Test
    fun failWhenAddingANonSdkDependencyToRequiredSdk() {
        val build = rule.build {
            privacySandboxSdk(":privacy-sandbox-sdk-mediator") {
                dependencies {
                    // Add a new requiredSdk dependency, that is not an SDK.
                    requiredSdk("com.externaldep:externaljar:1")
                }
            }
        }

        build
            .configuredExecutor()
            .expectFailure()
            .run(":privacy-sandbox-sdk-mediator:validatePrivacySandboxSdkConfiguration")
            .assertErrorContains(
                "com.externaldep:externaljar:1 is a not a privacy sandbox sdk."
            )

        build.privacySandboxSdk(":privacy-sandbox-sdk-mediator").reconfigure {
            dependencies {
                remove("requiredSdk", "com.externaldep:externaljar:1")
                requiredSdk(project(":android-lib"))
            }
        }

        build
            .configuredExecutor()
            .expectFailure()
            .run(":privacy-sandbox-sdk-mediator:validatePrivacySandboxSdkConfiguration")
            .assertErrorContains(
                "project :android-lib is a not a privacy sandbox sdk"
            )
    }

    @Test
    fun asbSdkBundleConfigProtoContainsCorrectSdkDependencyInfo() {
        val build = rule.build
        build.configuredExecutor().run(":privacy-sandbox-sdk-mediator:packagePrivacySandboxSdkBundle")

        val sdkMediatorProject = build.privacySandboxSdk(":privacy-sandbox-sdk-mediator")

        val asb =
            sdkMediatorProject.intermediatesDir.resolve("asb/single/packagePrivacySandboxSdkBundle/privacy-sandbox-sdk-mediator.asb")
        ZipFile(asb.toFile()).use { openAsar ->
            val sdkBundleConfig =
                    openAsar.getEntry("SdkBundleConfig.pb")
            val sdkMetadataBytes = openAsar.getInputStream(sdkBundleConfig).readBytes()
            val proto: SdkBundleConfigProto.SdkBundleConfig = sdkMetadataBytes.inputStream()
                    .buffered()
                    .use { input -> SdkBundleConfig.parseFrom(input) }
            assertThat(proto.sdkDependenciesCount).isEqualTo(2)
            val sdkBundlePackageNameMap =
                    proto.sdkDependenciesList.toList().associateBy { it.packageName }
            val sdkA = sdkBundlePackageNameMap.get("com.example.sdka")
            val sdkB = sdkBundlePackageNameMap.get("com.example.sdkb")
            val expectedSdkA = SdkBundleConfigProto.SdkBundle.newBuilder()
                    .setPackageName("com.example.sdka")
                    .setDependencyType(SdkDependencyType.SDK_DEPENDENCY_TYPE_REQUIRED)
                    .setVersionMajor(1)
                    .setVersionMinor(2)
                    .setBuildTimeVersionPatch(3)
                    .setCertificateDigest(sdkA?.certificateDigest)
                    .build()
            val expectedSdkB = SdkBundleConfigProto.SdkBundle.newBuilder()
                    .setPackageName("com.example.sdkb")
                    .setDependencyType(SdkDependencyType.SDK_DEPENDENCY_TYPE_OPTIONAL)
                    .setVersionMajor(1)
                    .setVersionMinor(2)
                    .setBuildTimeVersionPatch(3)
                    .setCertificateDigest(sdkB?.certificateDigest)
                    .build()
            assertThat(sdkA).isEqualTo(expectedSdkA)
            assertThat(sdkB).isEqualTo(expectedSdkB)
        }
    }
}
