/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.utils.getVariantByName
import com.android.build.gradle.options.BooleanOption
import com.android.ide.common.build.ListingFileRedirect.getListingFile
import com.android.testutils.truth.PathSubject
import com.google.common.truth.Truth.assertAbout
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

/**
 * When the [BooleanOption.USE_NEW_DSL] flag is removed, also rename this test to
 * `ApplicationIdReset`, as the current [ApplicationIdReset] will have been deleted.
 */
class ApplicationIdResetUseNewDsl {

    @get:Rule
    val rule = GradleRule.configure().from {
        androidApplication {
            android {
                defaultConfig {
                    applicationId = "com.flavors.appidtest"
                }
                flavorDimensions += listOf("build", "price")
                productFlavors {
                    create("app1") { it.dimension = "build" }
                    create("app2") { it.dimension = "build" }
                    create("free") { it.dimension = "price" }
                    create("paid") { it.dimension = "price" }
                }
            }
            pluginCallbacks += Callback::class.java
        }
    }

    class Callback : ApplicationComponentCallback {

        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            androidComponents.apply {
                onVariants(selector().all()) { variant ->
                    val appId = "com.flavors." + when(variant.flavorName) {
                        "app1Free" -> "app1.free"
                        "app2Free" -> "app2.free"
                        "app1Paid" -> "app1.paid"
                        "app2Paid" -> "app2.paid"
                        else -> throw RuntimeException("Unknown variant flavorName: ${variant.flavorName}")
                    }
                    variant.applicationId.set(appId)
                }
            }
        }
    }

    @Test
    fun checkApplicationIdDebug() {
        rule.build.run {
            executor.run("assembleApp1FreeDebug")
            val androidProject = modelBuilder.fetchModels().container.getProject().androidProject!!
            val variant = androidProject.getVariantByName("app1FreeDebug")
            val listingFile = getListingFile(variant.mainArtifact.assembleTaskOutputListingFile!!)
            assertAbout(PathSubject.paths()).that(listingFile.toPath()).contains("""
                |  "applicationId": "com.flavors.app1.free"
            """.trimMargin("|"))
        }
    }
}
