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
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericComponentCallback
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
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

    val expectedOutputs = mapOf(
        ":app" to mapOf(
            "blueDebug" to "projectExt=appProjectExt, buildTypeExt=appBuildTypeExt, productFlavorExt=[appProductFlavorExt]",
            "blueRelease" to "projectExt=appProjectExt, buildTypeExt=, productFlavorExt=[appProductFlavorExt]",
            "redDebug" to "projectExt=appProjectExt, buildTypeExt=appBuildTypeExt, productFlavorExt=[]",
            "redRelease" to "projectExt=appProjectExt, buildTypeExt=, productFlavorExt=[]",
        ),
        ":feature" to mapOf(
            "blueDebug" to "projectExt=dynamicFeatureProjectExt, buildTypeExt=dynamicFeatureBuildTypeExt, productFlavorExt=[dynamicFeatureProductFlavorExt]",
            "blueRelease" to "projectExt=dynamicFeatureProjectExt, buildTypeExt=, productFlavorExt=[dynamicFeatureProductFlavorExt]",
            "redDebug" to "projectExt=dynamicFeatureProjectExt, buildTypeExt=dynamicFeatureBuildTypeExt, productFlavorExt=[]",
            "redRelease" to "projectExt=dynamicFeatureProjectExt, buildTypeExt=, productFlavorExt=[]",
        ),
        ":lib" to mapOf(
            "blueDebug" to "projectExt=libProjectExt, buildTypeExt=libBuildTypeExt, productFlavorExt=[libProductFlavorExt]",
            "blueRelease" to "projectExt=libProjectExt, buildTypeExt=, productFlavorExt=[libProductFlavorExt]",
            "redDebug" to "projectExt=libProjectExt, buildTypeExt=libBuildTypeExt, productFlavorExt=[]",
            "redRelease" to "projectExt=libProjectExt, buildTypeExt=, productFlavorExt=[]",
        ),
        ":test" to mapOf(
            "blueDebug" to "projectExt=testOnlyProjectExt, buildTypeExt=testOnlyBuildTypeExt, productFlavorExt=[testOnlyProductFlavorExt]",
            "redDebug" to "projectExt=testOnlyProjectExt, buildTypeExt=testOnlyBuildTypeExt, productFlavorExt=[]",
        )
    )

    @Test
    fun checkOutputNewDslGroovy() {
        validate(BuildFileType.GROOVY, oldDsl = false)
    }

    @Test
    fun checkOutputOldDslGroovy() {
        validate(BuildFileType.GROOVY, oldDsl = true)
    }

    @Test
    fun checkOutputNewDslKts() {
        validate(BuildFileType.KTS, oldDsl = false)
    }

    @Test
    fun checkOutputOldDslKts() {
        validate(BuildFileType.KTS, oldDsl = true)
    }

    private fun validate(fileType: BuildFileType, oldDsl: Boolean) {
        val build = project.build {
            buildFileType = fileType
            gradleProperties {
                if (oldDsl) {
                    add(BooleanOption.USE_NEW_DSL, false)
                }
            }
        }

        // run tasks to validate that the values read are the right ones
        // This is more reliable than println
        build.executor.run(
            "variant_output_task_for_blueDebug",
            "variant_output_task_for_blueRelease",
            "variant_output_task_for_redDebug",
            "variant_output_task_for_redRelease",
        )

        build.validateVariant(":app", "blueDebug")
        build.validateVariant(":app", "blueRelease")
        build.validateVariant(":app", "redDebug")
        build.validateVariant(":app", "redRelease")

        build.validateVariant(":feature", "blueDebug")
        build.validateVariant(":feature", "blueRelease")
        build.validateVariant(":feature", "redDebug")
        build.validateVariant(":feature", "redRelease")

        build.validateVariant(":lib", "blueDebug")
        build.validateVariant(":lib", "blueRelease")
        build.validateVariant(":lib", "redDebug")
        build.validateVariant(":lib", "redRelease")

        build.validateVariant(":test", "blueDebug")
        build.validateVariant(":test", "redDebug")
    }

    private fun GradleBuild.validateVariant(projectPath: String, variantName: String) {
        val project = subProject(projectPath)
        val blueDebug = project.buildDir.resolve("$variantName.txt")
        val expectedContent = expectedOutputs[projectPath]?.get(variantName)
            ?: throw RuntimeException("Unable to get expected content for [$projectPath][$variantName]")
        PathSubject.assertThat(blueDebug).hasContents(expectedContent)
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
            .build()
        ) { config: VariantExtensionConfig<*> ->
            project.objects.newInstance(VariantDslExtension::class.java, config)
        }
        androidComponents.onVariants { variant ->
            project.tasks.register("variant_output_task_for_${variant.name}", VariantOutputTask::class.java) {
                it.output.set(project.layout.buildDirectory.file("${variant.name}.txt"))
                it.variantInfo.set(variant.getExtension(VariantDslExtension::class.java))
            }
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
    extensionConfig: VariantExtensionConfig<*>
): VariantExtension, Serializable {
    @get:Input
    abstract val projectExt: Property<String>
    @get:Input
    abstract val buildTypeExt: Property<String>
    @get:Input
    abstract val productFlavorExt: ListProperty<String>

    init {
        projectExt.set(extensionConfig.projectExtension(ProjectDslExtension::class.java).projectExt ?: "")
        buildTypeExt.set(extensionConfig.buildTypeExtension(BuildTypeDslExtension::class.java).buildTypeExt ?: "")
        productFlavorExt.set(extensionConfig.productFlavorsExtensions(ProductFlavorDslExtension::class.java).map { it.productFlavorExt ?: "" })
    }
}

abstract class VariantOutputTask : DefaultTask() {

    @get:Nested
    abstract val variantInfo: Property<VariantDslExtension>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun action() {
        val variant = variantInfo.get()
        output.get().asFile.writeText(
            "projectExt=${variant.projectExt.getOrElse("")}, " +
                    "buildTypeExt=${variant.buildTypeExt.getOrElse("")}, " +
                    "productFlavorExt=${variant.productFlavorExt.getOrElse(listOf())}"
        )
    }
}
