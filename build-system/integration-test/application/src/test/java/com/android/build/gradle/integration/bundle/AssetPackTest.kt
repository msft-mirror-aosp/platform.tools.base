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

package com.android.build.gradle.integration.bundle

import com.android.build.gradle.integration.common.fixture.project.AabSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test

class AssetPackTest {

    @get:Rule
    val rule = GradleRule.from {
        androidJavaApplication {
            android {
                assetPacks += listOf(
                    ":assetPackOne",
                    ":assetPackTwo",
                    ":assetPackA",
                    ":assetPackAA",
                    ":assetPackA:assetPackAB"
                )
            }
        }
        assetPack(":assetPackOne") {
            assetPack {
                packName.set("assetPackOne")
                dynamicDelivery {
                    deliveryType.set("fast-follow")
                    instantDeliveryType.set("on-demand")
                }
            }
            files.add(
                "src/main/assets/assetFileOne.txt",
                """This is an asset file from asset pack one."""
            )
        }
        assetPack(":assetPackTwo") {
            assetPack {
                packName.set("assetPackTwo")
                dynamicDelivery {
                    deliveryType.set("fast-follow")
                }
            }
            files.add(
                "src/main/assets/assetFileTwo.txt",
                """This is an asset file from asset pack two."""
            )
        }
        assetPack(":assetPackA") {
            assetPack {
                packName.set("assetPackA")
                dynamicDelivery {
                    deliveryType.set("fast-follow")
                }
            }
            files.add(
                "src/main/assets/assetFileA.txt",
                """This is an asset file from asset pack A."""
            )
        }
        assetPack(":assetPackAA") {
            assetPack {
                packName.set("assetPackAA")
                dynamicDelivery {
                    deliveryType.set("fast-follow")
                }
            }
            files.add(
                "src/main/assets/assetFileAA.txt",
                """This is an asset file from asset pack AA."""
            )
        }
        assetPack(":assetPackA:assetPackAB") {
            assetPack {
                packName.set("assetPackAB")
                dynamicDelivery {
                    deliveryType.set("fast-follow")
                }
            }
            files.add(
                "src/main/assets/assetFileAB.txt",
                """This is an asset file from asset pack AB."""
            )
        }
    }

    @Test
    fun buildDebugBundle() {
        val build = rule.build
        build.executor.run(":app:bundleDebug")
        build.androidApplication().assertAab(AabSelector.DEBUG) {
            contains(
                "/assetPackOne/assets/assetFileOne.txt",
                "/assetPackOne/manifest/AndroidManifest.xml",
                "/assetPackOne/assets.pb",
                "/assetPackTwo/assets/assetFileTwo.txt",
                "/assetPackTwo/manifest/AndroidManifest.xml",
                "/assetPackTwo/assets.pb",
                "/assetPackA/assets/assetFileA.txt",
                "/assetPackA/manifest/AndroidManifest.xml",
                "/assetPackA/assets.pb",
                "/assetPackAA/assets/assetFileAA.txt",
                "/assetPackAA/manifest/AndroidManifest.xml",
                "/assetPackAA/assets.pb",
                "/assetPackAB/assets/assetFileAB.txt",
                "/assetPackAB/manifest/AndroidManifest.xml",
                "/assetPackAB/assets.pb"
            )
        }
    }
}
