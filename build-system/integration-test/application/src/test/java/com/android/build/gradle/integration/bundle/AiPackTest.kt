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

package com.android.build.gradle.integration.bundle

import com.android.build.gradle.integration.common.fixture.project.BundleSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.bundle.Config
import com.android.tools.build.bundletool.model.AndroidManifest.MODULE_TYPE_AI_VALUE
import com.android.tools.build.bundletool.model.AppBundle
import com.android.tools.build.bundletool.model.BundleModule
import com.android.tools.build.bundletool.model.BundleModuleName
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import java.util.Optional
import java.util.zip.ZipFile

class AiPackTest {
    private val packageName = "com.example.aipacktestapp"

    @get:Rule
    val rule = GradleRule.from {
        androidApplication(":app") {
            android {
                namespace = packageName
                assetPacks += listOf(
                    ":customModelInstallTime",
                    ":customModelFastFollow",
                    ":modelAdaptationOnDemand"
                )
                bundle {
                    aiModelVersion {
                        enableSplit = true
                        defaultVersion = "1"
                    }
                }
            }
            HelloWorldAndroid.setupJava(files)
        }

        androidAiPack(":customModelInstallTime") {
            aiPack {
                packName.set("customModelInstallTime")
                dynamicDelivery {
                    deliveryType.set("install-time")
                }
            }
            files {
                add(
                    "src/main/assets/customModel.tflite",
                    """This is a custom model delivered at install time."""
                )
            }
        }

        androidAiPack(":customModelFastFollow") {
            aiPack {
                packName.set("customModelFastFollow")
                dynamicDelivery {
                    deliveryType.set("fast-follow")
                }
            }
            files {
                add(
                    "src/main/assets/customModel.jax",
                    """This is a custom model delivered after install time."""                )
            }
        }

        androidAiPack(":modelAdaptationOnDemand") {
            aiPack {
                packName.set("modelAdaptationOnDemand")
                dynamicDelivery {
                    deliveryType.set("on-demand")
                }
                modelDependency {
                    aiModelPackageName.set("com.foundation.app")
                    aiModelName.set("com.foundation.llm")
                }
            }
            files {
                add(
                    "src/main/assets/adaptation.lora",
                    """This is an adaptation file delivered on-demand."""
                )
            }
        }
    }

    @Test
    fun buildDebugBundle() {
        val build = rule.build

        build.executor.run(":app:bundleDebug")

        val app = build.androidApplication(":app")
        app.assertBundle(BundleSelector.DEBUG) {
            exists()
            contains(
                "/customModelInstallTime/assets/customModel.tflite",
                "/customModelInstallTime/manifest/AndroidManifest.xml",
                "/customModelInstallTime/assets.pb",
                "/customModelFastFollow/assets/customModel.jax",
                "/customModelFastFollow/manifest/AndroidManifest.xml",
                "/customModelFastFollow/assets.pb",
                "/modelAdaptationOnDemand/assets/adaptation.lora",
                "/modelAdaptationOnDemand/manifest/AndroidManifest.xml",
                "/modelAdaptationOnDemand/assets.pb",
            )
        }

        val bundleFile = app.getBundle(BundleSelector.DEBUG)

        ZipFile(bundleFile).use { zip ->
            val appBundle = AppBundle.buildFromZip(zip)

            val splitsConfigBuilder = Config.SplitsConfig.newBuilder()
            splitsConfigBuilder
                .addSplitDimension(
                    Config.SplitDimension.newBuilder().setValue(Config.SplitDimension.Value.AI_MODEL_VERSION)
                        .setSuffixStripping(
                            Config.SuffixStripping.newBuilder()
                                .setEnabled(true)
                                .setDefaultSuffix("1")
                        )
                        .setNegate(false)
                )
                .build()
            assertThat(appBundle.bundleConfig.optimizations.splitsConfig)
                .isEqualTo(splitsConfigBuilder.build())

            // Bundletool treats AI packs as special types of asset packs.
            val moduleNames = appBundle.assetModules.keys.map { it.name }
            assertThat(moduleNames).containsExactly(
                "customModelInstallTime",
                "customModelFastFollow",
                "modelAdaptationOnDemand"
            )

            val customModelInstallTimeManifest =
                appBundle.assetModules[BundleModuleName.create("customModelInstallTime")]!!.androidManifest
            assertThat(customModelInstallTimeManifest.moduleType).isEqualTo(
                BundleModule.ModuleType.ASSET_MODULE
            )
            assertThat(customModelInstallTimeManifest.optionalModuleTypeAttributeValue).isEqualTo(
                Optional.of(MODULE_TYPE_AI_VALUE)
            )
            assertThat(customModelInstallTimeManifest.packageName).isEqualTo(packageName)
            assertThat(
                customModelInstallTimeManifest.manifestDeliveryElement.get()
                    .hasInstallTimeElement()
            )
                .isTrue()

            val customModelFastFollowManifest =
                appBundle.assetModules[BundleModuleName.create("customModelFastFollow")]!!.androidManifest
            assertThat(customModelFastFollowManifest.moduleType).isEqualTo(
                BundleModule.ModuleType.ASSET_MODULE
            )
            assertThat(customModelInstallTimeManifest.optionalModuleTypeAttributeValue).isEqualTo(
                Optional.of(MODULE_TYPE_AI_VALUE)
            )
            assertThat(customModelFastFollowManifest.packageName).isEqualTo(packageName)
            assertThat(
                customModelFastFollowManifest.manifestDeliveryElement.get()
                    .hasFastFollowElement()
            )
                .isTrue()

            val modelAdaptationOnDemandManifest =
                appBundle.assetModules[BundleModuleName.create("modelAdaptationOnDemand")]!!.androidManifest
            assertThat(modelAdaptationOnDemandManifest.moduleType).isEqualTo(
                BundleModule.ModuleType.ASSET_MODULE
            )
            assertThat(modelAdaptationOnDemandManifest.optionalModuleTypeAttributeValue).isEqualTo(
                Optional.of(MODULE_TYPE_AI_VALUE)
            )
            assertThat(modelAdaptationOnDemandManifest.packageName).isEqualTo(packageName)
            assertThat(
                modelAdaptationOnDemandManifest.manifestDeliveryElement.get()
                    .hasOnDemandElement()
            )
                .isTrue()
        }
    }
}
