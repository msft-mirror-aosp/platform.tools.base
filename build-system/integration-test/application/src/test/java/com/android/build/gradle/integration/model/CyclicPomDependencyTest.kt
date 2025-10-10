/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.model

import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.utils.disableBuiltInKotlin
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import org.gradle.api.Project
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.junit.Rule
import org.junit.Test

/** Regression test for b/232075280. */
class CyclicPomDependencyTest: ModelComparator() {

    abstract class TestCallback(
        private val libName: String,
        private val dependencyName: String
    ): GenericCallback {

        override fun handleProject(project: Project) {
            project.group = "com.foo"
            project.version = "1.0"

            val customPublishing = project.configurations.create("customPublishing")
            customPublishing.isCanBeConsumed = true
            customPublishing.isCanBeResolved = false
            customPublishing.attributes.attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                project.objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM)
            )
            project.dependencies.add("customPublishing", "com.foo:$dependencyName:1.0")
            customPublishing.outgoing.artifact(project.tasks.getByName("jar"))

            abstract class FactoryAccessor {
                @javax.inject.Inject
                abstract fun getFactory(): SoftwareComponentFactory
            }

            val factoryAccessor = project.objects.newInstance(FactoryAccessor::class.java)
            val component = factoryAccessor.getFactory().adhoc("custom")
            component.addVariantsFromConfiguration(customPublishing) {}
            project.components.add(component)

            project.tasks.withType(GenerateModuleMetadata::class.java) {
                it.enabled = false
            }

            val publishing = project.extensions.findByType(PublishingExtension::class.java)
                ?: throw RuntimeException("Could not find extension of type PublishingExtension")

            publishing.apply {
                repositories {
                    it.maven {
                        it.url = project.uri(project.projectDir.parentFile.resolve("repo"))
                    }
                }
                publications.create("mavenJava", MavenPublication::class.java) {
                    it.artifactId = libName
                    it.from(component)
                }
            }
        }
    }

    class Bar1Callback: TestCallback(libName = "bar1", dependencyName = "bar2")
    class Bar2Callback: TestCallback(libName = "bar2", dependencyName = "bar1")

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies {
                implementation("com.foo:bar1:1.0")
            }
        }
        genericProject(":bar1") {
            applyPlugin(PluginType.JAVA_LIBRARY)
            applyPlugin(PluginType.MAVEN_PUBLISH)
            pluginCallbacks += Bar1Callback::class.java
        }
        genericProject(":bar2") {
            applyPlugin(PluginType.JAVA_LIBRARY)
            applyPlugin(PluginType.MAVEN_PUBLISH)
            pluginCallbacks += Bar2Callback::class.java
        }
        settings {
            addRepository("repo")
        }
        gradleProperties {
            // b/308936442
            add(BooleanOption.PRIVACY_SANDBOX_SDK_SUPPORT, false)
        }
        disableBuiltInKotlin()
    }


    @Test
    fun `test models`() {
        rule.build.executor.run(":bar1:publish", ":bar2:publish")
        val result = rule.build
            .modelBuilder
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")

        with(result).compareVariantDependencies(goldenFile = "VariantDependencies")
    }
}
