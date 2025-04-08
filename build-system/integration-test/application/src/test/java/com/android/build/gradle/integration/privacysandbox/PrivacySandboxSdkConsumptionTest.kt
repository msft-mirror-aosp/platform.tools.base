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

package com.android.build.gradle.integration.privacysandbox

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.ProfileCapturer
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox.privacySandboxSampleProject
import com.android.build.gradle.integration.common.output.ApkSubject
import com.android.build.gradle.integration.common.output.ClassesSubject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.internal.LoggerWrapper
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import com.android.builder.model.v2.ide.SyncIssue
import com.android.ide.common.build.GenericBuiltArtifactsLoader
import com.android.sdklib.SdkVersionInfo
import com.android.utils.StdLogger
import com.google.protobuf.TextFormat
import com.google.wireless.android.sdk.stats.GradleBuildProject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.io.path.isRegularFile

/** Integration tests for the privacy sandbox SDK for consumption */
class PrivacySandboxSdkConsumptionTest {

    @get:Rule
    val rule = privacySandboxSampleProject()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun GradleBuild.configuredExecutor() = executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
            .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
            .withPerTestPrefsRoot(true)
            .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679

    private fun GradleBuild.configuredModelBuilder() = modelBuilder
            .withFailOnWarning(false) // kgp uses deprecated api WrapUtil
            .withPerTestPrefsRoot(true)
            .with(BooleanOption.ENABLE_PROFILE_JSON, true) // Regression test for b/237278679
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)

    @Test
    fun testConsumptionViaBundle() {
        // TODO(b/235469089) expand this to verify installation also

        val build = rule.build {
            //Add service to sdk-impl-a
            androidLibrary(":sdk-impl-a") {
                files.add(
                    "src/main/java/com/example/sdkImplA/MySdk.kt",
                    //language=kotlin
                    """
                        package com.example.sdkImplA
                        import androidx.privacysandbox.tools.PrivacySandboxService
                        @PrivacySandboxService
                        public interface MySdk {
                         suspend fun foo(bar: Int): String
                        }
                    """.trimIndent()
                )
            }

        }

        // Check building the SDK itself
        build.configuredExecutor().run(":example-app:buildPrivacySandboxSdkApksForDebug")
        val ideModelFile = build
            .androidApplication(":example-app")
            .resolve(InternalArtifactType.EXTRACTED_APKS_FROM_PRIVACY_SANDBOX_SDKs_IDE_MODEL)
            .resolve("debug/buildPrivacySandboxSdkApksForDebug/ide_model.json")
            .toFile()
        val privacySandboxSdkApk = GenericBuiltArtifactsLoader.loadListFromFile(ideModelFile,
            LoggerWrapper.getLogger(PrivacySandboxSdkConsumptionTest::class.java))
            .single { it.applicationId == "com.example.privacysandboxsdk_10002" }
            .elements.single().outputFile

        ApkSubject.assertThat(File(privacySandboxSdkApk)) {
            classes().containsAtLeast(
                SDK_IMPL_A_CLASSNAME,
                "com/example/androidlib/Example",
                "com/example/androidlib/R",
                "com/example/privacysandboxsdk/RPackage",
                "com/example/sdkImplA/R\$string",
                "com/example/sdkImplA/R",
                "com/externaldep/externaljar/ExternalClass",
            )
        }

        // Check building the bundle to deploy to UpsideDownCake
        val apkSelectConfig = temporaryFolder.newFile("apkSelectConfig.json")
        apkSelectConfig.writeText(
            """{"sdk_version":$COMPILE_SDK_VERSION,"sdk_runtime":{"supported":"true"},"screen_density":420,"supported_abis":["x86_64","arm64-v8a"],"supported_locales":["en"]}""")

        build.configuredExecutor()
                .with(StringOption.IDE_APK_SELECT_CONFIG, apkSelectConfig.absolutePath)
                .run(":example-app:extractApksFromBundleForDebug")

        val extractedApks = build.androidApplication(":example-app")
                .intermediatesDir.resolve("extracted_apks/debug/extractApksFromBundleForDebug")
        val baseMaster2Apk = extractedApks.resolve("base-master_2.apk")
        val baseMaster3Apk = extractedApks.resolve("base-master_3.apk")

        ApkSubject.assertThat(baseMaster2Apk) {
            doesNotExist()
        }

        // action to validate that com/example/sdkImplA/Example does not exist
        val sdkImplADoesNotExist: (ClassesSubject) -> Unit = {
            it.subPackage("com/example/sdkImplA").containsExactly(
                "ICancellationSignal$",
                "IMySdk$",
                "IStringTransactionCallback$",
                "MySdk",
                "MySdkClientProxy$",
                "MySdkFactory",
                "ParcelableStackFrame$",
                "PrivacySandboxCancellationException",
                "PrivacySandboxException",
                "PrivacySandboxThrowableParcel$",
                "PrivacySandboxThrowableParcelConverter",
                "TransportCancellationCallback",
            )
        }

        // Expect the first assignment of certDigest to be the same for all modules.
        var certDigest: String
        ApkSubject.assertThat(baseMaster3Apk) {
            classes {
                contains("com/example/privacysandboxsdk/consumer/R")
                // validate com/example/sdkImplA/Example does not exist
                sdkImplADoesNotExist(this)
            }

            manifestAsNodes().node("manifest").apply {
                node("application")
                    .nodeByNameAndAttribute("uses-sdk-library", "com.example.privacysandboxsdk")
                    .apply {
                        certDigest = getAttributeValue("http://schemas.android.com/apk/res/android:certDigest")
                        containsExactlyAttributesAndValues(
                            "http://schemas.android.com/apk/res/android:name=\"com.example.privacysandboxsdk\"",
                            "http://schemas.android.com/apk/res/android:certDigest=$certDigest",
                            "http://schemas.android.com/apk/res/android:versionMajor=10002"
                        )
                    }

                // we want to validate that the internet permission is not present.
                // validate the number of permissions, and then verify each permission to
                // not be internet
                containsExactlyNodes(
                    "uses-sdk",
                    "uses-permission",
                    "uses-permission",
                    "uses-permission",
                    "application"
                )
                nodeByNameAndAttribute("uses-permission", "android.permission.WRITE_EXTERNAL_STORAGE")
                nodeByNameAndAttribute("uses-permission", "android.permission.READ_PHONE_STATE")
                nodeByNameAndAttribute("uses-permission", "android.permission.READ_EXTERNAL_STORAGE")
            }

            // TODO fix this!
            manifest().doesNotContain(FOREGROUND_SERVICE)
        }

        // Check building the bundle to deploy to a non-privacy sandbox device:
        apkSelectConfig.writeText(
                """{"sdk_version":32,"codename":"Tiramisu","screen_density":420,"supported_abis":["x86_64","arm64-v8a"],"supported_locales":["en"]}""")

        build.configuredExecutor()
                .with(StringOption.IDE_APK_SELECT_CONFIG, apkSelectConfig.absolutePath)
                .run(":example-app:extractApksFromBundleForDebug")

        ApkSubject.assertThat(baseMaster2Apk) {
            classes {
                contains("com/example/privacysandboxsdk/consumer/R")
                classDefinition("com/example/privacysandboxsdk/RPackage").apply {
                    fields().containsExactly("packageId")
                    fieldByName("packageId").isEqualTo("0x7e000000")
                }
                // validate com/example/sdkImplA/Example does not exist
                sdkImplADoesNotExist(this)
            }

            // validate there isn't a uses-sdk-library
            manifestAsNodes()
                .node("manifest")
                .node("application")
                .containsExactlyNodes("meta-data")
        }

        ApkSubject.assertThat(baseMaster3Apk) {
            doesNotExist()
        }
    }

    @Test
    fun testConsumptionViaApk() {
        val build = rule.build {
            //Add service to sdk-impl-a
            androidLibrary(":sdk-impl-a") {
                files.add(
                    "src/main/java/com/example/sdkImplA/MySdk.kt",
                    //language=kotlin
                    """
                        package com.example.sdkImplA
                        import androidx.privacysandbox.tools.PrivacySandboxService
                        @PrivacySandboxService
                        public interface MySdk {
                            suspend fun foo(bar: Int): String
                        }
                    """.trimIndent()
                )

            }
        }
        val model = build.configuredModelBuilder()
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, true)
            .fetchModels().container.getProject(":example-app")

        val exampleAppDebug = model.androidProject!!.variants.single { it.name == "debug" }
        val privacySandboxSdkInfo = exampleAppDebug.mainArtifact.privacySandboxSdkInfo!!

        val profiles = ProfileCapturer(build).capture {
            exampleAppDebug.mainArtifact.assembleTaskName?.let {
                build.configuredExecutor().with(BooleanOption.PRIVACY_SANDBOX_SDK_REQUIRE_SERVICES, false)
                    .run(it,
                        privacySandboxSdkInfo.task,
                        privacySandboxSdkInfo.taskLegacy,
                        privacySandboxSdkInfo.additionalApkSplitTask)
            }
        }

        val actualMetricsMetadata = profiles.single().projectList.single { it.androidPlugin == GradleBuildProject.PluginType.APPLICATION }.variantList.single { it.isDebug }.privacySandboxDependenciesInfo
        assertThat(TextFormat.printer().printToString(actualMetricsMetadata).trim()).isEqualTo("""
            sdk {
              package_name: "com.example.privacysandboxsdk"
              version_major: 1
              version_minor: 2
              build_time_version_patch: 3
            }
            sdk {
              package_name: "com.example.privacysandboxsdkb"
              version_major: 1
              version_minor: 2
              build_time_version_patch: 3
            }
            """.trimIndent())

        build.androidApplication(":example-app").assertApk(ApkSelector.DEBUG) {
            // This asset (RUNTIME_ENABLED_SDK_TABLE) must only be packaged in non-sandbox capable
            // devices, otherwise it may cause runtime exceptions on supported privacy
            // sandbox platforms.
            assets().isEmpty()

            manifest().apply {
                contains(INTERNET_PERMISSION)
                doesNotContain(FOREGROUND_SERVICE)
                doesNotContain(USES_SDK_LIBRARY_MANIFEST_ELEMENT)
            }
        }

        val usesSdkLibrarySplitPath =
                GenericBuiltArtifactsLoader.loadListFromFile(privacySandboxSdkInfo.additionalApkSplitFile,
                        LoggerWrapper.getLogger(PrivacySandboxSdkConsumptionTest::class.java))
                        .elementAt(0).elements.first().outputFile

        ApkSubject.assertThat(File(usesSdkLibrarySplitPath)) {
            manifestAsNodes()
                .node("manifest")
                .node("application")
                .nodeByNameAndAttribute("uses-sdk-library", "com.example.privacysandboxsdk")
                .apply {
                    containsAttributeAndValue(
                        "http://schemas.android.com/apk/res/android:versionMajor",
                        "10002"
                    )
                    containsAttribute("http://schemas.android.com/apk/res/android:certDigest")
                }

            // validate RuntimeEnabledSdkTable.xml is not present.
            assets().isEmpty()
        }

        val sdkApks =
                GenericBuiltArtifactsLoader.loadListFromFile(privacySandboxSdkInfo.outputListingFile,
                        StdLogger(StdLogger.Level.INFO))
        ApkSubject.assertThat(File(sdkApks.single { it.applicationId.startsWith("com.example.privacysandboxsdk_") }.elements.single().outputFile)) {
            classes().contains(SDK_IMPL_A_CLASSNAME)
            // validate RuntimeEnabledSdkTable.xml is not present.
            assets().containsExactly(
                "asset_from_sdkImplA.txt",
                "SandboxedSdkProviderCompatClassName.txt"
            )
        }

        val compatSplits =
                GenericBuiltArtifactsLoader.loadFromFile(privacySandboxSdkInfo.outputListingLegacyFile,
                        StdLogger(StdLogger.Level.INFO))!!

        assertThat(compatSplits.elements).named("compat splits elements").hasSize(3)
        ApkSubject.assertThat(File(compatSplits.elements.single { it.outputFile.endsWith(
            INJECTED_PRIVACY_SANDBOX_COMPAT_SUFFIX) }.outputFile)) {
            assets().contains(RUNTIME_ENABLED_SDK_TABLE)

            // validate no uses-sdk-library node
            manifestAsNodes()
                .node("manifest")
                .node("application")
                .hasNoNodes()
        }
    }

    @Test
    fun producesApkSplitsFromSdks() {
        val build = rule.build

        // For API S-, ensure that APKs are produced for each SDK that the app requires.
        val apkSelectConfig = temporaryFolder.newFile("apkSelectConfig.json")
        apkSelectConfig.writeText(
                """{"sdk_version":28,"codename":"Pie","screen_density":420,"supported_abis":["x86_64","arm64-v8a"],"supported_locales":["en"]}""")

        build.configuredExecutor()
            .withFailOnWarning(false)
            .with(StringOption.IDE_APK_SELECT_CONFIG, apkSelectConfig.absolutePath)
            .run(":example-app:extractApksFromSdkSplitsForDebug")

        val extractedSdkApksDir = build
            .androidApplication(":example-app")
            .resolve(InternalArtifactType.EXTRACTED_SDK_APKS)
            .toFile()
        val extractedSdkApks = extractedSdkApksDir
                .walkTopDown()
                .filter { it.isFile }
                .filter { it.extension == "apk" }
                .toList()
        assertThat(extractedSdkApks.map { it.name })
                .containsExactly(
                        "example-app-debug-injected-privacy-sandbox-compat.apk",
                        "comexampleprivacysandboxsdkb-master.apk",
                        "comexampleprivacysandboxsdk-master.apk"
                )

        ApkSubject.assertThat(extractedSdkApks.single { it.name == "comexampleprivacysandboxsdk-master.apk" }) {
            manifest().isEqualTo(
                """
                    N: android=http://schemas.android.com/apk/res/android
                      E: manifest
                        A: http://schemas.android.com/apk/res/android:versionCode=4
                        A: http://schemas.android.com/apk/res/android:isFeatureSplit=true
                        A: http://schemas.android.com/apk/res/android:compileSdkVersion=$COMPILE_SDK_VERSION
                        A: http://schemas.android.com/apk/res/android:compileSdkVersionCodename="$COMPILE_SDK_VERSION_CODENAME"
                        A: package="com.example.privacysandboxsdk.consumer"
                        A: platformBuildVersionCode=$COMPILE_SDK_VERSION
                        A: platformBuildVersionName=$COMPILE_SDK_VERSION_CODENAME
                        A: split="comexampleprivacysandboxsdk"
                          E: uses-permission
                            A: http://schemas.android.com/apk/res/android:name="android.permission.INTERNET"
                          E: uses-permission
                            A: http://schemas.android.com/apk/res/android:name="com.example.privacysandboxsdkb.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                          E: uses-permission
                            A: http://schemas.android.com/apk/res/android:name="com.example.privacysandboxsdk.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                          E: application
                            A: http://schemas.android.com/apk/res/android:hasCode=false
                            A: http://schemas.android.com/apk/res/android:appComponentFactory="androidx.core.app.CoreComponentFactory"
                              E: meta-data
                                A: http://schemas.android.com/apk/res/android:name="shadow.bundletool.com.android.vending.sdk.patch.version.comexampleprivacysandboxsdk"
                                A: http://schemas.android.com/apk/res/android:value=3
                          E: uses-sdk
                            A: http://schemas.android.com/apk/res/android:minSdkVersion=23
                          E: http://schemas.android.com/apk/distribution:module
                              E: http://schemas.android.com/apk/distribution:delivery
                                  E: http://schemas.android.com/apk/distribution:install-time
                                      E: http://schemas.android.com/apk/distribution:removable
                                        A: http://schemas.android.com/apk/distribution:value=true
                              E: http://schemas.android.com/apk/distribution:fusing
                                A: http://schemas.android.com/apk/distribution:include=true
                """.trimIndent()
            )

            assets().containsAtLeast(
                "asset_from_sdkImplA.txt",
                "RuntimeEnabledSdk-com.example.privacysandboxsdk/CompatSdkConfig.xml",
            )

            javaResources().containsExactly(
                "META-INF/MANIFEST.MF",
                "META-INF/BNDLTOOL.RSA",
                "META-INF/BNDLTOOL.SF",
            )
        }

        ApkSubject.assertThat(extractedSdkApks.single { it.name == "example-app-debug-injected-privacy-sandbox-compat.apk" }) {
            manifestAsNodes().node("manifest").apply {
                containsAtLeastAttributesAndValues(
                    "http://schemas.android.com/apk/res/android:isFeatureSplit=true",
                    "split=\"exampleappdebuginjectedprivacysandboxcompat\""
                )
                node("application").containsAttributeAndValue(
                    "http://schemas.android.com/apk/res/android:hasCode",
                    "false"
                )
            }

            javaResources().containsExactly(
                "META-INF/CERT.RSA",
                "META-INF/CERT.SF",
                "META-INF/MANIFEST.MF",
            )

            assets().contains(RUNTIME_ENABLED_SDK_TABLE)
        }
    }

    @Test
    fun testBuildFailureWhenPublicationNotEnabled() {
        val buildFailsPublicationNotEnabled = rule.build.configuredExecutor()
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_PLUGIN_SUPPORT, false)
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, false)
            .expectFailure()
            .run(":privacy-sandbox-sdk:assemble")
        buildFailsPublicationNotEnabled.stderr.use {
            val expectedContents = listOf(
                "> Failed to apply plugin 'com.android.internal.privacy-sandbox-sdk'.",
                "> Privacy Sandbox SDK Plugin support must be explicitly enabled."
            )
            expectedContents.forEach { line ->
                ScannerSubject.assertThat(it).contains(line)
            }
        }
    }

    @Test
    fun testPublicationAndConsumptionCanBeToggledSeparately() {
        val build = rule.build

        build.configuredExecutor().run(":privacy-sandbox-sdk:assemble")
        val sdkProject = build.privacySandboxSdk(":privacy-sandbox-sdk")
        assertThat(
            sdkProject.outputsDir.resolve("asb/single/privacy-sandbox-sdk.asb").isRegularFile()
        ).isTrue()

        val buildFailsConsumptionNotEnabled = build.configuredExecutor()
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_PLUGIN_SUPPORT, true)
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, false)
            .with(BooleanOption.PRIVACY_SANDBOX_SDK_REQUIRE_SERVICES, false)
            .expectFailure()
            .run(":example-app:assemble")
        assertThat(buildFailsConsumptionNotEnabled.failureMessage).isEqualTo(
            """2 issues were found when checking AAR metadata:

  1.  Dependency :privacy-sandbox-sdk is an Android Privacy Sandbox SDK library, and needs
      Privacy Sandbox support to be enabled in projects that depend on it.

      Recommended action: Enable privacy sandbox consumption in this project by setting
          android {
              privacySandbox {
                  enable = true
              }
          }
      in this project's build.gradle

  2.  Dependency :privacy-sandbox-sdk-b is an Android Privacy Sandbox SDK library, and needs
      Privacy Sandbox support to be enabled in projects that depend on it.

      Recommended action: Enable privacy sandbox consumption in this project by setting
          android {
              privacySandbox {
                  enable = true
              }
          }
      in this project's build.gradle"""
        )
        // Other tests verify behaviour with publication and consumption enabled.
    }

    companion object {
        private const val SDK_IMPL_A_CLASSNAME = "com/example/sdkImplA/Example"
        private const val USES_SDK_LIBRARY_MANIFEST_ELEMENT = "uses-sdk-library"
        private const val INTERNET_PERMISSION =
            "A: http://schemas.android.com/apk/res/android:name=\"android.permission.INTERNET\""
        private const val FOREGROUND_SERVICE = "FOREGROUND_SERVICE"
        private const val INJECTED_PRIVACY_SANDBOX_COMPAT_SUFFIX =
            "-injected-privacy-sandbox-compat.apk"
        private const val RUNTIME_ENABLED_SDK_TABLE = "RuntimeEnabledSdkTable.xml"
        private const val COMPILE_SDK_VERSION = DEFAULT_COMPILE_SDK_VERSION
        private val COMPILE_SDK_VERSION_CODENAME: String =
            COMPILE_SDK_VERSION.toPlatformBuildVersionName()

        /**
         * For any given SDK integer, prepare expected 'platformBuildVersionName' value.
         * Drop trailing zero, but do not change anything should the version name include
         * digits or multiple periods.
         */
        private fun Int.toPlatformBuildVersionName(): String {
            val versionName = SdkVersionInfo.getReleaseVersionString(this)
            return when {
                versionName.any { it.isLetter() } || versionName.count { it == '.' } > 1 -> versionName
                versionName.endsWith(".0") -> versionName.substringBefore(".")
                else -> versionName
            }
        }
    }
}
