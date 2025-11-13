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

package com.android.build.gradle.integration.dsl

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.DslExtension
import com.android.build.api.variant.VariantExtension
import com.android.build.api.variant.VariantExtensionConfig
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericComponentCallback
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import java.io.Serializable
import javax.inject.Inject


class ExtendingDslIntegrationTest {

    @get:Rule
    val project = GradleRule.from {
        androidLibrary {
            pluginCallbacks += ExtendingCallback::class.java
            android {
                viaExtension("custom", ProjectDslExtension::class) {
                    projectExt = "libProjectExt"
                }
                buildTypes {
                    named("debug") {
                        it.viaExtension("custom", BuildTypeDslExtension::class) {
                            buildTypeExt = "libBuildTypeExt"
                        }
                    }
                }
                flavorDimensions += "color"
                productFlavors {
                    create("blue") {
                        it.viaExtension("custom", ProductFlavorDslExtension::class) {
                            productFlavorExt = "libProductFlavorExt"
                        }
                    }
                    create("red") {

                    }
                }
            }
        }

        androidApplication {
            pluginCallbacks += ExtendingCallback::class.java
            android {
                viaExtension("custom", ProjectDslExtension::class) {
                    projectExt = "appProjectExt"
                }
                buildTypes {
                    named("debug") {
                        it.viaExtension("custom", BuildTypeDslExtension::class) {
                            buildTypeExt = "appBuildTypeExt"
                        }
                    }
                }
                flavorDimensions += "color"
                productFlavors {
                    create("blue") {
                        it.viaExtension("custom", ProductFlavorDslExtension::class) {
                            productFlavorExt = "appProductFlavorExt"
                        }
                    }
                    create("red") {

                    }
                }
            }
        }


        androidFeature {
            pluginCallbacks += ExtendingCallback::class.java
            android {
                viaExtension("custom", ProjectDslExtension::class) {
                    projectExt = "dynamicFeatureProjectExt"
                }
                buildTypes {
                    named("debug") {
                        it.viaExtension("custom", BuildTypeDslExtension::class) {
                            buildTypeExt = "dynamicFeatureBuildTypeExt"
                        }
                    }
                }
                flavorDimensions += "color"
                productFlavors {
                    create("blue") {
                        it.viaExtension("custom", ProductFlavorDslExtension::class) {
                            productFlavorExt = "dynamicFeatureProductFlavorExt"
                        }
                    }

                    create("red") {

                    }
                }
            }
        }

        androidTest {
            android {
                targetProjectPath = ":app"
            }
            pluginCallbacks += ExtendingCallback::class.java
            android {
                viaExtension("custom", ProjectDslExtension::class) {
                    projectExt = "testOnlyProjectExt"
                }
                buildTypes {
                    named("debug") {
                        it.viaExtension("custom", BuildTypeDslExtension::class) {
                            buildTypeExt = "testOnlyBuildTypeExt"
                        }
                    }
                }
                flavorDimensions += "color"
                productFlavors {
                    create("blue") {
                        it.viaExtension("custom", ProductFlavorDslExtension::class) {
                            productFlavorExt = "testOnlyProductFlavorExt"
                        }
                    }
                    create("red") {

                    }
                }
            }
        }
    }

    val expectedOutputs = listOf(
        ":app blueDebug: VariantDslExtension(projectExt=appProjectExt, buildTypeExt=appBuildTypeExt, productFlavorExt=[appProductFlavorExt])",
        ":app blueRelease: VariantDslExtension(projectExt=appProjectExt, buildTypeExt=, productFlavorExt=[appProductFlavorExt])",
        ":app redDebug: VariantDslExtension(projectExt=appProjectExt, buildTypeExt=appBuildTypeExt, productFlavorExt=[])",
        ":app redRelease: VariantDslExtension(projectExt=appProjectExt, buildTypeExt=, productFlavorExt=[])",
        ":feature blueDebug: VariantDslExtension(projectExt=dynamicFeatureProjectExt, buildTypeExt=dynamicFeatureBuildTypeExt, productFlavorExt=[dynamicFeatureProductFlavorExt])",
        ":feature blueRelease: VariantDslExtension(projectExt=dynamicFeatureProjectExt, buildTypeExt=, productFlavorExt=[dynamicFeatureProductFlavorExt])",
        ":feature redDebug: VariantDslExtension(projectExt=dynamicFeatureProjectExt, buildTypeExt=dynamicFeatureBuildTypeExt, productFlavorExt=[])",
        ":feature redRelease: VariantDslExtension(projectExt=dynamicFeatureProjectExt, buildTypeExt=, productFlavorExt=[])",
        ":lib blueDebug: VariantDslExtension(projectExt=libProjectExt, buildTypeExt=libBuildTypeExt, productFlavorExt=[libProductFlavorExt])",
        ":lib blueRelease: VariantDslExtension(projectExt=libProjectExt, buildTypeExt=, productFlavorExt=[libProductFlavorExt])",
        ":lib redDebug: VariantDslExtension(projectExt=libProjectExt, buildTypeExt=libBuildTypeExt, productFlavorExt=[])",
        ":lib redRelease: VariantDslExtension(projectExt=libProjectExt, buildTypeExt=, productFlavorExt=[])",
        ":test blueDebug: VariantDslExtension(projectExt=testOnlyProjectExt, buildTypeExt=testOnlyBuildTypeExt, productFlavorExt=[testOnlyProductFlavorExt])",
        ":test redDebug: VariantDslExtension(projectExt=testOnlyProjectExt, buildTypeExt=testOnlyBuildTypeExt, productFlavorExt=[])",
    )

    @Test
    fun checkOutputNewDslGroovy() {
        val build = project.build {
            buildFileType = BuildFileType.GROOVY
        }
        val result = build.executor.run("tasks")
        expectedOutputs.forEach {
            result.assertOutputContains(it)
        }
    }

    @Test
    fun checkOutputOldDslGroovy() {
        val build = project.build {
            buildFileType = BuildFileType.GROOVY
            gradleProperties {
                add(BooleanOption.USE_NEW_DSL, false)
            }
        }
        val result = build.executor.run("tasks")
        expectedOutputs.forEach {
            result.assertOutputContains(it)
        }
    }

    @Ignore("b/455844860 Currently viaExtension doesn't work with custom extensions defined in callbacks through this test framework in KTS")
    @Test
    fun checkOutputNewDslKts() {
        val build = project.build {
            buildFileType = BuildFileType.KTS
        }
        val result = build.executor.run("tasks")
        expectedOutputs.forEach {
            result.assertOutputContains(it)
        }
    }

    @Ignore("b/455844860 Currently viaExtension doesn't work with custom extensions defined in callbacks through this test framework in KTS")
    @Test
    fun checkOutputOldDslKts() {
        val build = project.build {
            buildFileType = BuildFileType.KTS
            gradleProperties {
                add(BooleanOption.USE_NEW_DSL, false)
            }
        }
        val result = build.executor.run("tasks")
        expectedOutputs.forEach {
            result.assertOutputContains(it)
        }
    }
}

class ExtendingCallback: GenericComponentCallback {
    override val useWithOldDsl: Boolean
        get() = true

    override fun handleExtension(
        project: Project,
        androidComponents: AndroidComponentsExtension<*, *, *>
    ) {
        androidComponents.registerExtension(
            DslExtension.Builder("custom")
            .extendProjectWith(ProjectDslExtension::class.java)
            .extendBuildTypeWith(BuildTypeDslExtension::class.java)
            .extendProductFlavorWith(ProductFlavorDslExtension::class.java)
            .build()) { config: VariantExtensionConfig<*> ->
            project.objects.newInstance(
                VariantDslExtension::class.java,
                config
            )
        }
        androidComponents.onVariants { variant ->
            println(project.path + " " + variant.name + ": " + variant.getExtension(VariantDslExtension::class.java))
        }
    }
}

interface ProjectDslExtension {
    var projectExt: String?
}

interface BuildTypeDslExtension {
    var buildTypeExt: String?
}
interface ProductFlavorDslExtension {
    var productFlavorExt: String?
}

abstract class VariantDslExtension @Inject constructor(
    extensionConfig: VariantExtensionConfig<*>): VariantExtension, Serializable {
    abstract val projectExt: Property<String>
    abstract val buildTypeExt: Property<String>
    abstract val productFlavorExt: ListProperty<String>

    init {
        projectExt.set(extensionConfig.projectExtension(ProjectDslExtension::class.java).projectExt ?: "")
        buildTypeExt.set(extensionConfig.buildTypeExtension(BuildTypeDslExtension::class.java).buildTypeExt ?: "")
        productFlavorExt.set(extensionConfig.productFlavorsExtensions(ProductFlavorDslExtension::class.java).map { it.productFlavorExt ?: "" })
    }

    override fun toString(): String {
        return "VariantDslExtension(" +
                "projectExt=${projectExt.getOrElse("")}, " +
                "buildTypeExt=${buildTypeExt.getOrElse("")}, " +
                "productFlavorExt=${productFlavorExt.getOrElse(listOf())}" +
                ")"
    }
}
