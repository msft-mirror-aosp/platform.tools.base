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

package com.android.build.gradle.integration.model

import com.android.build.gradle.integration.common.fixture.ANDROIDX_VERSION
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import org.gradle.api.Project
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.junit.Rule
import org.junit.Test

/** Regression test for http://b/229298359. */
class DependencyWithoutFileWithDependenciesTest: ModelComparator() {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            dependencies{
                testImplementation("com.foo:bar:1.0") {
                    requireCapability("com.foo:bar-custom:1.0")
                }
            }
        }

        genericProject(":bar") {
            applyPlugin(PluginType.JAVA_LIBRARY)
            applyPlugin(PluginType.MAVEN_PUBLISH)

            group = "com.foo"
            version = "1.0"

            pluginCallbacks += TestCallback::class.java
        }

        settings {
            addRepository("repo")
        }
    }

    class TestCallback: GenericCallback {
        override fun handleProject(project: Project) {
            val customCapability = project.configurations.create("customCapability")
            customCapability.isCanBeConsumed = true
            customCapability.isCanBeResolved = false
            customCapability.attributes.attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                project.objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM)
            )
            customCapability.outgoing.capability("com.foo:bar-custom:1.0")
            project.dependencies.add("customCapability", "androidx.annotation:annotation:$ANDROIDX_VERSION")
            val javaComponent = project.components.getByName("java") as AdhocComponentWithVariants
            javaComponent.addVariantsFromConfiguration(customCapability) {
                it.mapToOptional()
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
                    it.from(javaComponent)
                }
            }
        }
    }

    @Test
    fun `test models`() {
        rule.build.executor.run(":bar:publish")
        val result = rule.build
            .modelBuilder
            .with(BooleanOption.USE_ANDROID_X, true)
            .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
            .fetchModels(variantName = "debug")
        //todo fix me
        with(result).compareVariantDependencies(goldenFile = "VariantDependencies")
    }
}
