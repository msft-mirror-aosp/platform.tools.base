/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.bundle

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.project.AabSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.bundle.Config
import com.android.ide.common.signing.KeystoreHelper
import com.android.tools.build.bundletool.model.AndroidManifest.MODULE_TYPE_AI_VALUE
import com.android.tools.build.bundletool.model.BundleModule
import com.android.tools.build.bundletool.model.BundleModuleName
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Optional

private const val APP_ID = "com.test.assetpack.bundle"
private const val VERSION_TAG = "20210319.patch1"
private val VERSION_CODES = listOf(10, 20, 99034)

private val assetFileOneContent = "This is an asset file from asset pack one."
private val assetFileTwoContent = "This is an asset file from asset pack two."
private val onDemandAiPackContent = "This is a state-of-the-art machine learning model."
private val deviceGroupConfig = "This is a device group config."

class AssetPackBundleTest {
    @get:Rule
    val rule = GradleRule.from {
        assetPackBundle(":assetPackBundle") {
            bundle {
                applicationId = APP_ID
                compileSdk = GradleTestProject.DEFAULT_COMPILE_SDK_VERSION.toInt()

                versionTag = VERSION_TAG
                versionCodes += VERSION_CODES
                assetPacks += listOf(":assetPackOne", ":assetPackTwo", ":onDemandAiPack")

                deviceTier {
                    enableSplit = true
                    defaultTier = "medium"
                }

                countrySet {
                    enableSplit = true
                    defaultSet = "latam"
                }

                aiModelVersion {
                    enableSplit = true
                }
            }
            files.add("src/main/device_group_config.json", deviceGroupConfig)
            pluginCallback = MyCallback::class.java
        }
        assetPack(":assetPackOne") {
            assetPack {
                packName.set("assetPackOne")
                dynamicDelivery {
                    deliveryType.set("on-demand")
                    instantDeliveryType.set("on-demand")
                }
            }
            files.add("src/main/assets/assetFileOne.txt", assetFileOneContent)
        }
        assetPack(":assetPackTwo") {
            assetPack {
                packName.set("assetPackTwo")
                dynamicDelivery {
                    deliveryType.set("fast-follow")
                }
            }
            files.add("src/main/assets/assetFileTwo.txt", assetFileTwoContent)
        }
        aiPack(":onDemandAiPack") {
            aiPack {
                packName.set("onDemandAiPack")
                dynamicDelivery {
                    deliveryType.set("on-demand")
                }
            }
            files.add("src/main/assets/customModel.tflite", onDemandAiPackContent)
        }
    }

    class MyCallback: GenericCallback {
        override fun handleProject(project: Project) {
            project.extensions.getByType(ExtraPropertiesExtension::class.java).apply {
                set("android_experimental_bundle_deviceGroup_enableSplit", true)
                set("android_experimental_bundle_deviceGroup_defaultGroup", "highRam")
                set("android_experimental_bundle_deviceGroupConfig", project.file("src/main/device_group_config.json"))
            }
        }
    }

    @get:Rule
    val tmpFile = TemporaryFolder()

    @Test
    fun `should build asset pack bundle successfully`() {
        val build = rule.build
        build.executor.run(":assetPackBundle:bundle")

        build.assetPackBundle(":assetPackBundle").assertAab(AabSelector.NO_BUILD_TYPE) {
            containsFileWithContent("assetPackOne/assets/assetFileOne.txt", assetFileOneContent)
            containsFileWithContent("assetPackTwo/assets/assetFileTwo.txt", assetFileTwoContent)
            containsFileWithContent(
                "onDemandAiPack/assets/customModel.tflite",
                onDemandAiPackContent
            )
            containsFileWithContent(
                "BUNDLE-METADATA/com.android.tools.build.bundletool/DeviceGroupConfig.json",
                deviceGroupConfig
            )
            contains("assetPackOne/manifest/AndroidManifest.xml")
            contains("assetPackTwo/manifest/AndroidManifest.xml")
            contains("onDemandAiPack/manifest/AndroidManifest.xml")
            contains("BundleConfig.pb")
            doesNotContain("META-INF/KEY0.SF")
            doesNotContain("META-INF/KEY0.RSA")
        }

        build.assetPackBundle(":assetPackBundle").withAppBundle(AabSelector.NO_BUILD_TYPE) {
            assertThat(bundleConfig.type).isEqualTo(
                Config.BundleConfig.BundleType.ASSET_ONLY
            )

            val splitsConfigBuilder = Config.SplitsConfig.newBuilder()
            splitsConfigBuilder
                .addSplitDimensionBuilder()
                .setValue(Config.SplitDimension.Value.DEVICE_TIER)
                .suffixStrippingBuilder
                .setEnabled(true)
                .setDefaultSuffix("medium")
            splitsConfigBuilder
                .addSplitDimensionBuilder()
                .setValue(Config.SplitDimension.Value.DEVICE_GROUP)
                .suffixStrippingBuilder
                .setEnabled(true)
                .setDefaultSuffix("highRam")
            splitsConfigBuilder
                .addSplitDimensionBuilder()
                .setValue(Config.SplitDimension.Value.COUNTRY_SET)
                .suffixStrippingBuilder
                .setEnabled(true)
                .setDefaultSuffix("latam")
            splitsConfigBuilder
                .addSplitDimensionBuilder()
                .setValue(Config.SplitDimension.Value.AI_MODEL_VERSION)
                .suffixStrippingBuilder
                .setEnabled(true)
                .setDefaultSuffix("")
            assertThat(bundleConfig.optimizations.splitsConfig)
                .isEqualTo(splitsConfigBuilder.build())

            assertThat(bundleConfig.assetModulesConfig).isEqualTo(
                Config.AssetModulesConfig.newBuilder()
                    .setAssetVersionTag(VERSION_TAG)
                    .addAllAppVersion(VERSION_CODES.map { it.toLong() })
                    .build()
            )

            val moduleNames = assetModules.keys.map { it.name }
            assertThat(moduleNames).containsExactly(
                "assetPackOne",
                "assetPackTwo",
                "onDemandAiPack"
            )

            val assetPackOneManifest =
                assetModules[BundleModuleName.create("assetPackOne")]!!.androidManifest
            assertThat(assetPackOneManifest.moduleType).isEqualTo(
                BundleModule.ModuleType.ASSET_MODULE
            )
            assertThat(assetPackOneManifest.packageName).isEqualTo(APP_ID)
            assertThat(assetPackOneManifest.manifestDeliveryElement.get().hasOnDemandElement())
                .isTrue()

            val assetPackTwoManifest =
                assetModules[BundleModuleName.create("assetPackTwo")]!!.androidManifest
            assertThat(assetPackTwoManifest.moduleType).isEqualTo(
                BundleModule.ModuleType.ASSET_MODULE
            )
            assertThat(assetPackTwoManifest.packageName).isEqualTo(APP_ID)
            assertThat(assetPackTwoManifest.manifestDeliveryElement.get().hasFastFollowElement())
                .isTrue()

            val onDemandAiPackManifest =
                assetModules[BundleModuleName.create("onDemandAiPack")]!!.androidManifest
            assertThat(onDemandAiPackManifest.moduleType).isEqualTo(
                BundleModule.ModuleType.ASSET_MODULE
            )
            assertThat(onDemandAiPackManifest.optionalModuleTypeAttributeValue).isEqualTo(
                Optional.of(MODULE_TYPE_AI_VALUE)
            )
            assertThat(onDemandAiPackManifest.packageName).isEqualTo(APP_ID)
            assertThat(
                onDemandAiPackManifest.manifestDeliveryElement.get()
                    .hasOnDemandElement()
            )
                .isTrue()
        }
    }

    @Test
    fun `should build signed asset pack bundle successfully if signing config is provided`() {
        val storePassword = "storePassword"
        val keyPassword = "keyPassword"
        val keyAlias = "key0"

        val keyStoreFile = tmpFile.root.resolve("keystore")
        KeystoreHelper.createNewStore(
            "jks",
            keyStoreFile,
            storePassword,
            keyPassword,
            keyAlias,
            "CN=Bundle signing test",
            100
        )

        val build = rule.build {
            assetPackBundle(":assetPackBundle") {
                bundle {
                    signingConfig {
                        storeFile = keyStoreFile
                        this.storePassword = storePassword
                        this.keyAlias = keyAlias
                        this.keyPassword = keyPassword
                    }
                }
            }
        }

        build.executor.run(":assetPackBundle:bundle")

        build.assetPackBundle(":assetPackBundle").assertAab(AabSelector.NO_BUILD_TYPE_SIGNED) {
            containsFileWithContent("assetPackOne/assets/assetFileOne.txt", assetFileOneContent)
            containsFileWithContent("assetPackTwo/assets/assetFileTwo.txt", assetFileTwoContent)
            contains("assetPackOne/manifest/AndroidManifest.xml")
            contains("assetPackTwo/manifest/AndroidManifest.xml")
            contains("BundleConfig.pb")
            contains("META-INF/KEY0.SF")
            contains("META-INF/KEY0.RSA")
        }
    }

    @Test
    fun `should fail if asset pack bundle is misconfigured`() {
        val build = rule.build {
            assetPackBundle(":assetPackBundle") {
                bundle {
                    applicationId = ""
                    versionTag = ""
                    versionCodes.clear()
                    assetPacks.clear()
                }
            }
        }

        val failure = build.executor.expectFailure().run(":assetPackBundle:bundle")
        failure.stdout.use {
            assertThat(it).contains("'applicationId' must be specified for asset pack bundle.")
            assertThat(it).contains("'versionTag' must be specified for asset pack bundle.")
            assertThat(it).contains("Asset pack bundle must target at least one version code.")
            assertThat(it).contains("Asset pack bundle must contain at least one asset pack.")
        }
    }

    @Test
    fun `should fail if requested asset pack is not available in project`() {
        val build = rule.build {
            assetPackBundle(":assetPackBundle") {
                bundle {
                    assetPacks += ":notAvailable"
                }
            }
        }

        val failure = build.executor.expectFailure().run(":assetPackBundle:bundle")
        failure.stderr.use {
            assertThat(it)
                .contains("Unable to find matching projects for Asset Packs: [:notAvailable]")
        }
    }

    @Test
    fun `should fail if requested compileSdk is not available`() {
        val build = rule.build {
            assetPackBundle(":assetPackBundle") {
                bundle {
                     compileSdk = 128
                }
            }
        }

        val failure = build.executor.expectFailure().run(":assetPackBundle:bundle")
        failure.stderr.use {
            assertThat(it)
                .contains(
                    "Could not determine the dependencies of task " +
                            "':assetPackBundle:linkManifestForAssetPacks'"
                )
            assertThat(it).contains("'android-128'")
        }
    }

    @Test
    fun `should fail if install-time asset pack is included in asset pack bundle`() {
        val build = rule.build {
            assetPack(":assetPackTwo") {
                assetPack {
                    dynamicDelivery.deliveryType.set("install-time")

                }
            }
        }

        val failure = build.executor.expectFailure().run(":assetPackBundle:bundle")
        failure.stderr.use {
            assertThat(it)
                .contains("bundle contains an install-time asset module 'assetPackTwo'")
        }
    }

    @Test
    fun `should fail if keystore file is signing config is invalid`() {
        val build = rule.build
        val bundle = build.assetPackBundle(":assetPackBundle")

        val keystore = bundle.resolve("keystore.jks")

        bundle.reconfigure {
            bundle {
                signingConfig {
                    storeFile = keystore.toFile()
                    storePassword = ""
                    keyAlias = "key"
                    keyPassword = ""
                }
            }
        }

        val failure = build.executor.expectFailure().run(":assetPackBundle:bundle")
        failure.stderr.use {
            assertThat(it)
                .contains("Keystore file '${keystore}' not found")
        }
    }
}
